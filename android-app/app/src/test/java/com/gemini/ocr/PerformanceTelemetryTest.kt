package com.gemini.ocr

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PerformanceTelemetryTest {
    private class Fixture {
        var now = 0L
        var cpu = 10L
        var pss = 1024L
        var calls = 0
        val telemetry = PerformanceTelemetry({ now }) {
            calls++
            PerformanceProcessCounters(cpu, now, pss)
        }
        fun tick(delta: Long = 1000L): PerformanceSnapshot {
            now += delta
            assertTrue(telemetry.sampleIfDue())
            return telemetry.snapshot.value
        }
    }

    @Test fun disabledTelemetryDoesNotProbeResourcesOrRetainEvents() {
        val f = Fixture()
        f.telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED)
        f.telemetry.recordCacheLookups(PerformanceCacheTier.REVIEWED_MEMORY, 3, 2)
        f.telemetry.recordVisibleTranslation(PerformanceTranslationSource.GEMINI)
        f.now = 10_000
        assertFalse(f.telemetry.sampleIfDue())
        assertEquals(0, f.calls)
        assertEquals(PerformanceSnapshot(), f.telemetry.snapshot.value)
        f.telemetry.setEnabled(true)
        val sample = f.tick()
        assertEquals(0L, sample.frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).completed)
        assertEquals(PerformanceCacheMetric(0, 0), sample.caches.getValue(PerformanceCacheTier.REVIEWED_MEMORY))
        assertNull(sample.lastVisibleTranslationSource)
    }

    @Test fun sampleReservationIsAtMostOneHzIncludingRepeatedEarlyCalls() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        f.now = 999
        repeat(20) { assertFalse(f.telemetry.sampleIfDue()) }
        assertEquals(0, f.calls)
        f.now = 1000
        assertTrue(f.telemetry.sampleIfDue())
        repeat(20) { assertFalse(f.telemetry.sampleIfDue()) }
        f.now = 1999
        assertFalse(f.telemetry.sampleIfDue())
        assertEquals(1, f.calls)
        f.tick(1)
        assertEquals(2, f.calls)
    }

    @Test fun delayedTicksUseActualElapsedTimeAndKeepStagesIndependent() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        repeat(12) { f.telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED) }
        repeat(4) { f.telemetry.recordFrame(PerformanceFrameStage.OCR_COMPLETED) }
        f.telemetry.recordFrame(PerformanceFrameStage.CANVAS_DRAWN)
        val first = f.tick(2000)
        assertEquals(6.0, first.frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).perSecond!!, 0.0)
        assertEquals(2.0, first.frames.getValue(PerformanceFrameStage.OCR_COMPLETED).perSecond!!, 0.0)
        assertEquals(.5, first.frames.getValue(PerformanceFrameStage.CANVAS_DRAWN).perSecond!!, 0.0)
        val idle = f.tick(3000)
        assertEquals(12L, idle.frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).completed)
        assertEquals(0.0, idle.frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).perSecond!!, 0.0)
        assertEquals(3000L, idle.windowMs)
    }

    @Test fun frameHooksDoNotPublishOrCallSampler() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        val before = f.telemetry.snapshot.value
        repeat(2000) { f.telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED) }
        assertSame(before, f.telemetry.snapshot.value)
        assertEquals(0, f.calls)
        assertEquals(2000L, f.tick().frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).completed)
    }

    @Test fun cpuNeedsTwoValidSamplesAndCanExceedOneCore() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        val first = f.tick()
        assertNull(first.cpuOneCorePercent)
        assertNull(first.cpuWindowMs)
        f.cpu += 1500
        val second = f.tick()
        assertEquals(150.0, second.cpuOneCorePercent!!, 0.0)
        assertEquals(1000L, second.cpuWindowMs)
        assertEquals(1024L, second.pssKiB)
    }

    @Test fun cpuCounterResetDoesNotProduceNegativeCpuAndReestablishesBaseline() {
        val f = Fixture(); f.cpu = 1000; f.telemetry.setEnabled(true); f.tick()
        f.cpu = 2
        assertNull(f.tick().cpuOneCorePercent)
        f.cpu = 102
        assertEquals(10.0, f.tick().cpuOneCorePercent!!, 0.0)
    }

    @Test fun unknownResourceValuesRemainUnknownAndDoNotBecomeZero() {
        var now = 0L
        val telemetry = PerformanceTelemetry({ now }) { PerformanceProcessCounters() }
        telemetry.setEnabled(true); now = 1000
        assertTrue(telemetry.sampleIfDue())
        assertNull(telemetry.snapshot.value.cpuOneCorePercent)
        assertNull(telemetry.snapshot.value.pssKiB)
        assertFalse(telemetry.snapshot.value.resourceSampleFailed)
    }

    @Test fun invalidCpuTimestampsAndNegativeMemoryCannotProduceMisleadingMeasurements() {
        var now = 0L
        var counters = PerformanceProcessCounters(1, 2000, -1)
        val telemetry = PerformanceTelemetry({ now }) { counters }
        telemetry.setEnabled(true); now = 1000
        assertTrue(telemetry.sampleIfDue())
        assertNull(telemetry.snapshot.value.pssKiB)
        counters = PerformanceProcessCounters(10, 2000, 123)
        now = 2000
        assertTrue(telemetry.sampleIfDue())
        assertNull(telemetry.snapshot.value.cpuOneCorePercent) // Future first timestamp was not a baseline.
        counters = PerformanceProcessCounters(-1, 3000, 123)
        now = 3000
        assertTrue(telemetry.sampleIfDue())
        assertNull(telemetry.snapshot.value.cpuOneCorePercent)
        counters = PerformanceProcessCounters(100, 4000, 123)
        now = 4000
        assertTrue(telemetry.sampleIfDue())
        assertNull(telemetry.snapshot.value.cpuOneCorePercent) // Invalid CPU counter cleared the baseline.
    }

    @Test fun slowResourceSamplingIsIncludedInFrameRateWindow() {
        var now = 0L
        val telemetry = PerformanceTelemetry({ now }) {
            val cpuAt = now
            now += 500L
            PerformanceProcessCounters(10, cpuAt, 100)
        }
        telemetry.setEnabled(true)
        repeat(3) { telemetry.recordFrame(PerformanceFrameStage.OCR_COMPLETED) }
        now = 1000L
        assertTrue(telemetry.sampleIfDue())
        val sample = telemetry.snapshot.value
        assertEquals(1500L, sample.windowMs)
        assertEquals(2.0, sample.frames.getValue(PerformanceFrameStage.OCR_COMPLETED).perSecond!!, 0.0)
    }

    @Test fun failedResourceSamplerDoesNotBreakFrameRatesOrExposeExceptionText() {
        var now = 0L
        val telemetry = PerformanceTelemetry({ now }) { error("sensitive platform exception") }
        telemetry.setEnabled(true)
        telemetry.recordFrame(PerformanceFrameStage.OCR_COMPLETED)
        now = 1000
        assertTrue(telemetry.sampleIfDue())
        val sample = telemetry.snapshot.value
        assertTrue(sample.resourceSampleFailed)
        assertEquals(1.0, sample.frames.getValue(PerformanceFrameStage.OCR_COMPLETED).perSecond!!, 0.0)
        assertNull(sample.cpuOneCorePercent)
        assertFalse(sample.toString().contains("sensitive"))
    }

    @Test fun cacheDenominatorCountsOnlyCompletedAttemptsInTheSameTier() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        f.telemetry.recordCacheLookups(PerformanceCacheTier.REVIEWED_MEMORY, 3, 1)
        f.telemetry.recordCacheLookups(PerformanceCacheTier.REVIEWED_MEMORY, 2, 4)
        f.telemetry.recordCacheLookups(PerformanceCacheTier.LEGACY_BANK, 1, 0)
        val sample = f.tick()
        assertEquals(.5, sample.caches.getValue(PerformanceCacheTier.REVIEWED_MEMORY).hitRate!!, 0.0)
        assertEquals(1.0, sample.caches.getValue(PerformanceCacheTier.LEGACY_BANK).hitRate!!, 0.0)
        assertNull(sample.caches.getValue(PerformanceCacheTier.PHONE_EXACT).hitRate)
        assertEquals("source_line", PerformanceCacheTier.REVIEWED_MEMORY.lookupUnit)
        assertEquals("request_batch", PerformanceCacheTier.LEGACY_BANK.lookupUnit)
    }

    @Test fun negativeLookupCountsAreRejectedBeforeTheyCanCorruptRatio() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        assertThrows(IllegalArgumentException::class.java) {
            f.telemetry.recordCacheLookups(PerformanceCacheTier.PHONE_EXACT, -1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.telemetry.recordCacheLookups(PerformanceCacheTier.PHONE_EXACT, 1, -1)
        }
        assertNull(f.tick().caches.getValue(PerformanceCacheTier.PHONE_EXACT).hitRate)
    }

    @Test fun onlyExplicitVisibleTranslationEventChangesItsOrigin() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        f.telemetry.recordFrame(PerformanceFrameStage.CANVAS_DRAWN)
        assertNull(f.tick().lastVisibleTranslationSource)
        f.telemetry.recordVisibleTranslation(PerformanceTranslationSource.ON_DEVICE)
        assertEquals(PerformanceTranslationSource.ON_DEVICE, f.tick().lastVisibleTranslationSource)
        f.telemetry.recordVisibleTranslation(PerformanceTranslationSource.REVIEWED_MEMORY)
        assertEquals(PerformanceTranslationSource.REVIEWED_MEMORY, f.tick().lastVisibleTranslationSource)
    }

    @Test fun disableClearsSnapshotAndReenableDoesNotCountPriorSession() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        repeat(5) { f.telemetry.recordFrame(PerformanceFrameStage.OCR_COMPLETED) }; f.tick()
        f.telemetry.setEnabled(false)
        assertEquals(PerformanceSnapshot(), f.telemetry.snapshot.value)
        f.telemetry.setEnabled(true)
        f.telemetry.setEnabled(true) // Recomposition/repeated settings callbacks must not reset totals.
        f.telemetry.recordFrame(PerformanceFrameStage.OCR_COMPLETED)
        assertEquals(1L, f.tick().frames.getValue(PerformanceFrameStage.OCR_COMPLETED).completed)
        assertNull(f.telemetry.snapshot.value.cpuOneCorePercent)
    }

    @Test fun clockRollbackReanchorsRatesAndCpuWithoutAnImmediateSample() {
        val f = Fixture(); f.telemetry.setEnabled(true); f.tick()
        f.telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED)
        f.now = 20
        assertFalse(f.telemetry.sampleIfDue())
        assertEquals(1, f.calls)
        f.telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED)
        val sample = f.tick()
        assertEquals(1.0, sample.frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).perSecond!!, 0.0)
        assertNull(sample.cpuOneCorePercent)
    }

    @Test fun parallelFrameHooksDoNotLoseIncrements() {
        val f = Fixture(); f.telemetry.setEnabled(true)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 4).map { workers.submit {
                repeat(1000) { f.telemetry.recordFrame(PerformanceFrameStage.CAPTURE_ACQUIRED) }
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(4000L, f.tick().frames.getValue(PerformanceFrameStage.CAPTURE_ACQUIRED).completed)
        } finally { workers.shutdownNow() }
    }

    @Test fun inFlightSamplerCannotPublishAcrossAnOptInSessionChangeOrRunConcurrently() {
        val now = java.util.concurrent.atomic.AtomicLong()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        val telemetry = PerformanceTelemetry(now::get) {
            calls.incrementAndGet(); entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            PerformanceProcessCounters(1, now.get(), 10)
        }
        telemetry.setEnabled(true); now.set(1000)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> { telemetry.sampleIfDue() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            telemetry.setEnabled(false); telemetry.setEnabled(true)
            now.set(2000)
            assertFalse(telemetry.sampleIfDue())
            assertEquals(1, calls.get())
            finish.countDown()
            assertFalse(pending.get(5, TimeUnit.SECONDS))
            assertEquals(PerformanceSnapshot(enabled = true), telemetry.snapshot.value)
            assertTrue(telemetry.sampleIfDue())
            assertEquals(2, calls.get())
        } finally { finish.countDown(); worker.shutdownNow() }
    }
}
