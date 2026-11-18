package com.gemini.ocr.video

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoArtifactPublishTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedPublishKeepsPreviousFileAndCompleteReplacementIsVisible() {
        val target = temp.newFile("translated.srt").apply { writeText("Bản trước") }
        val partial = File(temp.root, "pending.srt")
        assertTrue(runCatching { publishVideoArtifact(partial, target) }.isFailure)
        assertEquals("Bản trước", target.readText())
        partial.writeText("Bản hoàn chỉnh")
        publishVideoArtifact(partial, target)
        assertFalse(partial.exists())
        assertEquals("Bản hoàn chỉnh", target.readText())
    }
}
