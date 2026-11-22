package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Album information and pictures (covers, back, booklet scans, discs, obi…) for the albums of
 * the library with 5 songs or more, from free sources:
 *  - pocketmonsters.net (Pokémon CDs: full scans and track lengths),
 *  - canta-per-me.net (Yuki Kajiura and her projects),
 *  - VGMdb (anime, games; through vgmdb.info's JSON),
 *  - MusicBrainz + Cover Art Archive (everything else).
 * A release is taken only when it is surely the same album: most of our songs (80 %, at least
 * 5) have a track of the same length (±2 s) on it. Then its pictures are saved in the album's
 * folder, `Imágenes/`, with `album.json` (source, catalog number, date, tracks, credits); nothing
 * already there is overwritten. Doubtful albums are listed, nothing is downloaded for them.
 * State in `<library>/.bitchord-library/albums.json`: each album is looked up once (failures
 * of a source are retried after 7 days).
 */
object AlbumArt {
    data class Album(val dir: File, val rel: String, val title: String, val artist: String, val year: String?, val lengths: List<Double>, val top: String)
    data class Release(val source: String, val url: String, val title: String, val catalog: String?, val date: String?, val lengths: List<Double>, val images: List<Pair<String, String>>, val extra: JSONObject = JSONObject())

    private const val UA = "BitChord-Library/1.0 (https://github.com/JuanmiRodas/Musichord)"
    private val lastCall = HashMap<String, Long>()

    /** One request per host per [gapMs] (MusicBrainz asks for 1 per second). */
    internal fun fetch(url: String, gapMs: Long = 1100, accept: String = "*/*"): ByteArray? {
        val host = URL(url).host
        synchronized(lastCall) {
            val wait = (lastCall[host] ?: 0) + gapMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall[host] = System.currentTimeMillis()
        }
        return runCatching {
            var u = url
            repeat(5) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", accept)
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = false
                when (c.responseCode) {
                    in 200..299 -> return@runCatching c.inputStream.use { it.readBytes() }
                    in 300..399 -> u = URL(URL(u), c.getHeaderField("Location")).toString()
                    429, 503 -> { Thread.sleep(5000); }
                    else -> return@runCatching null
                }
            }
            null
        }.getOrNull()
    }
    internal fun text(url: String, gapMs: Long = 1100) = fetch(url, gapMs)?.toString(Charsets.UTF_8)
    internal fun json(url: String, gapMs: Long = 1100) = text(url, gapMs)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun mmss(s: String): Double? = Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$").find(s.trim())?.let { m ->
        (m.groupValues[1].toIntOrNull() ?: 0) * 3600.0 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
    }

    /** How many of our songs have a track of the same length on the release (±2 s, each track used once). */
    fun matches(ours: List<Double>, theirs: List<Double>): Int {
        val left = theirs.toMutableList()
        var n = 0
        for (d in ours.sorted()) {
            val i = left.indexOfFirst { kotlin.math.abs(it - d) <= 2.0 }
            if (i >= 0) { left.removeAt(i); n++ }
        }
        return n
    }
    fun sure(a: Album, r: Release) = r.lengths.isNotEmpty() && a.lengths.size >= 5 && matches(a.lengths, r.lengths) >= kotlin.math.ceil(a.lengths.size * 0.8)

    private fun clean(t: String) = t.replace(Regex("(?i)\\[?\\(?(disc|cd|disk)\\s*\\d+\\)?]?|\\(deluxe.*?\\)|\\[.*?]"), " ").replace(Regex("\\s+"), " ").trim()

    // ── The library's albums ──

    fun albums(root: String): List<Album> {
        val rootFile = File(root)
        return rootFile.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "Imágenes" }
            .filter { it.isDirectory }
            .mapNotNull { d ->
                val songs = d.listFiles()?.filter { it.isFile && it.extension.lowercase() == "mp3" && !it.name.startsWith(".") }.orEmpty()
                if (songs.size < 5 || d.name == "Sueltas") return@mapNotNull null
                val tracks = songs.map { f -> Scanner.get(f.path) ?: Scanner.probe(f).also { Scanner.put(it) } }
                val m = Regex("^(\\d{4}) - (.+)$").find(d.name)
                val title = tracks.groupingBy { it.album }.eachCount().maxByOrNull { it.value }?.key?.ifBlank { null } ?: m?.groupValues?.get(2) ?: d.name
                val artist = tracks.groupingBy { it.albumArtist.ifBlank { it.artist } }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val rel = d.path.removePrefix(root).trimStart('/')
                Album(d, rel, title, artist, m?.groupValues?.get(1), tracks.mapNotNull { it.seconds }, rel.substringBefore('/'))
            }.toList()
    }

    // ── Sources ──

    /** pocketmonsters.net: its CD list read once (34 pages), then each candidate's page. */
    private var pokemonList: List<Pair<String, String>>? = null // id → the row's text
    internal fun pokemon(a: Album): List<Release> {
        val list = pokemonList ?: run {
            val out = ArrayList<Pair<String, String>>()
            for (page in 1..60) {
                val html = text("https://pocketmonsters.net/cd?page=$page", 1500) ?: break
                val rows = Regex("(?s)<tr.*?</tr>").findAll(html).map { it.value }.filter { it.contains("/cd/view/") }.toList()
                if (rows.isEmpty()) break
                rows.forEach { r -> Regex("/cd/view/(\\d+)").find(r)?.let { out += it.groupValues[1] to r.replace(Regex("<[^>]+>"), " ") } }
            }
            out.also { pokemonList = it }
        }
        val want = Words.plain(clean(a.title))
        if (want.length < 4) return emptyList()
        return list.filter { (_, row) -> Words.plain(row).contains(want) }.take(6).mapNotNull { (id, _) ->
            val html = text("https://pocketmonsters.net/cd/view/$id", 1500) ?: return@mapNotNull null
            val lengths = Regex(">\\s*(\\d{1,2}:\\d{2})\\s*<").findAll(html).mapNotNull { mmss(it.groupValues[1]) }.filter { it in 5.0..1800.0 }.toList()
            val images = Regex("https://media\\.pocketmonsters\\.net/cds/$id/[^\"'\\s)]+?\\.(?:jpg|jpeg|png)").findAll(html).map { it.value }
                .filter { !it.contains("/t/") }.distinct().map { u -> u.substringAfterLast('/').substringBeforeLast('.') to u }.toList()
            val title = Regex("(?s)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ")?.trim().orEmpty()
            val catalog = Regex("[A-Z]{2,5}-\\d{3,5}").find(html)?.value
            Release("pocketmonsters.net", "https://pocketmonsters.net/cd/view/$id", title, catalog, null, lengths, images)
        }
    }

    /** canta-per-me.net: the discography's pages, found from its index by title. */
    private var kajiuraList: List<Pair<String, String>>? = null
    internal fun kajiura(a: Album): List<Release> {
        val list = kajiuraList ?: run {
            val out = ArrayList<Pair<String, String>>()
            for (p in listOf("https://canta-per-me.net/discography/", "https://canta-per-me.net/discography/chronological/", "https://canta-per-me.net/discography/alphabetical/")) {
                val html = text(p, 1500) ?: continue
                Regex("(?s)<a[^>]+href=\"((?:https://canta-per-me\\.net)?/discography/[a-z0-9-]+/?)\"[^>]*>(.*?)</a>").findAll(html).forEach { m ->
                    out += m.groupValues[1].let { if (it.startsWith("/")) "https://canta-per-me.net$it" else it } to m.groupValues[2].replace(Regex("<[^>]+>"), " ")
                }
            }
            out.distinctBy { it.first }.also { kajiuraList = it }
        }
        val want = Words.plain(clean(a.title))
        if (want.length < 3) return emptyList()
        return list.filter { (url, t) -> Words.plain(t).let { it.isNotEmpty() && (it == want || it.contains(want) || want.contains(it) && it.length >= 5) } || Words.plain(url.substringAfterLast("discography/")).contains(want) }
            .take(5).mapNotNull { (url, t) ->
                val html = text(url, 1500) ?: return@mapNotNull null
                val lengths = Regex("[(>\\s](\\d{1,2}:\\d{2})[)<\\s]").findAll(html).mapNotNull { mmss(it.groupValues[1]) }.filter { it in 5.0..1800.0 }.toList()
                val images = Regex("(?:https://canta-per-me\\.net)?/images/[^\"'\\s)]+?\\.(?:jpg|jpeg|png)").findAll(html).map { it.value.let { u -> if (u.startsWith("/")) "https://canta-per-me.net$u" else u } }
                    .filter { !it.contains("thumb") }.distinct().map { u -> u.substringAfterLast('/').substringBeforeLast('.') to u }.toList()
                val catalog = Regex("[A-Z]{2,5}-\\d{3,5}").find(html)?.value
                Release("canta-per-me.net", url, t.trim(), catalog, null, lengths, images)
            }
    }

    /** VGMdb, through vgmdb.info (its public JSON mirror). */
    internal fun vgmdb(a: Album): List<Release> {
        val q = clean(a.title)
        if (q.length < 3) return emptyList()
        val found = json("https://vgmdb.info/search/albums/${enc(q)}?format=json", 1500)?.optJSONObject("results")?.optJSONArray("albums") ?: return emptyList()
        return (0 until minOf(found.length(), 6)).mapNotNull { i ->
            val link = found.getJSONObject(i).optString("link").ifBlank { return@mapNotNull null }
            val o = json("https://vgmdb.info/$link?format=json", 1500) ?: return@mapNotNull null
            val lengths = o.optJSONArray("discs")?.let { d -> (0 until d.length()).flatMap { k -> d.getJSONObject(k).optJSONArray("tracks")?.let { t -> (0 until t.length()).mapNotNull { mmss(t.getJSONObject(it).optString("track_length")) } }.orEmpty() } }.orEmpty()
            val covers = o.optJSONArray("covers")?.let { c -> (0 until c.length()).mapNotNull { k -> c.getJSONObject(k).let { x -> x.optString("full").ifBlank { null }?.let { u -> x.optString("name", "cover") to u } } } }.orEmpty()
            val title = o.optJSONObject("names")?.let { n -> n.optString("en").ifBlank { n.optString("ja") } } ?: o.optString("name")
            Release("VGMdb", "https://vgmdb.net/$link", title, o.optString("catalog").ifBlank { null }, o.optString("release_date").ifBlank { null }, lengths, covers,
                JSONObject().put("notes", o.optString("notes")).put("performers", o.optJSONArray("performers") ?: JSONArray()).put("composers", o.optJSONArray("composers") ?: JSONArray()))
        }
    }

    /** MusicBrainz releases with that title (and artist), their track lengths, and the Cover Art Archive's pictures. */
    internal fun musicbrainz(a: Album): List<Release> {
        val q = "release:\"${clean(a.title).replace("\"", "")}\"" + (a.artist.takeIf { it.isNotBlank() }?.let { " AND artist:\"${it.replace(Regex("\\s*\\(.*\\)"), "").replace("\"", "")}\"" } ?: "")
        val rs = json("https://musicbrainz.org/ws/2/release/?query=${enc(q)}&fmt=json&limit=8")?.optJSONArray("releases") ?: return emptyList()
        return (0 until rs.length()).map { rs.getJSONObject(it) }
            .filter { kotlin.math.abs(it.optInt("track-count") - a.lengths.size) <= maxOf(4, a.lengths.size / 2) }
            .take(5).mapNotNull { r ->
                val id = r.getString("id")
                val full = json("https://musicbrainz.org/ws/2/release/$id?inc=recordings+labels&fmt=json") ?: return@mapNotNull null
                val lengths = full.optJSONArray("media")?.let { m -> (0 until m.length()).flatMap { k -> m.getJSONObject(k).optJSONArray("tracks")?.let { t -> (0 until t.length()).mapNotNull { t.getJSONObject(it).optLong("length").takeIf { l -> l > 0 }?.div(1000.0) } }.orEmpty() } }.orEmpty()
                val images = if (full.optJSONObject("cover-art-archive")?.optInt("count", 0) ?: 0 > 0)
                    json("https://coverartarchive.org/release/$id", 500)?.optJSONArray("images")?.let { im -> (0 until im.length()).map { k -> im.getJSONObject(k) }.map { x ->
                        (x.optJSONArray("types")?.let { t -> (0 until t.length()).joinToString("-") { t.getString(it).lowercase() } }?.ifBlank { null } ?: "image") to x.getString("image") } }.orEmpty()
                else emptyList()
                val catalog = full.optJSONArray("label-info")?.let { l -> (0 until l.length()).firstNotNullOfOrNull { l.getJSONObject(it).optString("catalog-number").ifBlank { null } } }
                Release("MusicBrainz", "https://musicbrainz.org/release/$id", full.optString("title"), catalog, full.optString("date").ifBlank { null }, lengths, images)
            }
    }

    private fun sourcesFor(a: Album): List<(Album) -> List<Release>> = when {
        Words.plain(a.top).startsWith("pokemon") -> listOf(::pokemon, ::vgmdb, ::musicbrainz)
        Words.plain(a.top).startsWith("yukikajiura") -> listOf(::kajiura, ::vgmdb, ::musicbrainz)
        else -> listOf(::vgmdb, ::musicbrainz)
    }

    // ── Running ──

    data class Result(val album: Album, val release: Release?, val saved: Int, val note: String)

    /** Looks up one album and, when sure, saves its pictures and data in `<album>/Imágenes/`. */
    fun one(a: Album, dryRun: Boolean = false): Result {
        var best: Pair<Release, Int>? = null
        for (src in sourcesFor(a)) {
            val found = runCatching { src(a) }.getOrDefault(emptyList())
            found.forEach { r -> val m = matches(a.lengths, r.lengths); if (best == null || m > best!!.second) best = r to m }
            val b = best
            if (b != null && sure(a, b.first) && b.first.images.isNotEmpty()) break
        }
        val b = best ?: return Result(a, null, 0, "sin resultados")
        if (!sure(a, b.first)) return Result(a, b.first, 0, "dudoso: ${b.second} de ${a.lengths.size} duraciones coinciden con ${b.first.source}")
        if (dryRun) return Result(a, b.first, 0, "seguro (${b.second}/${a.lengths.size}); ${b.first.images.size} imágenes")
        val dir = File(a.dir, "Imágenes").apply { mkdirs() }
        var saved = 0
        val names = HashMap<String, Int>()
        for ((kind, url) in b.first.images) {
            val base = Builder.clean(kind.lowercase().replace(Regex("[^\\p{L}\\p{N}_-]+"), "-")).ifBlank { "imagen" }
            val n = names.merge(base, 1, Int::plus)!!
            val ext = url.substringAfterLast('.').substringBefore('?').lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "gif", "webp") } ?: "jpg"
            val f = File(dir, (if (n > 1) "$base-$n" else base) + "." + ext)
            if (f.exists()) continue
            val bytes = fetch(url, 700) ?: continue
            if (bytes.size < 2000) continue
            f.writeBytes(bytes); saved++
        }
        val info = JSONObject().put("source", b.first.source).put("url", b.first.url).put("title", b.first.title).put("catalog", b.first.catalog ?: JSONObject.NULL)
            .put("date", b.first.date ?: JSONObject.NULL).put("matched", "${b.second}/${a.lengths.size}").put("tracks", JSONArray(b.first.lengths)).put("extra", b.first.extra)
            .put("images", JSONArray(b.first.images.map { JSONObject().put("kind", it.first).put("url", it.second) }))
        File(dir, "album.json").writeText(info.toString(2))
        return Result(a, b.first, saved, "seguro (${b.second}/${a.lengths.size}) · ${b.first.source} · $saved imágenes nuevas")
    }

    /** Every album not looked up yet (or whose lookup failed more than 7 days ago), until [deadline]. */
    fun run(root: String, only: String? = null, dryRun: Boolean = false, deadline: Long = Long.MAX_VALUE, progress: (String) -> Unit = {}): List<Result> {
        val stateFile = File(root, ".bitchord-library/albums.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())
        val ops = Ops(root)
        val list = albums(root).filter { only == null || it.rel.contains(only, ignoreCase = true) }
        val out = ArrayList<Result>()
        Live.begin("Escaneos de álbumes", root)
        Live.total = list.size
        for (a in list) {
            if (System.currentTimeMillis() > deadline || Live.stop) break
            val prev = state.optJSONObject(a.rel)
            val retry = prev != null && !prev.optBoolean("sure") && System.currentTimeMillis() - prev.optLong("at") > 7L * 86_400_000
            if (prev != null && !retry && !dryRun && only == null) { Live.done.incrementAndGet(); continue }
            Live.current = a.rel; Live.write()
            progress(a.rel)
            val r = one(a, dryRun)
            out += r
            if (!dryRun) state.put(a.rel, JSONObject().put("sure", r.note.startsWith("seguro")).put("note", r.note).put("url", r.release?.url ?: JSONObject.NULL).put("at", System.currentTimeMillis()))
            if (!dryRun) runCatching { stateFile.writeText(state.toString(1)) }
            ops.log("albums", "${a.rel}: ${r.note}")
            if (r.saved > 0) Live.copied.addAndGet(r.saved)
            Live.done.incrementAndGet(); Live.write()
        }
        Scanner.saveIndex()
        Live.end("Escaneos: ${out.count { it.note.startsWith("seguro") }} álbumes seguros, ${out.sumOf { it.saved }} imágenes, ${out.count { it.note.startsWith("dudoso") }} dudosos, ${out.count { it.release == null }} sin resultados")
        return out
    }
}

/**
 * Latin names for people known only in Japanese (千葉紗子 → Saeko Chiba), from MusicBrainz:
 * its Latin name, else its sort name turned round ("Chiba, Saeko"), else a Latin alias. Kept in
 * `<app data>/romaji.json`; looked up once per name (one request a second).
 */
object Romaji {
    private val file get() = File(Store.dir, "romaji.json")
    @Volatile private var cache: JSONObject? = null
    private fun db() = cache ?: runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()).also { cache = it }

    fun of(name: String): String? = db().optString(Words.nfc(name)).ifBlank { null }

    private val CJK = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]")
    private fun latin(s: String) = s.any { it.isLetter() } && !CJK.containsMatchIn(s)

    /** Looks up the names not asked yet (at most [max]); returns how many were found. */
    fun fill(names: Collection<String>, max: Int = 300): Int {
        val o = db(); var found = 0
        for (n in names.map { Words.nfc(it.trim()) }.distinct().filter { it.isNotBlank() && !o.has(it) }.take(max)) {
            if (Live.stop) break
            val r = AlbumArt.json("https://musicbrainz.org/ws/2/artist/?query=${java.net.URLEncoder.encode("artist:\"$n\" OR alias:\"$n\"", "UTF-8")}&fmt=json&limit=3")
            val a = r?.optJSONArray("artists")?.let { x -> (0 until x.length()).map { x.getJSONObject(it) } }.orEmpty().firstOrNull { it.optInt("score") >= 90 }
            val latinName = a?.let { art ->
                art.optString("name").takeIf(::latin)
                    ?: art.optString("sort-name").takeIf(::latin)?.let { s -> if (s.contains(", ")) s.substringAfter(", ") + " " + s.substringBefore(", ") else s }
                    ?: art.optJSONArray("aliases")?.let { al -> (0 until al.length()).map { al.getJSONObject(it) } }?.firstOrNull { latin(it.optString("name")) }?.optString("name")
            }
            o.put(n, latinName ?: "")
            if (latinName != null) found++
        }
        runCatching { file.writeText(o.toString(1)) }
        return found
    }
}
