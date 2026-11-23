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

/**
 * The year of albums no copy dates (YouTube Music's downloads carry none): MusicBrainz's first
 * release of that album by that artist. Kept in `<app data>/album-years.json`; asked once.
 */
object AlbumYears {
    private val file get() = File(Store.dir, "album-years.json")
    @Volatile private var cache: JSONObject? = null
    private fun db() = cache ?: runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()).also { cache = it }
    private fun key(artist: String, album: String) = Words.plain(artist) + "|" + Words.plain(album)

    fun of(artist: String, album: String): String? = db().optString(key(artist, album)).ifBlank { null }

    fun fill(albums: Collection<Pair<String, String>>, max: Int = 150): Int {
        val o = db(); var found = 0
        val todo = albums.distinctBy { key(it.first, it.second) }.filter { !o.has(key(it.first, it.second)) }.take(max)
        val until = System.currentTimeMillis() + 4 * 60_000
        Live.phase = "años de los álbumes (MusicBrainz)"; Live.total = todo.size; Live.done.set(0)
        for ((artist, album) in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = "$artist — $album"; Live.done.incrementAndGet(); Live.write()
            val base = album.replace(Regex("\\s*[(\\[].*$"), "").trim().ifBlank { album }
            val q = URLEncoder.encode("releasegroup:\"$base\" AND artist:\"${artist.substringBefore(" (").substringBefore(",")}\"", "UTF-8")
            val rg = AlbumArt.json("https://musicbrainz.org/ws/2/release-group/?query=$q&fmt=json&limit=5")?.optJSONArray("release-groups")
            val year = rg?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                .filter { it.optInt("score") >= 90 }.mapNotNull { it.optString("first-release-date").take(4).takeIf { y -> y.length == 4 } }.minOrNull()
            o.put(key(artist, album), year ?: "")
            if (year != null) found++
            if (Live.done.get() % 20 == 0) runCatching { file.writeText(o.toString(1)) }
        }
        runCatching { file.writeText(o.toString(1)) }
        return found
    }
}

/**
 * The best-ofs and compilations of an artist (or of a series' best-of found here), with their
 * track lists, from MusicBrainz: what lets the library keep a song once, in the biggest album it
 * is on, and still list it in its original album. Kept in `<app data>/compilations.json`, with
 * each compilation's front cover in `<app data>/compilation-covers/<id>/cover.jpg`.
 */
object Compilations {
    data class Track(val no: Int, val disc: Int, val title: String, val seconds: Double?)
    data class Comp(val id: String, val title: String, val year: String?, val tracks: List<Track>) {
        val coverFolder: String get() = File(coverRoot, id).path
    }

    private val file get() = File(Store.dir, "compilations.json")
    val coverRoot: File get() = File(Store.dir, "compilation-covers").apply { mkdirs() }
    @Volatile private var cache: JSONObject? = null
    private fun db() = cache ?: runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()).also { cache = it }

    fun of(name: String): List<Comp> {
        val a = db().optJSONArray(Words.plain(name)) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Comp(o.getString("id"), o.getString("title"), o.optString("year").ifBlank { null },
                o.getJSONArray("tracks").let { t -> (0 until t.length()).map { t.getJSONObject(it) }.map { Track(it.getInt("no"), it.optInt("disc", 1), it.getString("title"), it.optDouble("seconds").takeIf { s -> !s.isNaN() }) } })
        }
    }

    private fun tracksOf(release: JSONObject): List<Track> = buildList {
        val media = release.optJSONArray("media") ?: return@buildList
        for (m in 0 until media.length()) {
            val md = media.getJSONObject(m)
            val tr = md.optJSONArray("tracks") ?: continue
            for (k in 0 until tr.length()) tr.getJSONObject(k).let { t ->
                add(Track(t.optString("number").toIntOrNull() ?: t.optInt("position", k + 1), md.optInt("position", m + 1), t.optString("title"),
                    t.optLong("length", 0).takeIf { it > 0 }?.let { it / 1000.0 }))
            }
        }
    }

    private fun release(rgOrRelease: String, isGroup: Boolean): JSONObject? {
        val url = if (isGroup) "https://musicbrainz.org/ws/2/release?release-group=$rgOrRelease&inc=recordings&status=official&fmt=json&limit=5"
                  else "https://musicbrainz.org/ws/2/release/$rgOrRelease?inc=recordings&fmt=json"
        val j = AlbumArt.json(url) ?: return null
        if (!isGroup) return j
        val rs = j.optJSONArray("releases") ?: return null
        // The edition with the most tracks (a deluxe holds the plain one's songs too).
        return (0 until rs.length()).map { rs.getJSONObject(it) }.maxByOrNull { r -> tracksOf(r).size }
    }

    private fun cover(releaseId: String) {
        val dir = File(coverRoot, releaseId).apply { mkdirs() }
        val f = File(dir, "cover.jpg")
        if (f.isFile) return
        AlbumArt.fetch("https://coverartarchive.org/release/$releaseId/front-500", 300)?.takeIf { it.size > 3000 }?.let { f.writeBytes(it) }
    }

    /**
     * Looks up the compilations of [artists] (best-ofs of each) and the track lists of the
     * compilation-like albums in [albums] (title to whose they are), not asked yet.
     */
    fun fill(artists: Collection<String>, albums: Collection<Pair<String, String>>, minutes: Int = 6): Int {
        val o = db(); var found = 0
        val until = System.currentTimeMillis() + minutes * 60_000L
        val todoA = artists.distinctBy { Words.plain(it) }.filter { it.isNotBlank() && !o.has(Words.plain(it) + "#asked") }
        val todoB = albums.distinctBy { Words.plain(it.first) + "|" + Words.plain(it.second) }.filter { (title, who) -> !o.has(Words.plain(who) + "#albums") || !o.getJSONObject(Words.plain(who) + "#albums").has(Words.plain(title)) }
        Live.phase = "recopilatorios (MusicBrainz)"; Live.total = todoA.size + todoB.size; Live.done.set(0)
        fun save() = runCatching { file.writeText(o.toString()) }
        fun add(key: String, rel: JSONObject, title: String, year: String?) {
            val tracks = tracksOf(rel)
            if (tracks.size < 4) return
            val list = o.optJSONArray(key) ?: JSONArray().also { o.put(key, it) }
            val id = rel.optString("id")
            if ((0 until list.length()).any { list.getJSONObject(it).optString("id") == id }) return
            list.put(JSONObject().put("id", id).put("title", title).put("year", year ?: "")
                .put("tracks", JSONArray(tracks.map { JSONObject().put("no", it.no).put("disc", it.disc).put("title", it.title).put("seconds", it.seconds ?: JSONObject.NULL) })))
            runCatching { cover(id) }
            found++
        }
        for (a in todoA) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = a; Live.done.incrementAndGet(); Live.write()
            o.put(Words.plain(a) + "#asked", true)
            val name = a.substringBefore(" (").trim()
            val art = AlbumArt.json("https://musicbrainz.org/ws/2/artist/?query=${URLEncoder.encode("artist:\"$name\"", "UTF-8")}&fmt=json&limit=3")
                ?.optJSONArray("artists")?.let { x -> (0 until x.length()).map { x.getJSONObject(it) } }?.firstOrNull { it.optInt("score") >= 95 }
            if (!o.has(Words.plain(a))) o.put(Words.plain(a), JSONArray())
            if (art == null) { save(); continue }
            val rgs = AlbumArt.json("https://musicbrainz.org/ws/2/release-group?artist=${art.getString("id")}&type=compilation&fmt=json&limit=100")
                ?.optJSONArray("release-groups")?.let { x -> (0 until x.length()).map { x.getJSONObject(it) } }.orEmpty()
                .filter { it.optJSONArray("secondary-types")?.length() == 1 } // compilations only (not live, remix or soundtrack ones)
                .take(12)
            for (rg in rgs) {
                if (Live.stop || System.currentTimeMillis() > until) break
                release(rg.getString("id"), true)?.let { add(Words.plain(a), it, rg.optString("title"), rg.optString("first-release-date").take(4).ifBlank { null }) }
            }
            save()
        }
        for ((title, who) in todoB) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = "$who — $title"; Live.done.incrementAndGet(); Live.write()
            val asked = o.optJSONObject(Words.plain(who) + "#albums") ?: JSONObject().also { o.put(Words.plain(who) + "#albums", it) }
            asked.put(Words.plain(title), true)
            val hit = AlbumArt.json("https://musicbrainz.org/ws/2/release/?query=${URLEncoder.encode("release:\"${title.substringBefore(" (")}\"", "UTF-8")}&fmt=json&limit=3")
                ?.optJSONArray("releases")?.let { x -> (0 until x.length()).map { x.getJSONObject(it) } }?.firstOrNull { it.optInt("score") >= 95 }
            if (hit != null) release(hit.getString("id"), false)?.let { add(Words.plain(who), it, hit.optString("title"), hit.optString("date").take(4).ifBlank { null }) }
            save()
        }
        save()
        return found
    }
}
