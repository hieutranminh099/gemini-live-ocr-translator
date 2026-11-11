package com.gemini.ocr.batch

import com.gemini.ocr.movie.MovieRecord

/** Source project IDs identify episodes; titles and episode numbers never overwrite old entries. */
object BatchLibraryTarget {
    fun movieId(batch: BatchProject) = batch.targetMovieId.ifBlank { batch.id }
    fun saved(batch: BatchProject, movies: List<MovieRecord>): Boolean {
        val movie = movies.firstOrNull { it.id == movieId(batch) } ?: return false
        return if (batch.targetMovieId.isBlank()) true else batch.items.isNotEmpty() &&
            batch.items.all { item -> movie.episodes.any { it.id == item.projectId } }
    }
}
