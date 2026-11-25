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

    private val PARTS = Regex("""\s*(?:,|&|/|;|、|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bcon\b|×)\s*""", RegexOption.IGNORE_CASE)

    @Volatile private var byArtist: Triple<List<Song>, List<Song>, Map<String, List<Song>>>? = null

    /** Every song of yours under each name it credits (and its artist folder), built once per library. */
    private fun artistIndex(): Map<String, List<Song>> {
        val library = WebDavRepository.library()
        val uploads = _uploads.value
        byArtist?.takeIf { it.first === library && it.second === uploads }?.let { return it.third }
        val map = HashMap<String, MutableList<Song>>()
        for (s in library + uploads) {
            val names = s.artist.split(PARTS) + listOfNotNull(WebDavRepository.artistFolderOf(s.videoId))
            names.map { OwnFileMatcher.key(it) }.filter { it.isNotEmpty() }.toSet()
                .forEach { map.getOrPut(it) { ArrayList() } += s }
        }
        byArtist = Triple(library, uploads, map)
        return map
    }

    /** Your songs by [artist] (any of their names), pCloud and uploads. */
    fun songsBy(artist: String): List<Song> {
        val index = artistIndex()
        val direct = artist.split(PARTS).flatMap { index[OwnFileMatcher.key(it)].orEmpty() }
        // Names it is linked to (a romaji credit for a kanji one), and the slower spelling match
        // only over the names the index holds — not every song.
        val linked = index.keys.filter { k -> direct.isEmpty() && (ArtistLinks.linked(artist, k) || OwnFileMatcher.sameArtist(artist, k)) }
            .flatMap { index[it].orEmpty() }
        return (direct + linked).distinctBy { it.videoId }
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
        val ka = keysOf(a).filter { it.length >= 4 }
        val kb = keysOf(b)
        return ka.any { it in kb }
    }

    /**
     * An album name's keys (reading it in every script is slow: a dictionary look-up per
     * word), worked out once per name — the Library tab compares thousands of albums.
     */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun keysOf(name: String): Set<String> {
        keyCache[name]?.let { return it }
        if (keyCache.size > 20_000) keyCache.clear()
        val keys = OwnFileMatcher.allKeys(name)
        if (com.music.bitchord.data.sources.JapaneseReading.ready) keyCache[name] = keys
        return keys
    }

    private const val OWN_ALBUM = "own:album:"
    private const val ALL_ARTISTS = "*"
    private const val VARIOUS = "Varios artistas"

    /**
     * BitChord Audio: whether [name] reads as a compilation's — a long, particular title
     * ("Pokémon TV Anime Theme Song BEST OF BEST OF BEST 1997-2023") or one with a
     * compilation word in it — so songs of several artists under it are one album. A short
     * common title ("Greatest Hits", "Love") stays each artist's own.
     */
    fun isCompilationName(name: String): Boolean {
        val key = OwnFileMatcher.key(name)
        if (key.length >= 20) return true
        return COMPILATION.containsMatchIn(name) && key.length >= 12
    }

    private val COMPILATION = Regex(
        """(?i)\b(best|theme songs?|anthology|collection|compilation|soundtrack|o\.?s\.?t|various|tribute|anniversary|songbook)\b|ベスト|主題歌|コレクション|サウンドトラック|オムニバス|トリビュート""",
    )

    /** All your songs (pCloud and uploads) of compilation [name] — when several artists share it. */
    private val compilationCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<Song>>>()

    fun compilationSongs(name: String): List<Song> {
        if (!isCompilationName(name)) return emptyList()
        val pool = WebDavRepository.library() + _uploads.value
        val k = OwnFileMatcher.key(name)
        compilationCache[k]?.takeIf { it.first == pool.size }?.let { return it.second }
        val songs = pool.filter { s -> OwnFileMatcher.sameAlbum(name, s.albumName) }
        val artists = songs.map { OwnFileMatcher.key(it.artist.split(PARTS).first()) }.distinct()
        val out = if (artists.size >= 2) songs.sortedWith(compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() })) else emptyList()
        if (compilationCache.size > 2_000) compilationCache.clear()
        compilationCache[k] = pool.size to out
        return out
    }
    private const val SEP = "\u0001"

    /**
     * The page your album [name] opens: your pCloud album's when it is only there; else
     * one page joining your pCloud files and your uploads of it — each song once, the
     * best copy of it (see [albumSongs]).
     */
    private fun pageOf(name: String, songs: List<Song>): String? {
        val pcloudOnly = songs.all(::isPcloud)
        if (pcloudOnly) return LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, songs.first().albumName ?: name)
        return OWN_ALBUM + songs.first().artist + SEP + name
    }

    /**
     * BitChord Audio: the page of your album [album] by [artist] — pCloud and uploads — found
     * by name in any script, or null when no song of yours is on it.
     */
    fun albumPageFor(artist: String, album: String): String? {
        // A compilation (one album, many artists) is one page, whoever sings the song.
        if (compilationSongs(album).isNotEmpty()) return OWN_ALBUM + ALL_ARTISTS + SEP + album
        val songs = songsBy(artist).filter { s -> s.albumName?.let { sameAlbum(album, it) } == true }
        if (songs.isEmpty()) return null
        return pageOf(songs.first().albumName ?: album, songs)
    }

    fun isOwnAlbum(browseId: String) = browseId.startsWith(OWN_ALBUM)

    /** The songs of your album page [browseId]: pCloud and uploads joined, the best copy of each. */
    fun albumSongs(browseId: String): List<Song>? {
        if (!isOwnAlbum(browseId)) return null
        val (artist, name) = browseId.removePrefix(OWN_ALBUM).split(SEP, limit = 2).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val all = if (artist == ALL_ARTISTS) compilationSongs(name)
        else songsBy(artist).filter { s -> s.albumName?.let { sameAlbum(name, it) } == true }
        val picked = ArrayList<Song>()
        for (s in all.sortedByDescending { runCatching { OwnFileMatcher.qualityRank(it) }.getOrDefault(0) + if (isPcloud(it)) 1 else 0 }) {
            if (picked.none { sameSong(it, s) }) picked += s
        }
        // In the order the library holds them (track numbers lead file names).
        return all.filter { a -> picked.any { it.videoId == a.videoId } }
    }

    data class Merged(val songs: List<Song>, val sections: List<HomeShelf>)

    /**
     * YouTube Music albums of [artist]'s you like songs from that the page doesn't show
     * (its shelf holds a handful; live albums are often left out) — most liked first.
     */
    private fun likedAlbumsOf(artist: String, sections: List<HomeShelf>): List<ShelfItem> {
        val shownIds = sections.flatMap { it.items }.mapNotNull { it.browseId }.toSet()
        val shownNames = sections.flatMap { it.items }.map { it.title }
        return LikedMusicCache.likedSongs.value.asSequence()
            .filter { !it.isVideo && !it.albumId.isNullOrBlank() && !it.albumName.isNullOrBlank() }
            .filter { s -> s.artist.split(PARTS).firstOrNull()?.let { sameName(artist, it) } == true }
            .groupBy { it.albumId!! }
            .filterKeys { it !in shownIds }
            // Only the very same name: "LIVE vol.#19" is not "LIVE vol.#21".
            .filter { (_, t) -> shownNames.none { OwnFileMatcher.key(it) == OwnFileMatcher.key(t.first().albumName!!) } }
            .entries.sortedByDescending { it.value.size }
            .map { (id, t) -> ShelfItem(t.first().albumName!!, t.first().artist.split(PARTS).first(), t.firstNotNullOfOrNull { it.thumbnailUrl }, null, id) }
    }

    // ── BitChord Audio: the Library tab with your music in it ─────────────────────────

    /**
     * [shelves] (YouTube Music's library) with your albums and artists added to its
     * Albums and Artists shelves — each once (YouTube's card kept when it has it too),
     * marked with the cloud — and new shelves when it has none.
     */
    fun mergeLibrary(shelves: List<HomeShelf>, albumsTitle: String, artistsTitle: String): List<HomeShelf> {
        val pool = (WebDavRepository.library() + _uploads.value).filter { !it.albumName.isNullOrBlank() }
        if (pool.isEmpty()) return shelves
        val shown = shelves.flatMap { it.items }.map { it.title }
        // A compilation's songs are one album, whoever sings each.
        val compilations = pool.groupBy { OwnFileMatcher.key(it.albumName!!.trim()) }
            .filter { (_, t) -> isCompilationName(t.first().albumName!!) && t.map { OwnFileMatcher.key(it.artist.split(PARTS).first()) }.distinct().size >= 2 }
            .keys
        val albums = pool.groupBy {
            val k = OwnFileMatcher.key(it.albumName!!.trim())
            if (k in compilations) "*|$k" else it.albumName!!.trim() + "|" + OwnFileMatcher.key(it.artist.split(PARTS).first())
        }
            .values.mapNotNull { tracks ->
                val name = tracks.first().albumName!!.trim()
                if (shown.any { sameAlbum(it, name) }) return@mapNotNull null
                val compilation = OwnFileMatcher.key(name) in compilations
                val id = (if (compilation) OWN_ALBUM + ALL_ARTISTS + SEP + name else pageOf(name, tracks)) ?: return@mapNotNull null
                val by = if (compilation) VARIOUS else tracks.first().artist.split(PARTS).first()
                ShelfItem(name, "$CLOUD $by", tracks.firstNotNullOfOrNull { it.thumbnailUrl }, null, id)
            }.sortedBy { it.subtitle.lowercase() + it.title.lowercase() }
        val artists = WebDavRepository.library().mapNotNull { s -> WebDavRepository.artistFolderOf(s.videoId) ?: s.artist.split(PARTS).firstOrNull() }
            .map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { OwnFileMatcher.key(it) }
            .filter { a -> shown.none { OwnFileMatcher.sameArtist(a, it) } }
            .sortedBy { it.lowercase() }
            .map { a ->
                ShelfItem(a, "$CLOUD pCloud", WebDavRepository.library().firstOrNull { OwnFileMatcher.sameArtist(a, it.artist) }?.thumbnailUrl, null,
                    LocalGroups.artistPageId(WebDavConfig.BROWSE_ID, a))
            }
        val out = shelves.toMutableList()
        fun add(items: List<ShelfItem>, pattern: Regex, title: String) {
            if (items.isEmpty()) return
            val i = out.indexOfFirst { pattern.containsMatchIn(it.title) }
            if (i >= 0) out[i] = out[i].copy(items = out[i].items + items) else out += HomeShelf(title = title, items = items)
        }
        add(albums, Regex("(?i)album|álbum|アルバム"), albumsTitle)
        add(artists, Regex("(?i)artist|artista|アーティスト"), artistsTitle)
        TrackLog.d(TAG, "own library: ${albums.size} of your albums and ${artists.size} of your artists in the Library")
        return out
    }

    // ── BitChord Audio: search over your own music ────────────────────────────────────

    data class Found(val songs: List<Song>, val albums: List<ShelfItem>)

    /**
     * Your songs and albums answering [query] (pCloud, uploads, downloads), each word of
     * it found in the title, artist or album — in any script (romaji keys too).
     */
    private val hayCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Readies the search text of your whole library ahead, so the first search is quick. */
    fun warmSearch() {
        runCatching { search("\u0000warm") }
    }

    fun search(query: String, downloaded: List<Song> = emptyList()): Found {
        val words = query.split(Regex("""\s+""")).map { OwnFileMatcher.key(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return Found(emptyList(), emptyList())
        // BitChord Audio: your liked songs first, then your files, uploads and downloads.
        val liked = LikedMusicCache.likedSongs.value.filter { !it.isVideo }
        val likedIds = liked.mapTo(HashSet()) { it.videoId }
        val pool = (liked + WebDavRepository.library() + _uploads.value + downloaded).distinctBy { it.videoId }
        // The searchable text of each song, worked out once (readings of kanji are slow).
        fun hay(s: Song): String = hayCache.getOrPut(s.videoId + "|" + s.title + "|" + s.artist) {
            buildString {
                append(OwnFileMatcher.key(s.title)); append(' ')
                append(OwnFileMatcher.key(s.artist)); append(' ')
                append(OwnFileMatcher.key(s.albumName.orEmpty())); append(' ')
                runCatching { OwnFileMatcher.allKeys(s.title).forEach { append(it.removePrefix("~")).append(' ') } }
            }
        }
        val q = OwnFileMatcher.key(query)
        val hits = pool.filter { s -> val h = hay(s); words.all { it in h } }
            // The title itself first ("Lacrimosa (Live)" for "lacrimosa"), liked before the rest.
            .sortedWith(compareBy<Song>({ !OwnFileMatcher.key(it.title).startsWith(q) }, { it.videoId !in likedIds }))
        val songs = hits.distinctBy { OwnFileMatcher.key(it.title) + "|" + OwnFileMatcher.key(it.artist) + "|" + ((seconds(it) ?: 0) / 3) }.take(40)
        val albums = hits.filter { !it.albumName.isNullOrBlank() }
            .filter { s -> words.all { w -> w in OwnFileMatcher.key(s.albumName!!) + " " + OwnFileMatcher.key(s.artist) } }
            .groupBy { it.albumName!!.trim() + "|" + OwnFileMatcher.key(it.artist.split(PARTS).first()) }
            .values.mapNotNull { tracks ->
                val name = tracks.first().albumName!!.trim()
                val id = pageOf(name, tracks) ?: return@mapNotNull null
                ShelfItem(
                    title = name,
                    subtitle = "$CLOUD " + tracks.first().artist + " · " + if (tracks.any(::isPcloud)) "pCloud" else "YouTube Music",
                    thumbnailUrl = tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                    videoId = null,
                    browseId = id,
                )
            }.take(12)
        return Found(songs, albums)
    }

    /**
     * [artist]'s page with your music in it: your songs not already among [songs] after
     * them, and your albums not already among its shelves in its albums shelf
     * ([albumsTitle] when it has none).
     */
    /**
     * Whether [s] is [artist]'s: credited to them (or a name linked to theirs), or credited
     * to nobody and filed in their folder — not another singer's album that happens to
     * sit in their folder (an album they produced for someone else).
     */
    private fun isBy(s: Song, artist: String): Boolean {
        val names = s.artist.split(PARTS).filter { it.isNotBlank() && WebDavRepository.named(it) }
        if (names.isEmpty()) return true
        // The first credit: "Revo & Yuki Kajiura" is Revo's, not hers.
        return sameName(artist, names.first())
    }

    /**
     * The same artist, strictly: the same name (any order, any script, a linked name) —
     * not one name inside another ("YUKI" is not "Yuki Kajiura").
     */
    fun sameName(a: String, b: String): Boolean {
        val ka = OwnFileMatcher.key(a)
        val kb = OwnFileMatcher.key(b)
        if (ka.isEmpty() || kb.isEmpty() || !WebDavRepository.named(a) || !WebDavRepository.named(b)) return false
        if (ka == kb || ArtistLinks.linked(a, kb) || ArtistLinks.linked(b, ka)) return true
        fun latin(s: String) = s.none { it.code > 0x2FF && it.isLetter() }
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.sorted()
        if (latin(a) && latin(b)) return words(a) == words(b)
        // A kanji name against its romaji: its reading, word for word.
        fun read(s: String) = if (latin(s)) words(s).toSet() else com.music.bitchord.data.sources.JapaneseReading.romajiWords(s)
        val ra = read(a) ?: return false
        val rb = read(b) ?: return false
        return ra.isNotEmpty() && ra == rb
    }

    fun mergeArtist(artist: String, songs: List<Song>, sections: List<HomeShelf>, albumsTitle: String): Merged {
        val mine = runCatching { songsBy(artist) }.getOrDefault(emptyList()).filter { isBy(it, artist) }
        val likedAlbums = likedAlbumsOf(artist, sections)
        if (mine.isEmpty() && likedAlbums.isEmpty()) return Merged(songs, sections)
        // Only songs sharing a name are weighed against each other (any script).
        val pageKeys = HashMap<String, MutableList<Song>>()
        songs.forEach { s -> OwnFileMatcher.allKeys(s.title).forEach { pageKeys.getOrPut(it) { ArrayList() } += s } }
        val extraSongs = mine.filter { m ->
            OwnFileMatcher.allKeys(m.title).flatMap { pageKeys[it].orEmpty() }.distinct().none { sameSong(it, m) }
        }
            .distinctBy { OwnFileMatcher.key(it.title) + "|" + (it.albumName ?: "") + "|" + ((seconds(it) ?: 0) / 3) }
            // The page's song list stays a list: your albums carry the rest.
            .take(30)
        val shown = sections.flatMap { it.items }.map { it.title }
        val albums = mine.filter { !it.albumName.isNullOrBlank() && WebDavRepository.named(it.albumName!!) && !WebDavRepository.discFolder(it.albumName!!) }
            .groupBy { it.albumName!!.trim() }
            .filterKeys { name -> shown.none { sameAlbum(it, name) } && likedAlbums.none { sameAlbum(it.title, name) } }
            // Those with a cover and the most songs first; loose files without one last.
            .entries.sortedWith(compareBy<Map.Entry<String, List<Song>>>({ e -> e.value.none { it.thumbnailUrl != null } }, { -it.value.size }))
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
        if (albums.isNotEmpty() || likedAlbums.isNotEmpty()) {
            val i = out.indexOfFirst { s -> s.layout == ShelfLayout.CARDS && Regex("(?i)album|álbum|アルバム").containsMatchIn(s.title) }
            // The albums you like songs from (a live album YouTube Music's shelf leaves out) and
            // your own with covers first; YouTube Music's after; covers-less files last.
            val withCover = albums.filter { it.thumbnailUrl != null }
            val bare = albums.filter { it.thumbnailUrl == null }
            if (i >= 0) out[i] = out[i].copy(items = likedAlbums + withCover + out[i].items + bare)
            else out.add(0, HomeShelf(title = albumsTitle, items = likedAlbums + withCover + bare))
        }
        if (extraSongs.isNotEmpty() || albums.isNotEmpty()) {
            TrackLog.d(TAG, "own library: '$artist' — ${extraSongs.size} of your songs and ${albums.size} of your albums added to the page")
        }
        return Merged(songs + extraSongs, out)
    }
}
