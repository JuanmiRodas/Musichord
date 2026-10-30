package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
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

    fun load(profile: String?): Saved? = runCatching {
        val f = file(profile)?.takeIf { it.isFile } ?: return null
        val root = JSONObject(f.readText())
        val array = root.getJSONArray("songs")
        val songs = (0 until array.length()).map { songFrom(array.getJSONObject(it)) }
        Saved(songs, root.optLong("savedAt", 0L)).takeIf { songs.isNotEmpty() }
    }.getOrNull()

    fun save(profile: String?, songs: List<Song>) {
        if (songs.isEmpty()) return
        runCatching {
            val f = file(profile) ?: return
            val array = JSONArray()
            songs.forEach { array.put(json(it)) }
            val root = JSONObject().put("savedAt", System.currentTimeMillis()).put("songs", array)
            // Written aside and renamed, so a process killed mid-write keeps the old list.
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
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

    private fun json(s: Song): JSONObject = JSONObject().apply {
        put("videoId", s.videoId)
        put("title", s.title)
        put("artist", s.artist)
        s.thumbnailUrl?.let { put("thumbnailUrl", it) }
        s.durationText?.let { put("durationText", it) }
        s.artistId?.let { put("artistId", it) }
        s.albumId?.let { put("albumId", it) }
        s.albumName?.let { put("albumName", it) }
        if (s.isVideo) put("isVideo", true)
        s.setVideoId?.let { put("setVideoId", it) }
        s.isExplicit?.let { put("isExplicit", it) }
        if (s.artists.isNotEmpty()) {
            put(
                "artists",
                JSONArray().apply {
                    s.artists.forEach { a ->
                        put(JSONObject().put("name", a.name).apply { a.browseId?.let { put("browseId", it) } })
                    }
                },
            )
        }
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun songFrom(o: JSONObject): Song {
        val artists = o.optJSONArray("artists")?.let { a ->
            (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                ArtistRef(name = x.getString("name"), browseId = x.str("browseId"))
            }
        }.orEmpty()
        val isVideo = o.optBoolean("isVideo", false)
        return Song(
            videoId = o.getString("videoId"),
            title = o.getString("title"),
            artist = o.getString("artist"),
            thumbnailUrl = o.str("thumbnailUrl"),
            durationText = o.str("durationText"),
            artistId = o.str("artistId"),
            artists = artists,
            albumId = o.str("albumId"),
            albumName = o.str("albumName"),
            isVideo = isVideo,
            setVideoId = o.str("setVideoId"),
            isExplicit = if (o.has("isExplicit")) o.getBoolean("isExplicit") else null,
        )
    }
}
