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
        placements = context.getSharedPreferences("bitchord_liked_placements", Context.MODE_PRIVATE)
        unliked = context.getSharedPreferences("bitchord_unliked", Context.MODE_PRIVATE)
    }

    // ── BitChord Audio: un-likes that stay un-liked ──────────────────────
    // An un-like is remembered (for [UNLIKE_DAYS]); a song un-liked is kept out of
    // Liked Music wherever the list comes from — YouTube's pages, the saved list,
    // the song-version swap — and if YouTube still lists it, it is un-liked there again.

    private var unliked: android.content.SharedPreferences? = null
    // Only while YouTube catches up: after that, what YouTube lists is the truth (a song
    // liked again elsewhere must come back, not be hidden for months).
    private const val UNLIKE_MS = 30 * 60_000L

    /** Told when YouTube still lists songs un-liked here, so they are un-liked there again. */
    @Volatile
    var onStillLiked: ((List<Song>) -> Unit)? = null

    fun markUnliked(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        unliked?.edit()?.apply { ids.forEach { putLong(it, now) } }?.apply()
    }

    fun clearUnliked(id: String) {
        if (unliked?.contains(id) == true) unliked?.edit()?.remove(id)?.apply()
    }

    fun isUnliked(id: String): Boolean {
        val at = unliked?.getLong(id, 0L) ?: 0L
        return at > 0L && System.currentTimeMillis() - at < UNLIKE_MS
    }

    /** [songs] without the ones un-liked here; those still listed are reported. */
    fun alive(songs: List<Song>): List<Song> {
        if (unliked?.all.isNullOrEmpty()) return songs
        val (gone, kept) = songs.partition { isUnliked(it.videoId) }
        if (gone.isNotEmpty()) onStillLiked?.invoke(gone)
        return kept
    }

    private var placements: android.content.SharedPreferences? = null

    /**
     * BitChord Audio: a song liked in place of its music video — YouTube files
     * the new like at the top; here it stays where the video was, just before
     * [before] (the like that followed the video).
     */
    fun place(songId: String, before: String?) {
        placements?.edit()?.putString(songId, before ?: "")?.apply()
    }

    /** [songs] with each placed song moved back to where its video was. */
    fun placed(songs: List<Song>): List<Song> {
        val all = placements?.all?.takeIf { it.isNotEmpty() } ?: return songs
        val wanted = all.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        if (wanted.isEmpty()) return songs
        val moving = songs.filter { it.videoId in wanted }
        if (moving.isEmpty()) return songs
        val out = songs.filterNot { it.videoId in wanted }.toMutableList()
        // Placed one after another, so a song placed before another placed song lands right.
        var pending = moving
        repeat(3) {
            val left = mutableListOf<Song>()
            for (song in pending) {
                val at = out.indexOfFirst { it.videoId == wanted[song.videoId] }
                if (at >= 0) out.add(at, song) else left += song
            }
            pending = left
            if (pending.isEmpty()) return out
        }
        // Whatever it followed is gone too: it keeps YouTube's place, at the top.
        return pending + out
    }

    private fun file(profile: String?): File? {
        val base = dir ?: return null
        val key = (profile ?: "default").hashCode().toUInt().toString(16)
        return File(base, "liked_$key.json")
    }

    fun load(profile: String?): Saved? {
        val f = file(profile) ?: return null
        val saved = SongListStore.load(f) ?: return null
        return Saved(saved.songs.distinctBy { it.videoId }.filterNot { isUnliked(it.videoId) }, saved.savedAt).takeIf { it.songs.isNotEmpty() }
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
        _likedSongs.value = songs
    }

    private val _likedSongs = kotlinx.coroutines.flow.MutableStateFlow<List<Song>>(emptyList())

    /** BitChord Audio: the saved Liked Music itself (albums included), for [AlbumAppearances]. */
    val likedSongs: kotlinx.coroutines.flow.StateFlow<List<Song>> = _likedSongs

    /** The profile Liked Music was last saved for — whose list an export takes. */
    @Volatile
    var lastProfile: String? = null
        private set

    fun save(profile: String?, songs0: List<Song>) {
        // BitChord Audio: one row per liked song, whatever the pages said.
        // BitChord Audio: YouTube's list only — songs liked just here (a file of yours YouTube
        // hasn't) are drawn in on the page, never saved into it: saved, the next merge with
        // YouTube's page dropped them, and they came and went.
        val localOnly = runCatching {
            LocalLikes.liked.value.keys.filterTo(HashSet()) { !LocalLikes.isYouTubeId(it) }
        }.getOrDefault(emptySet<String>())
        val songs = placed(alive(songs0.distinctBy { it.videoId }.filterNot { it.videoId in localOnly }))
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
    fun merge(fresh0: List<Song>, saved: List<Song>): List<Song>? {
        val fresh = alive(fresh0)
        if (fresh.isEmpty()) return null
        val savedIndex = HashMap<String, Int>(saved.size * 2)
        saved.forEachIndexed { i, s -> savedIndex.putIfAbsent(s.videoId, i) }
        // A song re-liked since sits on the fresh page but deep in the saved list; anchoring on
        // it would drop everything above it. Only an anchor near the top is believed.
        val reach = fresh.size + 50
        val anchor = fresh.asReversed().firstNotNullOfOrNull { savedIndex[it.videoId]?.takeIf { i -> i < reach } } ?: return null
        val inFresh = fresh.mapTo(HashSet()) { it.videoId }
        return placed((fresh + saved.drop(anchor + 1).filterNot { it.videoId in inFresh }).distinctBy { it.videoId })
    }
}
