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

    private const val MAX_DRIFT_SEC = 15
    private const val UPLOADER_DRIFT_SEC = 2
    private val UPLOADER = Regex(
        """(\bchannel\b|vevo|\brecords?\b|\btopic\b|\bofficial\b|unknown artist|\bmusic\s+(japan|channel|entertainment)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val QUOTED = Regex("""[『「](.+?)[』」]""")
    private val SEPARATOR = Regex("""\s+[-–—/／|｜]\s+|\s*／\s*|\s*｜\s*""")
    private val NOTE = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")
    private val GUEST = Regex("""\s+(feat\.?|ft\.?|featuring|con|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、|\band\b|\by\b|\bx\b|×)\s*""", RegexOption.IGNORE_CASE)

    /** Every name [title] could be the song under, as keys. */
    /** "~小さな羽根ひろげて~": a subtitle, often in another script than the file's. */
    private val TILDE = Regex("""\s*[~〜～][^~〜～]*[~〜～]\s*|\s*[~〜～].*$""")

    fun titleKeys(title: String): Set<String> {
        val bare = title.replace(TILDE, " ").trim()
        return keysOf(title) + (if (bare != title && bare.isNotBlank()) keysOf(bare) else emptySet())
    }

    private fun keysOf(title: String): Set<String> {
        val base = title.replace(NOTE, " ").replace(GUEST, "").trim()
        val names = buildList {
            add(base)
            QUOTED.findAll(title).forEach { add(it.groupValues[1]) }
            addAll(base.split(SEPARATOR))
        }
        return names.map { FileName.key(it) }.filter { it.length >= 2 }.toSet()
    }

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s.replace('$', 's'), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()

    /** The same person or group, in any order of names or with a member list around it. */
    fun sameArtist(a: String, b: String): Boolean {
        val left = a.split(ARTIST_SPLIT).map(::words).filter { it.isNotEmpty() }
        val right = b.split(ARTIST_SPLIT).map(::words).filter { it.isNotEmpty() }
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

    /** The library by every name its songs go by; rebuilt when the library changes. */
    private fun index(): Map<String, List<Song>> {
        val library = WebDavRepository.library()
        indexed?.let { (of, map) -> if (of === library) return map }
        val map = HashMap<String, MutableList<Song>>()
        for (song in library) {
            val names = LyricAliases.queries(song.videoId, song.title, song.artist).map { it.title } + song.title
            names.flatMap(::titleKeys).toSet().forEach { k -> map.getOrPut(k) { mutableListOf() } += song }
        }
        indexed = library to map
        return map
    }

    /**
     * Your files that are the song [title] by [artist] of [durationSec],
     * best first: lossless, then by bitrate.
     */
    /**
     * Your files that are the song [title] by [artist] of [durationSec] on
     * [album], best first: lossless, then by bitrate.
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
        val found = titleKeys(title).flatMap { map[it].orEmpty() }.distinctBy { it.videoId }
        return found
            .filter { file ->
                val got = file.durationMillis().takeIf { it > 0 }?.let { (it / 1000).toInt() }
                durationSec == null || got == null || abs(durationSec - got) <= MAX_DRIFT_SEC
            }
            .filter { file ->
                val artists = listOf(file.artist) +
                    LyricAliases.queries(file.videoId, file.title, file.artist).map { it.artist }
                val drift = durationSec?.let { want ->
                    file.durationMillis().takeIf { it > 0 }?.let { abs(want - (it / 1000).toInt()) }
                }
                val close = drift != null && drift <= CONFIRM_DRIFT_SEC
                when {
                    artists.any { sameArtist(artist, it) } -> true
                    close && artists.any { nearSpelling(artist, it) } -> true
                    close && sameAlbum(album, file.albumName) -> {
                        ArtistLinks.learn(artist, file.artist)
                        true
                    }
                    artists.any { ArtistLinks.linked(artist, it) } -> true
                    UPLOADER.containsMatchIn(artist) && drift != null && drift <= UPLOADER_DRIFT_SEC -> true
                    else -> false
                }
            }
            // A live take, a TV-size cut, a karaoke or remix isn't the song
            // asked for unless its title says so too.
            .filter { file -> VERSION_WORDS.none { it in versionNotes(file.title) && it !in versionNotes(title) } }
            // The same album first, then quality; audio files ahead of video files.
            .sortedWith(
                compareByDescending<Song> { if (sameAlbum(album, it.albumName)) 1 else 0 }
                    .thenByDescending { qualityRank(it, durationSec) }
                    .thenBy { if (isVideoFile(it)) 1 else 0 },
            )
    }

    private const val CONFIRM_DRIFT_SEC = 3

    /** "The Soul Extreme EP" / "The Soul Extreme EP(初回限定盤)": the same release. */
    fun sameAlbum(a: String?, b: String?): Boolean {
        val x = a?.replace(NOTE, " ")?.let(FileName::key).orEmpty()
        val y = b?.replace(NOTE, " ")?.let(FileName::key).orEmpty()
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 5 && (x in y || y in x))
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

    private val VERSION_WORDS = listOf(
        "live", "tv", "size", "karaoke", "instrumental", "inst", "off vocal", "remix", "mix", "acoustic",
        "demo", "short", "ver", "version", "edit", "cover", "directo", "en vivo",
    )

    /** The bracketed notes of a title, lower-cased: "(TV Live)" → "tv live". */
    private fun versionNotes(title: String): String =
        Regex("""[(\[（【]([^)\]）】]*)[)\]）】]""").findAll(title)
            .joinToString(" ") { it.groupValues[1] }
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

    fun decide(title: String, artist: String, durationSec: Int?, album: String? = null): Verdict {
        if (!WebDavRepository.isConfigured()) return Keep("pCloud not connected")
        val library = WebDavRepository.library()
        if (library.isEmpty()) return Keep("pCloud library not loaded yet")
        when (val choice = PreferredSource.get(title, artist)) {
            PreferredSource.YouTube -> return Keep("you chose YouTube for this song")
            is PreferredSource.File -> library.firstOrNull { it.videoId == choice.id }?.let { return Play(it) }
            null -> Unit
        }
        val pref = com.music.bitchord.data.settings.AppSettings.sourcePreference.value
        if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_YOUTUBE_FIRST) return Keep("Settings: YouTube first")
        val all = matches(title, artist, durationSec, album)
        val best = all.firstOrNull() ?: return Keep("no file of yours matches (${library.size} songs in your library)")
        if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_OWN_FIRST) return Play(best)
        val rank = qualityRank(best, durationSec)
        return if (rank >= RemoteLibrarySource.MIN_KBPS) {
            Play(best)
        } else {
            Keep("best file of yours is ${if (rank > 0) "$rank kbps" else "of unknown bitrate"}, below ${RemoteLibrarySource.MIN_KBPS}")
        }
    }

    fun beatsYouTube(title: String, artist: String, durationSec: Int?, album: String? = null): Boolean {
        when (val choice = PreferredSource.get(title, artist)) {
            PreferredSource.YouTube -> return false
            is PreferredSource.File -> return true
            null -> Unit
        }
        val pref = com.music.bitchord.data.settings.AppSettings.sourcePreference.value
        if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_YOUTUBE_FIRST) return false
        val best = matches(title, artist, durationSec, album).firstOrNull() ?: return false
        if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_OWN_FIRST) return true
        return qualityRank(best, durationSec) >= RemoteLibrarySource.MIN_KBPS
    }

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
