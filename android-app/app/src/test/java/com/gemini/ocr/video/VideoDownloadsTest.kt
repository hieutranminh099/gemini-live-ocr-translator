package com.gemini.ocr.video

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class VideoDownloadsTest {
    private lateinit var context:Context
    private lateinit var provider:DownloadProvider

    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        provider=DownloadProvider(File(context.cacheDir,"download-test-${UUID.randomUUID()}").apply{mkdirs()})
        provider.attachInfo(context,ProviderInfo().apply{authority="media"})
        ShadowContentResolver.registerProviderInternal("media",provider)
    }

    @Test fun textIsUtf8AndOnlyPublishedAfterCompletedStreamWithoutChangingDraft()=runBlocking {
        val original=project()
        val saved=VideoDownloads.saveSubtitle(context,original)
        assertEquals(VideoDownloads.DIRECTORY,saved.relativePath)
        assertEquals("application/x-subrip",provider.insertValues!!.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download/Gemini OCR",provider.insertValues!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1,provider.insertValues!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(listOf("insert:pending","open","publish"),provider.events)
        assertTrue(provider.file.readText(Charsets.UTF_8).contains("Đừng đi, anh Quân."))
        assertFalse(original.cues.single().approved)
        assertEquals(0,original.cloudCalls)
        assertFalse(provider.deleted)
    }

    @Test fun failedOrCancelledWriteAndFailedPublicationDeleteHiddenRows()=runBlocking {
        assertThrows(IOException::class.java) {runBlocking {
            VideoDownloads.publish(context.contentResolver,"broken.srt","application/x-subrip") {
                it.write("partial".toByteArray());throw IOException("storage stopped")
            }
        }}
        assertEquals(listOf("insert:pending","open","delete"),provider.events)
        assertTrue(provider.deleted);assertFalse(provider.file.exists())
        provider.reset()
        assertThrows(CancellationException::class.java) {runBlocking {
            VideoDownloads.publish(context.contentResolver,"cancelled.mp4","video/mp4") {
                it.write(byteArrayOf(1));throw CancellationException("user left")
            }
        }}
        assertTrue(provider.deleted);assertFalse(provider.events.contains("publish"))
        provider.reset();provider.failPublish=true
        assertThrows(IllegalStateException::class.java) {runBlocking {
            VideoDownloads.publish(context.contentResolver,"publish-failed.srt","application/x-subrip") {it.write(byteArrayOf(2))}
        }}
        assertEquals(listOf("insert:pending","open","publish","delete"),provider.events)
        assertTrue(provider.deleted);assertFalse(provider.file.exists())
    }

    @Test fun mp4CopyRequiresRenderedPrivateProjectFileAndPreservesSource()=runBlocking {
        val p=project();val store=VideoStore(context)
        val source=File(context.cacheDir,"original-public-video.mp4").apply{writeBytes(byteArrayOf(8,7,6))}
        val rendered=File(store.folder(p.id),"rendered.mp4").apply{writeBytes(ByteArray(320000){(it%251).toByte()})}
        VideoDownloads.saveRenderedMp4(context,p.copy(uri=source.toURI().toString(),exportedMp4=rendered.absolutePath))
        assertArrayEquals(rendered.readBytes(),provider.file.readBytes())
        assertArrayEquals(byteArrayOf(8,7,6),source.readBytes())
        provider.reset()
        assertThrows(IllegalArgumentException::class.java) {runBlocking {
            VideoDownloads.saveRenderedMp4(context,p.copy(exportedMp4=source.absolutePath))
        }}
        assertTrue(provider.events.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {runBlocking {
            VideoDownloads.saveSubtitle(context,p.copy(cues=p.cues.map{it.copy(translation="")}))
        }}
        assertTrue(provider.events.isEmpty())
    }

    @Test @Config(sdk=[28]) fun androidNineRequiresDocumentPickerAndNamesCannotTraverseDirectories()=runBlocking {
        assertFalse(VideoDownloads.supportsDirectSave)
        assertThrows(IllegalArgumentException::class.java) {runBlocking {VideoDownloads.saveSubtitle(context,project())}}
        assertTrue(provider.events.isEmpty())
        val name=VideoDownloads.resultName("../phim\\private:name?.mp4","srt")
        assertFalse(name.contains('/'));assertFalse(name.contains('\\'));assertFalse(name.contains(':'))
        assertTrue(name.endsWith(".srt"));assertTrue(name.length<100)
    }

    private fun project()=VideoProject(UUID.randomUUID().toString(),"Phim 中文.mp4","file:///source.mp4",5000,1280,720,
        stage=VideoStage.REVIEW,resumeStage=VideoStage.TRANSLATING,scanMs=5000,cues=listOf(VideoCue("one",100,2000,"别走，军哥。","Đừng đi, anh Quân.")))

    private class DownloadProvider(private val folder:File):ContentProvider() {
        val file=File(folder,"download.bin")
        val events=mutableListOf<String>()
        var insertValues:ContentValues?=null
        var deleted=false
        var failPublish=false
        fun reset(){events.clear();deleted=false;failPublish=false;insertValues=null;file.delete()}
        override fun onCreate()=true
        override fun insert(uri:Uri,values:ContentValues?):Uri {
            insertValues=ContentValues(values);events.add("insert:pending")
            return Uri.parse("content://media/external/downloads/1")
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            events.add("open")
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {
            assertEquals(0,values!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertTrue(file.length()>0)
            events.add("publish");return if(failPublish)0 else 1
        }
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {events.add("delete");deleted=true;file.delete();return 1}
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor?=null
    }
}
