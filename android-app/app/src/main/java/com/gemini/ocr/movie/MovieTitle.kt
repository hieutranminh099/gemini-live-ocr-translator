package com.gemini.ocr.movie

internal fun movieTitle(value:String):String = value.trim().also {
    require(it.isNotEmpty()) { "Tên phim không được để trống." }
    require(it.length<=200) { "Tên phim không được dài quá 200 ký tự." }
}

/** Separate title clock: watching a film must not overwrite a more recent rename. */
internal fun MovieRecord.mergeMovieTitle(other:MovieRecord):MovieRecord {
    require(id==other.id)
    return if(other.titleUpdatedAt>titleUpdatedAt ||
        (other.titleUpdatedAt>0 && other.titleUpdatedAt==titleUpdatedAt && other.title>title))
        copy(title=other.title,titleUpdatedAt=other.titleUpdatedAt) else this
}
