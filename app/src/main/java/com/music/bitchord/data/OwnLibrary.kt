package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ArtistLinks
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * BitChord Audio: your own music — your pCloud library and the songs you uploaded to
 * YouTube Music — blended into the catalogue rather than kept in a corner of its own.
 * An artist's page gets your albums and songs of theirs, among YouTube Music's own,
 * each only once (an album you have that YouTube Music has too stays YouTube's card,
 * whichever script either names it in), marked with a small cloud.
 */
object OwnLibrary {

    private const val TAG = "BitChord"
    const val CLOUD = "☁"

    // ---- YouTube Music uploads ------------------------------------------------------

    private const val UPLOADS = "FEmusic_library_privately_owned_tracks"
    private val _uploads = MutableStateFlow<List<Song>>(emptyList())
    val uploads: StateFlow<List<Song>> = _uploads.asStateFlow()
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshedAt = 0L

    fun init(context: Context) {
        file = File(context.filesDir, "uploads.json")
        scope.launch { file?.let { SongListStore.load(it) }?.songs?.let { _uploads.value = it } }
    }

    /** Your uploads, read again from YouTube Music (signed in) at most every 6 hours. */
    fun refreshUploads(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - refreshedAt < 6 * 3_600_000L) return
        refreshedAt = System.currentTimeMillis()
        scope.launch {
            val all = ArrayList<Song>()
            var page = YtMusicRepository.browseSongs(UPLOADS).getOrNull() ?: return@launch
            all += page.songs
            var guard = 0
            while (page.continuation != null && guard++ < 80) {
                page = YtMusicRepository.moreSongs(page.continuation!!).getOrNull() ?: break
                all += page.songs
            }
            val songs = all.distinctBy { it.videoId }
            _uploads.value = songs
            file?.let { runCatching { SongListStore.save(it, songs) } }
            TrackLog.d(TAG, "own library: ${songs.size} songs uploaded to YouTube Music")
        }
    }

    // ---- the blend --------------------------------------------------------------------

    private fun isPcloud(s: Song) = WebDavConfig.isWebDavId(s.videoId)

    /** Your songs by [artist] (any of their names), pCloud and uploads. */
    fun songsBy(artist: String): List<Song> {
        fun by(s: Song) = OwnFileMatcher.sameArtist(artist, s.artist) || ArtistLinks.linked(artist, s.artist) ||
            WebDavRepository.artistFolderOf(s.videoId)?.let { OwnFileMatcher.sameArtist(artist, it) } == true
        return (WebDavRepository.library() + _uploads.value).filter(::by)
    }

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** Whether [a] and [b] are the same song (any script), the same recording. */
    fun sameSong(a: Song, b: Song): Boolean {
        val ka = OwnFileMatcher.allKeys(a.title)
        val kb = OwnFileMatcher.allKeys(b.title)
        if (ka.none { it in kb }) return false
        return OwnFileMatcher.sameRecording(a.title, a.albumName, seconds(a), b.title, b.albumName, seconds(b))
    }

    /** Whether two album names are the same release, in either script. */
    fun sameAlbum(a: String, b: String): Boolean {
        if (OwnFileMatcher.sameAlbum(a, b)) return true
        val ka = OwnFileMatcher.allKeys(a).filter { it.length >= 4 }
        val kb = OwnFileMatcher.allKeys(b).toSet()
        return ka.any { it in kb }
    }

    /** The page your album [name] opens: your pCloud album's, or the uploads' own. */
    private fun pageOf(name: String, songs: List<Song>): String? {
        if (songs.any(::isPcloud)) return LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, songs.first(::isPcloud).albumName ?: name)
        return songs.firstNotNullOfOrNull { it.albumId }
    }

    data class Merged(val songs: List<Song>, val sections: List<HomeShelf>)

    /**
     * [artist]'s page with your music in it: your songs not already among [songs] after
     * them, and your albums not already among its shelves in its albums shelf
     * ([albumsTitle] when it has none).
     */
    fun mergeArtist(artist: String, songs: List<Song>, sections: List<HomeShelf>, albumsTitle: String): Merged {
        val mine = runCatching { songsBy(artist) }.getOrDefault(emptyList())
        if (mine.isEmpty()) return Merged(songs, sections)
        val extraSongs = mine.filter { m -> songs.none { sameSong(it, m) } }
            .distinctBy { OwnFileMatcher.key(it.title) + "|" + (it.albumName ?: "") + "|" + (seconds(it) ?: 0) }
        val shown = sections.flatMap { it.items }.map { it.title }
        val albums = mine.filter { !it.albumName.isNullOrBlank() }.groupBy { it.albumName!!.trim() }
            .filterKeys { name -> shown.none { sameAlbum(it, name) } }
            .mapNotNull { (name, tracks) ->
                val id = pageOf(name, tracks) ?: return@mapNotNull null
                val source = if (tracks.any(::isPcloud)) "pCloud" else "YouTube Music"
                ShelfItem(
                    title = name,
                    subtitle = "$CLOUD $source",
                    thumbnailUrl = tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                    videoId = null,
                    browseId = id,
                )
            }
        val out = sections.toMutableList()
        if (albums.isNotEmpty()) {
            val i = out.indexOfFirst { s -> s.layout == ShelfLayout.CARDS && Regex("(?i)album|álbum|アルバム").containsMatchIn(s.title) }
            if (i >= 0) out[i] = out[i].copy(items = out[i].items + albums)
            else out.add(0, HomeShelf(title = albumsTitle, items = albums))
        }
        if (extraSongs.isNotEmpty() || albums.isNotEmpty()) {
            TrackLog.d(TAG, "own library: '$artist' — ${extraSongs.size} of your songs and ${albums.size} of your albums added to the page")
        }
        return Merged(songs + extraSongs, out)
    }
}
