package com.gemini.ocr.mtprobe

/**
 * At most two native output Results survive between decoder calls. Outputs are borrowed from
 * their Result and must never be individually closed while a later run still references them.
 * The previous owners stay alive through run(). Adopt the new Result only after validation.
 */
internal class Mt60ResultOwners<T : AutoCloseable> : AutoCloseable {
    var cross: T? = null
        private set
    var decoder: T? = null
        private set

    fun adopt(next: T, replacesCross: Boolean) {
        require(next !== cross && next !== decoder) { "New native Result must have distinct ownership" }
        require(cross != null || replacesCross) { "Initial Result must own a nonempty cross-attention cache" }
        val previousCross = cross
        val previousDecoder = decoder
        if (replacesCross) cross = next
        decoder = next
        closeDistinct(listOfNotNull(previousCross, previousDecoder).filter { it !== cross && it !== decoder })
    }

    val retainedCount: Int get() = when {
        cross == null && decoder == null -> 0
        cross === decoder -> 1
        else -> listOfNotNull(cross, decoder).size
    }

    fun owns(value: T): Boolean = value === cross || value === decoder

    override fun close() {
        val previousCross = cross; val previousDecoder = decoder
        cross = null; decoder = null
        closeDistinct(listOfNotNull(previousCross, previousDecoder))
    }

    private fun closeDistinct(values: List<T>) {
        var failure: Throwable? = null
        val closed = mutableListOf<T>() // At most two entries, never a history of decoder steps.
        for (value in values) {
            if (closed.any { it === value }) continue
            closed += value
            try { value.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
