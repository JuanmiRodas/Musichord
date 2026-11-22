package bitchord.library

import org.json.JSONObject
import java.io.File

/**
 * The clean library, built from the sources. Each recording (same title, same person, same
 * length) is copied once, from its best copy: lossless made into MP3 320, else a real MP3
 * 320, else the best MP3 meanwhile (to the to-do); on a tie, the copy with more tags and a
 * cover. AAC, m4a, opus, webm and wma are never copied. Nothing in a source is changed.
 *
 * What a song is: its tags, repaired ([Words.fix]); where a tag is empty or names nothing
 * ("Anime", "Varios", a file name), what its file name and folders say. Untagged files are
 * never taken for one another: a recording is known by its title, its person and its length.
 *
 * Who a person is: one person under every spelling — case, accents, word order, "[nZk]",
 * in Latin and in Japanese (paired when the same recording carries both names) — shown as
 * "Romaji (日本語)".
 *
 * Where a song lives: a source with rules (Yuki Kajiura) files it by them; a song of a
 * collection (an anime, a game, a series — a folder of many artists whose songs name it, or
 * an album named after one) goes to that collection, one folder per series whatever it is
 * called ("Pokemon", "Pokémon", "Pocket Monsters"; "Tales of Symphonia" inside "Tales of");
 * any other song to its artist's folder, or, when the artist has fewer than 5 songs, to
 * "Otros artistas/<artist>". An album folder whose songs share an album artist is that
 * artist's album, never a collection.
 */
object Builder {
    data class Item(
        val source: Source,
        val track: Track,
        val target: String,
        val reason: String,
        val title: String,
        val artist: String,
        val album: String,
        val year: String?,
        val trackNo: Int?,
        val disc: Int?,
        val albumArtist: String,
        val grouping: String?,
        val cover: String?,
        val key: String,
        /** Every source file holding this recording (the best is [track]). */
        val copies: List<String>,
    )

    /** [copy]: every song the library should hold, where; [keyOf]: each source file's recording. */
    data class Plan(val copy: List<Item>, val skipped: List<Pair<Track, String>>, val alternatives: Map<String, List<String>>, val keyOf: Map<String, String>)

    /** A song as the plan reads it. */
    private data class Id(val title: String, val artist: String, val albumArtist: String, val various: Boolean, val album: String, val year: String?, val no: Int?, val disc: Int?)

    private data class Cand(val source: Source, val track: Track, val id: Id, val home: String, val homeKind: Int, val albumDir: String)

    fun rank(t: Track): Int = when {
        t.lossless -> 4
        t.mp3_320 && t.fake320 != true -> 3
        t.codec == "mp3" -> 1 + (if (t.kbps >= 256) 1 else 0)
        else -> 0
    }
    private fun richness(t: Track) = t.tags.keys.count { it in setOf("title", "artist", "album", "date", "track", "album_artist", "genre", "composer", "lyrics", "disc") } + if (t.hasCover) 3 else 0

    private val BAD = Regex("""[/\\:*?"<>|]""")
    fun clean(s: String) = Words.nfc(s).replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').trim()

    // ── Collections' names ──

    /** Well-known series under their other names (more in `<library>/.bitchord-library/collections.json`). */
    private val BUILT_IN = mapOf(
        "Pokémon" to listOf("pokemon", "pocket monsters", "pocket monster", "pokemon anime", "ポケモン", "ポケットモンスター", "pikachu", "ピカチュウ", "gekijouban pocket monsters"),
        "Sailor Moon" to listOf("sailor moon", "bishoujo senshi sailor moon", "bishojo senshi sailor moon", "bishōjo senshi sailor moon", "pretty guardian sailor moon", "美少女戦士セーラームーン", "セーラームーン"),
        "Detective Conan" to listOf("detective conan", "meitantei conan", "名探偵コナン", "case closed"),
        "Cardcaptor Sakura" to listOf("cardcaptor sakura", "card captor sakura", "ccsakura", "cc sakura", "カードキャプターさくら"),
        "Dragon Ball" to listOf("dragon ball", "dragonball", "ドラゴンボール"),
        "Naruto" to listOf("naruto", "ナルト"),
        "Evangelion" to listOf("neon genesis evangelion", "evangelion", "エヴァンゲリオン"),
        "Ojamajo Doremi" to listOf("ojamajo doremi", "doremi", "magical doremi", "おジャ魔女どれみ"),
        "Crayon Shin-chan" to listOf("crayon shin chan", "crayonshinchan", "クレヨンしんちゃん"),
        "Tsubasa Chronicle" to listOf("tsubasa chronicle", "tsubasa chronicles", "ツバサ・クロニクル"),
    )
    /** plain(alias) → collection. */
    @Volatile private var aliases: Map<String, String> = emptyMap()

    /** `collections.json`: {"aliases": {"美少女戦士セーラームーン": "Sailor Moon"}} — one collection under several names. */
    private fun loadAliases(root: String) {
        val user = runCatching {
            val o = JSONObject(File(root, ".bitchord-library/collections.json").readText()).getJSONObject("aliases")
            o.keys().asSequence().associate { Words.plain(it) to o.getString(it) }
        }.getOrDefault(emptyMap())
        aliases = BUILT_IN.flatMap { (name, list) -> (list + name).map { Words.plain(it) to name } }.toMap() + user
    }

    private val GENERIC = Regex("(?i)\\b(singles?|o\\.?s\\.?t\\.?|soundtracks?|bso|music collection|collection|best|songs?|music|op|ed|openings?|endings?|cds?|disc|vol\\.?\\s*\\d+|anime|movies?|tv|original|themes?|character|series|album|mp3|flac)\\b")

    /** "[Nemuri] Bishōjo Senshi Sailor Moon 美少女戦士セーラームーン (1992-2000) [MP3]" → "Sailor Moon". */
    fun collectionName(folder: String): String {
        val c = clean(folder.replace(Regex("""\[[^\]]*]"""), " ").replace(Regex("""\((?:\d{4}(?:\s*-\s*\d{4})?|MP3|FLAC)\)""", RegexOption.IGNORE_CASE), " "))
        aliases[Words.plain(c)]?.let { return it }
        val stripped = clean(c.replace(GENERIC, " ")).trim('-', '–', ' ').takeIf { it.count(Char::isLetter) >= 3 } ?: c
        aliases[Words.plain(stripped)]?.let { return it }
        val p = Words.plain(c)
        aliases.entries.filter { it.key.length >= 5 && p.contains(it.key) }.maxByOrNull { it.key.length }?.let { return it.value }
        return stripped
    }

    // ── People ──

    private val CJK = Regex("""[\p{IsHiragana}\p{IsKatakana}\p{IsHan}\p{IsHangul}]""")
    private fun latin(s: String) = s.any { it.isLetter() } && !CJK.containsMatchIn(s)
    private val VARIOUS = Regex("(?i)^(various( artists)?|varios( artistas)?|varias|va|v\\.a\\.|compilation|soundtrack|anime|unknown( artist)?|artista desconocido)$")
    private fun firstArtist(s: String) = s.split(',', '&', '、', ';', '/', '×', ':', '：').first()
        .replace(Regex("(?i)\\s(feat|ft|featuring|with|con)\\.?\\s.*$"), "").trim()
    private fun folderName(s: String) = s.replace(Regex("(?i)\\s*[-–]?\\s*(discography|discografía|discografia( completa)?|collection|colecci[oó]n|коллекция|complete|\\(mp3\\)|\\[mp3\\]|\\(flac\\))"), " ")
        .replace(Regex("\\[[^\\]]*]|\\(\\d{4}[^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

    /** One key per person, whatever the spelling: "SawanoHiroyuki[nZk]" = "Sawano Hiroyuki" = "Hiroyuki Sawano"; "Beyonce" = "Beyoncé". */
    fun pkey(s0: String): String {
        val s1 = Words.nfc(s0).replace(Regex("[\\[(（【][^\\])）】]*[\\])）】]"), " ").trim().ifBlank { s0 }
        val s = s1.replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " ")
        // Romaji spelt either way: "Ohnuki" = "Onuki" = "Ōnuki", "Youko" = "Yoko"; "Ke$ha" = "Kesha".
        fun fold(w: String) = w.replace("ou", "o").replace("oo", "o").replace("uu", "u").replace(Regex("oh(?=[^aeiou]|$)"), "o")
        return if (latin(s)) Regex("[\\p{L}\\p{N}$]+").findAll(s).map { fold(Words.plain(it.value.replace('$', 's'))) }.filter { it.isNotEmpty() }.sorted().joinToString("")
        else Words.plain(s)
    }

    /** People, joined: pkey → the group's key; and each group's name as shown. */
    private class People {
        val parent = HashMap<String, String>()
        val variants = HashMap<String, HashMap<String, Double>>()
        fun find(k: String): String { var x = k; while (parent[x] != null && parent[x] != x) x = parent[x]!!; return x }
        fun union(a: String, b: String) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }
        fun see(name: String, weight: Double) {
            val n = Words.nfc(name).trim()
            if (Words.noArtist(n) || VARIOUS.matches(n)) return
            val shown = n.replace(Regex("\\s*[(（]([^)）]+)[)）]\\s*$")) { m -> if (latin(m.groupValues[1])) m.value else "" }.trim().ifBlank { n }
            variants.getOrPut(pkey(n)) { HashMap() }.merge(shown, weight, Double::plus)
            parent.putIfAbsent(pkey(n), pkey(n))
        }
        private fun score(name: String, w: Double): Double {
            val words = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
            val caps = words.isNotEmpty() && words.all { !it[0].isLowerCase() }
            val accents = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            val shouting = name.length > 4 && name.none { it.isLowerCase() } && name.any { it.isUpperCase() }
            return w * (1 + (if (caps) 0.8 else 0.0) + (if (accents) 0.8 else 0.0) - (if (shouting) 0.3 else 0.0))
        }
        private val shownCache = HashMap<String, String>()
        fun shown(name: String): String {
            val k = pkey(name)
            if (k.isEmpty() || variants[k] == null) return Words.nfc(name).trim()
            val g = find(k)
            return shownCache.getOrPut(g) {
                val members = variants.keys.filter { find(it) == g }
                val all = members.flatMap { m -> variants[m].orEmpty().entries }
                // The spelling most used, then within it (same letters) the one with capitals and accents:
                // "Naomi Tamura" over "naomi tamura", "Beyoncé" over "Beyonce".
                val latins = all.filter { latin(it.key) }
                val spelling = latins.groupBy { Words.plain(it.key) }.maxByOrNull { (_, l) -> l.sumOf { it.value } }?.value.orEmpty()
                val withCaps = spelling.filter { v -> v.key.any { it.isUpperCase() } }.ifEmpty { spelling }
                val lat = withCaps.maxByOrNull { score(it.key, it.value) }?.key
                // A Japanese name is in Japanese (not "L'Arc乣en乣Ciel", a Latin name misread).
                val east = all.filter { CJK.containsMatchIn(it.key) && it.key.count { c -> c in 'A'..'Z' || c in 'a'..'z' } < 3 }.maxByOrNull { it.value }?.key
                when { lat != null && east != null -> "$lat ($east)"; lat != null -> lat; else -> east ?: Words.nfc(name).trim() }
            }
        }
        fun group(name: String): String = pkey(name).let { if (it.isEmpty()) "" else find(it) }
    }

    private val GRAB_BAG = Regex("(?i)^(vari[oa]s|various( artists)?|va|musique|m[uú]sica.*|music|otros.*|other.*|misc.*|comercial|rock|pop|nueva.*|mix.*|random|singles?|descargad.*|anime|animes|anime y japonadas|openings?|endings?|openings, endings.*|colaboraciones|mp3|videojuegos?|games?|juegos?|series|películas|peliculas|movies|bso|ost|espa[ñn]ol|spanish|english|ingl[eé]s|j-?pop|k-?pop|classical|cl[aá]sica|pop( .*)?|rock( .*)?|jazz|dance|electr[oó]nica?|latino?|indie|metal|chill.*|lounge|[0-9]{2}s|años [0-9]{2}|other artists|otros artistas|varios artistas)$")

    private val KARAOKE = Regex("(?i)karaoke|カラオケ|off[ -]?vocal|instrumental|inst\\.\\)|\\(inst\\)|backing track|オリジナル・カラオケ")

    /**
     * `<library>/.bitchord-library/quarantine.json`: folders and files that never enter the library
     * (the clips in "Música de los vídeos OPS", and their copies another tool renamed and spread
     * over other folders — found by their audio, see LIBRARY_PLAN.md). Sources stay untouched.
     */
    class Quarantine(val folders: List<String>, val files: Map<String, String>) {
        fun reason(path: String): String? {
            val p = Words.nfc(path)
            files[p]?.let { return "cuarentena: $it" }
            return if (folders.any { p.startsWith("$it/") }) "cuarentena: clip de vídeo (${folders.first { p.startsWith("$it/") }.substringAfterLast('/')})" else null
        }
        companion object {
            fun load(root: String) = runCatching {
                val o = JSONObject(File(root, ".bitchord-library/quarantine.json").readText())
                val fo = o.optJSONArray("folders")?.let { a -> (0 until a.length()).map { Words.nfc(a.getString(it)).trimEnd('/') } }.orEmpty()
                val fi = o.optJSONObject("files")?.let { f -> f.keys().asSequence().associate { Words.nfc(it) to f.getString(it) } }.orEmpty()
                Quarantine(fo, fi)
            }.getOrDefault(Quarantine(emptyList(), emptyMap()))
        }
    }

    fun plan(sources0: List<Source>, allTracks: List<Track>, targetRoot: String): Plan {
        // Each source's path spelt as its files are (accents in one Unicode form or the other).
        val sources = sources0.map { s ->
            val depth = s.path.trimEnd('/').count { it == '/' }
            val want = Words.nfc(s.path).trimEnd('/') + "/"
            val t = allTracks.firstOrNull { Words.nfc(it.path).startsWith(want) } ?: return@map s
            s.copy(path = t.path.split('/').take(depth + 1).joinToString("/"))
        }
        loadAliases(targetRoot)
        val skipped = ArrayList<Pair<Track, String>>()
        val quarantine = Quarantine.load(targetRoot)
        val tracks = allTracks.filter { t -> quarantine.reason(t.path)?.let { skipped += t to it; false } ?: true }
        val people = People()
        val bySource = sources.associateWith { s -> tracks.filter { it.path.startsWith(s.path + "/") } }

        // 1. Tags as they are (repaired), and who is named in them.
        fun tag(t: Track, k: String) = Words.fix(when (k) { "album_artist" -> t.albumArtist; else -> t.tags[k].orEmpty() })
        tracks.forEach { t -> people.see(firstArtist(tag(t, "artist")), 1.0); people.see(firstArtist(tag(t, "album_artist")), 1.0) }
        val known = people.variants.filter { (_, v) -> v.values.sum() >= 2 }.keys
        fun isKnown(s: String) = !Words.noArtist(s) && pkey(folderName(s)) in known
        val folderVotes = HashMap<Pair<String, String>, Int>()
        // Artists' own folders: their names, as people spelt them (counted five times).
        for ((s, mine) in bySource) if (s.mode == "all") mine.groupBy { it.path.removePrefix(s.path + "/").substringBefore('/', "") }.forEach { (top, list) ->
            if (top.isEmpty()) return@forEach
            val f = folderName(Words.fix(top))
            val owners = list.groupingBy { pkey(firstArtist(tag(it, "album_artist").ifBlank { tag(it, "artist") })) }.eachCount()
            val best = owners.maxByOrNull { it.value }
            if (best != null && best.value >= list.size * 0.6 && (best.key == pkey(f) || isKnown(f))) people.see(f, 5.0)
            if (best != null && best.value >= list.size * 0.6 && latin(f) && !Words.noArtist(f) && !GRAB_BAG.matches(f)) {
                // A Latin folder of songs tagged in Japanese: the same person in two scripts.
                val tagged = list.map { firstArtist(tag(it, "album_artist").ifBlank { tag(it, "artist") }) }.firstOrNull { pkey(it) == best.key }
                if (tagged != null && !latin(tagged)) { people.see(f, 5.0); folderVotes.merge(best.key to pkey(f), best.value, Int::plus) }
            }
        }

        // 2. What each song is: tags, else its file name, else its folders.
        val ids = HashMap<String, Id>()
        for ((s, mine) in bySource) for (t in mine) {
            val rel = t.path.removePrefix(s.path + "/")
            val dirs = rel.split('/').dropLast(1).map { Words.fix(it) }
            val fn = Words.fromName(Words.fix(File(t.path).name))
            var artist = tag(t, "artist").takeUnless { Words.noArtist(it) }.orEmpty()
            val aaRaw = tag(t, "album_artist")
            val various = VARIOUS.matches(aaRaw.trim()) || Words.noArtist(aaRaw) && aaRaw.isNotBlank()
            var albumArtist = aaRaw.takeUnless { Words.noArtist(it) || Words.noArtist(firstArtist(it)) || VARIOUS.matches(it.trim()) }.orEmpty()
            var album = tag(t, "album").takeUnless { Words.noAlbum(it) }.orEmpty()
            val folderWords = dirs.map { Words.plain(it) }.toSet()
            var title = tag(t, "title").takeUnless { Words.noTitle(it) }?.let { if (Regex("^\\s*\\d{1,3}\\s*([.)]\\s*|-\\s+)\\S").containsMatchIn(it)) Words.dropNumber(it) else it }.orEmpty()
            if (title.isEmpty()) {
                val parts = fn.parts.filter { Words.plain(it) !in folderWords || fn.parts.size == 1 }.ifEmpty { fn.parts }
                val who = parts.indexOfFirst { isKnown(it) || artist.isNotEmpty() && pkey(firstArtist(it)) == pkey(firstArtist(artist)) }
                when {
                    parts.size == 1 -> title = parts[0]
                    who >= 0 -> { if (artist.isEmpty()) artist = parts[who]; title = parts.filterIndexed { i, _ -> i != who }.firstOrNull { Words.plain(it) != Words.plain(album) } ?: parts[0] }
                    parts.size == 2 -> { if (artist.isEmpty() && !Words.noArtist(parts[0])) artist = parts[0]; title = parts[1] }
                    else -> { title = parts[0]; if (artist.isEmpty() && !Words.noArtist(parts[1])) artist = parts[1] }
                }
                title = Words.dropNumber(title)
            }
            if (artist.isEmpty() && fn.parts.size >= 2) fn.parts.firstOrNull { p ->
                val w = Words.plain(p); w != Words.plain(title) && w != Words.plain(album) && w !in folderWords && !Words.noArtist(p) && !GRAB_BAG.matches(p.trim()) && !Regex("^\\d{4}$").matches(p)
            }?.let { artist = it }
            // "The show must go on-Queen": a known artist glued to the title.
            if (artist.isEmpty()) title.split(Regex("\\s*[-–]\\s*")).takeIf { it.size == 2 }?.let { (x, y) ->
                when { isKnown(y) -> { artist = y; title = x }; isKnown(x) -> { artist = x; title = y } }
            }
            if (artist.isEmpty()) artist = albumArtist
            if (artist.isEmpty()) dirs.asReversed().firstOrNull { isKnown(it) }?.let { artist = folderName(it) }
            val (fYear, fAlbum) = dirs.lastOrNull()?.let { Words.albumFromFolder(it) } ?: (null to "")
            if (album.isEmpty() && dirs.isNotEmpty()) {
                val parent = dirs.last()
                if (!isKnown(parent) && !GRAB_BAG.matches(parent.trim()) && !Words.noAlbum(fAlbum) && Words.plain(fAlbum) != Words.plain(artist)) album = fAlbum
            }
            val year = t.year ?: fYear ?: dirs.firstOrNull { Regex("^\\d{4}$").matches(it) && it != "0000" }
            ids[t.path] = Id(title.ifBlank { File(t.path).nameWithoutExtension }, artist, albumArtist, various, album, year, t.trackNo ?: fn.no, t.discNo ?: fn.disc)
        }
        // A name only ever on one album, and that album's own name ("Tales of Symphonia" by "Tales of
        // Symphonia"), names the album or its series, not a person.
        val albumsOf = HashMap<String, MutableSet<String>>()
        ids.values.forEach { id -> listOf(id.artist, id.albumArtist).filter { it.isNotBlank() }.forEach { a -> albumsOf.getOrPut(pkey(firstArtist(a))) { HashSet() }.add(Words.plain(id.album)) } }
        fun onlyItsAlbum(a: String) = a.isNotBlank() && albumsOf[pkey(firstArtist(a))]?.let { it.size == 1 && it.first() == Words.plain(firstArtist(a)) } == true
        ids.replaceAll { _, id ->
            var x = id
            if (onlyItsAlbum(x.albumArtist)) x = x.copy(albumArtist = "", various = true)
            if (onlyItsAlbum(x.artist)) x = x.copy(artist = x.albumArtist)
            x
        }
        ids.values.forEach { people.see(firstArtist(it.artist), 0.5) }

        fun shown(n: String) = aliases[Words.plain(n)] ?: people.shown(n)
        fun gid(n: String) = people.group(firstArtist(n))
        fun ownerOf(id: Id) = firstArtist(id.albumArtist.ifBlank { id.artist })

        // 4. Collections by folder: a top folder of many artists whose songs name it, or the
        // folders inside a container of them ("Anime/Naruto").
        fun isCollection(name: String, list: List<Track>, below: (Track) -> String): Boolean {
            if (list.size < 3 || GRAB_BAG.matches(name.trim()) || Words.noAlbum(name)) return false
            // A folder named after a person is that person's ("Yuki Kajiura/See Saw/…").
            if (isKnown(name) && name.isNotBlank()) return false
            val owners = list.mapNotNull { ids[it.path]?.albumArtist?.takeIf { a -> a.isNotBlank() } }.groupingBy { gid(it) }.eachCount()
            if ((owners.values.maxOrNull() ?: 0) >= list.size * 0.6) return false
            // A folder of no one's songs, all of one album named as the folder (a game's or a show's soundtrack).
            if (list.count { Words.plain(ids[it.path]?.album.orEmpty()) == Words.plain(name) } >= list.size * 0.6) return true
            val artists = list.groupingBy { gid(ids[it.path]?.artist.orEmpty()) }.eachCount()
            if (artists.size < 3 || (artists.values.maxOrNull() ?: 0) >= list.size * 0.6) return false
            val words = Words.plain(name.replace(GENERIC, " "))
            if (words.length < 3) return false
            val named = list.count { t -> val id = ids[t.path]; listOf(id?.album.orEmpty(), id?.title.orEmpty(), below(t)).any { Words.plain(it).contains(words) } }
            return named >= list.size * 0.3
        }
        val series = HashMap<String, String>() // source path prefix → collection's name (before families)
        for ((s, mine) in bySource) {
            if (s.mode != "all") continue
            fun rel(t: Track) = t.path.removePrefix(s.path + "/")
            val byTop = mine.groupBy { rel(it).substringBefore('/', "") }.filterKeys { it.isNotEmpty() }
            for ((top, list) in byTop) {
                if (isCollection(Words.fix(top), list) { rel(it).removePrefix("$top/") } && !GRAB_BAG.matches(collectionName(Words.fix(top)))) { series["${s.path}/$top/"] = collectionName(Words.fix(top)); continue }
                // Inside an artist's own folder ("Zara Larsson/Varios 2016") there are only their albums.
                val owners = list.groupingBy { gid(ownerOf(ids[it.path] ?: return@groupingBy "")) }.eachCount()
                val top1 = owners.filterKeys { it.isNotEmpty() }.values.maxOrNull() ?: 0
                if (!GRAB_BAG.matches(Words.fix(top).trim()) && top1 >= list.size * 0.6) continue
                list.filter { rel(it).count { c -> c == '/' } >= 2 }.groupBy { rel(it).split('/')[1] }.forEach { (sub, l) ->
                    if (isCollection(Words.fix(sub), l) { rel(it).removePrefix("$top/$sub/") } && !GRAB_BAG.matches(collectionName(Words.fix(sub)))) series["${s.path}/$top/$sub/"] = collectionName(Words.fix(sub))
                }
            }
        }

        // 3. One person in two scripts: the same recording under a Latin name and a Japanese one.
        val votes = HashMap<Pair<String, String>, Int>(folderVotes)
        tracks.filter { ids[it.path] != null }.groupBy { t -> Words.plain(ids[t.path]!!.title.replace(Regex("[\\[(（【].*?[\\])）】]"), "")) + "|" + ((t.seconds ?: 0.0)).toInt() }
            .values.forEach { same ->
                val names = same.map { firstArtist(ids[it.path]!!.artist) }.filter { it.isNotBlank() }
                val lat = names.filter { latin(it) }.map(::pkey).distinct()
                val east = names.filter { !latin(it) }.map(::pkey).distinct()
                if (lat.size == 1 && east.size == 1) votes.merge(east[0] to lat[0], 1, Int::plus)
            }
        tracks.forEach { t ->
            Regex("""^(.+?)\s*[(（]([^)）]+)[)）]$""").find(firstArtist(tag(t, "artist")))?.let { m ->
                val (a, b) = m.groupValues[1].trim() to m.groupValues[2].trim()
                if (latin(a) && !latin(b)) { people.see(a, 1.0); people.see(b, 1.0); votes.merge(pkey(b) to pkey(a), 3, Int::plus) }
            }
        }
        val sameTrack = HashMap<Pair<String, String>, Int>()
        ids.values.forEach { id ->
            val a = id.artist.trim(); val b = id.albumArtist.trim()
            if (a.isBlank() || b.isBlank() || Regex("[,&、;/×:]|\\s(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE).containsMatchIn(a + " " + b)) return@forEach
            val (lat, east) = when { latin(a) && !latin(b) -> a to b; latin(b) && !latin(a) -> b to a; else -> return@forEach }
            sameTrack.merge(pkey(east) to pkey(lat), 1, Int::plus)
        }
        sameTrack.filter { it.value >= 2 }.forEach { (k, v) -> votes.merge(k, v, Int::plus) }
        // Only names of people (not of a series), each paired once, and only when each is the
        // other's best match: "Ayumi Hamasaki" and "宇多田ヒカル" never meet through one mistagged compilation.
        val seriesWords = series.values.map { Words.plain(it) }.toSet() + aliases.keys
        val fair = votes.filter { (k, _) -> k.first.isNotEmpty() && k.second.isNotEmpty() && k.first !in seriesWords && k.second !in seriesWords }
        val bestLatin = fair.entries.groupBy { it.key.first }.mapValues { (_, l) -> l.maxBy { it.value } }
        val bestEast = fair.entries.groupBy { it.key.second }.mapValues { (_, l) -> l.maxBy { it.value } }
        bestLatin.forEach { (east, e) ->
            val lat = e.key.second
            if (bestEast[lat]?.key?.first != east) return@forEach
            if (e.value < 2 && (folderVotes[e.key] ?: 0) == 0) return@forEach
            people.union(lat, east)
        }


        // 5. Where each copy belongs: rules, a collection, or its artist.
        val cands = ArrayList<Cand>()
        for ((s, mine) in bySource) for (t in mine) {
            val id = ids[t.path] ?: continue
            if (!t.lossless && t.codec != "mp3") { skipped += t to "formato ${t.codec ?: "?"} (no se copia)"; continue }
            if (KARAOKE.containsMatchIn(id.title + " " + File(t.path).nameWithoutExtension)) { skipped += t to "karaoke / sin voz"; continue }
            val r = t.path.removePrefix(s.path + "/")
            val dir = File(t.path).parent
            if (s.mode == "rules") {
                if (s.exclude.any { it.containsMatchIn(r) }) { skipped += t to "excluida"; continue }
                val albumRel = r.substringBeforeLast('/')
                val rule = s.include.firstOrNull { (re, _) -> re.containsMatchIn("$albumRel/") }
                if (rule == null) { skipped += t to "fuera de las reglas"; continue }
                if (s.vocalOnly?.containsMatchIn(albumRel) == true || rule.second == "Bandas sonoras") {
                    val a = Words.plain(id.artist)
                    val main = s.mainArtist?.let { pkey(it) }
                    if (a.isEmpty() || pkey(id.artist) == main) { skipped += t to "instrumental de banda sonora"; continue }
                }
                val group = rule.first.find("$albumRel/")?.let { m -> rule.second.replace(Regex("""\$(\d)""")) { g -> m.groupValues.getOrNull(g.groupValues[1].toInt()).orEmpty() } } ?: rule.second
                cands += Cand(s, t, id, listOfNotNull(s.target, group.ifBlank { null }).joinToString("/"), 3, dir)
                continue
            }
            val col = series.entries.filter { t.path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            if (col != null) { cands += Cand(s, t, id, col, 2, dir); continue }
            // No one named anywhere but the folder it sits in ("Música/Nana Mizuki/01 - Mugen.mp3").
            val top = Words.fix(r.substringBefore('/', ""))
            val who = if (id.artist.isBlank() && top.isNotEmpty() && !GRAB_BAG.matches(top.trim()) && !Words.noArtist(top) && Words.plain(top) != Words.plain(id.album)) folderName(top) else null
            cands += Cand(s, t, if (who != null) id.copy(artist = who) else id, "", 1, dir)
        }

        // A rules source takes in its people's songs from every other source (Kalafina, See-Saw… under Yuki Kajiura).
        val absorbed = HashMap<String, String>()
        cands.filter { it.homeKind == 3 }.forEach { c ->
            val parts = c.home.split('/')
            absorbed.putIfAbsent(gid(parts.last()), c.home)
            c.source.mainArtist?.let { absorbed.putIfAbsent(gid(it), parts.first()) }
        }
        fun absorbedHome(name: String): String? {
            if (name.isBlank()) return null
            absorbed[gid(name)]?.let { return it }
            val p = Words.plain(name)
            return absorbed.entries.filter { it.key.length >= 6 && p.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
        }
        // 6. An album named after a collection is that collection's — unless it is an artist's own
        // album (four songs or more of theirs). A song of no one whose title or file names one
        // ("a2 jap One Piece end4.mp3"), or "by" a collection, is that collection's.
        val collectionKeys = (series.values.map { Words.plain(it.replace(GENERIC, " ")) to it } + aliases.entries.map { it.key to it.value })
            .filter { it.first.length >= 5 && !GRAB_BAG.matches(it.second) }.sortedByDescending { it.first.length }
        val exactCollection = collectionKeys.toMap()
        val ownAlbum = cands.groupBy { gid(ownerOf(it.id)) to Words.plain(it.id.album) }.mapValues { (_, l) -> l.map { Words.plain(it.id.title) }.toSet().size }
        val placedCands = cands.map { c ->
            if (c.homeKind != 1) return@map c
            // On an album of one of a rules source's people but sung by someone else (ALI PROJECT on a
            // Yuki Kajiura compilation): the singer's.
            if (c.id.albumArtist.isNotBlank() && absorbedHome(c.id.albumArtist) != null && c.id.artist.isNotBlank() &&
                gid(c.id.artist) != gid(c.id.albumArtist) && absorbedHome(firstArtist(c.id.artist)) == null) return@map c.copy(id = c.id.copy(albumArtist = ""))
            val owner = ownerOf(c.id)
            exactCollection[Words.plain(owner)]?.let { return@map c.copy(home = it, homeKind = 2, id = c.id.copy(artist = if (Words.plain(c.id.artist) == Words.plain(owner)) "" else c.id.artist)) }
            val a = Words.plain(c.id.album)
            val byAlbum = if (a.isEmpty()) null else collectionKeys.firstOrNull { a.contains(it.first) }
            if (byAlbum != null) {
                val own = owner.isNotBlank() && !c.id.various && (ownAlbum[gid(owner) to a] ?: 0) >= 4
                return@map if (own) c else c.copy(home = byAlbum.second, homeKind = 2)
            }
            if (owner.isBlank()) {
                // No one's song on a named album (a show's or a game's): that album is its collection.
                if (c.id.album.isNotBlank() && !GRAB_BAG.matches(c.id.album.trim())) return@map c.copy(home = collectionName(c.id.album), homeKind = 2)
                val text = Words.plain(c.id.title + " " + File(c.track.path).nameWithoutExtension)
                collectionKeys.firstOrNull { text.contains(it.first) }?.let { return@map c.copy(home = it.second, homeKind = 2) }
            }
            c
        }

        // 7. Collections' families: "Tales of Symphonia" and "Tales of Singles" are both in "Tales of";
        // "NARUTO" and "Naruto" are one.
        val colNames = placedCands.filter { it.homeKind == 2 }.map { it.home }
        val spelled = colNames.groupBy { Words.plain(it) }.mapValues { (_, v) -> v.groupingBy { it }.eachCount().maxWith(compareBy({ it.value + if (it.key.any(Char::isLowerCase)) 1000 else 0 })).key }
        fun tokens(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { Words.plain(it.value) }.filter { it.isNotEmpty() }.toList()
        val STOP = setOf("the", "a", "an", "of", "de", "la", "el", "los", "las", "le", "les", "no", "to")
        // Two collections that start alike ("Operación triunfo 1er disco", "Operación triunfo 1a ed") share a family too.
        val prefixes = spelled.values.distinct().filter { tokens(it).size >= 3 }.groupBy { tokens(it).take(2) }
            .filter { (p, l) -> l.size >= 2 && p.any { it !in STOP && it.length >= 3 } }
            .map { (_, l) -> l.first().split(Regex("\\s+")).take(2).joinToString(" ") }
        val bases = (spelled.values + prefixes).distinct()
        fun familyOf(name: String): String {
            val n = spelled[Words.plain(name)] ?: name
            val t = tokens(n)
            val base = bases.filter { b -> b != n && tokens(b).let { bt -> bt.size >= 2 && bt.size < t.size && t.subList(0, bt.size) == bt } }.minByOrNull { tokens(it).size }
            return if (base != null) "$base/${clean(n)}" else n
        }
        val homes = placedCands.map { c -> if (c.homeKind == 2) c.copy(home = familyOf(c.home)) else c }

        // 8. One recording, one place.
        fun key(c: Cand): String {
            val title = Words.plain(c.id.title.replace(Regex("[\\[(（【].*?[\\])）】]"), "")).ifEmpty { Words.plain(c.id.title) }
            val who = gid(c.id.artist).ifEmpty { "?" }
            return "$title|$who|${((c.track.seconds ?: 0.0) / 3).toInt()}"
        }
        val byKey = homes.groupBy(::key)
        val keyOf = HashMap<String, String>()
        byKey.forEach { (k, l) -> l.forEach { keyOf[it.track.path] = k } }
        val rulesOf = cands.filter { it.homeKind == 3 }.map { it.source }.distinct()
        /** Where a song of a rules source's person, found in another source, goes by those rules — or null: left out. */
        fun byRules(c: Cand, home: String): String? {
            val s = rulesOf.firstOrNull { home.startsWith(it.target ?: "\u0000") } ?: return home
            if (home != s.target) return home // one of its groups (Kalafina, See-Saw…): all of theirs
            // On her album but sung by one of her groups: that group's.
            absorbedHome(firstArtist(c.id.artist))?.takeIf { it != s.target }?.let { return it }
            val pseudo = "${c.id.year?.takeIf { it != "0000" } ?: "1999"}/${c.id.album}/"
            if (s.exclude.any { it.containsMatchIn(pseudo) }) return null
            val rule = s.include.firstOrNull { (re, _) -> re.containsMatchIn(pseudo) } ?: return null
            if (s.vocalOnly?.containsMatchIn(c.id.album) == true || rule.second == "Bandas sonoras") {
                if (Words.plain(c.id.artist).isEmpty() || pkey(c.id.artist) == s.mainArtist?.let { pkey(it) }) return null
            }
            return listOfNotNull(s.target, rule.second.takeIf { it.isNotBlank() && !it.contains('$') }).joinToString("/")
        }
        val albumSize = homes.filter { it.homeKind >= 2 }.groupBy { it.home.lowercase() to Words.plain(it.id.album) }.mapValues { (_, l) -> l.map(::key).toSet().size }

        data class Placed(val k: String, val best: Cand, val chosen: Cand, val home: String, val homeKind: Int, val copies: List<Cand>)
        val placed = byKey.mapNotNull { (k, list) ->
            val best = list.maxWith(compareBy<Cand>({ rank(it.track) }, { richness(it.track) }, { it.track.kbps }))
            var homeKind = list.maxOf { it.homeKind }
            val there = list.filter { it.homeKind == homeKind }
            var home = there.groupingBy { it.home }.eachCount().maxBy { it.value }.key
            if (homeKind == 1) absorbedHome(ownerOf(best.id))?.let { home = it; homeKind = 3 }
            // A "collection" named as one of a rules source's people (a folder of FictionJunction with guests),
            // or a song of one of them in a collection, is that person's.
            if (homeKind == 2) (absorbedHome(home.substringBefore('/')) ?: absorbedHome(firstArtist(best.id.artist)).takeIf { it != rulesOf.firstOrNull()?.target })?.let { home = it; homeKind = 3 }
            if (homeKind == 3 && list.none { it.homeKind == 3 }) {
                val h = byRules(best, home)
                if (h == null) { list.forEach { skipped += it.track to "fuera de las reglas de ${home.substringBefore('/')}" }; return@mapNotNull null }
                home = h
            }
            val inHome = there.filter { it.home == home }
            val chosen = when {
                inHome.isEmpty() -> best
                homeKind >= 2 -> inHome.maxWith(compareBy({ albumSize[home.lowercase() to Words.plain(it.id.album)] ?: 0 }, { if (it.id.album.isBlank()) 0 else 1 }, { -(it.id.year?.toIntOrNull() ?: 9999) }))
                else -> inHome.maxWith(compareBy({ if (it.id.album.isBlank()) 0 else 1 }, { rank(it.track) }, { richness(it.track) }))
            }
            Placed(k, best, chosen, home, homeKind, list)
        }
        // The 5-song rule, counted once per recording.
        val artistSongs = placed.filter { it.homeKind == 1 }.groupingBy { gid(ownerOf(it.best.id)) }.eachCount()

        data class Draft(val p: Placed, val folder: String, val album: String, val albumArtist: String, val grouping: String?)
        val drafts = placed.map { p ->
            val id = p.chosen.id
            val owner = ownerOf(p.best.id).ifBlank { ownerOf(id) }
            val (folder, albumArtist, grouping) = when (p.homeKind) {
                3, 2 -> {
                    val first = p.home.substringBefore('/')
                    val top = if (p.homeKind == 2) aliases[Words.plain(first)] ?: first else shown(first)
                    Triple((listOf(top) + p.home.split('/').drop(1)).joinToString("/") { clean(it) }, top, if (p.homeKind == 2) top else null)
                }
                else -> when {
                    owner.isBlank() -> Triple("Sin identificar", "", null)
                    (artistSongs[gid(owner)] ?: 0) >= 5 -> Triple(clean(shown(owner)), shown(owner), null)
                    else -> Triple("Otros artistas/" + clean(shown(owner)), shown(owner), null)
                }
            }
            // An album named only as its collection, its folder or its artist is no album.
            val lastHome = p.home.substringAfterLast('/')
            val album = id.album.takeUnless { it.isBlank() || Words.plain(it) == Words.plain(lastHome) || Words.plain(it) == Words.plain(albumArtist) ||
                Words.plain(it) == Words.plain(p.home.substringBefore('/')) || GRAB_BAG.matches(it.trim()) } ?: "Sueltas"
            Draft(p, folder, album, albumArtist, grouping)
        }
        // One year per album folder: the one most of its songs carry (on a tie, the latest).
        val albumYear = drafts.groupBy { it.folder.lowercase() to Words.plain(it.album) }.mapValues { (_, l) ->
            l.mapNotNull { it.p.chosen.id.year ?: it.p.best.id.year }.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
        }
        val alternatives = HashMap<String, List<String>>()
        val items = drafts.map { d ->
            val p = d.p
            val t = p.best.track
            val id = p.chosen.id
            val year = albumYear[d.folder.lowercase() to Words.plain(d.album)]
            val rawArtist = p.best.id.artist.ifBlank { id.artist }
            val artist = if (Regex("[,&、;/×]|\\s(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE).containsMatchIn(rawArtist)) Words.nfc(rawArtist) else shown(rawArtist)
            // Loose songs have no year of their own as a folder.
            val albumDir = if (d.album == "Sueltas") "Sueltas" else clean(listOfNotNull(year, d.album).joinToString(" - "))
            val num = id.no?.let { n -> (id.disc?.takeIf { it > 1 }?.let { "$it-" } ?: "") + n.toString().padStart(2, '0') }
            val title = p.best.id.title
            val name = listOfNotNull(num, title, artist.ifBlank { null }, d.album, year).joinToString(" - ") { clean(it) }
            alternatives[p.k] = p.copies.map { it.albumDir }.distinct().filter { it != p.chosen.albumDir }
            Item(
                source = p.best.source, track = t, target = "$targetRoot/${d.folder}/$albumDir/$name.mp3",
                reason = when (rank(t)) { 4 -> "sin pérdida → MP3 320"; 3 -> "MP3 320"; else -> "MP3 ${t.kbps} kbps (mientras tanto; a la to-do)" },
                title = title, artist = artist, album = d.album, year = year, trackNo = id.no, disc = id.disc,
                albumArtist = d.albumArtist, grouping = d.grouping, cover = p.chosen.albumDir, key = p.k,
                copies = p.copies.map { it.track.path },
            )
        }
        val seen = HashMap<String, Int>()
        val unique = items.sortedBy { it.target }.map { i ->
            val n = seen.merge(i.target.lowercase(), 1, Int::plus)!!
            if (n == 1) i else i.copy(target = i.target.removeSuffix(".mp3") + " ($n).mp3")
        }
        return Plan(unique, skipped, alternatives, keyOf)
    }

    /**
     * Writes one item: copied (or converted) from its source, or, with [from], taken from a
     * file already in the library (only its tags and place change). Tagged as planned, with
     * its album's cover. Written beside the target first, then renamed: never half a file.
     */
    fun copy(i: Item, covers: Covers?, from: File? = null): Result<String> = runCatching {
        val out = File(i.target)
        require(!out.exists()) { "ya existe" }
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, ".part-" + out.name)
        val input = from?.path ?: i.track.path
        val cover = i.cover?.let { covers?.forFolder(it) }
        val meta = buildList {
            fun m(k: String, v: String?) { add("-metadata"); add("$k=${v.orEmpty()}") }
            m("title", i.title)
            m("artist", i.artist)
            m("album", i.album)
            m("album_artist", i.albumArtist)
            m("date", i.year)
            m("track", i.trackNo?.toString())
            m("disc", i.disc?.toString())
            m("grouping", i.grouping)
        }
        val inputs = listOf("-i", input) + (cover?.let { listOf("-i", it.path) } ?: emptyList())
        val maps = if (cover != null) listOf("-map", "0:a", "-map", "1:v") else listOf("-map", "0:a", "-map", "0:v?")
        val reencode = from == null && i.track.lossless
        val codec = if (reencode) listOf("-c:a", "libmp3lame", "-b:a", "320k") else listOf("-c:a", "copy")
        val args = listOf(Tools.ffmpeg, "-nostdin", "-v", "error", "-y") + inputs + maps + listOf("-c:v", "copy", "-disposition:v", "attached_pic") +
            codec + listOf("-map_metadata", "0", "-id3v2_version", "3") + meta + listOf("-f", "mp3", tmp.path)
        val (code, msg) = Tools.run(*args.toTypedArray(), timeoutSec = 300)
        if (code != 0 || tmp.length() == 0L) { tmp.delete(); error("ffmpeg ${if (code == -1) "no respondió en 5 min" else "falló"}: ${msg.take(300)}") }
        check(tmp.renameTo(out)) { "no se pudo renombrar" }
        covers?.recordPrimary(i.key, i.cover)
        out.path
    }
}
