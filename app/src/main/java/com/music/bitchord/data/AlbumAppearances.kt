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

    data class Album(val name: String, val browseId: String?, val source: String, val cover: String? = null)

    /**
     * BitChord Audio: the page of [song]'s own album — YouTube Music's, else the album of
     * yours (pCloud) it is on, by name in either script.
     */
    fun pageOf(song: Song): String? = runCatching {
        com.music.bitchord.data.webdav.PcloudAlbums.pageIdOf(song)
            ?: song.albumId
            ?: song.albumName?.takeIf { it.isNotBlank() }?.let { name ->
                WebDavRepository.library().firstOrNull { f -> f.albumName?.let { OwnLibrary.sameAlbum(name, it) } == true }?.albumName
                    ?.let { LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, it) }
            }
    }.getOrNull() ?: song.albumId

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun keyOf(title: String, artist: String): String? {
        val t = norm(title)
        if (t.isEmpty()) return null
        val a = norm(artist.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).firstOrNull().orEmpty())
        return "$t|$a"
    }

    private class Index(val liked: List<Song>, val library: List<Song>, val byKey: Map<String, List<Song>>, val likedByKey: Map<String, List<Song>>)

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** BitChord Audio: the same recording — not just the same title (see OwnFileMatcher.sameRecording). */
    private fun same(a: Song, b: Song): Boolean = a.videoId == b.videoId ||
        com.music.bitchord.data.sources.OwnFileMatcher.sameRecording(a.title, a.albumName, seconds(a), b.title, b.albumName, seconds(b))

    @Volatile
    private var index: Index? = null

    /** BitChord Audio: bumped when a fresh [index] is ready — what the stars redraw on. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    @Volatile private var building = false

    private fun index(): Index {
        val liked = LikedMusicCache.likedSongs.value
        val library = runCatching { WebDavRepository.library() }.getOrDefault(emptyList())
        index?.takeIf { it.liked === liked && it.library === library }?.let { return it }
        // BitChord Audio: on the main thread (every star on screen asks) a rebuild over
        // ~10,000 songs on each change to Liked Music froze taps. The last index answers
        // while a new one is built aside.
        val last = index
        if (last != null && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (!building) {
                building = true
                Thread {
                    try { build(LikedMusicCache.likedSongs.value, runCatching { WebDavRepository.library() }.getOrDefault(emptyList())) } finally { building = false }
                    version.value++
                }.start()
            }
            return last
        }
        return build(liked, library)
    }

    private fun build(liked: List<Song>, library: List<Song>): Index {
        val byKey = HashMap<String, MutableList<Song>>()
        (liked + library).forEach { s -> keyOf(s.title, s.artist)?.let { byKey.getOrPut(it) { ArrayList() } += s } }
        val likedByKey = HashMap<String, MutableList<Song>>()
        liked.forEach { s -> keyOf(s.title, s.artist)?.let { likedByKey.getOrPut(it) { ArrayList() } += s } }
        return Index(liked, library, byKey, likedByKey).also { index = it }
    }

    /**
     * The liked copy of [song]: itself when liked, else the same song liked
     * off another album (or another upload) — null when it isn't liked anywhere.
     */
    fun likedCopy(song: Song): Song? {
        if (LikeLookup.isLiked(song.videoId)) return song
        val key = keyOf(song.title, song.artist) ?: return null
        return index().likedByKey[key].orEmpty()
            .firstOrNull { same(song, it) && LikeLookup.status(it.videoId) != com.music.bitchord.data.model.LikeStatus.INDIFFERENT }
    }

    private fun albumOf(s: Song, source: String): Album? {
        val name = s.albumName?.takeIf { it.isNotBlank() } ?: return null
        val id = when {
            WebDavConfig.isWebDavId(s.videoId) -> LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, name)
            else -> s.albumId
        }
        return Album(name, id, source, s.thumbnailUrl)
    }

    /** The albums [song] is known to be on, from what is on the phone already. */
    fun known(song: Song): List<Album> {
        val key = keyOf(song.title, song.artist)
        val copies = listOf(song) + (key?.let { index().byKey[it] }.orEmpty()).filter { same(song, it) }
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
            // BitChord Audio: the same recording, not just the same title (a tribute, a live
            // album, a re-recording of other length are other recordings).
            if (keyOf(s.title, s.artist) != key || !same(song, s)) return@mapNotNull null
            albumOf(s, "YouTube Music")
        }
        (known + found).distinctBy { norm(it.name) }
    }
}
