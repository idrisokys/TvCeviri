package com.example.altyazicikar

import java.io.EOFException
import java.io.IOException
import java.util.Locale

/** Akış kaynağı: HTTP (WebDAV) veya test için dosya. */
interface ByteSource {
    val pos: Long
    /** Sonraki bayt, dosya sonunda -1. */
    fun readByte(): Int
    fun readBytes(n: Int): ByteArray
    fun skip(n: Long)
}

class CancelledException : IOException("İptal edildi")

class MkvTrack(
    val number: Long,
    val codec: String,
    val language: String,
    val name: String,
    val codecPrivate: ByteArray?,
    val isDefault: Boolean
) {
    val supported: Boolean
        get() = codec == "S_TEXT/ASS" || codec == "S_TEXT/SSA" || codec == "S_TEXT/UTF8"

    val ext: String
        get() = if (codec == "S_TEXT/UTF8") "srt" else "ass"

    fun label(): String {
        val kind = when (codec) {
            "S_TEXT/ASS" -> "ASS"
            "S_TEXT/SSA" -> "SSA"
            "S_TEXT/UTF8" -> "SRT"
            "S_HDMV/PGS" -> "PGS (görüntü)"
            "S_VOBSUB" -> "VobSub (görüntü)"
            else -> codec
        }
        val title = if (name.isNotBlank()) " – $name" else ""
        val def = if (isDefault) " ★" else ""
        val warn = if (supported) "" else " [desteklenmiyor]"
        return "$language · $kind$title$def$warn"
    }
}

class MkvHeader(val timecodeScale: Long, val tracks: List<MkvTrack>)

class SubEvent(val startMs: Long, val durMs: Long, val payload: ByteArray)

/**
 * Minimal Matroska (EBML) okuyucu. Dosyayı baştan sona akışla okur,
 * yalnızca istenen altyazı izlerinin bloklarını bellekte tutar.
 */
class MkvParser(
    private val src: ByteSource,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (Long) -> Unit = {}
) {
    private companion object {
        const val ID_EBML = 0x1A45DFA3L
        const val ID_SEGMENT = 0x18538067L
        const val ID_SEEKHEAD = 0x114D9B74L
        const val ID_INFO = 0x1549A966L
        const val ID_TIMECODE_SCALE = 0x2AD7B1L
        const val ID_TRACKS = 0x1654AE6BL
        const val ID_TRACK_ENTRY = 0xAEL
        const val ID_TRACK_NUMBER = 0xD7L
        const val ID_TRACK_TYPE = 0x83L
        const val ID_CODEC_ID = 0x86L
        const val ID_CODEC_PRIVATE = 0x63A2L
        const val ID_LANGUAGE = 0x22B59CL
        const val ID_LANGUAGE_BCP47 = 0x22B59DL
        const val ID_NAME = 0x536EL
        const val ID_FLAG_DEFAULT = 0x88L
        const val ID_CLUSTER = 0x1F43B675L
        const val ID_CLUSTER_TIMECODE = 0xE7L
        const val ID_SIMPLE_BLOCK = 0xA3L
        const val ID_BLOCK_GROUP = 0xA0L
        const val ID_BLOCK = 0xA1L
        const val ID_BLOCK_DURATION = 0x9BL
        const val ID_CUES = 0x1C53BB6BL
        const val ID_TAGS = 0x1254C367L
        const val ID_ATTACHMENTS = 0x1941A469L
        const val ID_CHAPTERS = 0x1043A770L
    }

    private class Hdr(val id: Long, val size: Long, val dataStart: Long) {
        val end: Long get() = if (size < 0) Long.MAX_VALUE else dataStart + size
    }

    private class Blk(val track: Long, val rel: Int, val payload: ByteArray)

    private var pending: Hdr? = null
    private var stop = false
    private var scale = 1_000_000L
    private val tracks = ArrayList<MkvTrack>()
    private var wanted: Set<Long> = emptySet()
    private var collectMode = false

    /** İz numarası -> bloklar (collect çağrısından sonra dolu). */
    val events = HashMap<Long, ArrayList<SubEvent>>()

    /** Yalnızca başlığı (iz listesi) okur; ilk Cluster'da durur. */
    fun readHeader(): MkvHeader {
        collectMode = false
        run()
        return MkvHeader(scale, tracks.toList())
    }

    /** Dosyanın tamamını okuyup istenen izlerin bloklarını toplar. */
    fun collect(trackNumbers: Set<Long>): MkvHeader {
        wanted = trackNumbers
        collectMode = true
        run()
        return MkvHeader(scale, tracks.toList())
    }

    private fun run() {
        val first = next(Long.MAX_VALUE) ?: throw IOException("Dosya boş")
        if (first.id != ID_EBML) {
            throw IOException("Bu dosya MKV değil (yalnızca .mkv destekleniyor)")
        }
        skipElem(first)
        while (!stop) {
            val h = next(Long.MAX_VALUE) ?: break
            if (h.id == ID_SEGMENT) parseSegment(h) else skipElem(h)
        }
    }

    private fun parseSegment(seg: Hdr) {
        while (!stop) {
            val h = next(seg.end) ?: break
            when (h.id) {
                ID_INFO -> parseInfo(h)
                ID_TRACKS -> parseTracks(h)
                ID_CLUSTER -> {
                    if (collectMode) parseCluster(h) else { stop = true }
                }
                else -> skipElem(h)
            }
        }
    }

    private fun parseInfo(info: Hdr) {
        while (true) {
            val h = next(info.end) ?: break
            if (h.id == ID_TIMECODE_SCALE) scale = readUInt(h.size) else skipElem(h)
        }
    }

    private fun parseTracks(t: Hdr) {
        while (true) {
            val h = next(t.end) ?: break
            if (h.id == ID_TRACK_ENTRY) parseTrackEntry(h) else skipElem(h)
        }
    }

    private fun parseTrackEntry(e: Hdr) {
        var number = -1L
        var type = -1L
        var codec = ""
        var lang = "eng"
        var bcp = ""
        var name = ""
        var priv: ByteArray? = null
        var def = true
        while (true) {
            val h = next(e.end) ?: break
            when (h.id) {
                ID_TRACK_NUMBER -> number = readUInt(h.size)
                ID_TRACK_TYPE -> type = readUInt(h.size)
                ID_CODEC_ID -> codec = readStr(h.size)
                ID_LANGUAGE -> lang = readStr(h.size)
                ID_LANGUAGE_BCP47 -> bcp = readStr(h.size)
                ID_NAME -> name = readStr(h.size)
                ID_FLAG_DEFAULT -> def = readUInt(h.size) != 0L
                ID_CODEC_PRIVATE -> priv = src.readBytes(h.size.toInt())
                else -> skipElem(h)
            }
        }
        if (type == 17L && number >= 0) {
            tracks.add(
                MkvTrack(number, codec, if (bcp.isNotEmpty()) bcp else lang, name, priv, def)
            )
        }
    }

    private fun parseCluster(cl: Hdr) {
        var time = 0L
        while (!stop) {
            val h = next(cl.end) ?: break
            if (isCancelled()) throw CancelledException()
            onProgress(src.pos)
            when (h.id) {
                ID_CLUSTER_TIMECODE -> time = readUInt(h.size)
                ID_SIMPLE_BLOCK -> {
                    val b = readBlock(h)
                    if (b != null) addEvent(b, time, -1L)
                }
                ID_BLOCK_GROUP -> parseBlockGroup(h, time)
                ID_CLUSTER, ID_CUES, ID_TAGS, ID_ATTACHMENTS, ID_CHAPTERS,
                ID_TRACKS, ID_INFO, ID_SEEKHEAD -> {
                    // Boyutu bilinmeyen Cluster başka bir üst düzey öğeyle biter.
                    if (cl.size < 0) {
                        pending = h
                        return
                    } else {
                        skipElem(h)
                    }
                }
                else -> skipElem(h)
            }
        }
    }

    private fun parseBlockGroup(g: Hdr, clusterTime: Long) {
        var blk: Blk? = null
        var dur = -1L
        while (true) {
            val h = next(g.end) ?: break
            when (h.id) {
                ID_BLOCK -> blk = readBlock(h)
                ID_BLOCK_DURATION -> dur = readUInt(h.size)
                else -> skipElem(h)
            }
        }
        if (blk != null) addEvent(blk, clusterTime, dur)
    }

    private fun addEvent(b: Blk, clusterTime: Long, dur: Long) {
        val startMs = (clusterTime + b.rel) * scale / 1_000_000L
        val durMs = if (dur >= 0) dur * scale / 1_000_000L else -1L
        events.getOrPut(b.track) { ArrayList() }.add(SubEvent(startMs, durMs, b.payload))
    }

    /** Block / SimpleBlock içeriğini okur; istenmeyen izlerin verisini atlar. */
    private fun readBlock(h: Hdr): Blk? {
        val b0 = src.readByte()
        if (b0 < 0) throw EOFException()
        var len = 1
        var mask = 0x80
        while (len <= 8 && (b0 and mask) == 0) {
            len++
            mask = mask shr 1
        }
        if (len > 8) throw IOException("Bozuk blok")
        var track = (b0 and (mask - 1)).toLong()
        if (len > 1) {
            for (x in src.readBytes(len - 1)) track = (track shl 8) or (x.toLong() and 0xFF)
        }
        val th = src.readBytes(3)
        val rel = ((th[0].toInt() shl 8) or (th[1].toInt() and 0xFF)).toShort().toInt()
        val flags = th[2].toInt() and 0xFF
        val remaining = h.size - len - 3
        if (remaining < 0) throw IOException("Bozuk blok")
        val lacing = (flags shr 1) and 3
        if (!collectMode || track !in wanted || lacing != 0) {
            src.skip(remaining)
            return null
        }
        return Blk(track, rel, src.readBytes(remaining.toInt()))
    }

    private fun next(end: Long): Hdr? {
        val p = pending
        if (p != null) {
            pending = null
            return p
        }
        if (src.pos >= end) return null
        val b = src.readByte()
        if (b < 0) return null
        var len = 1
        var mask = 0x80
        while (len <= 4 && (b and mask) == 0) {
            len++
            mask = mask shr 1
        }
        if (len > 4) throw IOException("Bozuk EBML kimliği")
        var id = b.toLong()
        if (len > 1) {
            for (x in src.readBytes(len - 1)) id = (id shl 8) or (x.toLong() and 0xFF)
        }
        val size = readSize()
        return Hdr(id, size, src.pos)
    }

    /** -1 = bilinmeyen boyut. */
    private fun readSize(): Long {
        val b = src.readByte()
        if (b < 0) throw EOFException()
        var len = 1
        var mask = 0x80
        while (len <= 8 && (b and mask) == 0) {
            len++
            mask = mask shr 1
        }
        if (len > 8) throw IOException("Bozuk EBML boyutu")
        var v = (b and (mask - 1)).toLong()
        var allOnes = (b and (mask - 1)) == (mask - 1)
        if (len > 1) {
            for (x in src.readBytes(len - 1)) {
                val u = x.toInt() and 0xFF
                if (u != 0xFF) allOnes = false
                v = (v shl 8) or u.toLong()
            }
        }
        return if (allOnes) -1L else v
    }

    private fun skipElem(h: Hdr) {
        if (h.size < 0) throw IOException("Boyutu bilinmeyen öğe atlanamadı")
        val n = h.dataStart + h.size - src.pos
        if (n > 0) src.skip(n)
    }

    private fun readUInt(size: Long): Long {
        var v = 0L
        for (x in src.readBytes(size.toInt())) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    private fun readStr(size: Long): String =
        String(src.readBytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
}

object Lang {
    private val map = mapOf(
        "tr" to "tur", "en" to "eng", "ja" to "jpn", "ru" to "rus", "es" to "spa",
        "fr" to "fre", "de" to "ger", "it" to "ita", "pt" to "por", "ar" to "ara",
        "zh" to "chi", "ko" to "kor"
    )

    private fun norm(s: String): String {
        val x = s.lowercase(Locale.ROOT).substringBefore('-')
        return map[x] ?: x
    }

    fun matches(t: MkvTrack, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return norm(t.language) == norm(q) ||
            t.name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }
}

object SubtitleWriter {
    private val DEFAULT_HEADER = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,3,1,2,30,30,30,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent() + "\n"

    fun build(track: MkvTrack, events: List<SubEvent>): ByteArray =
        if (track.codec == "S_TEXT/UTF8") srt(events) else ass(track, events)

    /** Her metin altyazısını (ASS/SSA/SRT) doğrudan SRT olarak üretir. */
    fun buildSrt(track: MkvTrack, events: List<SubEvent>): ByteArray =
        if (track.codec == "S_TEXT/UTF8") srt(events) else assToSrt(events)

    private val TAG_BLOCK = Regex("\\{[^}]*\\}")
    private val STYLE_TAG = Regex("\\\\([ibu])([01])(?![0-9])")
    private val DRAWING = Regex("\\\\p[1-9]")

    /** ASS stil bloklarını atar; italik/kalın/altçizgi etiketlerini SRT karşılıklarına çevirir. */
    private fun assTextToSrt(raw: String): String {
        if (DRAWING.containsMatchIn(raw)) return ""
        val open = linkedSetOf<Char>()
        val sb = StringBuilder()
        var pos = 0
        for (m in TAG_BLOCK.findAll(raw)) {
            sb.append(raw, pos, m.range.first)
            pos = m.range.last + 1
            for (t in STYLE_TAG.findAll(m.value)) {
                val c = t.groupValues[1][0]
                if (t.groupValues[2] == "1") {
                    if (open.add(c)) sb.append('<').append(c).append('>')
                } else if (open.remove(c)) {
                    sb.append("</").append(c).append('>')
                }
            }
        }
        sb.append(raw, pos, raw.length)
        for (c in open.reversed()) sb.append("</").append(c).append('>')
        return sb.toString()
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            .replace("{", "").replace("}", "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun assToSrt(events: List<SubEvent>): ByteArray {
        val sorted = events.sortedBy { it.startMs }
        val sb = StringBuilder("\uFEFF")
        val seen = HashSet<String>()
        var n = 0
        for (e in sorted) {
            val s = String(e.payload, Charsets.UTF_8)
            // ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            val parts = s.split(",", limit = 9)
            if (parts.size < 9) continue
            val text = assTextToSrt(parts[8].trimEnd('\r', '\n', '\u0000'))
            if (text.isBlank()) continue
            if (!seen.add("${e.startMs}|$text")) continue
            n++
            val end = e.startMs + (if (e.durMs >= 0) e.durMs else 2000L)
            sb.append(n).append('\n')
                .append(srtTime(e.startMs)).append(" --> ").append(srtTime(end)).append('\n')
                .append(text).append("\n\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private class Line(val order: Long, val idx: Int, val text: String)

    private fun ass(track: MkvTrack, events: List<SubEvent>): ByteArray {
        var header = (track.codecPrivate?.toString(Charsets.UTF_8) ?: "")
            .removePrefix("﻿")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        if (header.isBlank()) header = DEFAULT_HEADER
        if (!header.contains("[Events]")) {
            header = header.trimEnd() +
                "\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
        } else if (!header.endsWith("\n")) {
            header += "\n"
        }

        val lines = ArrayList<Line>()
        for ((i, e) in events.withIndex()) {
            val s = String(e.payload, Charsets.UTF_8)
            val c1 = s.indexOf(',')
            val c2 = if (c1 >= 0) s.indexOf(',', c1 + 1) else -1
            if (c1 < 0 || c2 < 0) continue
            val order = s.substring(0, c1).trim().toLongOrNull() ?: i.toLong()
            val layer = s.substring(c1 + 1, c2)
            val rest = s.substring(c2 + 1).trimEnd('\r', '\n', '\u0000')
            val end = e.startMs + (if (e.durMs >= 0) e.durMs else 2000L)
            lines.add(Line(order, i, "Dialogue: $layer,${assTime(e.startMs)},${assTime(end)},$rest"))
        }
        lines.sortWith(compareBy<Line>({ it.order }, { it.idx }))

        val sb = StringBuilder(header)
        for (l in lines) sb.append(l.text).append('\n')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun srt(events: List<SubEvent>): ByteArray {
        val sorted = events.sortedBy { it.startMs }
        val sb = StringBuilder("﻿")
        var n = 0
        for (e in sorted) {
            val text = String(e.payload, Charsets.UTF_8)
                .replace("\r\n", "\n")
                .trim('\n', '\r', '\u0000')
            if (text.isBlank()) continue
            n++
            val end = e.startMs + (if (e.durMs >= 0) e.durMs else 2000L)
            sb.append(n).append('\n')
                .append(srtTime(e.startMs)).append(" --> ").append(srtTime(end)).append('\n')
                .append(text).append("\n\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun assTime(ms: Long): String {
        val t = if (ms < 0) 0L else ms
        val cs = (t / 10) % 100
        val s = (t / 1000) % 60
        val m = (t / 60000) % 60
        val h = t / 3600000
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    private fun srtTime(ms: Long): String {
        val t = if (ms < 0) 0L else ms
        val milli = t % 1000
        val s = (t / 1000) % 60
        val m = (t / 60000) % 60
        val h = t / 3600000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}
