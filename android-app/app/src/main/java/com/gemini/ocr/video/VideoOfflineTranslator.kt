package com.gemini.ocr.video

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** This route never calls downloadModelIfNeeded or any cloud translator during processing. */
class VideoOfflineTranslator : AutoCloseable {
    private val translator=Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.VIETNAMESE).build())
    suspend fun prepare() { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
    suspend fun translate(text:String):String {
        currentCoroutineContext().ensureActive()
        val result=withContext(NonCancellable){translator.translate(text).await()}
        currentCoroutineContext().ensureActive()
        require(result.isNotBlank()){ "Model offline không trả bản dịch." };return result.trim()
    }
    override fun close()=translator.close()
    companion object {
        const val MODEL="ML Kit zh→vi 17.0.3 (thử nghiệm)"
        suspend fun ready():Boolean {
            val manager=RemoteModelManager.getInstance()
            return listOf(TranslateLanguage.CHINESE,TranslateLanguage.VIETNAMESE).all { language ->
                manager.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()
            }
        }
    }
}
