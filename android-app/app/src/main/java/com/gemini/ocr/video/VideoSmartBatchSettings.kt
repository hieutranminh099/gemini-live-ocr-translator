package com.gemini.ocr.video

import android.content.Context

/** Video-only tuning. Changing this never changes the user's model, backend or OCR sampling. */
class VideoSmartBatchSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("video-smart-batch", Context.MODE_PRIVATE)
    var groupSize: Int
        get() = preferences.getInt("group_size", DEFAULT_GROUP_SIZE).takeIf { it in GROUP_SIZES } ?: DEFAULT_GROUP_SIZE
        set(value) { require(value in GROUP_SIZES); preferences.edit().putInt("group_size", value).apply() }
    // The next request consumes the preceding accepted translations as context. Overlapping
    // these requests changes that context. Parallel batch lanes use separate videos.
    val maxConcurrentRequests: Int get() = 1
    var economyMode: Boolean
        get() = preferences.getBoolean("economy_mode", false)
        set(value) { preferences.edit().putBoolean("economy_mode", value).apply() }
    companion object {
        val GROUP_SIZES = listOf(10, 20, 30, 50)
        const val DEFAULT_GROUP_SIZE = 20
    }
}
