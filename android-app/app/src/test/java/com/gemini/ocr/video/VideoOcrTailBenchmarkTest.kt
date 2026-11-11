package com.gemini.ocr.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class VideoOcrTailBenchmarkTest {
    @Test fun tailReusePreservesRetainedSamplesAndRejectsOlderAndEqualTimestamps() {
        val tail = VideoOcrTail()
        fun capture(ms: Long, retained: Boolean, pattern: Int, rotation: Int = 90): VideoOcrFrame {
            val plane = ByteBuffer.wrap(ByteArray(40) { (it + pattern).toByte() })
            plane.position(2)
            val frame = VideoLumaCrop.sample(ms, plane, 10, 2, 1, 1, 3, 2, FULL_ROI, rotation,
                tail.reusableLuma(ms, retained))
            val fresh = VideoLumaCrop.sample(ms, plane, 10, 2, 1, 1, 3, 2, FULL_ROI, rotation)
            assertArrayEquals(fresh.luma, frame.luma)
            assertEquals(2, plane.position())
            tail.record(frame, retained)
            return frame
        }

        val first = capture(0, retained = true, pattern = 0)
        val firstBytes = first.luma.copyOf()
        val scratch = capture(33, retained = false, pattern = 1).luma
        val second = capture(66, retained = true, pattern = 2)
        val secondBytes = second.luma.copyOf()
        assertNotSame(scratch, second.luma)
        val latest = capture(99, retained = false, pattern = 3, rotation = 270)
        assertSame(scratch, latest.luma)
        val latestBytes = latest.luma.copyOf()

        assertNotSame(scratch, capture(80, retained = false, pattern = 4).luma)
        assertNotSame(scratch, capture(99, retained = false, pattern = 5).luma)
        assertSame(latest, tail.frame)
        assertArrayEquals(latestBytes, tail.frame!!.luma)
        assertArrayEquals(firstBytes, first.luma)
        assertArrayEquals(secondBytes, second.luma)

        val resized = VideoLumaCrop.sample(110, ByteBuffer.wrap(ByteArray(20)), 5, 1,
            0, 0, 4, 3, FULL_ROI, 0, tail.reusableLuma(110, retained = false))
        assertNotSame(scratch, resized.luma)
        tail.record(resized, retained = false)
        assertSame(resized.luma, tail.reusableLuma(120, retained = false))
        tail.clear()
        assertNull(tail.frame)
        assertNull(tail.reusableLuma(121, retained = false))
    }

    /** Isolated JVM Y-plane copy/ownership measurement, with no media decode, OCR or API work. */
    @Test fun sameSyntheticFramesHaveExactOutputAndFewerLumaAllocationsForOneThreeFiveTenEpisodes() {
        val fixture = Fixture()
        val cpu = hostThreadCpu()
        repeat(3) {
            fixture.run(3, reuse = false, verify = false, cpu = cpu)
            fixture.run(3, reuse = true, verify = false, cpu = cpu)
        }

        val output = StringBuilder()
        output.appendLine("episodes,eligible_frames,retained_samples,fresh_arrays_before,fresh_arrays_after," +
            "luma_bytes_before,luma_bytes_after,median_wall_ns_before,median_wall_ns_after," +
            "median_host_thread_cpu_ns_before,median_host_thread_cpu_ns_after")
        for (episodes in listOf(1, 3, 5, 10)) {
            val before = fixture.run(episodes, reuse = false, verify = true, cpu = cpu)
            val after = fixture.run(episodes, reuse = true, verify = true, cpu = cpu)
            assertEquals(before.digest, after.digest)
            assertEquals(before.checksum, after.checksum)
            assertEquals(60 * episodes, before.retained)
            assertEquals(before.retained, after.retained)
            assertEquals(120 * episodes, before.arrays)
            assertEquals(61 * episodes, after.arrays)

            val beforeRuns = mutableListOf<Run>()
            val afterRuns = mutableListOf<Run>()
            // Alternate A/B and B/A so a single warm-up order does not dominate every pair.
            repeat(5) { sample ->
                for (reused in listOf(sample % 2 != 0, sample % 2 == 0)) {
                    val result = fixture.run(episodes, reuse = reused, verify = false, cpu = cpu)
                    assertEquals(before.checksum, result.checksum)
                    (if (reused) afterRuns else beforeRuns).add(result)
                }
            }
            fun median(values: List<Long>) = values.sorted().let { it.getOrNull(it.size / 2) }
            output.appendLine(listOf(episodes, 120 * episodes, before.retained, before.arrays, after.arrays,
                before.bytes, after.bytes, median(beforeRuns.map { it.wallNs }), median(afterRuns.map { it.wallNs }),
                median(beforeRuns.mapNotNull { it.cpuNs }) ?: "", median(afterRuns.mapNotNull { it.cpuNs }) ?: "")
                .joinToString(","))
        }
        System.getProperty("gemini.test.screenshots")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "batch-ocr-tail-luma-jvm.csv").writeText(output.toString())
        }
    }

    private class Fixture {
        private val roi = listOf(.05f, .72f, .95f, .95f)
        private val planes = List(4) { pattern ->
            ByteBuffer.wrap(ByteArray(672 * 360) { (it * 13 + pattern * 47).toByte() })
        }

        fun run(episodes: Int, reuse: Boolean, verify: Boolean, cpu: () -> Long?): Run {
            val digest = if (verify) MessageDigest.getInstance("SHA-256") else null
            var arrays = 0
            var bytes = 0L
            var retained = 0
            var checksum = 0L
            val cpuStart = cpu()
            val start = System.nanoTime()
            repeat(episodes) { episode ->
                val tail = if (reuse) VideoOcrTail() else null
                var oldTail: VideoOcrFrame? = null
                val ring = ArrayDeque<VideoOcrFrame>()
                var lastSample = -50L
                repeat(120) { index ->
                    val ms = index * 1000L / 30
                    val retain = ms - lastSample >= 50
                    val scratch = tail?.reusableLuma(ms, retain)
                    val frame = VideoLumaCrop.sample(ms, planes[(index + episode) % planes.size],
                        672, 1, 0, 0, 640, 360, roi, 0, scratch)
                    if (frame.luma !== scratch) { arrays++; bytes += frame.luma.size }
                    if (tail != null) tail.record(frame, retain)
                    else if (ms > (oldTail?.ms ?: -1)) oldTail = frame
                    if (retain) {
                        ring.addLast(frame)
                        if (ring.size > 6) ring.removeFirst()
                        lastSample = ms
                        retained++
                        digest?.let { it.update(frame.ms.toString().toByteArray()); it.update(frame.luma) }
                    }
                    checksum += ring.first().luma[index % ring.first().luma.size].toLong()
                }
                val final = checkNotNull(tail?.frame ?: oldTail)
                checksum += final.ms + final.luma.last().toLong()
                digest?.let { it.update(final.ms.toString().toByteArray()); it.update(final.luma) }
            }
            val wallNs = System.nanoTime() - start
            val cpuNs = cpu()?.let { end -> cpuStart?.let { end - it } }?.takeIf { it >= 0 }
            return Run(arrays, bytes, retained, checksum, wallNs, cpuNs, digest?.digest()?.toList())
        }
    }

    private data class Run(val arrays: Int, val bytes: Long, val retained: Int, val checksum: Long,
        val wallNs: Long, val cpuNs: Long?, val digest: List<Byte>?)

    private fun hostThreadCpu(): () -> Long? = runCatching {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val bean = factory.getMethod("getThreadMXBean").invoke(null)
        val type = Class.forName("java.lang.management.ThreadMXBean")
        val time = type.getMethod("getCurrentThreadCpuTime")
        val reader: () -> Long? = { runCatching { (time.invoke(bean) as Long).takeIf { it >= 0 } }.getOrNull() }
        reader
    }.getOrDefault { null }

    private companion object {
        val FULL_ROI = listOf(0f, 0f, 1f, 1f)
    }
}
