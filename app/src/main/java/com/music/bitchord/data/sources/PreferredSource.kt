package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.playback.OriginalVersion

/**
 * BitChord Audio: the copy of a song the listener picked from the player —
 * one of their files, or YouTube — remembered for good.
 *
 * Keyed by the song's name (title and first artist, letters and digits only),
 * not by an id, so the choice holds wherever the song is queued from: Liked
 * Music, a playlist, radio, the merged library, the file itself. The chosen
 * file's own name is filed too, so the choice still applies when its tags
 * name it differently from YouTube (a romaji title, another script).
 */
object PreferredSource {

    sealed interface Choice
    data object YouTube : Choice
    data class File(val id: String) : Choice

    /** A copy picked from another source's search — JioSaavn, a YouTube upload, an addon. */
    data class Chosen(val song: Song) : Choice

    private const val PREFS = "bitchord_source_choices"
    private const val YOUTUBE = "youtube"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、|×|\bx\b)\s*""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")

    /** "europesskies|alexanderrybak" — the same key from the YouTube row and the file. */
    fun keyOf(title: String, artist: String): String? {
        val t = FileName.key(title.replace(BRACKETS, " ")).ifEmpty { FileName.key(title) }
        val a = FileName.key(artist.split(ARTIST_SPLIT).firstOrNull().orEmpty())
        if (t.isEmpty()) return null
        return "$t|$a"
    }

    fun get(title: String, artist: String): Choice? {
        val key = keyOf(title, artist) ?: return null
        val v = prefs?.getString(key, null) ?: return null
        return when {
            v == YOUTUBE -> YouTube
            v.startsWith(SONG) -> songFrom(v.removePrefix(SONG))?.let(::Chosen)
            else -> File(v)
        }
    }

    fun get(song: Song): Choice? = get(song.title, song.artist)

    fun prefersYouTube(title: String, artist: String): Boolean = get(title, artist) == YouTube

    /**
     * BitChord Audio: the keys a choice for [songs] is filed under — each one's
     * own, and every other key holding the same choice as any of them. A pick
     * is filed under two names (the song's and the copy's, which often differ:
     * a romaji title, "(Remastered)"), so changing or clearing it under one
     * name only used to leave the old choice standing under the other — and
     * picking YouTube for a song playing from JioSaavn flipped straight back.
     */
    private fun familyKeys(vararg songs: Song): Set<String> {
        val p = prefs ?: return emptySet()
        val own = songs.mapNotNull { keyOf(it.title, it.artist) }.toSet()
        // "youtube" is the same value for every song set to YouTube: never a family.
        val values = own.mapNotNull { p.getString(it, null) }.filter { it != YOUTUBE }.toSet()
        if (values.isEmpty()) return own
        val siblings = p.all.entries.filter { (_, v) -> v is String && v in values }.map { it.key }
        return own + siblings
    }

    /** Plays [file] for [song] (and the [also] copies of it) from now on. */
    fun chooseFile(song: Song, file: Song, vararg also: Song) {
        val keys = familyKeys(song, file, *also)
        val edit = prefs?.edit() ?: return
        keys.forEach { edit.putString(it, file.videoId) }
        edit.apply()
        if (!WebDavConfig.isWebDavId(song.videoId) && song.localUri == null) OriginalVersion.unpin(song.videoId)
    }

    private const val SONG = "song:"

    /** Plays [picked] — from any source — for [song] (and the [also] copies) from now on. */
    fun chooseSong(song: Song, picked: Song, vararg also: Song) {
        if (WebDavConfig.isWebDavId(picked.videoId)) return chooseFile(song, picked, *also)
        val keys = familyKeys(song, picked, *also)
        val edit = prefs?.edit() ?: return
        val value = SONG + songJson(picked)
        keys.forEach { edit.putString(it, value) }
        edit.apply()
        if (!WebDavConfig.isWebDavId(song.videoId) && song.localUri == null) OriginalVersion.unpin(song.videoId)
    }

    private fun songJson(s: Song): String = org.json.JSONObject().apply {
        put("id", s.videoId)
        put("title", s.title)
        put("artist", s.artist)
        s.albumName?.let { put("album", it) }
        s.durationText?.let { put("duration", it) }
        s.thumbnailUrl?.let { put("thumb", it) }
        s.sourceQuality?.let { put("quality", it) }
    }.toString()

    private fun songFrom(json: String): Song? = runCatching {
        val o = org.json.JSONObject(json)
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        Song(
            videoId = o.getString("id"),
            title = o.getString("title"),
            artist = o.optString("artist"),
            albumName = str("album"),
            durationText = str("duration"),
            thumbnailUrl = str("thumb"),
            sourceQuality = str("quality"),
        )
    }.getOrNull()

    /** Plays YouTube's own copy of [song] (and of the [also] copies) from now on. */
    fun chooseYouTube(song: Song, youtubeId: String?, vararg also: Song) {
        val keys = familyKeys(song, *also)
        val edit = prefs?.edit() ?: return
        keys.forEach { edit.putString(it, YOUTUBE) }
        edit.apply()
        youtubeId?.let(OriginalVersion::pin)
    }

    /** Back to the rule in Settings — under every name the choice was filed. */
    fun clear(song: Song, vararg also: Song) {
        val keys = familyKeys(song, *also)
        val edit = prefs?.edit() ?: return
        keys.forEach(edit::remove)
        edit.apply()
        if (!WebDavConfig.isWebDavId(song.videoId) && song.localUri == null) OriginalVersion.unpin(song.videoId)
    }

    /**
     * Your files that are [song], best match first: the same recording by
     * title (in any of its names), artist and length.
     */
    fun filesFor(song: Song): List<Song> {
        val library = WebDavRepository.library()
        if (library.isEmpty()) return emptyList()
        val target = TrackMatcher.Target(
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            durationSec = song.durationText?.let(::secondsOf),
        )
        val self = library.filter { it.videoId == song.videoId }
        val ranked = TrackMatcher.ranked(library, target)
        // A looser net as well: same title key under the same first artist,
        // for files whose tags the matcher's stricter rules turn away.
        val key = keyOf(song.title, song.artist)
        val named = if (key == null) emptyList() else library.filter { keyOf(it.title, it.artist) == key }
        val matched = OwnFileMatcher.matches(song.title, song.artist, target.durationSec, song.albumName)
        return (self + matched + ranked + named).distinctBy { it.videoId }
    }

    private fun secondsOf(text: String): Int? {
        val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, p -> acc * 60 + p }.takeIf { it > 0 }
    }
}
