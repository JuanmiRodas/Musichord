package com.music.bitchord.data.sources

import com.music.bitchord.data.lyrics.LyricAliases
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.webdav.WebDavRepository
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * BitChord Audio: which of your files is the song YouTube is about to play.
 *
 * Looser about names than [TrackMatcher] and stricter about length, because
 * the two sides name songs differently in predictable ways:
 *
 *  - YouTube joins two scripts in one title — "流れ星 - Nagare-Boshi",
 *    "遥か彼方 - Haruka Kanata" — where a file has one of them. Each side of
 *    the separator is tried on its own.
 *  - A video title quotes the song: "宇多田ヒカル『One Last Kiss』".
 *  - Notes in brackets and guest credits ("(con Bleachers)", "feat. …")
 *    describe the release, not the song's name.
 *  - Japanese names in either order: "Hikaru Utada" / "Utada Hikaru".
 *  - Your files' other names — the romaji or original title in the file
 *    name, the tags' alternatives, your own aliases ([LyricAliases]).
 *
 * A name and an artist agreeing is not enough on its own: the lengths have
 * to agree within [MAX_DRIFT_SEC] when both are known, so a live take or a
 * remix under the same name is not played for the album cut.
 */
object OwnFileMatcher {

    /** BitChord Audio: two songs are only the same when they last the same (to 2 seconds). */
    private const val MAX_DRIFT_SEC = 2
    private const val UPLOADER_DRIFT_SEC = 2
    private val UPLOADER = Regex(
        """(\bchannel\b|vevo|\brecords?\b|\btopic\b|\bofficial\b|unknown artist|\bmusic\s+(japan|channel|entertainment)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val QUOTED = Regex("""[『「](.+?)[』」]""")
    private val SEPARATOR = Regex("""\s+[-–—/／|｜]\s+|\s*／\s*|\s*｜\s*""")
    private val NOTE = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")
    private val GUEST_ONLY = Regex("""^(feat\.?|ft\.?|featuring|con|with)\s.*""", RegexOption.IGNORE_CASE)
    private val GUEST = Regex("""\s+(feat\.?|ft\.?|featuring|con|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、|\band\b|\by\b|\bx\b|×)\s*""", RegexOption.IGNORE_CASE)

    /** Every name [title] could be the song under, as keys. */
    /** "~小さな羽根ひろげて~": a subtitle, often in another script than the file's. */
    private val TILDE = Regex("""\s*[~〜～][^~〜～]*[~〜～]\s*|\s*[~〜～].*$""")

    /** "11.LOVE 'n' HATE", "09.identity": a track number left in the tag. */
    private val TRACK_NO = Regex("""^\s*\d{1,3}\s*[.\-_)]\s*(?=\S)""")
    /** "Don´t You Want Me.Mp3": the file's extension left in the tag. */
    private val EXTENSION = Regex("""\.(mp3|flac|m4a|ogg|opus|wav|aac)$""", RegexOption.IGNORE_CASE)
    /** "Heaven's What I Feel (Corazon": a bracket the tag was cut off inside. */
    private val UNCLOSED = Regex("""\s*[(\[（【][^)\]）】]*$""")

    /**
     * Letters and digits, accents taken off Latin letters only — "Así" and
     * "Asi", "Llévame" and "Llevame" agree, while kana keep their dakuten.
     */
    fun key(s: String): String {
        keyMemo[s]?.let { return it }
        if (keyMemo.size > 50_000) keyMemo.clear()
        return keyOf(s).also { keyMemo[s] = it }
    }

    /** Names compared over and over (the Library tab's thousands of albums): normalized once. */
    private val keyMemo = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun keyOf(s: String): String {
        val out = StringBuilder()
        for (ch in Normalizer.normalize(s, Normalizer.Form.NFKC)) {
            val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            if (d[0] < '\u0250') d.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.forEach(out::append)
            else out.append(ch)
        }
        return out.toString().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }

    private fun cleaned(title: String): String = title.replace(TRACK_NO, "").replace(EXTENSION, "").trim()

    fun titleKeys(title: String): Set<String> {
        val t = cleaned(title)
        val bare = t.replace(TILDE, " ").trim()
        val cut = t.replace(UNCLOSED, "").trim()
        return keysOf(t) +
            (if (bare != t && bare.isNotBlank()) keysOf(bare) else emptySet()) +
            (if (cut != t && cut.isNotBlank()) keysOf(cut) else emptySet())
    }

    /** A tag cut off at ID3v1's 30 characters, or inside a bracket: matched by how it starts. */
    private fun truncatedKey(title: String): String? {
        if (title.length != 30 && !UNCLOSED.containsMatchIn(title)) return null
        if (UNCLOSED.containsMatchIn(title) && title.trimEnd().endsWith(")")) return null
        return key(cleaned(title).replace(UNCLOSED, "")).takeIf { it.length >= 10 }
    }

    /**
     * How the title sounds, as romaji keys ("~" + [JapaneseReading.soundKey]):
     * "暁の車" and "Akatsuki no Kuruma" give the same one. Empty while the
     * kanji dictionary is loading.
     */
    fun soundKeys(title: String): Set<String> {
        val bare = title.replace(TILDE, " ").replace(NOTE, " ").replace(GUEST, "").trim()
        val names = (listOf(bare) + bare.split(SEPARATOR) +
            NOTE.findAll(title).map { it.value.trim().trim('(', ')', '[', ']', '（', '）', '【', '】') }
                .filter { inner -> versionNotes("($inner)").isEmpty() })
            .map { it.trim() }.filter { it.isNotEmpty() }
        return names.mapNotNull { n -> JapaneseReading.romaji(n)?.let(JapaneseReading::soundKey) }
            .filter { it.length >= 4 }
            .map { "~$it" }
            .toSet()
    }

    internal fun allKeys(title: String): Set<String> {
        allKeysCache[title]?.let { return it }
        val out = titleKeys(title) + soundKeys(title)
        // Kept once the reading dictionary is loaded (before, the readings are missing).
        if (JapaneseReading.ready) {
            if (allKeysCache.size > 30_000) allKeysCache.clear()
            allKeysCache[title] = out
        }
        return out
    }

    /** A title's keys, worked out once: reading every word of it in the dictionary is slow. */
    private val allKeysCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** BitChord Audio: every key a song goes by — its title's, and its other names' (romaji tables). */
    fun wantedKeys(title: String, artist: String): Set<String> =
        (listOf(title) + LyricAliases.queries("", title, artist).map { it.title }).flatMap(::allKeys).toSet()

    private fun keysOf(title: String): Set<String> {
        val base = title.replace(NOTE, " ").replace(GUEST, "").trim()
        val names = buildList {
            add(base)
            QUOTED.findAll(title).forEach { add(it.groupValues[1]) }
            addAll(base.split(SEPARATOR))
            // "Uta Choufugetsu (歌鳥風月)": the original title kept in brackets,
            // unless the bracket only says which version it is.
            NOTE.findAll(title).map { it.value.trim().trim('(', ')', '[', ']', '（', '）', '【', '】').trim() }
                .filter { inner -> inner.isNotEmpty() && versionNotes("($inner)").isEmpty() && !GUEST_ONLY.matches(inner) }
                .forEach { add(it) }
        }
        return names.map { key(it) }.filter { it.length >= 2 }.toSet()
    }

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s.replace('$', 's'), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()

    /** The same person or group, in any order of names or with a member list around it. */
    private val artistNamesMemo = java.util.concurrent.ConcurrentHashMap<String, List<Set<String>>>()

    /**
     * BitChord Audio: an artist credit's names as word sets (a kanji name read as romaji too),
     * worked out once per credit — reading kanji takes the dictionary, and album pages and
     * the player weigh one artist against every artist of the library.
     */
    private fun artistNames(s: String): List<Set<String>> {
        artistNamesMemo[s]?.let { return it }
        if (artistNamesMemo.size > 20_000) artistNamesMemo.clear()
        val out = s.split(ARTIST_SPLIT).flatMap { part ->
            listOfNotNull(words(part), JapaneseReading.romajiWords(part))
        }.filter { it.isNotEmpty() }
        artistNamesMemo[s] = out
        return out
    }

    fun sameArtist(a: String, b: String): Boolean {
        // A name in kanji read as romaji words too: 森川美穂 = Miho Morikawa.
        val left = artistNames(a)
        val right = artistNames(b)
        for (x in left) for (y in right) {
            if (x == y) return true
            val shorter = if (x.size <= y.size) x else y
            val longer = if (shorter === x) y else x
            if (longer.containsAll(shorter) && shorter.joinToString("").length >= 4) return true
        }
        val ka = FileName.key(a.replace('$', 's'))
        val kb = FileName.key(b.replace('$', 's'))
        return ka.length >= 4 && kb.length >= 4 && (ka in kb || kb in ka)
    }

    @Volatile
    private var indexed: Pair<List<Song>, Map<String, List<Song>>>? = null

    /** Files whose title tag was cut off, by the key it starts with. */
    @Volatile
    private var truncated: List<Pair<String, Song>> = emptyList()

    /** The alias tables' version the index was built with: new names, new index. */
    @Volatile
    private var indexedNames = -1

    /** The library by every name its songs go by; rebuilt when the library changes. */
    private fun index(): Map<String, List<Song>> {
        val library = WebDavRepository.library()
        val names = LyricAliases.version.value * 2 + if (JapaneseReading.ready) 1 else 0
        indexed?.let { (of, map) -> if (of === library && indexedNames == names) return map }
        val map = HashMap<String, MutableList<Song>>()
        truncated = library.mapNotNull { s -> truncatedKey(s.title)?.let { it to s } }
        for (song in library) {
            val titles = LyricAliases.queries(song.videoId, song.title, song.artist).map { it.title } + song.title
            titles.flatMap(::allKeys).toSet().forEach { k -> map.getOrPut(k) { mutableListOf() } += song }
        }
        indexed = library to map
        indexedNames = names
        return map
    }

    /**
     * Your files that are the song [title] by [artist] of [durationSec],
     * best first: lossless, then by bitrate.
     */
    /**
     * Your files that are the song [title] by [artist] of [durationSec] on
     * [album], best first: the same length, then lossless, then by bitrate.
     *
     * In stages: files with the song's name (in any of its names); of those,
     * the ones whose length agrees; then confirmed by any of —
     *  1. the artist (in either name order, a member list, a near spelling:
     *     "Porno Graffitti" / "Porno Graffiti");
     *  2. the album, with the length to [CONFIRM_DRIFT_SEC] seconds — which is
     *     how "浜崎あゆみ" turns out to be "Ayumi Hamasaki", and the pair is
     *     remembered ([ArtistLinks]) for every other song of hers;
     *  3. an artist pair remembered that way;
     *  4. an upload credited to its channel, by the length to 2 seconds.
     */
    fun matches(title: String, artist: String, durationSec: Int?, album: String? = null): List<Song> {
        val map = index()
        if (map.isEmpty()) return emptyList()
        // The song's other names too: your romaji tables pair "星屑" with "Hoshikuzu".
        val wanted = (listOf(title) + LyricAliases.queries("", title, artist).map { it.title })
            .flatMap(::allKeys).toSet()
        val full = key(cleaned(title))
        val cutOff = truncated.filter { (k, _) -> full.startsWith(k) }.map { it.second }
        val found = (wanted.flatMap { map[it].orEmpty() } + cutOff).distinctBy { it.videoId }
        // An official video runs longer than the song (intro, outro, a skit):
        // a file up to a quarter shorter (at least a minute) is still it.
        val videoUpload = VIDEO_UPLOAD.containsMatchIn(title)
        return found
            .filter { file ->
                val got = file.durationMillis().takeIf { it > 0 }?.let { (it / 1000).toInt() }
                // A length not known on one side: only the same artist under
                // the same name counts (checked below), off the same release; no guessing past that.
                if (durationSec == null || got == null) {
                    return@filter sameArtist(artist, file.artist) && (album.isNullOrBlank() || sameAlbum(album, file.albumName))
                }
                val limit = if (videoUpload && got < durationSec && got >= durationSec * 6 / 10) {
                    maxOf(60, durationSec / 4)
                } else {
                    MAX_DRIFT_SEC
                }
                abs(durationSec - got) <= limit
            }
            .filter { file ->
                // The tags' artist, its aliases, and the artist folder you
                // filed it in (a stage name over a real name in the tags).
                val artists = listOf(file.artist) +
                    LyricAliases.queries(file.videoId, file.title, file.artist).map { it.artist } +
                    listOfNotNull(WebDavRepository.artistFolderOf(file.videoId))
                val drift = durationSec?.let { want ->
                    file.durationMillis().takeIf { it > 0 }?.let { abs(want - (it / 1000).toInt()) }
                }
                val close = drift != null && drift <= CONFIRM_DRIFT_SEC
                // BitChord Audio: an album with both artists on it, each
                // under their own name (a soundtrack, a split, a compilation),
                // says they are two artists — not one under two names. Two
                // songs there with the same title are two songs.
                val bothOnAlbum = !artists.any { sameArtist(artist, it) } && hasOwnTracksOn(artist, file.albumName)
                when {
                    artists.any { sameArtist(artist, it) } -> true
                    bothOnAlbum -> false
                    close && artists.any { nearSpelling(artist, it) } -> true
                    close && sameAlbum(album, file.albumName) -> {
                        // Not from a row that names nobody: "Unknown artist"
                        // linked to one real artist would match every song
                        // that lost its credit to that artist's files.
                        if (!TrackMatcher.isPlaceholderArtist(artist)) ArtistLinks.learn(artist, file.artist)
                        true
                    }
                    close && artists.any { ArtistLinks.linked(artist, it) } -> true
                    UPLOADER.containsMatchIn(artist) && drift != null && drift <= UPLOADER_DRIFT_SEC -> true
                    else -> false
                }
            }
            // A live take, a TV-size cut, a karaoke or remix isn't the song
            // asked for unless its title says so too.
            .filter { file -> VERSION_WORDS.none { it in versionNotes(file.title) && it !in versionNotes(title) } }
            // A live album, a tribute or covers album holds other recordings.
            .filter { file -> !otherRecording(title, album, file) }
            // BitChord Audio: and the other way round — a live (or acoustic, remix…) song asked
            // for is never the studio file, and one concert's take is not another's.
            .filter { file ->
                val wantedAlbum = album ?: if (VIDEO_UPLOAD.containsMatchIn(title)) null else album
                sameRecording(title, wantedAlbum, null, file.title, file.albumName, null, lengthsChecked = true) ||
                    // A row that names no album (a video upload) says nothing of a live release;
                    // its title still has to say the same kind.
                    (wantedAlbum.isNullOrBlank() && kindsOf(title, null) == kindsOf(file.title, null) && kindsOf(file.title, file.albumName).isEmpty())
            }
            // BitChord Audio: the best copy of the recording wins, whichever
            // release it is filed under (a "Complete Best" at 320 kbps over the
            // original album at 160) — the versions that are other recordings
            // are already out above. The length agreeing to the second or so
            // comes first (it is that recording), then quality; the same album
            // only breaks a tie; audio files ahead of video files.
            .sortedWith(
                compareByDescending<Song> { file ->
                    val got = file.durationMillis().takeIf { it > 0 }?.let { (it / 1000).toInt() }
                    if (durationSec == null || got == null || abs(durationSec - got) <= CONFIRM_DRIFT_SEC) 1 else 0
                }
                    .thenByDescending { qualityRank(it, durationSec) }
                    .thenByDescending { if (sameAlbum(album, it.albumName)) 1 else 0 }
                    .thenBy { if (isVideoFile(it)) 1 else 0 },
            )
    }

    private const val CONFIRM_DRIFT_SEC = 2

    // ── BitChord Audio: one rule for "the same recording", used everywhere ──────────
    // A title shared is not a song shared. What a release says it is — live (and which
    // live), acoustic, remix, karaoke, a TV-size cut, a cover — has to agree on both
    // sides, whichever side says it; and the lengths have to agree to 2 seconds.

    private val KINDS = listOf(
        "live" to Regex("""(?<!love )\blive\b|ライブ|ライヴ|\bin concert\b|\bconcert\b|コンサート|\ben vivo\b|\bdirecto\b|\bunplugged\b|\btour\b|ツアー|\bfes\.?\b|\bfestival\b|フェス|\bbudokan\b|武道館|\bsession\b"""),
        "tribute" to Regex("""\btribute\b|トリビュート"""),
        "cover" to Regex("""\bcovers?\b|カバー|カヴァー"""),
        "remix" to Regex("""\bremix(es)?\b|リミックス|\bre-?mix\b"""),
        "acoustic" to Regex("""\bacoustic\b|アコースティック"""),
        "karaoke" to Regex("""\bkaraoke\b|カラオケ|\binstrumental\b|\binst\.?\b|インスト|\boff vocal\b"""),
        "orchestra" to Regex("""\borchestra|\bsymphonic\b|オーケストラ|\bpiano (ver|collection)"""),
        "short" to Regex("""\btv\s*size\b|tvサイズ|ｔｖサイズ|テレビサイズ|\bshort ver|\bradio edit\b"""),
        "demo" to Regex("""\bdemo\b"""),
    )

    /** What kind of recording [title] off [album] is: "live", "remix"… — empty for the studio cut. */
    fun kindsOf(title: String, album: String?): Set<String> {
        // The title's bracketed notes and anything after a dash ("Song - Live at X"); the album whole.
        val notes = versionNotesRaw(title) + " " + (SEPARATOR.split(title).drop(1).joinToString(" "))
        val text = (notes + " " + album.orEmpty()).lowercase(Locale.ROOT)
        return KINDS.filter { (_, re) -> re.containsMatchIn(text) }.map { it.first }.toSet()
    }

    /**
     * Whether two copies are the same recording: the same kinds (see [kindsOf]); a live one
     * off the same live release (two concerts are two recordings); and, when both lengths
     * are known, within 2 seconds. A length unknown on a side needs the same release.
     * Titles and artists are the caller's to have matched.
     */
    fun sameRecording(
        titleA: String, albumA: String?, secondsA: Int?,
        titleB: String, albumB: String?, secondsB: Int?,
        lengthsChecked: Boolean = false,
    ): Boolean {
        val ka = kindsOf(titleA, albumA)
        val kb = kindsOf(titleB, albumB)
        if (ka != kb) return false
        if ("live" in ka && !sameAlbum(albumA, albumB)) return false
        if (lengthsChecked) return true
        if (secondsA != null && secondsB != null) return abs(secondsA - secondsB) <= MAX_DRIFT_SEC
        return sameAlbum(albumA, albumB) || (albumA.isNullOrBlank() && albumB.isNullOrBlank())
    }

    /** The bracketed notes of [title], as written (lower-cased). */
    private fun versionNotesRaw(title: String): String =
        Regex("""[(\[（【<＜]([^)\]）】>＞]*)[)\]）】>＞]""").findAll(title).joinToString(" ") { it.groupValues[1] } +
            " " + (UNCLOSED.find(title)?.value?.trim()?.drop(1) ?: "")

    /** Whether your library has songs by [artist] (under that name) on [album]. */
    private fun hasOwnTracksOn(artist: String, album: String?): Boolean {
        if (album.isNullOrBlank()) return false
        return WebDavRepository.library().any { f -> sameAlbum(album, f.albumName) && sameArtist(artist, f.artist) }
    }

    private val VIDEO_UPLOAD = Regex(
        """(official|oficial|music|lyric)\s*(music\s*)?(video|vídeo|clip|mv)|music\s*clip|\bm/?v\b|vídeo\s+oficial|video\s+oficial""",
        RegexOption.IGNORE_CASE,
    )

    /** "The Soul Extreme EP" / "The Soul Extreme EP(初回限定盤)": the same release. */
    /** An album name's key, its notes dropped — worked out once per name. */
    private val albumKeyMemo = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun albumKey(name: String): String {
        albumKeyMemo[name]?.let { return it }
        if (albumKeyMemo.size > 30_000) albumKeyMemo.clear()
        return key(name.replace(NOTE, " ")).also { albumKeyMemo[name] = it }
    }

    fun sameAlbum(a: String?, b: String?): Boolean {
        val x = a?.let(::albumKey).orEmpty()
        val y = b?.let(::albumKey).orEmpty()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        // BitChord Audio: a live release is never the studio one, however alike the names.
        if (isLiveRelease(a!!) != isLiveRelease(b!!)) return false
        if (minOf(x.length, y.length) < 5 || !(x in y || y in x)) return false
        // BitChord Audio: one name inside another is the same album only when what is left
        // is an edition ("… (Deluxe Edition)", "… Remastered 2019", "… Disc 2") — not
        // another album that starts the same ("Fiction" vs "FictionJunction 2010-2013 The BEST").
        val wa = albumWords(a!!)
        val wb = albumWords(b!!)
        if (hasCjk(a) || hasCjk(b)) {
            return minOf(x.length, y.length).toDouble() / maxOf(x.length, y.length) >= 0.6
        }
        val (short, long) = if (wa.size <= wb.size) wa to wb else wb to wa
        if (short.isEmpty()) return false
        val at = (0..long.size - short.size).firstOrNull { i -> long.subList(i, i + short.size) == short } ?: return false
        val rest = long.subList(0, at) + long.subList(at + short.size, long.size)
        return rest.all { it in EDITION || it.all(Char::isDigit) }
    }

    private fun albumWords(s: String) = s.replace(NOTE, " ").lowercase()
        .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    private val EDITION = setOf(
        "deluxe", "edition", "remaster", "remastered", "remasters", "version", "ver", "disc", "disk", "cd", "bonus",
        "track", "tracks", "special", "limited", "anniversary", "expanded", "edit", "original", "standard", "complete",
        "the", "a", "an", "de", "of", "ep", "single", "reissue", "mono", "stereo", "hi", "res", "explicit", "clean",
        "digital", "japan", "japanese", "international", "import", "type", "regular", "first", "press",
    )

    private val LIVE_RELEASE = Regex(
        """(?i)\blive\b|\bconcert\b|\bunplugged\b|\ben (vivo|directo)\b|\btour\b|ライブ|ライヴ|コンサート|公演|演唱会""",
    )

    /** BitChord Audio: whether an album (or a track) name says it is a live recording. */
    fun isLiveRelease(name: String): Boolean {
        liveMemo[name]?.let { return it }
        if (liveMemo.size > 30_000) liveMemo.clear()
        return LIVE_RELEASE.containsMatchIn(name).also { liveMemo[name] = it }
    }

    private val liveMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]""")
    private val cjkMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * BitChord Audio: whether a name is written in kanji, kana or hangul — worked out once
     * per name: the Library tab weighs every album of yours against every album it shows.
     */
    fun hasCjk(name: String): Boolean {
        cjkMemo[name]?.let { return it }
        if (cjkMemo.size > 30_000) cjkMemo.clear()
        return CJK.containsMatchIn(name).also { cjkMemo[name] = it }
    }

    /** One letter apart, more or less: at least 85% alike as letters and digits. */
    fun nearSpelling(a: String, b: String): Boolean {
        val x = FileName.key(a.replace('$', 's'))
        val y = FileName.key(b.replace('$', 's'))
        if (x.length < 5 || y.length < 5) return false
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length) >= 0.85
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Whether [file] is another recording than the song [title] from [album]:
     * its album is a live, tribute, cover, remix, acoustic or karaoke release
     * and the song's own title and album say nothing of the kind.
     */
    fun otherRecording(title: String, album: String?, file: Song): Boolean {
        val fileAlbum = file.albumName?.lowercase(Locale.ROOT) ?: return false
        val wanted = (title + " " + album.orEmpty()).lowercase(Locale.ROOT)
        return ALBUM_VERSION.any { it.containsMatchIn(fileAlbum) && !it.containsMatchIn(wanted) }
    }

    private val ALBUM_VERSION = listOf(
        """\blive\b|ライブ|ライヴ|\bin concert\b|\ben vivo\b|\bdirecto\b|\bunplugged\b""",
        """\btribute\b|トリビュート""",
        """\bcovers?\b|カバー|カヴァー""",
        """\bremix(es)?\b|リミックス""",
        """\bacoustic\b|アコースティック""",
        """\bkaraoke\b|カラオケ|\binstrumental\b|\boff vocal\b""",
        """\borchestra|\bsymphonic\b|オーケストラ|\bpiano (ver|collection)""",
    ).map { Regex(it) }

    private val VERSION_WORDS = listOf(
        "live", "tv", "size", "karaoke", "instrumental", "inst", "off vocal", "remix", "mix", "acoustic",
        "demo", "short", "ver", "version", "edit", "cover", "directo", "en vivo",
    )

    /** The bracketed notes of a title, lower-cased: "(TV Live)" → "tv live". */
    /** Japanese version notes, read as the words above: （さくらバージョン） is a "version". */
    private val JAPANESE_VERSION = listOf(
        "バージョン" to "version", "ヴァージョン" to "version", "ライブ" to "live", "カラオケ" to "karaoke",
        "インスト" to "instrumental", "リミックス" to "remix", "テレビサイズ" to "tv size", "TVサイズ" to "tv size",
        "ＴＶサイズ" to "tv size", "アコースティック" to "acoustic",
    )

    private fun versionNotes(title: String): String =
        (Regex("""[(\[（【<＜]([^)\]）】>＞]*)[)\]）】>＞]""").findAll(
            JAPANESE_VERSION.fold(title) { t, (jp, en) -> t.replace(jp, " $en ") },
        ).map { it.groupValues[1] } +
            // A bracket cut off by the tag still says what it is: "(Directo Pri".
            listOfNotNull(UNCLOSED.find(title)?.value?.trim()?.drop(1)))
            .joinToString(" ")
            .lowercase(Locale.ROOT)
            .let { notes -> Regex("""\b(${VERSION_WORDS.joinToString("|") { Regex.escape(it) }})\b""").findAll(notes).joinToString(" ") { it.value } }

    private fun isVideoFile(song: Song): Boolean {
        val ext = com.music.bitchord.data.webdav.WebDavConfig.fileUrlOf(song.videoId)
            ?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return ext in setOf("webm", "mp4", "mkv", "mov")
    }

    /**
     * Whether a file of yours should play for this song over YouTube's copy —
     * a downloaded one included — judged from what is known on the device:
     * the listener's pick, else the rule in Settings applied to the best
     * matching file's quality.
     */
    /** The file of yours to play for this song, or why there is none. */
    sealed interface Verdict
    data class Play(val file: Song) : Verdict
    data class Keep(val reason: String) : Verdict

    fun decide(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String? = null,
        videoId: String? = null,
        // BitChord Audio: YouTube won't play this song here — your file is the copy.
        youTubeRefused: Boolean = false,
    ): Verdict {
        // A copy picked by hand from another source holds without pCloud.
        (PreferredSource.get(title, artist, album) as? PreferredSource.Chosen)
            ?.takeUnless { NotSame.blocked(videoId, it.song.videoId) }?.let { return Play(it.song) }
        if (!WebDavRepository.isConfigured()) return Keep("pCloud not connected")
        val library = WebDavRepository.library()
        if (library.isEmpty()) return Keep("pCloud library not loaded yet")
        when (val choice = PreferredSource.get(title, artist, album)) {
            PreferredSource.YouTube -> if (!youTubeRefused) return Keep("you chose YouTube for this song")
            is PreferredSource.Chosen -> if (!NotSame.blocked(videoId, choice.song.videoId)) return Play(choice.song)
            is PreferredSource.File -> library.firstOrNull { it.videoId == choice.id }?.let { return Play(it) }
            null -> Unit
        }
        val pref = com.music.bitchord.data.settings.AppSettings.sourcePreference.value
        if (!youTubeRefused && pref == com.music.bitchord.data.settings.AppSettings.SOURCE_YOUTUBE_FIRST) return Keep("Settings: YouTube first")
        // Worked out before for this song (see SourceMap): no matching again.
        val known = videoId?.let(SourceMap::lookup)?.takeUnless { NotSame.blocked(videoId, it.file) }
            // A remembered "no file" is asked again when YouTube refuses the song.
            ?.takeUnless { youTubeRefused && it.file == null }
        val best = if (known != null) {
            known.file?.let(SourceMap::fileSong)
                ?: return Keep("no file of yours matches (${library.size} songs in your library)")
        } else {
            // BitChord Audio: never one you said is another song.
            matches(title, artist, durationSec, album).firstOrNull { !NotSame.blocked(videoId, it.videoId) }
                .also { found -> videoId?.let { SourceMap.record(it, found) } }
                ?: return Keep("no file of yours matches (${library.size} songs in your library)")
        }
        if (youTubeRefused || pref == com.music.bitchord.data.settings.AppSettings.SOURCE_OWN_FIRST) return Play(best)
        val rank = qualityRank(best, durationSec)
        // A better copy than your file, where one plays: JioSaavn's 320 kbps,
        // when it's on for playback and the map knows it has the song.
        val saavnPlays = SourceMap.saavnPlayable()
        val saavn = videoId?.let(SourceMap::entry)?.saavn
        if (rank < 9_000 && saavnPlays && saavn != null && SourceMap.SAAVN_KBPS > rank) {
            return Keep("JioSaavn has a better copy (${SourceMap.SAAVN_KBPS} kbps) than your file ($rank kbps)")
        }
        // Your file: lossless, at or above your threshold, or simply better than YouTube's.
        val youtube = RemoteLibrarySource.youTubeKbps(videoId)
        return if (rank >= RemoteLibrarySource.MIN_KBPS || rank > youtube) {
            Play(best)
        } else {
            Keep(
                "best file of yours is ${if (rank > 0) "$rank kbps" else "of unknown bitrate"}, " +
                    "not above YouTube's $youtube kbps",
            )
        }
    }

    fun beatsYouTube(title: String, artist: String, durationSec: Int?, album: String? = null, videoId: String? = null): Boolean =
        decide(title, artist, durationSec, album, videoId) is Play

    private val KBPS = Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE)

    /**
     * Lossless as 9000+, else the bitrate — from the tags as last read
     * ([com.music.bitchord.data.remote.RemoteTagStore]), not the library row,
     * which can predate the bitrate being read; else the file's size over its
     * length; else whatever the row's label says.
     */
    fun qualityRank(song: Song, durationSec: Int? = null): Int {
        if (song.sourceHiRes) return 10_000
        val url = com.music.bitchord.data.webdav.WebDavConfig.fileUrlOf(song.videoId)
        val tags = url?.let { com.music.bitchord.data.remote.RemoteTagStore.get(it, com.music.bitchord.data.remote.RemoteTagStore.Stamp(null, null)) }
        if (tags?.lossless == true) return 9_000
        tags?.kbps?.takeIf { it > 0 }?.let { return it }
        val q = song.sourceQuality.orEmpty()
        if (listOf("FLAC", "ALAC", "WAV", "AIFF").any { it in q.uppercase(Locale.ROOT) }) return 9_000
        KBPS.find(q)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        // Before the tags are read: the file's size (from the folder listing)
        // over the song's length — the file's own, else the song asked for.
        val size = url?.let { com.music.bitchord.data.remote.RemoteTagStore.sizeOf(it) ?: WebDavRepository.sizeOf(it) }
        val ms = tags?.durationMs?.takeIf { it > 1000 }
            ?: song.durationMillis().takeIf { it > 1000 }
            ?: durationSec?.takeIf { it > 0 }?.let { it * 1000L }
        if (size != null && ms != null) return (size * 8 / ms).toInt()
        return 0
    }
}
