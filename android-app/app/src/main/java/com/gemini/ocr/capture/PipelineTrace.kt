package com.gemini.ocr.capture

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

/** Durations use Android's monotonic clock. Never log OCR, translations or credentials. */
object PipelineTrace {
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("event", name).put("at_ms", SystemClock.elapsedRealtime())
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        Log.i("OCRPipeline", json.toString())
    }
}
