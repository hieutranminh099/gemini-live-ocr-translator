package com.gemini.ocr

import android.net.Uri

/** Public video coordinates only; never accepts arbitrary navigation or credential query fields. */
internal data class YouTubeProbeTarget(val videoId: String, val startSeconds: Int) {
    val uri: Uri get() = Uri.parse("https://www.youtube.com/watch?v=$videoId&t=${startSeconds}s")

    companion object {
        /** Only for a parent-observed Copy link action; raw clipboard data is never reported. */
        fun fromCopiedLink(raw: String, startSeconds: Int): YouTubeProbeTarget {
            try {
                require(raw.length in 1..2048)
                val uri = Uri.parse(raw.trim())
                require(uri.queryParameterNames.all {
                    it in setOf("v", "t", "start", "si", "feature") || it.startsWith("utm_")
                })
                val clean = uri.buildUpon().clearQuery()
                when (uri.host?.lowercase()) {
                    "youtube.com", "www.youtube.com", "m.youtube.com" -> {
                        require(uri.getQueryParameters("v").size == 1)
                        clean.appendQueryParameter("v", uri.getQueryParameter("v"))
                    }
                    "youtu.be" -> Unit
                    else -> throw IllegalArgumentException()
                }
                // The original scheme, authority, path, port and fragment survive this
                // builder and must pass the same strict ordinary watch-link validation.
                return parse(clean.build().toString(), startSeconds)
            } catch (_: Exception) {
                throw IllegalArgumentException("The copied item is not a supported public YouTube video link")
            }
        }

        fun parse(raw: String, startSeconds: Int): YouTubeProbeTarget {
            require(startSeconds in 0..21600) { "Research start time must be 0..21600 seconds" }
            val uri = Uri.parse(raw)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null) {
                "Only an ordinary public HTTPS YouTube watch link is accepted"
            }
            val id = when (uri.host?.lowercase()) {
                "youtube.com", "www.youtube.com", "m.youtube.com" -> {
                    require(uri.path == "/watch" && uri.queryParameterNames.all { it in setOf("v", "t", "start") })
                    require(uri.getQueryParameters("v").size == 1)
                    uri.getQueryParameter("v")
                }
                "youtu.be" -> {
                    require(uri.pathSegments.size == 1 && uri.queryParameterNames.all { it in setOf("t", "start") })
                    uri.pathSegments.single()
                }
                else -> throw IllegalArgumentException("Expected a public YouTube video link")
            }
            require(id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Expected an eleven-character video id" }
            return YouTubeProbeTarget(id, startSeconds)
        }
    }
}
