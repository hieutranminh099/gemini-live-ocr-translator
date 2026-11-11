package com.gemini.ocr.network

import android.content.Context
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/** The UI and capture pipeline share one store, so an explicit edit invalidates their same LRU. */
object ContextualMemoryRepository {
    data class ReviewCandidate(
        val scope: ContextualTranslationMemory.Scope,
        val source: String,
        val translation: String,
        val requestId: String,
        val regionId: String,
        val screenGeneration: Long,
        val origin: String,
        val confidence: Double? = null,
    ) {
        init {
            require(requestId.isNotBlank() && requestId.length <= 128)
            require(regionId.isNotBlank() && regionId.length <= 160 && screenGeneration >= 0)
            require(origin.isNotBlank() && origin.length <= 160)
            require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0)
            // Reuse the record's text validation without inventing a quality/confidence score.
            ContextualTranslationMemory.Entry(scope, source, translation, "review-candidate",
                ContextualTranslationMemory.Provenance("model://accepted-outcome", "unreviewed-model-output"), confidence ?: 0.0)
        }
    }

    @Volatile private var instance: ContextualTranslationMemory? = null
    private val pendingCandidate = MutableStateFlow<ReviewCandidate?>(null)
    val candidate: StateFlow<ReviewCandidate?> = pendingCandidate.asStateFlow()

    fun store(context: Context): ContextualTranslationMemory = instance ?: synchronized(this) {
        instance ?: ContextualTranslationMemory(context.applicationContext).also { instance = it }
    }

    /**
     * Call BEFORE dispatch, with the exact namespace frozen for that request's prompt.
     * An unset film disables memory. Do not recompute this after committing dialogue history.
     */
    fun snapshotScope(settings: AppSettings, requestNamespace: String,
                      modelVersion: String = StandaloneTranslator.cacheModelIdentity()): ContextualTranslationMemory.Scope? {
        val film = settings.memoryFilmId
        if (film.isBlank() || !requestNamespace.matches(Regex("[a-f0-9]{64}"))) return null
        val glossary = PhoneTranslationCache.parseGlossary(settings.localGlossary).toSortedMap()
        val encodedGlossary = glossary.entries.joinToString("") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" }
        return ContextualTranslationMemory.Scope(film, settings.memoryDomain, modelVersion, sha256(encodedGlossary), requestNamespace)
    }

    /** Caller must first accept its requestId/regionId/screenGeneration guard. No auto-approval or DB I/O. */
    fun publishCandidate(value: ReviewCandidate) { pendingCandidate.value = value }
    fun clearCandidate() { pendingCandidate.value = null }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
