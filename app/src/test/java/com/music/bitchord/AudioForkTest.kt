package com.music.bitchord

import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** BitChord Audio: the fork's pure logic — remote tag reading and the Liked Music merge. */
class AudioForkTest {

    // ── Builders ─────────────────────────────────────────────────────────

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xff)
    private fun ByteArrayOutputStream.u24(v: Int) { u8(v shr 16); u8(v shr 8); u8(v) }
    private fun ByteArrayOutputStream.u32(v: Int) { u8(v shr 24); u8(v shr 16); u8(v shr 8); u8(v) }
    private fun ByteArrayOutputStream.u32le(v: Int) { u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24) }
    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun vorbisComment(vararg entries: String): ByteArray = ByteArrayOutputStream().apply {
        val vendor = "test".toByteArray()
        u32le(vendor.size); write(vendor)
        u32le(entries.size)
        entries.forEach { e -> val b = e.toByteArray(Charsets.UTF_8); u32le(b.size); write(b) }
    }.toByteArray()

    private fun flac(vararg comments: String): ByteArray = ByteArrayOutputStream().apply {
        ascii("fLaC")
        // STREAMINFO: 96 kHz, 2 channels, 24-bit, 96000*180 samples (3:00).
        u8(0x00); u24(34)
        u32(0); u32(0) // block sizes, frame sizes (8 bytes)
        u8(0); u8(0) // rest of frame sizes
        val rate = 96_000
        val channelsMinus1 = 1
        val depthMinus1 = 23
        val samples = 96_000L * 180
        u8(rate shr 12)
        u8(rate shr 4)
        u8(((rate and 0x0f) shl 4) or (channelsMinus1 shl 1) or (depthMinus1 shr 4))
        u8(((depthMinus1 and 0x0f) shl 4) or ((samples shr 32).toInt() and 0x0f))
        u32((samples and 0xffffffffL).toInt())
        repeat(16) { u8(0) } // MD5
        val vc = vorbisComment(*comments)
        u8(0x80 or 4); u24(vc.size); write(vc)
    }.toByteArray()

    private fun id3Frame(id: String, text: String): ByteArray = ByteArrayOutputStream().apply {
        val body = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        ascii(id); u32(body.size); u8(0); u8(0); write(body)
    }.toByteArray()

    private fun id3(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b }
        return ByteArrayOutputStream().apply {
            ascii("ID3"); u8(3); u8(0); u8(0)
            val n = body.size
            u8((n shr 21) and 0x7f); u8((n shr 14) and 0x7f); u8((n shr 7) and 0x7f); u8(n and 0x7f)
            write(body)
            write(ByteArray(64)) // audio would follow
        }.toByteArray()
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        return ByteArrayOutputStream().apply { u32(8 + body.size); ascii(type); write(body) }.toByteArray()
    }

    private fun mp4(title: String, artist: String): ByteArray {
        fun item(name: String, text: String) = box(
            name,
            ByteArrayOutputStream().apply {
                val v = text.toByteArray(Charsets.UTF_8)
                u32(16 + v.size); ascii("data"); u32(1); u32(0); write(v)
            }.toByteArray(),
        )
        val mvhd = ByteArrayOutputStream().apply {
            u8(0); u24(0); u32(0); u32(0); u32(1000); u32(200_000) // version, flags, dates, scale, 200 s
            write(ByteArray(80))
        }.toByteArray()
        val meta = ByteArrayOutputStream().apply { u32(0) }.toByteArray() +
            box("ilst", item("©nam", title), item("©ART", artist))
        return box("ftyp", "M4A ".toByteArray()) +
            box("moov", box("mvhd", mvhd), box("udta", box("meta", meta)))
    }

    // ── Tags ─────────────────────────────────────────────────────────────

    @Test
    fun `flac tags, quality and romaji alias`() {
        val tags = RemoteTags.parse(
            flac("TITLE=夜に駆ける", "ARTIST=YOASOBI", "ALBUM=THE BOOK", "TRACKNUMBER=3/10", "TITLE_ROMAJI=Yoru ni Kakeru"),
        )
        assertNotNull(tags)
        tags!!
        assertEquals("夜に駆ける", tags.title)
        assertEquals("YOASOBI", tags.artist)
        assertEquals("THE BOOK", tags.album)
        assertEquals(3, tags.trackNumber)
        assertEquals(96_000, tags.sampleRate)
        assertEquals(24, tags.bitDepth)
        assertEquals(180_000L, tags.durationMs)
        assertTrue(tags.lossless)
        assertTrue(tags.hiRes)
        assertEquals("FLAC 24/96", tags.qualityLabel)
        assertEquals(listOf("Yoru ni Kakeru"), tags.altTitles)
    }

    @Test
    fun `id3 text frames and original title`() {
        val txxx = ByteArrayOutputStream().apply {
            val body = byteArrayOf(3) + "ORIGINALTITLE".toByteArray() + byteArrayOf(0) + "残酷な天使のテーゼ".toByteArray(Charsets.UTF_8)
            write("TXXX".toByteArray()); u32(body.size); u8(0); u8(0); write(body)
        }.toByteArray()
        val tags = RemoteTags.parse(
            id3(id3Frame("TIT2", "Zankoku na Tenshi no Thesis"), id3Frame("TPE1", "Yoko Takahashi"), id3Frame("TRCK", "1"), txxx),
        )!!
        assertEquals("Zankoku na Tenshi no Thesis", tags.title)
        assertEquals("Yoko Takahashi", tags.artist)
        assertEquals(1, tags.trackNumber)
        assertEquals("MP3", tags.codec)
        assertEquals(listOf("残酷な天使のテーゼ"), tags.altTitles)
    }

    @Test
    fun `mp4 title, artist and length`() {
        val tags = RemoteTags.parse(mp4("Lemon", "Kenshi Yonezu"))!!
        assertEquals("Lemon", tags.title)
        assertEquals("Kenshi Yonezu", tags.artist)
        assertEquals(200_000L, tags.durationMs)
    }

    @Test
    fun `unknown bytes are no tags`() {
        assertNull(RemoteTags.parse(ByteArray(64) { it.toByte() }))
    }

    // ── Liked Music merge ────────────────────────────────────────────────

    private fun s(id: String) = Song(videoId = id, title = id, artist = "a", thumbnailUrl = null)

    @Test
    fun `new likes go in front of the saved list`() {
        val saved = listOf("c", "d", "e").map(::s)
        val fresh = listOf("a", "b", "c", "d").map(::s)
        assertEquals(listOf("a", "b", "c", "d", "e"), LikedMusicCache.merge(fresh, saved)!!.map { it.videoId })
    }

    @Test
    fun `an unlike near the top leaves`() {
        val saved = listOf("a", "b", "c", "d", "e").map(::s)
        val fresh = listOf("a", "c", "d").map(::s) // b un-liked
        assertEquals(listOf("a", "c", "d", "e"), LikedMusicCache.merge(fresh, saved)!!.map { it.videoId })
    }

    @Test
    fun `no overlap means walking the whole list`() {
        assertNull(LikedMusicCache.merge(listOf("x", "y").map(::s), listOf("a", "b").map(::s)))
    }
}
