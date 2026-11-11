package com.gemini.ocr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OwnedPublicScreenRecordingTest {
    private val path = "/storage/emulated/0/Android/data/com.gemini.ocr.codexbench/files/verification/public-run.mp4"
    @Test fun onlyASinglePositiveProcessIdCanBeOwned() {
        assertEquals(1234, OwnedPublicScreenRecording.parsePid("1234\n"))
        for (invalid in listOf("0", "-1", "1; echo anything", "12\n13", "99999999", "")) {
            assertThrows(IllegalArgumentException::class.java) { OwnedPublicScreenRecording.parsePid(invalid) }
        }
    }
    @Test fun reusedOrDifferentRecordingProcessCannotBeStopped() {
        assertTrue(OwnedPublicScreenRecording.owns("screenrecord", listOf("/system/bin/screenrecord", "--time-limit", "50", path), path))
        assertFalse(OwnedPublicScreenRecording.owns("other", listOf("other", path), path))
        assertFalse(OwnedPublicScreenRecording.owns("screenrecord", listOf("screenrecord", path + ".different"), path))
        assertFalse(OwnedPublicScreenRecording.owns("screenrecord", emptyList(), path))
    }
    @Test fun shellArgumentsAreFixedAndOutputStaysInTheVerificationDirectory() {
        assertTrue(OwnedPublicScreenRecording.launchCommand(File(path), 50).contains("--time-limit 50"))
        for (invalid in listOf("/sdcard/other.mp4", path.replace("public-run", "bad;name"), path.replace("public-run", "bad name"))) {
            assertThrows(IllegalArgumentException::class.java) { OwnedPublicScreenRecording.launchCommand(File(invalid), 50) }
        }
        assertThrows(IllegalArgumentException::class.java) { OwnedPublicScreenRecording.launchCommand(File(path), 0) }
    }
}
