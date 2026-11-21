package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Paths as written by a person ("Música") against paths as stored by the disk
 * ("Música" on pCloud Drive): each part found by its letters, whatever the accents' form.
 */
object Paths {
    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    fun resolve(path: String): String {
        val expanded = if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path
        if (File(expanded).exists()) return expanded.trimEnd('/')
        val parts = expanded.split('/').filter { it.isNotEmpty() }
        var at = File("/")
        for (p in parts) {
            val direct = File(at, p)
            at = if (direct.exists()) direct else at.listFiles()?.firstOrNull { nfc(it.name) == nfc(p) } ?: return expanded.trimEnd('/')
        }
        return at.path
    }
}

/**
 * Where a source's files go in the clean library, and which of them: a source is read
 * only, never changed. `mode = all`: every song, filed by its own tags. `mode = rules`:
 * only the album folders matching [include] (minus [exclude]), under [target]/<group>; in
 * albums matching [vocalOnly] (soundtracks, drama CDs) only the sung tracks — a track
 * credited to someone other than [mainArtist].
 */
data class Source(
    val name: String,
    val path: String,
    val mode: String = "all",
    val target: String? = null,
    val mainArtist: String? = null,
    val include: List<Pair<Regex, String>> = emptyList(),
    val exclude: List<Regex> = emptyList(),
    val vocalOnly: Regex? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("path", path).put("mode", mode).apply {
        target?.let { put("target", it) }
        mainArtist?.let { put("mainArtist", it) }
        if (include.isNotEmpty()) put("include", JSONArray(include.map { (r, g) -> JSONObject().put("folder", r.pattern).put("group", g) }))
        if (exclude.isNotEmpty()) put("exclude", JSONArray(exclude.map { it.pattern }))
        vocalOnly?.let { put("vocalOnly", it.pattern) }
    }

    companion object {
        fun from(o: JSONObject) = Source(
            name = o.getString("name"),
            path = o.getString("path"),
            mode = o.optString("mode", "all"),
            target = o.optString("target").ifBlank { null },
            mainArtist = o.optString("mainArtist").ifBlank { null },
            include = o.optJSONArray("include")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { r -> Regex(r.getString("folder"), RegexOption.IGNORE_CASE) to r.optString("group") } }
            }.orEmpty(),
            exclude = o.optJSONArray("exclude")?.let { a -> (0 until a.length()).map { Regex(a.getString(it), RegexOption.IGNORE_CASE) } }.orEmpty(),
            vocalOnly = o.optString("vocalOnly").ifBlank { null }?.let { Regex(it, RegexOption.IGNORE_CASE) },
        )

        /** The sources agreed on 21 Nov 2026 (editable in sources.json). */
        fun defaults(): List<Source> {
            val home = System.getProperty("user.home")
            return listOf(
                Source("Escritorio · Música", "$home/Desktop/Música"),
                Source("Música(1)", "$home/Music/Música(1)"),
                Source("Discografías", "$home/Downloads/Discografías"),
                Source(
                    name = "Yuki Kajiura (carpeta compartida)",
                    path = "$home/pCloud Drive/OnlineOnly/Yuki Kajiura",
                    mode = "rules",
                    target = "Yuki Kajiura",
                    mainArtist = "Yuki Kajiura",
                    include = listOf(
                        // Her groups and singers, each in its own folder.
                        Regex("^(FictionJunction(?: YUUKA| ASUKA)?|Kalafina|See-Saw|Saeko Chiba)/", RegexOption.IGNORE_CASE) to "$1",
                        // FICTION, The Works, every LIVE and concert, and every single ("Title [Singer]").
                        Regex("^\\d{4}/(FICTION|The Works)", RegexOption.IGNORE_CASE) to "",
                        Regex("^\\d{4}/.*(LIVE|Concert)", RegexOption.IGNORE_CASE) to "Directos",
                        Regex("^\\d{4}/.*\\[[^\\]]+\\]\\s*/?$", RegexOption.IGNORE_CASE) to "Singles",
                        // Soundtracks: only their sung tracks (see vocalOnly).
                        Regex("^\\d{4}/.*(Soundtrack|O\\.?S\\.?T|SONG TRACK|Image Album|Sound Art|Drama|SUIT CD|Best Collection|Vocal)", RegexOption.IGNORE_CASE) to "Bandas sonoras",
                    ),
                    exclude = listOf(Regex("^0000/(?!.*\\[)", RegexOption.IGNORE_CASE), Regex("Instrumental|Karaoke|Edits|Mixes|DEMO", RegexOption.IGNORE_CASE)),
                    vocalOnly = Regex("Soundtrack|O\\.?S\\.?T|SONG TRACK|Image|Sound Art|Drama|SUIT CD|BGM", RegexOption.IGNORE_CASE),
                ),
            )
        }
    }
}

object Sources {
    private val file get() = File(Store.dir, "sources-v2.json")

    fun load(): List<Source> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { Source.from(a.getJSONObject(it)) }
    }.getOrElse { Source.defaults().also(::save) }.map { it.copy(path = Paths.resolve(it.path)) }

    fun save(list: List<Source>) = file.writeText(JSONArray(list.map { it.toJson() }).toString(2))
}

/**
 * The clean library, built from the sources. Each recording (same title, first artist and
 * length) is copied once, from its best copy: lossless made into MP3 320, else a real MP3
 * 320, else the best MP3 meanwhile (to the to-do); on a tie, the copy with more tags and a
 * cover. AAC, m4a, opus, webm and wma are never copied. Nothing in a source is changed.
 *
 * Where a song lives: a source with rules (Yuki Kajiura) files it by them; a song of a
 * collection (an anime, a game, a series — a folder of many artists whose songs name it)
 * goes to that collection, in the album of it that gathers the most of its songs; any other
 * song to its artist's folder, or to "Otros artistas" when the artist has fewer than 5.
 */
object Builder {
    data class Item(
        val source: Source,
        val track: Track,
        val target: String,
        val reason: String,
        val album: String,
        val year: String?,
        val trackNo: Int?,
        val albumArtist: String,
        val grouping: String?,
        val cover: String?,
        val key: String,
    )
    data class Plan(val copy: List<Item>, val skipped: List<Pair<Track, String>>, val alternatives: Map<String, List<String>>)

    private data class Cand(val source: Source, val track: Track, val home: String, val homeKind: Int, val album: String, val year: String?, val albumDir: String)

    private fun norm(s: String) = Audit.norm(s)
    fun key(t: Track) = norm(t.title) + "|" + norm(t.artist.split(',', '&', '、', ';', '/').first().replace(Regex("(?i)\\s(feat|ft)\\.?.*$"), "")) + "|" + ((t.seconds ?: 0.0) / 3).toInt()

    fun rank(t: Track): Int = when {
        t.lossless -> 4
        t.mp3_320 && t.fake320 != true -> 3
        t.codec == "mp3" -> 1 + (if (t.kbps >= 256) 1 else 0)
        else -> 0
    }
    private fun richness(t: Track) = t.tags.keys.count { it in setOf("title", "artist", "album", "date", "track", "album_artist", "genre", "composer", "lyrics", "disc") } + if (t.hasCover) 3 else 0

    private val BAD = Regex("""[/\\:*?"<>|]""")
    private fun clean(s: String) = s.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    /** "[Nemuri] Bishōjo Senshi Sailor Moon 美少女戦士セーラームーン (1992-2000) [MP3]" → its name; aliases from collections.json. */
    @Volatile private var aliases: Map<String, String> = emptyMap()

    /** `<library>/.bitchord-library/collections.json`: {"aliases": {"美少女戦士セーラームーン": "Sailor Moon"}} — one collection under several names. */
    private fun loadAliases(root: String) {
        aliases = runCatching {
            val o = JSONObject(File(root, ".bitchord-library/collections.json").readText()).getJSONObject("aliases")
            o.keys().asSequence().associate { norm(it) to o.getString(it) }
        }.getOrDefault(emptyMap())
    }
    fun collectionName(folder: String): String {
        val c = clean(folder.replace(Regex("""\[[^\]]*\]"""), " ").replace(Regex("""\((?:\d{4}(?:\s*-\s*\d{4})?|MP3|FLAC)\)""", RegexOption.IGNORE_CASE), " "))
        return aliases[norm(c)] ?: c
    }

    // ── Artists' names: one folder per person, "Romaji (日本語)" when both are known ──

    private val CJK = Regex("""[\p{IsHiragana}\p{IsKatakana}\p{IsHan}\p{IsHangul}]""")
    private fun latin(s: String) = s.any { it.isLetter() } && !CJK.containsMatchIn(s)
    private val VARIOUS = Regex("(?i)^(various( artists)?|varios( artistas)?|varias|va|v\\.a\\.|compilation|unknown( artist)?|artista desconocido)$")
    /** "Akiko Shikata" and "Shikata Akiko" are one key. */
    private fun akey(s: String) = if (latin(s)) Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.sorted().joinToString("") else norm(s)
    private fun firstArtist(s: String) = s.split(',', '&', '、', ';', '/', '×').first().replace(Regex("(?i)\\s(feat|ft)\\.?.*$"), "").trim()
    private fun folderName(s: String) = s.replace(Regex("(?i)\\s*[-–]?\\s*(discography|discografía|collection|colecci[oó]n|коллекция|complete|\\(mp3\\)|\\[mp3\\]|\\(flac\\))"), " ").replace(Regex("\\s+"), " ").trim()

    /** akey → display name, learnt from folders named in Latin whose songs are tagged in Japanese (and spelling variants). */
    private fun artistNames(sources: List<Source>, tracks: List<Track>): Map<String, String> {
        val latinVariants = HashMap<String, MutableMap<String, Int>>()
        val pairs = HashMap<String, String>() // cjk akey → latin
        fun see(n: String) { if (latin(n)) latinVariants.getOrPut(akey(n)) { HashMap() }.merge(n, 1, Int::plus) }
        tracks.forEach { t -> see(firstArtist(t.artist)); if (t.albumArtist.isNotBlank()) see(firstArtist(t.albumArtist)) }
        // "Latin (日本語)" already in tags.
        tracks.forEach { t ->
            Regex("""^(.+?)\s*[(（]([^)）]+)[)）]$""").find(firstArtist(t.artist))?.let { m ->
                val (a, b) = m.groupValues[1].trim() to m.groupValues[2].trim()
                if (latin(a) && !latin(b)) pairs[akey(b)] = a
            }
        }
        for (s in sources.filter { it.mode == "all" }) {
            tracks.filter { it.path.startsWith(s.path + "/") }
                .groupBy { it.path.removePrefix(s.path + "/").substringBefore('/') }
                .forEach { (folder, list) ->
                    val f = folderName(folder)
                    val top = list.groupingBy { firstArtist(it.albumArtist.ifBlank { it.artist }) }.eachCount().maxByOrNull { it.value } ?: return@forEach
                    if (top.value < list.size * 0.6 || VARIOUS.matches(top.key)) return@forEach
                    if (latin(f) && !latin(top.key)) pairs.putIfAbsent(akey(top.key), f)
                    see(f)
                }
        }
        val best = latinVariants.mapValues { (_, v) -> v.maxBy { it.value }.key }
        val out = HashMap<String, String>()
        best.forEach { (k, v) -> out[k] = v }
        pairs.forEach { (cjk, lat) ->
            val l = best[akey(lat)] ?: lat
            val shown = "$l (${tracks.firstNotNullOfOrNull { t -> firstArtist(t.artist).takeIf { akey(it) == cjk } } ?: cjk})"
            out[cjk] = shown
            out[akey(lat)] = shown
        }
        return out
    }

    private val GENERIC = Regex("(?i)\\b(singles?|ost|soundtracks?|collection|best|songs?|music|op|ed|openings?|endings?|cds?|disc|vol\\.?\\s*\\d+)\\b")

    /** A folder of many artists whose songs carry its name (album, title or file name). */
    private val GRAB_BAG = Regex("(?i)^(vari[oa]s|various( artists)?|va|musique|m[uú]sica.*|music|otros.*|other.*|misc.*|comercial|rock|pop|nueva.*|mix.*|random|singles?|descargad.*|lost)$")

    private fun isCollection(name: String, tracks: List<Track>): Boolean {
        if (tracks.size < 3 || GRAB_BAG.matches(name.trim())) return false
        val artists = tracks.groupingBy { norm(it.artist.split(',', '&', '、').first()) }.eachCount()
        val top = artists.values.maxOrNull() ?: 0
        if (artists.size < 3 || top >= tracks.size * 0.6) return false
        val words = norm(name.replace(GENERIC, " "))
        if (words.length < 3) return false
        val named = tracks.count { t -> listOf(t.album, t.title, File(t.path).name).any { norm(it).contains(words) } }
        return named >= tracks.size * 0.3
    }

    private val KARAOKE = Regex("(?i)karaoke|カラオケ|off[ -]?vocal|instrumental|inst\\.\\)|\\(inst\\)|backing track|オリジナル・カラオケ")

    /** "2009 - storia" / "2003/FICTION": the album folder's year and name. */
    private fun folderAlbum(rel: String): Pair<String?, String> {
        val parts = rel.split('/')
        val albumDir = parts.getOrNull(parts.size - 2) ?: ""
        val m = Regex("""^(\d{4})\s*-\s*(.+)$""").find(albumDir)
        if (m != null) return m.groupValues[1] to m.groupValues[2]
        val year = parts.firstOrNull { Regex("""^\d{4}$""").matches(it) && it != "0000" }
        return year to albumDir
    }

    /** Where each source's songs belong: rules, a collection, or the artist. */
    private fun candidates(sources: List<Source>, tracks: List<Track>, skipped: MutableList<Pair<Track, String>>): List<Cand> {
        val out = ArrayList<Cand>()
        for (s in sources) {
            val mine = tracks.filter { it.path.startsWith(s.path + "/") }
            fun rel(t: Track) = t.path.removePrefix(s.path).trimStart('/')
            // Collections by folder: a top folder, or the folders inside a container of them ("Anime/Naruto").
            val series = HashMap<String, String>()
            if (s.mode == "all") {
                val byTop = mine.groupBy { rel(it).substringBefore('/', "") }.filterKeys { it.isNotEmpty() }
                for ((top, list) in byTop) {
                    val bySub = list.filter { rel(it).count { c -> c == '/' } >= 2 }.groupBy { rel(it).split('/')[1] }
                    val subCollections = bySub.filter { (sub, l) -> isCollection(sub, l) }
                    if (subCollections.size >= 2) subCollections.keys.forEach { sub -> series["$top/$sub/"] = collectionName(sub) }
                    else if (isCollection(top, list)) series["$top/"] = collectionName(top)
                }
            }
            for (t in mine) {
                if (!t.lossless && t.codec != "mp3") { skipped += t to "formato ${t.codec ?: "?"} (no se copia)"; continue }
                if (KARAOKE.containsMatchIn(t.title + " " + File(t.path).nameWithoutExtension)) { skipped += t to "karaoke / sin voz"; continue }
                val r = rel(t)
                val (fYear, fAlbum) = folderAlbum(r)
                val album = t.album.ifBlank { fAlbum }
                val year = t.year ?: fYear
                val dir = File(t.path).parent
                if (s.mode == "rules") {
                    if (s.exclude.any { it.containsMatchIn(r) }) { skipped += t to "excluida"; continue }
                    val albumRel = r.substringBeforeLast('/')
                    val rule = s.include.firstOrNull { (re, _) -> re.containsMatchIn("$albumRel/") }
                    if (rule == null) { skipped += t to "fuera de las reglas"; continue }
                    if (s.vocalOnly?.containsMatchIn(albumRel) == true || rule.second == "Bandas sonoras") {
                        val a = norm(t.artist)
                        val main = s.mainArtist?.let(::norm)
                        val mainRev = s.mainArtist?.split(' ')?.reversed()?.joinToString(" ")?.let(::norm)
                        if (a.isEmpty() || a == main || a == mainRev) { skipped += t to "instrumental de banda sonora"; continue }
                    }
                    val group = rule.first.find("$albumRel/")?.let { m -> rule.second.replace(Regex("""\$(\d)""")) { g -> m.groupValues.getOrNull(g.groupValues[1].toInt()).orEmpty() } } ?: rule.second
                    val home = listOfNotNull(s.target, group.ifBlank { null }).joinToString("/")
                    out += Cand(s, t, home, 3, album, year, dir)
                    continue
                }
                val col = series.entries.firstOrNull { r.startsWith(it.key) }?.value
                if (col != null) out += Cand(s, t, col, 2, album, year, dir)
                else out += Cand(s, t, "", 1, album, year, dir)
            }
        }
        return out
    }

    /** Accents and case aside: "Pokémon" = "Pokemon". */
    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").let(::norm)

    /** A tag that names no artist: a category, a grab-bag, a file name. */
    private val NOT_ARTIST = Regex("(?i)(^(anime|videojuegos?|games?|ost|soundtracks?|unknown.*|desconocid.*)$)|\\.(mp3|m4a|flac|wav)\\b")
    private fun notArtist(s: String) = s.isBlank() || NOT_ARTIST.containsMatchIn(s.trim()) || GRAB_BAG.matches(s.trim()) || s.trim().lowercase().startsWith("música ")

    fun plan(sources: List<Source>, tracks: List<Track>, targetRoot: String): Plan {
        loadAliases(targetRoot)
        val skipped = ArrayList<Pair<Track, String>>()
        val cands = candidates(sources, tracks, skipped)
        val have = tracks.filter { it.path.startsWith("$targetRoot/") }.associateBy { key(it) }
        val byKey = cands.groupBy { key(it.track) }
        val names = artistNames(sources, tracks)
        // collections.json's aliases name artists and collections alike ("美少女戦士セーラームーン" → "Sailor Moon").
        fun shown(n: String) = aliases[norm(n)] ?: names[akey(n)] ?: n
        fun ownerOf(t: Track): String {
            val aa = firstArtist(t.albumArtist)
            if (aa.isNotBlank() && !VARIOUS.matches(aa) && !notArtist(aa)) return aa
            val a = firstArtist(t.artist)
            return if (notArtist(a)) "" else a
        }
        // A source with rules takes in its people's songs from every other source too
        // (Kalafina, See-Saw… under Yuki Kajiura): their folders, by name.
        val absorbed = HashMap<String, String>()
        cands.filter { it.homeKind == 3 }.forEach { c ->
            val parts = c.home.split('/')
            absorbed.putIfAbsent(akey(shown(parts.last())), c.home)
            c.source.mainArtist?.let { absorbed.putIfAbsent(akey(shown(it)), parts.first()) }
        }
        // Artists' song counts (for the 5-song rule), counted once per recording.
        val artistSongs = byKey.values.mapNotNull { l -> l.maxBy { it.homeKind }.takeIf { it.homeKind == 1 } }
            .groupingBy { akey(shown(ownerOf(it.track))) }.eachCount()
        // A collection's albums by how many of its songs they gather.
        val albumSize = cands.filter { it.homeKind >= 2 }.groupBy { it.home.lowercase() to norm(it.album) }.mapValues { (_, l) -> l.map { key(it.track) }.toSet().size }
        val alternatives = HashMap<String, List<String>>()
        val items = byKey.mapNotNull { (k, list) ->
            val best = list.maxWith(compareBy<Cand>({ rank(it.track) }, { richness(it.track) }, { it.track.kbps }))
            var homeKind = list.maxOf { it.homeKind }
            val placed = list.filter { it.homeKind == homeKind }
            var home = placed.groupingBy { it.home }.eachCount().maxBy { it.value }.key
            if (homeKind == 1) absorbed[akey(shown(ownerOf(best.track)))]?.let { home = it; homeKind = 3 }
            val inHome = placed.filter { it.home == home }
            val chosen = if (inHome.isEmpty()) best else if (homeKind >= 2) inHome.maxWith(compareBy({ albumSize[home.lowercase() to norm(it.album)] ?: 0 }, { -(it.year?.toIntOrNull() ?: 9999) }))
                else inHome.maxWith(compareBy({ rank(it.track) }, { richness(it.track) }))
            alternatives[k] = list.map { it.albumDir }.distinct().filter { it != chosen.albumDir }
            val existing = have[k]
            if (existing != null && rank(existing) >= rank(best.track)) return@mapNotNull null
            val t = best.track
            val rawArtist = t.artist.ifBlank { chosen.track.artist }
            val artist = if (rawArtist.contains(',') || rawArtist.contains('&')) rawArtist else shown(rawArtist)
            val owner = ownerOf(t)
            val albumArtist = when (homeKind) {
                3 -> shown(home.substringBefore('/'))
                2 -> home.substringBefore('/')
                else -> if (owner.isBlank()) "Otros artistas" else shown(owner)
            }
            val folder = when (homeKind) {
                3 -> (listOf(shown(home.substringBefore('/'))) + home.split('/').drop(1)).joinToString("/") { clean(it) }
                2 -> home.split('/').joinToString("/") { clean(it) }
                else -> if (owner.isNotBlank() && (artistSongs[akey(albumArtist)] ?: 0) >= 5) clean(albumArtist) else "Otros artistas"
            }
            // An album named only as its collection or folder ("Sailor Moon/Sailor moon") is no album.
            val album = chosen.album.takeUnless { it.isBlank() || plain(it) == plain(home.substringAfterLast('/')) || plain(it) == plain(albumArtist) || GRAB_BAG.matches(it.trim()) } ?: "Sueltas"
            val year = chosen.year ?: t.year
            val no = chosen.track.trackNo ?: t.trackNo
            val disc = chosen.track.discNo
            val albumDir = clean(listOfNotNull(year, album).joinToString(" - "))
            val num = no?.let { n -> (disc?.takeIf { it > 1 }?.let { "$it-" } ?: "") + n.toString().padStart(2, '0') }
            val title = t.title.ifBlank { File(t.path).nameWithoutExtension }
            val name = listOfNotNull(num, title, artist.ifBlank { null }, album, year).joinToString(" - ") { clean(it) }
            Item(
                source = best.source, track = t, target = "$targetRoot/$folder/$albumDir/$name.mp3",
                reason = when (rank(t)) { 4 -> "sin pérdida → MP3 320"; 3 -> "MP3 320"; else -> "MP3 ${t.kbps} kbps (mientras tanto; a la to-do)" },
                album = album, year = year, trackNo = no, albumArtist = albumArtist,
                grouping = if (homeKind >= 2) shown(home.substringBefore('/')) else null,
                cover = chosen.albumDir, key = k,
            )
        }.filter { !File(it.target).exists() }
        val seen = HashMap<String, Int>()
        val unique = items.map { i ->
            val n = seen.merge(i.target.lowercase(), 1, Int::plus)!!
            if (n == 1) i else i.copy(target = i.target.removeSuffix(".mp3") + " ($n).mp3")
        }
        return Plan(unique, skipped, alternatives)
    }

    /** Copies (or converts) one item, tagged as planned, with its album's cover. */
    fun copy(i: Item, covers: Covers?): Result<String> = runCatching {
        val out = File(i.target)
        require(!out.exists()) { "ya existe" }
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, ".part-" + out.name)
        val t = i.track
        val cover = i.cover?.let { covers?.forFolder(it) }
        val meta = buildList {
            fun m(k: String, v: String?) { if (!v.isNullOrBlank()) { add("-metadata"); add("$k=$v") } }
            m("title", t.title.ifBlank { File(t.path).nameWithoutExtension })
            m("artist", t.artist)
            m("album", i.album)
            m("album_artist", i.albumArtist)
            m("date", i.year)
            m("track", i.trackNo?.toString())
            m("grouping", i.grouping)
        }
        val inputs = listOf("-i", t.path) + (cover?.let { listOf("-i", it.path) } ?: emptyList())
        val maps = if (cover != null) listOf("-map", "0:a", "-map", "1:v") else listOf("-map", "0:a", "-map", "0:v?")
        val codec = if (t.lossless) listOf("-c:a", "libmp3lame", "-b:a", "320k") else listOf("-c:a", "copy")
        val args = listOf(Tools.ffmpeg, "-v", "error", "-y") + inputs + maps + listOf("-c:v", "copy", "-disposition:v", "attached_pic") +
            codec + listOf("-map_metadata", "0", "-id3v2_version", "3") + meta + listOf("-f", "mp3", tmp.path)
        val (code, msg) = Tools.run(*args.toTypedArray(), timeoutSec = 900)
        check(code == 0 && tmp.length() > 0) { "ffmpeg: ${msg.take(300)}" }
        check(tmp.renameTo(out)) { "no se pudo renombrar" }
        covers?.recordPrimary(i.key, i.cover)
        out.path
    }
}

/**
 * The covers: one file per distinct picture (by its bytes), taken once per album folder and
 * reused by every song of it. Each recording has its primary cover (its album's, embedded in
 * the file) and the covers of the other releases it is on — proposed, to approve or reject
 * on the to-do. Only releases holding the same recording (same title, artist and length)
 * are proposed.
 */
class Covers(root: String) {
    private val dir = File(root, ".bitchord-library/covers").apply { mkdirs() }
    private val dbFile = File(root, ".bitchord-library/covers.json")
    private val db: JSONObject = runCatching { JSONObject(dbFile.readText()) }.getOrDefault(JSONObject())
    private val folders = db.optJSONObject("folders") ?: JSONObject().also { db.put("folders", it) }
    private val songs = db.optJSONObject("songs") ?: JSONObject().also { db.put("songs", it) }

    /** The picture of an album folder: its cover image file, else the first embedded picture. */
    fun forFolder(folder: String): File? {
        if (folders.has(folder)) return folders.optString(folder).ifBlank { null }?.let { File(dir, it) }?.takeIf { it.isFile }
        val f = File(folder)
        val image = f.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { n -> listOf("cover", "folder", "front", "albumart").indexOfFirst { n.name.lowercase().startsWith(it) }.let { if (it < 0) 9 else it } }
            ?.firstOrNull { !it.name.lowercase().contains("small") && !it.name.lowercase().contains("back") }
        val tmp = File(dir, ".tmp-" + System.nanoTime() + ".jpg")
        val ok = if (image != null) runCatching { image.copyTo(tmp, overwrite = true); true }.getOrDefault(false) else {
            val audio = f.listFiles()?.filter { it.extension.lowercase() in setOf("mp3", "flac", "m4a") }?.sorted()?.firstOrNull()
            audio != null && Tools.run(Tools.ffmpeg, "-v", "error", "-y", "-i", audio.path, "-an", "-map", "0:v?", "-c:v", "copy", "-frames:v", "1", "-f", "image2", tmp.path, timeoutSec = 60).first == 0 && tmp.length() > 0
        }
        if (!ok) { tmp.delete(); folders.put(folder, ""); return null }
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }.take(16)
        val name = "$hash.${if (image?.extension?.lowercase() == "png") "png" else "jpg"}"
        val final = File(dir, name)
        if (final.exists()) tmp.delete() else tmp.renameTo(final)
        folders.put(folder, name)
        return final
    }

    fun recordPrimary(key: String, folder: String?) {
        val s = songs.optJSONObject(key) ?: JSONObject().also { songs.put(key, it) }
        folder?.let { forFolder(it)?.let { f -> s.put("primary", f.name) } }
    }

    /** The other releases' covers of [key], proposed (unless already decided). */
    fun propose(key: String, otherFolders: List<String>) {
        val s = songs.optJSONObject(key) ?: JSONObject().also { songs.put(key, it) }
        val alts = s.optJSONObject("alternatives") ?: JSONObject().also { s.put("alternatives", it) }
        for (f in otherFolders) {
            val c = forFolder(f)?.name ?: continue
            if (c == s.optString("primary") || alts.has(c)) continue
            alts.put(c, JSONObject().put("status", "pending").put("from", f))
        }
    }

    data class Proposal(val key: String, val primary: File?, val alternative: File, val from: String)

    fun pending(): List<Proposal> = songs.keys().asSequence().flatMap { k ->
        val s = songs.getJSONObject(k)
        val alts = s.optJSONObject("alternatives") ?: return@flatMap emptySequence()
        alts.keys().asSequence().filter { alts.getJSONObject(it).optString("status") == "pending" }.map { c ->
            Proposal(k, s.optString("primary").ifBlank { null }?.let { File(dir, it) }, File(dir, c), alts.getJSONObject(c).optString("from"))
        }
    }.toList()

    fun decide(key: String, cover: String, approve: Boolean) {
        songs.optJSONObject(key)?.optJSONObject("alternatives")?.optJSONObject(cover)?.put("status", if (approve) "approved" else "rejected")
    }

    fun save() = dbFile.writeText(db.toString())
}

/**
 * Freeing space: a source folder (an artist's, a collection's) whose every song is in the
 * library at least as good can go — first copied to the online archive and checked, then
 * deleted from the Mac.
 */
object FreeSpace {
    data class Folder(val source: Source, val path: String, val files: Int, val bytes: Long, val covered: Int)

    fun scan(sources: List<Source>, tracks: List<Track>, root: String): List<Folder> {
        val lib = tracks.filter { it.path.startsWith("$root/") }.groupBy { Builder.key(it) }.mapValues { (_, l) -> l.maxOf { Builder.rank(it) } }
        return sources.filter { it.mode == "all" }.flatMap { s ->
            tracks.filter { it.path.startsWith(s.path + "/") }
                .groupBy { s.path + "/" + it.path.removePrefix(s.path + "/").substringBefore('/') }
                .filterKeys { File(it).isDirectory }
                .map { (folder, list) ->
                    val covered = list.count { t -> (lib[Builder.key(t)] ?: -1) >= Builder.rank(t).coerceAtMost(3) }
                    val bytes = File(folder).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    Folder(s, folder, list.size, bytes, covered)
                }
        }.sortedByDescending { it.bytes }
    }

    /** Copies [f] to the archive, checks every file arrived whole, then deletes it from the Mac. */
    fun archive(f: Folder, archiveRoot: String, delete: Boolean): Result<String> = runCatching {
        val src = File(f.path)
        val dest = File(File(archiveRoot, f.source.name), src.name)
        src.walkTopDown().filter { it.isFile }.forEach { file ->
            val to = File(dest, file.relativeTo(src).path)
            if (!to.exists() || to.length() != file.length()) { to.parentFile.mkdirs(); file.copyTo(to, overwrite = true) }
        }
        val missing = src.walkTopDown().filter { it.isFile }.count { file -> File(dest, file.relativeTo(src).path).length() != file.length() }
        check(missing == 0) { "$missing archivos no llegaron enteros; no se borra nada" }
        if (delete) check(src.deleteRecursively()) { "copiado, pero no se pudo borrar del Mac" }
        dest.path
    }
}
