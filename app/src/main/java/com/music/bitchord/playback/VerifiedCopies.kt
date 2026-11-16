package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream

/**
 * BitChord Audio: which other sources' copies of a YouTube song have been
 * listened to against it ([com.music.bitchord.playback.smart.SameRecording])
 * — and found the same recording, or not.
 *
 * A catalogue copy found by name (JioSaavn, SoundCloud, bilibili) plays from
 * the start only once it has been found the same; until then the song starts
 * on YouTube and the copy is checked while it plays. One found different is
 * never offered for that song again. Kept, and backed up with the other prefs.
 */
object VerifiedCopies {
    private const val PREFS = "bitchord_verified_copies"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(videoId: String, configId: String) = "$videoId|$configId"

    /** True: the same recording; false: not; null: never compared. */
    fun get(videoId: String, configId: String?): Boolean? {
        configId ?: return null
        val p = prefs ?: return null
        val k = key(videoId, configId)
        return if (p.contains(k)) p.getBoolean(k, false) else null
    }

    fun record(videoId: String, configId: String?, same: Boolean) {
        configId ?: return
        prefs?.edit()?.putBoolean(key(videoId, configId), same)?.apply()
    }

    /** A copy from a public catalogue, found by name only. */
    fun isCatalogue(stream: SourceStream): Boolean {
        val kind = stream.sourceConfigId?.let { SourceRegistry.config(it)?.kind } ?: return false
        return kind == SourceKind.JIOSAAVN || kind == SourceKind.SOUNDCLOUD || kind == SourceKind.BILIBILI
    }

    /** Whether [stream] may play [videoId] from its first note, unheard. */
    fun mayStartWith(videoId: String, stream: SourceStream): Boolean =
        !isCatalogue(stream) || get(videoId, stream.sourceConfigId) == true
}
