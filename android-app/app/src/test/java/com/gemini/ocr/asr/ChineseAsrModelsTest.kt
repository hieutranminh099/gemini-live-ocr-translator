package com.gemini.ocr.asr

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChineseAsrModelsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingModelsRemainExplicitlyUnavailableAndStatusDoesNotStartDownload() {
        val manager = manager()
        repeat(3) {
            val status = manager.status()
            assertFalse(status.ready)
            assertFalse(status.downloading)
            assertEquals(240193589L, status.totalBytes)
            assertEquals(0L, status.downloadedBytes)
        }
        assertFalse(manager.directory.exists())
    }

    @Test fun fileVerificationRejectsSameLengthCorruptionAndTruncatedDownloads() {
        val file = temporary.newFile("model.onnx")
        val spec = AsrModelFile("model.onnx", 3,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "https://example.invalid/model")
        file.writeText("abc")
        assertTrue(ChineseAsrModels.verifyFile(file, spec))
        file.writeText("abd")
        assertFalse(ChineseAsrModels.verifyFile(file, spec))
        file.writeText("ab")
        assertFalse(ChineseAsrModels.verifyFile(file, spec))
    }

    @Test fun invalidOfflinePackageNeverBecomesReadyOrLeavesAnIncomingDirectory() = runBlocking {
        val manager = manager()
        val source = temporary.newFolder("incomplete")
        File(source, "model.int8.onnx").writeText("not real model weights")
        try { manager.importVerifiedFiles(source); fail("Invalid package must fail") } catch (_: IOException) { }
        assertFalse(manager.status().ready)
        assertFalse(manager.status().downloading)
        assertFalse(manager.directory.exists())
        assertTrue(manager.directory.parentFile!!.listFiles().orEmpty().none { it.name.contains("incoming-") })
        assertTrue(File(source, "model.int8.onnx").isFile)
    }

    @Test fun removalRefusesUnknownFilesAndLeavesOtherPackagesUntouched() = runBlocking {
        val manager = manager()
        assertTrue(manager.directory.mkdirs())
        val unexpected = File(manager.directory, "not-owned.txt").apply { writeText("preserve") }
        val neighbor = File(manager.directory.parentFile, "other-model").apply { mkdir() }
        val neighborFile = File(neighbor, "weights.bin").apply { writeText("other package") }
        try { manager.remove(); fail("Unexpected files must be preserved") } catch (_: IllegalStateException) { }
        assertEquals("preserve", unexpected.readText())
        assertEquals("other package", neighborFile.readText())
    }

    private fun manager(): ChineseAsrModels {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val folder = temporary.newFolder()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = folder
        }
        return ChineseAsrModels(context)
    }
}
