package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keys of the services the listener signed up to (free): AcoustID, Discogs, Last.fm and
 * fanart.tv. Typed in Ajustes, kept in `<app data>/keys.json` on this Mac only.
 */
object Keys {
    private val file get() = File(Store.dir, "keys.json")
    @Synchronized fun get(name: String): String? = runCatching { JSONObject(file.readText()).optString(name) }.getOrNull()?.trim()?.ifBlank { null }
    @Synchronized fun set(name: String, value: String) {
        val o = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        o.put(name, value.trim()); file.writeText(o.toString(1))
    }
    const val ACOUSTID = "acoustid"; const val DISCOGS = "discogs"; const val LASTFM = "lastfm"; const val FANART = "fanart"

    /** Asks the service whether [name]'s key works: a few words to show beside its field. */
    fun test(name: String): String {
        val k = get(name) ?: return "falta la clave"
        val e = URLEncoder.encode(k, "UTF-8")
        fun call(url: String): Pair<Int, String> = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "BitChord-Library/1.0 (https://github.com/JuanmiRodas/Musichord)")
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        }.getOrElse { -1 to (it.message ?: "") }
        val (code, body) = when (name) {
            ACOUSTID -> call("https://api.acoustid.org/v2/lookup?client=$e&duration=100&fingerprint=AQAAAA")
            DISCOGS -> call("https://api.discogs.com/oauth/identity?token=$e")
            LASTFM -> call("https://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=Cher&api_key=$e&format=json")
            FANART -> call("https://webservice.fanart.tv/v3/music/cc197bad-dc9c-440d-a5b5-d52ba2e14234?api_key=$e")
            else -> return "?"
        }
        if (code == -1) return "sin conexión: $body"
        val bad = when (name) {
            ACOUSTID -> body.contains("\"code\": 4") || body.contains("\"code\":4") || body.contains("invalid API key", ignoreCase = true)
            LASTFM -> body.contains("\"error\":10") || body.contains("\"error\": 10") || code == 403
            else -> code == 401 || code == 403
        }
        return when {
            bad -> "✗ el servicio no acepta esta clave"
            code in 200..299 || name == ACOUSTID && code == 400 -> "✓ funciona"
            else -> "✗ respuesta $code"
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** A JSON cache in the app's data folder: one file per service. */
private class Cache(name: String) {
    private val file = File(Store.dir, name)
    val o: JSONObject by lazy { runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) }
    @Synchronized fun save() = runCatching { file.writeText(o.toString()) }
}

/**
 * AcoustID: which recording a file is, by how it sounds (Chromaprint's fingerprint), for songs
 * whose tags and names say nothing or say something wrong. fpcalc (Chromaprint's small tool) is
 * downloaded once from its GitHub releases into the app's data folder.
 */
object AcoustId {
    private val cache = Cache("acoustid.json")
    internal val fpcalc: String? by lazy {
        val f = File(Store.dir, "bin/fpcalc")
        if (f.canExecute()) return@lazy f.path
        listOf("/opt/homebrew/bin/fpcalc", "/usr/local/bin/fpcalc").firstOrNull { File(it).canExecute() }?.let { return@lazy it }
        runCatching {
            val tgz = AlbumArt.fetch("https://github.com/acoustid/chromaprint/releases/download/v1.5.1/chromaprint-fpcalc-1.5.1-macos-universal.tar.gz", 0) ?: return@runCatching null
            val dir = File(Store.dir, "bin").apply { mkdirs() }
            val t = File(dir, "fpcalc.tar.gz"); t.writeBytes(tgz)
            Tools.run("tar", "-xzf", t.path, "-C", dir.path, timeoutSec = 60); t.delete()
            dir.walkTopDown().firstOrNull { it.name == "fpcalc" && it.isFile }?.let { it.copyTo(f, overwrite = true); f.setExecutable(true); f.path }
        }.getOrNull()
    }

    data class Hit(val title: String, val artist: String, val album: String?, val year: String?, val score: Double)

    fun of(path: String): Hit? = cache.o.optJSONObject(Words.nfc(path))?.takeIf { it.has("title") }?.let {
        Hit(it.getString("title"), it.getString("artist"), it.optString("album").ifBlank { null }, it.optString("year").ifBlank { null }, it.optDouble("score"))
    }

    private fun post(url: String, body: String): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrNull()

    /** Looks up [paths] not asked yet (three a second, at most [minutes]). */
    fun fill(paths: Collection<String>, minutes: Int = 5): Int {
        val key = Keys.get(Keys.ACOUSTID) ?: return 0
        val todo = paths.map { Words.nfc(it) }.distinct().filter { !cache.o.has(it) }
        if (todo.isEmpty()) return 0
        val fp = fpcalc ?: return 0
        val until = System.currentTimeMillis() + minutes * 60_000L
        Live.phase = "reconociendo canciones por cómo suenan (AcoustID)"; Live.total = todo.size; Live.done.set(0)
        var found = 0
        for (p in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = p.substringAfterLast('/'); Live.done.incrementAndGet(); Live.write()
            val (code, out) = Tools.run(fp, "-json", p, timeoutSec = 60)
            val f = runCatching { JSONObject(out) }.getOrNull()
            if (code != 0 || f == null) { cache.o.put(p, JSONObject()); continue }
            Thread.sleep(340)
            val r = post("https://api.acoustid.org/v2/lookup", "client=${enc(key)}&meta=recordings+releasegroups+compress&duration=${f.optDouble("duration").toInt()}&fingerprint=${enc(f.optString("fingerprint"))}")
            val best = r?.optJSONArray("results")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                .filter { it.optDouble("score") >= 0.85 && (it.optJSONArray("recordings")?.length() ?: 0) > 0 }.maxByOrNull { it.optDouble("score") }
            val rec = best?.getJSONArray("recordings")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.firstOrNull { it.has("title") }
            if (rec == null) { cache.o.put(p, JSONObject()); continue }
            val artists = rec.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty()
            // Its album: the release group of type Album first, else the first one.
            val groups = rec.optJSONArray("releasegroups")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            val g = groups.firstOrNull { it.optString("type") == "Album" && it.optJSONArray("secondarytypes") == null } ?: groups.firstOrNull()
            cache.o.put(p, JSONObject().put("title", rec.getString("title")).put("artist", artists).put("album", g?.optString("title") ?: "")
                .put("score", best.optDouble("score")))
            found++
            if (Live.done.get() % 20 == 0) cache.save()
        }
        cache.save()
        return found
    }
}

/** Last.fm: an artist's tags (genres, language, decade), for "Otros artistas/<kind>". */
object LastFm {
    private val cache = Cache("lastfm-tags.json")
    fun tags(artist: String): List<String> = cache.o.optJSONArray(Words.plain(artist))?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    fun fill(artists: Collection<String>, minutes: Int = 3): Int {
        val key = Keys.get(Keys.LASTFM) ?: return 0
        val todo = artists.map { it.substringBefore(" (").trim() }.filter { it.isNotBlank() }.distinctBy { Words.plain(it) }.filter { !cache.o.has(Words.plain(it)) }
        val until = System.currentTimeMillis() + minutes * 60_000L
        Live.phase = "géneros de los artistas (Last.fm)"; Live.total = todo.size; Live.done.set(0)
        var found = 0
        for (a in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = a; Live.done.incrementAndGet(); Live.write()
            val r = AlbumArt.json("https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&artist=${enc(a)}&autocorrect=1&api_key=${enc(key)}&format=json", 250)
            val tags = r?.optJSONObject("toptags")?.optJSONArray("tag")?.let { t -> (0 until t.length()).map { t.getJSONObject(it) } }.orEmpty()
                .filter { it.optInt("count") >= 10 }.take(10).map { it.optString("name").lowercase() }
            cache.o.put(Words.plain(a), JSONArray(tags))
            if (tags.isNotEmpty()) found++
        }
        cache.save()
        return found
    }
}

/**
 * Discogs: best-ofs and compilations MusicBrainz lacks (many Japanese ones), with their track
 * lists, added to [Compilations] for the same artist.
 */
object Discogs {
    private val asked = Cache("discogs-asked.json")

    fun fill(artists: Collection<String>, minutes: Int = 4): Int {
        val token = Keys.get(Keys.DISCOGS) ?: return 0
        val todo = artists.filter { it.isNotBlank() && !asked.o.has(Words.plain(it)) }.distinctBy { Words.plain(it) }
        val until = System.currentTimeMillis() + minutes * 60_000L
        Live.phase = "recopilatorios (Discogs)"; Live.total = todo.size; Live.done.set(0)
        var found = 0
        for (a in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = a; Live.done.incrementAndGet(); Live.write()
            asked.o.put(Words.plain(a), true)
            val name = a.substringBefore(" (").trim()
            val r = AlbumArt.json("https://api.discogs.com/database/search?artist=${enc(name)}&type=master&format=compilation&per_page=15&token=${enc(token)}", 1100)
            val masters = r?.optJSONArray("results")?.let { x -> (0 until x.length()).map { x.getJSONObject(it) } }.orEmpty().take(10)
            for (m in masters) {
                if (Live.stop || System.currentTimeMillis() > until) break
                val main = AlbumArt.json("https://api.discogs.com/masters/${m.optLong("id")}?token=${enc(token)}", 1100) ?: continue
                val tracks = main.optJSONArray("tracklist")?.let { t -> (0 until t.length()).map { t.getJSONObject(it) } }.orEmpty().filter { it.optString("type_") == "track" }
                if (tracks.size < 4) continue
                val list = tracks.mapIndexed { k, t ->
                    val pos = t.optString("position")
                    val disc = Regex("^(?:CD)?(\\d+)-").find(pos)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val no = Regex("(\\d+)$").find(pos)?.groupValues?.get(1)?.toIntOrNull() ?: (k + 1)
                    Compilations.Track(no, disc, t.optString("title"), Regex("^(\\d+):(\\d{2})$").find(t.optString("duration"))?.let { d -> d.groupValues[1].toInt() * 60.0 + d.groupValues[2].toInt() })
                }
                val title = main.optString("title")
                val cover = main.optJSONArray("images")?.optJSONObject(0)?.optString("uri")
                if (Compilations.add(a, "discogs-${m.optLong("id")}", title, main.optInt("year").takeIf { it > 0 }?.toString(), list, cover)) found++
            }
            asked.save()
        }
        asked.save()
        return found
    }
}

/** fanart.tv: an artist's photo and logo (by its MusicBrainz id), for the phone's artist pages. */
object Fanart {
    private val cache = Cache("fanart.json")
    fun of(artist: String): JSONObject? = cache.o.optJSONObject(Words.plain(artist))?.takeIf { it.has("thumb") || it.has("logo") }

    fun fill(artists: Collection<String>, minutes: Int = 3): Int {
        val key = Keys.get(Keys.FANART) ?: return 0
        val todo = artists.filter { it.isNotBlank() }.distinctBy { Words.plain(it) }.filter { !cache.o.has(Words.plain(it)) }
        val until = System.currentTimeMillis() + minutes * 60_000L
        Live.phase = "fotos de los artistas (fanart.tv)"; Live.total = todo.size; Live.done.set(0)
        var found = 0
        for (a in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = a; Live.done.incrementAndGet(); Live.write()
            val name = a.substringBefore(" (").trim()
            val mbid = AlbumArt.json("https://musicbrainz.org/ws/2/artist/?query=${enc("artist:\"$name\"")}&fmt=json&limit=3")
                ?.optJSONArray("artists")?.let { x -> (0 until x.length()).map { x.getJSONObject(it) } }?.firstOrNull { it.optInt("score") >= 95 }?.optString("id")
            val o = JSONObject()
            if (mbid != null) AlbumArt.json("https://webservice.fanart.tv/v3/music/$mbid?api_key=${enc(key)}", 300)?.let { f ->
                f.optJSONArray("artistthumb")?.optJSONObject(0)?.optString("url")?.let { o.put("thumb", it) }
                (f.optJSONArray("hdmusiclogo") ?: f.optJSONArray("musiclogo"))?.optJSONObject(0)?.optString("url")?.let { o.put("logo", it) }
                f.optJSONArray("artistbackground")?.optJSONObject(0)?.optString("url")?.let { o.put("background", it) }
            }
            cache.o.put(Words.plain(a), o)
            if (o.length() > 0) found++
        }
        cache.save()
        return found
    }
}

/**
 * Whether two files are the same recording, by how they sound (Chromaprint's raw fingerprint of
 * their first two minutes, compared at the best offset): a better copy only replaces a song when
 * it sounds the same — never another version that happens to share its name and length.
 */
object Fingerprint {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    private fun of(path: String): IntArray? {
        val f = File(path)
        if (!f.isFile) return null
        val k = Words.nfc(path) + "|" + f.length()
        cache[k]?.let { return it }
        val fp = AcoustId.fpcalc ?: return null
        val (code, out) = Tools.run(fp, "-raw", "-length", "120", "-plain", path, timeoutSec = 120)
        if (code != 0) return null
        val ints = out.trim().split(',').mapNotNull { it.trim().toLongOrNull()?.toInt() }.toIntArray()
        if (ints.size < 30) return null
        cache[k] = ints
        return ints
    }

    /** How alike (0 to 1): 1 − the share of differing bits, at the offset where they line up best. */
    fun similarity(a: String, b: String): Double? {
        val x = of(a) ?: return null
        val y = of(b) ?: return null
        var best = 0.0
        for (off in -40..40) {
            var bits = 0; var n = 0
            for (i in x.indices) {
                val j = i + off
                if (j < 0 || j >= y.size) continue
                bits += Integer.bitCount(x[i] xor y[j]); n++
            }
            if (n >= 30) best = maxOf(best, 1.0 - bits / (32.0 * n))
        }
        return best
    }

    /** True: the same recording; false: sounds different; null: couldn't tell (no fpcalc, unreadable file). */
    fun same(a: String, b: String): Boolean? = similarity(a, b)?.let { it >= SAME }
    /** At or above: the same recording (measured: same recording 0.97+, live/acoustic version ≈ 0.58, another song ≈ 0.50). */
    const val SAME = 0.80
}
