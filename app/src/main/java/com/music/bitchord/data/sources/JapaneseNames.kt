package com.music.bitchord.data.sources

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.EditedLyrics
import com.music.bitchord.data.lyrics.LyricNames
import com.music.bitchord.data.lyrics.SidecarLyrics
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: the Japanese spelling of a song's title and artist when the
 * song is known by its romaji ("Boku ga Shinou to Omotta no Wa" · "Mika
 * Nakashima" → 僕が死のうと思ったのは · 中島美嘉) — so a catalogue that files it
 * only in Japanese (JioSaavn, NetEase, …) can be searched for it too.
 *
 * Never a guess on its own: every spelling is checked before it is kept.
 *
 *  - **Your own copies** — the pCloud files that are this song (their tags and
 *    file names, "Title (日本語)"), the YouTube song a file stands in for, the
 *    `.lrc` beside a file (`[ti:]`/`[ar:]`), your lyrics folders' file names,
 *    the lyrics you timed yourself.
 *  - **The song's own words** — a title is very often sung: the lyrics on
 *    screen are read with the kanji dictionary and the run of words that
 *    sounds like the romaji title is the title, in the right characters.
 *  - **Catalogues that hold both spellings of one entry** — Apple's (the
 *    iTunes catalogue, Japanese storefront, asked in English and in Japanese
 *    for the same track id), MusicBrainz (artist names, sort names and
 *    aliases; recordings by artist and length), Wikipedia (an English article
 *    linked to its Japanese one, and Japanese Wikipedia's search), the
 *    Vocaloid Lyrics wiki on Fandom (romaji pages redirect to the Japanese
 *    one), VGMdb (anime and game music artists), NetEase.
 *  - **Google's Japanese input** — the kana-kanji conversion a phone keyboard
 *    does — only when it reads back as the same sounds.
 *
 * A title from anywhere but a paired catalogue entry must read back (kanji
 * dictionary → romaji) as the same sounds as the romaji title; an artist must
 * be the same name read in romaji, or come paired with it. What is found is
 * kept for good — here and in pCloud (`.bitchord-audio/japanese_names.json`)
 * — so each song and artist is looked up once.
 */
object JapaneseNames {

    private const val TAG = "BitChord"
    private const val FILE = "japanese_names.json"
    private const val REMOTE_DIR = ".bitchord-audio"

    data class Names(val titles: List<String>, val artists: List<String>) {
        val isEmpty: Boolean get() = titles.isEmpty() && artists.isEmpty()

        /** Search queries: the Japanese title with each artist spelling, then the title alone. */
        fun queries(romajiArtist: String): List<String> = buildList {
            for (t in titles.take(2)) {
                for (a in artists.take(2)) add("$t $a")
                if (romajiArtist.isNotBlank()) add("$t ${romajiArtist.substringBefore(',').trim()}")
                add(t)
            }
            for (a in artists.take(1)) add(a)
        }.distinct()

        companion object { val NONE = Names(emptyList(), emptyList()) }
    }

    /** Title key ([titleKey] | [artistKey]) → Japanese titles; artist key → Japanese artist names. */
    private val titles = ConcurrentHashMap<String, List<String>>()
    private val artists = ConcurrentHashMap<String, List<String>>()

    /** Looked up this session and nothing found: not asked again until the app restarts. */
    private val missed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var local: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        local = File(context.filesDir, FILE)
        scope.launch {
            runCatching { local?.takeIf { it.isFile }?.readText() }.getOrNull()?.let(::read)
            remoteUrl()?.let { url ->
                WebDavClient.readText(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 4 * 1024 * 1024)
                    .getOrNull()?.let(::read)
            }
        }
    }

    // ── Keys ─────────────────────────────────────────────────────────────

    private val NOTE = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")
    private val GUEST = Regex("""\s+(feat\.?|ft\.?|featuring|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、|×|\bx\b)\s*""", RegexOption.IGNORE_CASE)
    private val TRACK_NO = Regex("""^\s*\d{1,3}\s*[.\-_)]\s*(?=\S)""")
    private val EXTENSION = Regex("""\.(mp3|flac|m4a|ogg|opus|wav|aac|lrc|lrcx|txt)$""", RegexOption.IGNORE_CASE)

    /** The title without notes, guests, track number or extension. */
    private fun bare(title: String): String =
        title.replace(EXTENSION, "").replace(TRACK_NO, "").replace(NOTE, " ").replace(GUEST, "").trim()

    /** How the title sounds, folded ([JapaneseReading.soundKey]); null while the dictionary loads. */
    fun soundOf(title: String): String? {
        val b = bare(title).ifBlank { title }
        return JapaneseReading.romaji(b)?.let(JapaneseReading::soundKey)?.takeIf { it.length >= 4 }
    }

    /** The first artist as sorted romaji words: "Mika Nakashima" and "Nakashima Mika" agree. */
    fun artistKey(artist: String): String {
        val first = artist.split(ARTIST_SPLIT).firstOrNull()?.trim().orEmpty()
        val words = if (JapaneseReading.isJapanese(first)) {
            JapaneseReading.romajiWords(first)?.toList() ?: listOf(FileName.key(first))
        } else {
            first.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        }
        return words.map(JapaneseReading::soundKey).filter { it.isNotEmpty() }.sorted().joinToString("_")
    }

    private fun titleKey(title: String, artist: String): String? = soundOf(title)?.let { "$it|${artistKey(artist)}" }

    /**
     * Whether [title] is a romaji title that may have a Japanese spelling:
     * not written in Japanese, and reading as romaji throughout.
     */
    fun mayHaveJapanese(title: String): Boolean {
        val b = bare(title)
        return b.isNotBlank() && !JapaneseReading.isJapanese(b) && LyricNames.kana(b) != null
    }

    private fun mayHaveJapaneseArtist(artist: String): Boolean {
        val first = artist.split(ARTIST_SPLIT).firstOrNull()?.trim().orEmpty()
        return first.isNotBlank() && !JapaneseReading.isJapanese(first) && LyricNames.kana(first) != null
    }

    // ── What is known already ────────────────────────────────────────────

    /** What has been learnt for this song, without looking anything up. */
    fun cached(title: String, artist: String): Names? {
        val t = titleKey(title, artist)?.let { titles[it] }.orEmpty()
        val japaneseSong = t.isNotEmpty() || JapaneseReading.isJapanese(title) || (title.isBlank() || mayHaveJapanese(title))
        val a = if (japaneseSong) artists[artistKey(artist)].orEmpty() else emptyList()
        return Names(t, a).takeUnless { it.isEmpty }
    }

    /** The Japanese spellings learnt for [artist], without looking anything up. */
    fun cachedArtist(artist: String): List<String> = artists[artistKey(artist)].orEmpty().filterNot(::katakanaOnly)

    /**
     * A name written only in katakana — how Japanese spells a foreign name
     * (ケシャ for Kesha). Not offered for an artist on its own: without a
     * Japanese song to go with it, it is far likelier a foreign artist.
     */
    private fun katakanaOnly(name: String): Boolean =
        name.isNotBlank() && name.all { it in '\u30a0'..'\u30ff' || it == '・' || it.isWhitespace() }

    // ── Finding ──────────────────────────────────────────────────────────

    private val songLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The Japanese title and artist of [song], learnt once and kept. Empty
     * when the song is not Japanese, or nothing could be confirmed. [online]
     * false: only what is on the phone and in your pCloud is looked at.
     */
    suspend fun of(song: Song, online: Boolean = true): Names = withContext(Dispatchers.IO) {
        val title = song.title
        val artist = song.artist
        val wantTitle = mayHaveJapanese(title) || JapaneseReading.isJapanese(title)
        // An artist's Japanese name only for a Japanese song: "Kesha" singing
        // "Backstabber" isn't asked for as ケシャ.
        val wantArtist = wantTitle && mayHaveJapaneseArtist(artist)
        if (!wantTitle && !wantArtist) return@withContext Names.NONE
        JapaneseReading.tokenizerNow()
        val sound = soundOf(title)
        val tKey = sound?.let { "$it|${artistKey(artist)}" }
        val aKey = artistKey(artist)
        val known = cached(title, artist)
        val titleDone = !mayHaveJapanese(title) || (tKey != null && titles.containsKey(tKey))
        val artistDone = !wantArtist || artists.containsKey(aKey)
        if (titleDone && artistDone) return@withContext known ?: Names.NONE
        val missKey = "${tKey ?: title}|$aKey|$online"
        if (missKey in missed) return@withContext known ?: Names.NONE

        songLocks.getOrPut(missKey) { Mutex() }.withLock {
            val found = Found(sound, artist, song.durationText?.let(::seconds))
            // Inside a Japanese-titled song's own title: "Title (日本語)".
            found.fromText(title, trusted = true)
            runCatching { fromOwnCopies(song, found) }
            runCatching { fromLyrics(song, found) }
            if (online && AppSettings.meteredConnection.value != null) {
                runCatching { fromCatalogues(title, artist, found) }
            }
            val names = found.result()
            if (names.titles.isNotEmpty() && tKey != null) titles[tKey] = names.titles
            if (names.artists.isNotEmpty()) artists[aKey] = names.artists
            if (names.isEmpty) {
                missed += missKey
            } else {
                TrackLog.d(TAG, "japanese names: '$title' · '$artist' → ${names.titles} · ${names.artists}")
                persist()
            }
            Names(
                names.titles.ifEmpty { known?.titles.orEmpty() },
                names.artists.ifEmpty { known?.artists.orEmpty() },
            )
        }
    }

    /** The Japanese spellings of an artist alone ("Mika Nakashima" → 中島美嘉). */
    suspend fun ofArtist(artist: String, online: Boolean = true): List<String> = withContext(Dispatchers.IO) {
        if (!mayHaveJapaneseArtist(artist)) return@withContext emptyList()
        artists[artistKey(artist)]?.filterNot(::katakanaOnly)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val missKey = "artist|${artistKey(artist)}|$online"
        if (missKey in missed) return@withContext emptyList()
        JapaneseReading.tokenizerNow()
        val found = Found(null, artist, null)
        runCatching { artistFromLibrary(artist, found) }
        if (online && AppSettings.meteredConnection.value != null) runCatching { artistFromCatalogues(artist, found) }
        val names = found.result().artists.filterNot(::katakanaOnly)
        if (names.isEmpty()) missed += missKey else {
            artists[artistKey(artist)] = names
            persist()
        }
        names
    }

    /**
     * For words typed in a search box: the Japanese they most likely are —
     * the known spelling of an artist or song by those words, else the
     * kana-kanji conversion of the whole.
     */
    suspend fun forQuery(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank() || JapaneseReading.isJapanese(q) || LyricNames.kana(q) == null) return@withContext emptyList()
        val out = LinkedHashSet<String>()
        artists[artistKey(q)]?.filterNot(::katakanaOnly)?.let(out::addAll)
        soundOf(q)?.let { s -> titles.entries.filter { it.key.startsWith("$s|") }.forEach { out += it.value } }
        if (out.isEmpty()) {
            withTimeoutOrNull(3_500) { runCatching { LyricNames.japanese(q) }.getOrNull() }?.firstOrNull()?.let(out::add)
        }
        out.take(3)
    }

    /** Lyrics just shown for a song: its title is read off them, if it is sung. */
    fun lyricsSeen(title: String, artist: String, lines: List<String>) {
        if (!mayHaveJapanese(title)) return
        val key = titleKey(title, artist) ?: return
        if (titles.containsKey(key)) return
        scope.launch {
            val found = Found(soundOf(title), artist, null)
            found.fromLyricLines(lines)
            val names = found.result()
            if (names.titles.isNotEmpty()) {
                titles[key] = names.titles
                TrackLog.d(TAG, "japanese names: '$title' from its lyrics → ${names.titles}")
                persist()
            }
        }
    }

    /** A match confirmed elsewhere (a JioSaavn row the length and sound agree on). */
    fun learn(title: String, artist: String, japaneseTitle: String?, japaneseArtist: String?) {
        var changed = false
        if (japaneseTitle != null && JapaneseReading.isJapanese(japaneseTitle)) {
            titleKey(title, artist)?.let { k ->
                val old = titles[k].orEmpty()
                val clean = bare(japaneseTitle)
                if (clean !in old) { titles[k] = (old + clean).take(3); changed = true }
            }
        }
        if (japaneseArtist != null && JapaneseReading.isJapanese(japaneseArtist)) {
            val k = artistKey(artist)
            val old = artists[k].orEmpty()
            if (japaneseArtist !in old) { artists[k] = (old + japaneseArtist).take(3); changed = true }
        }
        if (changed) persist()
    }

    // ── Collecting ───────────────────────────────────────────────────────

    /**
     * Spellings gathered for one song, each with how far it is trusted;
     * [result] keeps the confirmed ones, best first.
     */
    private class Found(val sound: String?, val romajiArtist: String, val seconds: Int?) {
        val titles = LinkedHashMap<String, Int>()
        val artists = LinkedHashMap<String, Int>()

        /** [trusted]: a copy of this very song, or a catalogue entry paired with the romaji. */
        fun title(candidate: String?, trusted: Boolean) {
            val t = candidate?.let(::bare)?.trim()?.takeIf { it.length >= 2 && JapaneseReading.isJapanese(it) } ?: return
            val s = sound ?: return
            val got = JapaneseReading.romaji(t)?.let(JapaneseReading::soundKey) ?: return
            val score = when {
                got == s -> if (trusted) 3 else 2
                // The dictionary reads a kanji another way now and then (明日:
                // asu/ashita); a copy of the song itself may be a sound or two off.
                trusted && near(got, s) -> 1
                else -> return
            }
            titles[t] = maxOf(titles[t] ?: 0, score)
        }

        fun artist(candidate: String?, trusted: Boolean) {
            val a = candidate?.split(ARTIST_SPLIT)?.firstOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && JapaneseReading.isJapanese(it) } ?: return
            val same = OwnFileMatcher.sameArtist(romajiArtist, a)
            val score = when {
                same -> if (trusted) 3 else 2
                trusted -> 1
                else -> return
            }
            artists[a] = maxOf(artists[a] ?: 0, score)
        }

        /** A text that may carry the Japanese inside: "Title (日本語)", "日本語 / Title", a file name. */
        fun fromText(text: String?, trusted: Boolean) {
            if (text.isNullOrBlank()) return
            val t = text.replace(EXTENSION, "").replace(TRACK_NO, "")
            val parts = buildList {
                add(t)
                NOTE.findAll(t).forEach { add(it.value.trim().trim('(', ')', '[', ']', '（', '）', '【', '】')) }
                addAll(t.replace(NOTE, " ").split(Regex("""\s+[-–—/／|｜]\s+|\s*／\s*|\s*｜\s*""")))
            }
            parts.forEach { title(it, trusted) }
        }

        fun fromLyricLines(lines: List<String>) {
            val s = sound ?: return
            for (line in lines.take(120)) {
                if (!JapaneseReading.isJapanese(line)) continue
                JapaneseReading.spanSounding(line, s)?.let { title(it, trusted = true) }
            }
        }

        fun result(): Names = Names(
            titles.entries.sortedByDescending { it.value }.map { it.key }.distinct().take(3),
            artists.entries.sortedByDescending { it.value }.map { it.key }.distinct().take(3),
        )

        private fun near(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 2 || minOf(a.length, b.length) < 8) return false
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            return prev[b.length] <= maxOf(1, b.length / 10)
        }
    }

    /** The pCloud files that are this song, the YouTube song a file stands in for, their `.lrc`s. */
    private suspend fun fromOwnCopies(song: Song, found: Found) {
        val copies = LinkedHashMap<String, Song>()
        fun add(s: Song?) { if (s != null) copies.putIfAbsent(s.videoId, s) }
        add(song)
        runCatching { PreferredSource.filesFor(song).take(5) }.getOrDefault(emptyList()).forEach(::add)
        SourceMap.entry(song.videoId)?.file?.let(SourceMap::fileSong).let(::add)
        LocalLikes.youTubeOf(song.videoId)?.let { yt ->
            LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs?.firstOrNull { it.videoId == yt }
        }.let(::add)
        if (WebDavConfig.isWebDavId(song.videoId)) runCatching { SourceMap.likedSongFor(song) }.getOrNull().let(::add)

        for (c in copies.values) {
            // A copy is this song by name and length already; its spellings are trusted.
            found.fromText(c.title, trusted = true)
            found.artist(c.artist, trusted = true)
            if (WebDavConfig.isWebDavId(c.videoId)) {
                WebDavConfig.fileUrlOf(c.videoId)?.let { url ->
                    found.fromText(runCatching { URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }.getOrNull(), trusted = true)
                }
            }
        }
        // The .lrc beside each file: its [ti:] and [ar:], and the words themselves.
        for (c in copies.values.filter { WebDavConfig.isWebDavId(it.videoId) }.take(3)) {
            val text = runCatching { SidecarLyrics.besideText(c.videoId) }.getOrNull() ?: continue
            Regex("""\[ti:([^\]]*)]""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { found.fromText(it, trusted = true) }
            Regex("""\[ar:([^\]]*)]""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { found.artist(it, trusted = true) }
            found.fromLyricLines(text.lines().map { it.replace(Regex("""\[[^\]]*]"""), "").trim() })
        }
        // Your lyrics folders: a file named in Japanese that sounds like the title.
        SidecarLyrics.japaneseNames().forEach { found.title(it, trusted = false) }
        artistFromLibrary(song.artist, found)
    }

    /** The artist as your library and likes already spell it in Japanese, or as learnt from your files. */
    private fun artistFromLibrary(artist: String, found: Found) {
        ArtistLinks.aliases(artist).forEach { found.artist(it, trusted = true) }
        val names = HashSet<String>()
        WebDavRepository.library().forEach { names += it.artist }
        LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs?.forEach { names += it.artist }
        names.asSequence().filter { JapaneseReading.isJapanese(it) }.take(5_000).forEach { found.artist(it, trusted = false) }
    }

    /** Lyrics you timed yourself, and the ones last shown, read for the title. */
    private suspend fun fromLyrics(song: Song, found: Found) {
        runCatching { EditedLyrics.get(song.videoId) }.getOrNull()?.let { lines -> found.fromLyricLines(lines.map { it.text }) }
    }

    // ── Catalogues ───────────────────────────────────────────────────────

    private suspend fun fromCatalogues(title: String, artist: String, found: Found) = coroutineScope {
        val plainTitle = bare(title)
        val firstArtist = artist.split(ARTIST_SPLIT).firstOrNull()?.trim().orEmpty()
        val jobs = listOf(
            async { runCatching { itunes(plainTitle, firstArtist, found) } },
            async { runCatching { netease(plainTitle, firstArtist, found) } },
            async { runCatching { wikipediaSong(plainTitle, firstArtist, found) } },
            async { runCatching { vocaloidWiki(plainTitle, found) } },
            async { runCatching { musicBrainzArtist(firstArtist, found, recordingsFor = found.seconds) } },
        )
        jobs.forEach { it.await() }
        if (found.artists.isEmpty()) artistFromCatalogues(artist, found)
        // Last: the keyboard's conversion, kept only if it reads back the same.
        if (found.titles.isEmpty() && mayHaveJapanese(title)) {
            withTimeoutOrNull(4_000) { runCatching { LyricNames.japanese(plainTitle) }.getOrNull() }
                .orEmpty().forEach { found.title(it, trusted = false) }
        }
    }

    private suspend fun artistFromCatalogues(artist: String, found: Found) = coroutineScope {
        val first = artist.split(ARTIST_SPLIT).firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return@coroutineScope
        listOf(
            async { runCatching { musicBrainzArtist(first, found, recordingsFor = null) } },
            async { runCatching { wikipediaArtist(first, found) } },
            async { runCatching { itunesArtist(first, found) } },
            async { runCatching { vgmdbArtist(first, found) } },
        ).forEach { it.await() }
        if (found.artists.isEmpty()) {
            // The keyboard's conversion, family name first (as Japanese writes it) and as given.
            val words = first.split(' ').filter { it.isNotBlank() }
            val orders = if (words.size == 2) listOf("${words[1]}${words[0]}", "${words[0]}${words[1]}") else listOf(first.replace(" ", ""))
            for (o in orders) {
                withTimeoutOrNull(3_500) { runCatching { LyricNames.japanese(o) }.getOrNull() }
                    ?.firstOrNull()?.let { found.artist(it, trusted = false) }
            }
        }
    }

    private val http by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private const val AGENT = "BitChordAudio/1.9 (https://github.com/JuanmiRodas/Musichord)"

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/json").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Apple's catalogue, Japanese storefront: the track found in English, then
     * the same track id asked for in Japanese. One entry, both spellings.
     */
    private fun itunes(title: String, artist: String, found: Found) {
        val term = enc("$title $artist".trim())
        val en = get("https://itunes.apple.com/search?term=$term&country=JP&media=music&entity=song&limit=25&lang=en_us") ?: return
        val rows = JSONObject(en).optJSONArray("results") ?: return
        val wanted = found.sound ?: return
        val ids = ArrayList<Long>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val name = r.optString("trackName")
            val sameSound = JapaneseReading.soundKey(bare(name)) == wanted ||
                JapaneseReading.romaji(bare(name))?.let(JapaneseReading::soundKey) == wanted
            val secs = r.optLong("trackTimeMillis") / 1000
            val lengthOk = found.seconds == null || secs == 0L || kotlin.math.abs(secs - found.seconds) <= 4
            if (sameSound && lengthOk && OwnFileMatcher.sameArtist(artist, r.optString("artistName"))) {
                ids += r.optLong("trackId")
            } else if (JapaneseReading.isJapanese(name) && lengthOk) {
                // Already Japanese in the English answer: checked by sound.
                found.title(name, trusted = false)
                found.artist(r.optString("artistName"), trusted = false)
            }
        }
        if (ids.isEmpty()) return
        val ja = get("https://itunes.apple.com/lookup?id=${ids.take(5).joinToString(",")}&country=JP&lang=ja_jp") ?: return
        val back = JSONObject(ja).optJSONArray("results") ?: return
        for (i in 0 until back.length()) {
            val r = back.getJSONObject(i)
            found.title(r.optString("trackName"), trusted = true)
            found.artist(r.optString("artistName"), trusted = true)
        }
    }

    private fun itunesArtist(artist: String, found: Found) {
        val en = get("https://itunes.apple.com/search?term=${enc(artist)}&country=JP&media=music&entity=musicArtist&limit=5&lang=en_us") ?: return
        val rows = JSONObject(en).optJSONArray("results") ?: return
        val ids = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { OwnFileMatcher.sameArtist(artist, it.optString("artistName")) || JapaneseReading.isJapanese(it.optString("artistName")) }
            .map { it.optLong("artistId") }.filter { it > 0 }
        if (ids.isEmpty()) return
        val ja = get("https://itunes.apple.com/lookup?id=${ids.take(3).joinToString(",")}&country=JP&lang=ja_jp") ?: return
        val back = JSONObject(ja).optJSONArray("results") ?: return
        for (i in 0 until back.length()) found.artist(back.getJSONObject(i).optString("artistName"), trusted = true)
    }

    private suspend fun netease(title: String, artist: String, found: Found) {
        val wanted = found.sound ?: return
        val rows = com.music.bitchord.data.lyrics.NetEase.songs("$title $artist".trim(), limit = 20) +
            com.music.bitchord.data.lyrics.NetEase.songs(artist, limit = 40)
        for (r in rows) {
            if (!JapaneseReading.isJapanese(r.name)) continue
            val lengthOk = found.seconds == null || r.seconds == 0L || kotlin.math.abs(r.seconds - found.seconds) <= 4
            if (!lengthOk) continue
            val got = JapaneseReading.romaji(bare(r.name))?.let(JapaneseReading::soundKey) ?: continue
            if (got != wanted) continue
            found.title(r.name, trusted = false)
            r.artists.forEach { found.artist(it, trusted = false) }
        }
    }

    /** MusicBrainz allows one request a second. */
    private val mbGate = Mutex()
    private var mbLast = 0L

    private suspend fun mb(path: String): JSONObject? = mbGate.withLock {
        val wait = 1_100 - (System.currentTimeMillis() - mbLast)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        mbLast = System.currentTimeMillis()
        get("https://musicbrainz.org/ws/2/$path")?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /**
     * MusicBrainz: the artist by its romaji alias or sort name, its own name
     * (Japanese) taken when the romaji is one of its names; then, with
     * [recordingsFor] seconds, its recordings of that length — the title is
     * the one that sounds like the romaji.
     */
    private suspend fun musicBrainzArtist(artist: String, found: Found, recordingsFor: Int?) {
        val q = enc("artist:\"$artist\" OR alias:\"$artist\" OR sortname:\"$artist\"")
        val res = mb("artist?query=$q&fmt=json&limit=5") ?: return
        val list = res.optJSONArray("artists") ?: return
        val mine = ArrayList<String>()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val name = a.optString("name")
            val sort = a.optString("sort-name")
            val aliases = a.optJSONArray("aliases")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).optString("name") } }.orEmpty()
            val romajiNames = (aliases + sort.split(',').map { it.trim() }.reversed().joinToString(" ") + sort)
            val isThem = romajiNames.any { OwnFileMatcher.sameArtist(artist, it) } || OwnFileMatcher.sameArtist(artist, name)
            if (!isThem) continue
            if (JapaneseReading.isJapanese(name)) found.artist(name, trusted = true)
            aliases.filter { JapaneseReading.isJapanese(it) }.forEach { found.artist(it, trusted = true) }
            mine += a.optString("id")
        }
        val secs = recordingsFor ?: return
        val id = mine.firstOrNull() ?: return
        val ms = secs * 1000L
        val rq = enc("arid:$id AND dur:[${ms - 4000} TO ${ms + 4000}]")
        val recs = mb("recording?query=$rq&fmt=json&limit=50")?.optJSONArray("recordings") ?: return
        for (i in 0 until recs.length()) found.title(recs.getJSONObject(i).optString("title"), trusted = false)
    }

    /** English Wikipedia's article on the song, through to the Japanese article linked to it. */
    private fun wikipediaSong(title: String, artist: String, found: Found) {
        val q = enc("$title $artist song")
        val body = get("https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=4&prop=langlinks&lllang=ja&redirects=1&format=json") ?: return
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return
        val wanted = found.sound ?: return
        pages.keys().forEach { k ->
            val p = pages.getJSONObject(k)
            val en = bare(p.optString("title"))
            // The English article has to be this song by name.
            if (JapaneseReading.soundKey(en) != wanted) return@forEach
            val ja = p.optJSONArray("langlinks")?.optJSONObject(0)?.optString("*") ?: return@forEach
            found.title(ja, trusted = true)
        }
    }

    /** English Wikipedia's article on the artist, and the Japanese one it links to; else Japanese Wikipedia's search. */
    private fun wikipediaArtist(artist: String, found: Found) {
        val q = enc(artist)
        get("https://en.wikipedia.org/w/api.php?action=query&titles=$q&prop=langlinks&lllang=ja&redirects=1&format=json")?.let { body ->
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages")
            pages?.keys()?.forEach { k ->
                val ja = pages.getJSONObject(k).optJSONArray("langlinks")?.optJSONObject(0)?.optString("*") ?: return@forEach
                found.artist(ja.replace(Regex("""\s*[(（][^)）]*[)）]\s*$"""), ""), trusted = true)
            }
        }
        if (found.artists.isNotEmpty()) return
        get("https://ja.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc("\"$artist\"")}&srlimit=5&format=json")?.let { body ->
            val hits = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: return
            for (i in 0 until hits.length()) {
                val t = hits.getJSONObject(i).optString("title").replace(Regex("""\s*[(（][^)）]*[)）]\s*$"""), "")
                // Japanese Wikipedia's search is fuzzy: the page has to read as the same name.
                found.artist(t, trusted = false)
            }
        }
    }

    /** The Vocaloid Lyrics wiki (Fandom): a romaji page name redirects to the Japanese one. */
    private fun vocaloidWiki(title: String, found: Found) {
        val body = get("https://vocaloidlyrics.fandom.com/api.php?action=query&titles=${enc(title)}&redirects=1&format=json") ?: return
        val q = JSONObject(body).optJSONObject("query") ?: return
        val redirects = q.optJSONArray("redirects") ?: return
        for (i in 0 until redirects.length()) found.title(redirects.getJSONObject(i).optString("to"), trusted = false)
    }

    /** VGMdb (anime and game music), through vgmdb.info: an artist's Japanese name. */
    private fun vgmdbArtist(artist: String, found: Found) {
        val body = get("https://vgmdb.info/search/artists/${enc(artist).replace("+", "%20")}?format=json") ?: return
        val list = JSONObject(body).optJSONObject("results")?.optJSONArray("artists") ?: return
        for (i in 0 until minOf(list.length(), 8)) {
            val a = list.getJSONObject(i)
            val names = a.optJSONObject("names") ?: continue
            val en = names.optString("en")
            val ja = names.optString("ja")
            if (OwnFileMatcher.sameArtist(artist, en)) found.artist(ja, trusted = true)
        }
    }

    // ── Keeping ──────────────────────────────────────────────────────────

    private fun read(text: String) = runCatching {
        val o = JSONObject(text)
        o.optJSONObject("t")?.let { t -> t.keys().forEach { k -> titles.putIfAbsent(k, t.getJSONArray(k).toList()) } }
        o.optJSONObject("a")?.let { a -> a.keys().forEach { k -> artists.putIfAbsent(k, a.getJSONArray(k).toList()) } }
    }

    private fun JSONArray.toList(): List<String> = (0 until length()).map { getString(it) }

    private fun text(): String = JSONObject().apply {
        put("t", JSONObject().apply { titles.forEach { (k, v) -> put(k, JSONArray(v)) } })
        put("a", JSONObject().apply { artists.forEach { (k, v) -> put(k, JSONArray(v)) } })
    }.toString()

    @Volatile
    private var uploadPending = false

    private fun persist() {
        val f = local ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        if (uploadPending) return
        uploadPending = true
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            uploadPending = false
            val dir = remoteDir() ?: return@launch
            val u = AppSettings.webdavUsername.value
            val p = AppSettings.webdavPassword.value
            val bytes = text().toByteArray(Charsets.UTF_8)
            WebDavClient.ensureCollection(dir, u, p)
            WebDavClient.putFile(dir + FILE, bytes.inputStream(), bytes.size.toLong(), "application/json", u, p, overwrite = true)
        }
    }

    private fun remoteDir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/$REMOTE_DIR/"
    }

    private fun remoteUrl(): String? = remoteDir()?.let { it + FILE }

    private fun seconds(text: String): Int? =
        text.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, p -> acc * 60 + p }.takeIf { it > 0 }
}
