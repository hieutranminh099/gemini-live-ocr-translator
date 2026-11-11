package com.gemini.ocr.capture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class LatestImageMailboxTest {
    private class FakeImage(val text: String, private val failClose: Boolean = false) : AutoCloseable {
        var closes = 0
            private set
        override fun close() {
            closes++
            if (failClose) throw IllegalStateException("Reader already closed")
        }
        fun copyPixels(): String {
            check(closes == 0) { "Image was closed during copy" }
            return text
        }
    }

    @Test fun newHardwareFrameReplacesBlankWhileOcrStillOwnsItsBitmap() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val blank = FakeImage("")
        mailbox.offer(source, blank, 100)
        val ocrOwnedBitmap = mailbox.withLatest(source) { it.image.copyPixels() }
        assertEquals("", ocrOwnedBitmap)
        // Native OCR is still working on the copied empty Bitmap. It has no ownership of Image.
        val next = FakeImage("还得是我兄弟")
        assertTrue(mailbox.offer(source, next, 120))
        assertEquals(1, blank.closes)
        assertEquals("还得是我兄弟", mailbox.withLatest(source) { it.image.copyPixels() })
        assertEquals("", ocrOwnedBitmap)
        mailbox.close()
        assertEquals(1, next.closes)
    }

    @Test fun staticScreenCanBeCopiedRepeatedlyWithoutConsumingItsImage() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val image = FakeImage("关键时候掉链子")
        mailbox.offer(source, image, 200)
        repeat(4) {
            assertEquals("关键时候掉链子", mailbox.withLatest(source) { it.image.copyPixels() })
        }
        assertEquals(0, image.closes)
        mailbox.close()
        assertEquals(1, image.closes)
    }

    @Test fun eachAcceptedImageCarriesItsOwnArrivalTimeAndSequence() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        mailbox.offer(source, FakeImage("A"), 100)
        assertEquals(1L to 100L, mailbox.withLatest(source) { it.sequence to it.acquiredAtMs })
        mailbox.offer(source, FakeImage("B"), 175)
        assertEquals(2L to 175L, mailbox.withLatest(source) { it.sequence to it.acquiredAtMs })
        mailbox.close()
    }

    @Test fun rotationRejectsOldReaderArrivalAndItsLateCleanupCannotCloseNewImage() {
        val first = Any()
        val second = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(first)
        val old = FakeImage("old dimensions")
        mailbox.offer(first, old, 100)
        mailbox.activate(second)
        assertEquals(1, old.closes)
        assertNull(mailbox.withLatest(first) { it.image.copyPixels() })
        val current = FakeImage("new dimensions")
        mailbox.offer(second, current, 150)
        val late = FakeImage("obsolete callback")
        assertFalse(mailbox.offer(first, late, 160))
        mailbox.deactivate(first)
        assertEquals(1, late.closes)
        assertEquals(0, current.closes)
        assertEquals("new dimensions", mailbox.withLatest(second) { it.image.copyPixels() })
        assertEquals(2L, mailbox.withLatest(second) { it.sequence })
        mailbox.close()
    }

    @Test fun readerIdentityCannotBeForgedByEqualObjects() {
        data class Reader(val id: Int)
        val first = Reader(1)
        val equalButDifferent = Reader(1)
        val mailbox = LatestImageMailbox<Reader, FakeImage>()
        mailbox.activate(first)
        val image = FakeImage("late")
        assertFalse(mailbox.offer(equalButDifferent, image, 100))
        assertEquals(1, image.closes)
        assertNull(mailbox.withLatest(equalButDifferent) { it.image.copyPixels() })
        mailbox.close()
    }

    @Test fun deactivationAndDestroyCloseEachTransferredResourceExactlyOnce() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val first = FakeImage("A")
        mailbox.offer(source, first, 100)
        mailbox.deactivate(source)
        mailbox.deactivate(source)
        assertEquals(1, first.closes)
        val late = FakeImage("late")
        assertFalse(mailbox.offer(source, late, 110))
        assertEquals(1, late.closes)
        mailbox.close()
        mailbox.close()
        val afterDestroy = FakeImage("destroyed")
        assertFalse(mailbox.offer(source, afterDestroy, 120))
        assertEquals(1, afterDestroy.closes)
        assertNull(mailbox.withLatest(source) { it.image.copyPixels() })
    }

    @Test fun activatingCurrentReaderAndRepeatOfferDoNotInvalidateRetainedImage() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val image = FakeImage("A")
        mailbox.offer(source, image, 100)
        mailbox.activate(source)
        assertFalse(mailbox.offer(source, image, 110))
        assertEquals(0, image.closes)
        assertEquals(1L to 100L, mailbox.withLatest(source) { it.sequence to it.acquiredAtMs })
        mailbox.close()
        assertEquals(1, image.closes)
    }

    @Test fun failedCloseDoesNotLoseTheReplacementOrLeakTheCurrentOwnership() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val first = FakeImage("A", failClose = true)
        val next = FakeImage("B")
        mailbox.offer(source, first, 100)
        assertTrue(mailbox.offer(source, next, 110))
        assertEquals(1, first.closes)
        assertEquals("B", mailbox.withLatest(source) { it.image.copyPixels() })
        mailbox.close()
        assertEquals(1, next.closes)
    }

    @Test fun copyFailureLeavesRetainedFrameAvailableForAnotherAttempt() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val image = FakeImage("A")
        mailbox.offer(source, image, 100)
        assertThrows(IllegalArgumentException::class.java) {
            mailbox.withLatest(source) { throw IllegalArgumentException("Bitmap copy failed") }
        }
        assertEquals("A", mailbox.withLatest(source) { it.image.copyPixels() })
        mailbox.close()
        assertEquals(1, image.closes)
    }

    @Test fun replacementWaitsForSynchronousPixelCopyButNotForSubsequentOcr() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val first = FakeImage("A")
        val next = FakeImage("B")
        mailbox.offer(source, first, 100)
        val copying = CountDownLatch(1)
        val finishCopy = CountDownLatch(1)
        val offering = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val copy = pool.submit<String> {
                mailbox.withLatest(source) {
                    copying.countDown()
                    assertTrue(finishCopy.await(2, TimeUnit.SECONDS))
                    it.image.copyPixels()
                }!!
            }
            assertTrue(copying.await(2, TimeUnit.SECONDS))
            val publish = pool.submit<Boolean> {
                offering.countDown()
                mailbox.offer(source, next, 110)
            }
            assertTrue(offering.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { publish.get(50, TimeUnit.MILLISECONDS) }
            assertEquals(0, first.closes)
            finishCopy.countDown()
            assertEquals("A", copy.get(2, TimeUnit.SECONDS))
            assertTrue(publish.get(2, TimeUnit.SECONDS))
            assertEquals(1, first.closes)
            assertEquals("B", mailbox.withLatest(source) { it.image.copyPixels() })
        } finally {
            finishCopy.countDown()
            pool.shutdownNow()
            mailbox.close()
        }
        assertEquals(1, next.closes)
    }

    @Test fun destroyReleasesImageButLeavesPreviouslyCopiedBitmapDataUsable() {
        val source = Any()
        val mailbox = LatestImageMailbox<Any, FakeImage>()
        mailbox.activate(source)
        val image = FakeImage("A")
        mailbox.offer(source, image, 100)
        val copiedBitmap = mailbox.withLatest(source) { it.image.copyPixels() }
        mailbox.close()
        assertEquals(1, image.closes)
        assertEquals("A", copiedBitmap)
        assertThrows(IllegalStateException::class.java) { mailbox.activate(Any()) }
    }

    @Test fun retainedImageLeavesTwoAcquireSlotsForLatestImageBacklogDiscard() {
        // Android's acquireLatestImage requires maxImages - currentAcquiredImages >= 2.
        // This tests our capacity contract without pretending to execute the native ImageReader.
        val retainedImages = 1
        assertEquals(2, LatestImageMailbox.REQUIRED_MAX_IMAGES - retainedImages)
        assertTrue(2 - retainedImages < 2) // Previous maxImages=2 cannot satisfy that contract.
    }
}
