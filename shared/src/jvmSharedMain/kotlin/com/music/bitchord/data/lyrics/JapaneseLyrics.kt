package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * BitChord Audio: lyrics sources strong on J-pop, anime and vocaloid.
 *
 * Timed: [NetEase] (fan-made LRC for a huge Japanese catalogue, often with a
 * timed romaji version of its own) and [QqMusic]. Untimed, used only when no
 * source has timed lyrics: [UtaTen] (with the kanji readings and romaji),
 * [UtaNet] and [JLyric], the Japanese sites with nearly every release.
 *
 * Romaji a source provides is kept in [ProvidedRomaji], so the romanized view
 * shows the source's own romaji rather than the automatic one.
 */
object ProvidedRomaji {
    private val byLine = ConcurrentHashMap<String, String>()

    fun put(original: String, romaji: String) {
        val o = original.trim()
        val r = romaji.trim()
        if (o.isEmpty() || r.isEmpty() || o == r || !NON_LATIN.containsMatchIn(o)) return
        if (byLine.size > 20_000) byLine.clear()
        byLine[o] = r
    }

    /** Romaji for every non-Latin line of [texts], or null if a source didn't give it for most. */
    fun forLines(texts: List<String>): List<String>? {
        val needing = texts.filter { NON_LATIN.containsMatchIn(it) }
        if (needing.isEmpty()) return null
        val known = needing.count { byLine.containsKey(it.trim()) }
        if (known < needing.size * 0.8) return null
        return texts.map { byLine[it.trim()] ?: it }
    }

    /** Pairs the lines of an original and a romaji LRC by their times. */
    fun pairTimed(original: List<LyricLine>, romaji: List<LyricLine>) {
        val byTime = romaji.filter { it.text.isNotBlank() }.associateBy { it.timeMs }
        original.forEach { o -> byTime[o.timeMs]?.let { put(o.text, it.text) } }
    }

    /** Pairs untimed lines one for one, when both have the same count. */
    fun pairUntimed(original: List<String>, romaji: List<String>) {
        val o = original.filter { it.isNotBlank() }
        val r = romaji.filter { it.isNotBlank() }
        if (o.size == r.size) o.indices.forEach { put(o[it], r[it]) }
    }

    private val NON_LATIN = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
}

internal object JapaneseMatch {
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("""[(\[（【].*?[)\]）】]"""), "")
            .filter { it.isLetterOrDigit() }

    fun sameTitle(want: String, got: String): Boolean {
        val a = norm(want)
        val b = norm(got)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (minOf(a.length, b.length) >= 2 && (a.startsWith(b) || b.startsWith(a)))
    }

    fun sameArtist(want: String, got: String): Boolean {
        if (want.isBlank()) return true
        val a = norm(want)
        val b = norm(got)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.contains(b) || b.contains(a)) return true
        // "Hamasaki Ayumi" / "Ayumi Hamasaki"
        val wa = want.lowercase(Locale.ROOT).split(Regex("[\\s,&、・]+")).filter { it.length > 1 }.toSet()
        val wb = got.lowercase(Locale.ROOT).split(Regex("[\\s,&、・]+")).filter { it.length > 1 }.toSet()
        return wa.isNotEmpty() && wa == wb
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** HTML lyrics (lines split by <br>) as plain lines. */
    fun htmlLines(html: String): List<String> =
        html.replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), "")
            .replace(Regex("""<[^>]+>"""), "")
            .let(EnhancedLrc::decodeEntities)
            .lines()
            .map { it.trim() }

    /** Untimed lines as lyrics: blank lines kept as the gaps between verses. */
    fun untimed(lines: List<String>): List<LyricLine>? {
        val trimmed = lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (trimmed.count { it.isNotBlank() } < 2) return null
        val out = ArrayList<LyricLine>()
        trimmed.forEach { l ->
            if (l.isBlank()) {
                if (out.lastOrNull()?.text?.isEmpty() != true) out += LyricLine(0L, "")
            } else {
                out += LyricLine(0L, l)
            }
        }
        return out
    }

    fun str(e: JsonElement?): String? = (e as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    fun arr(e: JsonElement?): JsonArray? = e as? JsonArray
}

/** NetEase Cloud Music: timed lyrics, and often a timed romaji version of them. */
object NetEase {
    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to BROWSER_AGENT,
        "Accept" to "application/json",
    )

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val q = JapaneseMatch.enc("$title $artist".trim())
        val body = lyricsGetWith("https://music.163.com/api/search/get?s=$q&type=1&limit=15&offset=0", headers)
            ?: return@withContext null
        val songs = runCatching {
            JapaneseMatch.arr(JapaneseMatch.obj(JapaneseMatch.obj(lyricsJson.parseToJsonElement(body))?.get("result"))?.get("songs"))
        }.getOrNull() ?: return@withContext null
        val seconds = durationMs / 1000
        val candidates = songs.mapNotNull { JapaneseMatch.obj(it) }.mapNotNull { s ->
            val id = s["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val name = JapaneseMatch.str(s["name"]) ?: return@mapNotNull null
            val artists = JapaneseMatch.arr(s["artists"] ?: s["ar"]).orEmpty()
                .mapNotNull { JapaneseMatch.str(JapaneseMatch.obj(it)?.get("name")) }
            val dur = (s["duration"] ?: s["dt"])?.jsonPrimitive?.longOrNull?.div(1000) ?: 0
            Triple(id, name to artists, dur)
        }.filter { (_, na, dur) ->
            JapaneseMatch.sameTitle(title, na.first) &&
                (na.second.any { JapaneseMatch.sameArtist(artist, it) } || artist.isBlank()) &&
                (seconds <= 0 || dur <= 0 || abs(dur - seconds) <= 6)
        }.sortedBy { abs(it.third - seconds) }
        for ((id, _, _) in candidates.take(3)) {
            val lyric = lyricsGetWith("https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1&rv=-1", headers) ?: continue
            val root = runCatching { JapaneseMatch.obj(lyricsJson.parseToJsonElement(lyric)) }.getOrNull() ?: continue
            fun part(k: String) = JapaneseMatch.str(JapaneseMatch.obj(root[k])?.get("lyric"))?.takeIf { it.isNotBlank() }
            val lrc = part("lrc") ?: continue
            val cleaned = with(KuGou) { lrc.stripCredits() }.takeUnless { with(KuGou) { it.isInstrumentalNotice() } } ?: continue
            val lines = LrcLib.parseLrc(cleaned).takeIf { l -> l.count { it.text.isNotBlank() } >= 2 } ?: continue
            part("romalrc")?.let { ProvidedRomaji.pairTimed(lines, LrcLib.parseLrc(it)) }
            return@withContext lines
        }
        null
    }
}

/** QQ Music: timed lyrics from the other big Chinese catalogue. */
object QqMusic {
    private val headers = mapOf(
        "Referer" to "https://y.qq.com/portal/player.html",
        "User-Agent" to BROWSER_AGENT,
    )

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val q = JapaneseMatch.enc("$title $artist".trim())
        val body = lyricsGetWith(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$q&format=json&p=1&n=15&cr=1&g_tk=5381",
            headers,
        )?.let(::unwrap) ?: return@withContext null
        val list = runCatching {
            val root = JapaneseMatch.obj(lyricsJson.parseToJsonElement(body))
            JapaneseMatch.arr(JapaneseMatch.obj(JapaneseMatch.obj(JapaneseMatch.obj(root?.get("data"))?.get("song")))?.get("list"))
        }.getOrNull() ?: return@withContext null
        val seconds = durationMs / 1000
        val candidates = list.mapNotNull { JapaneseMatch.obj(it) }.mapNotNull { s ->
            val mid = JapaneseMatch.str(s["songmid"]) ?: return@mapNotNull null
            val name = JapaneseMatch.str(s["songname"]) ?: return@mapNotNull null
            val singers = JapaneseMatch.arr(s["singer"]).orEmpty().mapNotNull { JapaneseMatch.str(JapaneseMatch.obj(it)?.get("name")) }
            val dur = s["interval"]?.jsonPrimitive?.longOrNull ?: 0
            Triple(mid, name to singers, dur)
        }.filter { (_, ns, dur) ->
            JapaneseMatch.sameTitle(title, ns.first) &&
                (ns.second.any { JapaneseMatch.sameArtist(artist, it) } || artist.isBlank()) &&
                (seconds <= 0 || dur <= 0 || abs(dur - seconds) <= 6)
        }.sortedBy { abs(it.third - seconds) }
        for ((mid, _, _) in candidates.take(3)) {
            val raw = lyricsGetWith(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1&g_tk=5381",
                headers,
            )?.let(::unwrap) ?: continue
            val root = runCatching { JapaneseMatch.obj(lyricsJson.parseToJsonElement(raw)) }.getOrNull() ?: continue
            val text = JapaneseMatch.str(root["lyric"])?.let(::decode) ?: continue
            val cleaned = with(KuGou) { text.stripCredits() }
            val lines = LrcLib.parseLrc(cleaned).takeIf { l -> l.count { it.text.isNotBlank() } >= 2 } ?: continue
            return@withContext lines
        }
        null
    }

    /** `MusicJsonCallback({...})` → `{...}`. */
    private fun unwrap(body: String): String {
        val t = body.trim()
        if (t.startsWith("{")) return t
        val start = t.indexOf('(')
        val end = t.lastIndexOf(')')
        return if (start >= 0 && end > start) t.substring(start + 1, end) else t
    }

    private fun decode(lyric: String): String {
        val t = lyric.trim()
        if (t.startsWith("[") || t.startsWith("&#")) return EnhancedLrc.decodeEntities(t)
        return runCatching { Base64.getDecoder().decode(t).toString(Charsets.UTF_8) }.getOrDefault(t)
    }
}

/** UtaTen: untimed Japanese lyrics with the kanji readings, and a romaji version. */
object UtaTen {
    private const val BASE = "https://utaten.com"
    private val headers = mapOf("User-Agent" to BROWSER_AGENT, "Accept-Language" to "ja,en;q=0.8")

    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val search = lyricsGetWith(
            "$BASE/search?title=${JapaneseMatch.enc(title)}&artist_name=${JapaneseMatch.enc(artist)}&layout_search_type=title",
            headers,
        ) ?: return@withContext null
        val path = RESULT.findAll(search).firstOrNull { m ->
            JapaneseMatch.sameTitle(title, JapaneseMatch.htmlLines(m.groupValues[2]).joinToString(" "))
        }?.groupValues?.get(1) ?: return@withContext null
        val page = lyricsGetWith(BASE + path, headers) ?: return@withContext null
        val hiragana = block(page, "hiragana") ?: return@withContext null
        // The kanji only: each <span class="rt">reading</span> dropped.
        val original = JapaneseMatch.htmlLines(hiragana.replace(RT, ""))
        block(page, "romaji")?.let { ProvidedRomaji.pairUntimed(original, JapaneseMatch.htmlLines(it.replace(RT, ""))) }
        JapaneseMatch.untimed(original)
    }

    private fun block(page: String, cls: String): String? =
        Regex("""(?is)<div class="$cls"[^>]*>(.*?)</div>""").find(page)?.groupValues?.get(1)

    private val RESULT = Regex("""(?is)<a href="(/lyric/[a-z]{0,3}\d+/?)"[^>]*>(.*?)</a>""")
    private val RT = Regex("""(?is)<span class="rt"[^>]*>.*?</span>""")
}

/** Uta-Net: untimed Japanese lyrics, nearly every release. */
object UtaNet {
    private const val BASE = "https://www.uta-net.com"
    private val headers = mapOf("User-Agent" to BROWSER_AGENT, "Accept-Language" to "ja,en;q=0.8")

    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val search = lyricsGetWith("$BASE/search/?Keyword=${JapaneseMatch.enc(title)}&Aselect=2&Bselect=3", headers)
            ?: return@withContext null
        val rows = ROW.findAll(search).map { it.groupValues[1] }.toList()
        val path = rows.firstNotNullOfOrNull { row ->
            val song = SONG.find(row) ?: return@firstNotNullOfOrNull null
            val name = JapaneseMatch.htmlLines(song.groupValues[2]).joinToString(" ")
            val singer = ARTIST.find(row)?.groupValues?.get(1)?.let { JapaneseMatch.htmlLines(it).joinToString(" ") }.orEmpty()
            song.groupValues[1].takeIf { JapaneseMatch.sameTitle(title, name) && JapaneseMatch.sameArtist(artist, singer) }
        } ?: return@withContext null
        val page = lyricsGetWith(BASE + path, headers) ?: return@withContext null
        val body = KASHI.find(page)?.groupValues?.get(1) ?: return@withContext null
        JapaneseMatch.untimed(JapaneseMatch.htmlLines(body))
    }

    private val ROW = Regex("""(?is)<tr[^>]*>(.*?)</tr>""")
    private val SONG = Regex("""(?is)<a href="(/song/\d+/?)"[^>]*>(.*?)</a>""")
    private val ARTIST = Regex("""(?is)<a href="/artist/\d+/?"[^>]*>(.*?)</a>""")
    private val KASHI = Regex("""(?is)<div id="kashi_area"[^>]*>(.*?)</div>""")
}

/** J-Lyric.net: untimed Japanese lyrics. */
object JLyric {
    private val headers = mapOf("User-Agent" to BROWSER_AGENT, "Accept-Language" to "ja,en;q=0.8")

    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val search = lyricsGetWith(
            "https://search2.j-lyric.net/index.php?kt=${JapaneseMatch.enc(title)}&ct=2&ka=${JapaneseMatch.enc(artist)}&ca=2&kl=&cl=0",
            headers,
        ) ?: return@withContext null
        val url = RESULT.findAll(search).firstOrNull { m ->
            JapaneseMatch.sameTitle(title, JapaneseMatch.htmlLines(m.groupValues[2]).joinToString(" "))
        }?.groupValues?.get(1) ?: return@withContext null
        val page = lyricsGetWith(url, headers) ?: return@withContext null
        val body = LYRIC.find(page)?.groupValues?.get(1) ?: return@withContext null
        JapaneseMatch.untimed(JapaneseMatch.htmlLines(body))
    }

    private val RESULT = Regex("""(?is)<a href="(https://j-lyric\.net/artist/[a-z0-9]+/l[a-z0-9]+\.html)"[^>]*>(.*?)</a>""")
    private val LYRIC = Regex("""(?is)<p id="Lyric"[^>]*>(.*?)</p>""")
}
