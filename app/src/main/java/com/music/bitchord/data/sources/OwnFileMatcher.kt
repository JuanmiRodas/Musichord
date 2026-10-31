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

    private val QUOTED = Regex("""[『「](.+?)[』」]""")
    private val SEPARATOR = Regex("""\s+[-–—/／|｜]\s+|\s*／\s*|\s*｜\s*""")
    private val NOTE = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")
    private val GUEST = Regex("""\s+(feat\.?|ft\.?|featuring|con|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、|\band\b|\by\b|\bx\b|×)\s*""", RegexOption.IGNORE_CASE)

    /** Every name [title] could be the song under, as keys. */
    fun titleKeys(title: String): Set<String> {
        val base = title.replace(NOTE, " ").replace(GUEST, "").trim()
        val names = buildList {
            add(base)
            QUOTED.findAll(title).forEach { add(it.groupValues[1]) }
            addAll(base.split(SEPARATOR))
        }
        return names.map { FileName.key(it) }.filter { it.length >= 2 }.toSet()
    }

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
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
        val ka = FileName.key(a)
        val kb = FileName.key(b)
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
    fun matches(title: String, artist: String, durationSec: Int?): List<Song> {
        val map = index()
        if (map.isEmpty()) return emptyList()
        val found = titleKeys(title).flatMap { map[it].orEmpty() }.distinctBy { it.videoId }
        return found
            .filter { file ->
                val artists = listOf(file.artist) +
                    LyricAliases.queries(file.videoId, file.title, file.artist).map { it.artist }
                artists.any { sameArtist(artist, it) }
            }
            .filter { file ->
                val got = file.durationMillis().takeIf { it > 0 }?.let { (it / 1000).toInt() }
                durationSec == null || got == null || abs(durationSec - got) <= MAX_DRIFT_SEC
            }
            .sortedByDescending { qualityRank(it) }
    }

    /**
     * Whether a file of yours should play for this song over YouTube's copy —
     * a downloaded one included — judged from what is known on the device:
     * the listener's pick, else the rule in Settings applied to the best
     * matching file's quality.
     */
    fun beatsYouTube(title: String, artist: String, durationSec: Int?): Boolean {
        when (val choice = PreferredSource.get(title, artist)) {
            PreferredSource.YouTube -> return false
            is PreferredSource.File -> return true
            null -> Unit
        }
        val pref = com.music.bitchord.data.settings.AppSettings.sourcePreference.value
        if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_YOUTUBE_FIRST) return false
        val best = matches(title, artist, durationSec).firstOrNull() ?: return false
        if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_OWN_FIRST) return true
        return qualityRank(best) >= RemoteLibrarySource.MIN_KBPS
    }

    private val KBPS = Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE)

    private fun qualityRank(song: Song): Int {
        val q = song.sourceQuality.orEmpty()
        if (song.sourceHiRes) return 10_000
        if (listOf("FLAC", "ALAC", "WAV", "AIFF").any { it in q.uppercase(Locale.ROOT) }) return 9_000
        return KBPS.find(q)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
