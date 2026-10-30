package com.music.bitchord.data.lyrics

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: other names to look a song's lyrics up by.
 *
 * Lyrics services catalogue a song under one title — often the original
 * Japanese, Korean or Chinese one — while the library may show a romanized
 * title, a translation, a soundtrack track name or a YouTube upload's own
 * wording. Searching by the name on screen then finds nothing although the
 * lyrics exist. Two places supply the names that would:
 *
 *  - **The listener**, per song, from the lyrics' provider sheet ([setUser]).
 *    Saved on the device and tried *before* the shown name, since someone took
 *    the trouble to say this is what the song is called.
 *  - **The file's own tags** ([fromTags]): `ORIGINALTITLE`, `TITLE_ROMAJI` and
 *    the like, read by [RemoteTags][com.music.bitchord.data.remote.RemoteTags].
 *    Tried *after* the shown name.
 *
 * A song is keyed both by its id and by its normalized title and artist, so an
 * alias set on a pCloud file also covers the same song from YouTube.
 */
object LyricAliases {

    data class Names(val titles: List<String> = emptyList(), val artists: List<String> = emptyList()) {
        val isEmpty: Boolean get() = titles.isEmpty() && artists.isEmpty()
    }

    /** One lookup to try: a title and artist to search by. */
    data class Query(val title: String, val artist: String)

    private val user = ConcurrentHashMap<String, Names>()
    private val tags = ConcurrentHashMap<String, Names>()

    /**
     * Title → every name it goes by, from a romaji list ([importCsv]): keyed
     * by letters and digits only, so "Rokutousei no Yoru (六等星の夜)",
     * "Rokutousei no Yoru" and "六等星の夜" all find each other.
     */
    private val titleNames = ConcurrentHashMap<String, Set<String>>()

    private val _version = MutableStateFlow(0)

    /** Bumped when the listener's names change, so the open lyrics can look again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile
    private var file: File? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val f = File(context.filesDir, "lyric_aliases.json")
        file = f
        runCatching {
            if (!f.isFile) return@runCatching
            val root = JSONObject(f.readText())
            root.keys().forEach { key ->
                val o = root.getJSONObject(key)
                user[key] = Names(o.strings("titles"), o.strings("artists"))
            }
        }
    }

    fun fromTags(videoId: String, titles: List<String>, artists: List<String>) {
        if (titles.isEmpty() && artists.isEmpty()) return
        tags[videoId] = Names(titles.distinct(), artists.distinct())
    }

    /** What the listener set for this song, if anything. */
    fun userNames(videoId: String, title: String, artist: String): Names =
        user[videoId] ?: user[songKey(title, artist)] ?: Names()

    /** Saves the listener's names for a song; empty lists remove them. */
    fun setUser(videoId: String, title: String, artist: String, names: Names) {
        val cleaned = Names(
            names.titles.map(String::trim).filter(String::isNotEmpty).distinct(),
            names.artists.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
        val keys = listOf(videoId, songKey(title, artist))
        if (cleaned.isEmpty) keys.forEach(user::remove) else keys.forEach { user[it] = cleaned }
        save()
        _version.value += 1
        // BitChord Audio: these names follow you through pCloud.
        appContext?.let(com.music.bitchord.data.sync.SettingsSync::changed)
    }

    /**
     * The lookups to make for a song, in order: the listener's names, the shown
     * name, then the file's own alternatives. Each title is paired with each
     * artist it could go with; capped at [MAX_QUERIES] so a song with many
     * aliases doesn't turn into dozens of requests.
     */
    fun queries(videoId: String, title: String, artist: String): List<Query> {
        val mine = userNames(videoId, title, artist)
        val fromFile = tags[videoId] ?: Names()
        val out = LinkedHashSet<Query>()
        fun add(titles: List<String>, artists: List<String>) {
            for (t in titles) for (a in artists) out += Query(t, a)
        }
        // The listener's names first: theirs with theirs, then theirs with the shown artist.
        add(mine.titles, mine.artists.ifEmpty { listOf(artist) })
        add(mine.titles.ifEmpty { listOf(title) }, mine.artists)
        add(mine.titles, listOf(artist))
        out += Query(title, artist)
        add(fromFile.titles, fromFile.artists.ifEmpty { listOf(artist) })
        add(titleNames[titleKey(title)].orEmpty().filter { titleKey(it) != titleKey(title) }, listOf(artist))
        add(fromFile.titles, listOf(artist))
        add(listOf(title), fromFile.artists)
        return out.filter { it.title.isNotBlank() }.take(MAX_QUERIES)
    }

    private const val MAX_QUERIES = 6

    private fun titleKey(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * Reads a romaji list — a CSV with `japanese_title` and `romanized_title`
     * columns (others ignored) — into [titleNames]. Each row's names, and the
     * halves of "Romaji (原題)", become one set of names for the same title.
     */
    fun importCsv(text: String) {
        val rows = parseCsv(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase(Locale.ROOT) } ?: return
        val jp = header.indexOf("japanese_title")
        val ro = header.indexOf("romanized_title")
        if (jp < 0 || ro < 0) return
        var added = 0
        for (row in rows.drop(1)) {
            val names = listOfNotNull(row.getOrNull(jp), row.getOrNull(ro))
                .flatMap(::halves)
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .distinctBy(::titleKey)
            if (names.size < 2) continue
            val set = names.toSet()
            names.forEach { n ->
                val k = titleKey(n)
                if (k.isNotEmpty()) titleNames[k] = titleNames[k].orEmpty() + set
            }
            added++
        }
        if (added > 0) _version.value += 1
    }

    private val HALVES = Regex("""^(.*?)\s*[(（](.+)[)）]\s*$""")

    /** "Rokutousei no Yoru (六等星の夜)" → itself, "Rokutousei no Yoru", "六等星の夜". */
    private fun halves(name: String): List<String> {
        val m = HALVES.find(name) ?: return listOf(name)
        return listOf(name, m.groupValues[1], m.groupValues[2])
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString()
                    cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    private fun songKey(title: String, artist: String): String =
        "song:" + norm(title) + "|" + norm(artist)

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val root = JSONObject()
            user.forEach { (key, names) ->
                root.put(
                    key,
                    JSONObject().put("titles", JSONArray(names.titles)).put("artists", JSONArray(names.artists)),
                )
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun JSONObject.strings(k: String) =
        optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}
