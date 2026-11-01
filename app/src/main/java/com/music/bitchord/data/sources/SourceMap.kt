package com.music.bitchord.data.sources

import android.content.Context
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: where each Liked Music song plays from, worked out once and
 * kept — on the phone and in pCloud (`.bitchord-audio/source_map.json`), so a
 * new install, a reinstall or another phone starts with it.
 *
 * For each YouTube song: your best matching file (by [OwnFileMatcher]) with
 * its quality, or that there is none. Playback reads the entry instead of
 * matching again; only songs not in the map yet — new likes — are matched,
 * and songs with no file are looked at again only when the library itself
 * has changed (files added or removed). An entry whose file is gone is
 * dropped and matched again.
 */
object SourceMap {

    private const val TAG = "BitChord"
    private const val FILE = "source_map.json"
    private const val REMOTE_DIR = ".bitchord-audio"

    /**
     * [file] null: no file of yours is this song (as of library [gen]).
     * [saavn]: JioSaavn's copy (a track key, its 320 kbps tier), once
     * [saavnChecked]; null after a check means JioSaavn hasn't it.
     */
    data class Entry(
        val file: String?,
        val kbps: Int,
        val lossless: Boolean,
        val gen: Int,
        val saavn: String? = null,
        val saavnChecked: Boolean = false,
    )

    /** JioSaavn's tier for every row it returns: 320 kbps AAC. */
    const val SAAVN_KBPS = 320

    /** The raw entry, JioSaavn part included, without the library checks. */
    fun entry(videoId: String): Entry? = entries[videoId]

    private val entries = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var local: File? = null

    fun init(context: Context) {
        local = File(context.filesDir, FILE)
        scope.launch {
            val loaded = runCatching { local?.takeIf { it.isFile }?.readText() }.getOrNull()
            if (loaded != null) read(loaded)
            // Nothing here yet: the copy in pCloud, from another install.
            if (entries.isEmpty()) remoteUrl()?.let { url ->
                WebDavClient.readText(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 16 * 1024 * 1024)
                    .getOrNull()?.let {
                        read(it)
                        TrackLog.d(TAG, "source map: ${entries.size} songs from pCloud")
                    }
            }
        }
    }

    /** The library's generation: changes when files are added or removed. */
    private fun gen(): Int = WebDavRepository.library().size * 100 + MATCHER_VERSION

    /** Bumped when matching improves: songs with no file then get another look. */
    private const val MATCHER_VERSION = 3

    /** The entry for [videoId], if it still holds for the library as it is. */
    fun lookup(videoId: String): Entry? {
        val e = entries[videoId] ?: return null
        if (e.file == null) return e.takeIf { it.gen == gen() }
        // A file that has gone is matched again.
        return e.takeIf { e.file in libraryIds() }
            ?: run { entries.remove(videoId); null }
    }

    @Volatile
    private var ids: Pair<List<Song>, Set<String>>? = null

    private fun libraryIds(): Set<String> {
        val library = WebDavRepository.library()
        ids?.let { (of, set) -> if (of === library) return set }
        return library.mapTo(HashSet()) { it.videoId }.also { ids = library to it }
    }

    fun fileSong(id: String): Song? = WebDavRepository.library().firstOrNull { it.videoId == id }

    fun record(videoId: String, file: Song?) {
        if (!WebDavConfig.isConfigured(AppSettings.webdavUrl.value)) return
        val rank = file?.let { OwnFileMatcher.qualityRank(it) } ?: 0
        val old = entries[videoId]
        entries[videoId] = Entry(
            file?.videoId, if (rank >= 9_000) 0 else rank, rank >= 9_000, gen(),
            saavn = old?.saavn, saavnChecked = old?.saavnChecked == true,
        )
    }

    /**
     * Matches the Liked Music songs not in the map yet, in the background,
     * then saves the map here and in pCloud. Called when the library has
     * loaded; quick when there is nothing new.
     */
    fun fill() {
        if (job?.isActive == true) return
        job = scope.launch {
            val songs = LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty()
            if (songs.isEmpty() || WebDavRepository.library().isEmpty()) return@launch
            val todo = songs.filter { lookup(it.videoId) == null }
            if (todo.isEmpty()) {
                fillSaavn(songs)
                return@launch
            }
            // Japanese titles are matched by reading; wait for the dictionary.
            JapaneseReading.tokenizerNow()
            TrackLog.d(TAG, "source map: matching ${todo.size} of ${songs.size} liked songs")
            todo.forEachIndexed { i, s ->
                val sec = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
                    ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
                val best = runCatching { OwnFileMatcher.matches(s.title, s.artist, sec, s.albumName).firstOrNull() }.getOrNull()
                record(s.videoId, best)
                if (i % 250 == 249) save()
            }
            save()
            upload()
            TrackLog.d(TAG, "source map: ${entries.count { it.value.file != null }} of ${entries.size} songs have a file of yours")
            fillSaavn(songs)
        }
    }

    /**
     * JioSaavn's copy of each liked song, looked up once: on Wi-Fi, a song
     * every half second or so, saved as it goes and synced to pCloud at the
     * end. Strict, because JioSaavn files different recordings under one
     * name: the same title (any of its names), the artist, and the length to
     * 3 seconds.
     */
    private suspend fun fillSaavn(songs: List<Song>) {
        if (!AppSettings.jioSaavnForDownloads.value) return
        val saavn = SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.JIOSAAVN }
            ?.let { SourceRegistry.instance(it.id) } ?: return
        val todo = songs.filter { entries[it.videoId]?.saavnChecked != true }
        if (todo.isEmpty()) return
        TrackLog.d(TAG, "source map: looking up ${todo.size} liked songs on JioSaavn")
        var found = 0
        todo.forEachIndexed { i, s ->
            if (AppSettings.meteredConnection.value != false) {
                save(); upload(); return
            }
            val sec = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
                ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
            val target = TrackMatcher.Target(title = s.title, artist = s.artist, album = s.albumName, durationSec = sec)
            val query = TrackMatcher.queries(target).firstOrNull() ?: "${s.title} ${s.artist}"
            val rows = runCatching { saavn.search(query, limit = 8) }.getOrNull() ?: return@forEachIndexed
            val keys = OwnFileMatcher.titleKeys(s.title)
            val hit = (TrackMatcher.ranked(rows, target) + rows).firstOrNull { row ->
                val rowSec = row.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
                    ?.fold(0) { acc, p -> acc * 60 + p }
                sec != null && rowSec != null && kotlin.math.abs(sec - rowSec) <= 3 &&
                    OwnFileMatcher.sameArtist(s.artist, row.artist) &&
                    OwnFileMatcher.titleKeys(row.title).any { it in keys }
            }
            if (hit != null) found++
            val old = entries[s.videoId] ?: Entry(null, 0, false, gen())
            entries[s.videoId] = old.copy(saavn = hit?.videoId, saavnChecked = true)
            if (i % 100 == 99) save()
            kotlinx.coroutines.delay(400)
        }
        save()
        upload()
        TrackLog.d(TAG, "source map: JioSaavn has $found of ${todo.size} liked songs checked")
    }

    private fun read(text: String) = runCatching {
        val o = JSONObject(text)
        o.keys().forEach { k ->
            val e = o.getJSONObject(k)
            entries[k] = Entry(
                file = if (e.has("f") && !e.isNull("f")) e.getString("f") else null,
                kbps = e.optInt("k"),
                lossless = e.optBoolean("l"),
                gen = e.optInt("g"),
                saavn = if (e.has("s") && !e.isNull("s")) e.getString("s") else null,
                saavnChecked = e.optBoolean("sc"),
            )
        }
    }

    private fun text(): String {
        val o = JSONObject()
        entries.forEach { (k, e) ->
            o.put(k, JSONObject().apply {
                e.file?.let { put("f", it) }
                if (e.kbps > 0) put("k", e.kbps)
                if (e.lossless) put("l", true)
                put("g", e.gen)
                e.saavn?.let { put("s", it) }
                if (e.saavnChecked) put("sc", true)
            })
        }
        return o.toString()
    }

    private fun save() {
        val f = local ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun remoteDir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/$REMOTE_DIR/"
    }

    private fun remoteUrl(): String? = remoteDir()?.let { it + FILE }

    private suspend fun upload() {
        val dir = remoteDir() ?: return
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val bytes = text().toByteArray(Charsets.UTF_8)
        WebDavClient.ensureCollection(dir, u, p)
        WebDavClient.putFile(dir + FILE, bytes.inputStream(), bytes.size.toLong(), "application/json", u, p, overwrite = true)
            .onFailure { TrackLog.w(TAG, "source map: upload failed: ${it.message}") }
    }
}
