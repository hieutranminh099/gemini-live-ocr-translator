package com.gemini.ocr.batch

import android.content.Context
import com.gemini.ocr.network.ContextualMemoryRepository
import com.gemini.ocr.network.ContextualTranslationMemory
import com.gemini.ocr.network.PhoneTranslationCache
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoStore
import com.gemini.ocr.video.VideoTranslationPlan
import java.io.File
import java.security.MessageDigest

/** Reads exact compatible reviewed entries only. Never imports, approves, corrects source or writes AI output. */
internal class BatchReviewedMemory(private val context: Context, private val testMemory: ContextualTranslationMemory? = null) {
    data class Match(val scope: ContextualTranslationMemory.Scope, val positions: List<Int>, val translations: List<String>)
    suspend fun lookup(project: VideoProject, request: VideoTranslationPlan.Batch): Match? {
        val marker = File(VideoStore(context).folder(project.id), "batch-owner")
        if (!marker.isFile || marker.length() != 36L) return null
        val id = marker.readText(Charsets.UTF_8)
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
        val batch = runCatching { BatchStore(context).load(id) }.getOrNull() ?: return null
        val scope = scope(batch, project, request) ?: return null
        val common = commonScope(batch, project, request) ?: return null
        val memory = testMemory ?: ContextualMemoryRepository.store(context)
        val positions = mutableListOf<Int>(); val translations = mutableListOf<String>()
        request.texts.forEachIndexed { index, source ->
            val contextual = memory.lookup(scope, source)
            // Explicit opt-in also authorizes the UI's selected-film no-history scope, but only
            // human-approved content. A Verified import is not permission to ignore dialogue history.
            val entry = contextual ?: memory.lookup(common, source)?.takeIf { it.quality == ContextualTranslationMemory.Quality.USER_APPROVED }
            if (entry != null && !VideoTranslationPlan.hasUnexpectedCyrillic(entry.translation, project.glossary)) {
                positions += index; translations += entry.translation
            }
        }
        return if (positions.isEmpty()) null else Match(scope, positions, translations)
    }
    companion object {
        internal fun commonScope(batch: BatchProject, project: VideoProject, request: VideoTranslationPlan.Batch): ContextualTranslationMemory.Scope? {
            val frozen = scope(batch, project, request) ?: return null
            val fingerprint = StandaloneTranslator.cacheNamespace(project.profile, project.glossary, emptyList(),
                batch.reviewedFilmId, batch.reviewedDomain)
            return frozen.copy(contextFingerprint = fingerprint)
        }
        internal fun scope(batch: BatchProject, project: VideoProject, request: VideoTranslationPlan.Batch): ContextualTranslationMemory.Scope? {
            if (!batch.useReviewedMemory || batch.reviewedFilmId.isBlank() || batch.reviewedDomain.isBlank() ||
                project.profile != batch.reviewedProfile || project.glossary != batch.reviewedGlossary ||
                batch.reviewedModel != StandaloneTranslator.cacheModelIdentity()) return null
            val glossary = PhoneTranslationCache.parseGlossary(project.glossary).toSortedMap()
            val encoded = glossary.entries.joinToString("") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" }
            val glossaryVersion = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val namespace = StandaloneTranslator.cacheNamespace(project.profile, project.glossary, emptyList(),
                filmId = batch.reviewedFilmId, domain = "official-video:${project.translationMode.name}:${batch.reviewedDomain}:private-batch:${batch.id}")
            // Never pretend an unrelated Live or empty-context scope is equivalent to a video request.
            val fingerprint = BatchTranslationCache.key(namespace, request.videoContext)
            return ContextualTranslationMemory.Scope(batch.reviewedFilmId, batch.reviewedDomain, batch.reviewedModel, glossaryVersion, fingerprint)
        }
    }
}
