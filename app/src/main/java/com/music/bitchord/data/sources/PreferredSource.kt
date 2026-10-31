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
        return if (v == YOUTUBE) YouTube else File(v)
    }

    fun get(song: Song): Choice? = get(song.title, song.artist)

    fun prefersYouTube(title: String, artist: String): Boolean = get(title, artist) == YouTube

    /** Plays [file] for [song] from now on. */
    fun chooseFile(song: Song, file: Song) {
        val edit = prefs?.edit() ?: return
        listOfNotNull(keyOf(song.title, song.artist), keyOf(file.title, file.artist)).distinct()
            .forEach { edit.putString(it, file.videoId) }
        edit.apply()
        if (!WebDavConfig.isWebDavId(song.videoId) && song.localUri == null) OriginalVersion.unpin(song.videoId)
    }

    /** Plays YouTube's own copy of [song] from now on. */
    fun chooseYouTube(song: Song, youtubeId: String?) {
        val edit = prefs?.edit() ?: return
        keyOf(song.title, song.artist)?.let { edit.putString(it, YOUTUBE) }
        edit.apply()
        youtubeId?.let(OriginalVersion::pin)
    }

    /** Back to the rule in Settings. */
    fun clear(song: Song) {
        val edit = prefs?.edit() ?: return
        keyOf(song.title, song.artist)?.let(edit::remove)
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
        val matched = OwnFileMatcher.matches(song.title, song.artist, target.durationSec)
        return (self + matched + ranked + named).distinctBy { it.videoId }
    }

    private fun secondsOf(text: String): Int? {
        val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, p -> acc * 60 + p }.takeIf { it > 0 }
    }
}
