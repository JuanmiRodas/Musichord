package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.json.JSONObject

/**
 * BitChord Audio: for a copy that plays in a song's place — your file, a
 * JioSaavn copy, an addon's, another upload — the song it stands in for, as it
 * was first queued. So "restore the original source" always knows what the
 * original is, however many swaps ago. A copy of a copy leads back to the
 * first original. Kept on the phone, newest [MAX] copies.
 */
object SongOrigins {

    private const val MAX = 3_000
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_song_origins", Context.MODE_PRIVATE)
    }

    /** [copy] now plays for [original]. */
    fun record(copy: Song, original: Song) {
        val p = prefs ?: return
        if (copy.videoId == original.videoId || copy.videoId.isBlank()) return
        // The original of the original, if [original] is itself a copy.
        val root = originOf(original.videoId) ?: original
        if (root.videoId == copy.videoId) {
            // Swapping back to the original itself: nothing to remember.
            p.edit().remove(copy.videoId).apply()
            return
        }
        val json = JSONObject()
            .put("id", root.videoId).put("title", root.title).put("artist", root.artist)
            .put("album", root.albumName ?: "").put("duration", root.durationText ?: "")
            .put("thumb", root.thumbnailUrl ?: "").put("at", System.currentTimeMillis())
            .toString()
        val edit = p.edit().putString(copy.videoId, json)
        if (p.all.size > MAX) {
            p.all.entries.mapNotNull { (k, v) -> runCatching { k to JSONObject(v as String).optLong("at") }.getOrNull() }
                .sortedBy { it.second }.take(p.all.size - MAX + 100).forEach { edit.remove(it.first) }
        }
        edit.apply()
    }

    /** The song [copyId] stands in for, or null when it is nobody's copy. */
    fun originOf(copyId: String): Song? {
        val v = prefs?.getString(copyId, null) ?: return null
        return runCatching {
            val o = JSONObject(v)
            Song(
                videoId = o.getString("id"),
                title = o.getString("title"),
                artist = o.optString("artist"),
                albumName = o.optString("album").ifBlank { null },
                durationText = o.optString("duration").ifBlank { null },
                thumbnailUrl = o.optString("thumb").ifBlank { null },
            )
        }.getOrNull()
    }
}
