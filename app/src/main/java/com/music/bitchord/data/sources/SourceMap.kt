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
import kotlinx.coroutines.async
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
        /** Which JioSaavn matching found (or missed) [saavn]: a miss is looked at again when it improves. */
        val saavnVersion: Int = 0,
    )

    /** Bumped when JioSaavn matching improves: songs it missed are looked up again. */
    private const val SAAVN_VERSION = 3

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
    private const val MATCHER_VERSION = 5

    /** The entry for [videoId], if it still holds for the library as it is. */
    fun lookup(videoId: String): Entry? {
        val e = entries[videoId] ?: return null
        // Matched by an older matcher: matched again (keeping what JioSaavn said).
        if (e.gen % 100 != MATCHER_VERSION) return null
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

    private val counterparts = ConcurrentHashMap<String, String>()
    private val noCounterpart: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var likedIndex: Pair<List<Song>, Map<String, List<Song>>>? = null

    /**
     * The Liked Music song a file of yours is, so playing the file — from
     * search, a folder, anywhere — shows the song's heart. First the song the
     * map plays this file for; else the liked song with the same title (in
     * any spelling — romaji or Japanese) and artist, within 2 seconds. Only the
     * same recording: a live take, a tribute or covers album, a remix or an
     * acoustic version is a song of its own, with its own heart.
     */
    fun likedSongFor(file: Song): Song? {
        if (file.videoId in noCounterpart) return null
        return likedSongFor(file, strict = true).also { if (it == null) noCounterpart += file.videoId }
    }

    /**
     * For a copy playing in a Liked Music queue: it is there because a liked
     * song is — so the liked song with its title (any spelling) and artist,
     * whatever its length or release.
     */
    fun likedSongInQueue(file: Song): Song? = likedSongFor(file, strict = true) ?: likedSongFor(file, strict = false)

    private fun likedSongFor(file: Song, strict: Boolean): Song? {
        val liked = LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty()
        if (liked.isEmpty()) return null
        counterparts[file.videoId]?.let { id -> liked.firstOrNull { it.videoId == id }?.let { return it } }
        val mapped = entries.entries.firstOrNull { it.value.file == file.videoId }?.key
        val byMap = mapped?.let { id -> liked.firstOrNull { it.videoId == id } }
            ?.takeIf { !OwnFileMatcher.otherRecording(it.title, it.albumName, file) }
        val found = byMap ?: run {
            val index = likedIndex?.takeIf { it.first.size == liked.size && it.first.firstOrNull()?.videoId == liked.firstOrNull()?.videoId }?.second ?: buildIndex(liked)
            val keys = OwnFileMatcher.titleKeys(file.title) + runCatching { OwnFileMatcher.soundKeys(file.title) }.getOrDefault(emptySet())
            val seconds = seconds(file.durationText)
            keys.flatMap { index[it].orEmpty() }.distinct()
                .filter { OwnFileMatcher.sameArtist(file.artist, it.artist) || ArtistLinks.linked(it.artist, file.artist) }
                .filter { !strict || !OwnFileMatcher.otherRecording(it.title, it.albumName, file) }
                .filter { s ->
                    if (!strict) return@filter true
                    val a = seconds ?: return@filter false
                    val b = seconds(s.durationText) ?: return@filter false
                    kotlin.math.abs(a - b) <= 2
                }
                .minByOrNull { s -> kotlin.math.abs((seconds(s.durationText) ?: 0) - (seconds ?: 0)) }
        }
        if (found != null) counterparts[file.videoId] = found.videoId
        return found
    }

    private fun buildIndex(liked: List<Song>): Map<String, List<Song>> {
        val map = HashMap<String, MutableList<Song>>()
        liked.forEach { s ->
            val keys = OwnFileMatcher.titleKeys(s.title) + runCatching { OwnFileMatcher.soundKeys(s.title) }.getOrDefault(emptySet())
            keys.forEach { map.getOrPut(it) { ArrayList() }.add(s) }
        }
        likedIndex = liked to map
        return map
    }

    private fun seconds(text: String?): Int? = text?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

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
        if (!AppSettings.jioSaavnDownloadsOn) return
        val saavnConfig = SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.JIOSAAVN } ?: return
        val saavn = SourceRegistry.instance(saavnConfig.id) ?: return
        val todo = songs.filter { s ->
            val e = entries[s.videoId]
            e?.saavnChecked != true || (e.saavn == null && e.saavnVersion < SAAVN_VERSION)
        }
        if (todo.isEmpty()) return
        TrackLog.d(TAG, "source map: looking up ${todo.size} liked songs on JioSaavn")
        var found = 0
        todo.forEachIndexed { i, s ->
            if (AppSettings.meteredConnection.value != false) {
                save(); upload(); return
            }
            val hit = saavnCopyOf(s, saavn) ?: run {
                if (lastLookupFailed) return@forEachIndexed
                null
            }
            if (hit != null) found++
            val old = entries[s.videoId] ?: Entry(null, 0, false, gen())
            entries[s.videoId] = old.copy(saavn = hit?.videoId, saavnChecked = true, saavnVersion = SAAVN_VERSION)
            if (i % 100 == 99) save()
            kotlinx.coroutines.delay(400)
        }
        save()
        TrackLog.d(TAG, "source map: JioSaavn has $found of ${todo.size} liked songs checked")
        found += albumPass(songs, saavnConfig.id)
        save()
        upload()
    }

    /** Set when the last [saavnCopyOf] couldn't ask JioSaavn at all (offline, an error). */
    @Volatile
    private var lastLookupFailed = false

    /**
     * JioSaavn's copy of [s], strictly matched (title in any of its names,
     * artist, length to 3 seconds), asked by its romaji and then its Japanese
     * names; null when JioSaavn hasn't it.
     */
    private suspend fun saavnCopyOf(s: Song, saavn: MusicSource): Song? {
        lastLookupFailed = false
        val sec = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
        val target = TrackMatcher.Target(title = s.title, artist = s.artist, album = s.albumName, durationSec = sec)
        val query = TrackMatcher.queries(target).firstOrNull() ?: "${s.title} ${s.artist}"
        val rows = runCatching { saavn.search(query, limit = 8) }.getOrNull() ?: run { lastLookupFailed = true; return null }
        val keys = OwnFileMatcher.titleKeys(s.title) + runCatching { OwnFileMatcher.soundKeys(s.title) }.getOrDefault(emptySet())
        fun same(row: Song, japaneseArtists: List<String>): Boolean {
            val rowSec = row.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
                ?.fold(0) { acc, p -> acc * 60 + p }
            val rowKeys = OwnFileMatcher.titleKeys(row.title) + runCatching { OwnFileMatcher.soundKeys(row.title) }.getOrDefault(emptySet())
            return sec != null && rowSec != null && kotlin.math.abs(sec - rowSec) <= 3 &&
                (OwnFileMatcher.sameArtist(s.artist, row.artist) || ArtistLinks.linked(s.artist, row.artist) ||
                    japaneseArtists.any { OwnFileMatcher.sameArtist(it, row.artist) }) &&
                rowKeys.any { it in keys }
        }
        var hit = (TrackMatcher.ranked(rows, target) + rows).firstOrNull { same(it, emptyList()) }
        // Not found by its romaji: asked again by its Japanese names (see JapaneseNames).
        if (hit == null && (JapaneseNames.mayHaveJapanese(s.title) || !JapaneseReading.isJapanese(s.artist))) {
            val names = runCatching { JapaneseNames.of(s) }.getOrDefault(JapaneseNames.Names.NONE)
            if (!names.isEmpty) {
                for (q in names.queries(s.artist).take(4)) {
                    val more = runCatching { saavn.search(q, limit = 12) }.getOrNull().orEmpty()
                    hit = more.firstOrNull { same(it, names.artists) }
                    if (hit != null) {
                        JapaneseNames.learn(s.title, s.artist, hit.title.takeIf(JapaneseReading::isJapanese), hit.artist.takeIf(JapaneseReading::isJapanese))
                        if (!OwnFileMatcher.sameArtist(s.artist, hit.artist)) ArtistLinks.learn(s.artist, hit.artist)
                        TrackLog.d(TAG, "source map: JioSaavn has '${s.title}' as '${hit.title}' ('$q')")
                        break
                    }
                    kotlinx.coroutines.delay(300)
                }
            }
        }
        return hit
    }

    private val ensuring = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()

    /**
     * Makes sure the map knows whether JioSaavn has [song] — asked now when it
     * hasn't been yet — so your file is never chosen over a better JioSaavn
     * copy just because the background lookup hadn't reached the song.
     */
    suspend fun ensureSaavn(song: Song) {
        val e = entries[song.videoId]
        if (e != null && e.saavnChecked && (e.saavn != null || e.saavnVersion >= SAAVN_VERSION)) return
        if (!AppSettings.jioSaavnDownloadsOn && SourceRegistry.activeForPlayback().none { it.kind == SourceKind.JIOSAAVN }) return
        val config = SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.JIOSAAVN } ?: return
        val saavn = SourceRegistry.instance(config.id) ?: return
        val job = ensuring.getOrPut(song.videoId) {
            scope.async {
                try {
                    val hit = saavnCopyOf(song, saavn)
                    if (!lastLookupFailed) {
                        val old = entries[song.videoId] ?: Entry(null, 0, false, gen())
                        entries[song.videoId] = old.copy(saavn = hit?.videoId, saavnChecked = true, saavnVersion = SAAVN_VERSION)
                        save()
                    }
                } finally {
                    ensuring.remove(song.videoId)
                }
            }
        }
        kotlinx.coroutines.withTimeoutOrNull(3_500) { job.await() }
    }

    /** What was playing turned out to be JioSaavn's copy of [videoId] (track key [saavnKey]). */
    fun recordSaavn(videoId: String, saavnKey: String) {
        val old = entries[videoId] ?: Entry(null, 0, false, gen())
        if (old.saavn == saavnKey) return
        entries[videoId] = old.copy(saavn = saavnKey, saavnChecked = true, saavnVersion = SAAVN_VERSION)
        save()
    }

    /**
     * The songs JioSaavn search missed, looked for on their album instead: an
     * album JioSaavn has is there whole, so a song of it is matched among the
     * album's tracks even when its name differs a little (another
     * romanisation, a "(2022 Remaster)", a typo) — as long as the length
     * agrees. Albums are found by name and artist; each is fetched once.
     */
    private suspend fun albumPass(songs: List<Song>, configId: String): Int {
        val missing = songs.filter { s ->
            val e = entries[s.videoId]
            e != null && e.saavnChecked && e.saavn == null && !s.albumName.isNullOrBlank()
        }
        if (missing.isEmpty()) return 0
        var found = 0
        val groups = missing.groupBy { OwnFileMatcher.key(it.albumName.orEmpty()) + "|" + OwnFileMatcher.key(it.artist.substringBefore(',')) }
        for ((_, group) in groups) {
            if (AppSettings.meteredConnection.value != false) break
            val sample = group.first()
            val album = sample.albumName.orEmpty()
            val hits = runCatching {
                com.music.bitchord.data.jiosaavn.JioSaavnService.searchAlbums("$album ${sample.artist.substringBefore(',')}")
            }.getOrDefault(emptyList())
            val hit = hits.firstOrNull { h ->
                OwnFileMatcher.sameAlbum(album, h.title) && (h.artists.isBlank() || OwnFileMatcher.sameArtist(sample.artist, h.artists))
            } ?: hits.firstOrNull { h -> OwnFileMatcher.sameAlbum(album, h.title) }
            if (hit == null) {
                kotlinx.coroutines.delay(300)
                continue
            }
            val tracks = runCatching { com.music.bitchord.data.jiosaavn.JioSaavnService.albumSongs(hit.id) }.getOrDefault(emptyList())
            for (s in group) {
                val sec = seconds(s.durationText)
                val keys = OwnFileMatcher.titleKeys(s.title) + runCatching { OwnFileMatcher.soundKeys(s.title) }.getOrDefault(emptySet())
                val best = tracks.mapNotNull { t ->
                    val tSec = t.moreInfo.duration.toIntOrNull() ?: return@mapNotNull null
                    val drift = if (sec == null) 0 else kotlin.math.abs(sec - tSec)
                    if (drift > 6) return@mapNotNull null
                    val tKeys = OwnFileMatcher.titleKeys(t.title) + runCatching { OwnFileMatcher.soundKeys(t.title) }.getOrDefault(emptySet())
                    val score = when {
                        tKeys.any { it in keys } -> 3
                        OwnFileMatcher.nearSpelling(s.title, t.title) -> 2
                        wordOverlap(s.title, t.title) >= 0.5 -> 1
                        // Same album, same length to the second: the same song by another name.
                        sec != null && drift <= 1 -> 1
                        else -> 0
                    }
                    if (score == 0) null else Triple(t, score, drift)
                }.sortedWith(compareByDescending<Triple<com.music.bitchord.data.jiosaavn.RawSongItem, Int, Int>> { it.second }.thenBy { it.third }).firstOrNull()
                if (best != null) {
                    val raw = best.first
                    entries[s.videoId] = entries[s.videoId]!!.copy(saavn = SourceRegistry.trackKey(configId, raw.id), saavnVersion = SAAVN_VERSION)
                    found++
                }
            }
            kotlinx.coroutines.delay(400)
        }
        TrackLog.d(TAG, "source map: JioSaavn albums found $found more liked songs")
        return found
    }

    private fun wordOverlap(a: String, b: String): Double {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
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
                saavnVersion = e.optInt("sv"),
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
                if (e.saavnVersion > 0) put("sv", e.saavnVersion)
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
