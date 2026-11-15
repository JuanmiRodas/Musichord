package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * BitChord Audio: a whole release linked to one of your pCloud folders, song
 * by song — each song then plays your file (see [PreferredSource.chooseFile]).
 *
 * The names rarely agree letter for letter: the release may title a song only
 * in Japanese while the file says "Himitsu - 秘密", in romaji, or with a track
 * number and a version note. So every song is weighed against every file of
 * the folder on several things at once — its names in both scripts (and how
 * they sound, through the romaji reading), a name contained in the other,
 * the track number, and the length — and each file goes to the song it fits
 * best, best pairs first.
 */
object AlbumFileLink {
    private const val TAG = "BitChord"

    /** A folder of your files, as offered for a release. */
    data class Folder(
        val url: String,
        val name: String,
        val files: List<Song>,
        /** How many of the release's songs it seems to hold. */
        val matching: Int,
    )

    data class Match(val song: Song, val file: Song?, val score: Int)

    private fun folderOf(file: Song): String? =
        WebDavConfig.fileUrlOf(file.videoId)?.substringBeforeLast('/')

    private fun nameOf(url: String): String = runCatching {
        URLDecoder.decode(url.substringAfterLast('/'), "UTF-8")
    }.getOrDefault(url.substringAfterLast('/'))

    private class Keys(val names: Set<String>, val sounds: Set<String>)

    private val fileKeys = HashMap<String, Keys>()

    private fun keysOfFile(file: Song): Keys = synchronized(fileKeys) {
        fileKeys.getOrPut(file.videoId) {
            val all = OwnFileMatcher.allKeys(file.title) +
                // A file named "Romaji - 日本語": both halves.
                file.title.split(Regex("\\s+[-–—/|｜]\\s+")).flatMap { OwnFileMatcher.allKeys(it) }
            Keys(all.filterNot { it.startsWith("~") }.toSet(), all.filter { it.startsWith("~") }.map { it.drop(1) }.toSet())
        }
    }

    private fun keysOfSong(song: Song): Keys {
        val all = OwnFileMatcher.wantedKeys(song.title, song.artist)
        return Keys(all.filterNot { it.startsWith("~") }.toSet(), all.filter { it.startsWith("~") }.map { it.drop(1) }.toSet())
    }

    private fun seconds(song: Song): Int? = song.durationMillis().takeIf { it > 0 }?.let { (it / 1000).toInt() }
        ?: song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.fold(0) { a, p -> a * 60 + p }?.takeIf { it > 0 }

    /** 0..1: how alike two strings are (edit distance over the longer one). */
    private fun alike(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    private fun score(song: Song, songKeys: Keys, number: Int?, file: Song): Int {
        val f = keysOfFile(file)
        var s = 0
        when {
            songKeys.names.any { it in f.names } || songKeys.sounds.any { it in f.sounds } -> s += 100
            else -> {
                val contained = songKeys.names.any { a -> a.length >= 4 && f.names.any { b -> b.length >= 4 && (a in b || b in a) } }
                if (contained) s += 70
                val heard = songKeys.sounds.maxOfOrNull { a -> f.sounds.maxOfOrNull { b -> alike(a, b) } ?: 0.0 } ?: 0.0
                val written = songKeys.names.maxOfOrNull { a -> f.names.maxOfOrNull { b -> alike(a, b) } ?: 0.0 } ?: 0.0
                val best = max(heard, written)
                if (best >= 0.6) s += (best * 80).toInt()
            }
        }
        if (number != null && file.trackNumber == number) s += 30
        val want = seconds(song)
        val got = seconds(file)
        if (want != null && got != null) {
            val d = abs(want - got)
            s += when {
                d <= 2 -> 35
                d <= 5 -> 20
                d <= 12 -> 0
                else -> -50
            }
        }
        return s
    }

    /** Each song its best file in [files] — no file twice, best pairs first. */
    fun pair(songs: List<Song>, files: List<Song>, numbers: Map<String, Int> = emptyMap()): List<Match> {
        val keys = songs.associate { it.videoId to keysOfSong(it) }
        val all = songs.withIndex().flatMap { (i, song) ->
            val n = numbers[song.videoId] ?: (i + 1)
            files.map { file -> Triple(song, file, score(song, keys.getValue(song.videoId), n, file)) }
        }.filter { it.third >= MIN_SCORE }.sortedByDescending { it.third }
        val taken = HashSet<String>()
        val chosen = HashMap<String, Match>()
        for ((song, file, s) in all) {
            if (song.videoId in chosen || file.videoId in taken) continue
            chosen[song.videoId] = Match(song, file, s)
            taken += file.videoId
        }
        return songs.map { chosen[it.videoId] ?: Match(it, null, 0) }
    }

    /**
     * Your folders that could be this release, best first: those holding the
     * most of its songs, then by name (the album's or the artist's).
     */
    fun folders(songs: List<Song>, albumTitle: String?, artist: String?): List<Folder> {
        val library = WebDavRepository.library()
        if (library.isEmpty()) return emptyList()
        val byFolder = library.groupBy { folderOf(it) }.filterKeys { it != null }.mapKeys { it.key!! }
        val wanted = songs.flatMap { keysOfSong(it).names }.toSet()
        val wantedSounds = songs.flatMap { keysOfSong(it).sounds }.toSet()
        val albumKeys = listOfNotNull(albumTitle).flatMap { OwnFileMatcher.allKeys(it) }.filter { it.length >= 3 }
        val artistKeys = listOfNotNull(artist).flatMap { a -> OwnFileMatcher.wantedKeys(a, a) }.filter { it.length >= 3 }
        val likely = byFolder.filter { (url, files) ->
            val name = OwnFileMatcher.key(nameOf(url) + " " + files.firstOrNull()?.albumName.orEmpty())
            files.any { f -> keysOfFile(f).let { k -> k.names.any { it in wanted } || k.sounds.any { it in wantedSounds } } } ||
                albumKeys.any { it in name } || artistKeys.any { it.removePrefix("~") in name }
        }
        return likely.map { (url, files) ->
            val pairs = pair(songs, files)
            Folder(url, nameOf(url), files.sortedWith(compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.title })), pairs.count { it.file != null && it.score >= 100 })
        }.sortedWith(compareByDescending<Folder> { it.matching }.thenBy { it.name }).take(30)
    }

    /** All your folders whose name holds [query] — for one the guess missed. */
    fun search(query: String, songs: List<Song>): List<Folder> {
        val q = OwnFileMatcher.key(query)
        if (q.length < 2) return emptyList()
        return WebDavRepository.library().groupBy { folderOf(it) }.filterKeys { it != null }.mapKeys { it.key!! }
            .filter { (url, files) ->
                OwnFileMatcher.key(url.substringAfterLast('/').let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } +
                    " " + files.firstOrNull()?.albumName.orEmpty() + " " + files.firstOrNull()?.artist.orEmpty()).contains(q)
            }
            .map { (url, files) ->
                Folder(url, nameOf(url), files.sortedWith(compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.title })),
                    pair(songs, files).count { it.file != null && it.score >= 100 })
            }
            .sortedByDescending { it.matching }
            .take(30)
    }

    /** Plays each paired file for its song from now on. */
    fun link(pairs: List<Match>) {
        var n = 0
        pairs.forEach { p ->
            val file = p.file ?: return@forEach
            PreferredSource.chooseFile(p.song, file)
            n++
        }
        TrackLog.i(TAG, "album linked to your files: $n of ${pairs.size} songs")
    }

    /** Takes the release's songs back to the usual rule. */
    fun unlink(songs: List<Song>) = songs.forEach { song ->
        if (PreferredSource.get(song) is PreferredSource.File) PreferredSource.clear(song)
    }

    private const val MIN_SCORE = 60
}
