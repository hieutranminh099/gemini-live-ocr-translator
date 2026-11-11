package com.gemini.ocr.video.sync

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

/** Real HTTP serialization/streaming/checksum/restore boundaries; no Google account or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DriveVideoRepositoryTest{
    private lateinit var context:Context
    private lateinit var server:MockWebServer
    private lateinit var drive:FakeDrive
    private lateinit var repository:DriveVideoRepository
    private lateinit var store:VideoStore
    private lateinit var fixtureFile:File
    @Before fun setup(){
        context=ApplicationProvider.getApplicationContext();store=VideoStore(context)
        server=MockWebServer();drive=FakeDrive();server.dispatcher=drive;server.start()
        repository=DriveVideoRepository(context,{"test-only-token"},api=server.url("/drive/v3/"),uploadApi=server.url("/upload/drive/v3/"))
    }
    @After fun stop(){server.shutdown()}
    private fun project():VideoProject{
        val source=File(context.cacheDir,"public-fixture-${UUID.randomUUID()}.mp4")
        fixtureFile=source
        source.writeBytes(ByteArray(4000){(it%251).toByte()})
        val authority="sync-fixture-${UUID.randomUUID()}"
        val provider=FixtureProvider(source)
        provider.attachInfo(context,ProviderInfo().apply{this.authority=authority})
        ShadowContentResolver.registerProviderInternal(authority,provider)
        // Android file URIs use Unix paths. A real document provider keeps this Windows JVM
        // fixture portable while exercising a fresh stream for both preflight and upload.
        val uri=Uri.parse("content://$authority/document/selected")
        return store.save(VideoProject(UUID.randomUUID().toString(),"中文视频",uri.toString(),1000,640,360,
            cues=listOf(VideoCue("one",100,800,"你好","Xin chào",approved=true)),stage=VideoStage.REVIEW,
            originalSourceUri="content://private-user-selection/video1"))
    }
    @Test fun immutableSnapshotsReuseIdenticalVideoAndNeverUploadPrivateUris()=runBlocking{
        val project=project();val first=repository.publish(project);val second=repository.publish(project)
        assertNotEquals(first.fileId,second.fileId)
        assertNotEquals(first.revisionId,second.revisionId)
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        assertEquals(2,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="manifest"})
        val manifest=JSONObject(drive.content.getValue(first.fileId).toString(Charsets.UTF_8))
        assertEquals(1,manifest.getInt("schema"))
        val portable=manifest.getJSONObject("project")
        assertEquals("",portable.getString("uri"));assertEquals("",portable.getString("originalSourceUri"))
        assertEquals("",portable.getString("exportedMp4"))
        assertEquals(2,repository.list().size)
        assertTrue(drive.metadata.values.all{it.getJSONArray("parents").getString(0)=="appDataFolder"})
        assertTrue(drive.authHeaders.all{it=="Bearer test-only-token"})
    }
    @Test fun restoreClonesProjectAndCannotOverwriteLocalApprovedChanges()=runBlocking{
        val original=project();val cloud=repository.publish(original)
        val edited=store.save(original.copy(cues=original.cues.map{it.copy(translation="Đã được tôi sửa",approved=true)}))
        val restored=repository.restore(cloud)
        assertNotEquals(original.id,restored.id)
        assertEquals("Đã được tôi sửa",store.load(edited.id).cues.single().translation)
        assertTrue(store.load(edited.id).cues.single().approved)
        assertEquals(original.cues,restored.cues)
        val restoredFile=File(store.folder(restored.id),"cloud-source.input")
        assertTrue(restoredFile.isFile)
        assertArrayEquals(fixtureFile.readBytes(),restoredFile.readBytes())
        assertEquals(restored.uri,restored.originalSourceUri)
        assertEquals(VideoStage.REVIEW,restored.stage)
    }
    @Test fun corruptCloudMediaIsRejectedBeforeAProjectIsPublishedLocally()=runBlocking{
        val original=project();val cloud=repository.publish(original)
        val before=store.list().map{it.id}.toSet()
        drive.corruptMedia=true
        try{repository.restore(cloud);fail("must reject corrupted bytes")}catch(expected:IllegalArgumentException){assertTrue(expected.message.orEmpty().contains("SHA-256"))}
        assertEquals(before,store.list().map{it.id}.toSet())
        assertEquals(original.cues,store.load(original.id).cues)
    }
    @Test fun invalidRemoteFileIdCannotBecomeARequestPath()=runBlocking{
        val count=server.requestCount
        try{repository.restore(CloudVideoSnapshot("../escape","project","x",0,"revision"));fail("must reject invalid id")}
        catch(_:IllegalArgumentException){}
        assertEquals(count,server.requestCount)
    }
    @Test fun providerBodyIsNotCopiedIntoTheUserFacingError()=runBlocking{
        drive.forbidden=true
        try{repository.list();fail("must report denied Drive access")}
        catch(expected:java.io.IOException){
            assertTrue(expected.message.orEmpty().contains("Google Drive"))
            assertFalse(expected.message.orEmpty().contains("sensitive-provider-detail"))
        }
    }
    @Test fun failedManifestCannotDeleteMediaAlreadyAvailableForAnotherDevice()=runBlocking{
        val project=project();drive.failManifestStart=true
        try{repository.publish(project);fail("manifest must fail")}
        catch(_:java.io.IOException){}
        val shared=drive.metadata.filterValues{it.getJSONObject("appProperties").getString("kind")=="media"}
        assertEquals(1,shared.size)
        // A second device can reuse this content-addressed object even after the first attempt failed.
        drive.failManifestStart=false
        val second=repository.publish(project)
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        assertEquals(project.cues,repository.restore(second).cues)
    }
    @Test fun sourceChangedBetweenPreflightAndUploadCannotCreateACorruptCloudRevision()=runBlocking{
        val project=project();val file=fixtureFile
        drive.onMediaSessionStarted={val bytes=file.readBytes();bytes[0]=(bytes[0]+1).toByte();file.writeBytes(bytes)}
        try{repository.publish(project);fail("must reject source changed after hashing")}
        catch(expected:java.io.IOException){assertTrue(expected.message.orEmpty().contains("đã thay đổi"))}
        assertTrue(drive.metadata.values.none{it.getJSONObject("appProperties").getString("kind") in setOf("media","manifest")})
        assertTrue(repository.list().isEmpty())
    }
    @Test fun videoLargerThanEightMiBUses308ChunkAcknowledgementsAndRestoresIdenticalBytes()=runBlocking{
        val project=project();val file=fixtureFile
        val bytes=ByteArray(8*1024*1024+17){(it%251).toByte()};file.writeBytes(bytes)
        val cloud=repository.publish(project)
        assertEquals(listOf("bytes 0-8388607/8388625","bytes 8388608-8388624/8388625"),drive.mediaContentRanges)
        val restored=repository.restore(cloud)
        assertArrayEquals(bytes,File(store.folder(restored.id),"cloud-source.input").readBytes())
    }

    private class FixtureProvider(private val file:File):ContentProvider(){
        override fun onCreate()=true
        override fun getType(uri:Uri)="video/mp4"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    }
    private inner class FakeDrive:Dispatcher(){
        val metadata=linkedMapOf<String,JSONObject>()
        val content=linkedMapOf<String,ByteArray>()
        val authHeaders=mutableListOf<String?>()
        private val sessions=mutableMapOf<String,JSONObject>()
        private val sessionBytes=mutableMapOf<String,Buffer>()
        val mediaContentRanges=mutableListOf<String>()
        var corruptMedia=false
        var forbidden=false
        var failManifestStart=false
        var onMediaSessionStarted:(()->Unit)?=null
        override fun dispatch(request:RecordedRequest):MockResponse{
            authHeaders+=request.getHeader("Authorization")
            if(forbidden)return MockResponse().setResponseCode(403).setBody("sensitive-provider-detail")
            val url=request.requestUrl!!
            if(request.method=="GET" && url.encodedPath=="/drive/v3/files"){
                val q=url.queryParameter("q").orEmpty()
                val rows=JSONArray()
                for((id,meta) in metadata){
                    val props=meta.getJSONObject("appProperties")
                    val kind=props.getString("kind")
                    if(q.contains("value='manifest'") && kind!="manifest")continue
                    if(q.contains("value='media'") && kind!="media")continue
                    if(q.contains("key='sha256'") && !q.contains("value='${props.getString("sha256")}'"))continue
                    rows.put(JSONObject().put("id",id).put("name",meta.getString("name"))
                        .put("size",content.getValue(id).size.toString()).put("appProperties",props))
                }
                return MockResponse().setBody(JSONObject().put("files",rows).toString())
            }
            if(request.method=="POST" && url.encodedPath=="/upload/drive/v3/files"){
                val meta=JSONObject(request.body.readUtf8())
                if(failManifestStart && meta.getString("name").endsWith("-manifest"))return MockResponse().setResponseCode(500)
                if(meta.getString("name").endsWith("-source"))onMediaSessionStarted?.invoke()
                val id="file-${metadata.size+sessions.size+1}"
                sessions[id]=meta
                sessionBytes[id]=Buffer()
                return MockResponse().setResponseCode(200).setHeader("Location",server.url("/session/$id"))
            }
            if(request.method=="PATCH" && url.encodedPath.startsWith("/drive/v3/files/")){
                val id=url.pathSegments.last();val row=metadata[id]?:return MockResponse().setResponseCode(404)
                row.put("appProperties",JSONObject(request.body.readUtf8()).getJSONObject("appProperties"))
                return MockResponse().setBody(JSONObject().put("id",id).toString())
            }
            if(request.method=="PUT" && url.encodedPath.startsWith("/session/")){
                val id=url.pathSegments.last();val meta=sessions[id]?:return MockResponse().setResponseCode(404)
                val range=request.getHeader("Content-Range")?:return MockResponse().setResponseCode(400)
                if(meta.getString("name").endsWith("-source"))mediaContentRanges+=range
                val total=range.substringAfter('/').toLong()
                val collected=sessionBytes.getValue(id);collected.write(request.body.readByteArray())
                if(collected.size<total)return MockResponse().setResponseCode(308).setHeader("Range","bytes=0-${collected.size-1}")
                sessions.remove(id);sessionBytes.remove(id)
                metadata[id]=meta;content[id]=collected.readByteArray()
                return MockResponse().setResponseCode(201).setBody(JSONObject().put("id",id).toString())
            }
            if(request.method=="GET" && url.encodedPath.startsWith("/drive/v3/files/")){
                val id=url.pathSegments.last();var bytes=content[id]?:return MockResponse().setResponseCode(404)
                if(corruptMedia && metadata.getValue(id).getJSONObject("appProperties").getString("kind")=="media"){
                    bytes=bytes.clone();bytes[0]=(bytes[0]+1).toByte()
                }
                return MockResponse().setBody(Buffer().write(bytes))
            }
            if(request.method=="DELETE"){
                val id=url.pathSegments.last();metadata.remove(id);content.remove(id)
                return MockResponse().setResponseCode(204)
            }
            return MockResponse().setResponseCode(404)
        }
    }
}
