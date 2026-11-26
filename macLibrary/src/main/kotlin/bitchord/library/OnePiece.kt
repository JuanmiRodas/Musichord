package bitchord.library

import java.io.File

/**
 * Dedicated organizer for One Piece when a full `One Piece OST` discography is present:
 *  - `One Piece/Openings/`  — all TV openings, numbered in order of appearance (01..28);
 *  - `One Piece/Endings/`   — all TV endings, numbered in order of appearance (01..22);
 *  - `One Piece/Películas/` — main theme songs from the movies (singles & Film Red themes, not BGM),
 *                             numbered by year + index (`YYYY - 01 - Song - Artist - Movie`);
 *  - `One Piece/Discografía/<exact relative folder structure>` — all remaining albums, singles,
 *                             movie OSTs and character singles, keeping instrumentals and preserving
 *                             exact folder hierarchy, removing duplicates acoustically verified via
 *                             Chromaprint (picking highest quality aiming 320 kbps), and leaving
 *                             `00 - Lista completa.m3u`, `00 - Dónde está cada canción.txt`, and
 *                             original covers/scans in every folder.
 */
object OnePiece {
    data class Result(
        val items: List<Builder.Item>,
        val sidecars: Map<String, String>,
        val sidecarFiles: Map<String, String>,
        val skipped: List<Pair<Track, String>>,
        val keyOf: Map<String, String>,
    )

    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")
    private val SCAN_DIR = Regex("(?i)^(scans?|booklet|im[aá]genes|covers?|artwork)$")
    private val NON_MAIN_VER = Regex("(?iu)\\b(karaoke|inst(?:rumental|urumentl)?|less\\s*vocal|off\\s*vocal|back\\s*track|remix(?:ed)?|short\\s*edit|tv\\s*(?:ver|size)|anime\\s*edit|part\\s*version|tacky|tackey|tsubasa|first\\s*take|child\\s*ver|piano\\s*ver|acoustic|classic|edm|eurobeat|rock\\s*ver|cover)\\b|カラオケ|インスト")
    private val BEST_DIR = Regex("(?i)\\b(best|anniversary\\s+best|super\\s+best|memorial\\s+best|song\\s+collection|shudaikashuu|cover\\s+songs|arrange\\s+collection)\\b")
    private val SPANISH = Regex("(?iu)español|espanol|castellano|latino|spanish|fandub|\\(es\\)|[-_\\s]sp(\\.\\w{3,4})?$")

    /** Finds `<source>/One Piece OST` if present in any configured source. */
    fun sourceRoot(sources: List<Source>): File? = sources.firstNotNullOfOrNull { s ->
        File(s.path).listFiles()?.firstOrNull { it.isDirectory && Words.plain(it.name) == "onepieceost" }
    }

    private fun clean(s: String) = Builder.clean(s)
    private fun normTitle(s: String): String {
        val noParens = Words.nfc(s).replace(Regex("[\\[(（【].*?[\\])）】]"), " ")
        return Words.plain(noParens).ifEmpty { Words.plain(s) }
    }
    private fun wordTokens(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(Words.nfc(s).lowercase()).map { Words.plain(it.value) }.filter { it.length >= 3 }.toSet()

    private fun relUnder(root: File, path: String): String? {
        val rn = Words.nfc(root.path).trimEnd('/') + "/"
        val pn = Words.nfc(path)
        return if (pn.startsWith(rn)) pn.removePrefix(rn) else null
    }

    private fun bestCopy(list: List<Track>, opRootNfc: String): Track = list.maxWith(
        compareBy<Track>(
            { Builder.rank(it) },
            { it.bitrate },
            { if (Words.nfc(it.path).startsWith(opRootNfc)) 1 else 0 },
            { it.tags.size + if (it.hasCover) 2 else 0 },
        )
    )

    private fun reasonOf(t: Track): String = when {
        t.lossless -> if (Builder.Formats.native) "sin pérdida → AAC 320" else "sin pérdida → MP3 320"
        t.codec == "aac" -> if (t.bitrate >= 290) "AAC ${t.bitrate} kbps" else "AAC ${t.bitrate} kbps (a la to-do, poca prioridad)"
        t.codec == "opus" -> "Opus → AAC 320 (a la to-do: buscar mejor copia)"
        Builder.rank(t) == 3 -> "MP3 320"
        else -> "MP3 ${t.kbps} kbps (mientras tanto; a la to-do)"
    }

    private fun titleOf(t: Track): String {
        val tag = Words.fix(t.title).takeUnless { Words.noTitle(it) }
        if (tag != null) return Words.dropNumber(tag).trim().ifBlank { tag.trim() }
        val fn = Words.fromName(Words.fix(File(t.path).name))
        return fn.parts.firstOrNull()?.let { Words.dropNumber(it) }?.ifBlank { File(t.path).nameWithoutExtension } ?: File(t.path).nameWithoutExtension
    }

    private fun artistOf(t: Track): String {
        val raw = Words.fix(t.artist).takeUnless { Words.noArtist(it) }
            ?: Words.fix(t.albumArtist).takeUnless { Words.noArtist(it) }
        return raw?.trim().orEmpty()
    }

    private fun trackNoOf(t: Track): Int? = t.trackNo ?: Words.fromName(Words.fix(File(t.path).name)).no
    private fun discNoOf(t: Track): Int? = t.discNo ?: Words.fromName(Words.fix(File(t.path).name)).disc

    private fun isPriorityPool(rel: String?): Boolean {
        if (rel == null) return true // external One Piece files: always check acoustically against OP OST
        return rel.startsWith("Openings/") || rel.startsWith("Endings/") ||
            rel.startsWith("Movies/") && (rel.contains("Theme Single") || rel.contains("Uta no Uta") || rel.contains("Film Z")) ||
            BEST_DIR.containsMatchIn(rel)
    }

    /** Clusters tracks into acoustically verified identical recordings (Chromaprint >= 0.85). */
    private fun clusterAcoustically(tracks: List<Track>, opRoot: File): List<List<Track>> {
        val n = tracks.size
        if (n == 0) return emptyList()
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (c != r) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }

        val rels = Array(n) { relUnder(opRoot, tracks[it].path) }
        val titles = Array(n) { titleOf(tracks[it]) }
        val normTitles = Array(n) { normTitle(titles[it]) }
        val tokens = Array(n) { wordTokens(titles[it]) }
        val artists = Array(n) { Words.plain(artistOf(tracks[it])) }
        val nonMain = BooleanArray(n) { NON_MAIN_VER.containsMatchIn(titles[it] + " " + File(tracks[it].path).nameWithoutExtension) }
        val priority = BooleanArray(n) { isPriorityPool(rels[it]) }
        val durs = DoubleArray(n) { tracks[it].seconds ?: -1.0 }

        // Sort indices by duration so candidate matching is fast O(N * small window).
        val order = (0 until n).sortedBy { durs[it] }
        val pairs = ArrayList<Pair<Int, Int>>()
        for (ii in order.indices) {
            val i = order[ii]
            val di = durs[i]
            if (di <= 5.0) continue
            for (jj in ii + 1 until order.size) {
                val j = order[jj]
                val dj = durs[j]
                val diff = dj - di
                if (diff > 10.0) break
                // Never merge two tracks from the exact same folder unless they have identical normalized title.
                val sameDir = File(tracks[i].path).parent == File(tracks[j].path).parent
                val titleOverlap = (normTitles[i].length >= 3 && (normTitles[i] == normTitles[j] || (tokens[i] intersect tokens[j]).isNotEmpty()))
                val crossLangCandidate = diff <= 2.5 && (priority[i] || priority[j]) && nonMain[i] == nonMain[j]
                if (!titleOverlap && !crossLangCandidate) continue
                if (sameDir && normTitles[i] != normTitles[j]) continue
                if (diff > 4.0 && !titleOverlap) continue
                pairs += i to j
            }
        }

        // Verify candidate pairs acoustically with Chromaprint (strict >= 0.85).
        for ((i, j) in pairs) {
            if (find(i) == find(j)) continue
            val sim = runCatching { Fingerprint.similarity(tracks[i].path, tracks[j].path) }.getOrNull()
            if (sim != null) {
                if (sim >= Fingerprint.STRICT) union(i, j)
            } else {
                // Fail-closed if fpcalc could not read one file: only merge on exact title + artist + <= 1s + same version flag.
                if (normTitles[i].length >= 4 && normTitles[i] == normTitles[j] &&
                    artists[i].isNotEmpty() && artists[i] == artists[j] &&
                    kotlin.math.abs(durs[i] - durs[j]) <= 1.0 && nonMain[i] == nonMain[j]) {
                    union(i, j)
                }
            }
        }
        Fingerprint.save()

        return (0 until n).groupBy { find(it) }.values.map { idxs -> idxs.map { tracks[it] } }
    }

    /** Picks the main vocal theme track inside a Single folder like `[2007] One Piece OP08 Single - Crazy Rainbow`. */
    private fun pickThemeTrack(folderTracks: List<Track>, folderName: String, overrideTitle: String? = null): Track? {
        val vocal = folderTracks.filter { !NON_MAIN_VER.containsMatchIn(titleOf(it) + " " + File(it.path).nameWithoutExtension) }
        val pool = vocal.ifEmpty { return null }
        val want = overrideTitle ?: folderName.substringAfter("Single -", "").trim()
        if (want.isNotBlank()) {
            val wPlain = Words.plain(want)
            pool.firstOrNull { Words.plain(titleOf(it)) == wPlain || normTitle(titleOf(it)) == wPlain }?.let { return it }
            pool.firstOrNull { Words.plain(titleOf(it)).contains(wPlain) || wPlain.contains(normTitle(titleOf(it))) && normTitle(titleOf(it)).length >= 4 }?.let { return it }
        }
        return pool.minByOrNull { trackNoOf(it) ?: 99 }
    }

    fun plan(opRoot: File, opTracks: List<Track>, sources: List<Source>, targetRoot: String): Result {
        val opRootNfc = Words.nfc(opRoot.path).trimEnd('/') + "/"
        val skipped = ArrayList<Pair<Track, String>>()
        val valid = opTracks.filter { t ->
            when {
                Builder.Formats.extOf(t) == null -> { skipped += t to "formato ${t.codec ?: "?"} (no se copia)"; false }
                File(t.path).extension.lowercase() in setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "flv") && (t.seconds ?: 0.0) > 15 * 60 -> {
                    skipped += t to "vídeo de concierto o programa (no se copia)"; false
                }
                else -> true
            }
        }

        val clusters = clusterAcoustically(valid, opRoot)
        val clusterOf = HashMap<String, Int>()
        clusters.forEachIndexed { idx, list -> list.forEach { clusterOf[Words.nfc(it.path)] = idx } }

        // Group tracks inside One Piece OST by their exact relative directory.
        val ostTracksByRelDir = LinkedHashMap<String, MutableList<Track>>()
        for (t in valid) {
            val rel = relUnder(opRoot, t.path) ?: continue
            val relDir = rel.substringBeforeLast('/', "")
            ostTracksByRelDir.getOrPut(relDir) { ArrayList() }.add(t)
        }

        // Resolve Movie names from sibling Movie OST folders: `[2003] Movie 4 - The Dead End Adventure` -> movie 4 = "The Dead End Adventure"
        val movieNameByNum = HashMap<Int, String>()
        val movieYearByNum = HashMap<Int, String>()
        ostTracksByRelDir.keys.filter { it.startsWith("Movies/") }.forEach { rd ->
            val top = rd.removePrefix("Movies/").substringBefore('/')
            Regex("^\\[(\\d{4})]\\s*Movie\\s+(\\d+)\\s*[-–]\\s*(.+)$", RegexOption.IGNORE_CASE).find(top)?.let { m ->
                val y = m.groupValues[1]; val num = m.groupValues[2].toInt(); val name = m.groupValues[3].trim()
                if (!name.startsWith("Theme Single", ignoreCase = true)) {
                    movieNameByNum[num] = name
                    movieYearByNum[num] = y
                }
            }
        }

        // 1. Identify Openings (01..28), Endings (01..22), and Movie main themes (Películas).
        data class Slot(val folder: String, val no: Int?, val year: String?, val title: String, val artist: String, val album: String, val movieName: String? = null)
        val assigned = HashMap<Int, Slot>() // clusterIdx -> assigned primary slot

        // 1A. Openings from `Openings/` single folders.
        val opDirs = ostTracksByRelDir.keys.filter { it.startsWith("Openings/") }.sorted()
        for (rd in opDirs) {
            val folderName = rd.substringAfterLast('/')
            val list = ostTracksByRelDir[rd].orEmpty()
            val year = Regex("^\\[(\\d{4})]").find(folderName)?.groupValues?.get(1)
            // Special double-OP single: `[2009] One Piece OP10-OP11 Single - Share The World - We Are!`
            val dbl = Regex("OP(\\d+)\\s*[-–]\\s*OP(\\d+)", RegexOption.IGNORE_CASE).find(folderName)
            if (dbl != null) {
                val n1 = dbl.groupValues[1].toInt() // OP10 = We Are!
                val n2 = dbl.groupValues[2].toInt() // OP11 = Share The World
                pickThemeTrack(list, folderName, "We Are!")?.let { t ->
                    val cid = clusterOf[Words.nfc(t.path)]!!
                    assigned.putIfAbsent(cid, Slot("One Piece/Openings", n1, year, titleOf(t), artistOf(t), "One Piece Openings"))
                }
                pickThemeTrack(list, folderName, "Share The World")?.let { t ->
                    val cid = clusterOf[Words.nfc(t.path)]!!
                    assigned.putIfAbsent(cid, Slot("One Piece/Openings", n2, year, titleOf(t), artistOf(t), "One Piece Openings"))
                }
                continue
            }
            val opNum = Regex("OP(\\d+)", RegexOption.IGNORE_CASE).find(folderName)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            // OP14 single is titled "Naked" but the actual One Piece OP14 is "Fight Together".
            val override = if (opNum == 14) "Fight Together" else null
            val chosen = pickThemeTrack(list, folderName, override) ?: continue
            val cid = clusterOf[Words.nfc(chosen.path)]!!
            assigned.putIfAbsent(cid, Slot("One Piece/Openings", opNum, year ?: chosen.year, titleOf(chosen), artistOf(chosen), "One Piece Openings"))
        }
        // Fill OP07 (We Are! ~7 Straw Hats~) and OP20 (Hope) from BEST albums if not in Openings/ singles.
        val usedOpNums = assigned.values.filter { it.folder == "One Piece/Openings" }.mapNotNull { it.no }.toSet()
        if (7 !in usedOpNums) {
            valid.firstOrNull { !NON_MAIN_VER.containsMatchIn(titleOf(it)) && Regex("(?i)we are.*(shichinin|7 straw)", RegexOption.IGNORE_CASE).containsMatchIn(titleOf(it) + " " + File(it.path).name) }?.let { t ->
                val cid = clusterOf[Words.nfc(t.path)]!!
                assigned.putIfAbsent(cid, Slot("One Piece/Openings", 7, "2006", titleOf(t), artistOf(t).ifBlank { "7 Straw Hats" }, "One Piece Openings"))
            }
        }
        if (20 !in usedOpNums) {
            valid.firstOrNull { !NON_MAIN_VER.containsMatchIn(titleOf(it)) && Words.plain(titleOf(it)) == "hope" && Words.plain(artistOf(it)).contains("amuro") }?.let { t ->
                val cid = clusterOf[Words.nfc(t.path)]!!
                assigned.putIfAbsent(cid, Slot("One Piece/Openings", 20, "2017", titleOf(t), artistOf(t), "One Piece Openings"))
            }
        }

        // 1B. Endings from `Endings/` single folders.
        val edDirs = ostTracksByRelDir.keys.filter { it.startsWith("Endings/") }.sorted()
        for (rd in edDirs) {
            val folderName = rd.substringAfterLast('/')
            val list = ostTracksByRelDir[rd].orEmpty()
            val year = Regex("^\\[(\\d{4})]").find(folderName)?.groupValues?.get(1)
            val edNum = Regex("ED(\\d+)", RegexOption.IGNORE_CASE).find(folderName)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val chosen = pickThemeTrack(list, folderName) ?: continue
            val cid = clusterOf[Words.nfc(chosen.path)]!!
            assigned.putIfAbsent(cid, Slot("One Piece/Endings", edNum, year ?: chosen.year, titleOf(chosen), artistOf(chosen), "One Piece Endings"))
        }
        // Fill ED03 (Watashi ga Iru yo) from BEST albums if not in Endings/ singles.
        val usedEdNums = assigned.values.filter { it.folder == "One Piece/Endings" }.mapNotNull { it.no }.toSet()
        if (3 !in usedEdNums) {
            valid.firstOrNull { !NON_MAIN_VER.containsMatchIn(titleOf(it) + " " + it.path) && Words.plain(titleOf(it)) == "watashigairuyo" }?.let { t ->
                val cid = clusterOf[Words.nfc(t.path)]!!
                assigned.putIfAbsent(cid, Slot("One Piece/Endings", 3, "2001", titleOf(t), artistOf(t).ifBlank { "Tomato Cube" }, "One Piece Endings"))
            }
        }

        // 1C. Películas (main theme songs of movies — singles & Film Red / Film Z vocal themes, NOT OST BGM).
        val moviePicks = ArrayList<Triple<Int, Track, Triple<String, String, String>>>() // (cid, track, (year, movieName, title))
        val movieDirs = ostTracksByRelDir.keys.filter { it.startsWith("Movies/") }.sorted()
        for (rd in movieDirs) {
            val folderName = rd.removePrefix("Movies/").substringBefore('/')
            val list = ostTracksByRelDir[rd].orEmpty()
            val year = Regex("^\\[(\\d{4})]").find(folderName)?.groupValues?.get(1) ?: "2000"
            when {
                folderName.contains("Theme Single", ignoreCase = true) -> {
                    val mNum = Regex("Movie\\s+(\\d+)", RegexOption.IGNORE_CASE).find(folderName)?.groupValues?.get(1)?.toIntOrNull()
                    val mName = mNum?.let { movieNameByNum[it] } ?: ("Movie " + (mNum ?: ""))
                    // Movie 14 Theme Single is "Summer Trap!!" where track 03 "Gong" is the actual Stampede theme.
                    val override = if (mNum == 14) "Gong" else null
                    val chosen = pickThemeTrack(list, folderName, override) ?: continue
                    val cid = clusterOf[Words.nfc(chosen.path)]!!
                    if (cid !in assigned) moviePicks += Triple(cid, chosen, Triple(year, mName, titleOf(chosen)))
                }
                folderName.contains("Film Z", ignoreCase = true) -> {
                    list.filter { Words.plain(titleOf(it)) in setOf("howyouremindme", "badreputation") }
                        .sortedBy { trackNoOf(it) ?: 99 }.forEach { t ->
                            val cid = clusterOf[Words.nfc(t.path)]!!
                            if (cid !in assigned) moviePicks += Triple(cid, t, Triple(year, "Film Z", titleOf(t)))
                        }
                }
                folderName.contains("Uta no Uta", ignoreCase = true) -> {
                    // Tracks 01..07 of Uta no Uta ONE PIECE FILM RED are the 7 main vocal theme songs of Film Red.
                    list.filter { (trackNoOf(it) ?: 99) in 1..7 && !NON_MAIN_VER.containsMatchIn(titleOf(it)) }
                        .sortedBy { trackNoOf(it) ?: 99 }.forEach { t ->
                            val cid = clusterOf[Words.nfc(t.path)]!!
                            if (cid !in assigned) moviePicks += Triple(cid, t, Triple(year, "One Piece Film Red", titleOf(t)))
                        }
                }
            }
        }
        val yearSeq = HashMap<String, Int>()
        moviePicks.distinctBy { it.first }.sortedWith(compareBy({ it.third.first }, { trackNoOf(it.second) ?: 99 })).forEach { (cid, t, meta) ->
            val (year, mName, title) = meta
            val seq = yearSeq.merge(year, 1, Int::plus)!!
            assigned.putIfAbsent(cid, Slot("One Piece/Películas", seq, year, title, artistOf(t), "One Piece Películas", mName))
        }

        // 2. Build final Builder.Item for every verified cluster (1 physical file per unique recording!).
        val sourceDefault = sources.firstOrNull { Words.nfc(opRoot.path).startsWith(Words.nfc(it.path)) } ?: sources.first()
        fun sourceOf(t: Track) = sources.firstOrNull { Words.nfc(t.path).startsWith(Words.nfc(it.path).trimEnd('/') + "/") } ?: sourceDefault

        val items = ArrayList<Builder.Item>()
        val clusterTarget = HashMap<Int, String>() // clusterIdx -> final absolute target path
        val keyOf = HashMap<String, String>()
        val usedPaths = HashMap<String, Int>()

        fun uniquePath(path: String): String {
            val n = usedPaths.merge(Words.nfc(path).lowercase(), 1, Int::plus)!!
            if (n == 1) return Words.nfc(path)
            val ext = path.substringAfterLast('.')
            return Words.nfc(path.removeSuffix(".$ext") + " ($n).$ext")
        }

        // Helper: rank a relative directory inside One Piece OST so original singles/OSTs win over BEST compilations.
        fun dirPriority(relDir: String): Int = when {
            relDir.startsWith("Openings/") || relDir.startsWith("Endings/") -> 0
            relDir.startsWith("Movies/") -> 1
            relDir.startsWith("Character Singles/") -> 2
            !BEST_DIR.containsMatchIn(relDir) -> 3
            else -> 4
        }

        for (cid in clusters.indices) {
            val cl = clusters[cid]
            val best = bestCopy(cl, opRootNfc)
            val ext = Builder.Formats.extOf(best) ?: continue
            val k = "onepiece|$cid"
            cl.forEach { keyOf[it.path] = k }
            val slot = assigned[cid]
            val targetPath: String
            val itemTitle: String
            val itemArtist: String
            val itemAlbum: String
            val itemYear: String?
            val itemNo: Int?
            val itemDisc: Int?
            val coverDir: String?

            if (slot != null) {
                itemTitle = slot.title
                itemArtist = slot.artist.ifBlank { artistOf(best) }
                itemAlbum = slot.album
                itemYear = slot.year ?: best.year
                itemNo = slot.no
                itemDisc = 1
                coverDir = File(best.path).parent
                val fname = if (slot.folder == "One Piece/Películas") {
                    // Exact user format: "YYYY - 01 - Name of the song - Artist - Movie name"
                    val num = (slot.no ?: 1).toString().padStart(2, '0')
                    listOfNotNull(itemYear, num, itemTitle, itemArtist.ifBlank { null }, slot.movieName?.ifBlank { null }).joinToString(" - ") { clean(it) }
                } else {
                    val num = slot.no?.toString()?.padStart(2, '0')
                    listOfNotNull(num, itemTitle, itemArtist.ifBlank { null }, itemYear).joinToString(" - ") { clean(it) }
                }
                targetPath = uniquePath("$targetRoot/${slot.folder}/$fname.$ext")
            } else {
                // Place inside One Piece/Discografía/<canonical relative folder> (or Canciones en español / Otras if external-only).
                val inOst = cl.mapNotNull { t -> relUnder(opRoot, t.path)?.let { t to it.substringBeforeLast('/', "") } }
                if (inOst.isNotEmpty()) {
                    val (canonTrack, canonRelDir) = inOst.minWith(compareBy({ dirPriority(it.second) }, { it.second }))
                    itemTitle = titleOf(canonTrack)
                    itemArtist = artistOf(canonTrack).ifBlank { artistOf(best) }
                    val albumFolderName = canonRelDir.split('/').firstOrNull { it.startsWith("[") } ?: canonRelDir.substringAfterLast('/')
                    val (_, parsedAlbum) = Words.albumFromFolder(albumFolderName)
                    itemAlbum = Words.fix(canonTrack.album).takeUnless { Words.noAlbum(it) } ?: parsedAlbum.ifBlank { albumFolderName }
                    itemYear = Regex("^\\[(\\d{4})]").find(albumFolderName)?.groupValues?.get(1) ?: canonTrack.year ?: best.year
                    itemNo = trackNoOf(canonTrack)
                    itemDisc = discNoOf(canonTrack)
                    coverDir = File(canonTrack.path).parent
                    val num = itemNo?.toString()?.padStart(2, '0')
                    val fname = listOfNotNull(num, itemTitle, itemArtist.ifBlank { null }).joinToString(" - ") { clean(it) }
                    val cleanRelDir = canonRelDir.split('/').filter { it.isNotEmpty() }.joinToString("/") { clean(it) }
                    targetPath = uniquePath("$targetRoot/One Piece/Discografía/$cleanRelDir/$fname.$ext")
                } else {
                    // External One Piece track not present in One Piece OST (e.g. Spanish dubs or extra songs).
                    itemTitle = titleOf(best)
                    itemArtist = artistOf(best)
                    itemAlbum = Words.fix(best.album).takeUnless { Words.noAlbum(it) }.orEmpty()
                    itemYear = best.year
                    itemNo = null
                    itemDisc = null
                    coverDir = null
                    val sub = if (SPANISH.containsMatchIn(best.path + " " + itemTitle)) "Canciones en español" else "Otras"
                    val fname = listOfNotNull(itemTitle, itemArtist.ifBlank { null }, itemYear).joinToString(" - ") { clean(it) }
                    targetPath = uniquePath("$targetRoot/One Piece/$sub/$fname.$ext")
                }
            }
            clusterTarget[cid] = targetPath

            val releases = cl.mapNotNull { t ->
                val rel = relUnder(opRoot, t.path)
                val albName = Words.fix(t.album).takeUnless { Words.noAlbum(it) }
                    ?: rel?.substringBeforeLast('/', "")?.substringAfterLast('/')?.let { Words.albumFromFolder(it).second }
                    ?: return@mapNotNull null
                Builder.Release(clean(albName), artistOf(t).ifBlank { "One Piece" }, t.year, trackNoOf(t), discNoOf(t), File(t.path).parent)
            }.distinctBy { Builder.albumKey(it.album) }

            items += Builder.Item(
                source = sourceOf(best),
                track = best,
                target = targetPath,
                reason = reasonOf(best),
                title = itemTitle,
                artist = itemArtist,
                album = itemAlbum,
                year = itemYear,
                trackNo = itemNo,
                disc = itemDisc,
                albumArtist = "One Piece",
                grouping = "One Piece",
                cover = coverDir,
                key = k,
                copies = cl.map { it.path },
                releases = releases,
            )
        }

        // 3. Build .m3u + .txt sidecars AND copy covers/scans for EVERY folder in One Piece/Discografía/.
        val sidecars = LinkedHashMap<String, String>()
        val sidecarFiles = LinkedHashMap<String, String>()

        for ((relDir, list) in ostTracksByRelDir) {
            val cleanRelDir = relDir.split('/').filter { it.isNotEmpty() }.joinToString("/") { clean(it) }
            val home = Words.nfc("$targetRoot/One Piece/Discografía/$cleanRelDir")
            val sorted = list.sortedWith(compareBy({ discNoOf(it) ?: 1 }, { trackNoOf(it) ?: 999 }, { File(it.path).name.lowercase() }))
            val resolved = sorted.mapNotNull { t ->
                val cid = clusterOf[Words.nfc(t.path)] ?: return@mapNotNull null
                val tgt = clusterTarget[cid] ?: return@mapNotNull null
                Triple(t, cid, tgt)
            }
            val elsewhere = resolved.count { (_, _, tgt) -> Words.nfc(tgt).substringBeforeLast('/') != home }
            if (elsewhere > 0 && resolved.isNotEmpty()) {
                val dirPath = java.nio.file.Paths.get(home)
                val folderTitle = relDir.substringAfterLast('/')
                val m3u = buildString {
                    append("#EXTM3U\n#PLAYLIST:").append(Words.nfc(folderTitle)).append("\n")
                    for ((t, _, tgt) in resolved) {
                        val art = artistOf(t).ifBlank { "One Piece" }
                        append("#EXTINF:").append((t.seconds ?: 0.0).toInt()).append(',').append(Words.nfc(art)).append(" - ").append(Words.nfc(titleOf(t))).append('\n')
                        append(Words.nfc(dirPath.relativize(java.nio.file.Paths.get(Words.nfc(tgt))).toString())).append('\n')
                    }
                }
                val txt = buildString {
                    append(Words.nfc(folderTitle)).append("\n\n")
                    append("Para ahorrar espacio sin duplicar canciones, las pistas repetidas o movidas a Openings / Endings / Películas se guardan una sola vez en su ubicación principal:\n\n")
                    for ((t, _, tgt) in resolved) {
                        val no = trackNoOf(t)?.toString()?.padStart(2, '0')
                        val where = Words.nfc(tgt).substringBeforeLast('/')
                        append(if (no != null) "$no. " else "· ").append(Words.nfc(titleOf(t)))
                        append(if (where == home) "  (aquí)" else "  → ${where.removePrefix(Words.nfc(targetRoot)).trimStart('/')}").append('\n')
                    }
                }
                sidecars[Words.nfc("$home/${Builder.SIDECAR_M3U}")] = m3u
                sidecars[Words.nfc("$home/${Builder.SIDECAR_TXT}")] = txt
            }
        }

        // Copy covers and Scans/ subfolders from One Piece OST into One Piece/Discografía/ (read-only source!).
        opRoot.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in IMG_EXT && !it.name.startsWith(".") }.forEach { img ->
            val rel = relUnder(opRoot, img.path) ?: return@forEach
            val parts = rel.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return@forEach
            val cleanRel = parts.dropLast(1).joinToString("/") { clean(it) } + "/" + clean(parts.last())
            sidecarFiles[Words.nfc("$targetRoot/One Piece/Discografía/$cleanRel")] = img.path
        }

        return Result(items, sidecars, sidecarFiles, skipped, keyOf)
    }
}
