package com.gemini.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PublicYouTubeScreenshotCommandTest {
    @Test fun isolatedCaptureHasABoundedLifetimeAndWritesOnlyItsOwnVerificationFile() {
        val path = "/storage/emulated/0/Android/data/com.gemini.ocr.codexbench/files/verification/.codex-youtube-roi-123.png"
        assertEquals("timeout -k 1 5 screencap -p $path", publicYouTubeScreenshotCommand(File(path)))
    }

    @Test fun anotherAppsFolderAndParentTraversalAreRejected() {
        rejects("/storage/emulated/0/Android/data/com.google.android.youtube/files/verification/output.png")
        rejects("/storage/emulated/0/Android/data/com.gemini.ocr.codexbench/files/verification/../private.png")
    }

    @Test fun argumentsAndShellMetacharactersCannotChangeTheCaptureCommand() {
        rejects("/sdcard/Android/data/com.gemini.ocr.codexbench/files/verification/output name.png")
        rejects("/sdcard/Android/data/com.gemini.ocr.codexbench/files/verification/output;id.png")
    }

    private fun rejects(path: String) {
        try {
            publicYouTubeScreenshotCommand(File(path))
            fail("Only a generated PNG in the disposable app's verification directory is allowed")
        } catch (_: IllegalArgumentException) { }
    }
}
