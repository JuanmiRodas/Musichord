package com.music.bitchord.data.cleanup

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BitChord Audio: a lossless file (FLAC, WAV, ALAC) made into AAC at 320 kbps in an
 * .m4a — the size of an MP3, the sound of the source. Decoded and encoded by the
 * phone's own codecs; above 48 kHz it is brought down to 48 kHz, more than two
 * channels to two.
 */
object Transcoder {

    private const val TIMEOUT = 10_000L
    private const val BITRATE = 320_000

    fun toAac320(input: File, output: File): Boolean = runCatching { run(input, output) }.getOrDefault(false)

    private fun run(input: File, output: File): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(input.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, null, null, 0)
        decoder.start()

        var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var srcChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = 2 // 16-bit
        val outRate = if (srcRate > 48_000) 48_000 else srcRate
        val outChannels = srcChannels.coerceIn(1, 2)

        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, outChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false

        // Interleaved 16-bit PCM waiting for the encoder, at outRate / outChannels.
        var pending = ShortArray(0)
        var pendingLen = 0
        var framesIn = 0L
        var resampleCarry = 0.0
        var lastFrame = ShortArray(outChannels)
        var inputDone = false
        var decodeDone = false
        var encodeDone = false
        var eosQueued = false
        val info = MediaCodec.BufferInfo()

        fun push(samples: ShortArray, n: Int) {
            if (pendingLen + n > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + n))
            System.arraycopy(samples, 0, pending, pendingLen, n)
            pendingLen += n
        }

        /** One decoded buffer → interleaved 16-bit at the output's rate and channels. */
        fun convert(buf: ByteBuffer, size: Int) {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val frames: Int
            val read: (Int, Int) -> Short
            when (pcmEncoding) {
                4 -> { // float
                    val f = buf.asFloatBuffer()
                    frames = size / 4 / srcChannels
                    read = { fr, ch -> (f.get(fr * srcChannels + ch).coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                }
                21 -> { // 24-bit packed
                    frames = size / 3 / srcChannels
                    read = { fr, ch ->
                        val o = buf.position() + (fr * srcChannels + ch) * 3
                        ((buf.get(o + 2).toInt() shl 8) or (buf.get(o + 1).toInt() and 0xff)).toShort()
                    }
                }
                22 -> { // 32-bit int
                    val i = buf.asIntBuffer()
                    frames = size / 4 / srcChannels
                    read = { fr, ch -> (i.get(fr * srcChannels + ch) shr 16).toShort() }
                }
                else -> {
                    val s = buf.asShortBuffer()
                    frames = size / 2 / srcChannels
                    read = { fr, ch -> s.get(fr * srcChannels + ch) }
                }
            }
            if (frames <= 0) return
            val out = ShortArray(((frames.toLong() * outRate / srcRate) + 2).toInt() * outChannels)
            var n = 0
            if (outRate == srcRate) {
                for (fr in 0 until frames) for (ch in 0 until outChannels) out[n++] = read(fr, ch)
            } else {
                val step = srcRate.toDouble() / outRate
                var pos = resampleCarry
                while (pos < frames) {
                    val i0 = pos.toInt()
                    val t = pos - i0
                    for (ch in 0 until outChannels) {
                        val a = if (i0 == 0 && t < 0) lastFrame[ch] else read(i0, ch)
                        val b = if (i0 + 1 < frames) read(i0 + 1, ch) else a
                        out[n++] = (a + (b - a) * t).toInt().toShort()
                    }
                    pos += step
                }
                resampleCarry = pos - frames
                for (ch in 0 until outChannels) lastFrame[ch] = read(frames - 1, ch)
            }
            push(out, n)
        }

        try {
            while (!encodeDone) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT)
                    if (i >= 0) {
                        val b = decoder.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val b = decoder.getOutputBuffer(o)!!
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            convert(b.slice(), info.size)
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                    }
                }
                // Feed the encoder what has been decoded.
                while (pendingLen > 0 || (decodeDone && !eosQueued)) {
                    val i = encoder.dequeueInputBuffer(if (decodeDone) TIMEOUT else 0)
                    if (i < 0) break
                    val b = encoder.getInputBuffer(i)!!
                    b.clear()
                    b.order(ByteOrder.LITTLE_ENDIAN)
                    val room = b.capacity() / 2 / outChannels * outChannels
                    val n = minOf(room, pendingLen)
                    if (n == 0 && decodeDone) {
                        encoder.queueInputBuffer(i, 0, 0, framesIn * 1_000_000L / outRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                        break
                    }
                    b.asShortBuffer().put(pending, 0, n)
                    encoder.queueInputBuffer(i, 0, n * 2, framesIn * 1_000_000L / outRate, 0)
                    framesIn += n / outChannels
                    System.arraycopy(pending, n, pending, 0, pendingLen - n)
                    pendingLen -= n
                }
                // Drain the encoder into the file.
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info, if (eosQueued) TIMEOUT else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                        continue
                    }
                    if (o < 0) continue
                    val b = encoder.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        muxer.writeSampleData(muxTrack, b, info)
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encodeDone = true
                        break
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }; runCatching { decoder.release() }
            runCatching { encoder.stop() }; runCatching { encoder.release() }
            runCatching { if (muxStarted) muxer.stop() }; runCatching { muxer.release() }
            runCatching { ex.release() }
        }
        return muxStarted && output.length() > 64 * 1024
    }
}
