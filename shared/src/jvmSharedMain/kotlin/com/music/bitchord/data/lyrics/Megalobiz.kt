package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Line-synced community LRC scraped from Megalobiz. */
object Megalobiz {
    private const val BASE = "https://www.megalobiz.com"

    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val search = "$BASE/searchall".toHttpUrl().newBuilder()
            .addQueryParameter("qry", "$artist $title".trim()).build()
        val results = lyricsGet(search.toString()) ?: return@withContext null
        // BitChord Audio: the search always lists something; only a file that
        // names this song (in its [ti:] header, else in its address) is taken.
        for (link in LRC_LINK.findAll(results).take(3)) {
            val path = link.groupValues[1]
            val page = lyricsGet(BASE + path.replace("&amp;", "&")) ?: continue
            val raw = LRC_BODY.find(page)?.groupValues?.get(1) ?: continue
            val lrc = raw.replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""<[^>]+>"""), "")
                .let(EnhancedLrc::decodeEntities)
            val declared = Regex("""\[ti:([^\]]*)]""", RegexOption.IGNORE_CASE).find(lrc)?.groupValues?.get(1)?.trim()
            val named = if (!declared.isNullOrBlank()) JapaneseMatch.sameTitle(title, declared)
            else JapaneseMatch.norm(java.net.URLDecoder.decode(path, "UTF-8")).contains(JapaneseMatch.norm(title))
            if (!named) continue
            LrcLib.parseLrc(lrc).takeIf { lines -> lines.any { it.text.isNotBlank() } }?.let { return@withContext it }
        }
        null
    }

    private val LRC_LINK = Regex("""href=[\"'](/lrc/maker/download/[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val LRC_BODY = Regex(
        """id=[\"']lrc_[^\"']*_details[\"'][^>]*>(.*?)</span>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
