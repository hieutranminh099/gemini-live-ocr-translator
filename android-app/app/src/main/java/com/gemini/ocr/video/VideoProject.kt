package com.gemini.ocr.video

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

enum class VideoTranslationMode { OFFLINE, GEMINI, HYBRID }
enum class VideoPolishPolicy {
    NONE, FLAGGED, ALL;

    companion object {
        fun fromPersisted(name: String): VideoPolishPolicy = entries.firstOrNull { it.name == name } ?: NONE
    }
}
enum class VideoSourceMode { EMBEDDED, SIDECAR, OCR, AUTO, ASR }
enum class VideoStage { READY, EXTRACTING, TRANSLATING, REVIEW, EXPORTING, COMPLETE, PAUSED, WAITING_LIVE, FAILED }

data class VideoCue(
    val id: String, val startMs: Long, val endMs: Long, val source: String,
    val translation: String = "", val approved: Boolean = false, val origin: String = "SUBTITLE",
    val model: String = "", val error: String = "",
    val localDraft: String = "", val refinementState: String = "",
    val rawZh: String = "", val correctedZh: String = "", val draftVi: String = "", val polishedVi: String = "",
    val ocrConfidence: Float? = null, val qualityFlags: List<String> = emptyList(), val version: Int = 1
) {
    val protectedText get() = approved || (model == "user-edited" && translation.isNotBlank())
    fun json() = JSONObject().put("id", id).put("startMs", startMs).put("endMs", endMs)
        .put("source", source).put("translation", translation).put("approved", approved)
        .put("origin", origin).put("model", model).put("error", error)
        .put("localDraft", localDraft).put("refinementState", refinementState)
        .put("rawZh", rawZh).put("correctedZh", correctedZh).put("draftVi", draftVi).put("polishedVi", polishedVi)
        .put("ocrConfidence", ocrConfidence ?: JSONObject.NULL).put("qualityFlags", JSONArray(qualityFlags)).put("version", version)
    companion object {
        fun from(j: JSONObject) = VideoCue(j.getString("id"), j.getLong("startMs"), j.getLong("endMs"),
            j.getString("source"), j.optString("translation"), j.optBoolean("approved"),
            j.optString("origin", "SUBTITLE"), j.optString("model"), j.optString("error"),
            j.optString("localDraft"), j.optString("refinementState"),
            j.optString("rawZh"), j.optString("correctedZh"), j.optString("draftVi"), j.optString("polishedVi"),
            if(j.has("ocrConfidence") && !j.isNull("ocrConfidence"))j.getDouble("ocrConfidence").toFloat().also{require(it.isFinite() && it in 0f..1f)} else null,
            j.optJSONArray("qualityFlags")?.let{a->(0 until a.length()).map{a.getString(it)}} ?: emptyList(),j.optInt("version",1).also{require(it>=1)} )
    }
}

/** Normalized output rectangle, independent of the Chinese OCR input ROI. */
data class VideoSubtitleFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
            right - left >= MIN_WIDTH - .00001f && bottom - top >= MIN_HEIGHT - .00001f) {
            "Khung phụ đề cần nằm trong video và đủ rộng/cao để đọc chữ."
        }
    }
    val width get() = right - left
    val height get() = bottom - top
    fun json() = JSONArray(listOf(left, top, right, bottom))
    fun move(dx: Float, dy: Float): VideoSubtitleFrame {
        val x = dx.coerceIn(-left, 1f - right)
        val y = dy.coerceIn(-top, 1f - bottom)
        return VideoSubtitleFrame(left + x, top + y, right + x, bottom + y)
    }
    fun resize(handle: SubtitleFrameHandle, dx: Float, dy: Float): VideoSubtitleFrame = VideoSubtitleFrame(
        if (handle.left) (left + dx).coerceIn(0f, maxOf(0f, right - MIN_WIDTH)) else left,
        if (handle.top) (top + dy).coerceIn(0f, maxOf(0f, bottom - MIN_HEIGHT)) else top,
        if (handle.right) (right + dx).coerceIn(minOf(1f, left + MIN_WIDTH), 1f) else right,
        if (handle.bottom) (bottom + dy).coerceIn(minOf(1f, top + MIN_HEIGHT), 1f) else bottom)
    companion object {
        const val MIN_WIDTH = .10f
        const val MIN_HEIGHT = .04f
        val BOTTOM = VideoSubtitleFrame(.03f, .75f, .97f, .93f)
        val TOP = VideoSubtitleFrame(.03f, .06f, .97f, .24f)
        val CENTER = VideoSubtitleFrame(.10f, .42f, .90f, .60f)
        fun from(j: JSONArray?): VideoSubtitleFrame? = j?.let {
            require(it.length() == 4) { "Khung phụ đề không hợp lệ." }
            VideoSubtitleFrame(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
        }
        fun between(x1: Float, y1: Float, x2: Float, y2: Float): VideoSubtitleFrame {
            val left = minOf(x1, x2).coerceIn(0f, 1f - MIN_WIDTH)
            val top = minOf(y1, y2).coerceIn(0f, 1f - MIN_HEIGHT)
            return VideoSubtitleFrame(left, top, maxOf(x1, x2).coerceIn(left + MIN_WIDTH, 1f),
                maxOf(y1, y2).coerceIn(top + MIN_HEIGHT, 1f))
        }
        fun fromRoi(roi: List<Float>): VideoSubtitleFrame {
            require(roi.size == 4 && roi.all { it.isFinite() })
            return between(roi[0], roi[1], roi[2], roi[3])
        }
    }
}

enum class SubtitleFrameHandle(val left: Boolean = false, val top: Boolean = false,
    val right: Boolean = false, val bottom: Boolean = false) {
    LEFT(left = true), TOP(top = true), RIGHT(right = true), BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true), TOP_RIGHT(right = true, top = true),
    BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true)
}

data class VideoSubtitleStyle(val position: Float = .86f, val sizeFraction: Float = .045f,
    val background: Boolean = false, val outline: Boolean = true, val frame: VideoSubtitleFrame? = null,
    val blurOriginal: Boolean = true, val yellowText: Boolean = true,
    val legacyDefault: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("position", position).put("sizeFraction", sizeFraction)
        .put("background", background).put("outline", outline).put("frame", frame?.json() ?: JSONObject.NULL)
        .also { if (!legacyDefault) it.put("blurOriginal", blurOriginal).put("yellowText", yellowText) }
    /** Rendering upgrades the old stock preset without rewriting a confirmed library snapshot. */
    fun displayStyle(): VideoSubtitleStyle = if (legacyDefault && background && outline && frame == null &&
        kotlin.math.abs(position - .86f) < .00001f && kotlin.math.abs(sizeFraction - .045f) < .00001f)
        VideoSubtitleStyle() else this
    companion object {
        fun from(j: JSONObject?): VideoSubtitleStyle {
            if (j == null) return VideoSubtitleStyle()
            // Keep raw old fields for the immutable movie-source revision; upgrade only when rendering.
            val legacyAppearance = !j.has("blurOriginal") && !j.has("yellowText")
            return VideoSubtitleStyle(
                j.optDouble("position", .86).toFloat().coerceIn(.05f,.95f),
                j.optDouble("sizeFraction", .045).toFloat().coerceIn(.02f,.09f),
                j.optBoolean("background",true),j.optBoolean("outline",true),VideoSubtitleFrame.from(j.optJSONArray("frame")),
                j.optBoolean("blurOriginal",false),j.optBoolean("yellowText",false),legacyAppearance)
        }
    }
}

data class VideoProject(
    val id: String, val title: String, val uri: String, val durationMs: Long, val width: Int, val height: Int,
    val sourceMode: VideoSourceMode = VideoSourceMode.AUTO,
    val translationMode: VideoTranslationMode = VideoTranslationMode.OFFLINE,
    val subtitleUri: String = "", val subtitleName: String = "", val trackId: Int = -1,
    val roi: List<Float> = listOf(.06f,.72f,.94f,.96f), val style: VideoSubtitleStyle = VideoSubtitleStyle(),
    val stage: VideoStage = VideoStage.READY, val resumeStage: VideoStage = VideoStage.EXTRACTING,
    val progress: Int = 0, val message: String = "Chọn nguồn phụ đề và cách dịch.",
    val cues: List<VideoCue> = emptyList(), val scanMs: Long = 0, val carry: JSONObject = JSONObject(),
    val glossary: String = "", val profile: String = "manga", val model: String = "",
    val elapsedMs: Long = 0, val maxPssKiB: Long = 0, val updatedAt: Long = System.currentTimeMillis(),
    val exportedMp4: String = "", val cloudCalls: Int = 0,
    val originalSourceUri: String = "", val originalSubtitleUri: String = "",
    val extractionElapsedMs: Long = 0, val translationElapsedMs: Long = 0, val exportElapsedMs: Long = 0,
    val ocrMetrics: JSONObject = JSONObject(), val visualSampleMs: Long = 250,
    val pipeline: VideoPipelineCheckpoint = VideoPipelineCheckpoint(),
    val offlinePolishPolicy: VideoPolishPolicy = VideoPolishPolicy.NONE,
    val speech: JSONObject = JSONObject()
) {
    val sourceExtractionPending get()=(scanMs<durationMs && resumeStage==VideoStage.EXTRACTING) ||
        (speech.optBoolean("asrActive") && !speech.optBoolean("complete")) ||
        (sourceMode == VideoSourceMode.AUTO && speech.optBoolean("probeComplete") &&
            speech.optString("resolvedSource") == "OCR" && !speech.optBoolean("resolutionComplete"))
    fun json() = JSONObject().put("schema",1).put("id",id).put("title",title).put("uri",uri)
        .put("durationMs",durationMs).put("width",width).put("height",height)
        .put("sourceMode",sourceMode.name).put("translationMode",translationMode.name)
        .put("subtitleUri",subtitleUri).put("subtitleName",subtitleName).put("trackId",trackId)
        .put("roi",JSONArray(roi)).put("style",style.json()).put("stage",stage.name)
        .put("resumeStage",resumeStage.name).put("progress",progress).put("message",message)
        .put("cues",JSONArray(cues.map{it.json()})).put("scanMs",scanMs).put("carry",carry)
        .put("glossary",glossary).put("profile",profile).put("model",model)
        .put("elapsedMs",elapsedMs).put("maxPssKiB",maxPssKiB).put("updatedAt",updatedAt)
        .put("exportedMp4",exportedMp4).put("cloudCalls",cloudCalls)
        .put("originalSourceUri",originalSourceUri).put("originalSubtitleUri",originalSubtitleUri)
        .put("extractionElapsedMs",extractionElapsedMs).put("translationElapsedMs",translationElapsedMs)
        .put("exportElapsedMs",exportElapsedMs).put("ocrMetrics",ocrMetrics).put("visualSampleMs",visualSampleMs)
        .put("pipeline",pipeline.json()).also { if (speech.length()>0) it.put("speech",speech) }
        .also { if (translationMode == VideoTranslationMode.OFFLINE && offlinePolishPolicy != VideoPolishPolicy.NONE) {
            it.put("offlinePolishPolicy", offlinePolishPolicy.name)
        } }
    companion object {
        const val MAX_CUES = 20000
        fun from(j: JSONObject): VideoProject {
            require(j.getInt("schema") == 1) { "Phiên bản dự án chưa được hỗ trợ." }
            val rows=j.getJSONArray("cues"); require(rows.length()<=MAX_CUES)
            val r=j.getJSONArray("roi"); require(r.length()==4)
            return VideoProject(j.getString("id"),j.getString("title"),j.getString("uri"),
                j.getLong("durationMs"),j.getInt("width"),j.getInt("height"),
                VideoSourceMode.valueOf(j.getString("sourceMode")),VideoTranslationMode.valueOf(j.getString("translationMode")),
                j.optString("subtitleUri"),j.optString("subtitleName"),j.optInt("trackId",-1),
                (0..3).map{r.getDouble(it).toFloat()},VideoSubtitleStyle.from(j.optJSONObject("style")),
                VideoStage.valueOf(j.getString("stage")),VideoStage.valueOf(j.getString("resumeStage")),
                j.optInt("progress"),j.optString("message"),(0 until rows.length()).map{VideoCue.from(rows.getJSONObject(it))},
                j.optLong("scanMs"),j.optJSONObject("carry")?:JSONObject(),j.optString("glossary"),
                j.optString("profile","manga"),j.optString("model"),j.optLong("elapsedMs"),j.optLong("maxPssKiB"),
                j.optLong("updatedAt"),j.optString("exportedMp4"),j.optInt("cloudCalls"),
                j.optString("originalSourceUri",j.getString("uri")),j.optString("originalSubtitleUri",j.optString("subtitleUri")),
                j.optLong("extractionElapsedMs"),j.optLong("translationElapsedMs"),j.optLong("exportElapsedMs"),
                j.optJSONObject("ocrMetrics")?:JSONObject(),j.optLong("visualSampleMs",250).also{require(it in setOf(250L,500L))},
                VideoPipelineCheckpoint.from(j.optJSONObject("pipeline")),
                if (j.getString("translationMode") == VideoTranslationMode.OFFLINE.name)
                    VideoPolishPolicy.fromPersisted(j.optString("offlinePolishPolicy")) else VideoPolishPolicy.NONE,
                j.optJSONObject("speech") ?: JSONObject())
        }
    }
}

/** Policy changes replay only the editorial stage and final output; validated text stays checkpointed. */
internal fun VideoProject.withOfflinePolishPolicy(policy: VideoPolishPolicy): VideoProject {
    val effective = if (translationMode == VideoTranslationMode.OFFLINE) policy else VideoPolishPolicy.NONE
    if (offlinePolishPolicy == effective) return this
    return copy(offlinePolishPolicy = effective, pipeline = pipeline.invalidateFrom(BatchPipelineStage.POLISH), exportedMp4 = "")
}

object VideoTimeline {
    fun identity(text: String) = Normalizer.normalize(text,Normalizer.Form.NFKC).replace(Regex("\\s+")," ").trim()
    fun validate(cues: List<VideoCue>, durationMs: Long): List<VideoCue> {
        require(durationMs>=0) { "Thời lượng video không hợp lệ." }
        require(cues.size <= VideoProject.MAX_CUES) { "Tối đa 20.000 câu; hãy chia video thành phần nhỏ hơn." }
        val sorted=cues.sortedWith(compareBy<VideoCue>{it.startMs}.thenBy{it.endMs})
        require(sorted.map{it.id}.distinct().size==sorted.size) { "ID câu trùng." }
        sorted.forEach { require(it.startMs>=0 && it.endMs>it.startMs && it.endMs<=durationMs) { "Mốc phụ đề nằm ngoài video." }
            require(it.source.isNotBlank() && it.source.length<=2000 && it.translation.length<=8000) { "Nội dung câu rỗng hoặc quá dài." } }
        sorted.zipWithNext().forEach { (a,b)->require(a.endMs<=b.startMs) { "Phụ đề chồng thời gian. Chọn một luồng hoặc sửa mốc thời gian." } }
        return sorted
    }
    fun active(cues: List<VideoCue>, timeMs: Long): VideoCue? {
        var lo=0;var hi=cues.lastIndex
        while(lo<=hi){val m=(lo+hi) ushr 1;val c=cues[m];if(timeMs<c.startMs)hi=m-1 else if(timeMs>=c.endMs)lo=m+1 else return c}
        return null
    }
    private fun stamp(ms:Long,ass:Boolean=false):String {
        val sec=ms/1000;return if(ass) String.format(Locale.ROOT,"%d:%02d:%02d.%02d",sec/3600,sec/60%60,sec%60,ms%1000/10)
        else String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",sec/3600,sec/60%60,sec%60,ms%1000)
    }
    fun srt(cues:List<VideoCue>,durationMs:Long?=null):String = validate(cues,durationMs?:cues.maxOfOrNull{it.endMs}?:0).mapIndexed { i,c ->
        require(c.translation.isNotBlank()){ "Câu ${i+1} chưa có bản dịch." }
        // SRT readers interpret HTML-like markup. Edited text must remain literal text.
        val text=c.translation.replace("\r","").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        "${i+1}\n${stamp(c.startMs)} --> ${stamp(c.endMs)}\n$text\n"
    }.joinToString("\n")
    fun ass(project:VideoProject):String {
        if (project.style.legacyDefault) return ass(project.copy(style = project.style.displayStyle().copy(legacyDefault = false)))
        val cues=validate(project.cues,project.durationMs)
        if (project.style.frame != null) return framedAss(project.copy(cues=cues))
        val size=(project.height*project.style.sizeFraction).toInt().coerceAtLeast(12)
        val y=(project.height*project.style.position).toInt()
        val foreground = if (project.style.yellowText) "&H003BEBFF" else "&H00FFFFFF"
        val header="[Script Info]\nScriptType: v4.00+\nPlayResX: ${project.width}\nPlayResY: ${project.height}\nWrapStyle: 0\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,$size,$foreground,$foreground,&H00000000,&H80000000,0,0,0,0,100,100,0,0,${if(project.style.background)3 else 1},${if(project.style.outline)2 else 0},0,2,30,30,20,1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        return header+cues.joinToString("\n") { c ->
            val text=c.translation.replace("\\","＼").replace("{","｛").replace("}","｝").replace("\r","").replace("\n","\\N")
            "Dialogue: 0,${stamp(c.startMs,true)},${stamp(c.endMs,true)},Default,,0,0,0,,{\\pos(${project.width/2},$y)}$text"
        }+"\n"
    }

    private fun framedAss(project: VideoProject): String {
        val frame = requireNotNull(project.style.frame)
        val bounds = frame.pixelBounds(project.width, project.height)
        val left = bounds.left.toInt(); val top = bounds.top.toInt()
        val right = bounds.right.toInt(); val bottom = bounds.bottom.toInt()
        val baseSize = (project.height * project.style.sizeFraction).toInt().coerceAtLeast(8)
        val foreground = if (project.style.yellowText) "&H003BEBFF" else "&H00FFFFFF"
        val header = "[Script Info]\nScriptType: v4.00+\nPlayResX: ${project.width}\nPlayResY: ${project.height}\nWrapStyle: 2\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,$baseSize,$foreground,$foreground,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,8,0,0,0,1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        return header + project.cues.joinToString("\n") { cue ->
            val measured = measureSubtitleFrame(cue.translation, project.style, project.width, project.height)
            val lines = (0 until measured.layout.lineCount).map { line ->
                measured.text.substring(measured.layout.getLineStart(line), measured.layout.getLineEnd(line)).trimEnd('\n')
                    .replace("\\", "＼").replace("{", "｛").replace("}", "｝")
            }.joinToString("\\N")
            val from = stamp(cue.startMs, true); val until = stamp(cue.endMs, true)
            val centerX = String.format(Locale.ROOT, "%.2f", bounds.centerX())
            val textY = String.format(Locale.ROOT, "%.2f", bounds.top + measured.textTop)
            val fontSize = String.format(Locale.ROOT, "%.2f", measured.paint.textSize)
            val border = if (project.style.outline) kotlin.math.ceil(measured.paint.textSize * .08f).toInt() else 0
            val background = if (project.style.background)
                "Dialogue: 0,$from,$until,Default,,0,0,0,,{\\an7\\pos($left,$top)\\p1\\bord0\\shad0\\1c&H000000&\\1a&H00&}m 0 0 l ${right-left} 0 ${right-left} ${bottom-top} 0 ${bottom-top}\n"
                else ""
            background + "Dialogue: 1,$from,$until,Default,,0,0,0,,{\\an8\\pos($centerX,$textY)\\clip($left,$top,$right,$bottom)\\q2\\fs$fontSize\\bord$border\\shad0}$lines"
        } + "\n"
    }
}

/** Port of Windows SubtitleAccumulator's occurrence rules, with serializable carry. */
class VideoCueAccumulator(existing:List<VideoCue> = emptyList(), saved:JSONObject=JSONObject()) {
    val cues=existing.toMutableList()
    private var source=saved.optString("source")
    private var start=saved.optLong("start")
    private var blank=saved.optLong("blank",-1)
    private var blanks=saved.optInt("blanks")
    private var last=saved.optLong("last",-1)
    fun add(timeMs:Long,text:String?) {
        require(timeMs>=last);last=timeMs
        if(text==null)return // uncertain OCR cannot certify disappearance
        val key=VideoTimeline.identity(text)
        if(key.isEmpty()) { if(source.isNotEmpty()){if(blank<0)blank=timeMs;blanks++;if(blanks>=2)close(blank)};return }
        if(key==source){blank=-1;blanks=0;return}
        // A different following subtitle confirms the earlier blank was a real gap.
        // A transient blank followed by the same text still leaves that cue intact.
        if(source.isNotEmpty())close(if(blank>=0)blank else timeMs)
        blank=-1;blanks=0
        source=key;start=timeMs
    }
    private fun close(end:Long){
        if(source.isNotEmpty() && end>start){require(cues.size<VideoProject.MAX_CUES);cues.add(VideoCue("ocr-${cues.size+1}",start,end,source,origin="OCR"))}
        source="";blank=-1;blanks=0
    }
    fun finish(end:Long):List<VideoCue>{close(if(blank>=0)blank else end);return cues.toList()}
    fun snapshot()=JSONObject().put("source",source).put("start",start).put("blank",blank).put("blanks",blanks).put("last",last)
}
