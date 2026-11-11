@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gemini.ocr.movie

import androidx.media3.datasource.DataSource
import com.gemini.ocr.video.VideoProject

/** A player-only view of an immutable manifest. Never saved as an editor/source project. */
internal data class MoviePlaybackHandle(
    val project: VideoProject,
    val episode: MovieEpisode,
    val accountEmail: String,
    val dataSourceFactory: DataSource.Factory
)
