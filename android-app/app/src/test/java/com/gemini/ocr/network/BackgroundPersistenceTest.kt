package com.gemini.ocr.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackgroundPersistenceTest {
    @Test fun diskFailureIsReportedWithoutFailingTheCaptureCoroutine() = runBlocking {
        var error: Exception? = null
        BackgroundPersistence.run({ throw IOException("fake full disk") }, { error = it })
        assertTrue(error is IOException)
    }
    @Test fun serviceCancellationIsPropagated() = runBlocking {
        var reported = false
        try {
            BackgroundPersistence.run({ throw CancellationException("stop") }, { reported = true })
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertFalse(reported)
    }
}
