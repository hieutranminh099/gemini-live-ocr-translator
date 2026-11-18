package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.batch.BatchItem
import com.gemini.ocr.batch.BatchKind
import com.gemini.ocr.batch.BatchStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoBatchOcrReuseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val videos get() = VideoStore(context)
    private val batches get() = BatchStore(context)
    private val first = VideoCue("donor-only", 35, 110, "不卖三匹马\n真的", origin = "OCR",
        qualityFlags = listOf("OCR_SHORT_QUORUM"), ocrConfidence = .64f)

    @Test fun exactBytesAndMatchingRoiReuseOnlyValidatedRawSourceInSameBatch() = runBlocking {
        val donor = video("donor")
        val same = video("copy")
        val other = video("different", "abcd0")
        attach(donor, same, other)
        val proof = requireNotNull(VideoBatchOcrReuse.prepare(context, donor) {})
        assertNull(proof.cues)
        proof.record(listOf(first.copy(translation = "Draft", model = "example", correctedZh = "试")), {})

        val result = requireNotNull(VideoBatchOcrReuse.prepare(context, same) {}?.cues).single()
        assertEquals(first.copy(id = "ocr-draft-1", rawZh = first.source), result)
        assertNull(VideoBatchOcrReuse.prepare(context, other) {}?.cues)
        assertNull(VideoBatchOcrReuse.prepare(context, same.copy(roi = listOf(.1f, .8f, .9f, .95f))) {}?.cues)
        assertNull(VideoBatchOcrReuse.prepare(context, same.copy(scanMs = 10)) {})
        assertNull(VideoBatchOcrReuse.prepare(context, same.copy(cues = listOf(first.copy(approved = true)))) {})
        assertNull(VideoBatchOcrReuse.prepare(context, same.copy(sourceMode = VideoSourceMode.AUTO)) {})

        attach(same, other)
        assertNull(VideoBatchOcrReuse.prepare(context, same) {}?.cues)
    }

    @Test fun documentChangedDuringExtractionCannotCreateReusableEvidence() = runBlocking {
        val donor = video("donor")
        val target = video("target")
        attach(donor, target)
        val proof = requireNotNull(VideoBatchOcrReuse.prepare(context, donor) {})
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse(donor.uri)) { "abcde".byteInputStream() }
        proof.record(listOf(first), {})
        assertNull(VideoBatchOcrReuse.prepare(context, target) {}?.cues)
    }

    private fun video(name: String, bytes: String = "abcdf"): VideoProject {
        val uri = Uri.parse("content://fixture/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { bytes.byteInputStream() }
        return videos.save(VideoProject(UUID.randomUUID().toString(), name, uri.toString(), 500, 640, 360,
            sourceMode = VideoSourceMode.OCR))
    }

    private fun attach(vararg projects: VideoProject) {
        val batch = batches.create("Fixture", BatchKind.SERIES)
        batches.save(batch.copy(items = projects.map { BatchItem(it.id, it.title, bytes = 5, durationMs = it.durationMs) }))
        projects.forEach { videos.folder(it.id).resolve("batch-owner").writeText(batch.id) }
    }
}
