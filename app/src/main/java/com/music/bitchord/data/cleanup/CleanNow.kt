package com.music.bitchord.data.cleanup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.covers.CoverOverrides
import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.edits.MetadataEdits
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BitChord Audio: "Clean with the assistant" from a song's or an album's menu — now,
 * whatever the hour. For a song, Gemini works out from the web what it really is: the
 * studio recording or a live one (then "Title (Live at the venue or show, year)"), its
 * real artist, album, year and track, the cover at its best size, and where it was used
 * (an anime's opening or ending and its number, a game, a series) — written into the
 * song as it is shown, its pCloud file and its download (in the comment for the last).
 * For an album, each of your pCloud folders in it is tidied as a whole, and the rest
 * song by song.
 */
object CleanNow {

    private const val TAG = "BitChord"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun toast(text: String) {
        val c = app ?: return
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, text, Toast.LENGTH_LONG).show() }
    }

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    fun song(song: Song) {
        if (!Gemini.ready) { toast("El asistente necesita una clave de Gemini (Ajustes → Limpiar la biblioteca)."); return }
        toast("El asistente está mirando «${song.title}»…")
        scope.launch {
            val msg = runCatching { clean(song) }.onFailure { TrackLog.w(TAG, "clean now: '${song.title}' failed: ${it.message}") }.getOrNull()
            toast(msg ?: (Gemini.lastError.value?.let { "Gemini: $it" } ?: "No he podido identificar «${song.title}»."))
        }
    }

    fun album(title: String, artist: String, songs: List<Song>) {
        if (!Gemini.ready) { toast("El asistente necesita una clave de Gemini (Ajustes → Limpiar la biblioteca)."); return }
        toast("El asistente está mirando «$title»…")
        scope.launch {
            var done = 0
            // Your pCloud folders in it: tidied whole.
            val files = songs.filter { WebDavConfig.isWebDavId(it.videoId) }
            val dirs = files.mapNotNull { WebDavConfig.fileUrlOf(it.videoId)?.substringBeforeLast('/') }.toSet()
            if (dirs.isNotEmpty()) {
                val groups = LibraryCleanup.groups().filter { it.key in dirs }
                for (g in groups) {
                    val p = runCatching { LibraryCleanup.proposeFor(g, listOfNotNull("$artist — $title", Assistant.hintFor(g.path)).joinToString(". ")) }.getOrNull() ?: continue
                    runCatching { LibraryCleanup.write(g, p, convert = false) }
                    LibraryCleanup.markDone(g.key, "asked")
                    done += g.files.size
                }
            }
            // The rest, one by one.
            for (s in songs.filterNot { WebDavConfig.isWebDavId(it.videoId) }.take(40)) {
                if (runCatching { clean(s, albumHint = "$title — $artist") }.getOrNull() != null) done++
            }
            toast(if (done > 0) "«$title»: $done canciones arregladas." else (Gemini.lastError.value?.let { "Gemini: $it" } ?: "No he podido con «$title»."))
        }
    }

    /** One song: a line for the toast when it was fixed, else null. */
    private suspend fun clean(song: Song, albumHint: String? = null): String? {
        val shown = MetadataEdits.applied(song) ?: song
        val prompt = """
Identify this track in a music library, searching the web (Discogs, VGMdb, MusicBrainz, Wikipedia, anime and game wikis, lyrics sites, Spotify, Apple Music):
title: "${shown.title}"
artist / channel: "${shown.artist}"
album: "${shown.albumName ?: "?"}"
length: ${shown.durationText ?: "?"}
${if (song.isVideo) "It is a YouTube video (maybe a TV appearance or a concert)." else ""}
${albumHint?.let { "It is on the album $it." } ?: ""}
Decide whether this is the STUDIO recording or a LIVE performance (concert, TV show, live album): use the title, the album name, the length and the channel. If live, the title must say where: "Title (Live at <venue or TV show>, <year>)" — only what you can confirm.
Find also where the song was used: anime openings/endings with their number (e.g. "One Piece — opening 1"), video games, TV series, films, commercials.
Answer ONLY a JSON object:
{"live": true/false,
 "title": "official title (romanized/Latin when it has one), with the live note if live",
 "artist": "the real performing artist(s)", "album": "the release (live album for a live take) or null", "albumArtist": "...",
 "year": "YYYY or null", "track": number or null,
 "usedIn": "where it was used, short, comma-separated, or null",
 "coverUrl": "direct URL of the release's cover at the largest size you can find, or null",
 "confidence": 0.0-1.0, "reason": "one short sentence in Spanish"}
""".trimIndent()
        val o = Gemini.ask(prompt, null, null) ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val artist = o.optString("artist").takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (o.optDouble("confidence", 0.0) < 0.6) return null
        val album = o.optString("album").takeIf { it.isNotBlank() && it != "null" }
        val year = o.optString("year").takeIf { Regex("""\d{4}""").matches(it) }
        val track = o.optInt("track", 0).takeIf { it > 0 }?.toString()
        val usedIn = o.optString("usedIn").takeIf { it.isNotBlank() && it != "null" }
        val albumArtist = o.optString("albumArtist").takeIf { it.isNotBlank() && it != "null" } ?: artist.split(",", "&").first().trim()

        // The cover at its best: YouTube Music's album art at 1200 px, else the web's, else Apple's.
        val cover = album?.let { a ->
            YtMusicRepository.search("$a $artist", SearchFilter.ALBUMS).getOrNull().orEmpty()
                .filterIsInstance<SearchResult.Browse>()
                .firstOrNull { it.item.type == BrowseType.ALBUM && OwnFileMatcher.sameAlbum(it.item.title, a) }
                ?.item?.thumbnailUrl?.let(LibraryCleanup::bigCover)
        } ?: o.optString("coverUrl").takeIf { it.startsWith("http") }
            ?: album?.let { LibraryCleanup.itunesCover(artist, it) }

        // As it is shown, everywhere.
        val edit = MetadataEdits.Edit(title = title, artist = artist, album = album, albumArtist = albumArtist, year = year, track = track, comment = usedIn)
        MetadataEdits.set(listOf(song), edit)
        cover?.let { CoverOverrides.set(listOf(song.copy(title = title, artist = artist, albumName = album ?: song.albumName)), it) }

        // Into the files: its pCloud file(s) and its download.
        val context = app
        if (context != null) {
            val image = cover?.let { runCatching { CoverWriter.fetch(it) }.getOrNull() }
            val change = TagWriter.Change(
                text = CoverTags.Text(title = title, artist = artist, album = album, albumArtist = albumArtist, year = year, track = track),
                cover = image,
                comment = usedIn,
            )
            val pcloud = if (WebDavConfig.isWebDavId(song.videoId)) listOf(song.videoId)
            else runCatching { com.music.bitchord.data.covers.CoverSources.filesOf(song).map { it.videoId } }.getOrDefault(emptyList()).take(1)
            pcloud.forEach { id -> TrackLog.d(TAG, "clean now: '$title' into the pCloud file — ${TagWriter.intoPcloud(context, id, change)}") }
            (song.localUri ?: Downloads.verifiedSavedUri(song.videoId))?.let { uri ->
                TrackLog.d(TAG, "clean now: '$title' into the download — ${TagWriter.intoDownload(context, uri, change)}")
            }
        }
        val live = o.optBoolean("live", false)
        TrackLog.d(TAG, "clean now: '${song.title}' → '$title' by $artist${album?.let { " ($it)" } ?: ""}${if (live) " · live" else ""}${usedIn?.let { " · $it" } ?: ""}")
        return buildString {
            append("«$title» — $artist")
            album?.let { append(" · $it") }
            year?.let { append(" ($it)") }
            usedIn?.let { append(" · $it") }
        }
    }
}
