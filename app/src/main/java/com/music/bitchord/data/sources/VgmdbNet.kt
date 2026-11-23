package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.CrossScript
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: VGMdb read from vgmdb.net itself.
 *
 * vgmdb.info, the JSON mirror every VGMdb lookup used, has been unreachable since
 * November 2025 — every look-up failed ("VGMdb did not answer"). This reads the same
 * pages vgmdb.info read and gives back the same JSON shape (its own parsers, search.py
 * and album.py, followed), so the callers keep working unchanged: `search/albums/<q>`,
 * `search/artists/<q>` and `album/<id>`.
 */
object VgmdbNet {
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val cache = ConcurrentHashMap<String, String>()

    /** A vgmdb.info address, answered from vgmdb.net; null when it can't be. */
    fun text(infoUrl: String): String? {
        val path = infoUrl.substringAfter("vgmdb.info/").substringBefore('?').trim('/')
        cache[path]?.let { return it }
        val out = runCatching {
            when {
                path.startsWith("search/albums/") -> search(decode(path.removePrefix("search/albums/")))?.let { only(it, "albums") }
                path.startsWith("search/artists/") -> search(decode(path.removePrefix("search/artists/")))?.let { only(it, "artists") }
                path.startsWith("search/") -> search(decode(path.removePrefix("search/")))
                path.startsWith("album/") -> album(path)
                else -> null
            }
        }.onFailure { TrackLog.d("BitChord", "vgmdb.net: $path failed: ${it.message}") }.getOrNull()?.toString()
        if (out != null) cache[path] = out
        return out
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun only(o: JSONObject, section: String): JSONObject =
        JSONObject().put("results", JSONObject().put(section, o.optJSONObject("results")?.optJSONArray(section) ?: JSONArray()))

    private fun fetch(url: String): Pair<String, String>? {
        val req = Request.Builder().url(url)
            .header("User-Agent", AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9,ja;q=0.8")
            .build()
        return com.music.bitchord.data.Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.request.url.toString() to (r.body?.string() ?: return@use null)
        }
    }

    // ── search.py ───────────────────────────────────────────────────────────

    private fun search(query: String): JSONObject? {
        val url = "https://vgmdb.net/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val (landed, html) = fetch(url) ?: return null
        // One exact hit: VGMdb goes straight to its page (vgmdb.info's "masquerade").
        val direct = Regex("""vgmdb\.net/(album|artist)/(\d+)""").find(landed)
        if (direct != null && !landed.contains("/search")) {
            val (kind, id) = direct.destructured
            val doc = Jsoup.parse(html, landed)
            val main = doc.getElementById("innermain") ?: return null
            val names = names(main.selectFirst("h1") ?: return null)
            val item = JSONObject().put("link", "$kind/$id")
            if (kind == "album") item.put("titles", names) else item.put("names", names)
            return JSONObject().put("results", JSONObject().put(if (kind == "album") "albums" else "artists", JSONArray().put(item)))
        }
        val doc = Jsoup.parse(html, url)
        val main = doc.getElementById("innermain") ?: return null
        val results = JSONObject()
        main.selectFirst("#albumresults")?.let { sec ->
            val list = JSONArray()
            for (row in sec.select("tr").drop(1)) {
                val cells = row.children().filter { it.tagName() == "td" }
                if (cells.size < 3) continue
                val a = cells[2].selectFirst("a") ?: continue
                val item = JSONObject()
                    .put("link", trim(a.attr("href")))
                    .put("catalog", cells[0].selectFirst("span")?.text().orEmpty())
                    .put("titles", names(a))
                cells.getOrNull(3)?.text()?.let { date(it) }?.let { item.put("release_date", it) }
                cells.getOrNull(4)?.text()?.let { item.put("media_format", it) }
                list.put(item)
            }
            results.put("albums", list)
        }
        main.selectFirst("#artistresults")?.let { sec ->
            val list = JSONArray()
            for (row in sec.select("tr").drop(1)) {
                val cell = row.children().firstOrNull { it.tagName() == "td" } ?: continue
                val a = cell.selectFirst("a") ?: continue
                val item = JSONObject().put("link", trim(a.attr("href"))).put("names", names(a))
                cell.children().firstOrNull { it.tagName() == "span" }?.text()?.let { al ->
                    item.put("aliases", JSONArray(al.split('/').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                list.put(item)
            }
            results.put("artists", list)
        }
        return JSONObject().put("results", results)
    }

    // ── album.py ────────────────────────────────────────────────────────────

    private fun album(path: String): JSONObject? {
        val url = "https://vgmdb.net/$path"
        val (_, html) = fetch(url) ?: return null
        val doc = Jsoup.parse(html, url)
        val main = doc.getElementById("innermain") ?: return null
        val o = JSONObject()
        val title = names(main.selectFirst("h1") ?: return null)
        o.put("names", title).put("name", title.optString("en").ifBlank { title.optString("ja") })
        main.getElementById("coverart")?.attr("style")?.let { style ->
            Regex("""url\(['"]?([^'")]+)""").find(style)?.groupValues?.get(1)?.let { medium ->
                val abs = if (medium.startsWith("http")) medium else "https://vgmdb.net/" + medium.trimStart('/')
                o.put("picture_small", abs).put("picture_full", abs.replace("/medium-media/", "/full-media/"))
                    .put("picture_thumb", abs.replace("/medium-media/", "/thumb-media/"))
            }
        }
        for (k in listOf("arrangers", "composers", "lyricists", "performers", "vocals", "organizations")) o.put(k, JSONArray())
        // The info table and the credits table (both carry this id on vgmdb.net).
        main.select("table#album_infobit_large").forEach { info(it, o) }
        o.put("discs", tracklist(main))
        // Right column: category and products.
        doc.getElementById("rightcolumn")?.let { right ->
            for (b in right.select("b")) {
                val label = b.text().trim()
                val box = b.parent() ?: continue
                if (label == "Category") {
                    val cats = box.textNodes().joinToString(" ") { it.text() }.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (cats.isNotEmpty()) { o.put("category", cats.first()); o.put("categories", JSONArray(cats)) }
                }
                if (label == "Products represented") {
                    val products = JSONArray()
                    box.select("a").forEach { a -> products.put(JSONObject().put("names", names(a)).apply { trim(a.attr("href")).takeIf { it.startsWith("product/") }?.let { put("link", it) } }) }
                    box.textNodes().flatMap { it.text().split(',') }.map { it.trim() }.filter { it.length > 1 }
                        .forEach { products.put(JSONObject().put("names", JSONObject().put("en", it))) }
                    o.put("products", products)
                }
            }
        }
        return o
    }

    private val MULTIPLE = mapOf(
        "Composed by" to "composers", "Arranged by" to "arrangers", "Performed by" to "performers", "Lyrics by" to "lyricists",
        "Composer" to "composers", "Arranger" to "arrangers", "Performer" to "performers", "Lyricist" to "lyricists",
        "Vocals" to "vocals", "All Composed by" to "composers", "All Music Composed by" to "composers",
        "All Arranged by" to "arrangers", "All Music Arranged by" to "arrangers", "Music" to "composers",
    )
    private val ORGS = setOf("Publisher", "Distributor", "Retailer", "Exclusive Retailer", "Manufacturer", "Label")

    private fun info(table: Element, o: JSONObject) {
        for (row in table.select("tr")) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 2) continue
            val label = cells[0].selectFirst("b")?.let { b -> names(b).optString("en").ifBlank { b.text() } }?.trim() ?: continue
            val value = cells[1]
            when {
                label == "Catalog Number" -> o.put("catalog", value.text().substringBefore('(').trim().substringBefore(' ').ifBlank { value.text().trim() })
                label == "Release Date" -> {
                    val cal = value.selectFirst("a[href*=#]")?.attr("href")?.substringAfter('#')
                    val d = if (cal != null && cal.length == 8 && cal.all { it.isDigit() }) "${cal.substring(0, 4)}-${cal.substring(4, 6)}-${cal.substring(6, 8)}"
                    else date(value.text())
                    d?.let { o.put("release_date", it) }
                }
                label == "Media Format" -> o.put("media_format", value.text().trim())
                label == "Classification" -> o.put("classification", value.text().trim())
                label == "Barcode" -> o.put("barcode", value.text().trim())
                label in ORGS -> value.select("a").forEach { a ->
                    o.getJSONArray("organizations").put(JSONObject().put("role", label.substringAfterLast(' ').lowercase()).put("names", names(a)).put("link", trim(a.attr("href"))))
                }
                label == "Organizations" -> orgs(value, o)
                label in MULTIPLE -> {
                    val list = o.getJSONArray(MULTIPLE.getValue(label))
                    for (child in value.childNodes()) {
                        when {
                            child is Element && child.tagName() == "a" -> list.put(JSONObject().put("names", names(child)).put("link", trim(child.attr("href"))))
                            child is Element && child.tagName() == "span" && child.selectFirst("a") == null -> child.text().trim('(', ')', ' ', ',').split(',')
                                .map { it.trim() }.filter { it.isNotEmpty() }.forEach { list.put(JSONObject().put("names", JSONObject().put("en", it))) }
                            child is TextNode -> child.text().trim('(', ')', ' ').split(',', '/')
                                .map { it.trim() }.filter { it.isNotEmpty() }.forEach { list.put(JSONObject().put("names", JSONObject().put("en", it))) }
                        }
                    }
                }
            }
        }
    }

    private fun orgs(value: Element, o: JSONObject) {
        val root = value.getElementById("publisher_less") ?: value
        var pending: JSONObject? = null
        for (child in root.childNodes()) {
            if (child is Element && child.tagName() == "a") {
                pending?.let { o.getJSONArray("organizations").put(it.put("role", "publisher")) }
                pending = JSONObject().put("names", names(child)).put("link", trim(child.attr("href")))
            } else {
                val text = (child as? TextNode)?.text() ?: (child as? Element)?.text() ?: continue
                val roles = text.substringAfter('(', "").substringBefore(')').split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val org = pending ?: text.substringBefore('(').trim().takeIf { it.length > 3 && roles.isNotEmpty() }?.let { JSONObject().put("names", JSONObject().put("en", it)) }
                if (org != null && roles.isNotEmpty()) {
                    for (r in roles) o.getJSONArray("organizations").put(JSONObject(org.toString()).put("role", r))
                    pending = null
                }
            }
        }
        pending?.let { o.getJSONArray("organizations").put(it.put("role", "publisher")) }
    }

    /** The track list in each language tab: names keyed by tab language, as vgmdb.info gave them. */
    private fun tracklist(main: Element): JSONArray {
        val discs = JSONArray()
        val tabs = main.getElementById("tlnav")?.select("li a")?.map { it.text().trim() }.orEmpty()
        val panes = main.select("#tracklist span.tl")
        panes.forEachIndexed { tab, pane ->
            val language = tabs.getOrNull(tab) ?: if (tab == 0) "English" else "Lang$tab"
            var discIndex = 0
            for (table in pane.select("table")) {
                val disc = if (discIndex < discs.length()) discs.getJSONObject(discIndex)
                else JSONObject().put("tracks", JSONArray()).also { discs.put(it) }
                val tracks = disc.getJSONArray("tracks")
                var n = 0
                for (row in table.select("tr")) {
                    val cells = row.children().filter { it.tagName() == "td" }
                    if (cells.size < 3) continue
                    val name = cells[1].text().trim()
                    val length = cells[2].text().trim().ifBlank { "Unknown" }
                    val track = if (n < tracks.length()) tracks.getJSONObject(n)
                    else JSONObject().put("names", JSONObject()).put("track_length", length).also { tracks.put(it) }
                    track.getJSONObject("names").put(language, name)
                    n++
                }
                discIndex++
            }
        }
        return discs
    }

    // ── utils.py ────────────────────────────────────────────────────────────

    /** parse_names: the element's own text (English, or Japanese when it isn't) and each span[lang]. */
    private fun names(el: Element): JSONObject {
        val o = JSONObject()
        val own = el.textNodes().joinToString("") { it.text() }.trim()
        if (own.isNotEmpty()) o.put(if (CrossScript.hasJapanese(own)) "ja" else "en", own)
        for (span in el.children()) {
            if (span.tagName() != "span" || !span.hasAttr("lang")) continue
            val t = span.text().trim().trimStart('/').trim()
            if (t.isNotEmpty()) o.put(span.attr("lang").lowercase(), t)
        }
        if (o.length() == 0) el.text().trim().takeIf { it.isNotEmpty() }?.let { o.put(if (CrossScript.hasJapanese(it)) "ja" else "en", it) }
        return o
    }

    private fun trim(link: String): String =
        link.removePrefix("https://vgmdb.net/").removePrefix("http://vgmdb.net/").trimStart('/')

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun date(s: String): String? {
        val t = s.trim()
        if (t.length == 4 && t.all { it.isDigit() }) return t
        val m = Regex("""([A-Za-z]{3})[a-z]*\.?\s+(?:(\d{1,2}),\s*)?(\d{4})""").find(t) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1]).takeIf { it >= 0 }?.plus(1) ?: return null
        val day = m.groupValues[2].toIntOrNull()
        return if (day != null) "%s-%02d-%02d".format(m.groupValues[3], month, day) else "%s-%02d".format(m.groupValues[3], month)
    }
}
