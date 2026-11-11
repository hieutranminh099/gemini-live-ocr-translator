package com.gemini.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Candidate measurement only. No production fallback is enabled before quality evaluation. */
internal class AndroidOfflineProbe : AutoCloseable {
    private val serial = Mutex()
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE)
        .setTargetLanguage(TranslateLanguage.VIETNAMESE).build())

    suspend fun prepare() {
        // Downloads public language packs only. This happens before capture timing starts.
        translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
    }

    suspend fun translate(text: String): String = serial.withLock {
        require(text.isNotBlank())
        currentCoroutineContext().ensureActive()
        // ML Kit tasks do not cancel. Keep one decoder owner until its task ends;
        // then propagate cancellation before any obsolete output reaches the pipeline.
        val translated = withContext(NonCancellable) { translator.translate(text).await() }
        currentCoroutineContext().ensureActive()
        check(translated.isNotBlank()) { "On-device model returned no text" }
        translated
    }

    override fun close() = translator.close()
}
