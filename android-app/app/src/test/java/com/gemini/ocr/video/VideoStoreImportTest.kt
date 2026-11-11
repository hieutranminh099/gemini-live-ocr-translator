package com.gemini.ocr.video

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [VideoStoreImportTest.SeekableOnlyRetriever::class])
class VideoStoreImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() { SeekableOnlyRetriever.sources.clear(); SeekableOnlyRetriever.valid = true }

    @Test fun transientProviderIsCopiedBeforeMediaProbeAndOriginalBytesStayUnchanged() {
        val source = File.createTempFile("selected-stream-", ".mp4", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val uri = register(source)
        val store = VideoStore(context)
        val project = store.importVideo(uri, "Selected stream")

        assertEquals(uri.toString(), project.originalSourceUri)
        assertEquals("file", Uri.parse(project.uri).scheme)
        assertTrue(SeekableOnlyRetriever.sources.all { it.scheme == "file" })
        val retained = store.folder(project.id).listFiles().orEmpty().single { it.extension == "input" }
        assertArrayEquals(source.readBytes(), retained.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
        assertEquals(project.id, store.load(project.id).id)
    }

    @Test fun failedImportRemovesOnlyItsOwnTemporaryCopyAndPreservesExistingProject() {
        val store = VideoStore(context)
        val source = File.createTempFile("bad-selected-stream-", ".mp4", context.cacheDir).apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
        val existing = store.create(Uri.fromFile(source), "Existing checkpoint", VideoMetadata(4000, 640, 360))
        val before = store.directory.listFiles().orEmpty().map { it.name }.toSet()
        SeekableOnlyRetriever.valid = false

        assertThrows(IllegalArgumentException::class.java) { store.importVideo(register(source), "Broken stream") }

        assertEquals(before, store.directory.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(existing.json().toString(), store.load(existing.id).json().toString())
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), source.readBytes())
    }

    private fun register(file: File): Uri {
        val authority = "stream-source-${file.name.hashCode().toUInt()}"
        val provider = StreamDocument(file)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/document/selected")
    }

    private class StreamDocument(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Implements(MediaMetadataRetriever::class)
    class SeekableOnlyRetriever {
        @Implementation fun setDataSource(context: Context, uri: Uri) {
            sources += uri
            require(uri.scheme == "file") { "Provider descriptor is not seekable" }
        }
        @Implementation fun extractMetadata(key: Int): String? = if (!valid) null else when (key) {
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH -> "640"
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT -> "360"
            MediaMetadataRetriever.METADATA_KEY_DURATION -> "4000"
            else -> null
        }
        @Implementation fun release() = Unit
        companion object {
            val sources = mutableListOf<Uri>()
            var valid = true
        }
    }
}
