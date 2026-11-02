package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.JapaneseReading
import com.music.bitchord.data.sources.SourceMap
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: where a download goes and what it is called.
 *
 *     Music/BitChord/<Artist>/<N. Title - Artist - Album>.ext
 *     Music/BitChord/<Artist>/<Album>/<N. Title - Artist - Album>.ext   (an album with more than 3 songs downloaded)
 *
 * A Japanese name is written in romaji with the Japanese after it in
 * brackets — "Hitomi no Juunin (瞳の住人)". The track number comes from the
 * song, your file's tags, or the album's track list; left out when none is
 * known. The cover is embedded in every file (Android doesn't let an app put
 * a picture file in the Music folder).
 */
object DownloadLayout {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_download_layout"
    private const val LAYOUT_VERSION = 1

    /** An album gets its own folder once more than this many of its songs are downloaded. */
    const val ALBUM_FOLDER_AFTER = 3

    private var prefs: SharedPreferences? = null
    private val trackNumbers = ConcurrentHashMap<String, Int>()
    private val albumTracks = ConcurrentHashMap<String, List<Song>>()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.all.forEach { (k, v) -> if (k.startsWith("n:") && v is Int) trackNumbers[k.removePrefix("n:")] = v }
    }

    class Place(val folder: String, val name: String)

    /** The place for [song], from what is known now (no lookups). */
    fun place(song: Song, extension: String, albumFolder: Boolean = albumFolderFor(song.artist, song.albumName, adding = 1)): Place {
        val artist = clean(display(song.artist)).ifEmpty { "Unknown Artist" }
        val album = song.albumName?.let { clean(display(it)) }?.takeIf { it.isNotEmpty() }
        val title = clean(display(song.title)).ifEmpty { song.videoId }
        val number = song.trackNumber ?: trackNumbers[song.videoId]
        val stem = buildString {
            if (number != null && number > 0) append(number).append(". ")
            append(title).append(" - ").append(artist)
            if (album != null) append(" - ").append(album)
        }
        val folder = if (albumFolder && album != null) "${artist.take(80)}/${album.take(80)}" else artist.take(80)
        return Place(folder, "${stem.take(MAX_STEM).trimEnd()}.$extension")
    }

    /** Whether [artist]'s [album] has more than [ALBUM_FOLDER_AFTER] songs downloaded, counting [adding] more. */
    fun albumFolderFor(artist: String, album: String?, adding: Int = 0): Boolean {
        if (album.isNullOrBlank()) return false
        val key = groupKey(artist, album)
        val count = Downloads.savedMetadataSnapshot().values
            .filter { groupKey(it.artist, it.albumName) == key }
            .distinctBy { it.uri }.size
        return count + adding > ALBUM_FOLDER_AFTER
    }

    private fun groupKey(artist: String, album: String?): String =
        norm(artist) + "\u0000" + norm(album.orEmpty())

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * The track number of [song] on its album: its own, your file's tag, or
     * its place in the album's track list on YouTube Music. Remembered.
     */
    suspend fun lookUpTrackNumber(song: Song): Int? {
        song.trackNumber?.let { return it }
        trackNumbers[song.videoId]?.let { return it }
        val found = runCatching {
            SourceMap.lookup(song.videoId)?.file?.let { SourceMap.fileSong(it) }?.trackNumber
                ?: song.albumId?.let { albumId ->
                    val tracks = albumTracks[albumId]
                        ?: YtMusicRepository.browseSongs(albumId).getOrNull()?.songs.orEmpty().also { albumTracks[albumId] = it }
                    val i = tracks.indexOfFirst { it.videoId == song.videoId }
                        .takeIf { it >= 0 }
                        ?: tracks.indexOfFirst { norm(it.title) == norm(song.title) }.takeIf { it >= 0 }
                    i?.let { tracks[it].trackNumber ?: (it + 1) }
                }
        }.getOrNull()
        if (found != null) {
            trackNumbers[song.videoId] = found
            prefs?.edit()?.putInt("n:${song.videoId}", found)?.apply()
        }
        return found
    }

    /** Romaji with the Japanese in brackets, for a name with Japanese in it. */
    fun display(text: String): String {
        val t = text.trim()
        if (!JapaneseReading.isJapanese(t)) return t
        // "瞳の住人 - Hitomi No Jyunin": the release already gives both.
        val parts = t.split(Regex("""\s+[-–—/]\s+"""))
        if (parts.size == 2) {
            val (a, b) = parts
            if (JapaneseReading.isJapanese(a) && !JapaneseReading.isJapanese(b)) return "$b ($a)"
            if (!JapaneseReading.isJapanese(a) && JapaneseReading.isJapanese(b)) return "$a ($b)"
        }
        val romaji = runCatching {
            JapaneseReading.tokenizerNow()
            JapaneseReading.romaji(t)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return t
        return "${titleCase(romaji)} ($t)"
    }

    private fun titleCase(s: String) = s.split(' ').joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun clean(raw: String): String = raw
        .replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.')

    private const val MAX_STEM = 150

    /**
     * Moves saved downloads to where [place] says they go now — all of them
     * once (the first run of this layout), then the songs of one album as it
     * passes [ALBUM_FOLDER_AFTER]. [only]: the artist and album to tidy.
     */
    suspend fun reorganise(context: Context, only: Pair<String, String?>? = null) {
        val firstRun = prefs?.getInt("version", 0) != LAYOUT_VERSION
        if (only == null && !firstRun) return
        val metas = Downloads.savedMetadataSnapshot().values
            .filter { only == null || groupKey(it.artist, it.albumName) == groupKey(only.first, only.second) }
            .distinctBy { it.uri }
        var moved = 0
        for (meta in metas) {
            val uri = Uri.parse(meta.uri)
            if (uri.lastPathSegment == "playlist.m3u8") continue
            val now = DownloadStore.placeOf(context, uri) ?: continue
            val ext = now.second.substringAfterLast('.', "m4a")
            val song = Song(videoId = meta.videoId, title = meta.title, artist = meta.artist, albumName = meta.albumName)
            val target = place(song, ext, albumFolderFor(meta.artist, meta.albumName))
            if (now.first == target.folder && now.second == target.name) continue
            val to = DownloadStore.move(context, uri, target.folder, target.name) ?: continue
            if (to != uri) Downloads.relocate(meta.uri, to.toString())
            moved++
        }
        if (only == null) prefs?.edit()?.putInt("version", LAYOUT_VERSION)?.apply()
        if (moved > 0) Log.d(TAG, "download layout: moved $moved files")
    }
}
