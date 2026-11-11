package com.gemini.ocr.video

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** Provider/container metadata compatibility; no decoder or remote calls are simulated as real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoMetadataImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://import-fixture/document/valid-video")
    private val dataSource get() = DataSource.toDataSource(context, uri)

    @Test fun missingRetrieverDimensionsUseActualVideoTrackAndKeepRotationAudioAndDuration() {
        val video = MediaFormat.createVideoFormat("video/avc", 640, 360).apply {
            setLong(MediaFormat.KEY_DURATION, 4_000_000L)
            setInteger(MediaFormat.KEY_ROTATION, 90)
        }
        ShadowMediaExtractor.addTrack(dataSource, video, byteArrayOf(1))
        ShadowMediaExtractor.addTrack(dataSource, MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2), byteArrayOf(2))

        assertEquals(VideoMetadata(4000, 360, 640, 90, true), VideoDecoder.metadata(context, uri))
    }

    @Test fun retrieverOpenFailureDoesNotRejectAContainerThatExtractorCanRead() {
        ShadowMediaMetadataRetriever.addException(dataSource, IllegalArgumentException("retriever unsupported"))
        ShadowMediaExtractor.addTrack(dataSource, MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
            setLong(MediaFormat.KEY_DURATION, 12_000_000L)
        }, byteArrayOf(1))

        assertEquals(VideoMetadata(12000, 1280, 720), VideoDecoder.metadata(context, uri))
    }

    @Test fun completeRetrieverMetadataKeepsTheExistingImportPath() {
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_DURATION, "5000")
        ShadowMediaMetadataRetriever.addMetadata(dataSource, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        assertEquals(VideoMetadata(5000, 1920, 1080, 0, true), VideoDecoder.metadata(context, uri))
    }

    @Test fun audioOnlyContainerHasAnActionableErrorInsteadOfAnEmptyQueueMystery() {
        ShadowMediaExtractor.addTrack(dataSource, MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2), byteArrayOf(2))
        val error = assertThrows(IllegalArgumentException::class.java) { VideoDecoder.metadata(context, uri) }
        assertTrue(error.message.orEmpty().contains("không có track hình ảnh video"))
    }

    @Test fun revokedReadPermissionIsReportedExplicitly() {
        ShadowMediaMetadataRetriever.addException(dataSource, SecurityException("denied"))
        val error = assertThrows(IllegalArgumentException::class.java) { VideoDecoder.metadata(context, uri) }
        assertTrue(error.message.orEmpty().contains("quyền đọc"))
        assertFalse(error.message.orEmpty().contains(uri.toString()))
    }
}
