package com.music.bitchord

import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.remote.EmbeddedArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** BitChord Audio: a chosen cover written into an MP3's or FLAC's tags, the rest kept. */
class CoverTagsTest {

    private class MemReader(val bytes: ByteArray) : EmbeddedArt.Reader {
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= bytes.size || length <= 0) return ByteArray(0)
            val end = minOf(offset + length, bytes.size.toLong()).toInt()
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }

    private val oldArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9, 9)
    private val newArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5)
    private val audio = ByteArray(500) { (it * 7).toByte() }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf(((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        id.toByteArray() + (if (v4) syncsafe(body.size) else u32(body.size)) + byteArrayOf(0, 0) + body

    private fun id3(version: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(byteArrayOf()) { a, b -> a + b } + ByteArray(64)
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(body.size) + body
    }

    private fun apply(file: ByteArray, r: CoverTags.Rewrite) = r.head + file.copyOfRange(r.replaces.toInt(), file.size)

    @Test
    fun `mp3 keeps its frames and gets the new cover`() {
        for (v in listOf(3, 4)) {
            val title = frame("TIT2", byteArrayOf(0) + "Boku ga".toByteArray(), v == 4)
            val pic = frame("APIC", byteArrayOf(0) + "image/jpeg\u0000".toByteArray() + byteArrayOf(3, 0) + oldArt, v == 4)
            val file = id3(v, title, pic) + audio
            val r = CoverTags.mp3(file.copyOfRange(0, CoverTags.headLength(file, CoverTags.Kind.Mp3)!!.toInt()), newArt, "image/jpeg")
            assertNotNull(r)
            val out = apply(file, r!!)
            val read = EmbeddedArt.extract(MemReader(out))
            assertTrue("v$v cover", read?.bytes?.contentEquals(newArt) == true)
            assertTrue("v$v title kept", String(out, Charsets.ISO_8859_1).contains("TIT2"))
            assertEquals("v$v one picture", 1, Regex("APIC").findAll(String(out, Charsets.ISO_8859_1)).count())
            assertTrue("v$v audio intact", out.copyOfRange(out.size - audio.size, out.size).contentEquals(audio))
        }
    }

    @Test
    fun `mp3 without a tag gets one`() {
        val r = CoverTags.mp3(audio.copyOfRange(0, 10), newArt, "image/jpeg")!!
        assertEquals(0L, r.replaces)
        val out = apply(audio, r)
        assertTrue(EmbeddedArt.extract(MemReader(out))?.bytes?.contentEquals(newArt) == true)
    }

    @Test
    fun `unsynchronised tag is left alone`() {
        val file = "ID3".toByteArray() + byteArrayOf(3, 0, 0x80.toByte()) + syncsafe(10) + ByteArray(10) + audio
        assertNull(CoverTags.mp3(file, newArt, "image/jpeg"))
    }

    private fun flacFile(withPicture: Boolean): ByteArray = ByteArrayOutputStream().apply {
        write("fLaC".toByteArray())
        fun block(type: Int, body: ByteArray, last: Boolean) {
            write((if (last) 0x80 else 0) or type); write(body.size shr 16); write(body.size shr 8); write(body.size)
            write(body)
        }
        block(0, ByteArray(34), false)
        block(4, "vorbis".toByteArray(), false)
        if (withPicture) {
            val m = "image/jpeg".toByteArray()
            block(6, u32(3) + u32(m.size) + m + u32(0) + u32(1) + u32(1) + u32(24) + u32(0) + u32(oldArt.size) + oldArt, false)
        }
        block(1, ByteArray(100), true)
        write(audio)
    }.toByteArray()

    @Test
    fun `flac picture replaced, other blocks kept`() {
        for (had in listOf(true, false)) {
            val file = flacFile(had)
            val end = CoverTags.flacMetadataEnd { off, len -> file.copyOfRange(off.toInt(), minOf(file.size, off.toInt() + len)) }!!
            val r = CoverTags.flac(file.copyOfRange(0, end.toInt()), newArt, "image/jpeg", 600, 600)!!
            val out = apply(file, r)
            assertTrue(EmbeddedArt.extract(MemReader(out))?.bytes?.contentEquals(newArt) == true)
            assertTrue(String(out, Charsets.ISO_8859_1).contains("vorbis"))
            assertTrue(out.copyOfRange(out.size - audio.size, out.size).contentEquals(audio))
            val end2 = CoverTags.flacMetadataEnd { off, len -> out.copyOfRange(off.toInt(), minOf(out.size, off.toInt() + len)) }
            assertEquals(out.size - audio.size.toLong(), end2)
        }
    }
}
