package com.gemini.ocr.video

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Reopen only a failed local decoder, never OCR, storage, source validation or API work. */
internal object VideoDecodeRecovery {
    const val MAX_REOPENS=2

    suspend fun <T> run(
        onRetry:suspend(Int,VideoDecodeException)->Unit={_,_->},
        wait:suspend(Long)->Unit={delay(it)},
        scan:suspend()->T
    ):T {
        var reopens=0
        while(true){
            currentCoroutineContext().ensureActive()
            try {
                val result=scan()
                currentCoroutineContext().ensureActive()
                return result
            }catch(error:VideoDecodeException){
                if(!error.retryable || reopens>=MAX_REOPENS)throw error
                reopens++
                // The decoder has already persisted its last real observation and released
                // native resources. Callback/storage failures must escape without another scan.
                onRetry(reopens,error)
                currentCoroutineContext().ensureActive()
                wait(250L*reopens)
            }
        }
    }
}
