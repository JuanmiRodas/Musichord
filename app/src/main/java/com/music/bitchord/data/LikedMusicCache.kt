package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.Song
import java.io.File

/**
 * BitChord Audio: the Liked Music list as it was last seen, kept on the device.
 *
 * Upstream walks every page of Liked Music each time the page is opened — for a
 * large collection that is many requests and a list that keeps growing under
 * the listener's thumb. With this, the saved list is on screen at once and only
 * the first page is fetched: Liked Music is ordered newest first, so the songs
 * liked since last time are exactly the ones in front of the first song already
 * saved. See [merge].
 *
 * One file per YouTube profile, since each has its own Liked Music. A full walk
 * still happens when the saved list is older than [FULL_REFRESH_MS], so songs
 * un-liked from another device eventually leave too.
 */
object LikedMusicCache {

    /** Both ids the app uses for Liked Music. */
    val IDS = setOf(YtMusicRepository.LIKED_MUSIC, "LM")

    /** A week: after that the whole list is fetched again rather than merged. */
    const val FULL_REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    data class Saved(val songs: List<Song>, val savedAt: Long) {
        val stale: Boolean get() = System.currentTimeMillis() - savedAt > FULL_REFRESH_MS
    }

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "liked_music_cache").apply { mkdirs() }
    }

    private fun file(profile: String?): File? {
        val base = dir ?: return null
        val key = (profile ?: "default").hashCode().toUInt().toString(16)
        return File(base, "liked_$key.json")
    }

    fun load(profile: String?): Saved? {
        val f = file(profile) ?: return null
        val saved = SongListStore.load(f) ?: return null
        return Saved(saved.songs, saved.savedAt).takeIf { it.songs.isNotEmpty() }
            ?.also {
                // The first list read this run is the account's, until one is saved.
                if (lastProfile == null) lastProfile = profile
                if (profile == lastProfile || _likedIds.value.isEmpty()) publish(it.songs)
            }
    }

    private val _likedIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /**
     * Every song in the saved Liked Music — the whole list, not just the first
     * page the Library fetches — so a song liked long ago still shows its
     * heart when it plays.
     */
    val likedIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _likedIds

    private fun publish(songs: List<Song>) {
        _likedIds.value = songs.mapTo(HashSet()) { it.videoId }
    }

    /** The profile Liked Music was last saved for — whose list an export takes. */
    @Volatile
    var lastProfile: String? = null
        private set

    fun save(profile: String?, songs: List<Song>) {
        if (songs.isEmpty()) return
        lastProfile = profile
        publish(songs)
        val f = file(profile) ?: return
        SongListStore.save(f, songs)
    }

    /** Takes [videoId] out of the saved list — an un-like made inside the app. */
    fun remove(profile: String?, videoId: String) {
        val saved = load(profile) ?: return
        val left = saved.songs.filterNot { it.videoId == videoId }
        if (left.size != saved.songs.size) save(profile, left)
    }

    /**
     * The fresh first page in front of the saved songs that come after it, or
     * null when the two don't overlap — more was liked since last time than one
     * page holds, and the whole list has to be walked instead.
     *
     * The anchor is the last song of the fresh page that the saved list also
     * has: everything the saved list holds up to it is replaced by the fresh
     * page (which takes care of songs un-liked near the top), and everything
     * after it is kept as it was.
     */
    fun merge(fresh: List<Song>, saved: List<Song>): List<Song>? {
        if (fresh.isEmpty()) return null
        val savedIndex = HashMap<String, Int>(saved.size * 2)
        saved.forEachIndexed { i, s -> savedIndex.putIfAbsent(s.videoId, i) }
        val anchor = fresh.asReversed().firstNotNullOfOrNull { savedIndex[it.videoId] } ?: return null
        val inFresh = fresh.mapTo(HashSet()) { it.videoId }
        return fresh + saved.drop(anchor + 1).filterNot { it.videoId in inFresh }
    }
}
