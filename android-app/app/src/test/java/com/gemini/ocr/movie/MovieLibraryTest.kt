package com.gemini.ocr.movie

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.gemini.ocr.video.VideoCue
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persistence/confirmation tests inject decoding; they do not claim native codec or Google verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MovieLibraryTest {
    private lateinit var context:Context
    private lateinit var library:MovieLibrary
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        runCatching{WorkManager.initialize(context,Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build())}
        VideoCloudSync.get(context).disconnect()
        library=MovieLibrary(context,validateVideo={require(it.readText().startsWith("fixture"));1000L},makeCover={_,_->})
    }
    private fun episode(label:String)=File(context.cacheDir,"${UUID.randomUUID()}.mp4").let{file->
        file.writeText("fixture $label")
        MovieEpisode(UUID.randomUUID().toString(),label,file.absolutePath,1000)
    }
    @Test fun fivePartsStayOutOfLibraryUntilExplicitConfirmationAndRepeatedConfirmationIsIdempotent()=runBlocking {
        val id=UUID.randomUUID().toString();val episodes=(1..5).map{episode("EP$it")}
        assertFalse(library.movies.value.any{it.id==id})
        val confirmed=library.confirm(id,"Series",episodes)
        assertEquals(5,confirmed.episodes.size)
        assertEquals(1,library.movies.value.count{it.id==id})
        val duplicate=library.confirm(id,"Different accidental title",episodes)
        assertEquals(confirmed.episodes,duplicate.episodes)
        assertEquals("Series",duplicate.title)
        assertEquals(5,library.folder(id).listFiles().orEmpty().count{it.extension=="mp4"})
        assertTrue(episodes.zip(confirmed.episodes).all{(source,copy)->source.path!=copy.path && File(source.path).readBytes().contentEquals(File(copy.path).readBytes())})
    }
    @Test fun failureDoesNotPublishAPartialMovieOrDamageCompletedMovie()=runBlocking {
        val complete=library.confirm(UUID.randomUUID().toString(),"Completed",listOf(episode("one")))
        val broken=episode("broken");File(broken.path).writeText("invalid")
        val id=UUID.randomUUID().toString()
        try{library.confirm(id,"Incomplete",listOf(episode("good"),broken));fail("invalid output must not publish")}
        catch(_:IllegalArgumentException){}
        assertNull(library.find(id))
        assertTrue(File(complete.episodes.single().path).isFile)
        assertEquals(complete.episodes,library.find(complete.id)!!.episodes)
    }
    @Test fun interruptedCopyValidationLeavesNoPublishedMetadata()=runBlocking {
        var validationCount=0
        val failing=MovieLibrary(context,validateVideo={validationCount++;require(validationCount==1);1000L},makeCover={_,_->})
        val id=UUID.randomUUID().toString()
        try{failing.confirm(id,"broken copy",listOf(episode("source")));fail("copied output must decode")}
        catch(_:IllegalArgumentException){}
        assertNull(failing.find(id))
        assertTrue(failing.folder(id).listFiles().orEmpty().none{it.extension=="mp4"})
    }
    @Test fun localMovieSurvivesReopeningAndStoresClampedWatchProgress()=runBlocking {
        val movie=library.confirm(UUID.randomUUID().toString(),"Watch later",listOf(episode("EP01")))
        library.progress(movie.id,movie.episodes.single().id,2500)
        val reopened=MovieLibrary(context,validateVideo={1000L},makeCover={_,_->})
        assertEquals(1000L,reopened.find(movie.id)!!.episodes.single().positionMs)
        assertTrue(File(reopened.find(movie.id)!!.episodes.single().path).isFile)
    }
    @Test fun localMergeKeepsPositionOnEqualClockConflictAndShowsNotice() {
        val identity=VideoCloudSync.get(context);identity.rememberVerifiedAccount("conflict@example.com")
        val stamp=System.currentTimeMillis()
        val episode=episode("same-clock").copy(positionMs=600,positionUpdatedAt=stamp)
        val local=MovieRecord(UUID.randomUUID().toString(),"Conflict",listOf(episode),accountEmail="conflict@example.com",
            syncState="SYNCED",cloudId="first-cloud",updatedAt=stamp)
        library.save(local)
        library.mergeCloud("conflict@example.com",listOf(local.copy(cloudId="second-cloud",
            episodes=listOf(episode.copy(path="",positionMs=300,positionConflict=true)))))
        val merged=library.find(local.id)!!
        assertEquals(600L,merged.episodes.single().positionMs)
        assertTrue(merged.episodes.single().positionConflict)
        assertTrue(merged.message.contains("Xung đột"))
        library.progress(local.id,episode.id,650)
        val resolved=library.find(local.id)!!
        assertFalse(resolved.episodes.single().positionConflict)
        assertTrue(resolved.episodes.single().positionUpdatedAt>stamp)
    }
    @Test fun legacyEpisodeJsonGetsItsSnapshotClockAndNewEpisodeClockRoundTrips() {
        val episode=episode("legacy").copy(positionMs=300)
        val record=MovieRecord(UUID.randomUUID().toString(),"Legacy",listOf(episode),updatedAt=12345)
        val json=record.json();json.getJSONArray("episodes").getJSONObject(0).remove("positionUpdatedAt")
        json.getJSONArray("episodes").getJSONObject(0).remove("positionConflict")
        assertEquals(12345L,MovieRecord.from(json).episodes.single().positionUpdatedAt)
        assertEquals(0L,MovieRecord.from(record.json()).episodes.single().positionUpdatedAt)
    }
    @Test fun offlineEvictionRequiresVerifiedCloudAndKeepsCatalogAndSource()=runBlocking {
        val source=episode("original")
        val movie=library.confirm(UUID.randomUUID().toString(),"Offline",listOf(source))
        try{library.removeOffline(movie.id);fail("must preserve only local copy")}
        catch(_:IllegalArgumentException){}
        assertTrue(File(movie.episodes.single().path).isFile)
        library.save(movie.copy(syncState="SYNCED",cloudId="remote-file"))
        library.removeOffline(movie.id)
        val cloud=library.find(movie.id)!!
        assertEquals("CLOUD",cloud.syncState)
        assertEquals("remote-file",cloud.cloudId)
        assertTrue(cloud.episodes.single().path.isBlank())
        assertFalse(File(movie.episodes.single().path).exists())
        assertTrue(File(source.path).isFile)
    }
    @Test fun removalOnlyDeletesOwnedCopies()=runBlocking {
        val source=episode("private source")
        val movie=library.confirm(UUID.randomUUID().toString(),"Delete",listOf(source))
        library.remove(movie.id)
        assertNull(library.find(movie.id))
        assertFalse(File(movie.episodes.single().path).exists())
        assertTrue(File(source.path).isFile)
    }
    @Test fun accountCatalogCannotExposeAnotherAccountsMovie() {
        val identity=VideoCloudSync.get(context)
        identity.rememberVerifiedAccount("first@example.com")
        val remote=MovieRecord(UUID.randomUUID().toString(),"Private",listOf(MovieEpisode("episode","EP01","",1000)),accountEmail="first@example.com",syncState="CLOUD",cloudId="cloud-id")
        library.mergeCloud("first@example.com",listOf(remote))
        assertNotNull(library.find(remote.id))
        identity.rememberVerifiedAccount("second@example.com");library.refresh()
        assertNull(library.find(remote.id))
        assertFalse(library.movies.value.any{it.id==remote.id})
        try{library.mergeCloud("first@example.com",listOf(remote));fail("stale account catalog must be rejected")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun accountSwitchDuringConfirmationCannotReassignPrivateMovieToNewAccount()=runBlocking {
        val identity=VideoCloudSync.get(context)
        identity.rememberVerifiedAccount("first@example.com")
        var calls=0
        val changing=MovieLibrary(context,validateVideo={
            calls++
            if(calls==2)identity.rememberVerifiedAccount("second@example.com")
            1000L
        },makeCover={_,_->})
        val id=UUID.randomUUID().toString()
        val record=changing.confirm(id,"Private",listOf(episode("one")))
        assertEquals("first@example.com",record.accountEmail)
        assertEquals("LOCAL",record.syncState)
        assertNull(changing.find(id))
        assertFalse(changing.movies.value.any{it.id==id})
        identity.rememberVerifiedAccount("first@example.com");changing.refresh()
        assertTrue(File(changing.find(id)!!.episodes.single().path).isFile)
    }
    @Test fun atomicBackupIsRecoveredAfterInterruptedMetadataReplacement()=runBlocking {
        val record=library.confirm(UUID.randomUUID().toString(),"Recover",listOf(episode("one")))
        val file=File(library.folder(record.id),"movie.json")
        assertTrue(file.renameTo(File(file.parentFile,"movie.json.bak")))
        val reopened=MovieLibrary(context,validateVideo={1000L},makeCover={_,_->})
        assertEquals(record.episodes,reopened.find(record.id)!!.episodes)
        assertTrue(file.isFile)
    }
    @Test fun enablingWifiOnlyReplacesPendingUploadAndWatchJobsWithUnmeteredConstraints() {
        val identity=VideoCloudSync.get(context)
        identity.rememberVerifiedAccount("network@example.com")
        identity.setWifiOnly(false)
        val upload=MovieRecord(UUID.randomUUID().toString(),"Queued",listOf(episode("one")),accountEmail="network@example.com",syncState="QUEUED")
        val progress=MovieRecord(UUID.randomUUID().toString(),"Watch",listOf(episode("two")),accountEmail="network@example.com",syncState="SYNCED",cloudId="verified-cloud-id")
        // Other tests deliberately use an independent store with an injected decoder. This
        // integration test must seed the singleton actually used by MovieCloudSync; Robolectric
        // can keep that singleton's application/store while replacing the test Application.
        val scheduledLibrary=MovieLibrary.get(context)
        scheduledLibrary.save(upload);scheduledLibrary.save(progress)
        assertNotNull(scheduledLibrary.find(upload.id))
        assertNotNull(scheduledLibrary.find(progress.id))
        val sync=MovieCloudSync.get(context)
        sync.enqueue(upload.id);sync.enqueueProgress(progress.id)
        assertEquals("network@example.com",identity.state.value.accountEmail)
        assertEquals("QUEUED",MovieLibrary.get(context).find(upload.id)?.syncState)
        val manager=WorkManager.getInstance(context)
        val names=listOf("${MovieCloudSync.TAG}-${upload.id}","${MovieCloudSync.TAG}-progress-${progress.id}")
        // Enqueue/REPLACE are asynchronous Operations. Advance Robolectric's main executor
        // and await persisted work before checking the replacement, as the real app does.
        names.forEach{assertEquals(NetworkType.CONNECTED,awaitActiveWork(manager,it).single().constraints.requiredNetworkType)}
        identity.setWifiOnly(true)
        names.forEach{workName->
            val active=awaitActiveWork(manager,workName,NetworkType.UNMETERED)
            assertEquals("Expected one replacement for $workName",1,active.size)
            assertEquals(NetworkType.UNMETERED,active.single().constraints.requiredNetworkType)
        }
    }
    private fun awaitActiveWork(manager:WorkManager,name:String,network:NetworkType?=null):List<WorkInfo> {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        var observed=emptyList<WorkInfo>()
        do {
            shadowOf(Looper.getMainLooper()).idle()
            observed=manager.getWorkInfosForUniqueWork(name).get(5,TimeUnit.SECONDS)
            val active=observed.filterNot{it.state.isFinished}
            if(active.size==1 && (network==null || active.single().constraints.requiredNetworkType==network))return active
            Thread.sleep(10)
        } while(System.nanoTime()<until)
        fail("No persisted active $network work for $name; observed ${observed.map{it.state to it.constraints.requiredNetworkType}}")
        return emptyList()
    }
    @Test fun batchProjectCannotEnterLegacyAutoBackupEvenAfterTranslationCompletes()=runBlocking {
        val store=VideoStore(context)
        val project=store.save(VideoProject(UUID.randomUUID().toString(),"Batch part","content://synthetic/video",1000,640,360,
            cues=listOf(VideoCue("cue",0,1000,"你好","Xin chào"))))
        val sync=VideoCloudSync(context)
        assertTrue(sync.ready(project))
        File(store.folder(project.id),"batch-owner").writeText(UUID.randomUUID().toString())
        assertFalse(sync.ready(project))
        assertTrue(sync.runQueued(project.id,"wrong-account@example.com"))
    }
}
