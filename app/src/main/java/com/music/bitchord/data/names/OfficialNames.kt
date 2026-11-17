package com.music.bitchord.data.names

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: the app's own database of names — for every song title,
 * artist and album written in a non-Latin script, its official Latin name.
 *
 * Nothing about a song is changed: this is a layer beside the metadata. It
 * is shown next to the original ([DisplayNames]) and used as one more name
 * when looking for lyrics, videos and other versions of a song.
 *
 * Where a name comes from, best first:
 *  - [Source.MANUAL] — yours, from the song menu; never replaced;
 *  - [Source.MUSICBRAINZ] — an artist's Latin alias (or sort name), and the
 *    title of a track or album on a Latin-script release of the same record;
 *  - [Source.VGMDB] — the English or romaji names of anime/game releases;
 *  - [Source.WIKIDATA] — the English label of the artist;
 *  - [Source.AUTO] — a romanization of the original, shown marked "≈" until
 *    an official one turns up (it is looked for again after [RECHECK_MS]).
 *
 * Kept in the app's storage (`official_names.json`) and in the settings
 * snapshot that follows you through pCloud ([com.music.bitchord.data.sync.SettingsSync]).
 * Filled in the background, a song at a time, starting with your likes.
 */
object OfficialNames {

    enum class Kind { TITLE, ARTIST, ALBUM }

    enum class Source(val official: Boolean) { MANUAL(true), MUSICBRAINZ(true), VGMDB(true), WIKIDATA(true), AUTO(false) }

    data class Entry(val original: String, val latin: String, val source: Source, val checkedAt: Long)

    const val FILE = "official_names.json"
    private const val TAG = "BitChord"
    private const val RECHECK_MS = 30L * 24 * 3600 * 1000

    private val entries = ConcurrentHashMap<String, Entry>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Called whenever the database changes (the app redraws names and syncs). */
    @Volatile var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
        runCatching {
            val f = File(context.filesDir, FILE)
            if (!f.isFile) return@runCatching
            val root = JSONObject(f.readText())
            root.keys().forEach { key ->
                val o = root.getJSONObject(key)
                entries[key] = Entry(
                    original = o.optString("o"),
                    latin = o.optString("l"),
                    source = runCatching { Source.valueOf(o.optString("s")) }.getOrDefault(Source.AUTO),
                    checkedAt = o.optLong("t"),
                )
            }
        }
        DisplayNames.latin = { kind, original, artist ->
            val k = when (kind) {
                DisplayNames.Kind.TITLE -> Kind.TITLE
                DisplayNames.Kind.ARTIST -> Kind.ARTIST
                DisplayNames.Kind.ALBUM -> Kind.ALBUM
            }
            get(k, original, artist)?.takeIf { it.latin.isNotBlank() }?.let { DisplayNames.Latin(it.latin, !it.source.official) }
                // Nothing looked up yet: the romanization, marked, worked out aside and
                // shown once ready (a redraw), so drawing a list never waits on it.
                ?: AutoLatin.cached(original, artist)?.let { DisplayNames.Latin(it, guessed = true) }
        }
    }

    // ---- keys ------------------------------------------------------------------------

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun key(kind: Kind, original: String, artist: String?): String = when (kind) {
        Kind.ARTIST -> "a|" + norm(original)
        Kind.TITLE -> "t|" + norm(original) + "|" + norm(primaryArtist(artist.orEmpty()))
        Kind.ALBUM -> "l|" + norm(original) + "|" + norm(primaryArtist(artist.orEmpty()))
    }

    private val CREDIT_SPLIT = Regex("""\s*(?:,|、|&|＆| feat\. | ft\. | x | × |/)\s*""")

    fun primaryArtist(credit: String): String = CREDIT_SPLIT.split(credit).firstOrNull()?.trim().orEmpty()

    // ---- reading ---------------------------------------------------------------------

    fun get(kind: Kind, original: String, artist: String?): Entry? {
        entries[key(kind, original, artist)]?.let { return it }
        // A title or album not yet seen with this artist: the same name under any artist.
        if (kind != Kind.ARTIST) {
            val prefix = (if (kind == Kind.TITLE) "t|" else "l|") + norm(original) + "|"
            return entries.entries.firstOrNull { it.key.startsWith(prefix) && it.value.source.official }?.value
        }
        return null
    }

    /** Official Latin names for a song's title and artist, for searches (lyrics, videos, versions). */
    fun searchNames(title: String, artist: String): Pair<String?, String?> {
        val t = get(Kind.TITLE, title, artist)?.takeIf { it.source.official }?.latin
        val a = get(Kind.ARTIST, primaryArtist(artist), null)?.takeIf { it.source.official }?.latin
        return t to a
    }

    // ---- writing ----------------------------------------------------------------------

    fun put(kind: Kind, original: String, artist: String?, latin: String, source: Source) {
        val k = key(kind, original, artist)
        val had = entries[k]
        if (had?.source == Source.MANUAL && source != Source.MANUAL) return
        entries[k] = Entry(original, latin.trim(), source, System.currentTimeMillis())
        if (had?.latin != latin.trim() || had.source != source) changed()
    }

    /** Your own name for it; blank removes it (and the search runs again). */
    fun setManual(kind: Kind, original: String, artist: String?, latin: String) {
        val k = key(kind, original, artist)
        if (latin.isBlank()) entries.remove(k) else entries[k] = Entry(original, latin.trim(), Source.MANUAL, System.currentTimeMillis())
        changed()
    }

    private fun markChecked(kind: Kind, original: String, artist: String?) {
        val k = key(kind, original, artist)
        if (entries[k] == null) {
            // Nothing official: the romanization is kept, so this isn't asked again for a month.
            val auto = AutoLatin.of(original, artist)
            entries[k] = Entry(original, auto.orEmpty(), Source.AUTO, System.currentTimeMillis())
            changed()
        }
    }

    private fun due(kind: Kind, original: String, artist: String?): Boolean {
        val e = entries[key(kind, original, artist)] ?: return true
        return !e.source.official && System.currentTimeMillis() - e.checkedAt > RECHECK_MS
    }

    private fun changed() {
        _version.value++
        onChanged?.invoke()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(3_000)
            save()
        }
    }

    private fun save() {
        val context = app ?: return
        val root = JSONObject()
        entries.forEach { (k, e) ->
            root.put(k, JSONObject().put("o", e.original).put("l", e.latin).put("s", e.source.name).put("t", e.checkedAt))
        }
        runCatching {
            val f = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }
        com.music.bitchord.data.sync.SettingsSync.changed(context)
    }

    // ---- filling ------------------------------------------------------------------------

    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val queue = kotlinx.coroutines.channels.Channel<Song>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    @Volatile private var worker: Job? = null

    /** The song playing: looked up straight away, ahead of the queue. */
    fun now(song: Song) {
        if (!DisplayNames.needsLatin(song.title + song.artist + song.albumName.orEmpty())) return
        if (!queued.add(song.videoId.ifEmpty { song.title + "|" + song.artist })) return
        scope.launch { runCatching { fill(song) } }
    }

    /** Looks up the names of [songs] in the background (once each; your likes first). */
    fun enqueue(songs: List<Song>) {
        for (song in songs) {
            if (!DisplayNames.needsLatin(song.title + song.artist + song.albumName.orEmpty())) continue
            if (!queued.add(song.videoId.ifEmpty { song.title + "|" + song.artist })) continue
            queue.trySend(song)
        }
        if (worker?.isActive != true) {
            worker = scope.launch {
                for (song in queue) {
                    runCatching { fill(song) }.onFailure { TrackLog.w(TAG, "names: '${song.title}' failed: ${it.message}") }
                }
            }
        }
    }

    private suspend fun fill(song: Song) {
        val artist = primaryArtist(song.artist)
        // The artist.
        var artistLatin: String? = get(Kind.ARTIST, artist, null)?.takeIf { it.source.official }?.latin
        if (artist.isNotBlank() && DisplayNames.needsLatin(artist) && due(Kind.ARTIST, artist, null)) {
            val found = NameSources.artist(artist)
            if (found != null) {
                put(Kind.ARTIST, artist, null, found.first, found.second)
                artistLatin = found.first
            } else {
                markChecked(Kind.ARTIST, artist, null)
            }
        }
        // The title.
        if (DisplayNames.needsLatin(song.title) && due(Kind.TITLE, song.title, artist)) {
            val found = NameSources.title(song.title, artist, artistLatin, song.albumName)
            if (found != null) put(Kind.TITLE, song.title, artist, found.first, found.second) else markChecked(Kind.TITLE, song.title, artist)
        }
        // The album.
        val album = song.albumName?.takeIf { it.isNotBlank() }
        if (album != null && DisplayNames.needsLatin(album) && due(Kind.ALBUM, album, artist)) {
            val found = NameSources.album(album, artist, artistLatin)
            if (found != null) put(Kind.ALBUM, album, artist, found.first, found.second) else markChecked(Kind.ALBUM, album, artist)
        }
    }
}
