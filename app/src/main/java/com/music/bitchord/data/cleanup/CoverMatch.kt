package com.music.bitchord.data.cleanup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * BitChord Audio: a better copy of a cover — the same picture, bigger. Candidates (YouTube
 * Music's album art, Apple's, the web's) are compared with the cover the song has now by
 * how they look (a 64-bit difference hash of the centre square, which survives size,
 * compression and a frame around it): only one that looks like it is taken, the largest.
 */
object CoverMatch {

    private const val TAG = "BitChord"
    /** Bits of 64 that may differ for "the same picture". */
    private const val SAME = 16

    /** Apple's album covers for [term], at 1200 px. */
    fun itunes(term: String): List<String> = runCatching {
        val q = URLEncoder.encode(term, "UTF-8")
        val req = Request.Builder().url("https://itunes.apple.com/search?entity=album&limit=4&term=$q").build()
        Http.client.newCall(req).execute().use { r ->
            val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return@use emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("artworkUrl100")?.takeIf { u -> u.startsWith("http") } }
                .map { it.replace(Regex("""/\d+x\d+bb\."""), "/1200x1200bb.") }
        }
    }.getOrDefault(emptyList())

    private fun bytes(url: String): ByteArray? = runCatching {
        when {
            url.startsWith("http") -> Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
            url.startsWith("file:") -> java.io.File(java.net.URI(url)).readBytes()
            url.startsWith("/") -> java.io.File(url).readBytes()
            else -> null
        }
    }.getOrNull()

    private data class Look(val hash: Long, val width: Int, val height: Int)

    private fun look(data: ByteArray): Look? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // The centre square: a video frame or a framed scan holds the cover in its middle.
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            fun lum(px: Int) = (px shr 16 and 0xff) * 299 + (px shr 8 and 0xff) * 587 + (px and 0xff) * 114
            if (lum(small.getPixel(x, y)) > lum(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
            bit++
        }
        return Look(hash, bounds.outWidth, bounds.outHeight)
    }

    /**
     * The best of [candidates]: one that looks like [current] (when there is a current cover
     * to compare with), the largest. Null when none does — the cover stays as it is.
     */
    fun best(current: String?, candidates: List<String>): String? {
        val now = current?.let(::bytes)?.let(::look)
        val looked = candidates.mapNotNull { url -> bytes(url)?.let(::look)?.let { url to it } }
        val pick = if (now == null) {
            looked.maxByOrNull { it.second.width * it.second.height }
        } else {
            looked.filter { java.lang.Long.bitCount(it.second.hash xor now.hash) <= SAME }
                .filter { it.second.width * it.second.height > now.width * now.height }
                .maxByOrNull { it.second.width * it.second.height }
        }
        TrackLog.d(TAG, "cover match: ${looked.size} candidates, now ${now?.let { "${it.width}px" } ?: "none"} → " +
            (pick?.let { "${it.second.width}px ${it.first.take(80)}" } ?: "none alike and bigger"))
        return pick?.first
    }
}
