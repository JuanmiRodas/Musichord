package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BitChord Audio: the albums a song is on — its studio album, a single, a
 * compilation, a deluxe edition — and whether it is liked on any of them.
 *
 * "The same song" is the same title and artist exactly (a live version or a
 * remix is a song of its own). Known straight away from your Liked Music and
 * your pCloud files; [search] also asks YouTube Music, for the song's menu.
 */
object AlbumAppearances {

    data class Album(val name: String, val browseId: String?, val source: String)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun keyOf(title: String, artist: String): String? {
        val t = norm(title)
        if (t.isEmpty()) return null
        val a = norm(artist.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).firstOrNull().orEmpty())
        return "$t|$a"
    }

    private class Index(val liked: List<Song>, val library: List<Song>, val byKey: Map<String, List<Song>>, val likedByKey: Map<String, Song>)

    @Volatile
    private var index: Index? = null

    private fun index(): Index {
        val liked = LikedMusicCache.likedSongs.value
        val library = runCatching { WebDavRepository.library() }.getOrDefault(emptyList())
        index?.takeIf { it.liked === liked && it.library === library }?.let { return it }
        val byKey = HashMap<String, MutableList<Song>>()
        (liked + library).forEach { s -> keyOf(s.title, s.artist)?.let { byKey.getOrPut(it) { ArrayList() } += s } }
        val likedByKey = HashMap<String, Song>()
        liked.forEach { s -> keyOf(s.title, s.artist)?.let { likedByKey.putIfAbsent(it, s) } }
        return Index(liked, library, byKey, likedByKey).also { index = it }
    }

    /**
     * The liked copy of [song]: itself when liked, else the same song liked
     * off another album (or another upload) — null when it isn't liked anywhere.
     */
    fun likedCopy(song: Song): Song? {
        if (LikeLookup.isLiked(song.videoId)) return song
        val key = keyOf(song.title, song.artist) ?: return null
        return index().likedByKey[key]?.takeIf { LikeLookup.status(it.videoId) != com.music.bitchord.data.model.LikeStatus.INDIFFERENT }
    }

    private fun albumOf(s: Song, source: String): Album? {
        val name = s.albumName?.takeIf { it.isNotBlank() } ?: return null
        val id = when {
            WebDavConfig.isWebDavId(s.videoId) -> LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, name)
            else -> s.albumId
        }
        return Album(name, id, source)
    }

    /** The albums [song] is known to be on, from what is on the phone already. */
    fun known(song: Song): List<Album> {
        val key = keyOf(song.title, song.artist)
        val copies = listOf(song) + (key?.let { index().byKey[it] }.orEmpty())
        return copies.mapNotNull { s -> albumOf(s, if (WebDavConfig.isWebDavId(s.videoId)) "pCloud" else "YouTube Music") }
            .distinctBy { norm(it.name) }
    }

    /** On more than one album (as far as is known here). */
    fun onSeveral(song: Song): Boolean = known(song).size > 1

    /** [known], and what YouTube Music's song search says on top. */
    suspend fun search(song: Song): List<Album> = withContext(Dispatchers.IO) {
        val known = known(song)
        val key = keyOf(song.title, song.artist) ?: return@withContext known
        val found = withTimeoutOrNull(8_000) {
            YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS).getOrNull()
        }.orEmpty().mapNotNull { r ->
            val s = when (r) {
                is SearchResult.Track -> r.song
                is SearchResult.TopTrack -> r.song
                else -> null
            } ?: return@mapNotNull null
            if (keyOf(s.title, s.artist) != key) return@mapNotNull null
            albumOf(s, "YouTube Music")
        }
        (known + found).distinctBy { norm(it.name) }
    }
}
