package com.music.bitchord.data.covers

import java.io.ByteArrayOutputStream

/**
 * BitChord Audio: a new front cover written into a file's own tags — the
 * ID3v2 tag of an MP3, the metadata blocks of a FLAC — with everything else in
 * the tag kept as it was and the audio itself untouched.
 *
 * Pure byte work, kept apart from the network so it can be tested: given the
 * start of a file (at least its whole tag), [rewrite] says what the new start
 * is and how many bytes of the old one it replaces; the rest of the file is
 * copied after it unchanged.
 */
object CoverTags {

    /** [head] replaces the first [replaces] bytes of the file. */
    class Rewrite(val head: ByteArray, val replaces: Long)

    sealed interface Kind {
        data object Mp3 : Kind
        data object Flac : Kind
        data object Unsupported : Kind
    }

    fun kindOf(extension: String): Kind = when (extension.lowercase()) {
        "mp3" -> Kind.Mp3
        "flac" -> Kind.Flac
        else -> Kind.Unsupported
    }

    /** How much of the file's start is needed: its tag, which its first bytes say the length of. */
    fun headLength(first: ByteArray, kind: Kind): Long? = when (kind) {
        Kind.Mp3 -> if (isId3(first)) id3TotalSize(first) else 0L
        Kind.Flac -> null // walked block by block, see [flacMetadataEnd]
        Kind.Unsupported -> null
    }

    fun isId3(b: ByteArray) = b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or
            ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun u32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun syncsafeBytes(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(), ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte(),
    )

    private fun u32Bytes(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** The whole ID3v2 tag's length, header (and footer) included. */
    fun id3TotalSize(b: ByteArray): Long {
        val footer = b[3].toInt() == 4 && (b[5].toInt() and 0x10) != 0
        return 10L + syncsafe(b, 6) + if (footer) 10 else 0
    }

    /**
     * An MP3's tag with the front cover replaced: every other frame kept as
     * it was, any old pictures dropped, the new one added. A tag in a form this
     * can't safely rewrite (ID3v2.2, or unsynchronised as a whole) is refused
     * rather than half-rewritten. No tag at all: a new ID3v2.3 tag with just
     * the cover.
     */
    fun mp3(head: ByteArray, cover: ByteArray, mime: String): Rewrite? {
        if (!isId3(head)) {
            return Rewrite(id3Tag(3, emptyList(), apic(3, cover, mime)), 0)
        }
        val version = head[3].toInt()
        val flags = head[5].toInt()
        if (version !in 3..4 || (flags and 0x80) != 0) return null
        val total = id3TotalSize(head)
        if (head.size < total) return null
        val end = (10 + syncsafe(head, 6)).coerceAtMost(head.size)
        var at = 10
        if ((flags and 0x40) != 0) {
            at += if (version == 3) 4 + u32(head, 10) else syncsafe(head, 10)
        }
        val frames = ArrayList<ByteArray>()
        while (at + 10 <= end) {
            if (head[at].toInt() == 0) break // padding
            val id = String(head, at, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (version == 4) syncsafe(head, at + 4) else u32(head, at + 4)
            if (size < 0 || at + 10 + size > end) return null
            if (id != "APIC") frames += head.copyOfRange(at, at + 10 + size)
            at += 10 + size
        }
        return Rewrite(id3Tag(version, frames, apic(version, cover, mime)), total)
    }

    private fun apic(version: Int, cover: ByteArray, mime: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0) // ISO-8859-1
            write(mime.toByteArray(Charsets.ISO_8859_1)); write(0)
            write(3) // front cover
            write(0) // empty description
            write(cover)
        }.toByteArray()
        val size = if (version == 4) syncsafeBytes(body.size) else u32Bytes(body.size)
        return "APIC".toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + body
    }

    private fun id3Tag(version: Int, frames: List<ByteArray>, picture: ByteArray): ByteArray {
        val padding = 2048
        val bodySize = frames.sumOf { it.size } + picture.size + padding
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(version); write(0)
            write(0) // no unsynchronisation, extended header or footer
            write(syncsafeBytes(bodySize))
            frames.forEach { write(it) }
            write(picture)
            write(ByteArray(padding))
        }.toByteArray()
    }

    /**
     * Where a FLAC's metadata ends, read from its block headers ([read] gives
     * bytes at an offset); null when it isn't a FLAC.
     */
    fun flacMetadataEnd(read: (offset: Long, length: Int) -> ByteArray): Long? {
        val magic = read(0, 4)
        if (magic.size < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        var at = 4L
        while (true) {
            val h = read(at, 4)
            if (h.size < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val len = ((h[1].toInt() and 0xff) shl 16) or ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
            at += 4 + len
            if (last) return at
            if (at > 512L * 1024 * 1024) return null
        }
    }

    /**
     * A FLAC's metadata with the front cover replaced: STREAMINFO and every
     * other block kept in order, front-cover PICTURE blocks dropped, the new
     * picture put in before any padding.
     */
    fun flac(metadata: ByteArray, cover: ByteArray, mime: String, width: Int, height: Int): Rewrite? {
        if (metadata.size < 8 || String(metadata, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        var at = 4
        while (at + 4 <= metadata.size) {
            val type = metadata[at].toInt() and 0x7f
            val last = (metadata[at].toInt() and 0x80) != 0
            val len = ((metadata[at + 1].toInt() and 0xff) shl 16) or ((metadata[at + 2].toInt() and 0xff) shl 8) or (metadata[at + 3].toInt() and 0xff)
            if (at + 4 + len > metadata.size) return null
            val body = metadata.copyOfRange(at + 4, at + 4 + len)
            val frontCover = type == 6 && body.size >= 4 && u32(body, 0).let { it == 3 || it == 0 }
            if (!frontCover) blocks += type to body
            at += 4 + len
            if (last) break
        }
        if (blocks.firstOrNull()?.first != 0) return null // STREAMINFO must lead
        val picture = ByteArrayOutputStream().apply {
            val m = mime.toByteArray(Charsets.US_ASCII)
            write(u32Bytes(3)); write(u32Bytes(m.size)); write(m)
            write(u32Bytes(0)) // no description
            write(u32Bytes(width)); write(u32Bytes(height)); write(u32Bytes(24)); write(u32Bytes(0))
            write(u32Bytes(cover.size)); write(cover)
        }.toByteArray()
        if (picture.size >= 1 shl 24) return null
        val paddingAt = blocks.indexOfFirst { it.first == 1 }.let { if (it < 0) blocks.size else it }
        blocks.add(paddingAt, 6 to picture)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { i, (type, body) ->
            val last = i == blocks.lastIndex
            out.write((if (last) 0x80 else 0) or type)
            out.write((body.size shr 16) and 0xff); out.write((body.size shr 8) and 0xff); out.write(body.size and 0xff)
            out.write(body)
        }
        return Rewrite(out.toByteArray(), at.toLong())
    }

    // ---- BitChord Audio: text tags (the metadata editor) --------------------

    /**
     * The words written into a file: null leaves that tag as it is, empty
     * removes it.
     */
    data class Text(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: String? = null,
        val track: String? = null,
        val genre: String? = null,
    ) {
        val isEmpty: Boolean get() = listOf(title, artist, album, albumArtist, year, track, genre).all { it == null }
    }

    private fun id3Frames(t: Text, version: Int): Map<String, String?> = buildMap {
        t.title?.let { put("TIT2", it) }
        t.artist?.let { put("TPE1", it) }
        t.album?.let { put("TALB", it) }
        t.albumArtist?.let { put("TPE2", it) }
        t.year?.let { put(if (version == 4) "TDRC" else "TYER", it) }
        t.track?.let { put("TRCK", it) }
        t.genre?.let { put("TCON", it) }
    }

    private fun textFrame(version: Int, id: String, value: String): ByteArray {
        // UTF-16 with BOM (v2.3) / UTF-8 (v2.4): any script, Japanese included.
        val body = if (version == 4) {
            byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        } else {
            byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
        }
        val size = if (version == 4) syncsafeBytes(body.size) else u32Bytes(body.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf(0, 0) + body
    }

    /**
     * An MP3's tag with the given text frames replaced (year: TYER and TDRC
     * both dropped and the version's own written); everything else, the cover
     * included, kept. No tag: a new ID3v2.3 one with just these.
     */
    fun mp3Text(head: ByteArray, text: Text): Rewrite? {
        if (!isId3(head)) {
            val frames = id3Frames(text, 3).mapNotNull { (id, v) -> v?.takeIf { it.isNotEmpty() }?.let { textFrame(3, id, it) } }
            return Rewrite(id3Tag(3, frames, ByteArray(0)), 0)
        }
        val version = head[3].toInt()
        val flags = head[5].toInt()
        if (version !in 3..4 || (flags and 0x80) != 0) return null
        val total = id3TotalSize(head)
        if (head.size < total) return null
        val end = (10 + syncsafe(head, 6)).coerceAtMost(head.size)
        var at = 10
        if ((flags and 0x40) != 0) {
            at += if (version == 3) 4 + u32(head, 10) else syncsafe(head, 10)
        }
        val replacing = id3Frames(text, version)
        val dropping = replacing.keys + if (text.year != null) setOf("TYER", "TDRC", "TDAT") else emptySet()
        val frames = ArrayList<ByteArray>()
        while (at + 10 <= end) {
            if (head[at].toInt() == 0) break
            val id = String(head, at, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (version == 4) syncsafe(head, at + 4) else u32(head, at + 4)
            if (size < 0 || at + 10 + size > end) return null
            if (id !in dropping) frames += head.copyOfRange(at, at + 10 + size)
            at += 10 + size
        }
        replacing.forEach { (id, v) -> if (!v.isNullOrEmpty()) frames += textFrame(version, id, v) }
        return Rewrite(id3Tag(version, frames, ByteArray(0)), total)
    }

    /**
     * A FLAC's metadata with its Vorbis comments changed: the named fields
     * replaced, every other comment (and block) kept. No comment block: one is
     * added after STREAMINFO.
     */
    fun flacText(metadata: ByteArray, text: Text): Rewrite? {
        if (metadata.size < 8 || String(metadata, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        var at = 4
        while (at + 4 <= metadata.size) {
            val type = metadata[at].toInt() and 0x7f
            val last = (metadata[at].toInt() and 0x80) != 0
            val len = ((metadata[at + 1].toInt() and 0xff) shl 16) or ((metadata[at + 2].toInt() and 0xff) shl 8) or (metadata[at + 3].toInt() and 0xff)
            if (at + 4 + len > metadata.size) return null
            blocks += type to metadata.copyOfRange(at + 4, at + 4 + len)
            at += 4 + len
            if (last) break
        }
        if (blocks.firstOrNull()?.first != 0) return null
        fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        fun le32Bytes(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
        val index = blocks.indexOfFirst { it.first == 4 }
        var vendor = "BitChord Audio".toByteArray()
        val comments = ArrayList<String>()
        if (index >= 0) {
            val b = blocks[index].second
            var o = 0
            val vlen = le32(b, o); o += 4
            if (vlen < 0 || o + vlen > b.size) return null
            vendor = b.copyOfRange(o, o + vlen); o += vlen
            val n = le32(b, o); o += 4
            repeat(n) {
                if (o + 4 > b.size) return null
                val l = le32(b, o); o += 4
                if (l < 0 || o + l > b.size) return null
                comments += String(b, o, l, Charsets.UTF_8); o += l
            }
        }
        val fields = buildMap {
            text.title?.let { put("TITLE", it) }
            text.artist?.let { put("ARTIST", it) }
            text.album?.let { put("ALBUM", it) }
            text.albumArtist?.let { put("ALBUMARTIST", it) }
            text.year?.let { put("DATE", it) }
            text.track?.let { put("TRACKNUMBER", it) }
            text.genre?.let { put("GENRE", it) }
        }
        val kept = comments.filter { c -> c.substringBefore('=').uppercase() !in fields.keys }
        val all = kept + fields.filterValues { it.isNotEmpty() }.map { (k, v) -> "$k=$v" }
        val body = ByteArrayOutputStream().apply {
            write(le32Bytes(vendor.size)); write(vendor)
            write(le32Bytes(all.size))
            all.forEach { c -> val bytes = c.toByteArray(Charsets.UTF_8); write(le32Bytes(bytes.size)); write(bytes) }
        }.toByteArray()
        if (body.size >= 1 shl 24) return null
        if (index >= 0) blocks[index] = 4 to body else blocks.add(1, 4 to body)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { i, (type, b) ->
            out.write((if (i == blocks.lastIndex) 0x80 else 0) or type)
            out.write((b.size shr 16) and 0xff); out.write((b.size shr 8) and 0xff); out.write(b.size and 0xff)
            out.write(b)
        }
        return Rewrite(out.toByteArray(), at.toLong())
    }
}
