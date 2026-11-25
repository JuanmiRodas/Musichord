package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Spotify's catalogue as a second check of the albums (name, year, number of songs, cover), in
 * Japan's catalogue first (many Japanese albums are only there) and Spain's after. Needs a free
 * Spotify app (developer.spotify.com → Dashboard → Create app): its Client ID and Client secret,
 * typed in Ajustes, kept in `<app data>/spotify.json` on this Mac. Only reads the public
 * catalogue (client credentials): nothing of the listener's account.
 */
object Spotify {
    private val file get() = File(Store.dir, "spotify.json")
    private val checks get() = File(Store.dir, "spotify-albums.json")
    @Volatile private var token: Pair<String, Long>? = null

    fun saved(): Pair<String, String>? = runCatching { JSONObject(file.readText()).let { it.getString("id") to it.getString("secret") } }.getOrNull()
        ?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
    fun save(id: String, secret: String) { file.writeText(JSONObject().put("id", id.trim()).put("secret", secret.trim()).toString()); token = null }
    val ready get() = saved() != null

    private fun bearer(): String? {
        token?.takeIf { it.second > System.currentTimeMillis() + 60_000 }?.let { return it.first }
        val (id, secret) = saved() ?: return null
        return runCatching {
            val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$id:$secret".toByteArray()))
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
            val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val t = o.getString("access_token")
            token = t to System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
            t
        }.getOrNull()
    }

    /** Whether the Client ID and secret in Ajustes work (for the "Comprobar" button). */
    fun test(): Boolean { token = null; return bearer() != null }

    private fun get(path: String): JSONObject? {
        repeat(3) {
            val t = bearer() ?: return null
            val c = URL("https://api.spotify.com/v1/$path").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $t"); c.connectTimeout = 10_000; c.readTimeout = 15_000
            when (val code = runCatching { c.responseCode }.getOrDefault(-1)) {
                200 -> return runCatching { JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrNull()
                429 -> Thread.sleep(((c.getHeaderField("Retry-After")?.toLongOrNull() ?: 2) * 1000).coerceAtMost(30_000))
                401 -> token = null
                else -> return null.also { if (code == -1) Thread.sleep(1000) }
            }
        }
        return null
    }

    data class Album(val id: String, val name: String, val artist: String, val year: String?, val tracks: List<String>, val cover: String?, val market: String)

    private val SPURIOUS_WORDS = Regex("(?i)\\b(best|collection|commentary|radio release|broadway|musical|karaoke|tribute)\\b")
    private val TRAILING_VOL = Regex("(?i)(?:^|\\s)(vol\\.?\\s*\\d+|\\d+|ii|iii|iv|v)\\s*$")

    private fun validAlbumMatch(wantTitle: String, gotTitle: String): Boolean {
        // Reject when Spotify returns a Best-Of, Commentary, Musical, etc. that the local album didn't ask for.
        val wantSpurious = SPURIOUS_WORDS.findAll(wantTitle).map { it.value.lowercase() }.toSet()
        val gotSpurious = SPURIOUS_WORDS.findAll(gotTitle).map { it.value.lowercase() }.toSet()
        if ((gotSpurious - wantSpurious).isNotEmpty()) return false
        // Reject sequel/volume mismatch ("ayu trance" vs "ayu trance 2", "Sugarless" vs "Sugarless III").
        val wantVol = TRAILING_VOL.find(wantTitle.trim())?.groupValues?.get(1)?.lowercase()
        val gotVol = TRAILING_VOL.find(gotTitle.trim())?.groupValues?.get(1)?.lowercase()
        if (gotVol != null && wantVol != gotVol) return false
        return true
    }

    /** The album [title] by [artist] on Spotify (Japan, then Spain), if one matches its name and artist. */
    fun album(artist: String, title: String): Album? {
        val who = artist.substringBefore(" (").trim()
        val jp = Regex("\\(([^)]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)]*)\\)").find(artist)?.groupValues?.get(1)?.trim()
        val base = title.replace(Regex("\\s*[(\\[].*$"), "").trim().ifBlank { title }
        val queries = listOfNotNull(
            jp?.let { "JP" to it },
            "JP" to who,
            "ES" to who,
        )
        for ((market, qArtist) in queries) {
            val q = URLEncoder.encode("album:$base artist:$qArtist", "UTF-8")
            val items = get("search?q=$q&type=album&market=$market&limit=10")?.optJSONObject("albums")?.optJSONArray("items") ?: continue
            val hit = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { a ->
                val aName = a.optString("name")
                val names = a.optJSONArray("artists")?.let { x -> (0 until x.length()).map { x.getJSONObject(it).optString("name") } }.orEmpty()
                val titleOk = validAlbumMatch(title, aName) &&
                    (Builder.albumKey(aName) == Builder.albumKey(title) || Words.plain(aName).startsWith(Words.plain(base)) && Words.plain(base).length >= 4)
                val artistOk = names.any { n ->
                    Words.plain(n) == Words.plain(qArtist) || Words.plain(n) == Words.plain(who) ||
                    (jp != null && Words.plain(n) == Words.plain(jp)) ||
                    Builder.pkey(n) == Builder.pkey(who) ||
                    (Words.plain(who).length >= 3 && Words.plain(n).contains(Words.plain(who)))
                }
                titleOk && artistOk
            } ?: continue
            val full = get("albums/${hit.getString("id")}?market=$market") ?: continue
            val trackNames = ArrayList<String>()
            var tracksPage = full.optJSONObject("tracks")
            var pages = 0
            while (tracksPage != null && pages++ < 5) {
                val arr = tracksPage.optJSONArray("items")
                if (arr != null) for (i in 0 until arr.length()) trackNames += arr.getJSONObject(i).optString("name")
                val next = tracksPage.optString("next").ifBlank { null }?.substringAfter("/v1/", "")?.ifBlank { null }
                tracksPage = if (next != null) get(next) else null
            }
            return Album(hit.getString("id"), full.optString("name"), who, full.optString("release_date").take(4).ifBlank { null },
                trackNames, full.optJSONArray("images")?.optJSONObject(0)?.optString("url"), market)
        }
        return null
    }

    /**
     * The albums of the plan checked on Spotify (those not checked yet, at most [minutes]): what was
     * found is kept in `spotify-albums.json` — its name, year, songs and cover — and used for missing
     * years, for the covers to review and for the report (albums Spotify doesn't know, or knows with
     * another year or more songs).
     */
    fun check(albums: Collection<Pair<String, String>>, minutes: Int = 5): Int {
        if (!ready) return 0
        val o = runCatching { JSONObject(checks.readText()) }.getOrDefault(JSONObject())
        val todo = albums.distinctBy { key(it.first, it.second) }.filter { !o.has(key(it.first, it.second)) }
        val until = System.currentTimeMillis() + minutes * 60_000L
        Live.phase = "comprobando álbumes en Spotify"; Live.total = todo.size; Live.done.set(0)
        var found = 0
        for ((artist, title) in todo) {
            if (Live.stop || System.currentTimeMillis() > until) break
            Live.current = "$artist — $title"; Live.done.incrementAndGet(); Live.write()
            val a = runCatching { album(artist, title) }.getOrNull()
            o.put(key(artist, title), if (a == null) JSONObject().put("found", false) else JSONObject().put("found", true).put("id", a.id).put("name", a.name)
                .put("year", a.year ?: "").put("tracks", JSONArray(a.tracks)).put("cover", a.cover ?: "").put("market", a.market))
            if (a != null) found++
            if (Live.done.get() % 25 == 0) runCatching { checks.writeText(o.toString()) }
        }
        runCatching { checks.writeText(o.toString()) }
        cache = null
        return found
    }

    private fun key(artist: String, title: String) = Builder.pkey(artist.substringBefore(" (")) + "|" + Builder.albumKey(title)
    @Volatile private var cache: JSONObject? = null
    fun checked(artist: String, title: String): JSONObject? {
        val o = cache ?: runCatching { JSONObject(checks.readText()) }.getOrDefault(JSONObject()).also { cache = it }
        return o.optJSONObject(key(artist, title))
    }
}
