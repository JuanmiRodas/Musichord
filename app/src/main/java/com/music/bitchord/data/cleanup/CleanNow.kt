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
        job?.cancel()
        _session.value = Session(song)
        if (!Gemini.ready) {
            _session.value = Session(song, working = false, failed = "El asistente necesita una clave de IA (Ajustes → Limpiar la biblioteca de pCloud).")
            return
        }
        job = scope.launch {
            val p = runCatching { propose(song) }.onFailure { TrackLog.w(TAG, "clean now: '${song.title}' failed: ${it.message}") }.getOrNull()
            Gemini.activity(null)
            finishSteps(p != null)
            _session.value = _session.value?.copy(
                proposal = p,
                working = false,
                failed = if (p == null) (Gemini.lastError.value ?: "No he podido identificar la canción.") else null,
            )
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
                val p = runCatching { propose(s, albumHint = "$title — $artist") }.getOrNull()
                if (p != null && p.confidence >= 0.6) { runCatching { write(s, p) }; done++ }
            }
            toast(if (done > 0) "«$title»: $done canciones arregladas." else (Gemini.lastError.value?.let { "Gemini: $it" } ?: "No he podido con «$title»."))
        }
    }

    /** What the assistant proposes for one song. */
    data class Proposal(
        val title: String,
        val artist: String,
        val album: String?,
        val albumArtist: String,
        val year: String?,
        val track: String?,
        val usedIn: String?,
        val live: Boolean,
        val cover: String?,
        val reason: String?,
        val confidence: Double,
    )

    // ── The window that shows it working ──

    enum class StepState { RUNNING, DONE, FAILED }
    data class Step(val text: String, val state: StepState)
    data class Session(
        val song: Song,
        val steps: List<Step> = emptyList(),
        val proposal: Proposal? = null,
        val working: Boolean = true,
        val applied: Boolean = false,
        val failed: String? = null,
    )

    private val _session = kotlinx.coroutines.flow.MutableStateFlow<Session?>(null)
    val session: kotlinx.coroutines.flow.StateFlow<Session?> = _session
    @Volatile private var job: kotlinx.coroutines.Job? = null

    private fun step(text: String) {
        val s = _session.value ?: return
        _session.value = s.copy(steps = s.steps.map { if (it.state == StepState.RUNNING) it.copy(state = StepState.DONE) else it } + Step(text, StepState.RUNNING))
    }

    private fun finishSteps(ok: Boolean) {
        val s = _session.value ?: return
        _session.value = s.copy(steps = s.steps.map { if (it.state == StepState.RUNNING) it.copy(state = if (ok) StepState.DONE else StepState.FAILED) else it })
    }

    fun close() {
        job?.cancel()
        _session.value = null
        Gemini.activity(null)
    }

    /** Your edits to the proposal, before applying it. */
    fun edit(p: Proposal) {
        _session.value = _session.value?.copy(proposal = p)
    }

    /** Applies the proposal on screen (as you left it). */
    fun apply() {
        val s = _session.value ?: return
        val p = s.proposal ?: return
        _session.value = s.copy(working = true)
        job = scope.launch {
            runCatching { write(s.song, p) }.onFailure { TrackLog.w(TAG, "clean now: writing failed: ${it.message}") }
            finishSteps(true)
            _session.value = _session.value?.copy(working = false, applied = true)
        }
    }

    /** One song, its proposal (null when it could not tell). */
    private suspend fun propose(song: Song, albumHint: String? = null): Proposal? {
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
        step("Preguntando a la IA qué canción es (estudio o directo, dónde se usó)…")
        val o = Gemini.ask(prompt, null, null) ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val artist = o.optString("artist").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val album = o.optString("album").takeIf { it.isNotBlank() && it != "null" }
        val year = o.optString("year").takeIf { Regex("""\d{4}""").matches(it) }
        val track = o.optInt("track", 0).takeIf { it > 0 }?.toString()
        val usedIn = o.optString("usedIn").takeIf { it.isNotBlank() && it != "null" }
        val albumArtist = o.optString("albumArtist").takeIf { it.isNotBlank() && it != "null" } ?: artist.split(",", "&").first().trim()

        // What it has so far, on screen while the cover is looked for.
        val partial = Proposal(title, artist, album, albumArtist, year, track, usedIn, o.optBoolean("live", false), null,
            o.optString("reason").takeIf { it.isNotBlank() && it != "null" }, o.optDouble("confidence", 0.0))
        _session.value?.takeIf { it.song.videoId == song.videoId }?.let { _session.value = it.copy(proposal = partial) }
        step("Buscando la portada a su mayor tamaño…")
        // The cover at its best: YouTube Music's album art at 1200 px, else the web's, else Apple's.
        val cover = album?.let { a ->
            YtMusicRepository.search("$a $artist", SearchFilter.ALBUMS).getOrNull().orEmpty()
                .filterIsInstance<SearchResult.Browse>()
                .firstOrNull { it.item.type == BrowseType.ALBUM && OwnFileMatcher.sameAlbum(it.item.title, a) }
                ?.item?.thumbnailUrl?.let(LibraryCleanup::bigCover)
        } ?: o.optString("coverUrl").takeIf { it.startsWith("http") }
            ?: album?.let { LibraryCleanup.itunesCover(artist, it) }

        return Proposal(title, artist, album, albumArtist, year, track, usedIn, o.optBoolean("live", false), cover,
            o.optString("reason").takeIf { it.isNotBlank() && it != "null" }, o.optDouble("confidence", 0.0))
    }

    /** Writes [p] for [song]: as shown in the app, its pCloud file, its download. A line saying what changed. */
    private suspend fun write(song: Song, p: Proposal): String {
        val title = p.title; val artist = p.artist; val album = p.album; val albumArtist = p.albumArtist
        val year = p.year; val track = p.track; val usedIn = p.usedIn; val cover = p.cover
        step("Guardando los datos en la app…")
        // As it is shown, everywhere.
        val edit = MetadataEdits.Edit(title = title, artist = artist, album = album, albumArtist = albumArtist, year = year, track = track, comment = usedIn)
        MetadataEdits.set(listOf(song), edit)
        cover?.let { CoverOverrides.set(listOf(song.copy(title = title, artist = artist, albumName = album ?: song.albumName)), it) }

        step("Escribiéndolo en tu archivo de pCloud y en la descarga…")
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
        val live = p.live
        TrackLog.d(TAG, "clean now: '${song.title}' → '$title' by $artist${album?.let { " ($it)" } ?: ""}${if (live) " · live" else ""}${usedIn?.let { " · $it" } ?: ""}")
        return buildString {
            append("«$title» — $artist")
            album?.let { append(" · $it") }
            year?.let { append(" ($it)") }
            usedIn?.let { append(" · $it") }
        }
    }
}
