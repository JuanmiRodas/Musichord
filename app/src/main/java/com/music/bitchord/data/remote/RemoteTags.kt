package com.music.bitchord.data.remote

import java.nio.charset.Charset

/**
 * BitChord Audio: the tags of a remote audio file, read from its first bytes.
 *
 * The remote libraries named every track after its file ("Artist - Title.flac")
 * and its album after its folder, because reading tags means fetching part of
 * each file. This reads them from a single ranged fetch of the file's head —
 * [HEAD_BYTES] covers the metadata of every ordinary file — and
 * [RemoteTagStore] keeps the answer, so each file is read once, not per visit.
 *
 * Understood: ID3v2.2–2.4 (MP3, and AIFF/WAV-wrapped where the tag leads), FLAC
 * (STREAMINFO and Vorbis comments), Ogg Vorbis and Opus, and MP4/M4A when its
 * `moov` box comes first. Anything else is null and the file-name guess stays.
 *
 * Besides the usual fields, [Tags.altTitles] and [Tags.altArtists] collect the
 * names a file gives for the same song in another script or spelling
 * (`ORIGINALTITLE`, `TITLE_ROMAJI`, ID3 `TOAL`/`TOPE`, a `TXXX` of those names…).
 * Lyric lookups try them when the shown title finds nothing — see
 * [com.music.bitchord.data.lyrics.LyricAliases].
 */
object RemoteTags {

    /** How much of a file is fetched to read its tags. */
    const val HEAD_BYTES = 512 * 1024

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val trackNumber: Int? = null,
        val durationMs: Long? = null,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val lossless: Boolean = false,
        val codec: String? = null,
        val altTitles: List<String> = emptyList(),
        val altArtists: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = title == null && artist == null && album == null

        /** "FLAC 24/96", "MP3", … — the badge text the player already shows for sources. */
        val qualityLabel: String?
            get() = codec?.let { c ->
                if (bitDepth != null && sampleRate != null) {
                    "$c $bitDepth/${formatRate(sampleRate)}"
                } else {
                    c
                }
            }

        val hiRes: Boolean get() = lossless && ((bitDepth ?: 0) > 16 || (sampleRate ?: 0) > 48_000)

        private fun formatRate(hz: Int): String =
            if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000.0)
    }

    fun parse(head: ByteArray): Tags? = runCatching {
        when {
            head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> {
                val id3 = id3(head)
                // A FLAC file may carry a leading ID3 tag; its own blocks follow.
                val after = id3?.second ?: 0
                val flac = if (after + 4 <= head.size && head.matches(after, "fLaC")) flac(head, after) else null
                merge(id3?.first, flac)
            }
            head.matches(0, "fLaC") -> flac(head, 0)
            head.matches(0, "OggS") -> ogg(head)
            head.size >= 12 && head.matches(4, "ftyp") -> mp4(head)
            else -> null
        }
    }.getOrNull()?.takeUnless { it.isEmpty && it.codec == null }

    private fun merge(a: Tags?, b: Tags?): Tags? {
        if (a == null) return b
        if (b == null) return a.copy(codec = a.codec ?: "MP3")
        return b.copy(
            title = b.title ?: a.title,
            artist = b.artist ?: a.artist,
            album = b.album ?: a.album,
            albumArtist = b.albumArtist ?: a.albumArtist,
            trackNumber = b.trackNumber ?: a.trackNumber,
            altTitles = (b.altTitles + a.altTitles).distinct(),
            altArtists = (b.altArtists + a.altArtists).distinct(),
        )
    }

    // ── ID3v2 ────────────────────────────────────────────────────────────

    /** The tag and the offset right after it. */
    private fun id3(b: ByteArray): Pair<Tags, Int>? {
        val version = b[3].toInt()
        val flags = b[5].toInt()
        val size = syncsafe(b, 6)
        val end = (10 + size).coerceAtMost(b.size)
        var tag = b.copyOfRange(10, end)
        if (flags and 0x80 != 0 && version < 4) tag = deunsync(tag)
        var at = 0
        if (flags and 0x40 != 0 && version >= 3) {
            // Extended header: skipped.
            at = if (version == 4) syncsafe(tag, 0) else u32(tag, 0) + 4
        }
        val fields = HashMap<String, MutableList<String>>()
        val txxx = HashMap<String, String>()
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        while (at + headLen <= tag.size) {
            val id = String(tag, at, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = when (version) {
                2 -> u24(tag, at + 3)
                4 -> syncsafe(tag, at + 4)
                else -> u32(tag, at + 4)
            }
            val bodyStart = at + headLen
            if (frameSize <= 0 || bodyStart + frameSize > tag.size) break
            val body = tag.copyOfRange(bodyStart, bodyStart + frameSize)
            val key = V22[id] ?: id
            if (key == "TXXX") {
                decodeTxxx(body)?.let { (desc, value) -> txxx[desc.uppercase()] = value }
            } else if (key.startsWith("T")) {
                decodeText(body)?.let { fields.getOrPut(key) { mutableListOf() } += it }
            }
            at = bodyStart + frameSize
        }
        fun f(k: String) = fields[k]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val alt = ALT_TITLE_KEYS.mapNotNull { txxx[it] } + listOfNotNull(f("TOAL"))
        val altArtist = ALT_ARTIST_KEYS.mapNotNull { txxx[it] } + listOfNotNull(f("TOPE"))
        val tags = Tags(
            title = f("TIT2"),
            artist = f("TPE1"),
            album = f("TALB"),
            albumArtist = f("TPE2"),
            trackNumber = f("TRCK")?.substringBefore('/')?.trim()?.toIntOrNull(),
            durationMs = f("TLEN")?.toLongOrNull(),
            codec = "MP3",
            altTitles = alt.filter { it.isNotBlank() }.distinct(),
            altArtists = altArtist.filter { it.isNotBlank() }.distinct(),
        )
        return tags to (10 + size + if (flags and 0x10 != 0) 10 else 0)
    }

    private val V22 = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TP2" to "TPE2",
        "TRK" to "TRCK", "TLE" to "TLEN", "TXX" to "TXXX", "TOT" to "TOAL", "TOA" to "TOPE",
    )

    private fun charsetFor(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun decodeText(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val text = String(body, 1, body.size - 1, charsetFor(body[0].toInt()))
        // v2.4 may hold several values split by NUL; the first is the one shown.
        return text.split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun decodeTxxx(body: ByteArray): Pair<String, String>? {
        if (body.isEmpty()) return null
        val parts = String(body, 1, body.size - 1, charsetFor(body[0].toInt())).split('\u0000')
        val desc = parts.firstOrNull()?.trim().orEmpty()
        val value = parts.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return desc to value
    }

    // ── FLAC ─────────────────────────────────────────────────────────────

    private fun flac(b: ByteArray, start: Int): Tags? {
        var at = start + 4
        var tags = Tags(codec = "FLAC", lossless = true)
        while (at + 4 <= b.size) {
            val header = b[at].toInt() and 0xff
            val last = header and 0x80 != 0
            val type = header and 0x7f
            val len = u24(b, at + 1)
            val body = at + 4
            if (body + len > b.size) break
            when (type) {
                0 -> if (len >= 18) {
                    // STREAMINFO: 20-bit rate, 3-bit channels-1, 5-bit depth-1, 36-bit samples.
                    val x = body + 10
                    val rate = ((b[x].u() shl 12) or (b[x + 1].u() shl 4) or (b[x + 2].u() shr 4))
                    val depth = (((b[x + 2].u() and 0x01) shl 4) or (b[x + 3].u() shr 4)) + 1
                    val samples = ((b[x + 3].u().toLong() and 0x0f) shl 32) or
                        (b[x + 4].u().toLong() shl 24) or (b[x + 5].u().toLong() shl 16) or
                        (b[x + 6].u().toLong() shl 8) or b[x + 7].u().toLong()
                    tags = tags.copy(
                        sampleRate = rate.takeIf { it > 0 },
                        bitDepth = depth,
                        durationMs = if (rate > 0 && samples > 0) samples * 1000 / rate else null,
                    )
                }
                4 -> tags = vorbisInto(tags, b, body, body + len)
            }
            if (last) break
            at = body + len
        }
        return tags
    }

    // ── Ogg (Vorbis / Opus) ──────────────────────────────────────────────

    private fun ogg(b: ByteArray): Tags? {
        // Reassemble the first few packets from their pages; the comment
        // header is the second packet of the stream.
        val packets = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var at = 0
        while (at + 27 <= b.size && packets.size < 3) {
            if (!b.matches(at, "OggS")) break
            val segments = b[at + 26].u()
            val tableEnd = at + 27 + segments
            if (tableEnd > b.size) break
            var data = tableEnd
            for (i in 0 until segments) {
                val lace = b[at + 27 + i].u()
                if (data + lace > b.size) return null
                current.write(b, data, lace)
                data += lace
                if (lace < 255) {
                    packets += current.toByteArray()
                    current = java.io.ByteArrayOutputStream()
                }
            }
            at = data
        }
        val ident = packets.getOrNull(0) ?: return null
        val comment = packets.getOrNull(1) ?: return null
        return when {
            ident.matches(0, "OpusHead") && comment.matches(0, "OpusTags") ->
                vorbisInto(Tags(codec = "Opus", sampleRate = 48_000), comment, 8, comment.size)
            ident.size > 16 && ident[0].toInt() == 1 && ident.matches(1, "vorbis") && comment.matches(1, "vorbis") -> {
                val rate = u32le(ident, 12)
                vorbisInto(Tags(codec = "Vorbis", sampleRate = rate.takeIf { it > 0 }), comment, 7, comment.size)
            }
            else -> null
        }
    }

    /** A Vorbis comment block (vendor string, then `KEY=value` entries) folded into [into]. */
    private fun vorbisInto(into: Tags, b: ByteArray, start: Int, end: Int): Tags {
        var at = start
        if (at + 4 > end) return into
        val vendorLen = u32le(b, at)
        at += 4 + vendorLen
        if (at + 4 > end) return into
        val count = u32le(b, at)
        at += 4
        val values = HashMap<String, MutableList<String>>()
        repeat(count) {
            if (at + 4 > end) return@repeat
            val len = u32le(b, at)
            at += 4
            if (len < 0 || at + len > end) return@repeat
            val entry = String(b, at, len, Charsets.UTF_8)
            at += len
            val eq = entry.indexOf('=')
            if (eq > 0) values.getOrPut(entry.substring(0, eq).uppercase()) { mutableListOf() } += entry.substring(eq + 1).trim()
        }
        fun v(k: String) = values[k]?.firstOrNull { it.isNotBlank() }
        return into.copy(
            title = v("TITLE") ?: into.title,
            artist = values["ARTIST"]?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: into.artist,
            album = v("ALBUM") ?: into.album,
            albumArtist = v("ALBUMARTIST") ?: v("ALBUM ARTIST") ?: into.albumArtist,
            trackNumber = v("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: into.trackNumber,
            altTitles = (into.altTitles + ALT_TITLE_KEYS.flatMap { values[it].orEmpty() }).filter { it.isNotBlank() }.distinct(),
            altArtists = (into.altArtists + ALT_ARTIST_KEYS.flatMap { values[it].orEmpty() }).filter { it.isNotBlank() }.distinct(),
        )
    }

    // ── MP4 / M4A ────────────────────────────────────────────────────────

    private fun mp4(b: ByteArray): Tags? {
        val moov = findBox(b, 0, b.size, "moov") ?: return Tags(codec = "AAC")
        var tags = Tags(codec = "AAC")
        // Duration from mvhd.
        findBox(b, moov.first, moov.second, "mvhd")?.let { (s, _) ->
            val version = b[s].u()
            val (scale, duration) = if (version == 1) {
                u32(b, s + 20) to u64(b, s + 24)
            } else {
                u32(b, s + 12) to (u32(b, s + 16).toLong() and 0xffffffffL)
            }
            if (scale > 0) tags = tags.copy(durationMs = duration * 1000 / scale)
        }
        // ALAC is lossless; the codec name sits in the sample description.
        if (indexOf(b, "alac", moov.first, moov.second) >= 0) tags = tags.copy(codec = "ALAC", lossless = true)
        val udta = findBox(b, moov.first, moov.second, "udta") ?: return tags
        val meta = findBox(b, udta.first, udta.second, "meta") ?: return tags
        // meta is a full box: 4 bytes of version/flags before its children.
        val ilst = findBox(b, meta.first + 4, meta.second, "ilst") ?: return tags
        var at = ilst.first
        val text = HashMap<String, String>()
        while (at + 8 <= ilst.second) {
            val size = u32(b, at)
            if (size < 8 || at + size > ilst.second) break
            val name = String(b, at + 4, 4, Charsets.ISO_8859_1)
            findBox(b, at + 8, at + size, "data")?.let { (s, e) ->
                // data: 4 bytes type, 4 bytes locale, then the value.
                if (name == "trkn" && e - s >= 12) {
                    tags = tags.copy(trackNumber = ((b[s + 10].u() shl 8) or b[s + 11].u()).takeIf { it > 0 })
                } else if (e - s > 8) {
                    text[name] = String(b, s + 8, e - s - 8, Charsets.UTF_8).trim()
                }
            }
            at += size
        }
        return tags.copy(
            title = text["©nam"],
            artist = text["©ART"],
            album = text["©alb"],
            albumArtist = text["aART"],
        )
    }

    /** The content range (after the 8-byte header) of the first [name] box inside [from, until). */
    private fun findBox(b: ByteArray, from: Int, until: Int, name: String): Pair<Int, Int>? {
        var at = from
        while (at + 8 <= until) {
            var size = u32(b, at).toLong() and 0xffffffffL
            var header = 8
            if (size == 1L && at + 16 <= until) {
                size = u64(b, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (until - at).toLong()
            }
            if (size < header || at + size > until) return null
            if (b.matches(at + 4, name)) return (at + header) to (at + size.toInt())
            at += size.toInt()
        }
        return null
    }

    // ── Names in another script ──────────────────────────────────────────

    private val ALT_TITLE_KEYS = listOf(
        "ORIGINALTITLE", "ORIGINAL TITLE", "TITLE_ORIGINAL", "TITLEORIGINAL",
        "ALTTITLE", "ALT_TITLE", "TITLE_ALT", "TITLE_ROMAJI", "TITLE_ROMANIZED",
        "TITLE_ROMANISED", "TITLE_JA", "TITLE_JP", "ROMAJI_TITLE", "LYRICS_TITLE",
    )

    private val ALT_ARTIST_KEYS = listOf(
        "ORIGINALARTIST", "ORIGINAL ARTIST", "ARTIST_ORIGINAL", "ALTARTIST", "ALT_ARTIST",
        "ARTIST_ALT", "ARTIST_ROMAJI", "ARTIST_ROMANIZED", "ARTIST_ROMANISED",
        "ARTIST_JA", "ARTIST_JP", "ROMAJI_ARTIST", "LYRICS_ARTIST",
    )

    // ── Bytes ────────────────────────────────────────────────────────────

    private fun Byte.u(): Int = toInt() and 0xff

    private fun ByteArray.matches(at: Int, ascii: String): Boolean {
        if (at < 0 || at + ascii.length > size) return false
        for (i in ascii.indices) if (this[at + i] != ascii[i].code.toByte()) return false
        return true
    }

    private fun indexOf(b: ByteArray, ascii: String, from: Int, until: Int): Int {
        val end = minOf(until, b.size) - ascii.length
        var i = from
        while (i <= end) {
            if (b.matches(i, ascii)) return i
            i++
        }
        return -1
    }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        (b[at].u() shl 21) or (b[at + 1].u() shl 14) or (b[at + 2].u() shl 7) or b[at + 3].u()

    private fun u24(b: ByteArray, at: Int): Int = (b[at].u() shl 16) or (b[at + 1].u() shl 8) or b[at + 2].u()

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].u() shl 24) or (b[at + 1].u() shl 16) or (b[at + 2].u() shl 8) or b[at + 3].u()

    private fun u32le(b: ByteArray, at: Int): Int =
        b[at].u() or (b[at + 1].u() shl 8) or (b[at + 2].u() shl 16) or (b[at + 3].u() shl 24)

    private fun u64(b: ByteArray, at: Int): Long =
        ((u32(b, at).toLong() and 0xffffffffL) shl 32) or (u32(b, at + 4).toLong() and 0xffffffffL)

    private fun deunsync(tag: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(tag.size)
        var i = 0
        while (i < tag.size) {
            out.write(tag[i].toInt())
            if (tag[i].u() == 0xff && i + 1 < tag.size && tag[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }
}
