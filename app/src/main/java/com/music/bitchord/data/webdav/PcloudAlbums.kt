package com.music.bitchord.data.webdav

import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.OwnLibrary
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * BitChord Audio: the player's album link for any song that names an album.
 *
 * Only pCloud files used to get one: a song from YouTube without an album id
 * (an upload of yours, a video, a download of either) or a file on the phone
 * said "none of these tracks says which album it is" even with the album name
 * right there. Now, in order: the pCloud album page; your album page (pCloud
 * and uploads joined) found by the song's artist and album name in any script;
 * YouTube Music's album of that name and artist.
 */
object PcloudAlbums {

    /** The album page of [song] from your own library, or null when none of yours is on it. */
    fun pageIdOf(song: Song): String? {
        val album = (if (WebDavConfig.isWebDavId(song.videoId)) {
            WebDavRepository.library().firstOrNull { it.videoId == song.videoId }?.albumName?.takeIf { it.isNotBlank() }
        } else {
            null
        }) ?: song.albumName?.takeIf { it.isNotBlank() } ?: ""
        if (album.isEmpty() && WebDavConfig.isWebDavId(song.videoId)) return null
        if (WebDavConfig.isWebDavId(song.videoId)) return LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, album)
        // Your own file of the song first: the album it is on in your library ("Guapa"),
        // not the single YouTube files the song under.
        runCatching {
            com.music.bitchord.data.covers.CoverSources.filesOf(song).firstOrNull { !it.albumName.isNullOrBlank() }
                ?.let { f -> OwnLibrary.albumPageFor(f.artist, f.albumName!!) ?: LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, f.albumName!!).takeIf { WebDavConfig.isWebDavId(f.videoId) } }
        }.getOrNull()?.let { return it }
        if (album.isEmpty()) return null
        return runCatching { OwnLibrary.albumPageFor(song.artist, album) }.getOrNull()
    }

    private val _opens = MutableSharedFlow<Pair<String, Song>>(extraBufferCapacity = 1)

    /** Album pages found after a look-up, for the app to open: id and the song it is for. */
    val opens: SharedFlow<Pair<String, Song>> = _opens

    /** YouTube Music's album named like [song]'s, by its artist; null when none matches. */
    suspend fun youTubeAlbumId(song: Song): String? {
        val album = song.albumName?.takeIf { it.isNotBlank() } ?: return null
        val artist = song.artist.substringBefore(',').trim()
        val results = com.music.bitchord.data.YtMusicRepository.search(
            "$album $artist",
            com.music.bitchord.data.model.SearchFilter.ALBUMS,
        ).getOrNull().orEmpty()
        val albums = results.mapNotNull { (it as? com.music.bitchord.data.model.SearchResult.Browse)?.item }
        val hit = albums.firstOrNull { OwnFileMatcher.sameAlbum(album, it.title) && it.subtitle.contains(artist, ignoreCase = true) }
            ?: albums.firstOrNull { OwnFileMatcher.sameAlbum(album, it.title) && OwnFileMatcher.sameArtist(song.artist, it.subtitle.substringAfterLast(" • ")) }
        TrackLog.d("BitChord", "album of '${song.title}': '$album' on YouTube Music " + (hit?.let { "→ ${it.title}" } ?: "not found (${albums.size} albums)"))
        return hit?.browseId
    }

    fun open(id: String, song: Song) {
        _opens.tryEmit(id to song)
    }

    // ── BitChord Audio: the whole album, wherever the song came from ──

    private val ytAlbums = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NONE = "-"

    private fun albumKey(artist: String, album: String) =
        artist.substringBefore(',').lowercase().filter { it.isLetterOrDigit() } + "|" + album.lowercase().filter { it.isLetterOrDigit() }

    /** YouTube Music's album page already known for [song] (no look-up). */
    fun knownYouTubeAlbum(song: Song): String? {
        // The album named on the song decides (a live album isn't its studio album, whatever
        // id a matched copy carries); the id alone only for a song with no album name.
        val album = song.albumName?.takeIf { it.isNotBlank() }
            ?: return song.albumId?.takeIf { it.startsWith("MPREb") } ?: ytAlbums["song|" + song.videoId]?.takeIf { it != NONE }
        return ytAlbums[albumKey(song.artist, album)]?.takeIf { it != NONE }
    }

    /**
     * The YouTube Music album of [song] — the whole album, with your own copies of its songs
     * shown in it — rather than the page of only the songs of it you have. Found by the album's
     * name; a song with no album name is looked up as a song for the album it is on. Kept.
     */
    suspend fun resolveYouTubeAlbum(song: Song): String? {
        knownYouTubeAlbum(song)?.let { return it }
        val album = song.albumName?.takeIf { it.isNotBlank() }
        if (album != null) {
            val key = albumKey(song.artist, album)
            if (ytAlbums[key] == NONE) return null
            val id = runCatching { youTubeAlbumId(song) }.getOrNull()
            ytAlbums[key] = id ?: NONE
            return id
        }
        val key = "song|" + song.videoId
        if (ytAlbums[key] == NONE) return null
        val title = song.title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim()
        val artist = song.artist.substringBefore(',').trim()
        val hit = runCatching {
            com.music.bitchord.data.YtMusicRepository.search("$title $artist", com.music.bitchord.data.model.SearchFilter.SONGS).getOrNull().orEmpty()
        }.getOrDefault(emptyList()).mapNotNull { (it as? com.music.bitchord.data.model.SearchResult.Track)?.song }
            .firstOrNull { s ->
                s.albumId?.startsWith("MPREb") == true &&
                    s.title.lowercase().filter { it.isLetterOrDigit() } == title.lowercase().filter { it.isLetterOrDigit() } &&
                    OwnFileMatcher.sameArtist(song.artist, s.artist)
            }
        TrackLog.d("BitChord", "album of '${song.title}' (no album name): " + (hit?.let { "${it.albumName} on YouTube Music" } ?: "not found"))
        ytAlbums[key] = hit?.albumId ?: NONE
        return hit?.albumId
    }
}
