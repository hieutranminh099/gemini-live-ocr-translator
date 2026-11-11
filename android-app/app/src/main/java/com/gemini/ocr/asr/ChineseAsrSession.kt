package com.gemini.ocr.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class ChineseAsrResult(
    val text: String,
    val language: String,
    val event: String,
    val tokens: List<String>,
    val timestampsSeconds: List<Float>,
    val qualityFlags: Set<String>,
)

data class AsrSpeechWindow(
    val startSample: Long,
    val samples: FloatArray,
    val coreStartSample: Long = startSample,
    val coreEndSample: Long = startSample + samples.size,
    // Silero's public SpeechSegment does not identify why a segment ended. Do not infer
    // continuation merely because context padding overlaps a neighboring utterance.
    val isContinuation: Boolean = false,
) {
    val endSample: Long get() = startSample + samples.size
    init {
        require(startSample >= 0 && samples.isNotEmpty() && samples.size <= ChineseAsrSession.MAX_WINDOW_SAMPLES)
        require(coreStartSample >= startSample && coreEndSample > coreStartSample && coreEndSample <= endSample)
    }
}

/** One CPU recognizer for a job or an entire batch. Call blocking native methods off the UI thread. */
class ChineseAsrSession private constructor(
    private val models: ChineseAsrModels,
    private val recognizer: OfflineRecognizer,
    val threads: Int,
) : Closeable {
    private var closed = false
    private val detectors = ConcurrentHashMap.newKeySet<ChineseAsrVad>()
    val fingerprint get() = models.fingerprint
    @Volatile var transcriptionCalls = 0L
        private set

    @Synchronized
    fun newVad(baseSample: Long = 0L): ChineseAsrVad {
        check(!closed)
        require(baseSample >= 0)
        return ChineseAsrVad(Vad(config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = models.vadFile.absolutePath,
                threshold = .5f, minSilenceDuration = .35f, minSpeechDuration = .25f,
                windowSize = 512, maxSpeechDuration = 18f),
            sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu", debug = false,
        )), baseSample) { detectors.remove(it) }.also { detectors += it }
    }

    /** Native decode is bounded by 20 seconds. Cancellation is checked by the caller between windows. */
    @Synchronized
    fun transcribe(samples: FloatArray): ChineseAsrResult {
        check(!closed)
        require(samples.size in 1..MAX_WINDOW_SAMPLES) { "Mỗi đoạn nhận diện phải dài không quá 20 giây" }
        require(samples.all { it.isFinite() }) { "Dữ liệu âm thanh không hợp lệ" }
        if (samples.all { kotlin.math.abs(it) < .0001f }) {
            return ChineseAsrResult("", "", "", emptyList(), emptyList(), setOf("ASR_NO_SPEECH"))
        }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            transcriptionCalls++
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val flags = linkedSetOf("ASR_CONFIDENCE_UNAVAILABLE")
            val language = result.lang.removePrefix("<|").removeSuffix("|>")
            val event = result.event.removePrefix("<|").removeSuffix("|>")
            val text = result.text.trim()
            if (language.isNotBlank() && language != "zh") flags += "ASR_LANGUAGE_UNCERTAIN"
            if (result.timestamps.size != result.tokens.size || result.timestamps.any { !it.isFinite() || it < 0f || it * SAMPLE_RATE > samples.size }) {
                flags += "ASR_TIMING_UNCERTAIN"
            }
            if (event.isNotBlank() && !event.equals("Speech", true)) {
                flags += "ASR_NON_SPEECH"
                return ChineseAsrResult("", language, event, emptyList(), emptyList(), flags)
            }
            if (text.isBlank()) flags += "ASR_UNCLEAR_AUDIO"
            return ChineseAsrResult(text, language, event, result.tokens.toList(), result.timestamps.toList(), flags)
        } finally { stream.release() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            try { detectors.toList().forEach { it.close() } } finally { recognizer.release() }
        } finally {
            try { models.releaseSession() } finally { sessionLease.unlock() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 20
        private val sessionLease = Mutex()

        suspend fun open(models: ChineseAsrModels, threads: Int = 2): ChineseAsrSession {
            require(threads in 1..4)
            sessionLease.lock()
            var acquiredModels = false
            var openedRecognizer: OfflineRecognizer? = null
            try {
                return withContext(Dispatchers.Default) {
                    models.acquireSession()
                    acquiredModels = true
                    val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(model = models.modelFile.absolutePath,
                                language = "zh", useInverseTextNormalization = true),
                            tokens = models.tokensFile.absolutePath, numThreads = threads, provider = "cpu", debug = false,
                        ),
                        decodingMethod = "greedy_search",
                    ))
                    openedRecognizer = recognizer
                    ChineseAsrSession(models, recognizer, threads)
                }
            } catch (error: Throwable) {
                try { openedRecognizer?.release() } finally {
                    try { if (acquiredModels) models.releaseSession() } finally { sessionLease.unlock() }
                }
                throw error
            }
        }
    }
}

/** Streaming Silero VAD, with a bounded rolling context buffer and absolute 16 kHz positions. */
class ChineseAsrVad internal constructor(private val vad: Vad, private val baseSample: Long,
    private val onClose: (ChineseAsrVad) -> Unit = {}) : Closeable {
    private var closed = false
    private var finished = false
    private val ring = AsrPcmRing(ChineseAsrSession.MAX_WINDOW_SAMPLES)
    private val pending = ArrayList<AsrSpeechWindow>()

    @Synchronized
    fun accept(samples: FloatArray) {
        check(!closed && !finished)
        require(samples.all { it.isFinite() })
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            val block = samples.copyOfRange(offset, end)
            ring.append(block)
            vad.acceptWaveform(block)
            collect()
            offset = end
        }
    }

    @Synchronized
    fun drain(): List<AsrSpeechWindow> {
        check(!closed)
        return pending.toList().also { pending.clear() }
    }

    @Synchronized
    fun finish() {
        check(!closed)
        if (finished) return
        finished = true
        vad.flush()
        collect()
    }

    private fun collect() {
        while (!vad.empty()) {
            val segment = vad.front()
            try {
                if (segment.samples.isNotEmpty()) {
                    val speechStart = segment.start.coerceAtLeast(0)
                    val speechEnd = speechStart + segment.samples.size
                    check(speechStart >= ring.firstSample) { "Đoạn giọng nói vượt giới hạn bộ đệm" }
                    val start = maxOf(ring.firstSample, speechStart - PADDING_SAMPLES)
                    val end = minOf(ring.totalSamples, speechEnd + PADDING_SAMPLES)
                    check(end > start && end - start <= ChineseAsrSession.MAX_WINDOW_SAMPLES)
                    val coreStart = maxOf(start, speechStart)
                    val coreEnd = minOf(end, speechEnd)
                    if (coreEnd > coreStart) pending += AsrSpeechWindow(baseSample + start, ring.copy(start, end),
                        coreStartSample = baseSample + coreStart, coreEndSample = baseSample + coreEnd)
                }
            } finally { vad.pop() }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        ring.clear()
        try { vad.release() } finally { onClose(this) }
    }

    companion object { const val PADDING_SAMPLES = 4800 }
}

internal class AsrPcmRing(private val capacity: Int) {
    private var data = FloatArray(capacity)
    var totalSamples = 0L
        private set
    val firstSample get() = maxOf(0L, totalSamples - data.size)
    init { require(capacity > 0) }
    fun append(block: FloatArray) {
        check(data.isNotEmpty())
        for (value in block) { data[(totalSamples % capacity).toInt()] = value; totalSamples++ }
    }
    fun copy(start: Long, end: Long): FloatArray {
        require(start >= firstSample && end <= totalSamples && end >= start && end - start <= data.size)
        return FloatArray((end - start).toInt()) { data[((start + it) % capacity).toInt()] }
    }
    fun clear() { data = FloatArray(0) }
}
