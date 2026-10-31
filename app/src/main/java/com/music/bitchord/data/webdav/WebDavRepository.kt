package com.music.bitchord.data.webdav

import android.content.Context
import com.music.bitchord.data.SongListStore
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.SidecarLyrics
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.remote.RemoteTagStore
import com.music.bitchord.data.remote.RemoteTags
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

/**
 * WebDAV library as [Song] rows, shaped like the on-device library so the
 * same Songs / Artists / Albums view can draw it.
 *
 * Upstream re-listed the server on every visit and named tracks after their
 * files. BitChord Audio (this is how a pCloud folder is read — see
 * [PcloudPreset]) adds three things:
 *
 *  - **Tags.** Each file's title, artist, album, track number, length and
 *    quality come from its own tags ([RemoteTags]), read once in the
 *    background and kept in [RemoteTagStore]. Until a file has been read, the
 *    file-name guess stands in for it, as before.
 *  - **A saved copy.** The last listing is kept on the device ([saved]) and
 *    drawn at once; the server is listed again behind it.
 *  - **Lyrics files.** A `.lrc` beside a track with the same name is that
 *    track's lyrics, used before any lyrics service ([SidecarLyrics]).
 *
 * The album of a track without tags is its parent folder, which groups a
 * "Music/Artist/Album" layout back into releases.
 */
object WebDavRepository {

    private const val TAG = "BitChord"

    /** How many files are read for their tags at once. */
    private const val TAG_READ_PARALLELISM = 4
    private const val MPEG_PROBE_BYTES = 16 * 1024
    private const val STARTUP_REFRESH_DELAY_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tagJob: Job? = null

    @Volatile
    private var savedFile: File? = null

    private val _changes = MutableStateFlow(0)

    /** Bumped when background tag reads have changed what [getSongs] returns. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _tagProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Files read / files to read while tags are being fetched, else null. */
    val tagProgress: StateFlow<Pair<Int, Int>?> = _tagProgress.asStateFlow()

    @Volatile
    private var lyricsIndexFile: File? = null

    fun init(context: Context) {
        savedFile = File(context.filesDir, "remote_library/webdav.json")
        lyricsIndexFile = File(context.filesDir, "remote_library/lyrics_index.txt")
        RemoteTagStore.init(context)
        LibraryFolders.init(context)
        com.music.bitchord.data.sources.PreferredSource.init(context)
        // The lyrics folders' file list as last seen, so lyrics are found
        // before the library has been opened this session.
        runCatching { lyricsIndexFile?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } }
            .getOrNull()?.let(SidecarLyrics::index)
        // And refreshed in the background, lyrics-only folders included.
        scope.launch { refreshLyricsOnly() }
        // BitChord Audio: the library itself too, once per start — so new
        // files, and the tags (bitrates) of files not read yet, are there for
        // playback without the library page having to be opened.
        scope.launch {
            kotlinx.coroutines.delay(STARTUP_REFRESH_DELAY_MS)
            if (isConfigured()) runCatching { getSongs() }
        }
    }

    /** A lyrics folder's name: "LyricsX", "Lyrics", "Letras", "lrc"… */
    private val LYRICS_FOLDER = Regex("""^(lyrics?\s*x?|letras?|lrcx?|karaoke lyrics)$""", RegexOption.IGNORE_CASE)

    /**
     * Lyrics folders found rather than added: one named like [LYRICS_FOLDER]
     * beside a music folder — `Music/LyricsX` next to `Music/Música`, as
     * LyricsX on a Mac keeps it. Folders already in the list are left to it.
     */
    private suspend fun autoLyricsFolders(username: String, password: String): List<String> {
        val configured = LibraryFolders.folders.value.map { WebDavConfig.normalizeUrl(it.url).trimEnd('/') }.toSet()
        val musicFolders = LibraryFolders.folders.value.filter { it.enabled && it.hasMusic }.map { it.url }
            .ifEmpty { listOf(AppSettings.webdavUrl.value) }
        return musicFolders
            .map { WebDavConfig.normalizeUrl(it).trimEnd('/') }
            .map { it.substringBeforeLast('/') + "/" }
            .distinct()
            .flatMap { parent ->
                WebDavClient.listFolders(parent, username, password).getOrNull().orEmpty()
                    .filter { LYRICS_FOLDER.matches(it.displayName.ifBlank { decodedLast(it.url) }.trim()) }
                    .map { WebDavConfig.normalizeUrl(it.url).trimEnd('/') }
            }
            .filter { it !in configured }
            .distinct()
            .also { if (it.isNotEmpty()) TrackLog.d(TAG, "WebDAV: lyrics folders found beside the music: $it") }
    }

    /** Lists just the folders that hold only lyrics — cheap, one flat folder is the usual case. */
    private suspend fun refreshLyricsOnly() {
        if (!isConfigured()) return
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val folders = LibraryFolders.folders.value.filter { it.enabled && it.kind == LibraryFolders.Kind.LYRICS }.map { it.url } +
            autoLyricsFolders(u, p)
        if (folders.isEmpty()) return
        val found = folders.mapNotNull { WebDavClient.listLibrary(it, u, p).getOrNull() }
        if (found.isEmpty()) return
        // Merged with what the music folders last contributed, kept in the file.
        val known = runCatching { lyricsIndexFile?.takeIf { it.isFile }?.readLines() }.getOrNull().orEmpty()
        publishLyrics((found.flatMap { it.lyrics.map { e -> e.url } } + known).distinct())
        found.flatMap { it.tables }.forEach { readAliasTable(it.url, u, p) }
    }

    private fun publishLyrics(urls: List<String>) {
        SidecarLyrics.index(urls)
        runCatching {
            lyricsIndexFile?.let { f ->
                f.parentFile?.mkdirs()
                f.writeText(urls.joinToString("\n"))
            }
        }
    }

    /** A romaji list (`japanese_title`/`romanized_title` columns) becomes lyric aliases. */
    private suspend fun readAliasTable(url: String, username: String, password: String) {
        val text = WebDavClient.readText(url, username, password, 4 * 1024 * 1024).getOrNull() ?: return
        com.music.bitchord.data.lyrics.LyricAliases.importCsv(text)
    }

    fun isConfigured(): Boolean =
        WebDavConfig.isConfigured(AppSettings.webdavUrl.value)

    /** The library as last listed, for drawing before the server answers. */
    fun saved(): List<Song>? {
        if (!isConfigured()) return null
        val f = savedFile ?: return null
        return SongListStore.load(f)?.songs?.takeIf { it.isNotEmpty() }?.also { inMemory = it }
    }

    @Volatile
    private var inMemory: List<Song>? = null

    /**
     * The library as last known, without asking the server — what
     * [RemoteLibrarySource] matches YouTube songs against. Read from the saved
     * copy the first time.
     */
    fun library(): List<Song> {
        if (!isConfigured()) return emptyList()
        return inMemory ?: saved().orEmpty()
    }

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        val url = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(url)) return@withContext emptyList()
        val username = AppSettings.webdavUsername.value
        val password = AppSettings.webdavPassword.value
        // Every enabled folder (several, with BitChord Audio's folder list);
        // a setup from before folders existed is its one URL.
        val folders = LibraryFolders.folders.value.filter { it.enabled }
            .ifEmpty { listOf(LibraryFolders.Folder("legacy", "", url, true, LibraryFolders.Kind.BOTH)) }
        val listed = folders.mapNotNull { folder ->
            WebDavClient.listLibrary(folder.url, username, password, maxFiles = 50_000).getOrNull()?.let { folder to it }
        }
        if (listed.isEmpty()) return@withContext emptyList()
        val autoLyrics = autoLyricsFolders(username, password)
            .mapNotNull { WebDavClient.listLibrary(it, username, password).getOrNull() }
        roots = listed.filter { it.first.hasMusic }.map { WebDavConfig.normalizeUrl(it.first.url).trimEnd('/') + "/" }
        val music = listed.filter { it.first.hasMusic }.map { it.second }
        val listing = WebDavClient.Listing(
            audio = music.flatMap { it.audio }.distinctBy { it.url },
            images = music.flatMap { it.images }.distinctBy { it.url },
            // Lyrics from every folder, music and lyrics alike, first folder first.
            lyrics = (listed.flatMap { it.second.lyrics } + autoLyrics.flatMap { it.lyrics }).distinctBy { it.url },
            tables = (listed.flatMap { it.second.tables } + autoLyrics.flatMap { it.tables }).distinctBy { it.url },
        )
        lastListing = listing
        listing.tables.forEach { readAliasTable(it.url, username, password) }
        val songs = songsOf(listing)
        registerLyrics(listing)
        publishLyrics(listing.lyrics.map { it.url })
        inMemory = songs
        savedFile?.let { SongListStore.save(it, songs) }
        readMissingTags(listing.audio, username, password)
        songs
    }

    @Volatile
    private var lastListing: WebDavClient.Listing? = null

    /** The music folders' roots, so a file's artist folder can be told apart. */
    @Volatile
    private var roots: List<String> = emptyList()

    private fun songsOf(listing: WebDavClient.Listing): List<Song> {
        val artByDir = listing.images.groupBy { dirKey(it.url) }
        return listing.audio.map { entry ->
            val tags = RemoteTagStore.get(entry.url, stamp(entry))
            val named = entry.toSong()
            val song = tags?.let { named.withTags(it) } ?: named
            // Covers: "cover - <Album>.jpg" beside the file or one folder up
            // (your layout files them in the artist folder), else any cover.
            val nearby = artByDir[dirKey(entry.url)].orEmpty() + artByDir[parentDirKey(entry.url)].orEmpty()
            song.copy(thumbnailUrl = artworkFor(entry.url, nearby, song.albumName))
        }
    }

    /**
     * The last listing again with the tags read since, without asking the
     * server — what an open page swaps to as [changes] ticks. Saved as the
     * new copy too, so the next visit opens with the tags already in.
     */
    suspend fun relabeled(): List<Song>? = withContext(Dispatchers.IO) {
        val listing = lastListing ?: return@withContext null
        songsOf(listing).also { songs ->
            inMemory = songs
            savedFile?.let { SongListStore.save(it, songs) }
        }
    }

    suspend fun testConnection(
        url: String,
        username: String,
        password: String,
    ): Result<Unit> = WebDavClient.testConnection(url, username, password)

    /**
     * A file's best guess from its name and place, for before (or without)
     * its tags. Reads the layouts a collection grows into:
     * `Artist/Album/01 Title.mp3`, `Artist - Title.mp3`, `Title - Artist.mp3`,
     * and `01. Title - Artist - Album - Year.mp3` — including the romanized
     * and original title side by side (`Natsu No Ringo - 夏の林檎 - Kalafina - …`),
     * whose spare names go to [fileNameAliases]. The artist folder (the first
     * folder under a music folder) decides which part is the artist.
     */
    fun WebDavClient.Entry.toSong(artworkUrl: String? = null): Song {
        val fileName = displayName.ifBlank { decodedLast(url) }
        val parsed = FileName.parse(fileName, artistFolder(url), parentFolderName(url))
        fileNameAliases[WebDavConfig.idFor(url)] = parsed.alternatives
        return WebDavConfig.songFor(url, albumName = parsed.album ?: parentFolderName(url)).copy(
            title = parsed.title,
            artist = parsed.artist ?: WebDavConfig.songFor(url).artist,
            trackNumber = parsed.track,
            thumbnailUrl = artworkUrl,
        )
    }

    /** Spare names a file's own name gave for its song (a romaji title beside the original). */
    private val fileNameAliases = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** [this] with whatever its file's tags say, keeping the guesses where they say nothing. */
    private fun Song.withTags(t: RemoteTags.Tags): Song {
        val guessed = this
        val song = copy(
            title = t.title ?: title,
            artist = t.artist ?: t.albumArtist ?: artist,
            albumName = t.album ?: albumName,
            trackNumber = t.trackNumber ?: trackNumber,
            durationText = t.durationMs?.let(::formatDuration) ?: durationText,
            sourceQuality = t.qualityLabel ?: sourceQuality,
            sourceHiRes = t.hiRes,
        )
        // Every other name this song goes by: the tags' own alternatives, the
        // file name's spare titles, and the file name's title when the tags
        // chose another (the romaji one, usually, against a Japanese tag).
        val alts = (t.altTitles + fileNameAliases[videoId].orEmpty() + listOf(guessed.title))
            .filter { FileName.key(it) != FileName.key(song.title) && FileName.key(it).isNotEmpty() }
            .distinctBy(FileName::key)
        if (alts.isNotEmpty() || t.altArtists.isNotEmpty()) {
            com.music.bitchord.data.lyrics.LyricAliases.fromTags(song.videoId, alts, t.altArtists)
        }
        return song
    }

    /** The first folder under the music folder this file is in: the artist, in an artist-first layout. */
    private fun artistFolder(fileUrl: String): String? {
        val root = roots.firstOrNull { fileUrl.startsWith(it) } ?: return null
        val rel = fileUrl.removePrefix(root).split('/').filter { it.isNotEmpty() }
        if (rel.size < 2) return null
        return runCatching { URLDecoder.decode(rel[0], "UTF-8") }.getOrNull()
    }

    private fun decodedLast(url: String): String =
        runCatching { URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8") }
            .getOrDefault(url.substringAfterLast('/'))

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun stamp(entry: WebDavClient.Entry) = RemoteTagStore.Stamp(entry.size, entry.modified)

    /**
     * Reads the tags of every file not read before, a few at a time, then
     * bumps [changes] so an open page lists again with them. One run at a
     * time; a new listing replaces a run still going.
     */
    private fun readMissingTags(audio: List<WebDavClient.Entry>, username: String, password: String) {
        val missing = audio.filter { RemoteTagStore.get(it.url, stamp(it)) == null }
        if (missing.isEmpty()) return
        tagJob?.cancel()
        tagJob = scope.launch {
            TrackLog.d(TAG, "WebDAV: reading tags of ${missing.size} files")
            val gate = Semaphore(TAG_READ_PARALLELISM)
            var done = 0
            _tagProgress.value = 0 to missing.size
            val jobs = missing.map { entry ->
                launch {
                    gate.withPermit {
                        val head = WebDavClient.readHead(entry.url, username, password, RemoteTags.HEAD_BYTES).getOrNull()
                        // Failures are not stored: a network blip shouldn't
                        // mark a file as having no tags for good.
                        if (head != null) {
                            var tags = RemoteTags.parse(head, entry.size) ?: RemoteTags.Tags()
                            // A cover larger than the first read pushes the MP3
                            // frames past it: a few KB from where they start.
                            tags.audioOffset?.let { offset ->
                                WebDavClient.readRange(entry.url, username, password, offset, MPEG_PROBE_BYTES).getOrNull()
                                    ?.let { tags = RemoteTags.completeMpeg(tags, it, entry.size) }
                            }
                            // 0 = "looked, couldn't tell": not read again on every listing.
                            val kbps = tags.kbps ?: if (!tags.lossless && tags.codec != null) 0 else null
                            RemoteTagStore.put(entry.url, stamp(entry), tags.copy(audioOffset = null, kbps = kbps))
                        }
                        synchronized(this@WebDavRepository) {
                            done++
                            _tagProgress.value = done to missing.size
                            // Saved and shown in batches, so a long first read
                            // isn't lost to a closed app and fills in as it goes.
                            if (done % 100 == 0) {
                                RemoteTagStore.flush()
                                _changes.value += 1
                            }
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            RemoteTagStore.flush()
            _tagProgress.value = null
            // The library rows carry the new tags (bitrates) without a page
            // having to be opened for it.
            runCatching { relabeled() }
            _changes.value += 1
        }
    }

    /** Pairs each `.lrc` with the track of the same name in the same folder. */
    private fun registerLyrics(listing: WebDavClient.Listing) {
        if (listing.lyrics.isEmpty()) return
        val byBase = listing.lyrics.associateBy { baseKey(it.url) }
        listing.audio.forEach { entry ->
            byBase[baseKey(entry.url)]?.let { lrc ->
                SidecarLyrics.registerRemote(WebDavConfig.idFor(entry.url), lrc.url)
            }
        }
    }

    /** The URL without its extension, lower-cased: `…/Album/01 Song` for `01 Song.flac` and `01 Song.lrc`. */
    private fun baseKey(url: String): String =
        url.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)

    /**
     * The cover for a track: a picture filed beside it. Embedded pictures
     * inside the audio itself are not read — that would be a ranged fetch per
     * track at list time. See [RemoteArtwork][com.music.bitchord.data.remote.RemoteArtwork].
     */
    fun artworkFor(fileUrl: String, siblings: List<WebDavClient.Entry>, album: String? = null): String? {
        if (siblings.isEmpty()) return null
        // "cover - <Album>.jpg" (or any picture named after the album) first.
        val albumKey = album?.let(FileName::key)?.takeIf { it.isNotEmpty() }
        if (albumKey != null) {
            siblings.firstOrNull { pic ->
                val stem = FileName.key(decodedLast(pic.url).substringBeforeLast('.').removePrefix("cover - ").removePrefix("Cover - "))
                stem == albumKey
            }?.let { return it.url }
        }
        // Otherwise only pictures in the file's own folder: one up holds every
        // album's cover, and any of them would be a guess.
        val own = siblings.filter { dirKey(it.url) == dirKey(fileUrl) }
        return com.music.bitchord.data.remote.RemoteArtwork.pick(own.map { it.url })
    }

    private fun parentDirKey(fileUrl: String): String = dirKey(dirKey(fileUrl).let { "https://$it" })

    private fun dirKey(fileUrl: String): String = runCatching {
        val uri = java.net.URI(fileUrl)
        "${uri.host.orEmpty()}${uri.path.orEmpty().trimEnd('/').substringBeforeLast('/', "")}"
    }.getOrDefault(fileUrl.substringBeforeLast('/'))

    private fun splitTitle(base: String): Pair<String?, String?> {
        return if (" - " in base) {
            val parts = base.split(" - ", limit = 2)
            parts[0].trim().takeIf { it.isNotBlank() } to parts[1].trim().takeIf { it.isNotBlank() }
        } else {
            null to base.trim().takeIf { it.isNotBlank() }
        }
    }

    internal fun parentFolderName(fileUrl: String): String? = runCatching {
        val path = java.net.URI(fileUrl).path.orEmpty().trimEnd('/')
        val parent = path.substringBeforeLast('/', "").substringAfterLast('/').trim()
        URLDecoder.decode(parent, "UTF-8").takeIf { it.isNotBlank() }
    }.getOrNull()
}
