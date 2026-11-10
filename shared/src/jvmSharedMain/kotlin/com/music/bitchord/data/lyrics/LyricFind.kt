package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * BitChord Audio: LyricFind — the licensed lyrics catalogue behind Google's
 * lyrics panels, strong on Japanese releases. Read from its public lyrics
 * site: the search answers JSON, a song's page carries its data as Next.js
 * page data (`__NEXT_DATA__`). Both are read loosely — any "lyrics" text and,
 * where present, any timed lines — so a change in their layout costs a miss,
 * not a crash. Untimed as a rule: asked once the timed sources have missed.
 */
object LyricFind {

    private const val SITE = "https://lyrics.lyricfind.com"
    private val headers = mapOf(
        "User-Agent" to BROWSER_AGENT,
        "Accept" to "application/json, text/html;q=0.9",
        "Referer" to "$SITE/",
    )

    private data class Hit(val slug: String, val title: String, val artist: String, val seconds: Long)

    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = durationMs / 1000
        val hits = search(title, artist).filter { h ->
            JapaneseMatch.sameTitle(title, h.title) &&
                (artist.isBlank() || h.artist.isBlank() || JapaneseMatch.sameArtist(artist, h.artist)) &&
                (seconds <= 0 || h.seconds <= 0 || abs(h.seconds - seconds) <= 6)
        }
        for (h in hits.take(2)) {
            val page = lyricsGetWith("$SITE/lyrics/${h.slug}", headers) ?: continue
            lyricsFrom(page)?.let { return@withContext it }
        }
        null
    }

    private fun search(title: String, artist: String): List<Hit> {
        val q = JapaneseMatch.enc("$title $artist".trim())
        val urls = listOf(
            "$SITE/api/v1/search?reqtype=default&territory=US&searchtype=track&all=$q&alltracks=no&limit=10&output=json",
            "$SITE/api/v1/search?reqtype=default&territory=JP&searchtype=track&all=$q&alltracks=no&limit=10&output=json",
        )
        val out = ArrayList<Hit>()
        for (u in urls) {
            val body = lyricsGetWith(u, headers) ?: continue
            val root = runCatching { lyricsJson.parseToJsonElement(body) }.getOrNull() ?: continue
            collectTracks(root, out)
            if (out.isNotEmpty()) break
        }
        return out.distinctBy { it.slug }
    }

    /** Any object with a slug and a title, wherever the answer keeps its tracks. */
    private fun collectTracks(e: JsonElement, out: MutableList<Hit>) {
        when (e) {
            is JsonArray -> e.forEach { collectTracks(it, out) }
            is JsonObject -> {
                val slug = str(e["slug"])
                val title = str(e["title"]) ?: str(e["name"])
                if (slug != null && title != null) {
                    val artist = str(e["artist"]) ?: (e["artist"] as? JsonObject)?.let { str(it["name"]) }
                        ?: (e["artists"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.let { a -> str(a["name"]) } }
                        ?: ""
                    out += Hit(slug, title, artist, seconds(str(e["duration"])))
                }
                e.values.forEach { collectTracks(it, out) }
            }
            else -> Unit
        }
    }

    private fun seconds(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        text.toLongOrNull()?.let { return if (it > 10_000) it / 1000 else it }
        val parts = text.split(':').mapNotNull { it.trim().toLongOrNull() }
        return if (parts.size == 2) parts[0] * 60 + parts[1] else 0
    }

    private val NEXT_DATA = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Timed lines if the page has them, else the plain text. */
    private fun lyricsFrom(page: String): List<LyricLine>? {
        val json = NEXT_DATA.find(page)?.groupValues?.get(1) ?: return null
        val root = runCatching { lyricsJson.parseToJsonElement(json) }.getOrNull() ?: return null
        timed(root)?.let { return it }
        val text = longestText(root) ?: return null
        return JapaneseMatch.untimed(text.replace("\r", "").lines().map { it.trim() })
    }

    /** An array of {line/text, milliseconds/time} objects, as some lyric pages carry. */
    private fun timed(e: JsonElement): List<LyricLine>? {
        when (e) {
            is JsonArray -> {
                val lines = e.mapNotNull { item ->
                    val o = item as? JsonObject ?: return@mapNotNull null
                    val text = str(o["line"]) ?: str(o["text"]) ?: str(o["words"]) ?: return@mapNotNull null
                    val ms = (str(o["milliseconds"]) ?: str(o["startTimeMs"]) ?: str(o["time"]))?.toDoubleOrNull() ?: return@mapNotNull null
                    LyricLine(timeMs = if (ms < 10_000 && ms % 1 != 0.0) (ms * 1000).toLong() else ms.toLong(), text = text)
                }
                if (lines.size >= 4 && lines.size >= e.size * 0.8) return lines.sortedBy { it.timeMs }
                e.forEach { child -> timed(child)?.let { return it } }
            }
            is JsonObject -> e.values.forEach { child -> timed(child)?.let { return it } }
            else -> Unit
        }
        return null
    }

    /** The longest string under a key named like "lyrics". */
    private fun longestText(e: JsonElement): String? {
        var best: String? = null
        fun walk(x: JsonElement) {
            when (x) {
                is JsonObject -> x.forEach { (k, v) ->
                    if (k.contains("lyric", ignoreCase = true)) {
                        str(v)?.takeIf { it.lines().size >= 4 && it.length > (best?.length ?: 0) }?.let { best = it }
                    }
                    walk(v)
                }
                is JsonArray -> x.forEach(::walk)
                else -> Unit
            }
        }
        walk(e)
        return best
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
