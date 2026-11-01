package com.music.bitchord.data

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * BitChord Audio: applies a bsdiff patch (BSDIFF40) — how an update can be a
 * few MB instead of the whole APK.
 *
 * The format: a 32-byte header (magic, then the lengths of the control and
 * diff blocks and the new file's size), then three bzip2 streams — control
 * triples (bytes to add from the diff block onto the old file, bytes to copy
 * from the extra block, how far to move in the old file), the diff bytes,
 * and the extra bytes. The old file is read where it is (the installed APK),
 * and the new one written as it comes, so neither is held in memory.
 */
object BsPatch {

    fun apply(old: File, patch: File, out: File) {
        val header = ByteArray(32)
        FileInputStream(patch).use { DataInputStream(it).readFully(header) }
        require(String(header, 0, 8, Charsets.US_ASCII) == "BSDIFF40") { "Not a bsdiff patch" }
        val ctrlLen = offtin(header, 8)
        val diffLen = offtin(header, 16)
        val newSize = offtin(header, 24)
        require(ctrlLen >= 0 && diffLen >= 0 && newSize >= 0) { "Corrupt patch header" }

        fun section(offset: Long): InputStream {
            val raw = FileInputStream(patch)
            var skipped = 0L
            while (skipped < offset) {
                val n = raw.skip(offset - skipped)
                if (n <= 0) throw EOFException("Patch too short")
                skipped += n
            }
            return BZip2CompressorInputStream(BufferedInputStream(raw, 64 * 1024))
        }

        section(32).use { ctrl ->
            section(32 + ctrlLen).use { diff ->
                section(32 + ctrlLen + diffLen).use { extra ->
                    RandomAccessFile(old, "r").use { oldFile ->
                        BufferedOutputStream(out.outputStream(), 256 * 1024).use { output ->
                            val oldSize = oldFile.length()
                            val triple = ByteArray(24)
                            val buf = ByteArray(64 * 1024)
                            val oldBuf = ByteArray(64 * 1024)
                            var newPos = 0L
                            var oldPos = 0L
                            while (newPos < newSize) {
                                readFully(ctrl, triple, 24)
                                val addLen = offtin(triple, 0)
                                val copyLen = offtin(triple, 8)
                                val seek = offtin(triple, 16)
                                require(addLen >= 0 && copyLen >= 0 && newPos + addLen + copyLen <= newSize) { "Corrupt patch" }
                                // Diff bytes added onto the old file's bytes.
                                var left = addLen
                                while (left > 0) {
                                    val n = minOf(left, buf.size.toLong()).toInt()
                                    readFully(diff, buf, n)
                                    java.util.Arrays.fill(oldBuf, 0, n, 0)
                                    if (oldPos < oldSize && oldPos + n > 0) {
                                        val from = maxOf(oldPos, 0L)
                                        val upTo = minOf(oldPos + n, oldSize)
                                        if (upTo > from) {
                                            oldFile.seek(from)
                                            oldFile.readFully(oldBuf, (from - oldPos).toInt(), (upTo - from).toInt())
                                        }
                                    }
                                    for (i in 0 until n) buf[i] = (buf[i] + oldBuf[i]).toByte()
                                    output.write(buf, 0, n)
                                    left -= n
                                    oldPos += n
                                    newPos += n
                                }
                                // Extra bytes, copied as they are.
                                left = copyLen
                                while (left > 0) {
                                    val n = minOf(left, buf.size.toLong()).toInt()
                                    readFully(extra, buf, n)
                                    output.write(buf, 0, n)
                                    left -= n
                                    newPos += n
                                }
                                oldPos += seek
                            }
                        }
                    }
                }
            }
        }
        require(out.length() == newSize) { "Patched file has the wrong size" }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("Patch ended early")
            off += n
        }
    }

    /** bsdiff's sign-and-magnitude little-endian 64-bit number. */
    private fun offtin(b: ByteArray, at: Int): Long {
        var y = (b[at + 7].toLong() and 0x7F)
        for (i in 6 downTo 0) y = y * 256 + (b[at + i].toLong() and 0xFF)
        return if (b[at + 7].toInt() and 0x80 != 0) -y else y
    }
}
