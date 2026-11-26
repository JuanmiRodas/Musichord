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
        /** Every album this recording is on (its original album, best-ofs, compilations…), for the phone to list it in each. */
        val releases: List<Release> = emptyList(),
    )
    data class Release(val album: String, val artist: String, val year: String?, val no: Int?, val disc: Int?, val folder: String)

    /** [copy]: every song the library should hold, where; [keyOf]: each source file's recording. */
    data class Plan(val copy: List<Item>, val skipped: List<Pair<Track, String>>, val alternatives: Map<String, List<String>>, val keyOf: Map<String, String>,
                    val japaneseAlbums: Set<String> = emptySet(), val yearless: Set<Pair<String, String>> = emptySet(),
                    /** Lists written beside the songs: a single's or a compilation's whole track list (.m3u and .txt), path → text. */
                    val sidecars: Map<String, String> = emptyMap(),
                    /** Artist (Latin, Japanese) pairs discovered across the library, for YouTube Music naming lookup. */
                    val scriptPairs: List<Pair<String?, String?>> = emptyList(),
                    /** Covers and scans copied alongside singles, compilations and One Piece Discografía: target path → source file path. */
                    val sidecarFiles: Map<String, String> = emptyMap())

    /** A song as the plan reads it. */
    private data class Id(val title: String, val artist: String, val albumArtist: String, val various: Boolean, val album: String, val year: String?, val no: Int?, val disc: Int?)

    private data class Cand(val source: Source, val track: Track, val id: Id, val home: String, val homeKind: Int, val albumDir: String)
    private data class Placed(val k: String, val best: Cand, val chosen: Cand, val home: String, val homeKind: Int, val copies: List<Cand>)
    private data class StudioSlot(val album: String, val year: String?, val no: Int, val albumDir: String)

    private fun versionTag(t: String): String {
        val s = Words.nfc(t).lowercase()
        return buildList {
            if (Regex("(?iu)\\b(live|en directo|directo|unplugged)\\b|ライブ|ライヴ").containsMatchIn(s)) add("live")
            if (Regex("(?iu)first\\s+take").containsMatchIn(s)) add("firsttake")
            if (Regex("(?iu)\\b(acoustic|ac[úu]stic[oa])\\b").containsMatchIn(s)) add("acoustic")
            if (Regex("(?iu)\\b(extended|club\\s+mix|radio\\s+edit|remix|rmx)\\b|リミックス").containsMatchIn(s)) add("mix")
            if (Regex("(?iu)re-?record(?:ed|ing)|self\\s*cover|new\\s+take|ニュー・?テイク").containsMatchIn(s)) add("rerec")
            Regex("(?iu)[(（\\[]\\s*(?:con|feat\\.?|ft\\.?|with)\\s+([^)）\\]]+)[)）\\]]").find(s)?.let { add("ft:" + Words.plain(it.groupValues[1])) }
        }.joinToString("+")
    }

    fun rank(t: Track): Int = when {
        t.lossless -> 4
        t.mp3_320 && t.fake320 != true -> 3
        t.codec == "mp3" -> 1 + (if (t.kbps >= 256) 1 else 0)
        // AAC (YouTube Music's .m4a) and Opus (.webm) kept as they are: as good as a 256 kbps MP3 when not starved.
        // AAC 320 (.m4a) is as good as MP3 320; an AAC 256 is kept until something better turns up (low priority on the to-do).
        Formats.native && t.codec == "aac" -> if (t.bitrate >= 290) 3 else if (t.bitrate >= 190) 2 else 1
        // Opus (YouTube's .webm) is not kept as it is: it becomes AAC 320, and is replaced by any MP3 or AAC copy.
        Formats.native && t.codec == "opus" -> 1
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
        "Tsubasa Chronicle" to listOf("tsubasa chronicle", "tsubasa chronicles", "tsubasa", "tsubasa reservoir chronicle", "tsubasa cronicas de sakura", "tsubasa croniques perdudes", "tsubasa les croniques perdudes", "tsubasa tokyo revelations", "ツバサ・クロニクル", "ツバサ"),
        "Digimon" to listOf("digimon", "デジモン", "digimon adventure"),
        "One Piece" to listOf("one piece", "ワンピース"),
        "Bleach" to listOf("bleach", "ブリーチ"),
        "Operación Triunfo" to listOf("operacion triunfo", "operación triunfo"),
        "Slayers" to listOf("slayers", "スレイヤーズ"),
        "Kimetsu no Yaiba" to listOf("kimetsu no yaiba", "鬼滅の刃", "demon slayer"),
        "My-HiME" to listOf("my hime", "mai hime", "舞 hime", "舞hime", "my otome", "mai otome", "舞 乙hime"),
        "Madoka Magica" to listOf("madoka magica", "mahou shoujo madoka magica", "mahou shoujo madoka magika", "puella magi madoka magica", "魔法少女まどか マギカ", "魔法少女まどかマギカ"),
        "Sword Art Online" to listOf("sword art online", "ソードアート オンライン", "ソードアートオンライン"),
        "Outlaw Star" to listOf("outlaw star", "seihou bukyou outlaw star", "星方武侠アウトロースター"),
        "Beck" to listOf("beck", "beck mongolian chop squad", "mongolian chop squad", "animation beck"),
        // The .hack saga (anime and games): its soundtracks by every composer together.
        "Hack" to listOf("hack sign", "hack gu", "hack g u", "hack roots", "hack dusk", "hack liminality", "hack quantum", "hack the world", "hack infection",
            "hack mutation", "hack outbreak", "hack quarantine", "hack zero", "hack link", "hack cell", "hack extra", "hack expansion", "hack legend of the twilight",
            "hack conglomerate", "hack gu trilogy", "tasogare no udewa densetsu", "黄昏の腕輪伝説"),
    )
    /** Names too common to look for inside other names ("Toki no Tsubasa" is ZARD's, not Tsubasa Chronicle). */
    private val EXACT_ONLY = setOf("tsubasa", "ツバサ", "doremi", "beck")

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
    /** Real band/duo names containing &, , or / (populated per plan from album_artist/folders and Last.fm). */
    @Volatile private var wholeBands: Set<String> = emptySet()
    private fun firstArtist(s: String): String {
        val stripped = Words.nfc(s).replace(Regex("(?i)\\s+(feat|ft|featuring|with|con)\\.?\\s.*$"), "").trim()
        if (stripped.isEmpty()) return ""
        // Keep real band/duo names intact ("Earth, Wind & Fire", "Simon & Garfunkel", "MYTH & ROID", "AC/DC", "S/mileage").
        if (Words.plain(stripped) in wholeBands || (stripped.any { it in ",&/" } && LastFm.tags(stripped).isNotEmpty())) return stripped
        var depth = 0
        val cut = stripped.indices.firstOrNull { i ->
            val c = stripped[i]
            when (c) {
                '(', '（', '[', '【' -> { depth++; false }
                ')', '）', ']', '】' -> { if (depth > 0) depth--; false }
                // Only split on '/' when surrounded by whitespace ("Artist A / Artist B"), never inside "AC/DC" or "S/mileage".
                '/' -> depth == 0 && i > 0 && i + 1 < stripped.length && stripped[i - 1].isWhitespace() && stripped[i + 1].isWhitespace()
                else -> depth == 0 && c in ",&、;×:："
            }
        } ?: -1
        return (if (cut >= 0) stripped.substring(0, cut) else stripped).trim()
    }
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
        /** Pairs never joined: two people whose names were confused somewhere ("Miho Morikawa" is not 福原美穂). */
        val apart = HashSet<Set<String>>()
        /** The Latin spelling a group is shown with when fixed by hand (still lets YtMusicNames attach Japanese script when appropriate). */
        val forcedLatin = HashMap<String, String>()
        fun union(a: String, b: String) {
            if (setOf(a, b) in apart || setOf(find(a), find(b)) in apart) return
            val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra
        }
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
        private fun isWesternArtist(lat: String?): Boolean {
            if (lat.isNullOrBlank()) return false
            val lt = LastFm.tags(lat).joinToString(" ")
            if (lt.isEmpty()) return false
            val eastTag = Regex("\\b(j-?pop|j-?rock|japanese|anime|city pop|vocaloid|enka|seiyuu|anison|k-?pop|korean|c-?pop|chinese|mandopop|cantopop|taiwanese)\\b").containsMatchIn(lt)
            val westTag = Regex("\\b(british|american|uk|usa|english|spanish|french|german|italian|swedish|canadian|australian|irish|boyband|latin pop)\\b").containsMatchIn(lt)
            return westTag && !eastTag
        }
        /** Pairs of (Latin, Japanese) spellings discovered for each group (for YouTube Music lookup). */
        fun scriptPairs(): List<Pair<String?, String?>> = variants.keys.map { find(it) }.distinct().mapNotNull { g ->
            val members = variants.keys.filter { find(it) == g }
            val all = members.flatMap { m -> variants[m].orEmpty().entries }
            val totalW = all.sumOf { it.value }.coerceAtLeast(1.0)
            val lat = forcedLatin[g] ?: all.filter { latin(it.key) }.maxByOrNull { it.value }?.key
            if (lat != null && (Words.plain(lat) in LATIN_ONLY || Words.plain(lat) in aliases.keys || isWesternArtist(lat))) return@mapNotNull null
            val eastEntry = all.filter { CJK.containsMatchIn(it.key) && it.key.count { c -> c in 'A'..'Z' || c in 'a'..'z' } < 3 }.maxByOrNull { it.value }
            val east = eastEntry?.takeIf { e -> (e.value >= 2.0 && e.value >= totalW * 0.15) || (e.value >= 1.0 && totalW <= 10.0) || Romaji.of(e.key)?.let { pkey(it) == pkey(lat.orEmpty()) } == true }?.key
            if (east == null) null else (lat to east)
        }
        fun shown(name: String): String {
            val k = pkey(name)
            if (k.isEmpty() || variants[k] == null) return Words.nfc(name).trim()
            val g = find(k)
            return shownCache.getOrPut(g) {
                val members = variants.keys.filter { find(it) == g }
                val all = members.flatMap { m -> variants[m].orEmpty().entries }
                val totalW = all.sumOf { it.value }.coerceAtLeast(1.0)
                // The spelling most used, then within it (same letters) the one with capitals and accents:
                // "Naomi Tamura" over "naomi tamura", "Beyoncé" over "Beyonce".
                val latins = all.filter { latin(it.key) }
                val spelling = latins.groupBy { Words.plain(it.key) }.maxByOrNull { (_, l) -> l.sumOf { it.value } }?.value.orEmpty()
                val withCaps = spelling.filter { v -> v.key.any { it.isUpperCase() } }.ifEmpty { spelling }
                val lat = forcedLatin[g] ?: withCaps.maxByOrNull { score(it.key, it.value) }?.key
                val blockCjk = lat != null && (Words.plain(lat) in LATIN_ONLY || Words.plain(lat) in aliases.keys || isWesternArtist(lat))
                // A Japanese name is in Japanese (not "L'Arc乣en乣Ciel"); guard against a stray guest/collab name sticking to a large group (e.g. JAM Project, VAGRANCY).
                val eastEntry = if (blockCjk) null else all.filter { CJK.containsMatchIn(it.key) && it.key.count { c -> c in 'A'..'Z' || c in 'a'..'z' } < 3 }.maxByOrNull { it.value }
                val east = eastEntry?.takeIf { e ->
                    val mbRoma = Romaji.of(e.key)
                    val mbAgrees = lat != null && mbRoma != null && pkey(mbRoma) == pkey(lat)
                    val mbConflicts = lat != null && mbRoma != null && pkey(mbRoma) != pkey(lat) && e.value < totalW * 0.35
                    !mbConflicts && (
                        (e.value >= 2.0 && e.value >= totalW * 0.15) ||
                        (e.value >= 1.0 && totalW <= 10.0) ||
                        mbAgrees ||
                        YtMusicNames.of(lat, e.key) == e.key
                    )
                }?.key
                val roma = if (lat == null && east != null) Romaji.of(east) else null
                // As YouTube Music (hl=ja, gl=JP) writes it: in Latin letters as it is ("Aimer", "L'Arc~en~Ciel"); in Japanese, "Romaji (日本語)".
                if (east != null && !blockCjk) YtMusicNames.of(lat, east)?.let { y ->
                    return@getOrPut if (!CJK.containsMatchIn(y)) y else "${lat ?: roma ?: y} ($y)".takeIf { lat != null || roma != null } ?: y
                }
                when { lat != null && east != null && !blockCjk -> "$lat ($east)"; lat != null -> lat; roma != null -> "$roma ($east)"; else -> east ?: Words.nfc(name).trim() }
            }
        }
        fun group(name: String): String = pkey(name).let { if (it.isEmpty()) "" else find(it) }
    }

    private val GRAB_BAG = Regex("(?iu)^(vari[oa]s|various( artists)?|va|musique|m[uú]sica.*|music|otros.*|other.*|misc.*|comercial|rock|pop|nueva.*|mix.*|random|singles?|descargad.*|anime|animes|anime y japonadas|openings?|endings?|openings, endings.*|colaboraciones|mp3|videojuegos?|games?|juegos?|series|películas|peliculas|movies|bso|ost|espa[ñn]ol|spanish|english|ingl[eé]s|j-?pop|k-?pop|classical|cl[aá]sica|pop( .*)?|rock( .*)?|jazz|dance|electr[oó]nica?|latino?|indie|metal|chill.*|lounge|[0-9]{2}s|años [0-9]{2}|other artists|otros artistas|varios artistas|.*fandubs?.*|.*\\ben espa[ñn]a\\b.*|.*latinas?|.*\\bmix \\d+|aquella m[uú]sica que conocimos|aquellos \\d0|ñ los éxitos del año|.*\\(studio albums\\)|best of anime)$")

    private val REMIX = Regex("(?iu)(?<!-)\\b(remix(es)?|rmx|re-?mix(ed)?|mix|mega ?mix|non-stop|trance|eurobeat|orchestra|orchestral|orgel|music ?box|meets classics|classical\\s+(?:mix|version|edition|album|collection))\\b(?!-)|ayu-?mi-?x|ayu-ro|リミックス|オルゴール")
    private val LIVE = Regex("(?i)\\blive\\b|concert|\\btour\\b|countdown|unplugged|コンサート|ライブ|ライヴ|\\ben directo\\b|en vivo|武道館|budokan|\\bdome\\b|arena")
    private val OST = Regex("(?i)soundtrack|sountrack|\\bost\\b|o\\.s\\.t|\\bbgm\\b|original score|music collection|音楽集|サウンドトラック|サントラ|交響詩|orgel|オルゴール|piano fantasia|brass|ブラス|music fantasy|image ?album|イメージアルバム|sound ?track|sound & song|合唱組曲")
    private val DRAMA = Regex("(?i)drama|ドラマ|radio|ラジオ|chapter\\.?\\s*\\d")
    private val SPANISH = Regex("(?iu)español|espanol|castellano|latino|spanish|fandub|\\bversi[oó]n espa|\\(es\\)|\\bes\\b.*\\b(op|ed|opening|ending)|^\\s*[a-z]?\\d+\\s+es\\s|[-_\\s]sp(\\.\\w{3,4})?$|\\(sp\\)")
    private val EDITION = Regex("(?i)\\s*[\\[(（]?\\s*(deluxe|super\\s+deluxe|(?:complete|expanded)\\s+(?:edition|version|ed\\.?)|extended\\s+versions?|(?:\\d+(?:st|nd|rd|th)\\s*)?anniversary\\s+(?:edition|version|ed\\.?|remaster(?:ed)?|deluxe)|edici[oó]n de lujo|special\\s+(?:edition|version|ed\\.?)|edici[oó]n especial|limited\\s+(?:edition|version|ed\\.?)|remaster(?:ed)?|bonus tracks?|from\\s+u\\.?s\\.?|target(?:\\s+exclusive)?|platinum\\s+(?:edition|version)|japan(?:ese)?\\s+(?:edition|version)|(?:the\\s+)?til\\s+dawn\\s+(?:edition|version)|disc\\s*\\d+|cd\\s*\\d+|disk\\s*\\d+|\\+\\s*cd extra|cd\\s*\\+\\s*dvd[^\\])）]*|アニメ盤|初回(?:限定|生産)盤[^\\])）]*|通常盤)\\s*(version|edition|ed\\.?)?\\s*[\\])）]?")

    /** An album without its edition or disc ("He Perdido Los Zapatos [edición De Lujo]" = "He Perdido Los Zapatos"). */
    fun albumBase(a0: String): String {
        // Normalize mixed fullwidth/ASCII brackets ("（「noAno」...CD)" → "(「noAno」...CD)") and strip genuinely unclosed trailing brackets.
        val norm = a0.replace('（', '(').replace('）', ')')
        val a = Regex("\\s*[(\\[][^)\\]]*$").replace(norm, "").ifBlank { norm }
        return clean(a.replace(EDITION, " ").replace(Regex("\\s+"), " ").trim(' ', '-', '–', '~')).ifBlank { a.trim() }
    }
    fun albumKey(a: String): String {
        // "Slayers MEGUMIX -GOLD (スレイヤーズ MEGUMIX -GOLD)" and "スレイヤーズ MEGUMIX -GOLD" are one album:
        // a name in brackets that only says the same in Japanese is left out, and a series' Japanese name read as its Latin one.
        var b = albumBase(a).replace('α', 'a').replace('Α', 'A')
        Regex("^(.*\\p{IsLatin}.*?)\\s*[(（]([^)）]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)）]*)[)）]\\s*$").find(b)?.let { b = it.groupValues[1] }
        var k = Words.plain(b)
        aliases.entries.filter { (key, _) -> key.length >= 3 && CJK.containsMatchIn(key) && k.contains(key) }.sortedByDescending { it.key.length }
            .forEach { (key, v) -> k = k.replace(key, Words.plain(v)) }
        // "BADモード" is "BAD Mode (BADモード)" seen elsewhere.
        return albumAlias[k] ?: k
    }
    /** Japanese names of albums also seen as "Latin (日本語)": the Latin name's key (set for each plan). */
    @Volatile private var albumAlias: Map<String, String> = emptyMap()

    /** "美少女戦士セーラームーン｜S 音楽集" in "Sailor Moon" → "S 音楽集". */
    private fun withoutSeries(album: String, series: String): String {
        val names = (aliases.filterValues { it == series }.keys + Words.plain(series)).filter { it.length >= 4 }
        val parts = album.split(Regex("\\s*[｜|:：~〜～\\-–]\\s*"), limit = 2)
        if (parts.size == 2 && names.any { Words.plain(parts[0]) == it } && parts[1].isNotBlank()) return parts[1].trim()
        return album
    }

    /** The series of a soundtrack ("Tsubasa Chronicle Original Soundtrack - Future Soundscape II" → "Tsubasa Chronicle"). */
    fun soundtrackSeries(album: String): String? {
        aliases.entries.filter { (it.key.length >= 5 || it.key.length >= 3 && CJK.containsMatchIn(it.key)) && Words.plain(album).contains(it.key) }.maxByOrNull { it.key.length }?.let { return it.value }
        Regex("^[「『]([^」』]{2,})[」』]").find(album.trim())?.let { return it.groupValues[1] }
        val cut = Regex("(?i)\\s*(original|soundtrack|sountrack|\\bost\\b|o\\.s\\.t|movie|music|sound|vol\\.?|drama|best|image|\\s-\\s|~|:|「|『|\\(|\\[).*$").replace(album, "").trim(' ', '.', '-')
        return cut.takeIf { it.count(Char::isLetter) >= 3 }
    }

    private fun tidyArtist(a0: String, title: String, isKnown: (String) -> Boolean): String {
        var a = a0.replace(Regex("(?iu)\\s+(lp|álbum|album)\\s*-.*$"), "").replace(Regex("(?iu)\\s+año\\s*-?\\s*\\d{4}.*$"), "").trim()
        val parts = a.split(Regex("\\s*-\\s+-?\\s*|\\s+-\\s*")).map { it.trim(' ', '-', '–') }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val rest = parts.filter { Words.plain(it) != Words.plain(title) }.ifEmpty { parts }
            a = rest.firstOrNull { isKnown(it) } ?: rest.first()
        }
        return a.trim(' ', '-', '–').ifBlank { a0 }
    }

    /**
     * What a song is kept as. The aim is AAC 320 (.m4a): MP3 320 stays MP3, AAC stays as it is
     * (an AAC 256 is flagged to replace, low priority), lossless becomes AAC 320, and Opus (YouTube's
     * .webm, not wanted as it is) becomes AAC 320 too, flagged. With [native] off (Ajustes): only
     * MP3, and lossless → MP3 320, as before 1.0.28.
     */
    object Formats {
        private val file get() = File(Store.dir, "formats.json")
        @Volatile var native: Boolean = runCatching { org.json.JSONObject(file.readText()).optBoolean("native", true) }.getOrDefault(true)
            set(v) { field = v; runCatching { file.writeText(org.json.JSONObject().put("native", v).toString()) } }
        val LIBRARY = setOf("mp3", "m4a")
        fun extOf(t: Track): String? = when {
            t.lossless -> if (native) "m4a" else "mp3"
            t.codec == "mp3" -> "mp3"
            native && t.codec == "aac" -> "m4a"
            native && t.codec == "opus" -> "m4a"
            else -> null
        }
        /** Made anew (not copied as it is): lossless, and Opus (into AAC 320). */
        fun encodes(t: Track) = t.lossless || t.codec == "opus"
    }

    /** How songs found on several albums are kept (Ajustes): see [plan]. */
    object Layout {
        private val file get() = File(Store.dir, "layout.json")
        /** Each song stored once, in the release chosen for it (originals, then singles, then compilations). */
        val byRelease: Boolean get() = compilationsFirst
        @Volatile var compilationsFirst: Boolean = runCatching { org.json.JSONObject(file.readText()).optBoolean("compilationsFirst", true) }.getOrDefault(true)
            set(v) { field = v; runCatching { file.writeText(org.json.JSONObject().put("compilationsFirst", v).toString()) } }
    }

    private val VERSION = Regex("(?iu)\\b(ver(sion)?|remix|mix|live|en directo|directo|unplugged|first take|acoustic|ac[úu]stic[oa]|extended|club mix|radio edit|instrumental|inst|edit|tv size|tv-size|off vocal|karaoke|demo|remaster(ed)?)\\b|-[a-z]-version|バージョン|ヴァージョン|リミックス|ライブ|ニュー・?テイク|new take|re-?record(?:ed|ing)|self ?cover|セルフカバー|featuring|\\bfeat\\.")

    const val OUTSIDE = "instrumental de banda sonora de serie"
    /** Beside the library (in its parent folder): the series' soundtrack instrumentals taken out of it. */
    const val OUTSIDE_FOLDER = "Bandas sonoras instrumentales"
    private val INST = Regex("(?iu)\\b(inst(rumental)?|bgm|off ?vocal|karaoke|orgel|music box version|piano version)\\b|インスト|オルゴール")
    private val SUNG = Regex("(?iu)\\b(vocal|song|tv size|tv-size|movie size|full size|ver\\.? ?vocal)\\b|歌|ソング|サイズ")

    /**
     * The kind of music of an artist with few songs, for "Otros artistas/<kind>/<artist>": from its
     * name, genre tags, words and where it was found. As few kinds as will do.
     */
    fun kindOf(tracks: List<Track>, titles: List<String>, years: List<Int>, tags: List<String> = emptyList(), artistName: String = ""): String {
        // CJK characters or seiyuu credit "(CV." in the artist's own name → JPOP directly.
        if (CJK.containsMatchIn(artistName) || Regex("(?iu)[(（]cv\\b|\\bcv[.:：]").containsMatchIn(artistName)) return "JPOP"
        // Last.fm's tags of the artist first, when it has them.
        val lt = tags.joinToString(" ")
        val top3 = tags.take(3).joinToString(" ")
        val eightiesTag = Regex("\\b80s\\b|ochenta").containsMatchIn(lt) || years.isNotEmpty() && years.average() in 1977.0..1992.0
        val nonSpanishTag = Regex("brazilian|mpb|bossa nova|german|eurodance|italian").containsMatchIn(lt)
        val mainstreamTop = Regex("\\b(pop|rock|metal|electropop|indie|dance|hip-?hop|rnb|punk|alternative)\\b").containsMatchIn(top3)
        when {
            Regex("j-?pop|j-?rock|japanese|anime|city pop|vocaloid|enka|seiyuu|anison").containsMatchIn(lt) -> return "JPOP"
            !nonSpanishTag && Regex("spanish|español|espanol|latin pop|pop latino|rock en español|movida|flamenco|rumba|copla|pop rock español").containsMatchIn(lt) -> return if (eightiesTag) "80s en España" else "Español"
            Regex("french|chanson|français|francais").containsMatchIn(lt) -> return "Francés"
            !mainstreamTop && Regex("classical|soundtrack|score|ost|composer|film music|video game music").containsMatchIn(lt) -> return "Clásica y bandas sonoras"
        }
        val genres = tracks.joinToString(" ") { it.tags["genre"].orEmpty() }.lowercase()
        val hasClearWesternTags = tags.isNotEmpty() && Regex("\\b(pop|rock|indie|electronic|british|american|uk|usa|english|hip-?hop|rnb|soul|folk|punk|metal|dance|synthpop|disco|new wave)\\b").containsMatchIn(lt)
        val text = (titles + tracks.map { it.path.substringAfterLast("/Música").substringAfterLast("/Discografías") }).joinToString(" ")
        val japanese = CJK.containsMatchIn(titles.joinToString(" ")) || (!hasClearWesternTags && (CJK.containsMatchIn(text) || Regex("(?i)j-?pop|j-?rock|anime|japan|vocaloid|enka|city ?pop").containsMatchIn(genres + " " + text)))
        // Which language its names are in: function words and endings typical of each.
        // Song titles only (an album's name can be a Spanish compilation of English songs), without credits in brackets ("(con Jorja Smith)").
        val words = Regex("[\\p{L}']+").findAll(titles.joinToString(" ") { it.replace(Regex("[(\\[（【][^)\\]）】]*[)\\]）】]"), " ") }.lowercase()).map { it.value }.toList()
        fun score(stop: Set<String>, ends: Regex, marks: Regex) = words.count { it in stop || ends.containsMatchIn(it) || marks.containsMatchIn(it) }
        val es = score(setOf("el", "la", "los", "las", "de", "del", "que", "y", "en", "mi", "tu", "te", "amor", "por", "para", "con", "un", "una", "como", "quiero", "eres", "soy", "lo", "corazón", "vida", "noche", "nada", "todo", "más", "sin"),
            Regex("(ción|dad|mente|ado|ada|ento|enta|ito|ita|oso|osa|ía|ío)$"), Regex("[ñáéíóú¿¡]"))
        val en = score(setOf("the", "you", "your", "my", "i", "i'm", "it", "is", "of", "and", "love", "don't", "we", "be", "baby", "night", "heart", "what", "with", "all", "on", "in", "to", "for", "this", "that", "me", "girl", "boy", "feel", "like"),
            Regex("(ing|ght|ness|'s|'re|'ll|n't)$"), Regex("^$"))
        val fr = score(setOf("le", "les", "du", "des", "je", "et", "pas", "mon", "ma", "toi", "moi", "est", "une", "qui", "dans", "sur", "avec", "ne", "vous", "nous", "mais", "oui", "rien", "c'est", "j'ai", "l'amour"),
            Regex("(eux|euse|ette|oir|aux)$"), Regex("[èêëàâçœù]"))
        val spanishPlace = !hasClearWesternTags && Regex("(?iu)/(español|espanol|españa|movida|80 en españa|fiesta española)/").containsMatchIn(text)
        val french = Regex("(?i)french|chanson|fran[cç]ais").containsMatchIn(genres) || fr >= 2 && fr > es && fr > en
        val spanish = (!hasClearWesternTags && Regex("(?i)español|espanol|spanish|flamenco|rumba|movida|latin pop").containsMatchIn(genres)) || (es + (if (spanishPlace) 2 else 0)) > en && es + (if (spanishPlace) 2 else 0) >= 2
        val eighties = Regex("(?iu)movida|80 en españa|ochenta").containsMatchIn(text) || years.isNotEmpty() && years.average() in 1977.0..1992.0
        val classical = !hasClearWesternTags && Regex("(?i)classical|clásica|clasica|soundtrack|score|bso|banda sonora").containsMatchIn(genres)
        return when {
            japanese -> "JPOP"
            !hasClearWesternTags && Regex("(?iu)movida|80 en españa").containsMatchIn(text) && es >= en -> "80s en España"
            spanish && eighties -> "80s en España"
            spanish -> "Español"
            french -> "Francés"
            classical -> "Clásica y bandas sonoras"
            else -> "Inglés"
        }
    }

    /** Names kept in Latin letters only, without their Japanese spelling after them. */
    private val LATIN_ONLY = setOf("larcenciel", "move")

    /** One artist under several names (the first is the one shown). */
    private val SAME_PEOPLE = listOf(
        listOf("La Quinta Estación", "La 5a Estación", "La Quinta Estacion", "La Quinta Estacion A Dueto"),
        listOf("Glee Cast", "Glee"),
        listOf("Soraya", "Soraya Arnelas"),
        listOf("Donny Montell", "Donatas Montvydas"),
        listOf("m.o.v.e", "Move"),
        listOf("Linkin Park", "Linkin park", "Linkin' Park Hybrid Theory"),
        listOf("L'Arc~en~Ciel", "彩虹L'Arc～en～Ciel", "ラルク アン シエル"),
        listOf("Elisa Petrikowski", "Elisa Petrikowski c"),
        listOf("Bhangra Knights", "Bhangra Knights vs. Husan"),
        listOf("Miho Morikawa", "森川美穂", "Morikawamiho", "Morikawa Miho"),
        listOf("Miho Fukuhara", "福原美穂"),
        listOf("Hitomi Kuroishi", "黒石ひとみ"),
    )
    /** Different people whose names get confused. */
    private val OTHER_PEOPLE = listOf("Miho Morikawa" to "福原美穂", "森川美穂" to "福原美穂", "Hitomi" to "Hitomi Kuroishi", "hitomi" to "黒石ひとみ")

    /** Series filed as if they were singers ("Death note", "Shaaman king", "WOLF'S RAIN ED"): their name as a series. */
    private val ALBUM_OWNER = mapOf("angelfeathervoice" to "Hitomi Kuroishi")
    private val SERIES_AS_ARTIST = listOf(
        "Death Note" to "death ?note", "Yu-Gi-Oh!" to "yu.?gi.?oh", "Tsubasa Chronicle" to "tsubasa chronicle", "Fushigi Yuugi" to "fushigi yu+gi",
        "Tengen Toppa Gurren Lagann" to "(tengen toppa )?gurren lagann", "Wolf's Rain" to "wolf.?s rain( ed| op)?", "Shaman King" to "sha+man king",
        "Saber Marionette J" to "saber marionettes? j", "Kareshi Kanojo no Jijou" to "kareshi kanojo no jijou", "Ghost in the Shell" to "gits( innocence)?|ghost in the shell.*",
        "Solatorobo" to "solatorobo", "YAT Anshin! Uchuu Ryokou" to "yat anshin.*", "Chrono Crusade" to "chr?ono crusade", "Death Note" to "death note",
        "Serial Experiments Lain" to "serial experiment(al|s) lain",
    ).map { (n, r) -> n to Regex("(?i)^($r)$") }
    private fun seriesOfArtist(a: String) = SERIES_AS_ARTIST.firstOrNull { it.second.matches(a.trim()) }?.first

    /** NANA's singers as the series' characters: their NANA songs go to the series, the rest to the singer. */
    private val NANA_CREDIT = Regex("(?i)inspi'?\\s*(nana|reira)|black stones|trapnest|nana starring")
    private val NANA_SINGER = Regex("(?i)anna tsuchiya|土屋アンナ|olivia|mika nakashima|中島美嘉")
    private val NANA_SONGS = Regex("(?i)glamorous sky|hitoiro|一色|a little pain|wish|rose|lucky star|zutto|starless night|kuroi namida|黒い涙|winter sleep|stand by me|recorded butterfly|ストロベリー")
    /** Songs of a series' characters. */
    private val CHARACTER = Regex("(?iu)character songs?|キャラクター ?ソング|キャラソン|image songs?|イメージソング|\\bchara(cter)? ?(cd|song)|\\bcv[.:：]|\\(cv\\b")

    /** "Believe (Opening 2)", "Kokoro no Chizu-OP", "OP-Op2-Believe", "(Ending 16) - Dear Friends": the marks of an opening or ending. */
    /** Names of channels, labels and placeholders, not of anyone who sings. */
    private val CHANNEL = Regex("(?iu)^(j-?pop|opening theme.*|various artists.*|banda sonora|radio song|realaudio|creacionesjuanmi|cover nation|impact vocals|multi interpr.*|nuevo interprete|late motiv.*|听动漫|群星|tv (ver|size).*|.*\\b(records|music channel|channel|vevo|- topic)|.*\\bexitos music|omegamusic|sonmay.*|nbcuniversal.*)$|^[^(（]*[)）]$")
    private val THEME_MARK = Regex("(?i)[(\\[]\\s*(opening|ending|op|ed)\\s*\\d*\\s*[)\\]]|^\\s*(op|ed)\\s*-\\s*(op|ed)\\s*\\d+\\s*-|-\\s*(op|ed)\\s*$|^\\s*-\\s*")
    private val THEME_BAG = Regex("(?iu)\\b(openings?|endings?|op\\s*(&|y|and|/)\\s*ed|themes?|tv size|主題歌|テーマ)\\b")
    private val YT_JUNK = Regex("(?iu)[(\\[{]\\s*(official\\s*(music\\s*)?(video|audio|videoclip|lyric video)|lyrics?|hq( lyrics)?|hd|4k|1080p|720p|320\\s?kbps|audio|video|mv|pv|full( song| ver\\.?| version)?( \\(with lyrics\\))?|with lyrics|letra|subtitulad[oa]|sub español|español sub|visualizer|explicit|original song|mp3juices\\.com|[\\w.-]+\\.(com|net|org))\\s*[)\\]}]|\\blyrics in description!?|\\bofficial video\\b|\\bfull song\\b|\\[mp3juices\\.com\\]|\\b4k\\b")

    private val COMPILATION = Regex("(?iu)\\b(best|greatest|hits|singles|collection|anthology|complete|the very)\\b|ベスト|コレクション|シングルズ")

    private val DISC_DIR = Regex("(?i)^\\s*(?:disc|disk|cd|disco|vol\\.?)\\s*[_-]?\\s*0?(\\d{1,2})(?:\\b|\\s*[-–:])")

    /** Always excluded (karaoke / off-vocal / TV-size instrumental). Studio album instrumentals are kept (user decision). */
    private val KARAOKE = Regex("(?i)karaoke|カラオケ|off[ -]?vocal|without[ -]?vocal|less[ -]?vocal|backing track|オリジナル・カラオケ|tv[ -]?size.*inst")
    private val INST_TITLE = Regex("(?i)\\binstrumental\\b|inst\\.\\)|\\(inst\\)|\\[inst\\]")
    private val GAME_HINT = Regex("(?i)\\b(guitar hero|street fighter|solatorobo|harvest moon|harvest|video ?game|vgm|playstation|nintendo|capcom|square ?enix|cyberconnect2|arcade)\\b|/videojuegos?/|/games?/")
    private val NON_ANIME_SERIES = Regex("(?i)^\\s*(trance|anna|club|dance|eurobeat|house|techno)\\s*$")

    /**
     * `<library>/.bitchord-library/quarantine.json`: folders and files that never enter the library
     * (the clips in "Música de los vídeos OPS", and their copies another tool renamed and spread
     * over other folders — found by their audio, see LIBRARY_PLAN.md). Sources stay untouched.
     */
    class Quarantine(val folders: List<String>, val files: Map<String, String>) {
        fun reason(path: String): String? {
            val p = Words.nfc(path)
            files[p]?.let { return "cuarentena: $it" }
            if (ALWAYS.containsMatchIn(p)) return "cuarentena: clip de vídeo (Música de los vídeos OPS)"
            if (CLIP_NAME.containsMatchIn(p.substringAfterLast('/'))) return "cuarentena: clip de vídeo (${p.substringAfterLast('/')})"
            return if (folders.any { p.startsWith("$it/") }) "cuarentena: clip de vídeo (${folders.first { p.startsWith("$it/") }.substringAfterLast('/')})" else null
        }
        companion object {
            /** The folders never copied whatever the quarantine file says: the videos' music, already sorted elsewhere. */
            private val ALWAYS = Regex("(?i)/música de los vídeos ops(/|$)")
            /** The names those clips were given ("a1 jap Pokémon", "a3 es one pieceend8", "b2 jap …"). */
            val CLIP_NAME = Regex("(?i)^[a-c]\\d{1,2}\\s+(jap|es|ita|eng|jeng|esp)\\b")

            fun load(root: String) = runCatching {
                // The library's copy, else the one kept on this Mac (pCloud may not answer at that moment).
                val lib = File(root, ".bitchord-library/quarantine.json")
                val local = File(Store.dir, "quarantine.json")
                val text = runCatching { lib.readText().also { t -> runCatching { local.writeText(t) } } }.getOrNull() ?: local.readText()
                val o = JSONObject(text)
                val fo = o.optJSONArray("folders")?.let { a -> (0 until a.length()).flatMap { val p = Words.nfc(a.getString(it)).trimEnd('/'); listOf(p, Words.nfc(Sources.moved(p))) }.distinct() }.orEmpty()
                val fi = o.optJSONObject("files")?.let { f -> f.keys().asSequence().flatMap { k -> listOf(Words.nfc(k) to f.getString(k), Words.nfc(Sources.moved(k)) to f.getString(k)) }.toMap() }.orEmpty()
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
        val nonQuarantined = allTracks.filter { t -> quarantine.reason(t.path)?.let { skipped += t to it; false } ?: true }
        // Dedicated One Piece discography organizer when `One Piece OST` is present in any source.
        val opRoot = OnePiece.sourceRoot(sources)
        val opRootNfc = opRoot?.let { Words.nfc(it.path).trimEnd('/') + "/" }
        fun isOnePieceTrack(t: Track): Boolean {
            if (opRootNfc == null) return false
            val p = Words.nfc(t.path)
            if (p.startsWith(opRootNfc)) return true
            if (Regex("(?iu)/(one piece|ワンピース)(/|\\s*-|\\s+ost)").containsMatchIn(p)) return true
            if (p.contains("/Music/BitChord/") && Regex("(?iu)one piece|ワンピース").containsMatchIn(p + " " + t.album)) return true
            return false
        }
        val (opTracks, tracks) = if (opRoot != null) nonQuarantined.partition(::isOnePieceTrack) else (emptyList<Track>() to nonQuarantined)
        val people = People()
        val bySource = sources.associateWith { s -> tracks.filter { it.path.startsWith(s.path + "/") } }

        // 1. Tags as they are (repaired), and who is named in them.
        fun tag(t: Track, k: String) = Words.fix(when (k) { "album_artist" -> t.albumArtist; else -> t.tags[k].orEmpty() })
        // Detect real bands/duos containing &, , or / from album_artist tags and top folders (e.g. "Earth, Wind & Fire", "MYTH & ROID", "Simon & Garfunkel").
        run {
            val bandCounts = HashMap<String, Int>()
            tracks.forEach { t ->
                val aa = tag(t, "album_artist").replace(Regex("(?i)\\s+(feat|ft|featuring|with|con)\\.?\\s.*$"), "").trim()
                if (aa.any { it in ",&/" } && !VARIOUS.matches(aa) && !Words.noArtist(aa)) bandCounts.merge(Words.plain(aa), 1, Int::plus)
            }
            for ((s, mine) in bySource) if (s.mode == "all") mine.map { it.path.removePrefix(s.path + "/").substringBefore('/', "") }.distinct().forEach { top ->
                val f = folderName(Words.fix(top))
                if (f.any { it in ",&/" } && !GRAB_BAG.matches(f) && !Words.noArtist(f)) bandCounts.merge(Words.plain(f), 3, Int::plus)
            }
            wholeBands = bandCounts.filterValues { it >= 2 }.keys
        }
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
            val allDirs = rel.split('/').dropLast(1).map { Words.fix(it) }
            // "ayu-mi-x/DISC 2 (Acoustic Orchestra Side)": the album is the folder above, the disc its number.
            val discDir = allDirs.lastOrNull()?.let { DISC_DIR.find(it) }
            val dirs = if (discDir != null && allDirs.size >= 2) allDirs.dropLast(1) else allDirs
            val fn = Words.fromName(Words.fix(File(t.path).name))
            var artist = tag(t, "artist").takeUnless { Words.noArtist(it) }.orEmpty()
            val aaRaw = tag(t, "album_artist")
            val various = VARIOUS.matches(aaRaw.trim()) || Words.noArtist(aaRaw) && aaRaw.isNotBlank()
            var albumArtist = aaRaw.takeUnless { Words.noArtist(it) || Words.noArtist(firstArtist(it)) || VARIOUS.matches(it.trim()) }.orEmpty()
            var album = tag(t, "album").takeUnless { Words.noAlbum(it) }.orEmpty()
            val folderWords = dirs.map { Words.plain(it) }.toSet()
            var title = tag(t, "title").takeUnless { Words.noTitle(it) }?.let { if (Regex("^\\s*\\d{1,3}\\s*([.)]\\s*|-\\s+)\\S").containsMatchIn(it)) Words.dropNumber(it) else it }.orEmpty()
            if (title.isEmpty()) {
                // A year alone is no title nor artist ("02. Pista 2 - … - 2001").
                val parts = fn.parts.filter { (Words.plain(it) !in folderWords || fn.parts.size == 1) && !Regex("^\\(?(19|20)\\d{2}\\)?$").matches(it) }.ifEmpty { fn.parts }
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
            // "C.C.Catch - Soul survivor Lp-Like a hurricane Año-1987", "Green Day- -Boulevard Of Broken Dreams-",
            // "Gotye - Somebody That I Used To Know (feat. Kimbra)": a whole file name in the artist tag → the artist.
            artist = tidyArtist(artist, title, ::isKnown)
            // A channel's or label's name ("NBCUniversal Music Channel", "J-Pop", "TV ver.-)") names no one.
            if (CHANNEL.matches(firstArtist(artist).trim())) artist = ""
            if (CHANNEL.matches(albumArtist.trim())) albumArtist = ""
            albumArtist = tidyArtist(albumArtist, title, ::isKnown)
            // A title cut at 30 letters (old ID3v1 tags: "Nena Daconte-En que estrella e"): the file's name has all of it.
            val stem = Words.dropNumber(Words.nfc(Words.fix(File(t.path).nameWithoutExtension))).trim()
            if (title.length in 28..30 && stem.length > title.length && stem.startsWith(title, ignoreCase = true)) title = stem
            // "Nena Daconte-En que estrella estara": the artist's name glued in front of the title.
            if (artist.isNotEmpty()) Regex("^" + Regex.escape(firstArtist(artist)) + "\\s*[-–]\\s*", RegexOption.IGNORE_CASE).find(title)?.let { m -> if (m.range.last + 1 < title.length) title = title.substring(m.range.last + 1).trim() }
            if (artist.isEmpty()) dirs.asReversed().firstOrNull { isKnown(it) }?.let { artist = folderName(it) }
            val (fYear, fAlbum) = dirs.lastOrNull()?.let { Words.albumFromFolder(it) } ?: (null to "")
            if (album.isEmpty() && dirs.isNotEmpty()) {
                val parent = dirs.last()
                if (!isKnown(parent) && !GRAB_BAG.matches(parent.trim()) && !Words.noAlbum(fAlbum) && Words.plain(fAlbum) != Words.plain(artist)) album = fAlbum
            }
            // Repair a cut-off leading letter when an album title starts with a known parent artist's name minus its first char ("aaya Sakamoto ..." inside "Maaya Sakamoto/").
            if (album.isNotEmpty() && dirs.size >= 2) {
                for (pDir in dirs.dropLast(1)) {
                    val fnP = folderName(pDir)
                    if (fnP.length >= 6 && isKnown(fnP) && album.startsWith(fnP.drop(1), ignoreCase = true) && !album.startsWith(fnP, ignoreCase = true)) {
                        album = fnP.first() + album
                        break
                    }
                }
            }
            // "1. Title - Artist - Album.webm" (the phone's downloads, no tags), "NN - Title - Artist - Album - Year.mp3": the album from the name.
            if (album.isEmpty() && tag(t, "title").isBlank() && fn.parts.size >= 3) fn.parts.drop(2).lastOrNull { p ->
                !Regex("^\\(?(19|20)\\d{2}\\)?$").matches(p) && Words.plain(p) != Words.plain(title) && !Words.noAlbum(p) && !GRAB_BAG.matches(p.trim())
            }?.let { album = it }
            val year = t.year ?: fYear ?: dirs.asReversed().firstNotNullOfOrNull { Regex("^((19|20)\\d{2})\\b").find(it)?.groupValues?.get(1) }
            // Nothing (or nothing useful) in its tags: what AcoustID heard it is, when it agrees with existing hints (or none exist).
            AcoustId.of(t.path)?.let { h ->
                val video = YT_JUNK.containsMatchIn(File(t.path).name) || YT_JUNK.containsMatchIn(tag(t, "title"))
                val missing = tag(t, "title").isBlank() || tag(t, "artist").isBlank() || artist.isEmpty() || Words.noArtist(tag(t, "artist"))
                val genericAlb = Regex("(?i)\\b(various artists|promo only|mastermix|top\\s*100|greatest hits|modern rock|now that'?s what i call)\\b")
                val safeAlbum = h.album?.takeUnless { genericAlb.containsMatchIn(it) }
                // Require at least one significant word overlap with existing artist/title/filename when non-empty hints exist (blocks misidentifications).
                val existingHints = listOf(title, artist, File(t.path).nameWithoutExtension).joinToString(" ")
                val hintTokens = Regex("[\\p{L}\\p{N}]{3,}").findAll(Words.nfc(existingHints).lowercase()).map { Words.plain(it.value) }.filter { it.length >= 3 }.toSet()
                val hitTokens = Regex("[\\p{L}\\p{N}]{3,}").findAll(Words.nfc(h.title + " " + h.artist).lowercase()).map { Words.plain(it.value) }.filter { it.length >= 3 }.toSet()
                val trulyNameless = Words.noArtist(artist) && Words.noTitle(title)
                val agrees = trulyNameless || hintTokens.isEmpty() || (hintTokens intersect hitTokens).isNotEmpty()
                if ((video || missing) && agrees) {
                    title = h.title; artist = h.artist
                    if (album.isEmpty()) safeAlbum?.let { album = it }
                } else if (album.isEmpty() && Words.plain(h.title) == Words.plain(title) && agrees) safeAlbum?.let { album = it }
            }
            ids[t.path] = Id(title.ifBlank { File(t.path).nameWithoutExtension }, artist, albumArtist, various, album, year, t.trackNo ?: fn.no, t.discNo ?: fn.disc ?: discDir?.groupValues?.get(1)?.toIntOrNull())
        }
        // Subfolder artist consistency: if >= 80% of tracks in a subfolder share a known artist that contradicts a stale grandparent folder / album_artist (e.g. Beatles inside Monica Naranjo/), trust the tracks' artist.
        tracks.filter { ids[it.path] != null }.groupBy { File(it.path).parent }.values.filter { it.size >= 3 }.forEach { list ->
            val dom = list.map { firstArtist(ids[it.path]!!.artist) }.filter { it.isNotBlank() && isKnown(it) }
                .groupingBy { pkey(it) }.eachCount().maxByOrNull { it.value } ?: return@forEach
            if (dom.value < list.size * 0.8) return@forEach
            val domName = list.map { firstArtist(ids[it.path]!!.artist) }.first { pkey(it) == dom.key }
            list.forEach { t ->
                val cur = ids[t.path]!!
                if (cur.albumArtist.isNotBlank() && pkey(firstArtist(cur.albumArtist)) != dom.key) {
                    ids[t.path] = cur.copy(albumArtist = domName)
                }
            }
        }
        // Folders holding one album only: their picture is that album's (a folder of loose songs' isn't anyone's).
        val singleAlbumDirs = tracks.filter { ids[it.path] != null }.groupBy { File(it.path).parent }
            .filter { (_, l) -> l.map { albumKey(ids[it.path]!!.album) }.toSet().let { a -> a.size == 1 && a.first().isNotEmpty() } }.keys
        // A name only ever on one album, and that album's own name ("Tales of Symphonia" by "Tales of
        // Symphonia"), names the album or its series, not a person.
        val albumsOf = HashMap<String, MutableSet<String>>()
        ids.values.forEach { id -> listOf(id.artist, id.albumArtist).filter { it.isNotBlank() }.forEach { a -> albumsOf.getOrPut(pkey(firstArtist(a))) { HashSet() }.add(Words.plain(id.album)) } }
        fun onlyItsAlbum(a: String) = a.isNotBlank() && albumsOf[pkey(firstArtist(a))]?.let { it.size == 1 && it.first() == Words.plain(firstArtist(a)) } == true
        ids.replaceAll { _, id ->
            var x = id
            if (onlyItsAlbum(x.albumArtist)) x = x.copy(albumArtist = "", various = true)
            if (onlyItsAlbum(x.artist)) x = x.copy(artist = x.albumArtist)
            // "Slayers; Megumi Hayashibara", "Slayers-Megumi Hayashibara": a series' name in front of the singer → the singer;
            // "Slayers; Next" (no singer after it) → no one.
            fun unseries(a: String): String {
                val m = Regex("^(.+?)\\s*[;,\\-–:/]\\s*(.+)$").find(a.trim()) ?: Regex("^(\\S+(?:\\s\\S+)?)\\s+(.+)$").find(a.trim()) ?: return a
                if (aliases[Words.plain(m.groupValues[1])] == null) return a
                val rest = m.groupValues[2].trim()
                return if (isKnown(rest) || isKnown(firstArtist(rest))) rest else ""
            }
            x = x.copy(artist = unseries(x.artist), albumArtist = unseries(x.albumArtist))
            // An album known to be someone else's than its short tag says ("Hitomi" on Hitomi Kuroishi's "Angel Feather Voice").
            ALBUM_OWNER.entries.firstOrNull { (k, _) -> Words.plain(x.album).contains(k) }?.let { (_, who) ->
                if (Words.plain(who).startsWith(Words.plain(firstArtist(x.artist.ifBlank { x.albumArtist })))) x = x.copy(artist = who, albumArtist = who)
            }
            // Names of YouTube videos: "(Official Video)", "[HQ lyrics]", "[MP3JUICES.COM]"… off; "Katy Perry (Lyrics) - I Kissed A Girl"
            // or "Dua Lipa - Levitating" under a channel's name: the singer named in the title is the artist.
            val cleanT = YT_JUNK.replace(x.title, " ").replace(Regex("\\s{2,}"), " ").trim(' ', '-', '–', '.', '!')
            if (cleanT.isNotBlank() && cleanT != x.title) x = x.copy(title = cleanT)
            Regex("^(.+?)\\s+[-–]\\s+(.+)$").find(x.title)?.let { m ->
                val (l, r) = m.destructured
                when {
                    isKnown(l) && !isKnown(x.artist) -> x = x.copy(artist = l.trim(), title = r.trim())
                    isKnown(r) && !isKnown(l) && (Words.plain(x.artist) == Words.plain(l) || !isKnown(x.artist)) -> x = x.copy(artist = r.trim(), title = l.trim())
                }
            }
            if (x.album.isNotBlank() && YT_JUNK.containsMatchIn(x.album)) x = x.copy(album = YT_JUNK.replace(x.album, " ").trim())
            // A title that is a whole file name of an older renaming ("Dont Be Discouraged (Ending) - Slayers-Megumi Hayashibara - Slayers Try - 2002"): its first part.
            x.title.split(Regex("\\s+-\\s+")).takeIf { it.size >= 3 && Regex("^(19|20)\\d{2}$").matches(it.last().trim()) }?.let { x = x.copy(title = it.first().trim()) }
            // "Just a Little Walk on the Moon - Carly Rae Jepsen - Day and Night": what follows the title is the artist and album again.
            x.title.split(Regex("\\s+-\\s+")).takeIf { it.size >= 2 }?.let { p ->
                val known = setOf(Words.plain(x.artist), Words.plain(firstArtist(x.artist)), Words.plain(x.album), Words.plain(x.albumArtist), x.year.orEmpty()).filter { it.isNotEmpty() }
                if (p.drop(1).all { Words.plain(it) in known || Regex("^(19|20)\\d{2}$").matches(it.trim()) }) x = x.copy(title = p.first().trim())
            }
            // "01 Give a Reason" on track 1: the number is not part of the name.
            Regex("^0?(\\d{1,2})\\s+(?=\\S)").find(x.title)?.let { m -> if (m.groupValues[1].toIntOrNull() == x.no && x.title.length > m.value.length + 2) x = x.copy(title = x.title.substring(m.value.length)) }
            x
        }
        ids.values.forEach { people.see(firstArtist(it.artist), 0.5) }
        albumAlias = emptyMap()
        albumAlias = ids.values.mapNotNull { id -> Regex("^(.*\\p{IsLatin}.*?)\\s*[(（]([^)）]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)）]*)[)）]\\s*$").find(albumBase(id.album)) }
            .associate { m -> Words.plain(m.groupValues[2]) to albumKey(m.groupValues[1]) }.filter { (j, l) -> j.isNotEmpty() && l.isNotEmpty() && j != l }

        fun shown(n: String) = aliases[Words.plain(n)] ?: people.shown(n)
        fun gid(n: String) = people.group(firstArtist(n))
        fun ownerOf(id: Id) = firstArtist(id.albumArtist.ifBlank { id.artist })

        // 4. Collections by folder: a top folder of many artists whose songs name it, or the
        // folders inside a container of them ("Anime/Naruto").
        fun isCollection(name: String, list: List<Track>, below: (Track) -> String): Boolean {
            if (list.size < 3 || GRAB_BAG.matches(name.trim()) || Words.noAlbum(name)) return false
            // A folder named after a person is that person's ("Yuki Kajiura/See Saw/…"), unless it's a known series alias (e.g. "Anime/Beck").
            val isKnownSeries = aliases[Words.plain(name)] != null
            if (!isKnownSeries && isKnown(name) && name.isNotBlank()) return false
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
            .values.forEach { all ->
                // A dub or cover in Spanish has the original's length: never a reason to join two names.
                val same = all.filter { !SPANISH.containsMatchIn(it.path + " " + ids[it.path]!!.title + " " + ids[it.path]!!.album) }
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
        OTHER_PEOPLE.forEach { (a, b) -> people.apart += setOf(pkey(a), pkey(b)) }
        // Only pair (artist, album_artist) as two scripts of the same person when that album_artist does not host multiple distinct artists (guards circles/labels like VAGRANCY).
        val aaDistinctArtists = HashMap<String, MutableSet<String>>()
        ids.values.forEach { id ->
            val a = id.artist.trim(); val b = id.albumArtist.trim()
            if (a.isNotBlank() && b.isNotBlank()) aaDistinctArtists.getOrPut(pkey(b)) { HashSet() }.add(pkey(firstArtist(a)))
        }
        val sameTrack = HashMap<Pair<String, String>, Int>()
        ids.values.forEach { id ->
            val a = id.artist.trim(); val b = id.albumArtist.trim()
            if (a.isBlank() || b.isBlank() || Regex("[,&、;/×:]|\\s(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE).containsMatchIn(a + " " + b)) return@forEach
            if ((aaDistinctArtists[pkey(b)]?.size ?: 0) >= 2) return@forEach
            val (lat, east) = when { latin(a) && !latin(b) -> a to b; latin(b) && !latin(a) -> b to a; else -> return@forEach }
            sameTrack.merge(pkey(east) to pkey(lat), 1, Int::plus)
        }
        sameTrack.filter { it.value >= 2 }.forEach { (k, v) -> votes.merge(k, v, Int::plus) }
        // Names MusicBrainz gave in Latin letters (奥井雅美 → Masami Okui): the same person as that name, if it is here too.
        people.variants.values.flatMap { it.keys }.filter { CJK.containsMatchIn(it) }.forEach { east ->
            Romaji.of(east)?.let { r -> if (people.variants.containsKey(pkey(r))) votes.merge(pkey(east) to pkey(r), 5, Int::plus) }
        }
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
        // Names fixed by hand: one artist under several names; sets Latin spelling while letting YtMusicNames apply Japanese script.
        for (list in SAME_PEOPLE) {
            val keys = list.map { pkey(it) }.filter { people.parent.containsKey(it) }
            if (keys.isEmpty()) continue
            keys.drop(1).forEach { people.union(keys.first(), it) }
            people.forcedLatin[people.find(keys.first())] = list.first()
        }


        // 5. Where each copy belongs: rules, a collection, or its artist.
        val cands = ArrayList<Cand>()
        for ((s, mine) in bySource) for (t in mine) {
            val id = ids[t.path] ?: continue
            if (Formats.extOf(t) == null) { skipped += t to "formato ${t.codec ?: "?"} (no se copia)"; continue }
            // A concert or programme on video (an hour of it) is no song: left out; a music video's sound is kept.
            if (File(t.path).extension.lowercase() in setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "flv") && (t.seconds ?: 0.0) > 15 * 60) { skipped += t to "vídeo de concierto o programa (no se copia)"; continue }
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
            .filter { it.first.length >= 5 && !GRAB_BAG.matches(it.second) && it.first !in EXACT_ONLY }.sortedByDescending { it.first.length }
        val exactCollection = collectionKeys.toMap()
        val ownAlbum = cands.groupBy { gid(ownerOf(it.id)) to albumKey(it.id.album) }.mapValues { (_, l) -> l.map { Words.plain(it.id.title) }.toSet().size }
        val ownerTotal = cands.groupBy { gid(ownerOf(it.id)) }.mapValues { (_, l) -> l.map { Words.plain(it.id.title) }.toSet().size }
        fun isOwnAlbum(c: Cand): Boolean {
            val owner = ownerOf(c.id)
            if (owner.isBlank() || c.id.various || c.id.album.isBlank()) return false
            val n = ownAlbum[gid(owner) to albumKey(c.id.album)] ?: 0
            return n >= 4 || n >= 2 && (c.id.albumArtist.isBlank() || gid(c.id.albumArtist) == gid(owner)) && (ownerTotal[gid(owner)] ?: 0) >= 30 && !OST.containsMatchIn(c.id.album)
        }
        val tributeHonorees = cands.filter { it.homeKind == 1 }.map { ownerOf(it.id) }.filter { it.isNotBlank() }
            .distinctBy { gid(it) }.filter { (ownerTotal[gid(it)] ?: 0) >= 10 && Words.plain(it).length >= 6 }
            .map { Words.plain(it) to it }
        val placedCands0 = cands.map { c ->
            if (c.homeKind == 2) {
                // Inside a series folder, if the album tag is literally just the series name ("BECK") while the file sits in a named single/album subfolder ("Hit in the USA"), use that subfolder name.
                val serPlain = Words.plain(c.home.substringBefore('/'))
                if (Words.plain(c.id.album) == serPlain) {
                    val pDir = Words.fix(File(c.track.path).parentFile?.name.orEmpty())
                    val pAlb = Words.albumFromFolder(pDir).second.ifBlank { pDir }
                    if (pAlb.isNotBlank() && !DISC_DIR.containsMatchIn(pDir) && !GRAB_BAG.matches(pDir.trim()) &&
                        Words.plain(pAlb) != serPlain && Words.plain(pAlb) != Words.plain(c.id.artist) && !isKnown(pDir)) {
                        return@map c.copy(id = c.id.copy(album = pAlb))
                    }
                }
                return@map c
            }
            if (c.homeKind != 1) return@map c
            // Tribute album to a known artist: keep guest singers' tracks inside the honoree's tribute album (e.g. Akino Arai on Maaya Sakamoto's REQUEST).
            if (Regex("(?i)\\btribute\\b").containsMatchIn(c.id.album)) {
                val ap = Words.plain(c.id.album)
                tributeHonorees.firstOrNull { (k, _) -> ap.contains(k) }?.let { (_, honoree) ->
                    return@map c.copy(id = c.id.copy(albumArtist = honoree))
                }
            }
            // On an album of one of a rules source's people but sung by someone else (ALI PROJECT on a
            // Yuki Kajiura compilation): the singer's — except on soundtracks (Noir OST I stays in Yuki Kajiura/Bandas sonoras/Noir).
            if (c.id.albumArtist.isNotBlank() && absorbedHome(c.id.albumArtist) != null && c.id.artist.isNotBlank() &&
                gid(c.id.artist) != gid(c.id.albumArtist) && absorbedHome(firstArtist(c.id.artist)) == null && !OST.containsMatchIn(c.id.album)) return@map c.copy(id = c.id.copy(albumArtist = ""))
            val owner = ownerOf(c.id)
            // "Operación triunfo 1a ed", "Operación triunfo 1er disco": a collection's name with an edition.
            collectionKeys.firstOrNull { it.first.length >= 8 && Words.plain(owner).startsWith(it.first) && Words.plain(owner) != it.first }?.let { return@map c.copy(home = it.second, homeKind = 2) }
            exactCollection[Words.plain(owner)]?.let { return@map c.copy(home = it, homeKind = 2, id = c.id.copy(artist = if (Words.plain(c.id.artist) == Words.plain(owner)) "" else c.id.artist)) }
            val a = Words.plain(c.id.album)
            val byAlbum = if (a.isEmpty()) null else collectionKeys.firstOrNull { a.contains(it.first) }
            if (byAlbum != null) {
                // An album of a big artist's own (its album artist is them: "スレイヤーズ MEGUMIX -SILVER" by Megumi Hayashibara) stays theirs.
                val own = isOwnAlbum(c)
                return@map if (own) c else c.copy(home = byAlbum.second, homeKind = 2)
            }
            if (owner.isBlank()) {
                // No one's song on a named album (a show's or a game's): that album is its collection.
                if (c.id.album.isNotBlank() && !GRAB_BAG.matches(c.id.album.trim()) && !GRAB_BAG.matches(collectionName(c.id.album).trim()) &&
                    !Regex("(?iu)\\b(mix|hits|exitos|éxitos|recopilatorio|compilation|best of|lo mejor)\\b").containsMatchIn(c.id.album)) return@map c.copy(home = collectionName(c.id.album), homeKind = 2)
                val text = Words.plain(c.id.title + " " + File(c.track.path).nameWithoutExtension)
                collectionKeys.firstOrNull { text.contains(it.first) }?.let { return@map c.copy(home = it.second, homeKind = 2) }
            }
            c
        }
        // Inside a series, separate single-artist singles whose album tag was lazily set to the series name (e.g. meister's "above the clouds" tagged album="BECK" 2004 beside the 2005 OST).
        val seriesSingleRename = HashMap<Triple<String, String, String>, String>() // (home, artistGid, year) -> track 1 title
        placedCands0.filter { it.homeKind == 2 && Words.plain(it.id.album) == Words.plain(it.home.substringBefore('/')) }
            .groupBy { it.home.lowercase() }.forEach { (h, list) ->
                val mainYear = list.mapNotNull { it.id.year }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                val distinctTitles = list.map { Words.plain(it.id.title) }.toSet().size
                if (distinctTitles < 10) return@forEach
                list.filter { it.id.albumArtist.isNotBlank() && !it.id.various && it.id.year != null && it.id.year != mainYear &&
                    Words.plain(it.id.albumArtist) == Words.plain(it.id.artist) && !Regex("(?i)^ost$").matches(it.id.artist.trim()) }
                    .groupBy { gid(it.id.albumArtist) to it.id.year!! }.forEach { (ay, sub) ->
                        val uniq = sub.distinctBy { Words.plain(it.id.title) }
                        if (uniq.size in 2..5) {
                            val t1 = uniq.firstOrNull { it.id.no == 1 }?.id?.title
                            if (!t1.isNullOrBlank()) seriesSingleRename[Triple(h, ay.first, ay.second)] = t1
                        }
                    }
            }
        val placedCands = placedCands0.map { c ->
            if (c.homeKind == 2 && Words.plain(c.id.album) == Words.plain(c.home.substringBefore('/')) && c.id.year != null && c.id.albumArtist.isNotBlank()) {
                seriesSingleRename[Triple(c.home.lowercase(), gid(c.id.albumArtist), c.id.year)]?.let { t1 ->
                    return@map c.copy(id = c.id.copy(album = t1))
                }
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

        // 8. One recording, one place: keep distinct versions (live, first take, acoustic, extended, remix, re-recording) separate,
        // and cluster identical recordings continuously within 3.5 s (never splitting across multiples of 3 s).
        fun baseKey(c: Cand): String {
            val title = Words.plain(c.id.title.replace(Regex("[\\[(（【].*?[\\])）】]"), "")).ifEmpty { Words.plain(c.id.title) }
            val rawWho = gid(c.id.artist).ifEmpty { "?" }
            // Align vocalist vs subgroup credits inside absorbed projects (e.g. See-Saw vs Chiaki Ishikawa on the same track).
            val who = if (c.homeKind == 3 && c.home.contains('/')) Words.plain(c.home) else rawWho
            val v = versionTag(c.id.title)
            return if (v.isEmpty()) "$title|$who" else "$title|$who|$v"
        }
        val byKey = LinkedHashMap<String, List<Cand>>()
        val candKey = HashMap<Cand, String>()
        homes.groupBy(::baseKey).forEach { (bk, group) ->
            val sorted = group.sortedBy { it.track.seconds ?: 0.0 }
            val clusters = ArrayList<MutableList<Cand>>()
            for (c in sorted) {
                val s = c.track.seconds
                val last = clusters.lastOrNull()
                val lastS = last?.lastOrNull()?.track?.seconds
                if (last != null && (s == null && lastS == null || s != null && lastS != null && kotlin.math.abs(s - lastS) <= 3.5)) {
                    last += c
                } else {
                    clusters += mutableListOf(c)
                }
            }
            clusters.forEachIndexed { idx, cl ->
                val sec = ((cl.first().track.seconds ?: 0.0)).toInt()
                val k = "$bk|$sec#$idx"
                byKey[k] = cl
                cl.forEach { candKey[it] = k }
            }
        }
        fun key(c: Cand): String = candKey[c] ?: (baseKey(c) + "|0")
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

        val placedAll = byKey.mapNotNull { (k, list) ->
            // On equal quality rank, local/Discografías reference copies always take priority over Music/BitChord phone downloads.
            val best = list.maxWith(compareBy<Cand>({ rank(it.track) }, { if (it.track.path.contains("/Music/BitChord/")) 0 else 1 }, { richness(it.track) }, { it.track.kbps }))
            var homeKind = list.maxOf { it.homeKind }
            val there = list.filter { it.homeKind == homeKind }
            var home = there.groupingBy { it.home }.eachCount().maxBy { it.value }.key
            // A big artist's own album (Megumi Hayashibara's "スレイヤーズ MEGUMIX") stays theirs, even if a copy sits in a collection's folder.
            if (homeKind == 2) list.firstOrNull { it.homeKind == 1 && isOwnAlbum(it) && Words.plain(it.id.album).let { a -> collectionKeys.any { k -> a.contains(k.first) } } }?.let { c1 -> homeKind = 1; home = c1.home }
            if (homeKind == 1) absorbedHome(ownerOf(best.id))?.let { home = it; homeKind = 3 }
            // A "collection" named as one of a rules source's people (a folder of FictionJunction with guests),
            // or a song of one of them in a collection, is that person's.
            if (homeKind == 2) (absorbedHome(home.substringBefore('/')) ?: absorbedHome(firstArtist(best.id.artist)).takeIf { it != rulesOf.firstOrNull()?.target })?.let { home = it; homeKind = 3 }
            if (homeKind == 3 && list.none { it.homeKind == 3 }) {
                val h = byRules(best, home)
                if (h == null) { list.forEach { skipped += it.track to "fuera de las reglas de ${home.substringBefore('/')}" }; return@mapNotNull null }
                home = h
            }
            val inHome = list.filter { it.homeKind == homeKind && it.home == home }
            val chosen = when {
                inHome.isEmpty() -> best
                homeKind >= 2 -> inHome.maxWith(compareBy({ albumSize[home.lowercase() to Words.plain(it.id.album)] ?: 0 }, { if (it.id.album.isBlank()) 0 else 1 }, { -(it.id.year?.toIntOrNull() ?: 9999) }))
                else -> inHome.maxWith(compareBy({ if (it.id.album.isBlank()) 0 else 1 }, { rank(it.track) }, { if (it.track.path.contains("/Music/BitChord/")) 0 else 1 }, { richness(it.track) }))
            }
            Placed(k, best, chosen, home, homeKind, list)
        }
        val compChosen = HashMap<String, Pair<Compilations.Comp, Compilations.Track>>()
        val placed = if (!Layout.byRelease) placedAll else assignByRelease(placedAll, compChosen, ::gid, ::ownerOf, ::shown)
        // A Spanish version (a fandub, a cover) goes where its original is: "Hanamori no oka (Spanish)" by
        // FictionLatinas beside "Hanamori no oka", in that place's "Canciones en español".
        fun baseTitle(t: String) = Words.plain(t.replace(Regex("[\\[(（【][^\\])）】]*[\\])）】]"), " ")
            .replace(Regex("(?iu)\\b(fandub|spanish|español|espanol|castellano|latino|versi[oó]n|cover|sp|es)\\b"), " "))
        fun spanish(p: Placed) = SPANISH.containsMatchIn(p.best.id.title + " " + p.best.id.album + " " + p.best.track.path)
        val originals = placed.filter { it.homeKind >= 2 && !spanish(it) }.groupBy { baseTitle(it.best.id.title) }
        val rehomed = placed.map { p ->
            if (!spanish(p)) return@map p
            val b = baseTitle(p.best.id.title)
            val o = originals[b]?.takeIf { b.length >= 4 }?.groupingBy { it.home to it.homeKind }?.eachCount()
                ?.maxWithOrNull(compareBy({ if (Regex("(?i)/(directos|bandas sonoras)\\b").containsMatchIn(it.key.first)) 0 else 1 }, { it.value }))?.key ?: return@map p
            p.copy(home = o.first, homeKind = o.second)
        }
        // Same song twice in one album (an edition and its deluxe, a badly named copy): same album
        // (editions aside), same track number, same length → one, the best copy.
        val deduped = rehomed.groupBy { Triple(it.home.lowercase() + "|" + gid(ownerOf(it.best.id)) + "|" + (it.chosen.id.disc ?: 1), albumKey(it.chosen.id.album), it.chosen.id.no) }
            .flatMap { (key, l) ->
                if (key.third == null || key.second.isEmpty() || l.size < 2 || key.second == Words.plain(ownerOf(l[0].best.id))) l
                else l.sortedByDescending { rank(it.best.track) * 1000 + richness(it.best.track) }.fold(ArrayList<Placed>()) { keep, p ->
                    fun core(x: Placed) = Words.plain(x.best.id.title).removePrefix(Words.plain(ownerOf(x.best.id))).let { t -> t.ifEmpty { Words.plain(x.best.id.title) } }
                    val twin = keep.any { k ->
                        kotlin.math.abs((k.best.track.seconds ?: 0.0) - (p.best.track.seconds ?: -9.0)) < 1.5 &&
                            core(k).take(6).let { a -> a.length >= 4 && core(p).startsWith(a) || core(p).take(6).let { b -> b.length >= 4 && core(k).startsWith(b) } }
                    }
                    if (!twin) keep += p
                    else p.copies.forEach { c -> skipped += c.track to "repetida en el álbum (otra edición o copia)" }
                    keep
                }
            }
        // The 5-song rule, counted once per recording.
        var artistSongs = deduped.filter { it.homeKind == 1 }.groupingBy { gid(ownerOf(it.best.id)) }.eachCount()

        // An artist whose songs are nearly all of one collection (AiM, Miyazaki Ayumi → Digimon) is that collection's;
        // a small performer on an album named after a big artist (Princess China Music Orchestra plays Ayumi Hamasaki) is that artist's.
        val bigArtists = artistSongs.filter { it.value >= 30 }.keys
        val bigNames = deduped.filter { it.homeKind == 1 && gid(ownerOf(it.best.id)) in bigArtists }.map { ownerOf(it.best.id) }.distinct()
            .flatMap { n -> listOf(Words.plain(n) to gid(n), Words.plain(shown(n).substringBefore(" (")) to gid(n)) }.filter { it.first.length >= 6 }.distinct()
        val byOwner = deduped.filter { it.homeKind == 1 }.groupBy { gid(ownerOf(it.best.id)) }
        val ownerCollection = HashMap<String, String>()
        val ownerArtist = HashMap<String, String>()
        for ((g, l) in byOwner) {
            if (g.isEmpty()) continue
            val hits = l.mapNotNull { p ->
                val text = Words.plain(p.chosen.id.album + " " + p.best.track.path.substringAfterLast("/", "") + " " + p.chosen.albumDir)
                collectionKeys.firstOrNull { text.contains(it.first) }?.second
            }
            hits.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { (c, n) -> if (n >= 2 && n >= l.size * 0.8) ownerCollection[g] = c }
            if (g !in bigArtists && g !in ownerCollection) {
                val albums = l.map { Words.plain(it.chosen.id.album) }
                bigNames.firstOrNull { (n, big) -> big != g && albums.count { it.contains(n) } >= l.size * 0.8 }?.let { ownerArtist[g] = it.second }
            }
        }
        val regrouped = deduped.map { p ->
            if (p.homeKind != 1) return@map p
            val g = gid(ownerOf(p.best.id))
            ownerCollection[g]?.let { return@map p.copy(home = familyOf(it), homeKind = 2) }
            p
        }
        artistSongs = regrouped.filter { it.homeKind == 1 }.groupingBy { gid(ownerOf(it.best.id)).let { g -> ownerArtist[g] ?: g } }.eachCount()
        val ownerName = HashMap<String, String>()
        regrouped.filter { it.homeKind == 1 }.forEach { p -> ownerOf(p.best.id).let { n -> ownerName.putIfAbsent(gid(n), n) } }

        data class Draft(val p: Placed, val folder: String, val album: String, val albumArtist: String, val grouping: String?, val no: Int?, val series: String?,
                         val theme: Boolean = false, val artistHint: String? = null, val flat: Boolean = false,
                         val title: String? = null)
        // How many songs each album of a collection has here: a single's name is no album to keep ("Long Kiss Good Bye").
        val japaneseAlbums = HashSet<String>()
        val yearless = HashSet<Pair<String, String>>()
        val colAlbumSize = regrouped.filter { it.homeKind == 2 }.groupingBy { it.home.lowercase() to albumKey(it.chosen.id.album) }.eachCount()
        // "Otros artistas/<kind>/<artist>": each artist with few songs under its kind of music (JPOP, Español, Inglés…).
        val kindOfOwner = regrouped.filter { it.homeKind < 2 }.groupBy { p -> val g0 = gid(ownerOf(p.best.id).ifBlank { ownerOf(p.chosen.id) }); ownerArtist[g0] ?: g0 }
            .filterKeys { (artistSongs[it] ?: 0) < 5 }
            .mapValues { (g, l) ->
                val displayName = shown(ownerOf(l.first().best.id).ifBlank { g })
                kindOf(l.map { it.best.track }, l.map { it.best.id.title }, l.mapNotNull { it.best.id.year?.toIntOrNull() }, LastFm.tags(displayName), displayName)
            }
        // Homonymous albums (e.g. "ANRI" 2018, "Miho Morikawa") that have >= 3 numbered tracks are real albums, never stripped to loose tracks.
        val numberedAlbumCount = regrouped.filter { it.chosen.id.album.isNotBlank() && it.chosen.id.no != null }
            .groupingBy { (if (it.homeKind == 1) gid(ownerOf(it.best.id)) else it.home.lowercase()) to albumKey(it.chosen.id.album) }.eachCount()
        // A collection that is a series: openings and endings known for it, or songs filed as an anime's or a game's.
        val seriesLikeCache = HashMap<String, Boolean>()
        fun seriesLike(series: String) = seriesLikeCache.getOrPut(series) {
            Themes.of(series).isNotEmpty() || aliases.values.contains(series) || regrouped.any { p -> p.homeKind == 2 && p.home.substringBefore('/').let { f -> Words.plain(aliases[Words.plain(f)] ?: f) == Words.plain(series) } &&
                Regex("(?iu)/anime/|\\banime\\b|\\b(op|ed|opening|ending|ost|soundtrack|game|juego|videojuego)\\b|主題歌|サウンドトラック").containsMatchIn(p.best.track.path + " " + p.best.id.album) }
        }
        val draftsAll = regrouped.map { p ->
            val id = p.chosen.id
            val g0 = gid(ownerOf(p.best.id).ifBlank { ownerOf(id) })
            val g = ownerArtist[g0] ?: g0
            val owner = ownerName[g] ?: ownerOf(p.best.id).ifBlank { ownerOf(id) }
            val text = listOf(p.best.id.title, p.best.track.path.substringAfterLast('/'), id.album, p.chosen.albumDir.substringAfterLast('/')).joinToString(" ")
            var (folder, albumArtist, grouping) = when (p.homeKind) {
                3, 2 -> {
                    val first = p.home.substringBefore('/')
                    val top = if (p.homeKind == 2) aliases[Words.plain(first)] ?: first else shown(first)
                    Triple((listOf(top) + p.home.split('/').drop(1)).joinToString("/") { clean(it) }, top, if (p.homeKind == 2) top else null)
                }
                else -> when {
                    owner.isBlank() -> Triple("Sin identificar", "", null)
                    (artistSongs[g] ?: 0) >= 5 -> Triple(clean(shown(owner)), shown(owner), null)
                    else -> Triple("Otros artistas/" + (kindOfOwner[g] ?: "Inglés") + "/" + clean(shown(owner)), shown(owner), null)
                }
            }
            // NANA's songs sung as its characters ("ANNA TSUCHIYA inspi' NANA (BLACK STONES)", "OLIVIA inspi' REIRA (TRAPNEST)",
            // "NANA starring MIKA NAKASHIMA") belong to the series; the singers' other songs stay theirs.
            val credit = listOf(p.best.id.artist, p.best.id.albumArtist, id.artist, id.albumArtist).joinToString(" | ")
            val nana = p.homeKind < 2 && (NANA_CREDIT.containsMatchIn(credit) || NANA_SINGER.containsMatchIn(credit) &&
                (Regex("(?i)\\bnana\\b").containsMatchIn(id.album + " " + p.best.track.path) || NANA_SONGS.containsMatchIn(p.best.id.title)))
            if (nana) { val top = aliases[Words.plain("NANA")] ?: "NANA"; folder = top; albumArtist = top; grouping = top }
            // A series' name where its singer should be: the series' folder (the singer found by its sound, if it is).
            val asSeries = if (p.homeKind < 2 && !nana) (seriesOfArtist(owner) ?: aliases[Words.plain(owner)])?.let { n -> aliases[Words.plain(n)] ?: n } else null
            if (asSeries != null) { folder = asSeries; albumArtist = asSeries; grouping = asSeries }
            val series = if (p.homeKind == 2 || nana || asSeries != null) folder.substringBefore('/') else null
            // The album: never "Sueltas". Named only as its artist/folder → keep if it is a real numbered album (>= 3 numbered tracks or on Spotify);
            // in a collection, without its series' name in front ("美少女戦士セーラームーン｜S 音楽集" → "S 音楽集").
            val lastHome = p.home.substringAfterLast('/')
            val homonymousOk = (numberedAlbumCount[(if (p.homeKind == 1) g else p.home.lowercase()) to albumKey(id.album)] ?: 0) >= 3 ||
                (Spotify.checked(albumArtist, albumBase(id.album))?.optBoolean("found") == true)
            var album = albumBase(id.album).takeUnless {
                it.isBlank() || GRAB_BAG.matches(it.trim()) ||
                (!homonymousOk && (Words.plain(it) == Words.plain(lastHome) || Words.plain(it) == Words.plain(albumArtist) || Words.plain(it) == Words.plain(p.home.substringBefore('/'))))
            }.orEmpty()
            // An album named only in Japanese: its Latin name (VGMdb) in front, the Japanese one in brackets.
            if (album.isNotEmpty() && album.none { it in 'A'..'Z' || it in 'a'..'z' } && CJK.containsMatchIn(album)) {
                val raw = albumBase(id.album)
                val lat = AlbumNames.of(raw)
                if (lat == null) japaneseAlbums += raw
                else album = clean((if (series != null) withoutSeries(lat, series) else lat) + " (" + (if (series != null) withoutSeries(album, series) else album) + ")")
            }
            if (album.isNotEmpty() && series != null) album = withoutSeries(album, series)
            var no = id.no
            // An opening or ending of the series (MyAnimeList's list): "<anime> Openings", numbered as aired.
            var theme: Themes.Theme? = null
            if (series != null && (album.isEmpty() || THEME_BAG.containsMatchIn(album) || (colAlbumSize[p.home.lowercase() to albumKey(id.album)] ?: 0) < 3))
                theme = Themes.match(series, p.best.id.title)?.also { th -> album = clean(th.anime) + " " + th.kind; no = th.no }
            // A collection's song with no album: "<series> Openings / Endings", numbered by its place in the series.
            if (album.isEmpty() && series != null) {
                Regex("(?i)(.*?)\\b(op|opening|ed|ending|insert|ins)\\s*0*(\\d{1,3})?\\b").find(Words.fix(p.best.track.path.substringAfterLast('/').substringBeforeLast('.')) + " " + p.best.id.title)?.let { m ->
                    val kind = if (m.groupValues[2].lowercase().startsWith("o")) "Openings" else if (m.groupValues[2].lowercase().startsWith("e")) "Endings" else "Insert songs"
                    val prefix = clean(m.groupValues[1].replace(Regex("^[a-z]?\\d+\\s*(jap|es|eng|jeng)?\\s*", RegexOption.IGNORE_CASE), "").trim(' ', '-', '_', '.', '(', '[', '–'))
                    val name = if (prefix.isNotEmpty() && Words.plain(prefix).startsWith(Words.plain(series).take(5)) && prefix.length <= 40) prefix else series
                    album = "$name $kind"
                    no = m.groupValues[3].toIntOrNull() ?: no
                }
            }
            // Kinds of songs inside a collection; an artist's live/remix albums (mutually exclusive: never Directos/Remixes/).
            val alreadySub = folder.contains("/Directos") || folder.contains("/Remixes")
            val isLiveRelease = LIVE.containsMatchIn(id.album) || p.best.track.tags["genre"].equals("Live", ignoreCase = true) ||
                Regex("(?i)/live( albums)?/").containsMatchIn(p.best.track.path)
            val isClassicalArtist = LastFm.tags(albumArtist).take(3).any { it in setOf("classical", "baroque", "romantic", "composer") }
            val sub = when {
                p.homeKind >= 2 && (SPANISH.containsMatchIn(text + " " + p.best.track.path) || SPANISH.containsMatchIn(p.best.id.title) ||
                    SPANISH.containsMatchIn(File(p.best.track.path).nameWithoutExtension)) && !folder.endsWith("Canciones en español") -> "Canciones en español"
                p.homeKind == 2 && DRAMA.containsMatchIn(id.album) -> "Doramas"
                p.homeKind == 2 && OST.containsMatchIn(id.album) -> "Bandas sonoras"
                p.homeKind != 2 && !alreadySub && isLiveRelease -> "Directos"
                // Remix, trance, orchestra and music-box albums apart from studio ones (never concerts or classical composers).
                p.homeKind != 2 && !alreadySub && !isClassicalArtist && REMIX.containsMatchIn(id.album) -> "Remixes"
                else -> null
            }
            if (sub != null) folder = "$folder/$sub"
            album = album.replace(Regex("^[「『](.*)[」』]$"), "$1").trim().replace('（', '(').replace('）', ')')
            album = Regex("\\s*[(\\[][^)\\]]*$").replace(album, "").ifBlank { album }
            // A series: Openings, Endings (as aired, every version), its characters' songs, and the loose ones in "Otras";
            // its real albums as they are.
            var flat = false
            var themeTitle: String? = null
            if (series != null && sub == null) {
                val kind = when {
                    album.endsWith(" Openings") || Regex("(?i)\\bopenings?\\b").containsMatchIn(album) && !Regex("(?i)\\bendings?\\b").containsMatchIn(album) -> "Openings"
                    album.endsWith(" Endings") || Regex("(?i)\\bendings?\\b").containsMatchIn(album) && !Regex("(?i)\\bopenings?\\b").containsMatchIn(album) -> "Endings"
                    CHARACTER.containsMatchIn(text) || Regex("^[^()]+\\s[(（]\\p{Lu}\\p{Ll}+ \\p{Lu}\\p{Ll}+[)）]$").matches(p.best.id.artist.trim()) -> "Personajes"
                    // "Otras" only in a series' folder (an anime's or a game's), not in a singer's taken for a collection.
                    (album.isEmpty() || album.endsWith(" Insert songs") || THEME_BAG.containsMatchIn(album) || GRAB_BAG.matches(album)) && seriesLike(series) -> "Otras"
                    else -> null
                }
                if (kind != null) {
                    flat = kind != "Personajes" || album.isEmpty()
                    // Another show of the series ("Naruto Shippuden Openings" in "Naruto"): its own folder inside.
                    val show = album.removeSuffix(" Openings").removeSuffix(" Endings").trim()
                    folder = "$folder/$kind" + if ((kind == "Openings" || kind == "Endings") && theme != null && Words.plain(show) != Words.plain(series) && show.isNotEmpty()) "/${clean(show)}" else ""
                    if (kind == "Otras" && album.endsWith(" Insert songs")) album = ""
                    // Not matched with the list as aired: its number only if its name says it ("Believe (Opening 2)", "Op2"), never the old album's.
                    if ((kind == "Openings" || kind == "Endings") && theme == null)
                        no = Regex("(?i)\\b(op|opening|ed|ending)\\s*[-#]?\\s*0*(\\d{1,3})\\b").find(p.best.id.title + " " + File(p.best.track.path).nameWithoutExtension)?.groupValues?.get(2)?.toIntOrNull()
                    if (kind == "Openings" || kind == "Endings") themeTitle = THEME_MARK.replace(p.best.id.title, " ").replace(Regex("\\s{2,}"), " ").trim(' ', '-', '–', '_').ifBlank { null }
                }
            } else if (series != null && sub == "Canciones en español" && album.isEmpty()) flat = true
            // Her soundtracks by series ("Bandas sonoras/Tsubasa Chronicle/…").
            if (folder.endsWith("/Bandas sonoras") && p.homeKind == 3) {
                // The series: the folder the source keeps its soundtracks of one series in ("OSTs/Hack/…"), else from the album's name.
                val segs = p.best.track.path.split('/')
                val k = segs.indexOfLast { Regex("(?i)^(osts?|soundtracks?|bandas sonoras|bso)$").matches(it) }
                val fromSource = if (k >= 0 && k + 2 < segs.size - 1) segs[k + 1] else null
                (fromSource ?: soundtrackSeries(id.album))?.let { folder = "$folder/${clean(it)}" }
            }
            Draft(p, folder, album, albumArtist, grouping, no, series, theme != null, theme?.artist, flat, themeTitle)
        }
        // One opening (or ending) once: the best copy of each.
        val themeDupes = draftsAll.filter { it.theme }.groupBy { listOf(it.folder.lowercase(), it.album, it.no.toString(), Words.plain(it.p.best.id.title),
                it.p.best.id.year.orEmpty(), pkey(firstArtist(it.p.best.id.artist))) }.values
            .flatMap { l -> l.sortedByDescending { rank(it.p.best.track) * 1000 + richness(it.p.best.track) }.drop(1) }.toSet()
        themeDupes.forEach { d -> d.p.copies.forEach { skipped += it.track to "repetida (el mismo opening o ending)" } }
        // A series' soundtrack albums keep their songs only (as Yuki Kajiura's): its composer's pieces (the artist of most
        // of the album, or none) go out of the library, to a folder beside it, until it is decided what to do with them.
        val instrumental = draftsAll.filter { it.p.homeKind == 2 && it.folder.endsWith("/Bandas sonoras") }.groupBy { it.folder.lowercase() + "|" + albumKey(it.p.chosen.id.album) }
            .values.flatMap { l ->
                // Only infer an instrumental composer from majority tracks on full soundtracks (>= 8 tracks); small subsets of vocal OST songs (e.g. Outlaw Star) stay intact.
                val composer = if (l.size < 8) null else l.map { pkey(firstArtist(it.p.best.id.artist)) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
                    ?.takeIf { it.value * 2 >= l.size }?.key
                l.filter { d ->
                    val a = pkey(firstArtist(d.p.best.id.artist))
                    val t = d.p.best.id.title
                    INST.containsMatchIn(t) || !SUNG.containsMatchIn(t) && (a.isEmpty() || a == composer)
                }
            }.toSet()
        instrumental.forEach { d -> d.p.copies.forEach { skipped += it.track to "$OUTSIDE (fuera de la Biblioteca)" } }
        // Studio album instrumentals are kept (user decision); only series' soundtracks and single/OST instrumental B-sides are excluded.
        val draftAlbumSize = draftsAll.groupingBy { it.folder.lowercase() to albumKey(it.album) }.eachCount()
        val nonAlbumInst = draftsAll.filter { d ->
            INST_TITLE.containsMatchIn(d.p.best.id.title + " " + File(d.p.best.track.path).nameWithoutExtension) &&
                (d.p.homeKind >= 2 || OST.containsMatchIn(d.album) || d.album.isEmpty() || (draftAlbumSize[d.folder.lowercase() to albumKey(d.album)] ?: 0) < 5)
        }.toSet()
        nonAlbumInst.forEach { d -> d.p.copies.forEach { skipped += it.track to "instrumental (single / banda sonora)" } }
        val drafts = draftsAll.filter { it !in themeDupes && it !in instrumental && it !in nonAlbumInst }
        // Small collections (< 10 songs): video games go to "Videojuegos/", anime to "Anime/"; non-anime folders (Trance, anna) stay out of Anime/.
        val seriesSize = drafts.filter { it.series != null }.groupingBy { it.series!! }.eachCount()
        val smallSeries = drafts.filter { it.series != null && (seriesSize[it.series] ?: 0) < 10 }.groupBy { it.series!! }
        val gameSeries = smallSeries.filter { (name, l) ->
            GAME_HINT.containsMatchIn(name) || l.any { d -> GAME_HINT.containsMatchIn(d.p.best.track.path + " " + d.p.chosen.id.album) }
        }.keys
        val animeSeries = smallSeries.filter { (name, l) ->
            name !in gameSeries && !NON_ANIME_SERIES.matches(name) &&
                l.any { d -> Regex("(?i)/anime/|\\banime\\b|\\b(op|ed|opening|ending)\\b").containsMatchIn(d.p.best.track.path) || CJK.containsMatchIn(d.p.chosen.id.album + d.p.best.id.title) }
        }.keys
        val placed0 = drafts.map { d ->
            when {
                d.series in gameSeries -> d.copy(folder = "Videojuegos/" + d.folder)
                d.series in animeSeries -> d.copy(folder = "Anime/" + d.folder)
                else -> d
            }
        }
        // One album under two names, one of them read in Japanese ("Kalafina LIVE THE BEST 2015 “Blue Day” at 日本武道館" =
        // "… at Nippon Budokan") or same artist + same year (±1) with >= 60% matching tracks by duration ("Hatsukoi" + "初恋"): merge into the larger/Latin one.
        val albumTo = HashMap<Pair<String, String>, String>()
        placed0.groupBy { it.folder.lowercase() }.forEach { (f, l) ->
            val names = l.map { it.album }.filter { it.isNotEmpty() }.distinct()
            for (a in names.filter { CJK.containsMatchIn(it) }) {
                val k = Words.plain(a.replace(CJK, " ").replace(Regex("[「」『』“”]"), " "))
                if (k.length < 12) continue
                names.firstOrNull { it != a && !CJK.containsMatchIn(it) && Words.plain(it).startsWith(k) }?.let { albumTo[f to a] = it }
            }
            // Cross-script / edition overlap within the same artist folder & year (±1): merge when >= 60% of the smaller album's tracks match by duration (±2 s).
            val byAlb = l.filter { it.album.isNotEmpty() && !it.flat }.groupBy { albumTo[f to it.album] ?: it.album }
            val albList = byAlb.entries.filter { it.value.size >= 2 }.sortedByDescending { it.value.size }
            for (idxA in albList.indices) {
                val (bigName, bigTracks) = albList[idxA]
                val bigYear = bigTracks.mapNotNull { it.p.best.id.year?.toIntOrNull() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                for (idxB in idxA + 1 until albList.size) {
                    val (smallName, smallTracks) = albList[idxB]
                    if (albumTo.containsKey(f to smallName)) continue
                    val smallYear = smallTracks.mapNotNull { it.p.best.id.year?.toIntOrNull() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    if (bigYear != null && smallYear != null && kotlin.math.abs(bigYear - smallYear) > 1) continue
                    // Only merge across scripts (one CJK, one Latin) or when one title is a prefix/edition of the other.
                    val crossScript = CJK.containsMatchIn(bigName) != CJK.containsMatchIn(smallName)
                    val sameBase = Words.plain(albumBase(bigName)) == Words.plain(albumBase(smallName))
                    if (!crossScript && !sameBase) continue
                    val matched = smallTracks.count { st ->
                        val sd = st.p.best.track.seconds ?: return@count false
                        bigTracks.any { bt -> bt.p.best.track.seconds?.let { bd -> kotlin.math.abs(bd - sd) <= 2.0 && (st.no == null || bt.no == null || st.no == bt.no) } == true }
                    }
                    if (matched * 10 >= smallTracks.size * 6) {
                        val keep = if (!CJK.containsMatchIn(bigName) || CJK.containsMatchIn(smallName)) bigName else smallName
                        val drop = if (keep == bigName) smallName else bigName
                        albumTo[f to drop] = keep
                    }
                }
            }
        }
        // One concert under several names ("Consolation Live Tour 2013", "Kalafina Live Tour 2013 Consolation",
        // "Kalafina Consolation Live 2013"): the same words, but the singer's and "live"/"tour", are one album, under its longest name.
        placed0.filter { it.folder.endsWith("/Directos") && it.album.isNotEmpty() }.groupBy { it.folder.lowercase() }.forEach { (f, l) ->
            val owner = f.substringBefore("/directos").split('/').flatMap { Regex("[\\p{L}\\p{N}]+").findAll(Words.plain(it)).map { m -> m.value } }.toSet()
            fun words(a: String) = Regex("[\\p{L}\\p{N}]+").findAll(a.lowercase()).map { Words.plain(it.value) }
                .filter { it.isNotEmpty() && it !in owner && it !in setOf("live", "tour", "the", "at", "in", "concert", "ライブ") && Words.plain(it) !in owner }.toSortedSet().joinToString(" ")
            l.map { albumTo[f to it.album] ?: it.album }.distinct().groupBy { words(it) }.filter { it.key.length >= 4 && it.value.size > 1 }.values.forEach { names ->
                val keep = names.maxBy { it.length }
                names.filter { it != keep }.forEach { n -> albumTo[f to n] = keep }
                // names already sent elsewhere follow
                albumTo.entries.filter { it.key.first == f && it.value in names && it.value != keep }.forEach { it.setValue(keep) }
            }
        }
        val merged = placed0.map { d -> albumTo[d.folder.lowercase() to d.album]?.let { d.copy(album = it) } ?: d }
        // The same track of the same album twice (tagged once in Japanese, once in romaji): the better copy, the Latin name if equal.
        val twinTracks0 = merged.filter { it.album.isNotEmpty() && it.no != null && !it.flat }
            .groupBy { listOf(it.folder.lowercase(), albumKey(it.album), (it.p.chosen.id.disc ?: 1).toString(), it.no.toString()) }.values
            .flatMap { l ->
                if (l.size < 2) emptyList() else l.groupBy { d -> ((d.p.best.track.seconds ?: 0.0) / 3).toInt() }.values.filter { it.size >= 2 }
                    .flatMap { g -> g.sortedWith(compareByDescending<Draft> { rank(it.p.best.track) }.thenBy { if (CJK.containsMatchIn(it.p.best.id.title)) 1 else 0 }).drop(1) }
            }.toSet()

        val liveTwins = merged.filter { it !in twinTracks0 && it.folder.endsWith("/Directos") && it.album.isNotEmpty() }
            .groupBy { listOf(it.folder.lowercase(), albumKey(it.album), Words.plain(it.p.best.id.title)) }.values.filter { it.size > 1 }
            .flatMap { l -> l.groupBy { d -> ((d.p.best.track.seconds ?: 0.0) / 3).toInt() }.values.filter { it.size >= 2 }.flatMap { g -> g.sortedByDescending { rank(it.p.best.track) }.drop(1) } }
        val twinTracks = twinTracks0 + liveTwins
        twinTracks.forEach { d -> d.p.copies.forEach { skipped += it.track to "repetida (la misma pista del mismo álbum con otro nombre)" } }
        val placedDrafts = merged.filter { it !in twinTracks }
        // Editions of one album ("Emotion", "Emotion (10th Anniversary Edition)"): the year it first came out, for the edition kept.
        fun editionOf(a: String) = Words.plain(a.replace(Regex("\\s*[(\\[（【].*$"), "")
            .replace(Regex("(?i)\\s+-\\s*\\d*(st|nd|rd|th)?\\s*(deluxe|expanded|anniversary|special|complete|super).*$"), ""))
        val familyYear by lazy {
            val m = HashMap<Pair<String, String>, String>()
            for (d in placedDrafts) {
                if (d.album.isEmpty()) continue
                val fam = editionOf(d.album)
                for (c in d.p.copies) {
                    val y = c.id.year?.takeIf { Regex("^(19|20)\\d{2}$").matches(it) } ?: continue
                    if (editionOf(c.id.album) != fam) continue
                    val key = d.folder.lowercase() to fam
                    if (m[key] == null || y < m[key]!!) m[key] = y
                }
            }
            m
        }
        // One year per album; an album folder only for 3 songs or more (fewer: the songs at the folder's root).
        val albumYear = placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }.mapValues { (_, l) ->
            val d0 = l.first()
            val who = d0.albumArtist.ifBlank { d0.p.best.id.artist }
            val isNumericTitle = Regex("^(19|20)\\d{2}$").matches(d0.album.trim())
            val isCompOrLive = COMPILATION.containsMatchIn(d0.album) || d0.folder.contains("/Directos")
            val validYears = l.mapNotNull { it.p.chosen.id.year ?: it.p.best.id.year }.filter { Regex("^(19|20)\\d{2}$").matches(it) }
            val taggedAll = validYears.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { it.key }))
            // Ignore impossible pre-1960 years (e.g. "Lost/1957") unless the majority of tracks explicitly agree on it.
            val tagged = taggedAll?.takeIf { it.key.toInt() >= 1960 || it.value * 2 >= l.size }?.key
                ?: validYears.filter { it.toInt() >= 1960 }.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
            val spObj = Spotify.checked(who, d0.album)?.takeIf { it.optBoolean("found") }
            val spCount = spObj?.optJSONArray("tracks")?.length() ?: 0
            val minTaggedYear = validYears.mapNotNull { it.toIntOrNull() }.filter { it >= 1960 }.minOrNull()
            // On series collections (e.g. anime "Beck"), ignore external years > 5 years earlier than all tagged years (blocks false artist matches like Jeff Beck 1969).
            fun saneExtYear(y: String?): String? = y?.takeIf {
                val yi = it.toIntOrNull() ?: return@takeIf false
                (!isNumericTitle || it != d0.album.trim()) && (d0.series == null || minTaggedYear == null || minTaggedYear - yi <= 5)
            }
            // Ignore Spotify hits returning a 1–3 track single when our local album has >= 6 tracks.
            val sp = saneExtYear(spObj?.takeIf { !(l.size >= 6 && spCount in 1..3) }?.optString("year")?.ifBlank { null })
            val mb = saneExtYear(AlbumYears.of(who, d0.album))
            // Compilations, live concerts, and series collections with explicit tagged years keep their own release year.
            if (isCompOrLive || (d0.series != null && tagged != null)) {
                val safeTagged = tagged?.takeIf { !isNumericTitle || it != d0.album.trim() }
                safeTagged ?: sp ?: mb
            } else {
                val safeTagged = tagged?.takeIf { !isNumericTitle || it != d0.album.trim() }
                // The year it first came out: a reissue's tag ("DEEP RIVER" tagged 2018) gives way to an earlier year Spotify or
                // MusicBrainz knows (2002); a later one there (a remaster on Spotify) never replaces the tag's.
                listOfNotNull(safeTagged, sp, mb).filter { Regex("^(19|20)\\d{2}$").matches(it) && it.toInt() >= 1960 }
                    .minOrNull()?.takeIf { y -> safeTagged == null || safeTagged.toInt() - y.toInt() >= 2 } ?: safeTagged ?: mb ?: sp
            }
        }
        placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }.forEach { (k, l) ->
            if (k.second.isNotEmpty() && albumYear[k] == null && l.size >= 3) l.first().let { d -> yearless += (d.albumArtist.ifBlank { d.p.best.id.artist }) to d.album }
        }
        val albumCount = placedDrafts.groupingBy { it.folder.lowercase() to albumKey(it.album) }.eachCount()
        // Distinct discs actually present in each destination album folder: only prefix "2-NN" when the folder holds > 1 disc.
        val albumDiscs = placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }
            .mapValues { (_, l) -> l.map { it.p.chosen.id.disc ?: 1 }.toSet() }
        val albumMaxTrackNo = placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }
            .mapValues { (_, l) -> l.mapNotNull { it.no ?: it.p.chosen.id.no }.maxOrNull() ?: 0 }
        // How many of the songs kept here each release has (also those kept on another album): a single whose A-side is on its
        // album still gets its folder for its B-sides.
        val releaseSongs = HashMap<Pair<String, String>, MutableSet<String>>()
        for (d in placedDrafts) for (c in d.p.copies) if (c.id.album.isNotBlank()) releaseSongs.getOrPut(d.folder.lowercase() to albumKey(c.id.album)) { HashSet() }.add(d.p.k)
        // The album's name as most copies spell it, preferring the one in both scripts ("Slayers MEGUMIX -GOLD (スレイヤーズ MEGUMIX -GOLD)").
        val albumShown = placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }.mapValues { (_, l) ->
            l.groupingBy { it.album }.eachCount().maxWith(compareBy({ e -> if (latin(e.key.replace(CJK, "")) && CJK.containsMatchIn(e.key) && e.key.any { it in 'A'..'z' }) 1 else 0 }, { it.value })).key
        }
        // Artists with > 3 studio/full albums keep every single & compilation folder (with .m3u/.txt + covers/scans even if 0 audio files remain).
        // Artists with <= 3 albums (like Billie Eilish or Beyoncé) keep loose songs at root without spawning 1-2 file folders.
        val artistAlbumCount = placedDrafts
            .filter { it.series == null && !it.folder.startsWith("Otros artistas/") && it.album.isNotEmpty() && !it.flat && !it.folder.contains("/Directos") && !it.folder.contains("/Remixes") }
            .groupBy { it.folder.substringBefore('/').lowercase() }
            .mapValues { (_, l) ->
                l.groupBy { albumKey(it.album) }.count { (_, al) ->
                    val aName = al.first().album
                    al.size >= 3 && !COMPILATION.containsMatchIn(aName) && !Regex("(?i)\\bsingles?\\b|\\bep\\b|シングル").containsMatchIn(aName)
                }
            }
        val bigDiscogFolders = artistAlbumCount.filterValues { it > 3 }.keys
        val alternatives = HashMap<String, List<String>>()
        val items = placedDrafts.map { d ->
            val p = d.p
            val t = p.best.track
            val id = p.chosen.id
            val k = d.folder.lowercase() to albumKey(d.album)
            val album = if (d.album.isEmpty()) "" else albumShown[k] ?: d.album
            val isCompOrLive = COMPILATION.containsMatchIn(album) || d.folder.contains("/Directos")
            val year = if (album.isEmpty() || d.flat) (p.best.id.year ?: id.year)
                else if (isCompOrLive) albumYear[k]
                else listOfNotNull(familyYear[d.folder.lowercase() to editionOf(album)], albumYear[k]).filter { Regex("^\\d{4}$").matches(it) && it.toInt() >= 1960 }.minOrNull() ?: albumYear[k]
            // A collection's name with an edition is no singer ("Operación triunfo 1a ed").
            fun noSinger(a: String) = d.series != null && (Words.plain(a) == Words.plain(d.series) ||
                Words.plain(a).startsWith(Words.plain(d.series)) && Regex("(?iu)\\b\\d+\\s*(a|er|ª|º|st|nd|rd|th)?\\s*(ed|edici[oó]n|disco|edition)\\b").containsMatchIn(a))
            val rawArtist = p.best.id.artist.ifBlank { id.artist }.takeUnless { noSinger(it) }.orEmpty().ifBlank { d.artistHint.orEmpty() }
            val artist = if (Regex("[,&、;/×]|\\s(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE).containsMatchIn(rawArtist)) Words.nfc(rawArtist) else shown(rawArtist)
            val relSize = releaseSongs[k]?.size ?: 0
            val ownCount = albumCount[k] ?: 0
            val bigDiscog = d.series == null && d.folder.substringBefore('/').lowercase() in bigDiscogFolders
            val spTracks = Spotify.checked(d.albumArtist.ifBlank { artist }, album)?.takeIf { it.optBoolean("found") }?.optJSONArray("tracks")?.length() ?: 0
            val maxTrackNo = albumMaxTrackNo[k] ?: 0
            val looksStudioOrComp = spTracks >= 6 || maxTrackNo >= 7 || COMPILATION.containsMatchIn(album) || Regex("\\b(19|20)\\d{2}\\s*[-–]\\s*(19|20)\\d{2}\\b").containsMatchIn(album)
            val explicitSingle = Regex("(?i)\\bsingles?\\b|\\bep\\b|シングル").containsMatchIn(album)
            val inSingleDir = Regex("(?i)/singles?/").containsMatchIn(p.best.track.path) || p.copies.any { Regex("(?i)/singles?/").containsMatchIn(it.track.path) }
            val singleCandidate = !d.flat && album.isNotEmpty() && Layout.byRelease && d.p.homeKind == 1 &&
                !d.folder.contains("/Directos") && !d.folder.contains("/Remixes") && !looksStudioOrComp &&
                (if (bigDiscog) (relSize in 1..4 || explicitSingle || (inSingleDir && relSize <= 5))
                 else ((relSize in 1..4 && (relSize > ownCount || explicitSingle || inSingleDir)) || (relSize == 5 && explicitSingle)))
            val ownFolder = !d.flat && album.isNotEmpty() && (
                ownCount >= 3 ||
                bigDiscog ||
                (Layout.byRelease && d.p.homeKind == 1 && relSize >= 2 && relSize > ownCount)
            )
            val single = ownFolder && singleCandidate
            val albumDir = if (ownFolder) (if (single) "Singles/" else "") + clean(listOfNotNull(year, album).joinToString(" - ")) else ""
            val multiDisc = (albumDiscs[k]?.size ?: 0) > 1
            // Root loose files (no album/single folder) omit leading track numbers — start directly with the song title (Billie Eilish style).
            // Series Openings/Endings keep their sequential theme numbers.
            val isNumberedFlat = d.flat && (d.folder.endsWith("/Openings") || d.folder.endsWith("/Endings") || d.folder.contains("/Openings/") || d.folder.contains("/Endings/"))
            val num = if (!ownFolder && !isNumberedFlat) null else d.no?.let { n -> (id.disc?.takeIf { multiDisc && it > 1 }?.let { "$it-" } ?: "") + n.toString().padStart(2, '0') }
            val title = d.title ?: p.best.id.title
            val name = listOfNotNull(num, title, artist.ifBlank { null }, album.ifBlank { null }, year).joinToString(" - ") { clean(it) }
            alternatives[p.k] = p.copies.map { it.albumDir }.distinct().filter { it != p.chosen.albumDir }
            Item(
                source = p.best.source, track = t, target = listOf(targetRoot, d.folder, albumDir, "$name.${Formats.extOf(t)}").filter { it.isNotEmpty() }.joinToString("/"),
                reason = when {
                    t.lossless -> if (Formats.native) "sin pérdida → AAC 320" else "sin pérdida → MP3 320"
                    t.codec == "aac" -> if (t.bitrate >= 290) "AAC ${t.bitrate} kbps" else "AAC ${t.bitrate} kbps (a la to-do, poca prioridad)"
                    t.codec == "opus" -> "Opus → AAC 320 (a la to-do: buscar mejor copia)"
                    rank(t) == 3 -> "MP3 320"
                    else -> "MP3 ${t.kbps} kbps (mientras tanto; a la to-do)"
                },
                title = title, artist = artist, album = album, year = year, trackNo = d.no, disc = id.disc,
                albumArtist = d.albumArtist, grouping = d.grouping,
                // A folder's picture only when the folder is one album; else the song's own.
                cover = p.chosen.albumDir.takeIf { it in singleAlbumDirs || p.k in compChosen }, key = p.k,
                copies = p.copies.map { it.track.path },
                releases = (listOfNotNull(compChosen[p.k]?.let { (c, tr) -> Release(clean(c.title), artist, c.year, tr.no, tr.disc, c.coverFolder) }) +
                    p.copies.filter { it.id.album.isNotBlank() }.map { c -> Release(clean(c.id.album), shown(c.id.albumArtist.ifBlank { c.id.artist }), c.id.year, c.id.no, c.id.disc, c.albumDir) })
                    .distinctBy { albumKey(it.album) },
            )
        }
        val unique = verifyAndDedupeItems(items, tracks, ids, targetRoot, skipped)
        val (mainSidecars, mainSidecarFiles) = sidecarsWithFiles(unique, targetRoot, bigDiscogFolders)
        val opResult = if (opRoot != null && opTracks.isNotEmpty()) OnePiece.plan(opRoot, opTracks, sources, targetRoot) else null
        val allItems = if (opResult != null) (unique + opResult.items).sortedBy { it.target } else unique
        if (opResult != null) {
            skipped += opResult.skipped
            keyOf.putAll(opResult.keyOf)
        }
        val allSidecars = LinkedHashMap(mainSidecars).apply { if (opResult != null) putAll(opResult.sidecars) }
        val allSidecarFiles = LinkedHashMap(mainSidecarFiles).apply { if (opResult != null) putAll(opResult.sidecarFiles) }
        return Plan(allItems, skipped, alternatives, keyOf, japaneseAlbums, yearless, allSidecars, people.scriptPairs(), allSidecarFiles)
    }

    /** Assigns each song to its canonical release (original studio album > single/EP > local compilation > synthetic MB compilation),
     *  reconstructing original studio albums from Spotify tracklists when a song currently sits only in a compilation or loose. */
    private fun assignByRelease(
        placedAll: List<Placed>,
        compChosen: HashMap<String, Pair<Compilations.Comp, Compilations.Track>>,
        gid: (String) -> String,
        ownerOf: (Id) -> String,
        shown: (String) -> String,
    ): List<Placed> = placedAll.groupBy { (if (it.homeKind == 1) "1|" + gid(ownerOf(it.best.id)) else "${it.homeKind}|" + it.home.lowercase()) }.values.flatMap { group ->
        fun same(c: Cand, p: Placed) = c.homeKind == p.homeKind && c.home == p.home && c.id.album.isNotBlank() && (p.homeKind != 1 || gid(ownerOf(c.id)) == gid(ownerOf(p.best.id)))
        val albums = HashMap<String, MutableSet<String>>()
        val albumOrigName = HashMap<String, String>()
        val albumPaths = HashMap<String, MutableList<String>>()
        group.forEach { p ->
            p.copies.filter { same(it, p) }.forEach { c ->
                val ak = albumKey(c.id.album)
                albums.getOrPut(ak) { HashSet() }.add(p.k)
                albumOrigName.merge(ak, c.id.album) { a, b -> if (b.length > a.length) b else a }
                albumPaths.getOrPut(ak) { ArrayList() }.add(c.track.path)
            }
        }
        val g0 = group.first()
        val whose = if (g0.homeKind == 1) shown(ownerOf(g0.best.id)) else g0.home.substringBefore('/')
        val comps = Compilations.of(whose)
        val inComp = HashMap<String, Pair<Compilations.Comp, Compilations.Track>>()
        for (c in comps) {
            val byCore = c.tracks.groupBy { Themes.core(it.title) }
            for (p in group) {
                val cores = (listOf(Themes.core(p.best.id.title)) + Regex("[(（]([^)）]+)[)）]").findAll(p.best.id.title).map { Words.plain(it.groupValues[1]) }).filter { it.length >= 2 }
                val ver = VERSION.containsMatchIn(p.best.id.title)
                val t = cores.firstNotNullOfOrNull { byCore[it] }?.filter { VERSION.containsMatchIn(it.title) == ver }?.firstOrNull { tr -> tr.seconds == null || p.best.track.seconds == null || kotlin.math.abs(tr.seconds - p.best.track.seconds!!) <= 6 } ?: continue
                albums.getOrPut("mb:" + c.id) { HashSet() }.add(p.k)
                inComp["mb:${c.id}|${p.k}"] = c to t
            }
        }
        val byPlace = group.associateBy { it.k }
        inComp.entries.groupBy { (key, v) -> Triple(key.substringBefore('|'), v.second.disc ?: 1, v.second.no) }.values.filter { it.size > 1 }.forEach { l ->
            val best = l.minWith(compareBy({ (key, v) -> val p = byPlace[key.substringAfter('|')]; val a = v.second.seconds; val b = p?.best?.track?.seconds
                if (a == null || b == null) 999.0 else kotlin.math.abs(a - b) }, { (key, _) -> -(byPlace[key.substringAfter('|')]?.let { rank(it.best.track) } ?: 0) }))
            l.filter { it !== best }.forEach { (key, _) -> inComp.remove(key); albums[key.substringBefore('|')]?.remove(key.substringAfter('|')) }
        }
        val pick = HashMap<String, String>()
        val left = group.filter { p -> p.copies.any { same(it, p) } }.map { it.k }.toMutableSet()
        val compTitles = comps.map { Words.plain(it.title) }.toSet()
        fun kind(a: String, ks: Set<String>): Int {
            if (g0.homeKind != 1) return if (g0.homeKind == 2 && a == Words.plain(whose)) 1 else 0
            if (a.startsWith("mb:")) return 3
            val orig = albumOrigName[a] ?: a
            val paths = albumPaths[a].orEmpty()
            val inCompDir = paths.any { Regex("(?i)/(compilations?|best( of)?|greatest hits|recopilatorios?)/").containsMatchIn(it) }
            val inSingleDir = paths.any { Regex("(?i)/singles?/").containsMatchIn(it) }
            val spStudio = Spotify.checked(whose, orig)?.takeIf { it.optBoolean("found") }?.optJSONArray("tracks")?.length()?.let { it >= 6 } == true
            return when {
                Regex("(?i)[-(\\[]\\s*single\\s*[)\\]]?$|\\bep\\b|シングル$").containsMatchIn(orig) -> 1
                COMPILATION.containsMatchIn(orig) || Words.plain(orig) in compTitles || inCompDir -> 2
                spStudio -> 0
                ks.size <= 4 || Regex("(?i)\\bsingle\\b|\\bep\\b|シングル").containsMatchIn(orig) || (inSingleDir && ks.size <= 5) -> 1
                else -> 0
            }
        }
        val studioRehome = HashMap<String, StudioSlot>()
        // Reconstruct original studio albums from compilations for artists with > 5 local release folders (ZARD, L'Arc~en~Ciel, Ayumi Hamasaki, Anri, etc.).
        if (g0.homeKind == 1 && albums.keys.count { !it.startsWith("mb:") } > 5) {
            val localStudioAks = albums.keys.filter { !it.startsWith("mb:") && kind(it, albums[it].orEmpty()) == 0 }
            val alreadyInStudio = localStudioAks.flatMap { albums[it].orEmpty() }.toSet()
            val localStudioData = localStudioAks.mapNotNull { ak ->
                val orig = albumOrigName[ak] ?: return@mapNotNull null
                val spObj = Spotify.checked(whose, orig)?.takeIf { it.optBoolean("found") } ?: return@mapNotNull null
                val arr = spObj.optJSONArray("tracks") ?: return@mapNotNull null
                if (arr.length() < 6) return@mapNotNull null
                val spYear = spObj.optString("year").ifBlank { null }
                val existingTracks = group.filter { it.k in albums[ak].orEmpty() }
                val existingNos = existingTracks.mapNotNull { p -> p.copies.firstOrNull { same(it, p) && albumKey(it.id.album) == ak }?.id?.no }.toSet()
                val existingYear = existingTracks.firstNotNullOfOrNull { p -> p.copies.firstOrNull { same(it, p) && albumKey(it.id.album) == ak }?.id?.year }
                val existingDir = existingTracks.firstNotNullOfOrNull { p -> p.copies.firstOrNull { same(it, p) && albumKey(it.id.album) == ak }?.albumDir } ?: ""
                val spTracks = (0 until arr.length()).map { idx -> (idx + 1) to arr.getString(idx) }
                Triple(ak, Triple(orig, existingYear ?: spYear, existingDir), existingNos to spTracks)
            }.sortedBy { it.second.second ?: "9999" }
            if (localStudioData.isNotEmpty()) {
                for (p in group) {
                    if (p.k in alreadyInStudio) continue
                    val rawTitle = p.best.id.title
                    val pVer = VERSION.containsMatchIn(rawTitle)
                    val pCores = (listOf(Themes.core(rawTitle)) +
                        Regex("[(（「『]([^)）」』]+)[)）」』]").findAll(rawTitle).map { Themes.core(it.groupValues[1]) })
                        .filter { it.length >= 2 }.distinct()
                    if (pCores.isEmpty()) continue
                    outer@ for ((ak, meta, trData) in localStudioData) {
                        val (origName, year, dir) = meta
                        val (existingNos, spTracks) = trData
                        for ((no, spTitle) in spTracks) {
                            if (no in existingNos) continue
                            if (VERSION.containsMatchIn(spTitle) != pVer) continue
                            val spCores = (listOf(Themes.core(spTitle)) +
                                Regex("[(（「『]([^)）」』]+)[)）」』]").findAll(spTitle).map { Themes.core(it.groupValues[1]) })
                                .filter { it.length >= 2 }
                            if (pCores.any { it in spCores }) {
                                albums.getOrPut(ak) { HashSet() }.add(p.k)
                                left.add(p.k)
                                studioRehome["$ak|${p.k}"] = StudioSlot(origName, year, no, dir)
                                break@outer
                            }
                        }
                    }
                }
            }
        }
        while (left.isNotEmpty()) {
            val (a, ks) = albums.maxWithOrNull(compareBy({ -kind(it.key, it.value) }, { it.value.count { k -> k in left } }, { it.value.size })) ?: break
            val take = ks.filter { it in left }
            if (take.isEmpty()) break
            take.forEach { pick[it] = a; left -= it }
            albums.remove(a)
        }
        group.map { p ->
            val a = pick[p.k] ?: return@map p
            studioRehome["$a|${p.k}"]?.let { s ->
                return@map p.copy(chosen = p.chosen.copy(id = p.chosen.id.copy(album = s.album, year = s.year ?: p.chosen.id.year, no = s.no, disc = 1), albumDir = s.albumDir.ifBlank { p.chosen.albumDir }))
            }
            inComp["$a|${p.k}"]?.let { (c, t) ->
                compChosen[p.k] = c to t
                return@map p.copy(chosen = p.chosen.copy(id = p.chosen.id.copy(album = c.title, year = c.year ?: p.chosen.id.year, no = t.no, disc = t.disc), albumDir = c.coverFolder))
            }
            val c = p.copies.filter { same(it, p) && albumKey(it.id.album) == a }.maxWith(compareBy({ rank(it.track) }, { if (it.track.path.contains("/Music/BitChord/")) 0 else 1 }, { richness(it.track) }))
            p.copy(chosen = c)
        }
    }

    /** Deduplicates same-name/loose twins and acoustically verifies phone downloads against local reference copies (always falling back to local). */
    private fun verifyAndDedupeItems(
        items: List<Item>,
        tracks: List<Track>,
        ids: Map<String, Id>,
        targetRoot: String,
        skipped: MutableList<Pair<Track, String>>,
    ): List<Item> {
        fun better(a: Item, b: Item) = compareValuesBy(a, b, { rank(it.track) }, { if (it.album.isNotBlank()) 1 else 0 }, { if (it.trackNo != null) 1 else 0 }, { it.track.kbps })
        fun sameLength(a: Item, b: Item) = a.track.seconds != null && b.track.seconds != null && kotlin.math.abs(a.track.seconds - b.track.seconds) <= 2.0
        val twins = HashSet<Item>()
        val trackByPath = tracks.associateBy { it.path }
        fun dedupe(groups: Collection<List<Item>>, why: String) = groups.filter { it.size > 1 }.forEach { g ->
            val left = g.filter { it !in twins }.sortedWith { a, b -> better(b, a) }.toMutableList()
            while (left.isNotEmpty()) {
                val keep = left.removeAt(0)
                left.filter { sameLength(keep, it) }.forEach { d -> twins += d; left.remove(d); d.copies.forEach { c -> trackByPath[c]?.let { skipped += it to why } } }
            }
        }
        dedupe(items.groupBy { it.target.substringBeforeLast('.').lowercase().replace(Regex(" \\(\\d\\)$"), "") }.values, "repetida (la misma canción con el mismo nombre)")
        fun downloaded(path: String) = path.contains("/Music/BitChord/")
        fun songKey(id: Id) = pkey(firstArtist(id.artist.ifBlank { id.albumArtist })) + "|" + Themes.core(id.title)
        val reference = tracks.filter { !downloaded(it.path) }.mapNotNull { t -> ids[t.path]?.let { songKey(it) to t } }.groupBy({ it.first }, { it.second })
        val verifiedItems = items.mapNotNull { i ->
            if (i in twins) return@mapNotNull i
            if (!downloaded(i.track.path)) return@mapNotNull i
            val id = ids[i.track.path] ?: return@mapNotNull i
            val localCopies = i.copies.filter { !downloaded(it) }.mapNotNull { trackByPath[it] }
                .sortedWith(compareByDescending<Track> { rank(it) }.thenByDescending { richness(it) }.thenByDescending { it.bitrate })
            val bestLocal = localCopies.firstOrNull()
            val refs = (localCopies + reference[songKey(id)].orEmpty().filter {
                val rt = ids[it.path]?.title.orEmpty()
                versionTag(rt) == versionTag(id.title) && VERSION.containsMatchIn(rt) == VERSION.containsMatchIn(id.title)
            }).distinctBy { it.path }
            if (refs.isEmpty()) return@mapNotNull i
            val bcCopies = (listOf(i.track) + i.copies.filter { downloaded(it) && it != i.track.path }.mapNotNull { trackByPath[it] })
                .distinctBy { it.path }
                .sortedWith(compareByDescending<Track> { rank(it) }.thenByDescending { it.bitrate })
            var chosenBc: Track? = null
            var lastRef: Track = refs.first()
            for (bc in bcCopies) {
                val r = refs.minBy { kotlin.math.abs((it.seconds ?: 0.0) - (bc.seconds ?: 0.0)) }
                lastRef = r
                if (bestLocal != null && (bestLocal.lossless || bestLocal.mp3_320 && bestLocal.fake320 != true || (bestLocal.codec == "aac" && bestLocal.bitrate >= 290) || (bc.bitrate - bestLocal.bitrate < 32 && rank(bc) <= rank(bestLocal)))) {
                    continue
                }
                val sim = runCatching { Fingerprint.similarity(r.path, bc.path) }.getOrNull()
                if (sim != null && sim >= Fingerprint.SAME) { chosenBc = bc; break }
            }
            if (chosenBc != null) {
                val ext = Formats.extOf(chosenBc) ?: return@mapNotNull i
                return@mapNotNull if (chosenBc.path == i.track.path) i else i.copy(track = chosenBc, target = i.target.substringBeforeLast('.') + "." + ext)
            }
            skipped += i.track to "descarga que no suena como tu copia (${lastRef.path.substringAfterLast('/')})"
            if (bestLocal != null) {
                val ext = Formats.extOf(bestLocal) ?: return@mapNotNull null
                val newReason = when {
                    bestLocal.lossless -> if (Formats.native) "sin pérdida → AAC 320" else "sin pérdida → MP3 320"
                    bestLocal.codec == "aac" -> if (bestLocal.bitrate >= 290) "AAC ${bestLocal.bitrate} kbps" else "AAC ${bestLocal.bitrate} kbps (a la to-do, poca prioridad)"
                    bestLocal.codec == "opus" -> "Opus → AAC 320 (a la to-do: buscar mejor copia)"
                    rank(bestLocal) == 3 -> "MP3 320"
                    else -> "MP3 ${bestLocal.kbps} kbps (mientras tanto; a la to-do)"
                }
                return@mapNotNull i.copy(track = bestLocal, target = i.target.substringBeforeLast('.') + "." + ext, reason = newReason)
            }
            twins += i
            null
        }
        Fingerprint.save()
        val LIVE_OR_MIX = Regex("(?i)/(directos|remixes)/|\\b(live|remix|mix|ver\\.?|version|versión|acoustic|instrumental|tv size|karaoke)\\b")
        dedupe(verifiedItems.filter { it !in twins && !LIVE_OR_MIX.containsMatchIn(it.target.removePrefix(targetRoot)) }.groupBy { i ->
            val rel = i.target.removePrefix(targetRoot).trimStart('/').split('/')
            val owner = if (rel.firstOrNull() == "Otros artistas") rel.take(3).joinToString("/") else rel.first()
            owner.lowercase() + "|" + Words.plain(i.title) + "|" + pkey(firstArtist(i.artist))
        }.values, "repetida (la misma canción del mismo artista, suelta y en su álbum)")
        val seen = HashMap<String, Int>()
        return verifiedItems.filter { it !in twins }.sortedBy { it.target }.map { i ->
            val n = seen.merge(i.target.lowercase(), 1, Int::plus)!!
            if (n == 1) i else i.target.substringAfterLast('.').let { e -> i.copy(target = i.target.removeSuffix(".$e") + " ($n).$e") }
        }
    }

    const val SIDECAR_M3U = "00 - Lista completa.m3u"
    const val SIDECAR_TXT = "00 - Dónde está cada canción.txt"
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")
    private val SCAN_SUBDIR = Regex("(?i)^(scans?|booklet|im[aá]genes|covers?|artwork|bk)$")

    /** Collects covers and Scans/ subfolders from a local single/compilation source directory into [outFiles] (read-only source!). */
    private fun collectReleaseImages(srcDirPath: String, destHome: String, ownerName: String, outFiles: MutableMap<String, String>) {
        if (srcDirPath.isBlank() || srcDirPath.contains("/Music/BitChord/")) return
        val dir = File(srcDirPath)
        if (!dir.isDirectory) return
        val dirName = dir.name
        if (GRAB_BAG.matches(dirName.trim()) || Regex("(?i)^(singles?|albums?|compilations?|best|recopilatorios?)$").matches(dirName.trim())) return
        if (Words.plain(folderName(dirName)) == Words.plain(ownerName.substringBefore(" ("))) return
        dir.listFiles().orEmpty().forEach { f ->
            if (f.name.startsWith(".")) return@forEach
            when {
                f.isFile && f.extension.lowercase() in IMG_EXT -> {
                    outFiles.putIfAbsent(Words.nfc("$destHome/${clean(f.name)}"), f.path)
                }
                f.isDirectory && SCAN_SUBDIR.matches(f.name) -> {
                    f.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in IMG_EXT && !it.name.startsWith(".") }.forEach { img ->
                        val relImg = img.path.removePrefix(dir.path).trimStart('/').split('/').joinToString("/") { clean(it) }
                        outFiles.putIfAbsent(Words.nfc("$destHome/$relImg"), img.path)
                    }
                }
            }
        }
    }

    /** A single's or a compilation's folder holds only its own songs (B-sides, songs on no album); its other songs live in their
     *  albums. Beside them: the whole release as a playlist (.m3u, in its order, pointing to those files), in words (.txt), and for
     *  artists with > 3 albums, the single/compilation folder itself (even when 0 audio files remain) with its covers and scans. */
    private fun sidecarsWithFiles(items: List<Item>, root: String, bigDiscogFolders: Set<String>): Pair<Map<String, String>, Map<String, String>> {
        fun rel(t: String) = t.removePrefix(root).trimStart('/')
        fun owner(t: String) = rel(t).split('/').let { if (it.first() == "Otros artistas") it.take(3) else it.take(1) }.joinToString("/")
        val groups = HashMap<Pair<String, String>, MutableList<Pair<Item, Release>>>()
        for (i in items) for (r in i.releases) if (r.album.isNotBlank()) groups.getOrPut(owner(i.target) to albumKey(r.album)) { ArrayList() }.add(i to r)
        val out = LinkedHashMap<String, String>()
        val outFiles = LinkedHashMap<String, String>()
        val compCoverRoot = Compilations.coverRoot.path
        for ((key, l0) in groups) {
            val l = l0.distinctBy { it.first.target }
            val ownerTop = key.first.substringBefore('/').lowercase()
            val isBigArtist = ownerTop in bigDiscogFolders && !key.first.startsWith("Otros artistas/")
            val r0 = l.first().second
            val allLocalDirs = l.map { it.second.folder }.filter { it.isNotBlank() && !it.startsWith(compCoverRoot) }.distinct()
            val scanDirs = allLocalDirs.filter { !it.contains("/Music/BitChord/") }
            val inSingleDir = allLocalDirs.any { Regex("(?i)/singles?/").containsMatchIn(it) }
            val inCompDir = allLocalDirs.any { Regex("(?i)/(compilations?|best( of)?|greatest hits|recopilatorios?)/").containsMatchIn(it) }
            val isExplicitSingle = Regex("(?i)\\bsingles?\\b|\\bep\\b|シングル").containsMatchIn(r0.album)
            val isComp = COMPILATION.containsMatchIn(r0.album) || inCompDir
            // For artists with > 3 albums, even a 1-track single or compilation (whose songs live on albums) gets its folder + .m3u/.txt + scans.
            val minTracks = if (isBigArtist && allLocalDirs.isNotEmpty() && (inSingleDir || isExplicitSingle || isComp)) 1 else 2
            if (l.size < minTracks) continue
            val existingHome = l.filter { (i, _) -> albumKey(i.album) == key.second }.map { it.first.target.substringBeforeLast('/') }
                .filter { d -> d.substringAfterLast('/').let { Regex("^(\\d{4} - )?").replace(it, "") }.let { albumKey(it) == key.second } }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            // Create empty-audio single & compilation folders (with .m3u, .txt, covers/scans) for artists with > 3 albums (never synthetic MB-only comps).
            val home = existingHome ?: run {
                val fromLocalRelease = allLocalDirs.isNotEmpty()
                val isSingleOrComp = isComp || inSingleDir || isExplicitSingle || l.size in 2..5
                if (!isBigArtist || !fromLocalRelease || !isSingleOrComp) null
                else {
                    val sub = if (!isComp && (inSingleDir || isExplicitSingle || l.size <= 4)) "Singles/" else ""
                    "$root/${key.first}/$sub" + clean(listOfNotNull(r0.year, r0.album).joinToString(" - "))
                }
            } ?: continue
            val elsewhere = l.count { (i, _) -> i.target.substringBeforeLast('/') != home }
            val isSingleOrCompHome = home.contains("/Singles/") || isComp || isExplicitSingle || inSingleDir
            // Copy source covers & Scans/ for singles and compilations of artists with > 3 albums.
            if (isBigArtist && isSingleOrCompHome) {
                scanDirs.forEach { d -> collectReleaseImages(d, home, key.first, outFiles) }
            }
            if (elsewhere == 0) continue
            val sorted = l.sortedWith(compareBy({ it.second.disc ?: 1 }, { it.second.no ?: 999 }, { it.first.title.lowercase() }))
            val dir = java.nio.file.Paths.get(Words.nfc(home))
            val m3u = buildString {
                append("#EXTM3U\n#PLAYLIST:").append(Words.nfc(r0.album)).append("\n")
                for ((i, _) in sorted) {
                    append("#EXTINF:").append((i.track.seconds ?: 0.0).toInt()).append(',').append(Words.nfc(i.artist)).append(" - ").append(Words.nfc(i.title)).append('\n')
                    append(Words.nfc(dir.relativize(java.nio.file.Paths.get(Words.nfc(i.target))).toString())).append('\n')
                }
            }
            val txt = buildString {
                append(Words.nfc(r0.album)).append(r0.year?.let { " ($it)" } ?: "").append("\n\n")
                append("Aquí solo están las canciones que no salieron en ningún álbum. Las demás están guardadas una vez, en su álbum:\n\n")
                for ((i, r) in sorted) {
                    val n = listOfNotNull(r.disc?.takeIf { it > 1 }?.let { "$it-" }, r.no?.toString()?.padStart(2, '0')).joinToString("")
                    val where = i.target.substringBeforeLast('/')
                    append(if (n.isNotEmpty()) "$n. " else "· ").append(Words.nfc(i.title))
                    append(if (where == home) "  (aquí)" else "  → ${Words.nfc(rel(where))}").append('\n')
                }
            }
            out[Words.nfc("$home/$SIDECAR_M3U")] = m3u
            out[Words.nfc("$home/$SIDECAR_TXT")] = txt
        }
        return out to outFiles
    }

    /** The plan in words, for the report: each top folder and what is in it, what is left out and why. */
    fun overview(plan: Plan, root: String): String = buildString {
        val rel = plan.copy.map { it.target.removePrefix(root).trimStart('/') }
        appendLine("Plan: ${rel.size} canciones en ${rel.map { it.substringBefore('/') }.distinct().size} carpetas")
        appendLine("Sin álbum: ${plan.copy.count { it.album.isBlank() }} · sin artista: ${plan.copy.count { it.artist.isBlank() }} · sin identificar: ${rel.count { it.startsWith("Sin identificar/") }}")
        appendLine("Álbumes solo en japonés aún sin nombre latino: ${plan.japaneseAlbums.size}")
        appendLine("No se copian ${plan.skipped.size}: " + plan.skipped.groupingBy { it.second.substringBefore(":") }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.value} ${it.key}" })
        if (Spotify.ready) {
            // Albums against Spotify (Japan's catalogue, then Spain's): not there, another year, more songs here than it has.
            val albums = plan.copy.filter { it.album.isNotBlank() }.groupBy { (it.albumArtist.ifBlank { it.artist }) to it.album }.filter { it.value.size >= 3 }
            val notFound = ArrayList<String>(); val otherYear = ArrayList<String>(); val more = ArrayList<String>()
            albums.forEach { (k, l) ->
                val c = Spotify.checked(k.first, k.second) ?: return@forEach
                if (!c.optBoolean("found")) { notFound += "${k.first} — ${k.second}"; return@forEach }
                val y = c.optString("year"); val mine = l.first().year
                if (y.isNotBlank() && mine != null && y != mine) otherYear += "${k.first} — ${k.second}: aquí $mine, Spotify $y"
                val n = c.optJSONArray("tracks")?.length() ?: 0
                if (n in 1 until l.size) more += "${k.first} — ${k.second}: aquí ${l.size}, Spotify $n («${c.optString("name")}»)"
            }
            appendLine("— Spotify — ${albums.size} álbumes; no están: ${notFound.size} · otro año: ${otherYear.size} · más canciones aquí que en Spotify: ${more.size}")
            otherYear.take(60).forEach { appendLine("  año: $it") }
            more.take(60).forEach { appendLine("  canciones: $it") }
            notFound.take(80).forEach { appendLine("  no está: $it") }
        }
        appendLine("— Carpetas —")
        rel.groupBy { it.substringBefore('/') }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (top, l) ->
            val subs = l.map { it.split('/') }.filter { it.size > 2 }.map { it.drop(1).dropLast(1).joinToString("/") }.groupingBy { it }.eachCount()
            appendLine("$top (${l.size}; ${l.count { it.count { c -> c == '/' } == 1 }} sueltas)" + if (subs.isEmpty()) "" else ": " + subs.entries.joinToString(" · ") { "${it.key} (${it.value})" })
        }
    }

    /**
     * The plan, with what it lacked looked up on the internet first: Latin names of people known only
     * in Japanese (MusicBrainz) and the openings and endings of its series (MyAnimeList). Planned
     * again only if something was found; what was found is kept for next time.
     */
    fun planLookingUp(sources: List<Source>, tracks: List<Track>, targetRoot: String): Plan {
        var plan = plan(sources, tracks, targetRoot)
        val japaneseOnly = plan.copy.map { it.albumArtist.ifBlank { it.artist } }.filter { n -> n.isNotBlank() && n.none { it in 'A'..'Z' || it in 'a'..'z' } }
        val series = plan.copy.mapNotNull { it.grouping }.groupingBy { it }.eachCount().filter { it.value >= 3 }.keys + "NANA"
        Live.phase = "buscando nombres en romaji y openings/endings de las series"; Live.write()
        val r = runCatching { Romaji.fill(japaneseOnly) }.getOrDefault(0)
        val t = runCatching { Themes.fill(series) }.getOrDefault(0)
        // Japanese artists' names as YouTube Music writes them (hl=ja, gl=JP), including Latin+Japanese pairs discovered in People.
        val yt = runCatching {
            val fromNames = plan.copy.flatMap { listOf(it.albumArtist, it.artist) }.filter { it.isNotBlank() }.distinct().mapNotNull { YtMusicNames.split(it) }
            YtMusicNames.fill((plan.scriptPairs + fromNames).distinct())
        }.getOrDefault(0)
        val a = runCatching { AlbumNames.fill(plan.japaneseAlbums) }.getOrDefault(0) + runCatching { AlbumYears.fill(plan.yearless) }.getOrDefault(0)
        val c = if (!Layout.compilationsFirst) 0 else runCatching {
            // Artists with three albums or more, biggest first; and the best-ofs already here (Pokémon's "BEST OF BEST OF BEST").
            val rel = plan.copy.map { it to it.target.removePrefix(targetRoot).trimStart('/') }
            val artists = rel.filter { (i, r) -> r.count { it == '/' } >= 2 && !r.startsWith("Otros artistas/") && i.grouping == null }
                .groupBy { (i, _) -> i.albumArtist.ifBlank { i.artist } }.mapValues { (_, l) -> l.map { it.first.album }.distinct().size }
                .filter { it.value >= 3 && it.key.isNotBlank() }.entries.sortedByDescending { it.value }.map { it.key }
            val bests = rel.filter { (i, _) -> COMPILATION.containsMatchIn(i.album) }.map { (i, r) -> i.album to (if (i.grouping != null) r.substringBefore('/') else i.albumArtist.ifBlank { i.artist }) }.distinct()
            Compilations.fill(artists, bests)
        }.getOrDefault(0)
        Live.total = 0; Live.done.set(0); Live.current = ""
        Live.phase = "planeando de nuevo con lo encontrado ($r nombres en romaji, $t series con openings/endings, $a álbumes)"; Live.write(force = true)
        // Every album folder (>= 2 tracks) checked on Spotify too (its tracklist reconstructs studio albums from compilations, its year fills gaps).
        val sp = runCatching {
            Spotify.check(plan.copy.filter { it.album.isNotBlank() }.groupBy { (it.albumArtist.ifBlank { it.artist }) to it.album }.filter { it.value.size >= 2 }.keys)
        }.getOrDefault(0)
        // With the listener's keys (Ajustes): AcoustID for songs nothing names, Last.fm for "Otros artistas", Discogs
        // for the compilations MusicBrainz lacks, fanart.tv for the artists' photos.
        val rel2 = plan.copy.map { it to it.target.removePrefix(targetRoot).trimStart('/') }
        // Songs nothing names first, then YouTube's (a video's name for a title) and the loose ones (no album).
        val ac = runCatching {
            AcoustId.fill(rel2.filter { (i, r) -> i.artist.isBlank() || r.startsWith("Sin identificar/") || YT_JUNK.containsMatchIn(File(i.track.path).name) ||
                i.album.isBlank() && i.grouping == null }
                .sortedBy { (i, r) -> if (i.artist.isBlank() || r.startsWith("Sin identificar/")) 0 else if (YT_JUNK.containsMatchIn(File(i.track.path).name)) 1 else 2 }
                .map { it.first.track.path }, minutes = 15)
        }.getOrDefault(0)
        val lf = runCatching { LastFm.fill(rel2.filter { it.second.startsWith("Otros artistas/") }.map { it.first.albumArtist.ifBlank { it.first.artist } }) }.getOrDefault(0)
        val dg = if (!Layout.compilationsFirst) 0 else runCatching {
            Discogs.fill(rel2.filter { (i, r) -> r.count { it == '/' } >= 2 && !r.startsWith("Otros artistas/") && i.grouping == null }
                .groupBy { (i, _) -> i.albumArtist.ifBlank { i.artist } }.filter { it.value.map { p -> p.first.album }.distinct().size >= 3 }.keys)
        }.getOrDefault(0)
        runCatching { Fanart.fill(rel2.filter { (i, r) -> !r.startsWith("Otros artistas/") && i.grouping == null }.groupBy { it.first.albumArtist.ifBlank { it.first.artist } }.filter { it.value.size >= 5 }.keys) }
        val found = if (Live.stop) 0 else r + t + a + c + sp + ac + lf + dg + yt
        if (found > 0) plan = plan(sources, tracks, targetRoot)
        return plan
    }

    /**
     * Writes one item: copied (or converted) from its source, or, with [from], taken from a
     * file already in the library (only its tags and place change). Tagged as planned, with
     * its album's cover. Written beside the target first, then renamed: never half a file.
     */
    fun copy(i: Item, covers: Covers?, from: File? = null, id: String? = null, coverFile: File? = null): Result<String> = runCatching {
        val out = File(i.target)
        require(!out.exists()) { "ya existe" }
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, ".part-" + out.name)
        // The path as the disk spells it now (accents in one Unicode form or the other).
        val input = (from?.path ?: i.track.path).let { p ->
            listOf(p, java.text.Normalizer.normalize(p, java.text.Normalizer.Form.NFC), java.text.Normalizer.normalize(p, java.text.Normalizer.Form.NFD)).firstOrNull { File(it).exists() } ?: p
        }
        // An approved cover first; else the album folder's picture (only for folders of one album); else the song's own.
        val cover = coverFile?.takeIf { it.isFile } ?: i.cover?.let { covers?.forFolder(it) }
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
            // The file's id for the phone (LIBRARY_LINK.md): TXXX:BITCHORD_ID.
            if (id != null) m("BITCHORD_ID", id)
        }
        val inputs = listOf("-i", input) + (cover?.let { listOf("-i", it.path) } ?: emptyList())
        // A video (a music video's .mp4): its sound only; its picture is no cover.
        val video = File(input).extension.lowercase() in setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "flv")
        val maps = if (cover != null) listOf("-map", "0:a:0", "-map", "1:v") else if (video) listOf("-map", "0:a:0") else listOf("-map", "0:a", "-map", "0:v?")
        val reencode = from == null && Formats.encodes(i.track)
        // Apple's AAC encoder takes up to 48 kHz (a 96 kHz FLAC fails with "AudioToolbox init error"); FFmpeg's own when it fails anyway.
        val rate = if (reencode && out.extension.lowercase() == "m4a" && (i.track.sampleRate ?: 0) > 48000) listOf("-ar", "48000") else emptyList()
        fun codecWith(enc: String) = if (!reencode) listOf("-c:a", "copy") else if (out.extension.lowercase() == "m4a") listOf("-c:a", enc, "-b:a", "320k") + rate else listOf("-c:a", "libmp3lame", "-b:a", "320k")
        fun argsWith(codec: List<String>) = when (out.extension.lowercase()) {
            // AAC as it came, in an .m4a with its tags and cover (no new compression).
            "m4a" -> listOf(Tools.ffmpeg, "-nostdin", "-v", "error", "-y") + inputs + maps + listOf("-c:v", "copy", "-disposition:v", "attached_pic") +
                codec + listOf("-map_metadata", "0", "-movflags", "+use_metadata_tags+faststart") + meta + listOf("-f", "mp4", tmp.path)
            else -> listOf(Tools.ffmpeg, "-nostdin", "-v", "error", "-y") + inputs + maps + listOf("-c:v", "copy", "-disposition:v", "attached_pic") +
                codec + listOf("-map_metadata", "0", "-id3v2_version", "3") + meta + listOf("-f", "mp3", tmp.path)
        }
        var (code, msg) = Tools.run(*argsWith(codecWith(Tools.aacEncoder)).toTypedArray(), timeoutSec = 300)
        if (code != 0 && reencode && Tools.aacEncoder == "aac_at" && out.extension.lowercase() == "m4a") {
            tmp.delete(); val r = Tools.run(*argsWith(codecWith("aac")).toTypedArray(), timeoutSec = 300); code = r.first; msg = r.second
        }
        if (code != 0 || tmp.length() == 0L) { tmp.delete(); error("ffmpeg ${if (code == -1) "no respondió en 5 min" else "falló"}: ${msg.take(300)}") }
        check(tmp.renameTo(out)) { "no se pudo renombrar" }
        covers?.recordPrimary(i.key, i.cover)
        out.path
    }
}
