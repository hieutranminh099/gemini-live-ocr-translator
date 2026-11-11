package com.gemini.ocr.movie

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gemini.ocr.video.*
import com.gemini.ocr.video.sync.VideoCloudSync
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.util.UUID

/** Injected decoding verifies persistence/identity, not actual codec or playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [MovieSourceLibraryTest.WindowsFileUris::class])
class MovieSourceLibraryTest {
    private lateinit var context: Context
    private lateinit var library: MovieLibrary
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // The URI shadow uses a separate Robolectric sandbox; initialize its own scheduler.
        runCatching { WorkManager.initialize(context, Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()) }
        VideoCloudSync.get(context).disconnect()
        library = MovieLibrary(context, validateVideo = { require(it.readText().startsWith("fixture")); 1000L }, makeCover = { _, _ -> })
    }
    private fun input(label: String = "EP01"): MovieSourceInput {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.mp4").apply { writeText("fixture $label") }
        val p = VideoProject(UUID.randomUUID().toString(), label, Uri.fromFile(file).toString(), 1000, 1920, 1080,
            cues = listOf(VideoCue("one", 100, 500, "你好", "Xin chào", approved = true),
                VideoCue("two", 550, 900, "再见", "Hẹn gặp lại", model = "user-edited")),
            style = VideoSubtitleStyle(position = .7f, background = false),
            stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING, scanMs = 1000)
        return MovieSourceInput(label, label, p)
    }
    @Test fun sourceConfirmationNeedsNoRenderCopiesNoVideoAndIsIdempotent() = runBlocking {
        val input = input(); val source = File(Uri.parse(input.project.uri).path!!)
        val hash = MovieSourceAccess.inspect(context, Uri.fromFile(source))
        val id = UUID.randomUUID().toString()
        val saved = library.confirmSources(id, "Original", listOf(input))
        assertEquals(input.project.uri, saved.episodes.single().mediaUri)
        assertEquals(input.project.cues, saved.episodes.single().source!!.cues)
        assertEquals(input.project.style, saved.episodes.single().source!!.style)
        assertEquals(hash.first, saved.episodes.single().source!!.sha256)
        assertEquals(0, library.folder(id).listFiles().orEmpty().count { it.extension in setOf("input", "mp4") })
        assertEquals(saved, library.confirmSources(id, "Duplicate", listOf(input), true))
        assertEquals(hash, MovieSourceAccess.inspect(context, Uri.fromFile(source)))
        val reopened = MovieLibrary(context, validateVideo = { 1000 }, makeCover = { _, _ -> })
        assertEquals(saved.episodes, reopened.find(id)!!.episodes)
    }
    @Test fun optionalManagedCopyIsByteIdenticalAndSurvivesOriginalRemoval() = runBlocking {
        val input = input(); val original = File(Uri.parse(input.project.uri).path!!)
        val saved = library.confirmSources(UUID.randomUUID().toString(), "Independent", listOf(input), true)
        val copy = File(saved.episodes.single().path)
        assertNotEquals(original.canonicalPath, copy.canonicalPath)
        assertArrayEquals(original.readBytes(), copy.readBytes())
        original.delete()
        assertTrue(library.isAvailable(saved.episodes.single()))
    }
    @Test fun missingSourceAndUntranslatedTimelineNeverPublishPartialMovie() = runBlocking {
        val good = input("good"); val missing = input("missing")
        File(Uri.parse(missing.project.uri).path!!).delete()
        val id = UUID.randomUUID().toString()
        try { library.confirmSources(id, "Missing", listOf(good, missing), true); fail() } catch (_: IllegalArgumentException) { }
        assertNull(library.find(id))
        assertTrue(library.folder(id).listFiles().orEmpty().none { it.extension == "input" })
        val incomplete = good.copy(project = good.project.copy(cues = good.project.cues.map { it.copy(translation = "") }))
        try { library.confirmSources(id, "Empty", listOf(incomplete)); fail() } catch (_: IllegalArgumentException) { }
        assertNull(library.find(id))
    }
    @Test fun legacyRecordWithoutSnapshotStillReadsAndUnknownSourceSchemaFails(): Unit = runBlocking {
        val old = MovieRecord(UUID.randomUUID().toString(), "Old", listOf(MovieEpisode("one", "Old", "/owned/render.mp4", 1000)))
        val json = old.json(); json.getJSONArray("episodes").getJSONObject(0).remove("source")
        assertEquals(1,json.getInt("schema"))
        assertEquals(old, MovieRecord.from(json))
        val input = input(); val saved = library.confirmSources(UUID.randomUUID().toString(), "New", listOf(input))
        assertEquals(2,saved.json().getInt("schema"))
        assertEquals(saved,MovieRecord.from(saved.json()))
        assertThrows(IllegalArgumentException::class.java) { MovieRecord.from(saved.json().put("schema",3)) }
        val changed = saved.json(); changed.getJSONArray("episodes").getJSONObject(0).getJSONObject("source").put("schema", 99)
        assertThrows(IllegalArgumentException::class.java) { MovieRecord.from(changed) }
    }
    @Test fun editedSnapshotRequiresExplicitApplyPreservesProgressAndRejectsStaleRevision() = runBlocking {
        val input = input(); val saved = library.confirmSources(UUID.randomUUID().toString(), "Edit", listOf(input))
        library.progress(saved.id, input.episodeId, 600)
        val editor = library.createSourceEditor(saved.id, input.episodeId)
        assertNotEquals(input.project.id, editor.id)
        val changed = editor.copy(cues = editor.cues.map { if (it.id == "two") it.copy(translation = "Tạm biệt", approved = false) else it })
        assertEquals("Hẹn gặp lại", library.find(saved.id)!!.episodes.single().source!!.cues.last().translation)
        val revision = saved.episodes.single().source!!.revision
        val applied = library.refreshSourceSnapshot(saved.id, input.episodeId, changed, revision)
        assertEquals(600L, applied.episodes.single().positionMs)
        assertTrue(applied.episodes.single().source!!.cues.first().approved)
        assertEquals("Tạm biệt", applied.episodes.single().source!!.cues.last().translation)
        try { library.refreshSourceSnapshot(saved.id, input.episodeId, editor, revision); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(applied.episodes, library.find(saved.id)!!.episodes)
    }
    @Test fun sourceReplacementAndDeletingLibraryCannotChangeOrDeleteReferencedSource() = runBlocking {
        val input = input(); val source = File(Uri.parse(input.project.uri).path!!)
        val saved = library.confirmSources(UUID.randomUUID().toString(), "Keep source", listOf(input))
        val replacement = input("different").project
        try { library.refreshSourceSnapshot(saved.id, input.episodeId, replacement, saved.episodes.single().source!!.revision); fail() }
        catch (_: IllegalArgumentException) { }
        library.remove(saved.id)
        assertTrue(source.isFile)
    }
    @Test fun portableSnapshotHidesLocalReferencesAndRejectsTimelineTampering(): Unit = runBlocking {
        val saved = library.confirmSources(UUID.randomUUID().toString(), "Portable", listOf(input()))
        val json = saved.json(portable = true)
        assertFalse(json.toString().contains(context.cacheDir.absolutePath))
        val source = json.getJSONArray("episodes").getJSONObject(0).getJSONObject("source")
        assertEquals("", source.getString("projectId")); assertEquals("", source.getString("uri"))
        assertEquals(saved.episodes.single().source!!.cues, MovieRecord.from(json).episodes.single().source!!.cues)
        source.getJSONArray("cues").getJSONObject(0).put("translation", "Tampered")
        assertThrows(IllegalArgumentException::class.java) { MovieRecord.from(json) }
    }

    @Test fun appendKeepsOldEpisodesProgressAndMovieIdentityAndIsIdempotent() = runBlocking {
        val first=input("EP01");val extra=input("EP02")
        val original=library.confirmSources(UUID.randomUUID().toString(),"My series",listOf(first),true)
        library.progress(original.id,first.episodeId,450)
        val before=library.find(original.id)!!
        val appended=library.confirmSources(original.id,"Must not rename",listOf(extra),true,appendToExisting=true)
        assertEquals(before.id,appended.id);assertEquals(before.title,appended.title)
        assertEquals(before.confirmedAt,appended.confirmedAt);assertEquals(before.coverPath,appended.coverPath)
        assertEquals(before.episodes.single(),appended.episodes.first())
        assertEquals(listOf("EP01","EP02"),appended.episodes.map{it.id})
        assertTrue(appended.updatedAt>before.updatedAt)
        assertEquals(appended,library.confirmSources(original.id,"Repeat",listOf(extra),true,appendToExisting=true))
        assertTrue(File(before.episodes.single().path).isFile)
        val reopened=MovieLibrary(context,validateVideo={1000},makeCover={_,_->})
        assertEquals(appended.episodes,reopened.find(original.id)!!.episodes)
    }
    @Test fun failedAppendNeverPublishesPartialEpisodesOrDeletesOldCopies() = runBlocking {
        val original=library.confirmSources(UUID.randomUUID().toString(),"Keep",listOf(input("old")),true)
        val good=input("good");val missing=input("missing")
        File(Uri.parse(missing.project.uri).path!!).delete()
        val existingFiles=library.folder(original.id).listFiles()!!.map{it.name}.toSet()
        try {library.confirmSources(original.id,"Keep",listOf(good,missing),true,appendToExisting=true);fail()}
        catch(_:IllegalArgumentException){}
        assertEquals(original,library.find(original.id))
        assertEquals(existingFiles,library.folder(original.id).listFiles()!!.map{it.name}.toSet())
        try {library.confirmSources(UUID.randomUUID().toString(),"Missing",listOf(good),appendToExisting=true);fail()}
        catch(_:IllegalArgumentException){}
    }
    @Test fun appendBatchTargetRoundTripsAndOnlyItsOwnNewEpisodesMarkItSaved() = runBlocking {
        val first=input("old");val extra=input("new")
        val original=library.confirmSources(UUID.randomUUID().toString(),"Series",listOf(first))
        val batch=com.gemini.ocr.batch.BatchProject(UUID.randomUUID().toString(),"Series",com.gemini.ocr.batch.BatchKind.SERIES,
            items=listOf(com.gemini.ocr.batch.BatchItem(extra.episodeId,"new.mp4")),targetMovieId=original.id)
        val restored=com.gemini.ocr.batch.BatchProject.from(JSONObject(batch.json().toString()))
        assertEquals(batch,restored)
        assertFalse(com.gemini.ocr.batch.BatchLibraryTarget.saved(restored,listOf(original)))
        val appended=library.confirmSources(original.id,"Series",listOf(extra),appendToExisting=true)
        assertTrue(com.gemini.ocr.batch.BatchLibraryTarget.saved(restored,listOf(appended)))
        assertFalse(com.gemini.ocr.batch.BatchLibraryTarget.saved(restored.copy(items=emptyList()),listOf(appended)))
        assertEquals("",com.gemini.ocr.batch.BatchProject.from(batch.json().apply{remove("targetMovieId")}).targetMovieId)
    }

    /** Android emits Unix file paths; encode this Windows JVM fixture as an actual file URL. */
    @Implements(Uri::class)
    class WindowsFileUris {
        companion object {
            @JvmStatic @Implementation fun fromFile(file: File): Uri = Uri.parse(file.toURI().toASCIIString())
        }
    }
}
