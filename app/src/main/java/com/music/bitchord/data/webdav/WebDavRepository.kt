package com.music.bitchord.data.webdav

import android.content.Context
import com.music.bitchord.data.SongListStore
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.SidecarLyrics
import com.music.bitchord.data.model.Song
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

    fun init(context: Context) {
        savedFile = File(context.filesDir, "remote_library/webdav.json")
        RemoteTagStore.init(context)
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
        val listing = WebDavClient.listLibrary(
            baseUrl = url,
            username = username,
            password = password,
        ).getOrNull() ?: return@withContext emptyList()
        lastListing = listing
        val songs = songsOf(listing)
        registerLyrics(listing)
        inMemory = songs
        savedFile?.let { SongListStore.save(it, songs) }
        readMissingTags(listing.audio, username, password)
        songs
    }

    @Volatile
    private var lastListing: WebDavClient.Listing? = null

    private fun songsOf(listing: WebDavClient.Listing): List<Song> {
        val artByDir = listing.images.groupBy { dirKey(it.url) }
        return listing.audio.map { entry ->
            val base = entry.toSong(artworkFor(entry.url, artByDir[dirKey(entry.url)].orEmpty()))
            RemoteTagStore.get(entry.url, stamp(entry))?.let { base.withTags(it) } ?: base
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

    fun WebDavClient.Entry.toSong(artworkUrl: String? = null): Song {
        val album = parentFolderName(url)
        return WebDavConfig.songFor(url, albumName = album).copy(
            // Prefer the server's display name over the URL-decoded guess when
            // it carries one.
            title = displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                ?.let { splitTitle(it).second } ?: WebDavConfig.songFor(url).title,
            artist = splitTitle(displayName.substringBeforeLast('.')).first
                ?: WebDavConfig.songFor(url, album).artist,
            thumbnailUrl = artworkUrl,
        )
    }

    /** [this] with whatever its file's tags say, keeping the guesses where they say nothing. */
    private fun Song.withTags(t: RemoteTags.Tags): Song = copy(
        title = t.title ?: title,
        artist = t.artist ?: t.albumArtist ?: artist,
        albumName = t.album ?: albumName,
        trackNumber = t.trackNumber ?: trackNumber,
        durationText = t.durationMs?.let(::formatDuration) ?: durationText,
        sourceQuality = t.qualityLabel ?: sourceQuality,
        sourceHiRes = t.hiRes,
    ).also { song ->
        if (t.altTitles.isNotEmpty() || t.altArtists.isNotEmpty()) {
            com.music.bitchord.data.lyrics.LyricAliases.fromTags(song.videoId, t.altTitles, t.altArtists)
        }
    }

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
                            RemoteTagStore.put(entry.url, stamp(entry), RemoteTags.parse(head) ?: RemoteTags.Tags())
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
    fun artworkFor(fileUrl: String, siblings: List<WebDavClient.Entry>): String? =
        com.music.bitchord.data.remote.RemoteArtwork.pick(siblings.map { it.url })

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
