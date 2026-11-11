package com.gemini.ocr.network

import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.Writer
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/**
 * Bounded, streaming conversion to the reviewed-memory store's staging JSONL.
 * A subtitle pair must occur IN ONE cue. Separate files/events are never aligned.
 * Script detection is not a translation-quality or language-quality assessment:
 * all output is DRAFT and still needs preview, rights confirmation and review.
 *
 * Streams remain owned by the caller. On a structural/size exception the caller
 * MUST discard partial output, rather than staging a partially converted pack.
 */
object MemoryImportConverter {
    enum class Format { CSV, JSONL, SRT, ASS, SSA, VTT, TMX, LRC }
    data class Metadata(val sourceUrl: String? = null, val licenseId: String? = null, val topic: String? = null)
    data class ConversionSummary(val accepted: Long, val rejected: Long, val recordsRead: Long,
                                 val rejectionReasons: Map<String, Long>)

    const val MAX_LINE_CHARS = 32768
    const val MAX_CUE_CHARS = 8192
    const val MAX_CUE_LINES = 64
    const val MAX_XML_DEPTH = 32
    const val MAX_RECORDS = 1_000_000L
    private const val MAX_FIELDS = 64
    private val fields = setOf("source", "translation", "confidence", "scope", "quality", "topic", "source_url",
        "license_id", "model_version", "import_version", "usage_count", "created_at", "updated_at")
    private val csvFields = setOf("source", "translation", "confidence", "quality", "topic", "source_url", "license_id")

    fun convert(format: Format, input: InputStream, output: Writer, metadata: Metadata = Metadata()): ConversionSummary {
        validateMetadata(metadata)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = BoundedReader(InputStreamReader(input, decoder).buffered(8192))
        val sink = Sink(output, metadata)
        when (format) {
            Format.CSV -> csv(reader, sink)
            Format.JSONL -> jsonl(reader, sink)
            Format.SRT, Format.VTT -> subtitles(reader, sink, format)
            Format.ASS, Format.SSA -> ass(reader, sink)
            Format.LRC -> lrc(reader, sink)
            Format.TMX -> tmx(reader, sink)
        }
        output.flush()
        return sink.summary()
    }

    private class Limit(message: String) : IllegalArgumentException(message)

    /** Also used by SAX: no unbounded BufferedReader.readLine or XML text token. */
    private class BoundedReader(private val source: Reader) : Reader() {
        private var first = true
        private var pending = -1
        private var lineChars = 0
        override fun read(): Int {
            var c = if (pending >= 0) pending.also { pending = -1 } else source.read()
            if (first) { first = false; if (c == 0xFEFF) c = source.read() }
            if (c == '\r'.code) {
                val next = source.read()
                if (next != '\n'.code) pending = next
                c = '\n'.code
            }
            if (c == '\n'.code) lineChars = 0
            else if (c >= 0 && ++lineChars > MAX_LINE_CHARS) throw Limit("Physical line exceeds $MAX_LINE_CHARS characters")
            return c
        }
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            var n = 0
            while (n < minOf(length, 4096)) {
                val c = read()
                if (c < 0) break
                buffer[offset + n++] = c.toChar()
            }
            return if (n == 0) -1 else n
        }
        override fun close() { /* Caller owns input. */ }
        fun line(): String? {
            val value = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0) return if (value.isEmpty()) null else value.toString()
                if (c == '\n'.code) return value.toString()
                value.append(c.toChar())
            }
        }
    }

    private class Sink(private val output: Writer, private val defaults: Metadata) {
        private var records = 0L
        private var accepted = 0L
        private val reasons = linkedMapOf<String, Long>()
        fun begin() { if (++records > MAX_RECORDS) throw Limit("Too many import records") }
        fun reject(reason: String) { reasons[reason] = (reasons[reason] ?: 0L) + 1 }
        fun emit(source: String, translation: String, metadata: JSONObject = JSONObject()) {
            metadata.put("source", source).put("translation", translation)
            emit(metadata)
        }
        fun emit(row: JSONObject) {
            try {
                require(row.keys().asSequence().all { it in fields })
                require(row.opt("source") is String && row.opt("translation") is String)
                val source = row.getString("source").trim()
                val target = row.getString("translation").trim()
                require(source.length in 1..2000 && target.length in 1..4000)
                require('\u0000' !in source && '\u0000' !in target)
                require(scripts(source).han && !scripts(target).han && scripts(target).latin)
                require(!scripts(target).otherLetter)
                if (row.has("source_url") || row.has("license_id")) {
                    require(row.opt("source_url") is String && row.opt("license_id") is String)
                    validateMetadata(Metadata(row.getString("source_url"), row.getString("license_id")))
                } else if (defaults.sourceUrl != null) {
                    row.put("source_url", defaults.sourceUrl).put("license_id", defaults.licenseId)
                }
                if (row.has("topic")) {
                    require(row.opt("topic") is String); label(row.getString("topic"), 160)
                } else defaults.topic?.let { row.put("topic", it) }
                if (row.has("confidence")) {
                    require(row.opt("confidence") is Number)
                    require(row.getDouble("confidence").let { it.isFinite() && it in 0.0..1.0 })
                }
                if (row.has("scope")) require(row.opt("scope") is JSONObject)
                if (row.has("model_version")) require(row.opt("model_version") is String)
                row.put("source", source).put("translation", target).put("quality", "DRAFT")
                val encoded = row.toString()
                if (encoded.length > MAX_LINE_CHARS) throw Limit("Converted JSONL record exceeds its size limit")
                output.write(encoded); output.write("\n")
                accepted++
            } catch (error: Limit) { throw error
            } catch (_: IllegalArgumentException) { reject("invalid_fields") }
        }
        fun bilingual(lines: List<String>) {
            val zh = mutableListOf<String>(); val vi = mutableListOf<String>()
            for (raw in lines) {
                val text = clean(raw).trim()
                if (text.isEmpty()) continue
                val label = languageLabel.find(text)
                val declared = label?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                val body = if (label != null) text.substring(label.range.last + 1).trim() else text
                val script = scripts(body)
                when {
                    !script.han && !script.latin && !script.otherLetter && body.none { it.isDigit() } -> continue
                    declared in setOf("zh", "中文") && script.han && !script.otherLetter -> zh.add(body)
                    declared in setOf("vi", "tiếng việt") && script.latin && !script.han && !script.otherLetter -> vi.add(body)
                    declared != null -> { reject("ambiguous_languages"); return }
                    script.han && !script.latin && !script.otherLetter -> zh.add(body)
                    script.latin && !script.han && !script.otherLetter && vietnameseMark.containsMatchIn(Normalizer.normalize(body, Normalizer.Form.NFC)) -> vi.add(body)
                    else -> { reject("ambiguous_languages"); return }
                }
            }
            if (zh.isEmpty() || vi.isEmpty()) { reject("missing_bilingual_pair"); return }
            emit(zh.joinToString(" "), vi.joinToString(" "))
        }
        fun summary() = ConversionSummary(accepted, reasons.values.sum(), records, reasons.toMap())
    }

    private data class Scripts(val han: Boolean, val latin: Boolean, val otherLetter: Boolean)
    private fun scripts(text: String): Scripts {
        var han = false; var latin = false; var other = false; var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han = true
                Character.UnicodeScript.LATIN -> latin = true
                else -> other = true
            }
        }
        return Scripts(han, latin, other)
    }
    private val languageLabel = Regex("^(?:\\[|<)?(zh|vi|中文|tiếng việt)(?:\\]|>|:)\\s*", RegexOption.IGNORE_CASE)
    private val vietnameseMark = Regex("[ĂăÂâĐđÊêÔôƠơƯưÀÁẢÃẠàáảãạẰẮẲẴẶằắẳẵặẦẤẨẪẬầấẩẫậÈÉẺẼẸèéẻẽẹỀẾỂỄỆềếểễệÌÍỈĨỊìíỉĩịÒÓỎÕỌòóỏõọỒỐỔỖỘồốổỗộỜỚỞỠỢờớởỡợÙÚỦŨỤùúủũụỪỨỬỮỰừứửữựỲÝỶỸỴỳýỷỹỵ]")
    private val safeTags = Regex("</?(?:b|i|u|font|c|v|lang|ruby|rt)(?:[ .][^<>]*)?>", RegexOption.IGNORE_CASE)
    private val assOverrides = Regex("\\{[^{}]*}")
    private fun clean(text: String): String = safeTags.replace(assOverrides.replace(text, ""), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun label(text: String, max: Int) {
        require(text.isNotBlank() && text.length <= max && text.none { it.isISOControl() })
    }
    private fun validateMetadata(value: Metadata) {
        require((value.sourceUrl == null) == (value.licenseId == null)) { "Supply both provenance URL and license" }
        value.topic?.let { label(it, 160) }
        value.licenseId?.let { label(it, 160) }
        value.sourceUrl?.let {
            label(it, 2048)
            val uri = runCatching { URI(it) }.getOrNull()
            require(uri != null && uri.scheme in setOf("https", "user", "model") && uri.userInfo == null)
            require(uri.scheme != "https" || !uri.host.isNullOrBlank())
            val names = uri.rawQuery.orEmpty().split('&').map { item ->
                URLDecoder.decode(item.substringBefore('='), "UTF-8").lowercase(Locale.ROOT)
            }
            require(names.none { name -> name in setOf("key", "api_key", "token", "access_token", "auth", "password") })
        }
    }

    private fun jsonl(reader: BoundedReader, sink: Sink) {
        while (true) {
            val line = reader.line() ?: break
            if (line.isBlank()) continue
            sink.begin()
            val row = try {
                StrictJson(line).check()
                JSONObject(line)
            } catch (error: Limit) { throw error
            } catch (_: Exception) { sink.reject("invalid_json"); continue }
            sink.emit(row)
        }
    }

    /** RFC4180 quotes/newlines, bounded record and columns; never split CSV on comma. */
    private fun csv(reader: BoundedReader, sink: Sink) {
        var header: List<String>? = null
        var columns = mutableListOf<String>(); var field = StringBuilder()
        var quoted = false; var afterQuote = false; var recordChars = 0
        fun endField() {
            if (columns.size >= MAX_FIELDS) throw Limit("Too many CSV columns")
            columns.add(field.toString()); field = StringBuilder(); afterQuote = false
        }
        fun endRecord() {
            endField()
            if (columns.any { it.isNotBlank() }) {
                if (header == null) {
                    val names = columns.map { it.trim().lowercase(Locale.ROOT) }
                    require(names.toSet().size == names.size && names.all { it in csvFields } &&
                        "source" in names && "translation" in names) { "CSV needs unique source/translation and supported metadata columns" }
                    header = names
                } else {
                    sink.begin()
                    if (columns.size != header!!.size) sink.reject("invalid_csv_columns")
                    else {
                        val row = JSONObject()
                        header!!.forEachIndexed { index, key ->
                            val value = columns[index]
                            if (key == "source" || key == "translation" || value.isNotBlank()) {
                                row.put(key, if (key == "confidence") value.toDoubleOrNull() ?: value else value)
                            }
                        }
                        sink.emit(row)
                    }
                }
            }
            columns = mutableListOf(); recordChars = 0
        }
        while (true) {
            val code = reader.read()
            if (code < 0) {
                require(!quoted) { "Unterminated CSV quote; discard partial output" }
                if (field.isNotEmpty() || columns.isNotEmpty() || afterQuote) endRecord()
                break
            }
            if (++recordChars > MAX_LINE_CHARS) throw Limit("CSV record exceeds its size limit")
            val c = code.toChar()
            when {
                quoted && c == '"' -> { quoted = false; afterQuote = true }
                quoted -> field.append(c)
                afterQuote && c == '"' -> { field.append('"'); quoted = true; afterQuote = false }
                c == ',' -> endField()
                c == '\n' -> endRecord()
                afterQuote -> throw IllegalArgumentException("Unexpected character after CSV quote")
                c == '"' -> { require(field.isEmpty()) { "Unexpected quote inside CSV field" }; quoted = true }
                else -> field.append(c)
            }
        }
        require(header != null) { "CSV header is missing" }
    }

    private fun subtitles(reader: BoundedReader, sink: Sink, format: Format) {
        val block = mutableListOf<String>(); var chars = 0
        fun flush() {
            if (block.isEmpty()) return
            val first = block.first().trim()
            if (format == Format.VTT && (first.startsWith("WEBVTT") || first == "STYLE" || first == "REGION" || first == "NOTE" || first.startsWith("NOTE "))) {
                block.clear(); chars = 0; return
            }
            sink.begin()
            val timing = block.indexOfFirst { "-->" in it }
            if (timing !in 0..1 || !validRange(block.getOrElse(timing) { "" }, format)) sink.reject("invalid_timing")
            else sink.bilingual(block.drop(timing + 1))
            block.clear(); chars = 0
        }
        while (true) {
            val line = reader.line() ?: break
            if (line.isBlank()) { flush(); continue }
            chars += line.length
            if (chars > MAX_CUE_CHARS || block.size >= MAX_CUE_LINES) throw Limit("Subtitle cue exceeds its size limit")
            block.add(line)
        }
        flush()
    }

    private val clock = Regex("(?:(\\d{1,6}):)?([0-5]\\d):([0-5]\\d)([.,])(\\d{3})")
    private fun timestamp(value: String, format: Format): Long? {
        val m = clock.matchEntire(value.trim()) ?: return null
        if (format == Format.SRT && (m.groupValues[1].isEmpty() || m.groupValues[4] != ",")) return null
        if (format == Format.VTT && m.groupValues[4] != ".") return null
        return (m.groupValues[1].toLongOrNull() ?: 0) * 3_600_000 + m.groupValues[2].toLong() * 60_000 +
            m.groupValues[3].toLong() * 1000 + m.groupValues[5].toLong()
    }
    private fun validRange(line: String, format: Format): Boolean {
        val range = line.trim().split(Regex("\\s+-->\\s+"), limit = 2)
        if (range.size != 2) return false
        val start = timestamp(range[0], format) ?: return false
        val end = timestamp(range[1].substringBefore(' '), format) ?: return false
        return end > start
    }

    private val assClock = Regex("(\\d{1,6}):([0-5]\\d):([0-5]\\d)\\.(\\d{2})")
    private fun assTime(value: String): Long? {
        val m = assClock.matchEntire(value.trim()) ?: return null
        return m.groupValues[1].toLong() * 3_600_000 + m.groupValues[2].toLong() * 60_000 +
            m.groupValues[3].toLong() * 1000 + m.groupValues[4].toLong() * 10
    }
    private fun ass(reader: BoundedReader, sink: Sink) {
        var events = false; var names: List<String>? = null
        while (true) {
            val line = (reader.line() ?: break).trim()
            if (line.startsWith('[')) { events = line.equals("[Events]", true); names = null; continue }
            if (!events || line.isEmpty() || line.startsWith(';')) continue
            if (line.startsWith("Format:", true)) {
                val parsed = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                require(parsed.size <= MAX_FIELDS && parsed.toSet().size == parsed.size &&
                    parsed.lastOrNull() == "text" && "start" in parsed && "end" in parsed) { "ASS needs an explicit Format with Text last" }
                names = parsed; continue
            }
            if (!line.startsWith("Dialogue:", true)) continue
            sink.begin()
            val header = names
            if (header == null) { sink.reject("missing_ass_format"); continue }
            val values = line.substringAfter(':').split(',', limit = header.size)
            if (values.size != header.size) { sink.reject("invalid_ass_columns"); continue }
            val start = assTime(values[header.indexOf("start")]); val end = assTime(values[header.indexOf("end")])
            if (start == null || end == null || end <= start) { sink.reject("invalid_timing"); continue }
            val text = values.last()
            if (text.length > MAX_CUE_CHARS) throw Limit("ASS cue exceeds its size limit")
            if (Regex("\\\\p[1-9]", RegexOption.IGNORE_CASE).containsMatchIn(text)) { sink.reject("ass_drawing"); continue }
            val lines = text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").split('\n')
            if (lines.size > MAX_CUE_LINES) throw Limit("ASS cue has too many lines")
            sink.bilingual(lines)
        }
    }

    private val lrcTime = Regex("^\\[(\\d{1,6}):([0-5]\\d)(?:\\.(\\d{1,3}))?]")
    private val lrcHeader = Regex("^\\[(?:ar|al|ti|au|by|offset|re|ve|length):[^]]*]$", RegexOption.IGNORE_CASE)
    private fun lrc(reader: BoundedReader, sink: Sink) {
        while (true) {
            var text = (reader.line() ?: break).trim()
            if (text.isEmpty() || lrcHeader.matches(text)) continue
            sink.begin()
            var count = 0
            while (true) {
                val time = lrcTime.find(text) ?: break
                if (++count > 8) throw Limit("Too many LRC timestamps in one cue")
                text = text.substring(time.range.last + 1)
            }
            if (count == 0 || text.startsWith('[') && !languageLabel.containsMatchIn(text)) { sink.reject("invalid_timing"); continue }
            if (text.length > MAX_CUE_CHARS) throw Limit("LRC cue exceeds its size limit")
            // Literal line escapes or one explicit pipe delimit the SAME record.
            val lines = text.replace("\\N", "\n").replace("\\n", "\n").replace('|', '\n').split('\n')
            if (lines.size > MAX_CUE_LINES) throw Limit("LRC cue has too many lines")
            sink.bilingual(lines)
        }
    }

    /** Lexical DTD rejection and an always-rejecting resolver are both mandatory. */
    private fun tmx(reader: BoundedReader, sink: Sink) {
        val handler = object : DefaultHandler2() {
            var depth = 0; var tuDepth = 0; var tuvDepth = 0; var segDepth = 0; var codeDepth = 0
            var propDepth = 0; var propName = ""; var prop = StringBuilder()
            var language = ""; var text = StringBuilder(); var tuChars = 0; var variants = 0
            var zh: String? = null; var vi: String? = null; var invalid = false; var segments = 0
            var metadata = JSONObject()
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DOCTYPE is forbidden") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entity is forbidden") }
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { throw SAXException("External entity is forbidden") }
            override fun startElement(uri: String?, local: String?, qualified: String?, attributes: Attributes) {
                if (++depth > MAX_XML_DEPTH) throw Limit("XML is too deeply nested")
                if (attributes.length > MAX_FIELDS) throw Limit("Too many XML attributes")
                for (i in 0 until attributes.length) if (attributes.getValue(i).length > 2048) throw Limit("XML attribute too long")
                val name = local.orEmpty().ifEmpty { qualified.orEmpty() }
                when (name) {
                    "tu" -> {
                        require(tuDepth == 0) { "Nested TMX translation unit" }; sink.begin(); tuDepth = depth
                        zh = null; vi = null; invalid = false; tuChars = 0; variants = 0; metadata = JSONObject()
                    }
                    "tuv" -> if (tuDepth > 0) {
                        if (tuvDepth > 0 || ++variants > MAX_FIELDS) throw Limit("Invalid or excessive TMX variants")
                        tuvDepth = depth; segments = 0; text = StringBuilder()
                        language = (attributes.getValue("http://www.w3.org/XML/1998/namespace", "lang")
                            ?: attributes.getValue("xml:lang") ?: "").lowercase(Locale.ROOT)
                    }
                    "seg" -> if (tuvDepth > 0) { if (++segments > 1 || segDepth > 0) invalid = true; segDepth = depth }
                    "bpt", "ept", "it", "ph", "ut" -> if (segDepth > 0 && codeDepth == 0) codeDepth = depth
                    "prop" -> if (tuDepth > 0 && tuvDepth == 0) {
                        propDepth = depth; propName = attributes.getValue("type").orEmpty(); prop = StringBuilder()
                    }
                }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (tuDepth > 0) { tuChars += length; if (tuChars > MAX_CUE_CHARS) throw Limit("TMX unit exceeds its size limit") }
                if (segDepth > 0 && codeDepth == 0) text.append(chars, start, length)
                if (propDepth > 0) prop.append(chars, start, length)
            }
            override fun endElement(uri: String?, local: String?, qualified: String?) {
                val name = local.orEmpty().ifEmpty { qualified.orEmpty() }
                if (depth == codeDepth) codeDepth = 0
                if (depth == segDepth) segDepth = 0
                if (name == "prop" && depth == propDepth) {
                    if (propName in setOf("source_url", "license_id", "topic")) {
                        if (metadata.has(propName)) invalid = true else metadata.put(propName, prop.toString().trim())
                    }
                    propDepth = 0
                }
                if (name == "tuv" && depth == tuvDepth) {
                    val value = text.toString().trim()
                    when {
                        language == "zh" || language.startsWith("zh-") -> if (zh != null || segments != 1) invalid = true else zh = value
                        language == "vi" || language.startsWith("vi-") -> if (vi != null || segments != 1) invalid = true else vi = value
                    }
                    tuvDepth = 0
                }
                if (name == "tu" && depth == tuDepth) {
                    if (invalid || zh == null || vi == null) sink.reject("missing_or_ambiguous_tmx_pair")
                    else sink.emit(zh!!, vi!!, metadata)
                    tuDepth = 0
                }
                depth--
            }
        }
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true; isValidating = false
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val parser = factory.newSAXParser().xmlReader
            // If the platform cannot install the DTD guard, fail closed.
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            parser.contentHandler = handler; parser.entityResolver = handler; parser.errorHandler = handler
            parser.parse(InputSource(reader))
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid or unsafe TMX; discard partial output", error)
        }
    }

    /** org.json accepts non-JSON extensions; validate grammar, depth and duplicate keys first. */
    private class StrictJson(private val input: String) {
        private var at = 0
        fun check() { space(); require(at < input.length && input[at] == '{'); value(0); space(); require(at == input.length) }
        private fun space() { while (at < input.length && input[at] in " \t\r\n") at++ }
        private fun take(c: Char): Boolean { space(); if (at < input.length && input[at] == c) { at++; return true }; return false }
        private fun value(depth: Int) {
            if (depth > MAX_XML_DEPTH) throw Limit("JSON is too deeply nested")
            space(); require(at < input.length)
            when (input[at]) {
                '{' -> {
                    at++; val names = hashSetOf<String>()
                    if (take('}')) return
                    do { space(); val key = string(); require(names.add(key)); if (names.size > MAX_FIELDS) throw Limit("Too many JSON fields")
                        require(take(':')); value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
                '[' -> { at++; var count = 0; if (take(']')) return
                    do { if (++count > MAX_FIELDS) throw Limit("Too many JSON array elements"); value(depth + 1) } while (take(',')); require(take(']')) }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> { val match = number.find(input, at); require(match != null && match.range.first == at); at = match.range.last + 1 }
            }
        }
        private fun literal(text: String) { require(input.startsWith(text, at)); at += text.length }
        private fun string(): String {
            require(at < input.length && input[at++] == '"'); val result = StringBuilder()
            while (at < input.length) {
                val c = input[at++]
                if (c == '"') return result.toString()
                require(c.code >= 32)
                if (c != '\\') result.append(c)
                else {
                    require(at < input.length)
                    when (val escape = input[at++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b'); 'f' -> result.append('\u000C'); 'n' -> result.append('\n')
                        'r' -> result.append('\r'); 't' -> result.append('\t')
                        'u' -> { require(at + 4 <= input.length); val hex = input.substring(at, at + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }); result.append(hex.toInt(16).toChar()); at += 4 }
                        else -> throw IllegalArgumentException("Invalid JSON string escape")
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }
        companion object { private val number = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?") }
    }
}
