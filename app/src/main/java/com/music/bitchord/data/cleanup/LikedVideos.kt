package com.music.bitchord.data.cleanup

import android.content.Context
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.edits.MetadataEdits
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: the assistant's phase 2 — videos you like that are not the song.
 *
 * A like on an upload from someone's channel ("Brave Series 20th Anniversary Project
 * Harvest Track…" by a fan) comes in with the uploader as the artist and the video's
 * title as the song. The app already moves likes from official music videos to their
 * song; what is left is what YouTube Music cannot pair. For each, Gemini works out —
 * searching the web (lyrics sites, VGMdb, Discogs, Wikipedia) — what the song really
 * is: title, artist, album, year, track, cover. Then:
 *  - when YouTube Music has the official song (same title, artist and length), the like
 *    moves to it, in the video's place;
 *  - when it has not, the video keeps the like and is shown with the right details
 *    (your corrected title, artist, album… and cover), ready for an album of its own.
 * Where it is not sure, it asks (the dot on your profile photo).
 *
 * New likes are looked at within minutes; older ones in the night hours.
 */
object LikedVideos {

    private const val TAG = "BitChord"
    const val FILE = "liked_videos.json"

    enum class Status { SWAPPED, EDITED, ASKED, OFFICIAL, FAILED }

    data class Result(
        val videoId: String,
        val videoTitle: String,
        val status: Status,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: String? = null,
        val songId: String? = null,
        val reason: String? = null,
        val at: Long = System.currentTimeMillis(),
    )

    private val _results = MutableStateFlow<List<Result>>(emptyList())
    val results: StateFlow<List<Result>> = _results.asStateFlow()

    /** Moves a like from the video to the song, in its place (set by the app's view model). */
    @Volatile var swapLike: (suspend (video: Song, song: Song) -> Song?)? = null

    private var store: File? = null
    private var app: Context? = null
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hints = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var seeded = false

    fun init(context: Context) {
        app = context.applicationContext
        store = File(context.filesDir, FILE)
        load()
    }

    private fun handled(id: String) = _results.value.any { it.videoId == id && it.status != Status.ASKED }

    /**
     * Liked videos still waiting: [fresh] ones (liked since the app last looked) first —
     * those are done at once, whatever the hour; the rest only at night.
     */
    fun todo(): Pair<List<Song>, List<Song>> {
        val versions = com.music.bitchord.data.SongVersions
        val videos = LikedMusicCache.likedSongs.value.filter {
            versions.looksLikeVideo(it) && com.music.bitchord.data.LocalLikes.isYouTubeId(it.videoId) && !handled(it.videoId) &&
                _results.value.none { r -> r.videoId == it.videoId && r.status == Status.ASKED && hints[it.videoId] == null }
        }
        if (!seeded) {
            // The first time: everything already liked is the backlog, not "new".
            if (videos.isNotEmpty() || LikedMusicCache.likedSongs.value.isNotEmpty()) {
                LikedMusicCache.likedSongs.value.forEach { seen += it.videoId }
                seeded = true
                save()
            }
            return emptyList<Song>() to videos
        }
        val fresh = videos.filter { it.videoId !in seen }
        return fresh to videos.filter { it.videoId in seen }
    }

    /** The answer to a question about a video: looked at again with it. */
    fun answer(videoId: String, text: String) {
        hints[videoId] = listOfNotNull(hints[videoId], text).joinToString(". ")
        _results.value = _results.value.filterNot { it.videoId == videoId }
        save()
    }

    /** Back to the video as YouTube has it (the corrected details dropped). */
    fun undo(r: Result) {
        if (r.status == Status.EDITED) {
            LikedMusicCache.likedSongs.value.firstOrNull { it.videoId == r.videoId }?.let { MetadataEdits.clear(listOf(it)) }
        }
        _results.value = _results.value.map { if (it === r) it.copy(status = Status.FAILED, reason = "deshecho") else it }
        save()
    }

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** One video worked out; true when it used a Gemini request. */
    suspend fun handle(video: Song, ask: (key: String, title: String, text: String) -> Unit): Boolean {
        seen += video.videoId
        val prompt = """
A music fan liked this YouTube video as a song:
title: "${video.title}"
channel (YouTube's guess at the artist): "${video.artist}"
length: ${video.durationText ?: "?"}
${hints[video.videoId]?.let { "The owner adds: $it" } ?: ""}
Is the channel the official artist (or their label)? Whatever it is, find out from the web (lyrics sites such as canta-per-me.net or uta-net, VGMdb, Discogs, Wikipedia, Spotify, Apple Music) which song this really is.
Answer ONLY a JSON object:
{"official": true/false (is the channel the artist's own official one),
 "title": "the song's official title (romanized/Latin title when it has one, else the original)",
 "artist": "the real performing artist(s), comma-separated",
 "album": "the release it comes from (single or album), or null",
 "albumArtist": "...", "year": "YYYY or null", "track": number or null,
 "coverUrl": "a direct URL of the release's cover image, or null",
 "confidence": 0.0-1.0,
 "question": "in Spanish, what you would need to know if unsure, else null",
 "reason": "one short sentence in Spanish: what it is and how you know"}
""".trimIndent()
        val o = Gemini.ask(prompt, null, null)
        if (o == null) {
            record(Result(video.videoId, video.title, Status.FAILED, reason = "sin respuesta"))
            return true
        }
        val title = o.optString("title").takeIf { it.isNotBlank() && it != "null" }
        val artist = o.optString("artist").takeIf { it.isNotBlank() && it != "null" }
        val album = o.optString("album").takeIf { it.isNotBlank() && it != "null" }
        val year = o.optString("year").takeIf { Regex("""\d{4}""").matches(it) }
        val confidence = o.optDouble("confidence", 0.5).takeIf { !it.isNaN() } ?: 0.5
        val reason = o.optString("reason").takeIf { it.isNotBlank() && it != "null" }
        val question = o.optString("question").takeIf { it.isNotBlank() && it != "null" }
        if (o.optBoolean("official", false) && confidence >= 0.8 && title != null && OwnFileMatcher.key(title) == OwnFileMatcher.key(video.title)) {
            record(Result(video.videoId, video.title, Status.OFFICIAL, title, artist, album, year, reason = reason))
            return true
        }
        if (title == null || artist == null || confidence < 0.7) {
            record(Result(video.videoId, video.title, Status.ASKED, title, artist, album, year, reason = reason))
            ask("video:" + video.videoId, video.title, question ?: "No sé qué canción es «${video.title}» (${video.artist}). ¿Me dices el artista o de dónde es?")
            return true
        }
        // 1. The official song on YouTube Music: the like moves to it.
        val song = findSong(title, artist, album, seconds(video))
        if (song != null) {
            val moved = runCatching { swapLike?.invoke(video, song) }.getOrNull()
            if (moved != null) {
                TrackLog.d(TAG, "liked videos: '${video.title}' is '${song.title}' by ${song.artist} — like moved to the song")
                record(Result(video.videoId, video.title, Status.SWAPPED, song.title, song.artist, song.albumName ?: album, year, song.videoId, reason))
                return true
            }
        }
        // 2. Not on YouTube Music: the video keeps the like, shown as the song it is.
        MetadataEdits.set(
            listOf(video),
            MetadataEdits.Edit(title = title, artist = artist, album = album, albumArtist = o.optString("albumArtist").takeIf { it.isNotBlank() && it != "null" } ?: artist,
                year = year, track = o.optInt("track", 0).takeIf { it > 0 }?.toString()),
        )
        o.optString("coverUrl").takeIf { it.startsWith("http") }?.let { url ->
            com.music.bitchord.data.covers.CoverOverrides.set(listOf(video.copy(title = title, artist = artist, albumName = album)), url)
        }
        TrackLog.d(TAG, "liked videos: '${video.title}' is '$title' by $artist${album?.let { " ($it)" } ?: ""} — not on YouTube Music; details corrected")
        record(Result(video.videoId, video.title, Status.EDITED, title, artist, album, year, reason = reason))
        return true
    }

    /** The song on YouTube Music: the same title (any script), artist and — when known — length within 6 s. */
    private suspend fun findSong(title: String, artist: String, album: String?, secs: Int?): Song? {
        val queries = listOfNotNull("$title $artist", album?.let { "$title $it" }, title).distinct()
        for (q in queries) {
            val found = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty()
                .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                .filter { !it.isVideo }
            val hit = found.firstOrNull { s ->
                val sameTitle = OwnFileMatcher.allKeys(s.title).any { it in OwnFileMatcher.allKeys(title) }
                val sameArtist = artist.split(",", "&").any { a -> OwnFileMatcher.sameArtist(a.trim(), s.artist) }
                val len = seconds(s)
                sameTitle && sameArtist && (secs == null || len == null || kotlin.math.abs(len - secs) <= 6) &&
                    // Not a karaoke or cover in its place.
                    !Regex("""(?i)karaoke|カラオケ|off vocal|instrumental""").containsMatchIn(s.title + " " + s.albumName.orEmpty())
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun record(r: Result) {
        _results.value = (listOf(r) + _results.value.filterNot { it.videoId == r.videoId }).take(1000)
        save()
    }

    private fun save() {
        val o = JSONObject()
            .put("seeded", seeded)
            .put("seen", JSONArray(seen.toList().takeLast(8000)))
            .put("hints", JSONObject(hints as Map<*, *>))
            .put("results", JSONArray(_results.value.map { r ->
                JSONObject().put("v", r.videoId).put("vt", r.videoTitle).put("s", r.status.name).put("at", r.at).apply {
                    r.title?.let { put("t", it) }; r.artist?.let { put("a", it) }; r.album?.let { put("al", it) }
                    r.year?.let { put("y", it) }; r.songId?.let { put("sid", it) }; r.reason?.let { put("r", it) }
                }
            }))
        runCatching { store?.writeText(o.toString()) }
        app?.let { com.music.bitchord.data.sync.SettingsSync.changed(it) }
    }

    fun load() {
        val text = runCatching { store?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val o = JSONObject(text)
            seeded = o.optBoolean("seeded")
            o.optJSONArray("seen")?.let { a -> (0 until a.length()).forEach { seen += a.getString(it) } }
            o.optJSONObject("hints")?.let { h -> h.keys().forEach { hints[it] = h.getString(it) } }
            _results.value = o.optJSONArray("results")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    fun opt(k: String) = r.optString(k).takeIf { it.isNotBlank() }
                    Result(r.getString("v"), r.optString("vt"), runCatching { Status.valueOf(r.getString("s")) }.getOrDefault(Status.FAILED),
                        opt("t"), opt("a"), opt("al"), opt("y"), opt("sid"), opt("r"), r.optLong("at"))
                }
            }.orEmpty()
        }
    }
}
