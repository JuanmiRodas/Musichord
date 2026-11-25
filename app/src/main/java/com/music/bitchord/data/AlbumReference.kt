package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: an album's whole track list from the reference catalogues — Apple Music
 * (iTunes, Japan's store first), MusicBrainz, VGMdb (anime, games, old Japanese releases) and
 * Discogs (with your token) — for an album of yours YouTube Music doesn't have. Your songs
 * are put in their place on it, discs included, and the ones you are missing show greyed in
 * theirs: a tap looks for one to play.
 *
 * Only asked when such an album is opened, and kept for good — on the phone and in your
 * pCloud (`.bitchord-audio/album-references.json`, merged both ways), so any phone of yours
 * has it. Tracks you lack are found to play (and that kept too) when the album plays. A
 * list is only taken when most of your songs of the album are on it — never another album's.
 */
object AlbumReference {
    private const val TAG = "BitChord"
    private const val UA = "BitChordAudio/1.0 (https://github.com/JuanmiRodas/Musichord)"
    private const val TTL_MS = 30L * 24 * 3600 * 1000
    const val MISSING = "missing:"
    /** Lists kept before this were picked too loosely (a concert video's for its CD): asked again. */
    private const val VERSION = 3

    data class Track(val disc: Int, val number: Int, val title: String, val seconds: Int?, val artist: String? = null, val videoId: String? = null)
    data class Reference(val source: String, val tracks: List<Track>, val cover: String? = null)

    @Volatile private var file: File? = null
    private val cache: JSONObject by lazy {
        runCatching { file?.takeIf { it.isFile }?.readText()?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    }
    private val http by lazy { Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }
    private val brainz = Mutex()
    private val running = HashMap<String, kotlinx.coroutines.Deferred<Reference?>>()
    private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]""")
    private val TALK = Regex("""(?i)^\s*(mc|talk|トーク|ＭＣ)\b|^\s*mc\s*[-#]?\s*\d*\s*$""")
    @Volatile private var lastBrainz = 0L

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, "album_references.json")
        syncScope.launch {
            delay(25_000)
            runCatching { pullFromCloud() }.onFailure { TrackLog.w(TAG, "album references: pCloud not read: ${it.message}") }
        }
    }

    private fun save() {
        synchronized(cache) { runCatching { file?.writeText(cache.toString()) } }
        pushSoon()
    }

    // ── Kept in pCloud too ──

    private val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var pushJob: kotlinx.coroutines.Job? = null

    private fun cloudDir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!com.music.bitchord.data.webdav.WebDavConfig.isConfigured(base)) return null
        return com.music.bitchord.data.webdav.WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/"
    }

    /** pCloud's copy merged in: what this phone lacks (or only knows as a miss) taken from it. */
    private suspend fun pullFromCloud() {
        val dir = cloudDir() ?: return
        val text = com.music.bitchord.data.webdav.WebDavClient.readText(dir + CLOUD_FILE, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 32 * 1024 * 1024).getOrNull()
        val remote = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        var taken = 0
        var ours = 0
        synchronized(cache) {
            remote.keys().forEach { k ->
                val r = remote.optJSONObject(k) ?: return@forEach
                val l = cache.optJSONObject(k)
                val rHas = (r.optJSONArray("t")?.length() ?: 0) > 0 || r.has("p")
                if (l == null || ((l.optJSONArray("t")?.length() ?: 0) == 0 && rHas)) { cache.put(k, r); taken++ }
                else l.optJSONObject("p")?.let { lp -> r.optJSONObject("p")?.let { rp -> rp.keys().forEach { pk -> if (!lp.has(pk)) lp.put(pk, rp.get(pk)) } } }
            }
            cache.keys().forEach { k -> if (!remote.has(k)) ours++ }
            runCatching { file?.writeText(cache.toString()) }
        }
        if (taken > 0) TrackLog.d(TAG, "album references: $taken taken from pCloud")
        if (ours > 0) pushSoon()
    }

    private fun pushSoon() {
        if (cloudDir() == null) return
        pushJob?.cancel()
        pushJob = syncScope.launch {
            delay(8_000)
            val dir = cloudDir() ?: return@launch
            val user = AppSettings.webdavUsername.value
            val pass = AppSettings.webdavPassword.value
            runCatching {
                com.music.bitchord.data.webdav.WebDavClient.ensureCollection(dir, user, pass)
                val bytes = synchronized(cache) { cache.toString() }.toByteArray(Charsets.UTF_8)
                com.music.bitchord.data.webdav.WebDavClient.putFile(dir + CLOUD_FILE, bytes.inputStream(), bytes.size.toLong(), "application/json", user, pass, overwrite = true)
            }.onFailure { TrackLog.w(TAG, "album references: not sent to pCloud: ${it.message}") }
        }
    }

    private const val CLOUD_FILE = "album-references.json"

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun key(s: String) = OwnFileMatcher.key(s)

    fun isMissing(song: Song) = song.videoId.startsWith(MISSING)

    // ── Your album, completed ──

    /**
     * [songs] (your songs of album [album]) laid out on the album's full track list, with
     * greyed rows for the ones you lack; [songs] unchanged when no reference fits (or none
     * answers within [waitMs] — the look-up carries on and is kept for next time).
     */
    suspend fun complete(album: String, songs: List<Song>, waitMs: Long = 6_000): List<Song> {
        if (songs.isEmpty() || album.isBlank()) return songs
        val byArtist = songs.groupingBy { it.artist.split(',', '&', '、').first().trim() }.eachCount()
        // A compilation (several artists) is looked up by its name alone.
        val artist = if (byArtist.size > 1 && byArtist.values.max() * 2 < songs.size) null else byArtist.maxByOrNull { it.value }?.key
        // The look-up carries on past the wait (one per album at a time) and is kept: a slow
        // catalogue fills the page the next time it opens instead of never.
        val job = synchronized(running) {
            running[key(album)]?.takeIf { it.isActive } ?: syncScope.async { find(album, artist, songs) }.also { j ->
                running[key(album)] = j
                j.invokeOnCompletion { synchronized(running) { running.remove(key(album), j) } }
            }
        }
        val ref = withTimeoutOrNull(waitMs) { job.await() } ?: cached(album)?.takeIf { fits(it, songs) } ?: return songs
        return layOut(ref, album, artist, songs)
    }

    private fun layOut(ref: Reference, album: String, artist: String?, songs: List<Song>): List<Song> {
        val left = songs.toMutableList()
        val cover = ref.cover ?: songs.firstNotNullOfOrNull { it.thumbnailUrl }
        val out = ArrayList<Song>()
        // The same song kept under another album name (downloaded on the phone from YouTube
        // Music, a single or a best-of in pCloud): shown — and played — in its place, when it is
        // surely that recording: same artist, same length (±6 s). Never for a live album, whose
        // takes only its own files are.
        val live = OwnFileMatcher.isLiveRelease(album)
        val elsewhere: List<Song> by lazy {
            if (live) emptyList() else {
                val saved = runCatching { com.music.bitchord.download.Downloads.savedMetadataSongs() }.getOrDefault(emptyList())
                val ids = songs.mapTo(HashSet()) { it.videoId }
                (saved + com.music.bitchord.data.webdav.WebDavRepository.library()).filter { it.videoId !in ids && !OwnFileMatcher.isLiveRelease(it.albumName.orEmpty()) }
            }
        }
        fun sameSong(t: Track, s: Song): Boolean {
            val a = t.seconds ?: return false
            val b = secondsOf(s) ?: return false
            if (kotlin.math.abs(a - b) > 6) return false
            val by = t.artist ?: artist ?: return false
            val sameBy = OwnFileMatcher.sameArtist(by, s.artist) || com.music.bitchord.data.sources.ArtistLinks.linked(by, s.artist)
            return sameBy && matches(t, s)
        }
        val used = HashSet<String>()
        for (t in ref.tracks) {
            val mine = closest(t, left.filter { matches(t, it) })
                ?: elsewhere.firstOrNull { it.videoId !in used && sameSong(t, it) }?.also { used += it.videoId }
            if (mine != null) {
                left.remove(mine)
                out += mine.copy(trackNumber = t.number)
            } else {
                out += Song(
                    videoId = MISSING + key(album) + ":" + t.disc + ":" + t.number,
                    title = t.title,
                    artist = t.artist ?: artist ?: "",
                    thumbnailUrl = cover,
                    albumName = album,
                    durationText = t.seconds?.let { "%d:%02d".format(it / 60, it % 60) },
                    trackNumber = t.number,
                )
            }
        }
        // Yours not on the list (a bonus track, another edition's) after it.
        out += left
        TrackLog.d(TAG, "album '$album': ${ref.source} lists ${ref.tracks.size} — you have ${songs.size - left.size} of them" +
            (if (left.isNotEmpty()) ", ${left.size} more of yours" else ""))
        return out
    }

    private fun secondsOf(s: Song) = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.fold(0) { a, p -> a * 60 + p }

    private fun words(t: String) = t.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.toSet()

    /** Of several of yours matching [t] (one song recorded twice, "1・2・3" by two acts), the nearest in length. */
    private fun closest(t: Track, candidates: List<Song>): Song? =
        if (candidates.size <= 1 || t.seconds == null) candidates.firstOrNull()
        else candidates.minByOrNull { s -> secondsOf(s)?.let { kotlin.math.abs(it - t.seconds) } ?: Int.MAX_VALUE }

    /** One of yours is this listed track: the title in any script, or a shared word and the same length (±3 s). */
    private fun matches(t: Track, s: Song): Boolean {
        val kt = OwnFileMatcher.allKeys(t.title) + key(t.title)
        val ks = OwnFileMatcher.allKeys(s.title) + key(s.title)
        // A listed artist (a compilation's songs each have theirs) must be this one's.
        // (Only within one script: a Japanese store's 松本梨香 can't be told from Rica Matsumoto here.)
        if (!t.artist.isNullOrBlank() && CJK.containsMatchIn(t.artist) == CJK.containsMatchIn(s.artist) &&
            !OwnFileMatcher.sameArtist(t.artist, s.artist) && !com.music.bitchord.data.sources.ArtistLinks.linked(t.artist, s.artist)
        ) return false
        val a = t.seconds
        val b = secondsOf(s)
        // Same title, other recording: a live take against the studio cut runs a different
        // length — not this track.
        if (a != null && b != null && kotlin.math.abs(a - b) > 20) return false
        if (kt.any { it.isNotEmpty() && it in ks }) return true
        if (a == null || b == null) return false
        if (kotlin.math.abs(a - b) > 3) return false
        return (words(t.title) + kt).any { it in (words(s.title) + ks) }
    }

    /**
     * How well [ref] is this album, or null when it isn't: most of your songs (and at least
     * one) are on it, and where your files carry track numbers, they are at those numbers on
     * it — a concert video's list (talks, encores) or another edition puts them elsewhere.
     */
    private fun score(ref: Reference, songs: List<Song>): Int? {
        if (ref.tracks.isEmpty()) return null
        var hit = 0
        var placed = 0
        var numbered = 0
        for (s in songs) {
            val t = ref.tracks.filter { matches(it, s) }.let { ts ->
                val b = secondsOf(s)
                if (ts.size <= 1 || b == null) ts.firstOrNull() else ts.minByOrNull { it.seconds?.let { a -> kotlin.math.abs(a - b) } ?: Int.MAX_VALUE }
            } ?: continue
            hit++
            val n = s.trackNumber ?: continue
            numbered++
            if (n == t.number) placed++
        }
        if (hit == 0 || hit * 10 < songs.size * 6) return null
        if (numbered >= 2 && placed * 10 < numbered * 7) return null
        // Most placed, then most found, then the list closest in size to what you have.
        return placed * 10_000 + hit * 100 - minOf(99, kotlin.math.abs(ref.tracks.size - songs.size))
    }

    private fun fits(ref: Reference, songs: List<Song>) = score(ref, songs) != null

    // ── The catalogues ──

    private fun cached(album: String): Reference? {
        val o = synchronized(cache) { cache.optJSONObject(key(album)) }?.takeIf { it.optInt("v") == VERSION } ?: return null
        val a = o.optJSONArray("t")?.takeIf { it.length() > 0 } ?: return null
        return Reference(o.optString("s"), (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Track(it.optInt("d", 1), it.optInt("n"), it.optString("t"), it.optInt("l").takeIf { l -> l > 0 }, it.optString("a").takeIf { x -> x.isNotBlank() }, it.optString("y").takeIf { x -> x.isNotBlank() })
        }, o.optString("c").takeIf { it.isNotBlank() })
    }

    /** The album's real cover from the catalogue whose list was taken (null when none is kept yet). */
    fun coverOf(album: String): String? =
        synchronized(cache) { cache.optJSONObject(key(album)) }?.takeIf { it.optInt("v") == VERSION && (it.optJSONArray("t")?.length() ?: 0) > 0 }
            ?.optString("c")?.takeIf { it.isNotBlank() }

    private fun keep(album: String, ref: Reference?) {
        val o = JSONObject().put("at", System.currentTimeMillis()).put("s", ref?.source.orEmpty()).put("v", VERSION)
        ref?.cover?.let { o.put("c", it) }
        synchronized(cache) { cache.optJSONObject(key(album))?.takeIf { it.optInt("v") == VERSION }?.optJSONObject("p") }?.let { o.put("p", it) }
        o.put("t", JSONArray().apply {
            ref?.tracks?.forEach { put(JSONObject().put("d", it.disc).put("n", it.number).put("t", it.title).put("l", it.seconds ?: 0).also { t -> it.artist?.let { a -> t.put("a", a) }; it.videoId?.let { v -> t.put("y", v) } }) }
        })
        synchronized(cache) { cache.put(key(album), o) }
        save()
    }

    /** The first catalogue whose list fits your songs (kept, a miss too). */
    suspend fun find(album: String, artist: String?, songs: List<Song>): Reference? = withContext(Dispatchers.IO) {
        // A list found is kept for good; a miss is asked again after a month.
        synchronized(cache) { cache.optJSONObject(key(album)) }?.takeIf {
            it.optInt("v") == VERSION && ((it.optJSONArray("t")?.length() ?: 0) > 0 || System.currentTimeMillis() - it.optLong("at") < TTL_MS)
        }?.let {
            return@withContext cached(album)?.takeIf { r -> fits(r, songs) }
        }
        // YouTube Music first (fast, and each of its tracks plays as it is): taken when it fits.
        val yt = runCatching { youTubeMusic(album, artist, songs) }.getOrDefault(emptyList())
        yt.mapNotNull { r -> score(r, songs)?.let { r to it } }.maxByOrNull { it.second }?.first?.let { best ->
            TrackLog.d(TAG, "album '$album': YouTube Music's list fits (${best.tracks.size} tracks)")
            keep(album, best)
            return@withContext best
        }
        // Else every other catalogue asked (at once), and the list that fits your songs best taken.
        val all = kotlinx.coroutines.coroutineScope {
            listOf(
                async { runCatching { itunes(album, artist) }.getOrDefault(emptyList()) },
                async { runCatching { vgmdb(album) }.getOrDefault(emptyList()) },
                async { runCatching { musicBrainz(album, artist) }.getOrDefault(emptyList()) },
                async { runCatching { discogs(album, artist) }.getOrDefault(emptyList()) },
            ).flatMap { it.await() }
        }
        val best = all.mapNotNull { r -> score(r, songs)?.let { r to it } }.maxByOrNull { it.second }?.first
        if (best != null) {
            TrackLog.d(TAG, "album '$album': ${all.size} lists found, ${best.source}'s fits best (${best.tracks.size} tracks)")
            keep(album, best)
            return@withContext best
        }
        TrackLog.d(TAG, "album '$album': no catalogue lists it")
        keep(album, null)
        null
    }

    private fun sameTitle(a: String, b: String): Boolean {
        if (OwnFileMatcher.isLiveRelease(a) != OwnFileMatcher.isLiveRelease(b)) return false
        if (key(a) == key(b) || OwnFileMatcher.sameAlbum(a, b)) return true
        // A long title worded a little differently by each catalogue ("Pokémon TV Anime Theme
        // Song BEST OF BEST OF BEST 1997-2023" / "TV Anime "Pokemon" Theme song collection BEST
        // OF BEST OF BEST 1997-2023"): the same years, and most of the words in common.
        fun toks(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "").lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 2 }
        val ta = toks(a); val tb = toks(b)
        if (ta.size < 5 || tb.size < 5) return false
        val years = Regex("""\b(19|20)\d\d\b""")
        if (years.findAll(a).map { it.value }.toSet() != years.findAll(b).map { it.value }.toSet()) return false
        val sa = ta.toSet(); val sb = tb.toSet()
        return (sa intersect sb).size.toDouble() / (sa union sb).size >= 0.6
    }

    /**
     * YouTube Music's album of that name (its tracks, numbered as on the album) — asked here,
     * then as from Japan and the US: an album not offered in your country still has its full
     * list there. Only this country's tracks are kept to play (the others wouldn't play here).
     */
    private suspend fun youTubeMusic(album: String, artist: String?, songs: List<Song>): List<Reference> {
        for (region in listOf<String?>(null, "JP", "US")) {
            val found = if (region == null) youTubeMusicIn(album, artist, local = true)
            else withContext(com.music.bitchord.data.innertube.Innertube.AskedFrom(region)) { youTubeMusicIn(album, artist, local = false) }
            if (found.any { score(it, songs) != null }) {
                if (region != null) TrackLog.d(TAG, "album '$album': listed by YouTube Music as from $region")
                return found
            }
        }
        return emptyList()
    }

    private suspend fun youTubeMusicIn(album: String, artist: String?, local: Boolean): List<Reference> {
        val q = listOfNotNull(album, artist).joinToString(" ")
        val albums = YtMusicRepository.search(q, SearchFilter.ALBUMS).getOrNull().orEmpty()
            .mapNotNull { (it as? SearchResult.Browse)?.item }
            .filter { it.browseId.startsWith("MPREb") && sameTitle(album, it.title) }
            .filter { a -> artist == null || OwnFileMatcher.sameArtist(artist, a.subtitle.substringAfterLast(" • ")) || a.subtitle.contains(artist, ignoreCase = true) }
        val out = ArrayList<Reference>()
        for (a in albums.take(2)) {
            val page = YtMusicRepository.browseSongs(a.browseId).getOrNull() ?: continue
            val tracks = page.songs.mapIndexed { i, s ->
                Track(1, s.trackNumber ?: (i + 1), s.title, secondsOf(s), s.artist.takeIf { it.isNotBlank() }, s.videoId.takeIf { local })
            }
            if (tracks.isNotEmpty()) out += Reference("YouTube Music", tracks, a.thumbnailUrl)
        }
        return out
    }

    /** Apple Music's catalogue (Japan's store first, then the US one). */
    private fun itunes(album: String, artist: String?): List<Reference> {
        val out = ArrayList<Reference>()
        // The store's search wants most words to be in its title: worded another way
        // ("Pokémon … Theme Song" / "TV Anime "Pokemon" Theme song collection"), it is asked
        // again without accents and marks, then by the title's most telling words alone.
        val plain = java.text.Normalizer.normalize(album, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        val telling = plain.split(' ').filter { it.isNotBlank() }.let { w ->
            val years = w.filter { Regex("""(19|20)\d\d""").matches(it) }
            (w.filter { it !in years && it.length >= 4 }.distinct().sortedByDescending { it.length }.take(3) + years).joinToString(" ")
        }
        val queries = listOf(album, plain, telling).filter { it.isNotBlank() }.distinct()
        val seen = HashSet<Long>()
        for (country in listOf("JP", "US")) for (term in queries) {
            val q = listOfNotNull(term, artist).joinToString(" ")
            // In English: Japan's store otherwise names it in Japanese only ("ポケモンTVアニメ主題歌
            // BEST OF…"), which no romaji-tagged file's album name can be told to be.
            val body = get("https://itunes.apple.com/search?term=${enc(q)}&entity=album&limit=10&country=$country&lang=en_us") ?: continue
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: continue
            if (results.length() == 0) continue
            var any = false
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (!sameTitle(album, r.optString("collectionName")) && !sameTitle(album, r.optString("collectionCensoredName"))) continue
                val id = r.optLong("collectionId").takeIf { it > 0 } ?: continue
                any = true
                if (!seen.add(id)) continue
                val art = r.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace(Regex("""/\d+x\d+bb\."""), "/1200x1200bb.")
                // Every track (the store stops at 50 unless told more), named both ways — its own
                // language and English — and whichever fits your files better is taken.
                for (lang in listOf("", "&lang=en_us")) {
                    val list = get("https://itunes.apple.com/lookup?id=$id&entity=song&limit=300&country=$country$lang") ?: continue
                    val items = runCatching { JSONObject(list).optJSONArray("results") }.getOrNull() ?: continue
                    val tracks = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                        .filter { it.optString("wrapperType") == "track" }
                        .map { Track(it.optInt("discNumber", 1), it.optInt("trackNumber"), it.optString("trackName"), (it.optLong("trackTimeMillis") / 1000).toInt().takeIf { s -> s > 0 }, it.optString("artistName").takeIf { a -> a.isNotBlank() }) }
                        .sortedWith(compareBy({ it.disc }, { it.number }))
                    if (tracks.isNotEmpty()) out += Reference("Apple Music", tracks, art)
                    if (country != "JP") break
                }
            }
            // Found under this wording: no need for the looser ones in this store.
            if (any) break
        }
        return out
    }

    /** VGMdb (through vgmdb.info), the reference for anime, game and older Japanese releases. */
    private fun vgmdb(album: String): List<Reference> {
        val body = get("https://vgmdb.info/search/albums/${enc(album)}?format=json") ?: return emptyList()
        val albums = runCatching { JSONObject(body).optJSONObject("results")?.optJSONArray("albums") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reference>()
        for (i in 0 until minOf(albums.length(), 5)) {
            val a = albums.optJSONObject(i) ?: continue
            val titles = a.optJSONObject("titles")
            val names = listOfNotNull(titles?.optString("en"), titles?.optString("ja"), titles?.optString("ja-latn")).filter { it.isNotBlank() }
            if (names.none { sameTitle(album, it) }) continue
            val link = a.optString("link").takeIf { it.isNotBlank() } ?: continue
            val page = get("https://vgmdb.info/$link?format=json") ?: continue
            val pageJson = runCatching { JSONObject(page) }.getOrNull() ?: continue
            val discs = pageJson.optJSONArray("discs") ?: continue
            val tracks = ArrayList<Track>()
            for (d in 0 until discs.length()) {
                val disc = discs.optJSONObject(d) ?: continue
                val list = disc.optJSONArray("tracks") ?: continue
                for (t in 0 until list.length()) {
                    val tr = list.optJSONObject(t) ?: continue
                    val n = tr.optJSONObject("names")
                    val title = n?.let { listOf("English", "Romaji", "Japanese").firstNotNullOfOrNull { k -> it.optString(k).takeIf { v -> v.isNotBlank() } } ?: it.keys().asSequence().firstOrNull()?.let { k -> it.optString(k) } }
                        ?: continue
                    val len = tr.optString("track_length").split(':').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] * 60 + it[1] }
                    tracks += Track(d + 1, t + 1, title, len)
                }
            }
            val art = listOf("picture_full", "picture_small").firstNotNullOfOrNull { k -> pageJson.optString(k).takeIf { it.startsWith("http") && "nocover" !in it } }
            if (tracks.isNotEmpty()) out += Reference("VGMdb", tracks, art)
        }
        return out
    }

    /** MusicBrainz releases of that title (once a second, as it asks). */
    private suspend fun musicBrainz(album: String, artist: String?): List<Reference> {
        suspend fun ask(url: String): String? = brainz.withLock {
            val wait = 1100 - (System.currentTimeMillis() - lastBrainz)
            if (wait > 0) delay(wait)
            lastBrainz = System.currentTimeMillis()
            get(url)
        }
        // Its words, not the exact phrase: a catalogue's wording often differs a little.
        val words = java.text.Normalizer.normalize(album, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().ifBlank { album.replace("\"", "") }
        val q = "release:($words)" + (artist?.let { " AND artist:\"${it.replace("\"", "")}\"" } ?: "")
        val body = ask("https://musicbrainz.org/ws/2/release/?query=${enc(q)}&fmt=json&limit=5")
            ?: return emptyList()
        val releases = runCatching { JSONObject(body).optJSONArray("releases") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reference>()
        for (i in 0 until minOf(releases.length(), 3)) {
            val r = releases.optJSONObject(i) ?: continue
            if (!sameTitle(album, r.optString("title"))) continue
            val page = ask("https://musicbrainz.org/ws/2/release/${r.optString("id")}?inc=recordings+artist-credits&fmt=json") ?: continue
            val rel = runCatching { JSONObject(page) }.getOrNull() ?: continue
            val media = rel.optJSONArray("media") ?: continue
            val tracks = ArrayList<Track>()
            for (m in 0 until media.length()) {
                val disc = media.optJSONObject(m) ?: continue
                val list = disc.optJSONArray("tracks") ?: continue
                for (t in 0 until list.length()) {
                    val tr = list.optJSONObject(t) ?: continue
                    val credit = tr.optJSONArray("artist-credit")?.let { ac -> (0 until ac.length()).joinToString("") { i -> ac.optJSONObject(i)?.let { c -> c.optString("name") + c.optString("joinphrase") }.orEmpty() } }
                    tracks += Track(disc.optInt("position", m + 1), tr.optInt("position", t + 1), tr.optString("title"), (tr.optLong("length") / 1000).toInt().takeIf { it > 0 }, credit?.takeIf { it.isNotBlank() })
                }
            }
            val art = if (rel.optJSONObject("cover-art-archive")?.optBoolean("front") == true) "https://coverartarchive.org/release/${rel.optString("id")}/front-1200" else null
            if (tracks.isNotEmpty()) out += Reference("MusicBrainz", tracks, art)
        }
        return out
    }

    /** Discogs (only with your token, from Settings › Account and integrations). */
    private fun discogs(album: String, artist: String?): List<Reference> {
        val token = AppSettings.discogsToken.value.trim().ifBlank { return emptyList() }
        val q = "release_title=${enc(album)}" + (artist?.let { "&artist=${enc(it)}" } ?: "")
        val body = get("https://api.discogs.com/database/search?$q&type=release&per_page=5&token=${enc(token)}") ?: return emptyList()
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reference>()
        for (i in 0 until minOf(results.length(), 3)) {
            val r = results.optJSONObject(i) ?: continue
            val title = r.optString("title").substringAfter(" - ")
            if (!sameTitle(album, title)) continue
            val page = get("https://api.discogs.com/releases/${r.optLong("id")}?token=${enc(token)}") ?: continue
            val rel = runCatching { JSONObject(page) }.getOrNull() ?: continue
            val list = rel.optJSONArray("tracklist") ?: continue
            var lastDisc = 1
            var n = 0
            val tracks = ArrayList<Track>()
            for (t in 0 until list.length()) {
                val tr = list.optJSONObject(t) ?: continue
                if (tr.optString("type_") != "track") continue
                val pos = tr.optString("position")
                val disc = Regex("""^(\d+)[-.]""").find(pos)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (disc != lastDisc) { lastDisc = disc; n = 0 }
                n++
                val len = tr.optString("duration").split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] * 60 + it[1] }
                tracks += Track(disc, n, tr.optString("title"), len)
            }
            val images = rel.optJSONArray("images")
            val art = images?.let { im -> (0 until im.length()).mapNotNull { im.optJSONObject(it) }.let { all -> all.firstOrNull { it.optString("type") == "primary" } ?: all.firstOrNull() } }
                ?.optString("uri")?.takeIf { it.startsWith("http") }
            if (tracks.isNotEmpty()) out += Reference("Discogs", tracks, art)
        }
        return out
    }

    // ── A missing track, found to play ──

    /** Somewhere [missing] plays (YouTube Music: same title in any script, length within 6 s when known). */
    suspend fun findPlayable(missing: Song): Song? = withContext(Dispatchers.IO) {
        val albumKey = key(missing.albumName.orEmpty())
        val trackKey = missing.videoId.removePrefix(MISSING).substringAfter(':')
        // A talk between songs ("MC", "トーク") is no song to find elsewhere.
        if (TALK.containsMatchIn(missing.title)) return@withContext null
        // Listed by YouTube Music: its own track there.
        cached(missing.albumName.orEmpty())?.tracks?.firstOrNull { "${it.disc}:${it.number}" == trackKey && it.videoId != null }?.let { t ->
            return@withContext missing.copy(videoId = t.videoId!!)
        }
        synchronized(cache) { cache.optJSONObject(albumKey)?.takeIf { it.optInt("v") == VERSION }?.optJSONObject("p")?.optJSONObject(trackKey) }?.let { p ->
            return@withContext missing.copy(videoId = p.optString("v"), title = p.optString("t", missing.title), artist = p.optString("a", missing.artist))
        }
        val q = "${missing.title} ${missing.artist}".trim()
        val want = secondsOf(missing)
        val keys = OwnFileMatcher.allKeys(missing.title) + key(missing.title)
        val live = OwnFileMatcher.isLiveRelease(missing.albumName.orEmpty())
        // For a live album the concert's own name is asked first (its take, if YouTube Music has it).
        val hits = (if (live) listOf("$q ${missing.albumName}", q) else listOf(q)).flatMap { query ->
            YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty().mapNotNull { (it as? SearchResult.Track)?.song }
        }.distinctBy { it.videoId }
        // The same artist; and for a live album, a live recording of it — a studio cut is
        // another track, and a same-titled song by someone else is not this one.
        hits.firstOrNull { s ->
            val k = OwnFileMatcher.allKeys(s.title) + key(s.title)
            k.any { it.isNotEmpty() && it in keys } &&
                OwnFileMatcher.sameArtist(missing.artist, s.artist) &&
                (want == null || secondsOf(s)?.let { kotlin.math.abs(it - want) <= 6 } != false) &&
                (!live || OwnFileMatcher.isLiveRelease(s.title) || OwnFileMatcher.isLiveRelease(s.albumName.orEmpty()) ||
                    OwnFileMatcher.sameAlbum(missing.albumName, s.albumName))
        }.also { found ->
            TrackLog.d(TAG, "missing '${missing.title}': " + (found?.let { s -> "plays from YouTube Music (${s.videoId})" } ?: "nowhere found"))
            if (found != null) {
                synchronized(cache) {
                    val entry = cache.optJSONObject(albumKey)?.takeIf { it.optInt("v") == VERSION }
                        ?: JSONObject().put("v", VERSION).put("at", 0L).also { cache.put(albumKey, it) }
                    val p = entry.optJSONObject("p") ?: JSONObject().also { entry.put("p", it) }
                    p.put(trackKey, JSONObject().put("v", found.videoId).put("t", found.title).put("a", found.artist))
                }
                save()
            }
        }?.let { found -> missing.copy(videoId = found.videoId, title = found.title, artist = found.artist, thumbnailUrl = found.thumbnailUrl ?: missing.thumbnailUrl) }
    }

    /**
     * [songs] ready to play: each greyed track swapped for where it plays (kept from before,
     * or found now — all at once, a few seconds at most); the ones found nowhere left out.
     */
    suspend fun playable(songs: List<Song>, start: Int): Pair<List<Song>, Int> {
        if (songs.none(::isMissing)) return songs to start
        val found = withTimeoutOrNull(8_000) {
            kotlinx.coroutines.coroutineScope {
                songs.filter(::isMissing).map { m -> async { m.videoId to runCatching { findPlayable(m) }.getOrNull() } }.map { it.await() }.toMap()
            }
        }.orEmpty()
        val out = ArrayList<Song>()
        var at = 0
        songs.forEachIndexed { i, s ->
            val real = if (isMissing(s)) found[s.videoId] else s
            if (i == start) at = out.size
            if (real != null) out += real
        }
        TrackLog.d(TAG, "album play: ${songs.count(::isMissing)} you lack, ${found.values.count { it != null }} found to play")
        return out to at.coerceAtMost((out.size - 1).coerceAtLeast(0))
    }
}
