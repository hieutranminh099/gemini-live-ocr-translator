package com.gemini.ocr.capture

/**
 * Owns one hardware image independently of the OCR worker, which owns only a copied Bitmap.
 * A synchronous [withLatest] copy and replacing/closing that image cannot overlap. Never run
 * native OCR or suspend inside the copy callback: the resource must leave the callback as bytes.
 * Reader identity, rather than equality, prevents late callbacks from a replaced reader publishing.
 */
class LatestImageMailbox<Reader : Any, Image : AutoCloseable> : AutoCloseable {
    data class Entry<Image>(val image: Image, val sequence: Long, val acquiredAtMs: Long)

    companion object {
        // One retained static image plus the two free slots acquireLatestImage needs to drop backlog.
        const val REQUIRED_MAX_IMAGES = 3
    }

    private var reader: Reader? = null
    private var latest: Entry<Image>? = null
    private var sequence = 0L
    private var closed = false

    /** Activate a replacement reader and release its predecessor's retained image exactly once. */
    @Synchronized fun activate(source: Reader) {
        check(!closed) { "Image mailbox is closed" }
        if (reader === source) return
        reader = source
        releaseLatest()
    }

    /**
     * Transfers ownership of [image] even when rejected. Callers must not close it afterwards.
     * This runs for every hardware arrival, including while native OCR processes an earlier Bitmap.
     */
    @Synchronized fun offer(source: Reader, image: Image, acquiredAtMs: Long): Boolean {
        if (closed || reader !== source) {
            closeQuietly(image)
            return false
        }
        // An accidental repeat offer must not close an image still retained by this mailbox.
        if (latest?.image === image) return false
        val previous = latest
        latest = Entry(image, ++sequence, acquiredAtMs)
        previous?.image?.let(::closeQuietly)
        return true
    }

    /** The callback may read/copy the current image, but must not retain or return the Image itself. */
    @Synchronized fun <T> withLatest(source: Reader, copy: (Entry<Image>) -> T): T? {
        if (closed || reader !== source) return null
        return latest?.let(copy)
    }

    /** Late cleanup belonging to an old reader must never close its replacement's retained image. */
    @Synchronized fun deactivate(source: Reader) {
        if (reader !== source) return
        reader = null
        releaseLatest()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        reader = null
        releaseLatest()
    }

    private fun releaseLatest() {
        val previous = latest
        latest = null
        previous?.image?.let(::closeQuietly)
    }

    private fun closeQuietly(image: Image) {
        try { image.close() } catch (_: Exception) { /* A closed reader can invalidate its Images. */ }
    }
}
