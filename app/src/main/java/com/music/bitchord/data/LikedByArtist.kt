package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import java.text.Normalizer
import java.util.Locale

/**
 * BitChord Audio: your liked songs by one artist — the artist page's "Liked
 * songs" row, as Spotify has. Read off Liked Music as last synced and the
 * device's own Liked playlist; an artist is matched by their page id where
 * the song carries one, else by name in the credit ("A, B & C feat. D").
 */
object LikedByArtist {
    const val PREFIX = "likedby:"

    fun browseIdFor(artistName: String, artistBrowseId: String?): String =
        PREFIX + (artistBrowseId.orEmpty()) + "|" + artistName

    fun parse(browseId: String): Pair<String?, String>? {
        if (!browseId.startsWith(PREFIX)) return null
        val rest = browseId.removePrefix(PREFIX)
        val id = rest.substringBefore('|').ifBlank { null }
        val name = rest.substringAfter('|', "")
        return id to name
    }

    private fun norm(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val SPLIT = Regex("""\s*(?:,|&|/|;|、|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)

    fun isBy(song: Song, artistName: String, artistBrowseId: String?): Boolean {
        if (artistBrowseId != null && (song.artistId == artistBrowseId || song.artists.any { it.browseId == artistBrowseId })) return true
        val want = norm(artistName)
        if (want.isEmpty()) return false
        val names = song.artists.map { it.name } + song.artist.split(SPLIT) + song.artist
        return names.any { norm(it) == want }
    }

    /** Newest like first, one row per song. */
    fun songs(artistName: String, artistBrowseId: String?): List<Song> {
        val liked = LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty()
        val device = OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty()
        return (liked + device)
            .filter { isBy(it, artistName, artistBrowseId) }
            .distinctBy { it.videoId }
    }
}
