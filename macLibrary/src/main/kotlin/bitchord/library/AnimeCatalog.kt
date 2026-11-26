package bitchord.library

import org.json.JSONObject
import java.io.File

/**
 * Comprehensive anime & game franchise catalog (`anime-catalog.json`):
 *  - Maps every anime franchise (Naruto, Pokémon, Inuyasha, Tsubasa Chronicle, Sailor Moon,
 *    Cardcaptor Sakura, Detective Conan, Digimon, Dragon Ball, Bleach, Slayers, Evangelion,
 *    Kimetsu no Yaiba, Madoka Magica, Fate, Sword Art Online, Noir, Hack, Tales of, etc.)
 *    with all TV Openings, Endings (in order of appearance across sequels/OVAs), Movies themes,
 *    and soundtrack hints.
 *  - Drives:
 *      1. Placement of anime/game songs by small artists (<= 4 studio albums, e.g. Every Little Thing,
 *         Dream, V6, Aikawa Nanase) directly into `<Anime>/Openings/`, `Endings/`, `Películas/`.
 *      2. Cross-artist macOS Finder aliases + `.m3u` + `.txt` inside `<Anime>/Openings/`, `Endings/`,
 *         `Películas/`, and `Bandas sonoras/` pointing to real audio files owned by big artists (> 4 albums:
 *         Ayumi Hamasaki, Maaya Sakamoto, Yuki Kajiura, Aimer, ZARD, Utada Hikaru, L'Arc~en~Ciel, etc.).
 */
object AnimeCatalog {
    data class Theme(
        val series: String,
        val kind: String, // "Openings", "Endings", "Películas"
        val no: Int,
        val show: String,
        val year: String?,
        val title: String,
        val jp: String,
        val artist: String,
        val movie: String? = null,
    )

    data class SeriesInfo(
        val name: String,
        val aliases: List<String>,
        val soundtrackHints: List<String>,
        val themes: List<Theme>,
    )

    data class CrossResult(
        val sidecars: Map<String, String>,
        val aliases: Map<String, String>,
    )

    val cacheFile: File get() = File(Store.dir, "anime-catalog.json")

    private class Db(
        val series: Map<String, SeriesInfo>,
        val aliasMap: Map<String, String>,
        val byCore: Map<String, List<Theme>>,
    )

    @Volatile private var dbCache: Pair<Long, Db>? = null

    private fun loadDb(): Db {
        val m = if (cacheFile.isFile) cacheFile.lastModified() else -1L
        dbCache?.takeIf { it.first == m }?.let { return it.second }
        val seriesMap = LinkedHashMap<String, SeriesInfo>()
        val aliasMap = HashMap<String, String>()
        val byCore = HashMap<String, MutableList<Theme>>()
        runCatching {
            if (!cacheFile.isFile) return@runCatching
            val root = JSONObject(cacheFile.readText()).optJSONObject("series") ?: return@runCatching
            for (sName in root.keySet()) {
                val o = root.getJSONObject(sName)
                val als = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val hints = o.optJSONArray("soundtrackHints")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val themes = ArrayList<Theme>()
                fun addArr(key: String, kind: String) {
                    val arr = o.optJSONArray(key) ?: return
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        val th = Theme(
                            series = sName,
                            kind = kind,
                            no = t.optInt("no", i + 1),
                            show = t.optString("show", sName),
                            year = t.optString("year").ifBlank { null },
                            title = t.optString("title"),
                            jp = t.optString("jp"),
                            artist = t.optString("artist"),
                            movie = t.optString("movie").ifBlank { null },
                        )
                        themes += th
                        val cores = listOf(Themes.core(th.title), Words.plain(th.title), Words.plain(th.jp)).filter { it.length >= 3 }.distinct()
                        for (c in cores) byCore.getOrPut(c) { ArrayList() }.add(th)
                    }
                }
                addArr("openings", "Openings")
                addArr("endings", "Endings")
                addArr("movies", "Películas")
                seriesMap[sName] = SeriesInfo(sName, als, hints, themes)
                (als + sName).forEach { al ->
                    val p = Words.plain(al)
                    if (p.length >= 3) aliasMap[p] = sName
                }
            }
        }
        return Db(seriesMap, aliasMap, byCore).also { dbCache = m to it }
    }

    /** All franchise aliases loaded from anime-catalog.json (plain(alias) -> canonical series name). */
    fun allAliases(): Map<String, String> = loadDb().aliasMap

    private fun titleCores(title: String): List<String> {
        val clean = title.replace(Regex("(?iu)\\b(tv\\s*size|full\\s*ver\\.?|movie\\s*ver\\.?|short\\s*ver\\.?|album\\s*ver\\.?|single\\s*ver\\.?|remaster(?:ed)?|op\\s*\\d*|ed\\s*\\d*|opening\\s*\\d*|ending\\s*\\d*)\\b"), " ")
        val out = ArrayList<String>()
        Themes.core(clean).takeIf { it.length >= 3 }?.let { out += it }
        Words.plain(clean).takeIf { it.length >= 3 }?.let { out += it }
        Regex("[(（「『]([^)）」』]+)[)）」』]").findAll(title).forEach { m ->
            val inside = m.groupValues[1]
            Themes.core(inside).takeIf { it.length >= 3 }?.let { out += it }
            Words.plain(inside).takeIf { it.length >= 3 }?.let { out += it }
        }
        return out.distinct()
    }

    private fun artistMatches(trackArtist: String, themeArtist: String): Boolean {
        if (trackArtist.isBlank() || themeArtist.isBlank()) return false
        val ta = Builder.pkey(trackArtist.substringBefore(" ("))
        val th = Builder.pkey(themeArtist.substringBefore(" ("))
        if (ta.isNotEmpty() && th.isNotEmpty() && (ta == th || (ta.length >= 5 && th.contains(ta)) || (th.length >= 5 && ta.contains(th)))) return true
        val taPlain = Words.plain(trackArtist)
        val thPlain = Words.plain(themeArtist)
        if (taPlain.length >= 4 && thPlain.contains(taPlain) || thPlain.length >= 4 && taPlain.contains(thPlain)) return true
        // Handle Japanese artist inside parentheses ("Ayumi Hamasaki (浜崎あゆみ)" vs "浜崎あゆみ")
        val jpA = Regex("[(（]([^)）]+)[)）]").find(trackArtist)?.groupValues?.get(1)?.let { Words.plain(it) }.orEmpty()
        if (jpA.length >= 2 && thPlain.contains(jpA)) return true
        return false
    }

    /** Matches a track against anime-catalog.json. When [seriesHint] is null, requires artist confirmation to prevent false matches on generic words. */
    fun matchTheme(title: String, artist: String, seriesHint: String? = null): Theme? {
        val db = loadDb()
        val cores = titleCores(title)
        if (cores.isEmpty()) return null
        val candidates = cores.flatMap { db.byCore[it].orEmpty() }.distinct()
        if (candidates.isEmpty()) return null
        if (seriesHint != null) {
            val sh = Words.plain(seriesHint)
            val inSeries = candidates.filter { Words.plain(it.series) == sh }
            if (inSeries.isNotEmpty()) {
                return inSeries.firstOrNull { artistMatches(artist, it.artist) } ?: inSeries.first()
            }
        }
        // Without a series hint, strictly require artist match AND non-trivial title length (>= 4) or exact CJK match.
        return candidates.firstOrNull { th ->
            artistMatches(artist, th.artist) && (Words.plain(th.title).length >= 4 || (th.jp.length >= 2 && title.contains(th.jp)))
        }
    }

    /** Matches a soundtrack album/folder (e.g. Yuki Kajiura/Bandas sonoras/Tsubasa Chronicle/...) to its anime series. */
    fun soundtrackSeriesFor(album: String, relPath: String): String? {
        val db = loadDb()
        val p = Words.plain("$relPath $album")
        for (info in db.series.values) {
            if (info.soundtrackHints.any { h -> Words.plain(h).let { it.length >= 4 && p.contains(it) } }) return info.name
        }
        return null
    }

    private val NON_MAIN = Regex("(?iu)\\b(remix|rmx|mix|live|en directo|instrumental|karaoke|off ?vocal|piano ver|acoustic|orchestra|orgel|music box|demo|tv ?size|short ver)\\b|リミックス|ライブ|インスト")

    /**
     * Generates cross-artist macOS Finder aliases + .m3u + .txt inside `<Anime>/Openings/`, `Endings/`, `Películas/`,
     * and `Bandas sonoras/` pointing to real audio files owned by artists with their own folders (e.g. > 4 albums or
     * artists with non-anime discography like Ayumi Hamasaki, Maaya Sakamoto, Yuki Kajiura, BUCK-TICK, Aki Okui, Aimer, ZARD).
     * Only populates series already present in the library OR franchises with >= 2 matched tracks (never 1-alias folders).
     */
    fun buildCrossAliases(items: List<Builder.Item>, targetRoot: String): CrossResult {
        val db = loadDb()
        if (db.series.isEmpty()) return CrossResult(emptyMap(), emptyMap())
        fun rel(p: String) = Words.nfc(p).removePrefix(Words.nfc(targetRoot)).trimStart('/')
        val existingTops = items.map { rel(it.target).substringBefore('/') }.toSet()
        val existingAnimeSub = items.mapNotNull { i ->
            val r = rel(i.target)
            if (r.startsWith("Anime/")) r.split('/').getOrNull(1) else null
        }.toSet()
        // Never overwrite One Piece (already managed by OnePiece.kt).
        val skipSeries = setOf("One Piece")

        data class Link(val series: String, val subFolder: String, val sortNo: Int, val year: String?, val aliasName: String, val item: Builder.Item, val displayTitle: String)
        val pending = ArrayList<Link>()
        val seenPerBucket = HashSet<String>()

        for (i in items) {
            val r = rel(i.target)
            val top = r.substringBefore('/')
            val isArtistOwned = i.grouping == null || top.startsWith("Yuki Kajiura")
            // 1. Soundtrack albums inside Yuki Kajiura/Bandas sonoras/<Series>/... -> alias into <Series>/Bandas sonoras/<Album>/
            if (isArtistOwned && r.contains("/Bandas sonoras/")) {
                val sName = soundtrackSeriesFor(i.album, r)
                if (sName != null && sName !in skipSeries && !top.equals(sName, ignoreCase = true) && i.album.isNotBlank()) {
                    val albDir = Builder.clean(listOfNotNull(i.year, i.album).joinToString(" - "))
                    val numStr = i.trackNo?.toString()?.padStart(2, '0')
                    val aliasStem = listOfNotNull(numStr, i.title, i.artist.ifBlank { null }, i.album.ifBlank { null }, i.year?.ifBlank { null }).joinToString(" - ") { Builder.clean(it) }
                    pending += Link(sName, "Bandas sonoras/$albDir", i.trackNo ?: 999, i.year, "$aliasStem.alias", i, i.title)
                }
            }
            // 2. Openings, Endings, and Movie theme songs:
            //    - From artists who kept their own folder (e.g. Ayumi Hamasaki -> Inuyasha/Endings/, Maaya Sakamoto -> Tsubasa Chronicle/Endings/)
            //    - OR from an album/compilation inside the same anime series (e.g. Tsubasa Chronicle/2006 - Best Vocal Collection/Loop -> Tsubasa Chronicle/Endings/01 - Loop.alias!)
            if (NON_MAIN.containsMatchIn(i.title) || r.contains("/Directos/") || r.contains("/Remixes/") || r.contains("/Canciones en español/")) continue
            val th = matchTheme(i.title, i.artist) ?: continue
            if (th.series in skipSeries) continue
            val inSameSeriesAlbum = (top.equals(th.series, ignoreCase = true) || r.startsWith("Anime/${th.series}/", ignoreCase = true)) &&
                !r.contains("/Openings/") && !r.contains("/Endings/") && !r.contains("/Películas/")
            if (!isArtistOwned && !inSameSeriesAlbum) continue
            // Skip if the series' target subfolder (Openings/Endings/Películas) ALREADY holds a physical copy of this exact theme song!
            val targetSub = if (th.kind == "Películas") "Películas" else th.kind
            val alreadyPhysical = items.any { other ->
                val or = rel(other.target)
                !or.contains("/Canciones en español/") &&
                    (or.startsWith("${th.series}/$targetSub/") || or.startsWith("Anime/${th.series}/$targetSub/")) &&
                    Words.plain(other.title).let { ot -> ot.isNotEmpty() && titleCores(i.title).any { c -> ot.contains(c) || c.contains(ot) } } &&
                    kotlin.math.abs((other.track.seconds ?: 0.0) - (i.track.seconds ?: -99.0)) <= 6.0
            }
            if (alreadyPhysical) continue
            val bucketKey = "${th.series}|${th.kind}|${th.no}|${Words.plain(th.title)}"
            if (!seenPerBucket.add(bucketKey)) continue
            val sub = if (th.kind == "Películas") "Películas" else th.kind
            val aliasStem = if (th.kind == "Películas") {
                val y = th.year ?: i.year ?: "0000"
                val num = th.no.toString().padStart(2, '0')
                listOfNotNull(y, num, i.title, i.artist.ifBlank { null }, th.movie?.ifBlank { null }, i.album.ifBlank { null }, i.year?.ifBlank { null }).joinToString(" - ") { Builder.clean(it) }
            } else {
                val num = th.no.toString().padStart(2, '0')
                listOfNotNull(num, i.title, i.artist.ifBlank { null }, i.album.ifBlank { null }, i.year?.ifBlank { null }).joinToString(" - ") { Builder.clean(it) }
            }
            pending += Link(th.series, sub, th.no, th.year ?: i.year, "$aliasStem.alias", i, i.title)
        }

        // Only emit into existing series OR franchises with >= 2 matched tracks (avoid 1-alias empty folders!).
        val bySeries = pending.groupBy { it.series }
        val aliases = LinkedHashMap<String, String>()
        val sidecars = LinkedHashMap<String, String>()
        val physicalByDir = items.groupBy { Words.nfc(it.target).substringBeforeLast('/') }

        for ((sName, links) in bySeries) {
            val targetBase = when {
                sName in existingTops -> "$targetRoot/$sName"
                sName in existingAnimeSub -> "$targetRoot/Anime/$sName"
                links.size >= 2 -> "$targetRoot/$sName"
                else -> continue
            }
            for ((subFolder, subLinks) in links.groupBy { it.subFolder }) {
                val home = Words.nfc("$targetBase/$subFolder")
                // Combine physical files already in this folder + cross-artist alias links into one unified playlist & index.
                val physEntries = physicalByDir[home].orEmpty().map { it ->
                    Link(sName, subFolder, it.trackNo ?: 999, it.year, "", it, it.title)
                }
                val combined = (physEntries + subLinks).sortedWith(compareBy({ if (subFolder == "Películas") (it.year ?: "9999") else "" }, { it.sortNo }, { it.displayTitle.lowercase() }))
                val yearSeq = HashMap<String, Int>()
                val dirPath = java.nio.file.Paths.get(home)
                val m3u = buildString {
                    append("#EXTM3U\n#PLAYLIST:").append(Words.nfc("$sName - $subFolder")).append("\n")
                    for (lk in combined) {
                        append("#EXTINF:").append((lk.item.track.seconds ?: 0.0).toInt()).append(',').append(Words.nfc(lk.item.artist)).append(" - ").append(Words.nfc(lk.displayTitle)).append('\n')
                        append(Words.nfc(dirPath.relativize(java.nio.file.Paths.get(Words.nfc(lk.item.target))).toString())).append('\n')
                    }
                }
                val txt = buildString {
                    append(Words.nfc("$sName — $subFolder")).append("\n\n")
                    append("Las canciones de artistas con discografía propia viven en su carpeta y aquí tienes su alias de macOS:\n\n")
                    for (lk in combined) {
                        val isHere = lk.aliasName.isEmpty()
                        if (!isHere) {
                            val finalAlias = if (subFolder == "Películas") {
                                val y = lk.year ?: "0000"
                                val seq = yearSeq.merge(y, 1, Int::plus)!!.toString().padStart(2, '0')
                                lk.aliasName.replaceFirst(Regex("^\\d{4} - \\d{2} - "), "$y - $seq - ")
                            } else lk.aliasName
                            aliases.putIfAbsent(Words.nfc("$home/$finalAlias"), Words.nfc(lk.item.target))
                        } else if (subFolder == "Películas") {
                            yearSeq.merge(lk.year ?: "0000", 1, Int::plus)
                        }
                        val where = Words.nfc(rel(lk.item.target).substringBeforeLast('/', ""))
                        val numLabel = if (lk.sortNo in 1..998) lk.sortNo.toString().padStart(2, '0') + ". " else "· "
                        append(numLabel).append(Words.nfc(lk.displayTitle))
                            .append(" (").append(Words.nfc(lk.item.artist)).append(")")
                            .append(if (isHere) "  (aquí)" else "  → $where").append('\n')
                    }
                }
                sidecars.putIfAbsent(Words.nfc("$home/${Builder.SIDECAR_M3U}"), m3u)
                sidecars.putIfAbsent(Words.nfc("$home/${Builder.SIDECAR_TXT}"), txt)
            }
        }
        return CrossResult(sidecars, aliases)
    }
}
