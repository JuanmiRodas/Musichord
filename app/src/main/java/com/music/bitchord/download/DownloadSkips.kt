package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: "download only from" — the sources downloads may come from
 * (Settings → Downloads), and the report of songs skipped because none of
 * them has the song.
 */
object DownloadSkips {

    /** The choices, by the name stored in [AppSettings.downloadSources]. */
    enum class Choice(val label: String) {
        JIOSAAVN("JioSaavn"),
        MY_LIBRARY("My library (pCloud)"),
        YOUTUBE("YouTube"),
        OTHER("Addons & modules"),
    }

    /** Thrown when no allowed source has the song: it is skipped, not failed. */
    class Skipped(message: String) : IllegalStateException(message)

    data class Entry(val title: String, val artist: String, val album: String?, val reason: String, val at: Long)

    private var file: File? = null
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "download_skips.json")
        file = f
        _entries.value = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(o.getString("t"), o.getString("a"), o.optString("al").ifBlank { null }, o.getString("r"), o.getLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    /** No choice made: every source is allowed (as before). */
    val restricted: Boolean get() = AppSettings.downloadSources.value.isNotEmpty()

    fun allowed(choice: Choice): Boolean =
        AppSettings.downloadSources.value.let { it.isEmpty() || choice.name in it }

    fun choiceOf(kind: SourceKind?): Choice = when (kind) {
        SourceKind.JIOSAAVN -> Choice.JIOSAAVN
        SourceKind.MY_LIBRARY -> Choice.MY_LIBRARY
        SourceKind.YOUTUBE -> Choice.YOUTUBE
        else -> Choice.OTHER
    }

    /** Which choice a found stream belongs to: by the source that answered, else by its address. */
    fun choiceOf(stream: SourceStream): Choice {
        stream.sourceConfigId?.let { id -> SourceRegistry.config(id)?.kind?.let { return choiceOf(it) } }
        val url = stream.url
        val base = AppSettings.webdavUrl.value
        return when {
            "saavncdn" in url || "jiosaavn" in url -> Choice.JIOSAAVN
            WebDavConfig.isConfigured(base) && runCatching {
                java.net.URI(url).host == java.net.URI(WebDavConfig.normalizeUrl(base)).host
            }.getOrDefault(false) -> Choice.MY_LIBRARY
            "pcloud" in url || "webdav" in url -> Choice.MY_LIBRARY
            "googlevideo" in url || "youtube" in url -> Choice.YOUTUBE
            else -> Choice.OTHER
        }
    }

    fun allowedLabels(): String =
        AppSettings.downloadSources.value.mapNotNull { n -> Choice.entries.firstOrNull { it.name == n }?.label }.joinToString(", ")

    fun record(song: Song, reason: String) {
        val e = Entry(song.title, song.artist, song.albumName, reason, System.currentTimeMillis())
        _entries.value = (_entries.value.filterNot { it.title == e.title && it.artist == e.artist } + e).takeLast(2_000)
        save()
    }

    fun clear() {
        _entries.value = emptyList()
        save()
    }

    /** The report as CSV, for sharing or opening in a spreadsheet. */
    fun csv(): String = buildString {
        append("Title,Artist,Album,Reason\n")
        _entries.value.forEach { e ->
            append(listOf(e.title, e.artist, e.album.orEmpty(), e.reason).joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
            append('\n')
        }
    }

    private fun save() {
        val a = JSONArray()
        _entries.value.forEach { e ->
            a.put(JSONObject().apply {
                put("t", e.title); put("a", e.artist); e.album?.let { put("al", it) }; put("r", e.reason); put("at", e.at)
            })
        }
        runCatching { file?.writeText(a.toString()) }
    }
}
