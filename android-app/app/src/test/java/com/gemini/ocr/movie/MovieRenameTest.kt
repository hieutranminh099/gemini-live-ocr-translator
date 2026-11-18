package com.gemini.ocr.movie

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MovieRenameTest {
    private lateinit var context:Context
    private lateinit var library:MovieLibrary
    private lateinit var original:MovieRecord
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        runCatching{WorkManager.initialize(context,Configuration.Builder().build())}
        VideoCloudSync.get(context).disconnect()
        library=MovieLibrary(context,validateVideo={10_000},makeCover={_,_->})
        original=MovieRecord(UUID.randomUUID().toString(),"Tên cũ",listOf(
            MovieEpisode("ep-a","Tên tập giữ nguyên","/unchanged.mp4",10000,4000,123),
            MovieEpisode("ep-b","Tập hai","/second.mp4",10000)),cloudId="",coverPath="/cover.jpg")
        library.save(original)
    }
    @Test fun renamePersistsOnlyDisplayMetadataAndSearchFindsIt()=runBlocking {
        val changed=library.rename(original.id,"  Phim mới  ")
        assertEquals("Phim mới",changed.title)
        assertEquals(original.id,changed.id);assertEquals(original.episodes,changed.episodes)
        assertEquals(original.coverPath,changed.coverPath);assertEquals(original.confirmedAt,changed.confirmedAt)
        val reopened=MovieLibrary(context,validateVideo={10_000},makeCover={_,_->}).find(original.id)!!
        assertEquals(changed.title,reopened.title);assertEquals(changed.titleUpdatedAt,reopened.titleUpdatedAt)
        assertEquals(original.episodes,reopened.episodes)
        assertEquals(listOf(reopened),sortedMovies(listOf(reopened),"mới",MovieSort.RECENT))
        assertEquals("ep-a",continueEpisode(reopened)!!.id)
    }
    @Test fun emptyAndTooLongNamesLeaveStoredRecordAlone()=runBlocking {
        for(name in listOf(" \n\t ","x".repeat(201))) {
            try{library.rename(original.id,name);fail("must reject invalid name")}
            catch(_:IllegalArgumentException){}
            assertEquals(original,library.find(original.id))
        }
    }
    @Test fun unavailableRecordFailsClearly()=runBlocking {
        try { library.rename(UUID.randomUUID().toString(),"Tên mới");fail() }
        catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("chưa lưu"))}
    }
    @Test fun progressAndRenameDoNotOverwriteEachOther()=runBlocking {
        library.progress(original.id,"ep-a",6000,300)
        val renamed=library.rename(original.id,"Tên mới")
        library.progress(original.id,"ep-a",7000,400)
        val after=library.find(original.id)!!
        assertEquals(renamed.title,after.title);assertEquals(renamed.titleUpdatedAt,after.titleUpdatedAt)
        assertEquals(7000L,after.episodes.first().positionMs)
    }
    @Test fun cloudProgressClockCannotOverwriteNewerName() {
        val renamed=original.copy(title="Mới",titleUpdatedAt=300,updatedAt=300)
        val stale=original.copy(updatedAt=9000)
        assertEquals("Mới",renamed.mergeMovieTitle(stale).title)
        assertEquals("Mới",stale.mergeMovieTitle(renamed).title)
        assertEquals(renamed,MovieRecord.from(renamed.json(portable=false)))
    }
    @Test fun oldManifestWithoutTitleClockStillLoads() {
        val json=original.json();json.remove("titleUpdatedAt")
        assertEquals(0L,MovieRecord.from(json).titleUpdatedAt)
    }
    @Test fun cloudRefreshPreservesLatestRenameAndEpisodeProgress() {
        val auth=VideoCloudSync.get(context);auth.rememberVerifiedAccount("fixture@example.com")
        val local=original.copy(accountEmail="fixture@example.com",cloudId="cloud-fixture",syncState="SYNCED",title="Đã đổi",titleUpdatedAt=500)
        library.save(local)
        library.mergeCloud("fixture@example.com",listOf(local.copy(title="Cũ",titleUpdatedAt=0,updatedAt=9999)))
        assertEquals("Đã đổi",library.find(local.id)!!.title)
        library.mergeCloud("fixture@example.com",listOf(local.copy(title="Từ máy khác",titleUpdatedAt=600)))
        assertEquals("Từ máy khác",library.find(local.id)!!.title)
        assertEquals(original.episodes,library.find(local.id)!!.episodes)
        auth.disconnect()
    }
}
