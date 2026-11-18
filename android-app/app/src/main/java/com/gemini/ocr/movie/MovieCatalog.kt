package com.gemini.ocr.movie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.gemini.ocr.batch.BatchOrder
import java.io.File

internal enum class MovieFilter(val label: String) {
    ALL("Tất cả"), TRANSLATED("Đã dịch"), PROCESSING("Đang xử lý"),
    WATCHED("Đã xem"), IMPORTED("Chưa xử lý")
}

internal enum class MovieWatchState(val label: String) {
    NEW("Chưa xem"), STARTED("Đang xem"), FINISHED("Đã xem")
}

internal fun MovieEpisode.watchState(): MovieWatchState = when {
    positionMs <= 0 -> MovieWatchState.NEW
    movieWatchedToEnd(positionMs, durationMs) -> MovieWatchState.FINISHED
    else -> MovieWatchState.STARTED
}

private val legacyExportName = Regex("[a-f0-9-]{36}-[0-9]+\\.mp4")

internal fun MovieEpisode.importedVideo(movieId: String): Boolean =
    imported || source == null && id == movieId && File(path).name.startsWith("video.")

internal fun MovieEpisode.translatedVideo(): Boolean =
    source?.cues?.let { it.isNotEmpty() && it.all { cue -> cue.translation.isNotBlank() } } == true ||
        source == null && legacyExportName.matches(File(path).name)

internal fun matchingMovieEpisodes(movie: MovieRecord, query: String, filter: MovieFilter): List<MovieEpisode> {
    val term = query.trim()
    val titleMatch = movie.title.contains(term, ignoreCase = true)
    return movie.episodes.filter { episode ->
        (titleMatch || episode.title.contains(term, ignoreCase = true)) && when (filter) {
            MovieFilter.ALL -> true
            MovieFilter.TRANSLATED -> episode.translatedVideo()
            MovieFilter.WATCHED -> episode.watchState() == MovieWatchState.FINISHED
            MovieFilter.IMPORTED -> episode.importedVideo(movie.id)
            MovieFilter.PROCESSING -> false
        }
    }
}

/** Lists are reordered only when the user accepts the suggestion and every episode has a unique identity. */
internal fun suggestedMovieOrder(episodes: List<MovieEpisode>): List<String>? {
    if (episodes.size < 2) return null
    val identified = episodes.map { it.id to BatchOrder.identify(it.title) }
    if (identified.any { it.second.episode == null || it.second.part != null }) return null
    if (identified.any { it.second.season == null } && identified.any { it.second.season != null }) return null
    if (identified.map { it.second.season to it.second.episode }.distinct().size != episodes.size) return null
    val order = identified.sortedWith { left, right ->
        BatchOrder.natural(left.second.season ?: "0", right.second.season ?: "0").takeIf { it != 0 }
            ?: BatchOrder.natural(checkNotNull(left.second.episode), checkNotNull(right.second.episode))
    }.map { it.first }
    return order.takeIf { it != episodes.map(MovieEpisode::id) }
}

/** Existing covers are already generated at import. Downsample legacy images and memoize by file revision. */
internal object MovieCoverCache {
    private data class Cover(val movieId: String, val path: String, val modified: Long, val bytes: Long)
    private val images = object : LruCache<Cover, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Cover, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    fun read(movieId: String, path: String): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = Cover(movieId, path, file.lastModified(), file.length())
        return synchronized(images) {
            images.get(key) ?: decode(path)?.also { images.put(key, it) }
        }
    }

    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > 640) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
