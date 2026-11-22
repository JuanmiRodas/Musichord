package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * A series' openings and endings, in order, from MyAnimeList (through Jikan, free and without a
 * key): "One Piece" → Opening 1 "We Are!" by Hiroshi Kitadani, Ending 2 "RUN! RUN! RUN!"… For
 * every TV series whose name starts like the collection's ("Naruto", "Naruto: Shippuuden").
 * Kept in `<app data>/themes.json`; looked up once per collection.
 */
object Themes {
    data class Theme(val anime: String, val kind: String, val no: Int, val title: String, val artist: String)

    private val file get() = File(Store.dir, "themes.json")
    @Volatile private var cache: JSONObject? = null
    private fun db() = cache ?: runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()).also { cache = it }

    fun of(series: String): List<Theme> {
        val a = db().optJSONArray(Words.plain(series)) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Theme(it.getString("anime"), it.getString("kind"), it.getInt("no"), it.getString("title"), it.optString("artist")) }
    }

    private val LINE = Regex("^(?:#?(\\d+)\\s*:\\s*)?\"(.+?)\"\\s*(?:by\\s+(.+?))?\\s*(?:\\((?:eps?|episodes?)\\b[^)]*\\))?\\s*$")

    /** The song's name without what is said again in Japanese or between brackets: "We Are! (ウィーアー!)" → "we are". */
    fun core(t: String) = Words.plain(t.replace(Regex("[\\[(（【][^\\])）】]*[\\])）】]"), " ")
        .replace(Regex("(?iu)\\b(op|ed|opening|ending|tv size|full|version|ver)\\s*\\d*\\b"), " "))

    /** The theme a song is, by its title (the same words, or one name inside the other). */
    fun match(series: String, title: String): Theme? {
        val c = core(title)
        if (c.length < 3) return null
        val all = of(series)
        return all.firstOrNull { core(it.title) == c } ?: all.firstOrNull { val t = core(it.title); t.length >= 5 && c.length >= 5 && (c.startsWith(t) || t.startsWith(c)) }
    }

    /** Looks up the collections not asked yet (at most [max]); returns how many got themes. */
    fun fill(series: Collection<String>, max: Int = 40): Int {
        val o = db(); var found = 0
        val todo = series.distinct().filter { it.isNotBlank() && !o.has(Words.plain(it)) }.take(max)
        val until = System.currentTimeMillis() + 4 * 60_000
        Live.phase = "openings y endings (MyAnimeList)"; Live.total = todo.size; Live.done.set(0)
        for (s in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = s; Live.done.incrementAndGet(); Live.write()
            val list = JSONArray()
            val r = AlbumArt.json("https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(s, "UTF-8")}&type=tv&limit=10&order_by=members&sort=desc", 1200)
            val p = Words.plain(s)
            val shows = r?.optJSONArray("data")?.let { d -> (0 until d.length()).map { d.getJSONObject(it) } }.orEmpty()
                .filter { a -> listOf(a.optString("title"), a.optString("title_english")).any { Words.plain(it).startsWith(p) } }
                .sortedBy { it.optJSONObject("aired")?.optString("from").orEmpty() }.take(4)
            for (a in shows) {
                val name = a.optString("title_english").takeIf { it.isNotBlank() && Words.plain(it).startsWith(p) } ?: a.optString("title")
                val t = AlbumArt.json("https://api.jikan.moe/v4/anime/${a.optInt("mal_id")}/themes", 1200)?.optJSONObject("data") ?: continue
                for ((key, kind) in listOf("openings" to "Openings", "endings" to "Endings")) {
                    val arr = t.optJSONArray(key) ?: continue
                    for (k in 0 until arr.length()) {
                        val m = LINE.find(arr.getString(k).trim()) ?: continue
                        list.put(JSONObject().put("anime", name.replace(Regex("\\s*[:：]\\s*"), " ").trim()).put("kind", kind)
                            .put("no", m.groupValues[1].toIntOrNull() ?: (k + 1)).put("title", m.groupValues[2].trim())
                            .put("artist", m.groupValues[3].replace(Regex("\\s*\\([^)]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)]*\\)"), "").trim()))
                    }
                }
            }
            o.put(p, list)
            if (list.length() > 0) found++
        }
        runCatching { file.writeText(o.toString(1)) }
        return found
    }
}

/**
 * Latin names of albums known here only in Japanese ("美少女戦士セーラームーンR 〜乙女の詩集〜" → its
 * romaji or English name), from VGMdb: the album whose Japanese name is that one. Kept in
 * `<app data>/album-names.json`; looked up once per name.
 */
object AlbumNames {
    private val file get() = File(Store.dir, "album-names.json")
    @Volatile private var cache: JSONObject? = null
    private fun db() = cache ?: runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()).also { cache = it }
    private val CJK = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]")

    fun of(album: String): String? = db().optString(Words.nfc(album)).ifBlank { null }

    fun fill(albums: Collection<String>, max: Int = 150): Int {
        val o = db(); var found = 0
        val todo = albums.map { Words.nfc(it.trim()) }.distinct().filter { it.isNotBlank() && !o.has(it) }.take(max)
        val until = System.currentTimeMillis() + 4 * 60_000
        Live.phase = "nombres de álbumes (VGMdb)"; Live.total = todo.size; Live.done.set(0)
        for (a in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = a; Live.done.incrementAndGet(); Live.write()
            if (Live.done.get() % 20 == 0) runCatching { file.writeText(o.toString(1)) }
            val q = a.replace(Regex("[〜～~「」『』【】]"), " ").replace(Regex("\\s+"), " ").trim()
            val res = AlbumArt.json("https://vgmdb.info/search/albums/${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}?format=json", 1500)
                ?.optJSONObject("results")?.optJSONArray("albums")
            val want = Words.plain(q)
            val hit = res?.let { r -> (0 until r.length()).map { r.getJSONObject(it) } }.orEmpty().firstOrNull { x ->
                val t = x.optJSONObject("titles")
                Words.plain(t?.optString("ja").orEmpty().replace(Regex("[〜～~「」『』【】]"), " ")) == want
            }?.optJSONObject("titles")
            val latin = hit?.let { t -> listOf(t.optString("en"), t.optString("ja-latn")).firstOrNull { it.isNotBlank() && !CJK.containsMatchIn(it) } }
            o.put(a, latin ?: "")
            if (latin != null) found++
        }
        runCatching { file.writeText(o.toString(1)) }
        return found
    }
}
