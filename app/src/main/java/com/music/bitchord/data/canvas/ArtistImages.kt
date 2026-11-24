package com.music.bitchord.data.canvas

import android.content.Context
import com.music.bitchord.data.AppleCatalogue
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: an artist's pictures beyond Apple Music — fanart.tv (with your own free
 * key: photos, HD logos, backgrounds), TheAudioDB (its free public key) and Wikimedia
 * Commons (the photo and logo Wikidata names). The artist is found on MusicBrainz by name,
 * and only taken when exactly one artist there carries that name, so a namesake's face is
 * never shown. MusicBrainz is asked at most once a second; answers are kept for a month.
 */
object ArtistImages {
    private const val TAG = "ArtistImages"
    private const val TTL_MS = 30L * 24 * 3600 * 1000
    private const val UA = "BitChordAudio/1.0 (https://github.com/JuanmiRodas/Musichord)"

    enum class Kind { PHOTO, BACKGROUND, LOGO, BANNER }
    data class Picture(val url: String, val kind: Kind, val source: String)

    /** fanart.tv's best of each kind for an artist. */
    data class Fanart(val thumb: String?, val background: String?, val logo: String?)

    @Volatile private var file: File? = null
    private val cache: JSONObject by lazy {
        runCatching { file?.takeIf { it.isFile }?.readText()?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    }
    private val brainz = Mutex()
    @Volatile private var lastBrainz = 0L

    private val http by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, "artist_images.json")
    }

    private fun save() = synchronized(cache) { runCatching { file?.writeText(cache.toString()) } }

    private fun key(name: String) = AppleCatalogue.matchKey(name)

    private fun fresh(o: JSONObject?) = o != null && System.currentTimeMillis() - o.optLong("at") < TTL_MS

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The artist's MusicBrainz id, when exactly one artist there has this name. */
    suspend fun mbid(name: String): String? = withContext(Dispatchers.IO) {
        val k = key(name)
        if (k.isEmpty()) return@withContext null
        synchronized(cache) { cache.optJSONObject("mb:$k") }?.takeIf(::fresh)?.let { return@withContext it.optString("id").ifBlank { null } }
        val body = brainz.withLock {
            val wait = 1100 - (System.currentTimeMillis() - lastBrainz)
            if (wait > 0) delay(wait)
            lastBrainz = System.currentTimeMillis()
            get("https://musicbrainz.org/ws/2/artist/?query=${enc("artist:\"${name.trim()}\"")}&fmt=json&limit=5")
        } ?: return@withContext null
        val artists = runCatching { JSONObject(body).optJSONArray("artists") }.getOrNull() ?: JSONArray()
        val same = (0 until artists.length()).mapNotNull { artists.optJSONObject(it) }.filter { a ->
            a.optInt("score") >= 90 && (key(a.optString("name")) == k ||
                (a.optJSONArray("aliases")?.let { al -> (0 until al.length()).any { key(al.optJSONObject(it)?.optString("name").orEmpty()) == k } } == true))
        }
        val id = same.singleOrNull()?.optString("id")?.ifBlank { null }
        if (same.size > 1) TrackLog.d(TAG, "MusicBrainz: ${same.size} artists named '$name' — no pictures by name")
        synchronized(cache) { cache.put("mb:$k", JSONObject().put("id", id.orEmpty()).put("at", System.currentTimeMillis())) }
        save()
        id
    }

    /** fanart.tv's pictures of [name] (null without your key, or for an artist it can't be sure of). */
    suspend fun fanart(name: String): Fanart? = withContext(Dispatchers.IO) {
        val apiKey = AppSettings.fanartKey.value.trim().ifBlank { return@withContext null }
        val list = fanartAll(name, apiKey) ?: return@withContext null
        Fanart(
            thumb = list.firstOrNull { it.kind == Kind.PHOTO }?.url,
            background = list.firstOrNull { it.kind == Kind.BACKGROUND }?.url,
            logo = list.firstOrNull { it.kind == Kind.LOGO }?.url,
        ).takeIf { it.thumb != null || it.background != null || it.logo != null }
    }

    private suspend fun fanartAll(name: String, apiKey: String): List<Picture>? {
        val id = mbid(name) ?: return null
        val ck = "fa:$id"
        val raw = synchronized(cache) { cache.optJSONObject(ck) }?.takeIf(::fresh)?.optString("j")
            ?: get("https://webservice.fanart.tv/v3/music/$id?api_key=${enc(apiKey)}").orEmpty().also { j ->
                synchronized(cache) { cache.put(ck, JSONObject().put("j", j).put("at", System.currentTimeMillis())) }
                save()
            }
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        fun urls(field: String) = o.optJSONArray(field)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") } } }.orEmpty()
        return urls("artistthumb").map { Picture(it, Kind.PHOTO, "fanart.tv") } +
            urls("artistbackground").map { Picture(it, Kind.BACKGROUND, "fanart.tv") } +
            (urls("hdmusiclogo") + urls("musiclogo")).map { Picture(it, Kind.LOGO, "fanart.tv") } +
            urls("musicbanner").map { Picture(it, Kind.BANNER, "fanart.tv") }
    }

    private fun audioDb(name: String): List<Picture> {
        val ck = "adb:${key(name)}"
        val raw = synchronized(cache) { cache.optJSONObject(ck) }?.takeIf(::fresh)?.optString("j")
            ?: get("https://www.theaudiodb.com/api/v1/json/123/search.php?s=${enc(name.trim())}").orEmpty().also { j ->
                synchronized(cache) { cache.put(ck, JSONObject().put("j", j).put("at", System.currentTimeMillis())) }
                save()
            }
        val a = runCatching { JSONObject(raw).optJSONArray("artists")?.optJSONObject(0) }.getOrNull() ?: return emptyList()
        if (key(a.optString("strArtist")) != key(name)) return emptyList()
        fun f(field: String) = a.optString(field).takeIf { it.startsWith("http") }
        return listOfNotNull(
            f("strArtistThumb")?.let { Picture(it, Kind.PHOTO, "TheAudioDB") },
            f("strArtistWideThumb")?.let { Picture(it, Kind.BACKGROUND, "TheAudioDB") },
            f("strArtistFanart")?.let { Picture(it, Kind.BACKGROUND, "TheAudioDB") },
            f("strArtistFanart2")?.let { Picture(it, Kind.BACKGROUND, "TheAudioDB") },
            f("strArtistFanart3")?.let { Picture(it, Kind.BACKGROUND, "TheAudioDB") },
            f("strArtistLogo")?.let { Picture(it, Kind.LOGO, "TheAudioDB") },
            f("strArtistBanner")?.let { Picture(it, Kind.BANNER, "TheAudioDB") },
        )
    }

    /** The photo (P18) and logo (P154) Wikidata gives the artist, from Wikimedia Commons. */
    private fun commons(mbid: String): List<Picture> {
        val ck = "wd:$mbid"
        val raw = synchronized(cache) { cache.optJSONObject(ck) }?.takeIf(::fresh)?.optString("j") ?: run {
            val search = get("https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=${enc("haswbstatement:P434=$mbid")}&format=json")
            val q = runCatching { JSONObject(search.orEmpty()).optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title") }.getOrNull()
            val claims = q?.takeIf { it.startsWith("Q") }?.let { get("https://www.wikidata.org/w/api.php?action=wbgetclaims&entity=$it&format=json") }.orEmpty()
            claims.also { j ->
                synchronized(cache) { cache.put(ck, JSONObject().put("j", j).put("at", System.currentTimeMillis())) }
                save()
            }
        }
        val claims = runCatching { JSONObject(raw).optJSONObject("claims") }.getOrNull() ?: return emptyList()
        fun files(p: String) = claims.optJSONArray(p)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifBlank { null } }
        }.orEmpty()
        fun url(f: String) = "https://commons.wikimedia.org/wiki/Special:FilePath/${enc(f.replace(' ', '_')).replace("+", "%20")}?width=1200"
        return files("P18").map { Picture(url(it), Kind.PHOTO, "Wikimedia Commons") } +
            files("P154").map { Picture(url(it), Kind.LOGO, "Wikimedia Commons") }
    }

    /** Every picture found of [name] (plus [extra], e.g. Apple's), for the artist gallery. */
    suspend fun gallery(name: String, extra: List<Picture> = emptyList()): List<Picture> = withContext(Dispatchers.IO) {
        val out = ArrayList(extra)
        AppSettings.fanartKey.value.trim().takeIf { it.isNotEmpty() }?.let { k -> runCatching { fanartAll(name, k) }.getOrNull()?.let(out::addAll) }
        runCatching { audioDb(name) }.getOrNull()?.let(out::addAll)
        runCatching { mbid(name)?.let(::commons) }.getOrNull()?.let(out::addAll)
        out.distinctBy { it.url }
    }
}
