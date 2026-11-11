@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.video.sync.DriveVideoRepository
import com.gemini.ocr.video.sync.DrivePlaybackException
import com.gemini.ocr.video.sync.DrivePlaybackFailure
import androidx.media3.datasource.DataSpec
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoStage
import com.gemini.ocr.video.VideoSubtitleStyle
import android.net.Uri
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
import java.security.MessageDigest
import java.util.UUID

/** Offline HTTP protocol tests, not a successful Google login or cross-device cloud test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DriveMovieRepositoryTest {
    private lateinit var context:Context
    private lateinit var server:MockWebServer
    private lateinit var drive:MovieDrive
    private lateinit var repository:DriveVideoRepository
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        server=MockWebServer();drive=MovieDrive();server.dispatcher=drive;server.start()
        repository=DriveVideoRepository(context,{"offline-test-token"},api=server.url("/drive/v3/"),uploadApi=server.url("/upload/drive/v3/"))
    }
    @After fun stop(){server.shutdown()}
    private fun movie(count:Int=1):MovieRecord {
        val episodes=(1..count).map{index->
            val file=File(context.cacheDir,"${UUID.randomUUID()}.mp4")
            file.writeBytes(ByteArray(4096+index){(it%251).toByte()})
            MovieEpisode("episode-$index","EP$index",file.absolutePath,1000,index*10L)
        }
        return MovieRecord(UUID.randomUUID().toString(),"Movie",episodes,accountEmail="test@example.com")
    }
    @Test fun confirmedMovieCatalogIsSeparateAndRetryReusesManifestAndMedia()=runBlocking {
        val original=movie(5)
        val first=repository.publishMovie(original)
        val second=repository.publishMovie(original)
        assertEquals(first,second)
        assertEquals(5,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="movie-manifest"})
        assertTrue(repository.list().isEmpty())
        val cloud=repository.listMovies().single()
        assertEquals(original.id,cloud.id)
        assertTrue(cloud.accountEmail.isBlank())
        assertTrue(cloud.episodes.all{it.path.isBlank()})
        val manifest=drive.content.getValue(first).toString(Charsets.UTF_8)
        assertFalse(manifest.contains(context.cacheDir.absolutePath))
        assertFalse(manifest.contains("test@example.com"))
    }
    @Test fun fiveEpisodesDownloadWithVerifiedBytesAndWatchPositions()=runBlocking {
        val original=movie(5);val id=repository.publishMovie(original)
        val folder=File(context.cacheDir,"download-${UUID.randomUUID()}").apply{mkdirs()}
        val downloaded=repository.downloadMovie(original.copy(cloudId=id,accountEmail="test@example.com"),folder)
        assertEquals("SYNCED",downloaded.syncState)
        assertEquals(original.episodes.map{it.positionMs},downloaded.episodes.map{it.positionMs})
        original.episodes.zip(downloaded.episodes).forEach{(before,after)->assertArrayEquals(File(before.path).readBytes(),File(after.path).readBytes())}
    }
    @Test fun corruptDownloadCannotBecomeAnOfflineMovie()=runBlocking {
        val original=movie();val id=repository.publishMovie(original)
        drive.corruptDownloads=true
        val folder=File(context.cacheDir,"corrupt-${UUID.randomUUID()}").apply{mkdirs()}
        try{repository.downloadMovie(original.copy(cloudId=id),folder);fail("must reject corrupted media")}
        catch(_:IllegalArgumentException){}
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertTrue(File(original.episodes.single().path).isFile)
    }
    @Test fun serverChecksumMismatchCannotPublishMovieManifest()=runBlocking {
        drive.wrongServerHash=true
        try{repository.publishMovie(movie());fail("server must verify upload")}
        catch(_:IllegalArgumentException){}
        assertTrue(repository.listMovies().isEmpty())
        assertTrue(drive.metadata.values.none{it.getJSONObject("appProperties").getString("kind")=="movie-manifest"})
    }
    @Test fun failedManifestCanRetryWithoutReuploadingCompletedEpisode()=runBlocking {
        val original=movie();drive.failManifest=true
        try{repository.publishMovie(original);fail("manifest must fail")}
        catch(_:java.io.IOException){}
        assertTrue(repository.listMovies().isEmpty())
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        drive.failManifest=false
        repository.publishMovie(original)
        assertEquals(1,repository.listMovies().size)
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        assertTrue(File(original.episodes.single().path).isFile)
    }
    @Test fun newMetadataRevisionReusesVideoAndChoosesLatestCatalogEntry()=runBlocking {
        val original=movie();repository.publishMovie(original)
        val watched=original.copy(episodes=original.episodes.map{it.copy(positionMs=500)},updatedAt=original.updatedAt+1)
        repository.publishMovie(watched)
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        assertEquals(500L,repository.listMovies().single().episodes.single().positionMs)
    }
    @Test fun largerMovieUsesMultipleAcknowledgedChunks()=runBlocking {
        val original=movie();File(original.episodes.single().path).writeBytes(ByteArray(8*1024*1024+17){(it%251).toByte()})
        repository.publishMovie(original)
        assertTrue(drive.ranges.contains("bytes 0-8388607/8388625"))
        assertTrue(drive.ranges.contains("bytes 8388608-8388624/8388625"))
    }
    @Test fun watchProgressSynchronizesWithoutReadingOrUploadingVideoAgain()=runBlocking {
        val original=movie();val id=repository.publishMovie(original)
        val watched=original.copy(cloudId=id,episodes=original.episodes.map{it.copy(path="",positionMs=700,positionUpdatedAt=original.updatedAt+20)},updatedAt=original.updatedAt+20)
        File(original.episodes.single().path).delete()
        val first=repository.publishMovieProgress(watched)
        assertEquals(first,repository.publishMovieProgress(watched))
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        val catalog=repository.listMovies().single()
        assertEquals(700L,catalog.episodes.single().positionMs)
        assertEquals(id,catalog.cloudId)
    }
    private fun writer(id:String)=DriveVideoRepository(context,{"offline-test-token"},api=server.url("/drive/v3/"),
        uploadApi=server.url("/upload/drive/v3/"),progressWriterId=id)
    @Test fun twoDevicesWatchingDifferentEpisodesMergeWithoutDiscardingEitherPosition()=runBlocking {
        val original=movie(2);val id=repository.publishMovie(original)
        val first=original.copy(cloudId=id,updatedAt=original.updatedAt+10,episodes=original.episodes.mapIndexed{i,e->
            if(i==0)e.copy(positionMs=400,positionUpdatedAt=original.updatedAt+10) else e})
        val second=original.copy(cloudId=id,updatedAt=original.updatedAt+20,episodes=original.episodes.mapIndexed{i,e->
            if(i==1)e.copy(positionMs=500,positionUpdatedAt=original.updatedAt+20) else e})
        writer("device-a").publishMovieProgress(first)
        writer("device-b").publishMovieProgress(second)
        val merged=repository.listMovies().single()
        assertEquals(listOf(400L,500L),merged.episodes.map{it.positionMs})
        assertTrue(merged.episodes.none{it.positionConflict})
        assertEquals(2,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
    }
    @Test fun equalEpisodeClockWithDifferentPositionsReportsConflictAndKeepsBothImmutableVersions()=runBlocking {
        val original=movie();val id=repository.publishMovie(original);val stamp=original.updatedAt+20
        val first=original.copy(cloudId=id,updatedAt=stamp,episodes=original.episodes.map{it.copy(positionMs=300,positionUpdatedAt=stamp)})
        val second=first.copy(episodes=first.episodes.map{it.copy(positionMs=700)})
        writer("device-a").publishMovieProgress(first);writer("device-b").publishMovieProgress(second)
        val merged=repository.listMovies().single()
        assertTrue(merged.episodes.single().positionConflict)
        assertEquals(300L,merged.episodes.single().positionMs)
        assertTrue(merged.message.contains("Xung đột"))
        assertEquals(2,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="movie-progress"})
    }
    @Test fun latestSnapshotPerDeviceCarriesItsEarlierEpisodeClock()=runBlocking {
        val original=movie(2);val id=repository.publishMovie(original)
        val first=original.copy(cloudId=id,updatedAt=original.updatedAt+10,episodes=original.episodes.mapIndexed{i,e->
            if(i==0)e.copy(positionMs=400,positionUpdatedAt=original.updatedAt+10) else e})
        val second=first.copy(updatedAt=original.updatedAt+20,episodes=first.episodes.mapIndexed{i,e->
            if(i==1)e.copy(positionMs=600,positionUpdatedAt=original.updatedAt+20) else e})
        writer("device-a").publishMovieProgress(first);writer("device-a").publishMovieProgress(second)
        assertEquals(listOf(400L,600L),repository.listMovies().single().episodes.map{it.positionMs})
    }
    @Test fun legacyWatchSnapshotWithoutEpisodeClocksStillLoads()=runBlocking {
        val original=movie();val id=repository.publishMovie(original);val stamp=original.updatedAt+20
        writer("device-a").publishMovieProgress(original.copy(cloudId=id,updatedAt=stamp,
            episodes=original.episodes.map{it.copy(positionMs=700,positionUpdatedAt=stamp)}))
        val progress=drive.metadata.entries.single{it.value.getJSONObject("appProperties").getString("kind")=="movie-progress"}
        progress.value.getJSONObject("appProperties").remove("writerId")
        val body=JSONObject(drive.content.getValue(progress.key).toString(Charsets.UTF_8));body.remove("writerId")
        body.getJSONArray("episodes").getJSONObject(0).remove("positionUpdatedAt")
        body.getJSONArray("episodes").getJSONObject(0).remove("positionConflict")
        drive.content[progress.key]=body.toString().toByteArray(Charsets.UTF_8)
        assertEquals(700L,repository.listMovies().single().episodes.single().positionMs)
    }
    private suspend fun sourceMovie():MovieRecord {
        val original=movie()
        val episode=original.episodes.single()
        val file=File(episode.path)
        val authority="movie-source-${UUID.randomUUID()}"
        val provider=SourceFixtureProvider(file)
        provider.attachInfo(context,ProviderInfo().apply{this.authority=authority})
        ShadowContentResolver.registerProviderInternal(authority,provider)
        // Exercise real provider streams rather than Android's Unix-only file URI on Windows JVM.
        val uri=Uri.parse("content://$authority/document/selected")
        val project=VideoProject(UUID.randomUUID().toString(),episode.title,uri.toString(),1000,1920,1080,
            cues=listOf(VideoCue("cn-one",100,800,"你好","Xin chào",approved=true)),
            style=VideoSubtitleStyle(position=.72f,outline=false),scanMs=1000,
            stage=VideoStage.REVIEW,resumeStage=VideoStage.TRANSLATING)
        val fingerprint=MovieSourceAccess.inspect(context,uri)
        return original.copy(episodes=listOf(episode.copy(source=MovieSourceSnapshot.freeze(project,fingerprint.first,fingerprint.second))))
    }
    @Test fun sourceQualityMovieRestoresExactMediaTimelineStyleAndApprovalsWithoutRender()=runBlocking {
        val original=sourceMovie();val id=repository.publishMovie(original)
        val remote=repository.listMovies().single()
        assertTrue(remote.episodes.single().isSourceQuality)
        assertEquals("",remote.episodes.single().mediaUri)
        assertEquals("",remote.episodes.single().source!!.projectId)
        val manifest=drive.content.getValue(id).toString(Charsets.UTF_8)
        assertFalse(manifest.contains(context.cacheDir.absolutePath))
        val folder=File(context.cacheDir,"source-cloud-${UUID.randomUUID()}").apply{mkdirs()}
        val restored=repository.downloadMovie(remote.copy(accountEmail="test@example.com"),folder)
        val before=original.episodes.single();val after=restored.episodes.single()
        assertArrayEquals(File(before.path).readBytes(),File(after.path).readBytes())
        assertEquals(before.source!!.sha256,after.source!!.sha256)
        assertEquals(before.source.cues,after.source.cues)
        assertEquals(before.source.style,after.source.style)
        assertEquals(before.source.revision,after.source.revision)
        assertTrue(after.source.cues.single().approved)
        assertEquals(Uri.fromFile(File(after.path)).toString(),after.mediaUri)
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
    }
    @Test fun editedTimelineMakesImmutableRevisionWhileReusingIdenticalSourceBlob()=runBlocking {
        val original=sourceMovie();val first=repository.publishMovie(original)
        val episode=original.episodes.single();val snapshot=episode.source!!
        val changedProject=snapshot.project(episode).copy(cues=snapshot.cues.map{it.copy(translation="Bản sửa đã kiểm tra",approved=false)})
        val changedSnapshot=MovieSourceSnapshot.freeze(changedProject,snapshot.sha256,snapshot.bytes,revisedAt=snapshot.revisedAt+1)
        val edited=original.copy(episodes=listOf(episode.copy(source=changedSnapshot)),updatedAt=original.updatedAt+100)
        val second=repository.publishMovie(edited)
        assertNotEquals(first,second)
        assertEquals(1,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="media"})
        assertEquals(2,drive.metadata.values.count{it.getJSONObject("appProperties").getString("kind")=="movie-source-manifest"})
        assertEquals("Bản sửa đã kiểm tra",repository.listMovies().single().episodes.single().source!!.cues.single().translation)
    }
    @Test fun changedSourceBytesCannotPublishSnapshotForAnUnconfirmedReplacement()=runBlocking {
        val original=sourceMovie();val file=File(original.episodes.single().path)
        val bytes=file.readBytes();bytes[0]=(bytes[0]+1).toByte();file.writeBytes(bytes)
        try{repository.publishMovie(original);fail("reject replacement with same size")}
        catch(_:IllegalArgumentException){}
        assertTrue(repository.listMovies().isEmpty())
    }
    @Test fun legacyCatalogDiscoveryCannotMisinterpretSourceMovieAsRenderedMp4()=runBlocking {
        val legacy=movie();val source=sourceMovie()
        repository.publishMovie(legacy)
        val sourceId=repository.publishMovie(source)
        // This exact app-property kind is the discoverability contract used by 1.7.7 clients.
        val visibleToOldClient=drive.metadata.values.filter{it.getJSONObject("appProperties").getString("kind")=="movie-manifest"}
        assertEquals(listOf(legacy.id),visibleToOldClient.map{it.getJSONObject("appProperties").getString("projectId")})
        val raw=JSONObject(drive.content.getValue(sourceId).toString(Charsets.UTF_8))
        assertEquals(2,raw.getInt("schema"))
        assertEquals(2,raw.getJSONObject("movie").getInt("schema"))
        assertNotEquals("Schema-1 readers must reject a source-bearing record",1,raw.getJSONObject("movie").getInt("schema"))
        assertEquals(setOf(legacy.id,source.id),repository.listMovies().map{it.id}.toSet())
    }
    @Test fun cloudSourcePlaybackResolvesSnapshotWithoutDownloadingAndRetainsApprovalsAndLatestWatchPosition()=runBlocking {
        val original=sourceMovie();val id=repository.publishMovie(original)
        val cloud=repository.listMovies().single().copy(accountEmail="test@example.com")
        val newer=cloud.copy(episodes=cloud.episodes.map{it.copy(positionMs=600,positionUpdatedAt=original.updatedAt+10)})
        val handle=repository.prepareMoviePlayback(newer,newer.episodes.single().id)
        assertEquals(0,drive.mediaDownloads)
        assertEquals(600L,handle.episode.positionMs)
        assertEquals("test@example.com",handle.accountEmail)
        assertEquals("gemini-drive",Uri.parse(handle.project.uri).scheme)
        assertEquals(original.episodes.single().source!!.cues,handle.project.cues)
        assertEquals(original.episodes.single().source!!.style,handle.project.style)
        assertEquals(1920,handle.project.width);assertEquals(1080,handle.project.height)
        assertTrue(handle.project.cues.single().approved)
        val reader=handle.dataSourceFactory.createDataSource()
        assertEquals(16L,reader.open(DataSpec.Builder().setUri(handle.project.uri).setPosition(32).setLength(16).build()))
        val actual=ByteArray(16);assertEquals(16,reader.read(actual,0,actual.size));reader.close()
        assertArrayEquals(File(original.episodes.single().path).readBytes().copyOfRange(32,48),actual)
        assertEquals(1,drive.mediaDownloads)
        assertTrue(repository.listMovies().single().episodes.single().source!!.cues.single().approved)
        assertEquals(id,cloud.cloudId)
    }
    @Test fun legacyRenderedPlaybackNeedsNoLocalDecoderOrTimelineMigration()=runBlocking {
        val original=movie();repository.publishMovie(original)
        val cloud=repository.listMovies().single()
        val handle=repository.prepareMoviePlayback(cloud,cloud.episodes.single().id)
        assertTrue(handle.project.cues.isEmpty());assertNull(handle.episode.source)
        assertEquals(cloud.episodes.single().durationMs,handle.project.durationMs)
        assertEquals(0,drive.mediaDownloads)
    }
    @Test fun cloudPlaybackRejectsWrongBlobChecksumBeforeReadingAnyMedia()=runBlocking {
        val original=sourceMovie();repository.publishMovie(original)
        val cloud=repository.listMovies().single();drive.wrongServerHash=true
        try{repository.prepareMoviePlayback(cloud,cloud.episodes.single().id);fail("must reject changed Drive media")}
        catch(e:DrivePlaybackException){assertEquals(DrivePlaybackFailure.METADATA,e.failure);assertNull(e.cause)}
        assertEquals(0,drive.mediaDownloads)
    }
    @Test fun playbackMetadataUsesSafeStatusSpecificFailuresWithoutReadingMedia()=runBlocking {
        repository.publishMovie(movie())
        val cloud=repository.listMovies().single()
        val playback=DriveVideoRepository(context,{"offline-test-token"},api=server.url("/drive/v3/"),
            refreshPlaybackToken={"offline-test-token"})
        for((code,failure) in listOf(403 to DrivePlaybackFailure.FORBIDDEN,404 to DrivePlaybackFailure.MISSING,
            429 to DrivePlaybackFailure.QUOTA)) {
            drive.playbackMetadataError=code
            try{playback.prepareMoviePlayback(cloud,cloud.episodes.single().id);fail("must report HTTP $code")}
            catch(e:DrivePlaybackException){assertEquals(failure,e.failure);assertNull(e.cause)}
        }
        assertEquals(0,drive.mediaDownloads)
    }
    @Test fun cloudPlaybackRejectsDuplicateDescriptorOrChangedSnapshotWithoutDownloading()=runBlocking {
        val original=sourceMovie();val id=repository.publishMovie(original)
        val cloud=repository.listMovies().single()
        val raw=JSONObject(drive.content.getValue(id).toString(Charsets.UTF_8))
        raw.getJSONArray("media").put(raw.getJSONArray("media").getJSONObject(0))
        drive.content[id]=raw.toString().toByteArray(Charsets.UTF_8)
        try{repository.prepareMoviePlayback(cloud,cloud.episodes.single().id);fail("must reject ambiguous media")}
        catch(e:DrivePlaybackException){assertEquals(DrivePlaybackFailure.METADATA,e.failure)}
        assertEquals(0,drive.mediaDownloads)
    }
    private class SourceFixtureProvider(private val file:File):ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)="video/mp4"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    }
    private inner class MovieDrive:Dispatcher() {
        val metadata=linkedMapOf<String,JSONObject>()
        val content=linkedMapOf<String,ByteArray>()
        val ranges=mutableListOf<String>()
        private val sessions=mutableMapOf<String,JSONObject>()
        private val buffers=mutableMapOf<String,Buffer>()
        var corruptDownloads=false
        var wrongServerHash=false
        var failManifest=false
        var mediaDownloads=0
        @Volatile var playbackMetadataError:Int?=null
        private var next=0
        override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.getHeader("Authorization")!="Bearer offline-test-token")return MockResponse().setResponseCode(401)
            val url=request.requestUrl!!
            if(request.method=="GET" && url.encodedPath=="/drive/v3/files") {
                val query=url.queryParameter("q").orEmpty()
                val filters=Regex("key='([^']+)' and value='([^']+)'").findAll(query).map{it.groupValues[1] to it.groupValues[2]}.toList()
                val rows=JSONArray()
                metadata.forEach{(id,meta)->
                    val props=meta.getJSONObject("appProperties")
                    if(filters.all{(key,value)->props.optString(key)==value})rows.put(JSONObject().put("id",id).put("name",meta.getString("name"))
                        .put("size",content.getValue(id).size.toString()).put("appProperties",props))
                }
                return MockResponse().setBody(JSONObject().put("files",rows).toString())
            }
            if(request.method=="POST" && url.encodedPath=="/upload/drive/v3/files") {
                val meta=JSONObject(request.body.readUtf8())
                if(failManifest && meta.getString("name").endsWith("-movie-manifest"))return MockResponse().setResponseCode(503)
                val id="file-${++next}";sessions[id]=meta;buffers[id]=Buffer()
                return MockResponse().setHeader("Location",server.url("/session/$id"))
            }
            if(request.method=="PUT" && url.encodedPath.startsWith("/session/")) {
                val id=url.pathSegments.last();val meta=sessions[id]?:return MockResponse().setResponseCode(404)
                val range=request.getHeader("Content-Range")!!;ranges+=range
                val buffer=buffers.getValue(id);buffer.write(request.body.readByteArray())
                if(buffer.size<range.substringAfter('/').toLong())return MockResponse().setResponseCode(308).setHeader("Range","bytes=0-${buffer.size-1}")
                metadata[id]=meta;content[id]=buffer.readByteArray();sessions.remove(id);buffers.remove(id)
                return MockResponse().setResponseCode(201).setBody(JSONObject().put("id",id).toString())
            }
            if(request.method=="PATCH" && url.encodedPath.startsWith("/drive/v3/files/")) {
                val id=url.pathSegments.last();val meta=metadata[id]?:return MockResponse().setResponseCode(404)
                meta.put("appProperties",JSONObject(request.body.readUtf8()).getJSONObject("appProperties"))
                return MockResponse().setBody(JSONObject().put("id",id).toString())
            }
            if(request.method=="GET" && url.encodedPath.startsWith("/drive/v3/files/")) {
                val id=url.pathSegments.last();val bytes=content[id]?:return MockResponse().setResponseCode(404)
                if(url.queryParameter("alt")=="media") {
                    if(metadata.getValue(id).getJSONObject("appProperties").getString("kind")=="media")mediaDownloads++
                    val body=if(corruptDownloads && metadata.getValue(id).getJSONObject("appProperties").getString("kind")=="media")bytes.clone().also{it[0]=(it[0]+1).toByte()} else bytes
                    request.getHeader("Range")?.let{range->
                        val match=Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(range)?:return MockResponse().setResponseCode(416)
                        val first=match.groupValues[1].toInt();val last=match.groupValues[2].toInt()
                        return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $first-$last/${body.size}")
                            .setBody(Buffer().write(body.copyOfRange(first,last+1)))
                    }
                    return MockResponse().setBody(Buffer().write(body))
                }
                if(url.queryParameter("fields").orEmpty().contains("capabilities"))playbackMetadataError?.let{code->
                    return MockResponse().setResponseCode(code).setBody("private-provider-body")
                }
                val hash=if(wrongServerHash)"0".repeat(64) else MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                return MockResponse().setBody(JSONObject().put("size",bytes.size.toString()).put("sha256Checksum",hash).toString())
            }
            return MockResponse().setResponseCode(404)
        }
    }
}
