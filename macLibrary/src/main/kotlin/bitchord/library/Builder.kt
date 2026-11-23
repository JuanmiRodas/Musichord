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
                    val japaneseAlbums: Set<String> = emptySet(), val yearless: Set<Pair<String, String>> = emptySet())

    /** A song as the plan reads it. */
    private data class Id(val title: String, val artist: String, val albumArtist: String, val various: Boolean, val album: String, val year: String?, val no: Int?, val disc: Int?)

    private data class Cand(val source: Source, val track: Track, val id: Id, val home: String, val homeKind: Int, val albumDir: String)

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
        // The .hack saga (anime and games): its soundtracks by every composer together.
        "Hack" to listOf("hack sign", "hack gu", "hack g u", "hack roots", "hack dusk", "hack liminality", "hack quantum", "hack the world", "hack infection",
            "hack mutation", "hack outbreak", "hack quarantine", "hack zero", "hack link", "hack cell", "hack extra", "hack expansion", "hack legend of the twilight",
            "hack conglomerate", "hack gu trilogy", "tasogare no udewa densetsu", "黄昏の腕輪伝説"),
    )
    /** Names too common to look for inside other names ("Toki no Tsubasa" is ZARD's, not Tsubasa Chronicle). */
    private val EXACT_ONLY = setOf("tsubasa", "ツバサ", "doremi")

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
                val roma = if (lat == null && east != null) Romaji.of(east) else null
                when { lat != null && east != null -> "$lat ($east)"; lat != null -> lat; roma != null -> "$roma ($east)"; else -> east ?: Words.nfc(name).trim() }
            }
        }
        fun group(name: String): String = pkey(name).let { if (it.isEmpty()) "" else find(it) }
    }

    private val GRAB_BAG = Regex("(?iu)^(vari[oa]s|various( artists)?|va|musique|m[uú]sica.*|music|otros.*|other.*|misc.*|comercial|rock|pop|nueva.*|mix.*|random|singles?|descargad.*|anime|animes|anime y japonadas|openings?|endings?|openings, endings.*|colaboraciones|mp3|videojuegos?|games?|juegos?|series|películas|peliculas|movies|bso|ost|espa[ñn]ol|spanish|english|ingl[eé]s|j-?pop|k-?pop|classical|cl[aá]sica|pop( .*)?|rock( .*)?|jazz|dance|electr[oó]nica?|latino?|indie|metal|chill.*|lounge|[0-9]{2}s|años [0-9]{2}|other artists|otros artistas|varios artistas|.*fandubs?.*|.*\\ben espa[ñn]a\\b.*|.*latinas?|.*\\bmix \\d+)$")

    private val REMIX = Regex("(?iu)\\b(remix(es)?|rmx|re-?mix(ed)?|mix|mega ?mix|non-stop|trance|eurobeat|orchestra|orchestral|orgel|music ?box|classical|classics|meets classics)\\b|ayu-?mi-?x|ayu-ro|リミックス|オルゴール")
    private val LIVE = Regex("(?i)\\blive\\b|concert|\\btour\\b|countdown|unplugged|コンサート|ライブ|ライヴ|\\ben directo\\b|en vivo|武道館|budokan|\\bdome\\b|arena")
    private val OST = Regex("(?i)soundtrack|sountrack|\\bost\\b|o\\.s\\.t|\\bbgm\\b|original score|music collection|音楽集|サウンドトラック|サントラ|交響詩|orgel|オルゴール|piano fantasia|brass|ブラス|music fantasy|image ?album|イメージアルバム|sound ?track|sound & song|合唱組曲")
    private val DRAMA = Regex("(?i)drama|ドラマ|radio|ラジオ|chapter\\.?\\s*\\d")
    private val SPANISH = Regex("(?iu)español|espanol|castellano|latino|spanish|fandub|\\bversi[oó]n espa|\\(es\\)|\\bes\\b.*\\b(op|ed|opening|ending)|^\\s*[a-z]?\\d+\\s+es\\s|[-_\\s]sp(\\.\\w{3,4})?$|\\(sp\\)")
    private val EDITION = Regex("(?i)\\s*[\\[(（]?\\s*(deluxe|edici[oó]n de lujo|special edition|edici[oó]n especial|limited edition|expanded edition|remaster(ed)?|bonus tracks?|disc\\s*\\d+|cd\\s*\\d+|disk\\s*\\d+)\\s*(version|edition|ed\\.?)?\\s*[\\])）]?")

    /** An album without its edition or disc ("He Perdido Los Zapatos [edición De Lujo]" = "He Perdido Los Zapatos"). */
    fun albumBase(a: String): String = clean(a.replace(EDITION, " ").replace(Regex("\\s+"), " ").trim(' ', '-', '–', '~')).ifBlank { a.trim() }
    fun albumKey(a: String): String {
        // "Slayers MEGUMIX -GOLD (スレイヤーズ MEGUMIX -GOLD)" and "スレイヤーズ MEGUMIX -GOLD" are one album:
        // a name in brackets that only says the same in Japanese is left out, and a series' Japanese name read as its Latin one.
        var b = albumBase(a)
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
        @Volatile var compilationsFirst: Boolean = runCatching { org.json.JSONObject(file.readText()).optBoolean("compilationsFirst", true) }.getOrDefault(true)
            set(v) { field = v; runCatching { file.writeText(org.json.JSONObject().put("compilationsFirst", v).toString()) } }
    }

    private val VERSION = Regex("(?iu)\\b(ver(sion)?|remix|mix|live|acoustic|instrumental|inst|edit|tv size|tv-size|off vocal|karaoke|demo|remaster(ed)?)\\b|-[a-z]-version|バージョン|ヴァージョン|リミックス|ライブ")

    const val OUTSIDE = "instrumental de banda sonora de serie"
    /** Beside the library (in its parent folder): the series' soundtrack instrumentals taken out of it. */
    const val OUTSIDE_FOLDER = "Bandas sonoras instrumentales"
    private val INST = Regex("(?iu)\\b(inst(rumental)?|bgm|off ?vocal|karaoke|orgel|music box version|piano version)\\b|インスト|オルゴール")
    private val SUNG = Regex("(?iu)\\b(vocal|song|tv size|tv-size|movie size|full size|ver\\.? ?vocal)\\b|歌|ソング|サイズ")

    /**
     * The kind of music of an artist with few songs, for "Otros artistas/<kind>/<artist>": from its
     * genre tags, its words and where it was found. As few kinds as will do.
     */
    fun kindOf(tracks: List<Track>, titles: List<String>, years: List<Int>): String {
        val genres = tracks.joinToString(" ") { it.tags["genre"].orEmpty() }.lowercase()
        val text = (titles + tracks.map { it.path.substringAfterLast("/Música").substringAfterLast("/Discografías") }).joinToString(" ")
        val japanese = CJK.containsMatchIn(text) || Regex("(?i)j-?pop|j-?rock|anime|japan|vocaloid|enka|city ?pop").containsMatchIn(genres + " " + text)
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
        val spanishPlace = Regex("(?iu)/(español|espanol|españa|movida|80 en españa|fiesta española)/").containsMatchIn(text)
        val french = Regex("(?i)french|chanson|fran[cç]ais").containsMatchIn(genres) || fr >= 2 && fr > es && fr > en
        val spanish = Regex("(?i)latin|español|espanol|spanish|flamenco|rumba|movida").containsMatchIn(genres) || (es + (if (spanishPlace) 2 else 0)) > en && es + (if (spanishPlace) 2 else 0) >= 2
        val eighties = Regex("(?iu)movida|80 en españa|ochenta").containsMatchIn(text) || years.isNotEmpty() && years.average() in 1977.0..1992.0
        val classical = Regex("(?i)classical|clásica|clasica|soundtrack|score|bso|banda sonora").containsMatchIn(genres)
        return when {
            japanese -> "JPOP"
            Regex("(?iu)movida|80 en españa").containsMatchIn(text) -> "80s en España"
            spanish && eighties -> "80s en España"
            spanish -> "Español"
            french -> "Francés"
            classical -> "Clásica y bandas sonoras"
            else -> "Inglés"
        }
    }

    private val COMPILATION = Regex("(?iu)\\b(best|greatest|hits|singles?|collection|anthology|complete|the very)\\b|ベスト|コレクション|シングルズ")

    private val DISC_DIR = Regex("(?i)^\\s*(?:disc|disk|cd|disco)\\s*0?(\\d{1,2})\\b")

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
            // "1. Title - Artist - Album.webm" (the phone's downloads, no tags), "NN - Title - Artist - Album - Year.mp3": the album from the name.
            if (album.isEmpty() && tag(t, "title").isBlank() && fn.parts.size >= 3) fn.parts.drop(2).lastOrNull { p ->
                !Regex("^\\(?(19|20)\\d{2}\\)?$").matches(p) && Words.plain(p) != Words.plain(title) && !Words.noAlbum(p) && !GRAB_BAG.matches(p.trim())
            }?.let { album = it }
            val year = t.year ?: fYear ?: dirs.asReversed().firstNotNullOfOrNull { Regex("^((19|20)\\d{2})\\b").find(it)?.groupValues?.get(1) }
            ids[t.path] = Id(title.ifBlank { File(t.path).nameWithoutExtension }, artist, albumArtist, various, album, year, t.trackNo ?: fn.no, t.discNo ?: fn.disc ?: discDir?.groupValues?.get(1)?.toIntOrNull())
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
        val sameTrack = HashMap<Pair<String, String>, Int>()
        ids.values.forEach { id ->
            val a = id.artist.trim(); val b = id.albumArtist.trim()
            if (a.isBlank() || b.isBlank() || Regex("[,&、;/×:]|\\s(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE).containsMatchIn(a + " " + b)) return@forEach
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


        // 5. Where each copy belongs: rules, a collection, or its artist.
        val cands = ArrayList<Cand>()
        for ((s, mine) in bySource) for (t in mine) {
            val id = ids[t.path] ?: continue
            if (Formats.extOf(t) == null) { skipped += t to "formato ${t.codec ?: "?"} (no se copia)"; continue }
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
        val placedCands = cands.map { c ->
            if (c.homeKind != 1) return@map c
            // On an album of one of a rules source's people but sung by someone else (ALI PROJECT on a
            // Yuki Kajiura compilation): the singer's.
            if (c.id.albumArtist.isNotBlank() && absorbedHome(c.id.albumArtist) != null && c.id.artist.isNotBlank() &&
                gid(c.id.artist) != gid(c.id.albumArtist) && absorbedHome(firstArtist(c.id.artist)) == null) return@map c.copy(id = c.id.copy(albumArtist = ""))
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
        val placedAll = byKey.mapNotNull { (k, list) ->
            val best = list.maxWith(compareBy<Cand>({ rank(it.track) }, { richness(it.track) }, { it.track.kbps }))
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
                else -> inHome.maxWith(compareBy({ if (it.id.album.isBlank()) 0 else 1 }, { rank(it.track) }, { richness(it.track) }))
            }
            Placed(k, best, chosen, home, homeKind, list)
        }
        // Fewest albums (Ajustes): in each artist's or collection's place, the albums holding the most of its songs
        // first — a best-of or a compilation takes in songs also on smaller albums, and the songs are kept there once
        // (their other albums are listed in the file's "releases" for the phone).
        val compChosen = HashMap<String, Pair<Compilations.Comp, Compilations.Track>>()
        val placed = if (!Layout.compilationsFirst) placedAll else placedAll.groupBy { (if (it.homeKind == 1) "1|" + gid(ownerOf(it.best.id)) else "${it.homeKind}|" + it.home.lowercase()) }.values.flatMap { group ->
            fun same(c: Cand, p: Placed) = c.homeKind == p.homeKind && c.home == p.home && c.id.album.isNotBlank() && (p.homeKind != 1 || gid(ownerOf(c.id)) == gid(ownerOf(p.best.id)))
            val albums = HashMap<String, MutableSet<String>>()
            group.forEach { p -> p.copies.filter { same(it, p) }.forEach { c -> albums.getOrPut(albumKey(c.id.album)) { HashSet() }.add(p.k) } }
            // Its best-ofs and compilations on MusicBrainz (looked up before planning): each song of theirs found here joins them.
            val g0 = group.first()
            val whose = if (g0.homeKind == 1) shown(ownerOf(g0.best.id)) else g0.home.substringBefore('/')
            val comps = Compilations.of(whose)
            val inComp = HashMap<String, Pair<Compilations.Comp, Compilations.Track>>() // "mb:id|k" → where
            for (c in comps) {
                val byCore = c.tracks.groupBy { Themes.core(it.title) }
                for (p in group) {
                    // "Yureru Omoi (揺れる想い)" is "揺れる想い" on MusicBrainz: the name and what it says in brackets.
                    val cores = (listOf(Themes.core(p.best.id.title)) + Regex("[(（]([^)）]+)[)）]").findAll(p.best.id.title).map { Words.plain(it.groupValues[1]) }).filter { it.length >= 2 }
                    // Another version ("B-version", "Remix", "TV size", "Live") is another song.
                    val ver = VERSION.containsMatchIn(p.best.id.title)
                    val t = cores.firstNotNullOfOrNull { byCore[it] }?.filter { VERSION.containsMatchIn(it.title) == ver }?.firstOrNull { tr -> tr.seconds == null || p.best.track.seconds == null || kotlin.math.abs(tr.seconds - p.best.track.seconds!!) <= 6 } ?: continue
                    albums.getOrPut("mb:" + c.id) { HashSet() }.add(p.k)
                    inComp["mb:${c.id}|${p.k}"] = c to t
                }
            }
            val pick = HashMap<String, String>()
            val left = group.filter { p -> p.copies.any { same(it, p) } }.map { it.k }.toMutableSet()
            while (left.isNotEmpty()) {
                val (a, ks) = albums.maxWithOrNull(compareBy({ it.value.count { k -> k in left } }, { it.value.size })) ?: break
                val take = ks.filter { it in left }
                if (take.isEmpty()) break
                take.forEach { pick[it] = a; left -= it }
                albums.remove(a)
            }
            group.map { p ->
                val a = pick[p.k] ?: return@map p
                inComp["$a|${p.k}"]?.let { (c, t) ->
                    // Kept in the compilation: its name, number and year; its cover from MusicBrainz's Cover Art Archive.
                    compChosen[p.k] = c to t
                    return@map p.copy(chosen = p.chosen.copy(id = p.chosen.id.copy(album = c.title, year = c.year ?: p.chosen.id.year, no = t.no, disc = t.disc), albumDir = c.coverFolder))
                }
                val c = p.copies.filter { same(it, p) && albumKey(it.id.album) == a }.maxWith(compareBy({ rank(it.track) }, { richness(it.track) }))
                p.copy(chosen = c)
            }
        }
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
                         val theme: Boolean = false, val artistHint: String? = null)
        // How many songs each album of a collection has here: a single's name is no album to keep ("Long Kiss Good Bye").
        val japaneseAlbums = HashSet<String>()
        val yearless = HashSet<Pair<String, String>>()
        val colAlbumSize = regrouped.filter { it.homeKind == 2 }.groupingBy { it.home.lowercase() to albumKey(it.chosen.id.album) }.eachCount()
        // "Otros artistas/<kind>/<artist>": each artist with few songs under its kind of music (JPOP, Español, Inglés…).
        val kindOfOwner = regrouped.filter { it.homeKind < 2 }.groupBy { p -> val g0 = gid(ownerOf(p.best.id).ifBlank { ownerOf(p.chosen.id) }); ownerArtist[g0] ?: g0 }
            .filterKeys { (artistSongs[it] ?: 0) < 5 }
            .mapValues { (_, l) -> kindOf(l.map { it.best.track }, l.map { it.best.id.title }, l.mapNotNull { it.best.id.year?.toIntOrNull() }) }
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
            val series = if (p.homeKind == 2) folder.substringBefore('/') else null
            // The album: never "Sueltas". Named only as its collection, folder or artist → none; in a collection, without
            // its series' name in front ("美少女戦士セーラームーン｜S 音楽集" → "S 音楽集").
            val lastHome = p.home.substringAfterLast('/')
            var album = albumBase(id.album).takeUnless { it.isBlank() || Words.plain(it) == Words.plain(lastHome) || Words.plain(it) == Words.plain(albumArtist) ||
                Words.plain(it) == Words.plain(p.home.substringBefore('/')) || GRAB_BAG.matches(it.trim()) }.orEmpty()
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
            if (series != null && (album.isEmpty() || (colAlbumSize[p.home.lowercase() to albumKey(id.album)] ?: 0) < 3))
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
            // Kinds of songs inside a collection; an artist's live albums.
            val sub = when {
                p.homeKind >= 2 && SPANISH.containsMatchIn(text + " " + p.best.track.path) && !folder.endsWith("Canciones en español") -> "Canciones en español"
                p.homeKind == 2 && DRAMA.containsMatchIn(id.album) -> "Doramas"
                p.homeKind == 2 && OST.containsMatchIn(id.album) -> "Bandas sonoras"
                p.homeKind != 2 && LIVE.containsMatchIn(id.album) && !folder.endsWith("Directos") -> "Directos"
                // Remix, trance, orchestra and music-box albums apart from the studio ones (Ayumi Hamasaki's ayu-mi-x…).
                p.homeKind != 2 && REMIX.containsMatchIn(id.album) && !folder.endsWith("Remixes") -> "Remixes"
                else -> null
            }
            if (sub != null) folder = "$folder/$sub"
            // Her soundtracks by series ("Bandas sonoras/Tsubasa Chronicle/…").
            if (folder.endsWith("/Bandas sonoras") && p.homeKind == 3) {
                // The series: the folder the source keeps its soundtracks of one series in ("OSTs/Hack/…"), else from the album's name.
                val segs = p.best.track.path.split('/')
                val k = segs.indexOfLast { Regex("(?i)^(osts?|soundtracks?|bandas sonoras|bso)$").matches(it) }
                val fromSource = if (k >= 0 && k + 2 < segs.size - 1) segs[k + 1] else null
                (fromSource ?: soundtrackSeries(id.album))?.let { folder = "$folder/${clean(it)}" }
            }
            Draft(p, folder, album, albumArtist, grouping, no, series, theme != null, theme?.artist)
        }
        // One opening (or ending) once: the best copy of each.
        val themeDupes = draftsAll.filter { it.theme }.groupBy { Triple(it.folder.lowercase(), it.album, it.no) }.values
            .flatMap { l -> l.sortedByDescending { rank(it.p.best.track) * 1000 + richness(it.p.best.track) }.drop(1) }.toSet()
        themeDupes.forEach { d -> d.p.copies.forEach { skipped += it.track to "repetida (el mismo opening o ending)" } }
        // A series' soundtrack albums keep their songs only (as Yuki Kajiura's): its composer's pieces (the artist of most
        // of the album, or none) go out of the library, to a folder beside it, until it is decided what to do with them.
        val instrumental = draftsAll.filter { it.p.homeKind == 2 && it.folder.endsWith("/Bandas sonoras") }.groupBy { it.folder.lowercase() + "|" + albumKey(it.p.chosen.id.album) }
            .values.flatMap { l ->
                val composer = l.map { pkey(firstArtist(it.p.best.id.artist)) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
                    ?.takeIf { it.value * 2 >= l.size }?.key
                l.filter { d ->
                    val a = pkey(firstArtist(d.p.best.id.artist))
                    val t = d.p.best.id.title
                    INST.containsMatchIn(t) || !SUNG.containsMatchIn(t) && (a.isEmpty() || a == composer)
                }
            }.toSet()
        instrumental.forEach { d -> d.p.copies.forEach { skipped += it.track to "$OUTSIDE (fuera de la Biblioteca)" } }
        val drafts = draftsAll.filter { it !in themeDupes && it !in instrumental }
        // Collections of fewer than 10 songs from anime go together in "Anime".
        val seriesSize = drafts.filter { it.series != null }.groupingBy { it.series!! }.eachCount()
        val animeSeries = drafts.filter { it.series != null && (seriesSize[it.series] ?: 0) < 10 }.groupBy { it.series!! }
            .filter { (_, l) -> l.any { d -> Regex("(?i)/anime/|\\banime\\b|\\b(op|ed|opening|ending)\\b").containsMatchIn(d.p.best.track.path) || CJK.containsMatchIn(d.p.chosen.id.album + d.p.best.id.title) } }.keys
        val placedDrafts = drafts.map { d -> if (d.series in animeSeries) d.copy(folder = "Anime/" + d.folder) else d }
        // One year per album; an album folder only for 3 songs or more (fewer: the songs at the folder's root).
        val albumYear = placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }.mapValues { (_, l) ->
            l.mapNotNull { it.p.chosen.id.year ?: it.p.best.id.year }.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
                // No copy says the year: the one MusicBrainz gives for that album (looked up before planning).
                ?: l.firstOrNull()?.let { d -> AlbumYears.of(d.albumArtist.ifBlank { d.p.best.id.artist }, d.album) }
        }
        placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }.forEach { (k, l) ->
            if (k.second.isNotEmpty() && albumYear[k] == null && l.size >= 3) l.first().let { d -> yearless += (d.albumArtist.ifBlank { d.p.best.id.artist }) to d.album }
        }
        val albumCount = placedDrafts.groupingBy { it.folder.lowercase() to albumKey(it.album) }.eachCount()
        // The album's name as most copies spell it, preferring the one in both scripts ("Slayers MEGUMIX -GOLD (スレイヤーズ MEGUMIX -GOLD)").
        val albumShown = placedDrafts.groupBy { it.folder.lowercase() to albumKey(it.album) }.mapValues { (_, l) ->
            l.groupingBy { it.album }.eachCount().maxWith(compareBy({ e -> if (latin(e.key.replace(CJK, "")) && CJK.containsMatchIn(e.key) && e.key.any { it in 'A'..'z' }) 1 else 0 }, { it.value })).key
        }
        val alternatives = HashMap<String, List<String>>()
        val items = placedDrafts.map { d ->
            val p = d.p
            val t = p.best.track
            val id = p.chosen.id
            val k = d.folder.lowercase() to albumKey(d.album)
            val album = if (d.album.isEmpty()) "" else albumShown[k] ?: d.album
            val year = if (album.isEmpty()) (id.year ?: p.best.id.year) else albumYear[k]
            // A collection's name with an edition is no singer ("Operación triunfo 1a ed").
            fun noSinger(a: String) = d.series != null && (Words.plain(a) == Words.plain(d.series) ||
                Words.plain(a).startsWith(Words.plain(d.series)) && Regex("(?iu)\\b\\d+\\s*(a|er|ª|º|st|nd|rd|th)?\\s*(ed|edici[oó]n|disco|edition)\\b").containsMatchIn(a))
            val rawArtist = p.best.id.artist.ifBlank { id.artist }.takeUnless { noSinger(it) }.orEmpty().ifBlank { d.artistHint.orEmpty() }
            val artist = if (Regex("[,&、;/×]|\\s(feat|ft)\\.?\\s", RegexOption.IGNORE_CASE).containsMatchIn(rawArtist)) Words.nfc(rawArtist) else shown(rawArtist)
            val ownFolder = album.isNotEmpty() && (albumCount[k] ?: 0) >= 3
            val albumDir = if (ownFolder) clean(listOfNotNull(year, album).joinToString(" - ")) else ""
            val num = d.no?.let { n -> (id.disc?.takeIf { it > 1 }?.let { "$it-" } ?: "") + n.toString().padStart(2, '0') }
            val title = p.best.id.title
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
        val seen = HashMap<String, Int>()
        val unique = items.sortedBy { it.target }.map { i ->
            val n = seen.merge(i.target.lowercase(), 1, Int::plus)!!
            if (n == 1) i else i.target.substringAfterLast('.').let { e -> i.copy(target = i.target.removeSuffix(".$e") + " ($n).$e") }
        }
        return Plan(unique, skipped, alternatives, keyOf, japaneseAlbums, yearless)
    }

    /** The plan in words, for the report: each top folder and what is in it, what is left out and why. */
    fun overview(plan: Plan, root: String): String = buildString {
        val rel = plan.copy.map { it.target.removePrefix(root).trimStart('/') }
        appendLine("Plan: ${rel.size} canciones en ${rel.map { it.substringBefore('/') }.distinct().size} carpetas")
        appendLine("Sin álbum: ${plan.copy.count { it.album.isBlank() }} · sin artista: ${plan.copy.count { it.artist.isBlank() }} · sin identificar: ${rel.count { it.startsWith("Sin identificar/") }}")
        appendLine("Álbumes solo en japonés aún sin nombre latino: ${plan.japaneseAlbums.size}")
        appendLine("No se copian ${plan.skipped.size}: " + plan.skipped.groupingBy { it.second.substringBefore(":") }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.value} ${it.key}" })
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
        val series = plan.copy.mapNotNull { it.grouping }.groupingBy { it }.eachCount().filter { it.value >= 3 }.keys
        Live.phase = "buscando nombres en romaji y openings/endings de las series"; Live.write()
        val r = runCatching { Romaji.fill(japaneseOnly) }.getOrDefault(0)
        val t = runCatching { Themes.fill(series) }.getOrDefault(0)
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
        val found = if (Live.stop) 0 else r + t + a + c
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
        val maps = if (cover != null) listOf("-map", "0:a", "-map", "1:v") else listOf("-map", "0:a", "-map", "0:v?")
        val reencode = from == null && Formats.encodes(i.track)
        val codec = if (!reencode) listOf("-c:a", "copy") else if (out.extension.lowercase() == "m4a") listOf("-c:a", Tools.aacEncoder, "-b:a", "320k") else listOf("-c:a", "libmp3lame", "-b:a", "320k")
        val args = when (out.extension.lowercase()) {
            // AAC as it came, in an .m4a with its tags and cover (no new compression).
            "m4a" -> listOf(Tools.ffmpeg, "-nostdin", "-v", "error", "-y") + inputs + maps + listOf("-c:v", "copy", "-disposition:v", "attached_pic") +
                codec + listOf("-map_metadata", "0", "-movflags", "+use_metadata_tags+faststart") + meta + listOf("-f", "mp4", tmp.path)
            else -> listOf(Tools.ffmpeg, "-nostdin", "-v", "error", "-y") + inputs + maps + listOf("-c:v", "copy", "-disposition:v", "attached_pic") +
                codec + listOf("-map_metadata", "0", "-id3v2_version", "3") + meta + listOf("-f", "mp3", tmp.path)
        }
        val (code, msg) = Tools.run(*args.toTypedArray(), timeoutSec = 300)
        if (code != 0 || tmp.length() == 0L) { tmp.delete(); error("ffmpeg ${if (code == -1) "no respondió en 5 min" else "falló"}: ${msg.take(300)}") }
        check(tmp.renameTo(out)) { "no se pudo renombrar" }
        covers?.recordPrimary(i.key, i.cover)
        out.path
    }
}
