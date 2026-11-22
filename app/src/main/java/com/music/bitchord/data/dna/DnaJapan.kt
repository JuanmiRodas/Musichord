package com.music.bitchord.data.dna

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.CrossScript
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: the song's DNA for Japanese music, anime, games and their series —
 * where Spotify's SongDNA (and the big general sources) know little or nothing.
 *
 *  - **VGMdb** (through vgmdb.info's JSON): anime, game and J-pop releases — catalogue
 *    number, date, label, the tie-in (product), performers, composers, arrangers,
 *    lyricists, notes (often per track: 作詞 / 作曲 / 編曲) and every scan.
 *  - **pocketmonsters.net**: every Pokémon CD, its track lengths, staff and scans.
 *  - **canta-per-me.net**: Yuki Kajiura and her projects (FictionJunction, Kalafina,
 *    See-Saw…), with per-track vocals and musicians.
 *  - **niconico 大百科**: the 楽曲情報 table of Japanese songs (作詞, 作曲, 編曲, 主題歌).
 *  - **Generasia**: J-pop discographies and tie-ins.
 *  - **The series' own wikis** (Bulbapedia, Wikimon, the Fandom wikis of Sailor Moon,
 *    Card Captor Sakura, One Piece, Naruto, Digimon, Dragon Ball, Tales of… — see
 *    [WIKIS]): their song pages' infoboxes and introductions. A wiki is only asked
 *    when the song is plainly of its series (its title, album, file path, tie-ins).
 *
 * Asked after the general sources, for songs that are Japanese, of an anime, a game or
 * a series (niconico and Generasia also for those the general sources found no
 * writer for). "Very sure" before
 * anything is taken as this song's: on a release, a track with the same title (in
 * any script) and the same length (±3 s); with no length known, the artist must be
 * on it too. A wiki page must be titled as the song and name its artist; what it says
 * of a release (date) only counts when its length agrees.
 *
 * Each site is asked at most once a second (fan sites are small); a failure, a
 * rate limit or a server error is thrown (the source is then "didn't answer" and
 * asked again in 2 days, as with the rest of the DNA). Indexes of the fan sites (their
 * whole CD lists) are kept on the phone for a month, built on Wi-Fi only.
 */
internal object DnaJapan {

    private const val TAG = "BitChord"
    private const val AGENT = "BitChordAudio/1.9 ( https://github.com/JuanmiRodas/Musichord )"
    private const val INDEX_MS = 30L * 24 * 60 * 60 * 1000

    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "dna-index").apply { mkdirs() }
    }

    // ── Which songs, which sources ─────────────────────────────────────────

    /** A wiki of a series: its api.php, and the words that name the series in any script. */
    data class Wiki(val name: String, val api: String, val page: String, val keys: List<String>)

    private fun fandom(name: String, sub: String, vararg keys: String) =
        Wiki(name, "https://$sub.fandom.com/api.php", "https://$sub.fandom.com/wiki/", keys.toList())

    /** The series' wikis. Sources, not songs: each asked only when a song is of its series. */
    val WIKIS: List<Wiki> = listOf(
        Wiki("Bulbapedia", "https://bulbapedia.bulbagarden.net/w/api.php", "https://bulbapedia.bulbagarden.net/wiki/",
            listOf("pokemon", "pokémon", "ポケモン", "ポケットモンスター", "pocket monsters", "pikachu", "ピカチュウ")),
        Wiki("Wikimon", "https://wikimon.net/api.php", "https://wikimon.net/", listOf("digimon", "デジモン")),
        fandom("Digimon Wiki", "digimon", "digimon", "デジモン"),
        fandom("Sailor Moon Wiki", "sailormoon", "sailor moon", "sailormoon", "セーラームーン", "bishoujo senshi", "美少女戦士"),
        fandom("Cardcaptor Sakura Wiki", "ccsakura", "card captor", "cardcaptor", "カードキャプター", "clear card", "ccさくら"),
        fandom("One Piece Wiki", "onepiece", "one piece", "onepiece", "ワンピース"),
        fandom("Naruto Wiki", "naruto", "naruto", "ナルト", "boruto", "ボルト"),
        fandom("Dragon Ball Wiki", "dragonball", "dragon ball", "dragonball", "ドラゴンボール"),
        fandom("Aselia (Tales of)", "aselia", "tales of", "テイルズ"),
        fandom("Gundam Wiki", "gundam", "gundam", "ガンダム"),
        fandom("Evangelion Wiki", "evangelion", "evangelion", "エヴァンゲリオン", "エヴァ"),
        fandom("Detective Conan Wiki", "detectiveconan", "detective conan", "case closed", "meitantei conan", "名探偵コナン", "コナン"),
        fandom("Inuyasha Wiki", "inuyasha", "inuyasha", "犬夜叉"),
        fandom("Saint Seiya Wiki", "saintseiya", "saint seiya", "聖闘士星矢"),
        fandom("Yu-Gi-Oh! Wiki", "yugioh", "yu-gi-oh", "yugioh", "遊☆戯☆王", "遊戯王"),
        fandom("Bleach Wiki", "bleach", "bleach", "ブリーチ"),
        fandom("Fullmetal Alchemist Wiki", "fma", "fullmetal", "full metal alchemist", "鋼の錬金術師", "hagane no renkinjutsushi"),
        fandom("Sword Art Online Wiki", "swordartonline", "sword art online", "ソードアート"),
        fandom("Macross Wiki", "macross", "macross", "マクロス"),
        fandom("Kingdom Hearts Wiki", "kingdomhearts", "kingdom hearts", "キングダムハーツ", "キングダム ハーツ"),
        fandom("Final Fantasy Wiki", "finalfantasy", "final fantasy", "ファイナルファンタジー"),
        fandom("Megami Tensei Wiki", "megamitensei", "persona", "ペルソナ", "megami tensei", "女神転生"),
        fandom("Puella Magi Wiki", "madoka", "madoka", "まどか"),
        fandom("TYPE-MOON Wiki", "typemoon", "fate/", "fate stay", "fate zero", "type-moon", "空の境界", "kara no kyoukai"),
        fandom("Hunter × Hunter Wiki", "hunterxhunter", "hunter x hunter", "hunter×hunter", "ハンター×ハンター"),
        fandom("Love Live! Wiki", "love-live", "love live", "ラブライブ"),
        fandom("Magic Knight Rayearth Wiki", "rayearth", "rayearth", "レイアース"),
        fandom("Slayers Wiki", "slayers", "slayers", "スレイヤーズ"),
    )

    private val POKEMON = listOf("pokemon", "pokémon", "ポケモン", "ポケットモンスター", "pocket monsters", "pikachu", "ピカチュウ")
    private val KAJIURA = listOf(
        "kajiura", "梶浦", "fictionjunction", "fiction junction", "kalafina", "see-saw", "seesaw", "saeko chiba", "千葉紗子",
    )
    private val ANIME_WORDS = Regex(
        """(?i)\b(anime|anison|soundtrack|ost|vgm|game|j-?pop|j-?rock|japanese|city pop|enka|kayou?kyoku|idol|vocaloid|visual kei|seiyuu|opening|ending|theme|lang (?:ja|jpn))\b|声優|主題歌|挿入歌|サウンドトラック|アニメ""",
    )

    /** Everything that can say what series a song is of: names, album, file path, tie-ins, genres. */
    fun contextOf(song: Song, album: String?, uses: List<String>, genres: List<String>): String {
        val path = WebDavConfig.fileUrlOf(song.videoId)
            ?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            ?.substringAfter("://")?.substringAfter('/').orEmpty()
        return listOfNotNull(song.title, song.artist, song.albumName, album, path, uses.joinToString(" "), genres.joinToString(" "))
            .joinToString(" \n ").lowercase()
    }

    private fun mentions(context: String, keys: List<String>) = keys.any { it.lowercase() in context }

    /** Whether this song is of the kind these sources know (Japanese, anime, games, a series). */
    fun relevant(song: Song, context: String, uses: List<String>, credits: List<SongDna.Credit>): Boolean =
        CrossScript.hasJapanese(song.title + song.artist + (song.albumName ?: "")) ||
            uses.isNotEmpty() || ANIME_WORDS.containsMatchIn(context) ||
            WIKIS.any { mentions(context, it.keys) } || mentions(context, KAJIURA) ||
            credits.any { CrossScript.hasJapanese(it.name) }

    /** The sources for this song, each as a name and its look-up. */
    fun sourcesFor(
        song: Song,
        context: String,
        album: String?,
        credits: List<SongDna.Credit>,
        noWriters: Boolean,
    ): List<Pair<String, suspend () -> SongDna.Found?>> {
        val titles = SongDna.titles(song.title)
        val artist = SongDna.firstArtist(song.artist)
        val seconds = SongDna.secondsOf(song.durationText)
        val number = Regex("""^(\d{1,2})\s*[-.]""").find(
            WebDavConfig.fileUrlOf(song.videoId)?.substringAfterLast('/')
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty(),
        )?.groupValues?.get(1)?.toIntOrNull()
        val albums = listOfNotNull(album, song.albumName).map { it.trim() }.filter { it.length >= 2 }.distinct()
        val japanese = CrossScript.hasJapanese(song.title + song.artist)
        val out = ArrayList<Pair<String, suspend () -> SongDna.Found?>>()
        out += "VGMdb" to suspend { vgmdb(titles, artist, seconds, albums) }
        if (mentions(context, POKEMON)) out += "pocketmonsters.net" to suspend { pokemon(titles, artist, seconds, albums) }
        if (mentions(context, KAJIURA) || credits.any { mentions(it.name.lowercase(), KAJIURA) }) {
            out += "canta-per-me.net" to suspend { kajiura(titles, artist, seconds, albums, number) }
        }
        WIKIS.filter { mentions(context, it.keys) }.forEach { w -> out += w.name to suspend { wiki(w, titles, artist, seconds) } }
        if (japanese || noWriters) {
            out += "niconico 大百科" to suspend { nicoPedia(titles, artist) }
            out += "Generasia" to suspend { wiki(GENERASIA, titles, artist, seconds) }
        }
        return out
    }

    private val GENERASIA = Wiki("Generasia", "https://www.generasia.com/w/api.php", "https://www.generasia.com/wiki/", emptyList())

    // ── HTTP: one request a second per site ───────────────────────────────

    private val http by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val lastCall = ConcurrentHashMap<String, Long>()

    /**
     * The body, or null when there is nothing there (404, a site that doesn't exist). A
     * network failure, a rate limit or a server error is thrown: the source didn't answer.
     */
    private suspend fun get(url: String, gapMs: Long = 1_000, accept: String = "application/json, text/html;q=0.9"): String? {
        val host = runCatching { java.net.URL(url).host }.getOrDefault(url)
        return hostLocks.getOrPut(host) { Mutex() }.withLock {
            val wait = (lastCall[host] ?: 0L) + gapMs - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastCall[host] = System.currentTimeMillis()
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", accept).build()
                try {
                    http.newCall(req).execute().use { r ->
                        when {
                            r.isSuccessful -> r.body?.string()
                            r.code == 429 || r.code >= 500 -> throw java.io.IOException("HTTP ${r.code} from $host")
                            else -> null
                        }
                    }
                } catch (e: java.net.UnknownHostException) {
                    null // A wiki that moved or closed has nothing, it hasn't "failed".
                }
            }
        }
    }

    private suspend fun json(url: String, gapMs: Long = 1_000): JSONObject? =
        get(url, gapMs)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── Matching ("very sure") ─────────────────────────────────────────────

    private fun mmss(s: String?): Int? = Regex("""^\s*(?:(\d+):)?(\d{1,2}):(\d{2})\s*$""").find(s ?: "")?.let { m ->
        (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
    }

    private fun sameTitle(titles: List<String>, got: String): Boolean {
        val g = got.replace(Regex("""\s*[～~〜].*?[～~〜]\s*$"""), "").trim()
        return SongDna.sameTitle(titles, got) || SongDna.sameTitle(titles, g) ||
            titles.any { t -> runCatching { CrossScript.sameTitle(t, got) || CrossScript.sameTitle(t, g) }.getOrDefault(false) } ||
            // "Kanata 「彼方」": either part.
            Regex("""[「『](.+?)[」』]""").find(got)?.groupValues?.get(1)?.let { SongDna.sameTitle(titles, it) } == true
    }

    private fun sameArtist(artist: String, got: String): Boolean =
        got.isNotBlank() && (SongDna.sameArtist(artist, got) || runCatching { CrossScript.sameArtist(artist, got) }.getOrDefault(false))

    /** A track of the release is this song: same title, and same length (±3 s) when both are known. */
    private fun sureTrack(titles: List<String>, seconds: Int?, title: String, length: Int?): Boolean =
        sameTitle(titles, title) && (seconds == null || length == null || kotlin.math.abs(seconds - length) <= 3)

    /** "Kanata 「彼方」 instrumental", "(TV size)", "off vocal": another version of the song, not it. */
    private fun otherVersion(ours: String, theirs: String): Boolean {
        val words = Regex("""(?i)instrumental|off ?vocal|karaoke|tv ?size|tv ?ver|movie ?ver|short ?ver|inst\.|カラオケ|オフボーカル""")
        return words.containsMatchIn(theirs) && !words.containsMatchIn(ours)
    }

    // ── Credits written as text ("作詞：X / 作曲：Y", "Lyrics by X", "Music: X (1, 2)") ──

    private val ROLE_WORDS = mapOf(
        "作詞" to "lyricist", "作曲" to "composer", "編曲" to "arranger", "歌" to "vocals", "唄" to "vocals",
        "ボーカル" to "vocals", "演奏" to "performer", "作詞・作曲" to "writer", "作詞作曲" to "writer",
        "プロデュース" to "producer", "ストリングス" to "strings", "ギター" to "guitar", "ベース" to "bass",
        "ドラム" to "drums", "ピアノ" to "piano", "キーボード" to "keyboards", "コーラス" to "chorus",
    )

    /** Infobox keys and labels that are not a person's role ("soundtrack" is not sound engineering). */
    private val SKIP_KEYS = Regex("""(?i)track|album|series|anime|single|chronolog|prev|next|length|duration|date|release|format|genre|title|name|image|caption|cover|catalog|label|studio|location|recorded|b-side|language|episode""")

    /** "Role: Name, Name (1, 2)" and "Role by Name" lines, kept when they name this track (or every track). */
    private fun roleLines(text: String, source: String, track: Int? = null): List<SongDna.Credit> {
        val out = ArrayList<SongDna.Credit>()
        for (raw in text.split('\n')) {
            val line = raw.trim().trim('•', '-', '*', '·').trim()
            if (line.length < 4 || line.length > 300) continue
            val m = Regex("""^(.{2,48}?)\s*(?:[:：]|\s+by\s+)\s*(.+)$""", RegexOption.IGNORE_CASE).find(line) ?: continue
            var role = m.groupValues[1].trim().removeSuffix(" by")
            role = ROLE_WORDS[role] ?: role
            if (DnaRoles.group(role) == DnaRoles.Group.OTHER && role.lowercase() !in setOf("music", "lyrics", "words", "vocal", "vocals")) continue
            if (SKIP_KEYS.containsMatchIn(role)) continue
            for (part in m.groupValues[2].split(Regex("""\s*(?:,(?![^()]*\))|、|/|／|&|＆|\band\b)\s*"""))) {
                val n = Regex("""^(.+?)\s*[(（]([\d,\s\-–allALL]+)[)）]\s*$""").find(part.trim())
                val name = (n?.groupValues?.get(1) ?: part).trim().trim('"', '「', '」')
                val tracks = n?.groupValues?.get(2)
                if (track != null && tracks != null && !tracks.contains("all", true)) {
                    val nums = tracks.split(',').flatMap { t ->
                        val r = t.trim().split('-', '–').mapNotNull { it.trim().toIntOrNull() }
                        if (r.size == 2) (r[0]..r[1]).toList() else r
                    }
                    if (track !in nums) continue
                }
                if (name.length < 2 || name.length > 60 || name.any { it == '=' || it == '|' } || name.none { it.isLetter() }) continue
                val r = when (role.lowercase()) { "music" -> "composer"; "lyrics", "words" -> "lyricist"; "vocal" -> "vocals"; else -> role }
                out += SongDna.Credit(r, name, source)
            }
        }
        return out
    }

    // ── VGMdb ──────────────────────────────────────────────────────────────

    private fun names(o: Any?): List<String> = when (o) {
        is String -> listOf(o)
        is JSONObject -> o.keys().asSequence().map { o.optString(it) }.filter { it.isNotBlank() }.toList()
        else -> emptyList()
    }

    /** "artist/77" people of a VGMdb list ("performers", "composers"…). */
    private fun vgPeople(a: JSONArray?, role: String, into: SongDna.Found) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val x = a.opt(i)
            val (name, link) = when (x) {
                is String -> x to null
                is JSONObject -> {
                    val n = x.optJSONObject("names")
                    (n?.optString("ja")?.takeIf { it.isNotBlank() && CrossScript.hasJapanese(it) }?.let { ja ->
                        n.optString("en").takeIf { it.isNotBlank() && it != ja }?.let { "$it ($ja)" } ?: ja
                    } ?: n?.optString("en")?.ifBlank { null } ?: names(n).firstOrNull() ?: x.optString("name")) to
                        x.optString("link").ifBlank { null }
                }
                else -> continue
            }
            if (name.isBlank()) continue
            into.credits += SongDna.Credit(role, name.trim(), "VGMdb", vgmdbId = link)
        }
    }

    private suspend fun vgmdb(titles: List<String>, artist: String, seconds: Int?, albums: List<String>): SongDna.Found? {
        val queries = (albums + titles.take(2)).distinct().take(3)
        val links = LinkedHashSet<String>()
        for (q in queries) {
            val r = json("https://vgmdb.info/search/albums/${enc(q).replace("+", "%20")}?format=json")
                ?.optJSONObject("results")?.optJSONArray("albums") ?: continue
            for (i in 0 until minOf(r.length(), 4)) r.optJSONObject(i)?.optString("link")?.takeIf { it.startsWith("album/") }?.let { links += it }
            if (links.size >= 6) break
        }
        for (link in links.take(6)) {
            val o = json("https://vgmdb.info/$link?format=json") ?: continue
            // The track: the same title (in any of its languages) and length.
            var hit: Pair<String, Int?>? = null
            var trackCount = 0
            var position = 0
            val discs = o.optJSONArray("discs")
            for (d in 0 until (discs?.length() ?: 0)) {
                val tracks = discs!!.optJSONObject(d)?.optJSONArray("tracks") ?: continue
                for (t in 0 until tracks.length()) {
                    val tr = tracks.optJSONObject(t) ?: continue
                    trackCount++
                    val all = names(tr.optJSONObject("names") ?: tr.opt("names"))
                    val len = mmss(tr.optString("track_length"))
                    if (hit == null) {
                        val name = all.firstOrNull { sureTrack(titles, seconds, it, len) && !otherVersion(titles.first(), it) }
                        if (name != null) { hit = name to len; position = trackCount }
                    }
                }
            }
            val h = hit ?: continue
            val performers = o.optJSONArray("performers")
            if (seconds == null && h.second == null) {
                // No length to go by: the artist must be on it.
                val people = (0 until (performers?.length() ?: 0)).flatMap { names(performers!!.optJSONObject(it)?.optJSONObject("names")) }
                if (people.none { sameArtist(artist, it) }) continue
            }
            val f = SongDna.Found()
            f.sources += "VGMdb"
            f.links += SongDna.Link("VGMdb", "https://vgmdb.net/$link")
            f.album = names(o.optJSONObject("names")).let { n -> o.optJSONObject("names")?.optString("ja")?.ifBlank { null } ?: n.firstOrNull() }
                ?: o.optString("name").ifBlank { null }
            f.catalog = o.optString("catalog").takeIf { it.isNotBlank() && !it.equals("N/A", true) }
            o.optString("release_date").ifBlank { null }?.let { f.releaseDate = it }
            o.optJSONArray("organizations")?.let { orgs ->
                for (i in 0 until orgs.length()) {
                    val g = orgs.optJSONObject(i) ?: continue
                    if (g.optString("role").contains("label", true) || g.optString("role").contains("publish", true)) {
                        val n = (0 until (g.optJSONArray("names")?.length() ?: 0)).firstNotNullOfOrNull { k ->
                            names(g.optJSONArray("names")?.optJSONObject(k)?.optJSONObject("names")).firstOrNull()
                        } ?: names(g.optJSONObject("names")).firstOrNull()
                        if (n != null) { f.label = n; break }
                    }
                }
            }
            o.optJSONArray("products")?.let { p ->
                for (i in 0 until p.length()) {
                    val x = p.opt(i)
                    val n = if (x is JSONObject) names(x.optJSONObject("names")).let { l -> l.firstOrNull { CrossScript.hasJapanese(it) }?.let { ja -> l.firstOrNull { !CrossScript.hasJapanese(it) }?.let { "$it ($ja)" } ?: ja } ?: l.firstOrNull() } else x as? String
                    n?.takeIf { it.isNotBlank() }?.let { f.uses += it }
                }
            }
            o.optJSONArray("categories")?.let { c -> for (i in 0 until c.length()) c.optString(i).ifBlank { null }?.let { f.genres += it } }
            o.optString("classification").split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { f.genres += it }
            // The notes, then the people: the notes' own block for this track first (作詞・作曲・編曲).
            val notes = o.optString("notes").replace("\r", "")
            val block = notesFor(notes, titles, position)
            if (block != null) f.credits += roleLines(block, "VGMdb").map { it.copy(source = "VGMdb") }
            // The release's lists only when they are this song's: a single or a short release.
            if (trackCount <= 6) {
                vgPeople(performers, "performer", f)
                vgPeople(o.optJSONArray("composers"), "composer", f)
                vgPeople(o.optJSONArray("arrangers"), "arranger", f)
                vgPeople(o.optJSONArray("lyricists"), "lyricist", f)
            } else {
                o.optJSONArray("composers")?.takeIf { it.length() == 1 }?.let { vgPeople(it, "composer", f) }
            }
            if (notes.length > 40 && block == null) f.facts += SongDna.Fact(SongDna.clip(notes, 900), "VGMdb", "https://vgmdb.net/$link")
            o.optJSONArray("covers")?.let { c ->
                for (i in 0 until minOf(c.length(), 12)) {
                    val x = c.optJSONObject(i) ?: continue
                    val u = x.optString("full").ifBlank { x.optString("medium") }.ifBlank { null } ?: continue
                    f.covers += SongDna.Cover(u, "VGMdb · " + x.optString("name").ifBlank { "Cover" })
                }
            }
            o.optString("picture_full").ifBlank { null }?.let { if (f.covers.none { c -> c.url == it }) f.covers.add(0, SongDna.Cover(it, "VGMdb")) }
            return f
        }
        return null
    }

    /** The part of a release's notes about one track ("M-2 「…」 / 作詞：…"), or null. */
    private fun notesFor(notes: String, titles: List<String>, position: Int): String? {
        if (notes.isBlank()) return null
        val lines = notes.split('\n')
        val header = Regex("""^\s*(?:M-?|Tr(?:ack)?\.?\s*|#|Disc\s*\d+\s*[-–]\s*)?0?(\d{1,2})[.):\s]""", RegexOption.IGNORE_CASE)
        var start = -1
        for ((i, l) in lines.withIndex()) {
            val n = header.find(l)?.groupValues?.get(1)?.toIntOrNull()
            if ((n == position && position > 0) || (l.length < 120 && sameTitle(titles, l.replace(header, "").trim().trim('「', '」', '"')))) {
                start = i; break
            }
        }
        if (start < 0) return null
        val out = StringBuilder()
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (header.containsMatchIn(l) && i > start + 1 && l.trim().isNotEmpty() && !l.contains(':') && !l.contains('：')) break
            if (l.isBlank() && out.isNotEmpty()) break
            out.appendLine(l)
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    // ── pocketmonsters.net (Pokémon CDs) ──────────────────────────────────

    private val buildScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val building = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<Pair<String, String>>>>()

    private fun readIndex(f: File?): Pair<Long, List<Pair<String, String>>>? = runCatching {
        val o = JSONObject(f!!.readText())
        val a = o.optJSONArray("rows") ?: JSONArray()
        o.optLong("at") to (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let { r -> r.optString(0) to r.optString(1) } }
    }.getOrNull()

    /**
     * A site's whole list of releases ("id or URL" → its row's text), kept on the phone for
     * a month. Built on Wi-Fi only, on its own (a song's look-up giving up doesn't stop it,
     * so the list is there next time).
     */
    private suspend fun index(name: String, build: suspend () -> List<Pair<String, String>>): List<Pair<String, String>> {
        val f = dir?.let { File(it, "$name.json") }
        val kept = f?.takeIf { it.isFile }?.let(::readIndex)
        if (kept != null && System.currentTimeMillis() - kept.first < INDEX_MS) return kept.second
        if (AppSettings.meteredConnection.value != false) {
            return kept?.second ?: throw java.io.IOException("$name: its list waits for Wi-Fi")
        }
        val job = building.getOrPut(name) {
            buildScope.async {
                try {
                    val rows = build()
                    if (rows.isNotEmpty()) runCatching {
                        f?.writeText(JSONObject().put("at", System.currentTimeMillis()).put("rows", JSONArray(rows.map { JSONArray(listOf(it.first, it.second)) })).toString())
                    }
                    TrackLog.d(TAG, "song dna: $name list read (${rows.size} releases)")
                    rows
                } finally {
                    building.remove(name)
                }
            }
        }
        val rows = job.await()
        return rows.ifEmpty { kept?.second ?: throw java.io.IOException("$name: its list could not be read") }
    }

    private fun text(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h\\d)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("[ \\t]+"), " ").replace(Regex(" *\n *"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun pokemon(titles: List<String>, artist: String, seconds: Int?, albums: List<String>): SongDna.Found? {
        val list = index("pocketmonsters") {
            val out = ArrayList<Pair<String, String>>()
            for (page in 1..80) {
                val html = get("https://pocketmonsters.net/cd?page=$page", 1_500, "text/html") ?: break
                val rows = Regex("(?s)<tr.*?</tr>").findAll(html).map { it.value }.filter { it.contains("/cd/view/") }.toList()
                if (rows.isEmpty()) break
                rows.forEach { r -> Regex("/cd/view/(\\d+)").find(r)?.let { out += it.groupValues[1] to text(r).replace('\n', ' ') } }
            }
            out.distinctBy { it.first }
        }
        val wants = (titles + albums).map(::plain).filter { it.length >= 3 }
        val candidates = list.filter { (_, row) -> val p = plain(row); wants.any { p.contains(it) } }.take(6)
        for ((id, _) in candidates) {
            val html = get("https://pocketmonsters.net/cd/view/$id", 1_500, "text/html") ?: continue
            val tracks = Regex("(?s)<tr[^>]*>(.*?)</tr>").findAll(html).mapNotNull { row ->
                val cells = Regex("(?s)<t[dh][^>]*>(.*?)</t[dh]>").findAll(row.groupValues[1]).map { text(it.groupValues[1]).replace('\n', ' ').trim() }.toList()
                val len = cells.firstNotNullOfOrNull { mmss(it) } ?: return@mapNotNull null
                val title = cells.filter { mmss(it) == null && it.toIntOrNull() == null && it.isNotBlank() }.maxByOrNull { it.length } ?: return@mapNotNull null
                title to len
            }.toList()
            val pos = tracks.indexOfFirst { (t, l) -> sureTrack(titles, seconds, t, l) && !otherVersion(titles.first(), t) }
            if (pos < 0) continue
            val page = text(html)
            fun field(label: String) = Regex("""(?m)^\s*$label\s*:?\s*(.+)$""", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val artists = field("Artists?") ?: field("Music Group") ?: field("Singer")
            if (seconds == null && (artists == null || !sameArtist(artist, artists))) continue
            val f = SongDna.Found()
            f.sources += "pocketmonsters.net"
            f.links += SongDna.Link("pocketmonsters.net", "https://pocketmonsters.net/cd/view/$id")
            f.album = Regex("(?s)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.let(::text)?.ifBlank { null }
            f.catalog = field("Catalog #")?.takeIf { it.length < 40 }
            field("Release Date")?.takeIf { it.take(4).all(Char::isDigit) }?.let { f.releaseDate = it }
            artists?.let { f.credits += SongDna.Credit("performer", it, "pocketmonsters.net") }
            // Staff, between "Extra Staff" and the track list; the description before it.
            val staff = page.substringAfter("Extra Staff", "").substringBefore("Track Listing")
            if (staff.isNotBlank()) {
                f.credits += roleLines(staff, "pocketmonsters.net")
                // "Drum CONA": an instrument and a name, no colon.
                staff.split('\n').forEach { l ->
                    val m = Regex("""^(\p{L}[\p{L} ]{1,24}?)\s{1,3}(\S.{1,60})$""").find(l.trim()) ?: return@forEach
                    val role = m.groupValues[1].trim()
                    if (l.contains(':') || l.contains(" by ", true)) return@forEach
                    if (DnaRoles.group(role) in setOf(DnaRoles.Group.PERFORMER, DnaRoles.Group.ENGINEERING, DnaRoles.Group.PRODUCTION)) {
                        m.groupValues[2].split(Regex("\\s*,\\s*")).forEach { n -> f.credits += SongDna.Credit(role, n.trim(), "pocketmonsters.net") }
                    }
                }
            }
            page.substringAfter("Description:", "").substringBefore("Extra Staff").trim().takeIf { it.length > 40 }
                ?.let { f.facts += SongDna.Fact(SongDna.clip(it, 900), "pocketmonsters.net", "https://pocketmonsters.net/cd/view/$id") }
            Regex("https://media\\.pocketmonsters\\.net/cds/$id/([^\"'\\s/)]+?\\.(?:jpg|jpeg|png))").findAll(html)
                .map { it.value }.distinct().take(14).forEach { u ->
                    f.covers += SongDna.Cover(u, "pocketmonsters.net · " + u.substringAfterLast('/').substringBeforeLast('.'))
                }
            return f
        }
        return null
    }

    // ── canta-per-me.net (Yuki Kajiura) ───────────────────────────────────

    private suspend fun kajiura(titles: List<String>, artist: String, seconds: Int?, albums: List<String>, number: Int?): SongDna.Found? {
        val base = "https://canta-per-me.net"
        val list = index("canta-per-me") {
            val out = ArrayList<Pair<String, String>>()
            for (p in listOf("/discography/", "/discography/alphabetical-listing/", "/discography/per-project/", "/discography/chronological/")) {
                val html = get(base + p, 1_500, "text/html") ?: continue
                Regex("""(?s)<a[^>]+href="((?:https://canta-per-me\.net)?/discography/[a-z0-9-]+/?)"[^>]*>(.*?)</a>""").findAll(html).forEach { m ->
                    val url = m.groupValues[1].let { if (it.startsWith("/")) base + it else it }
                    if (!url.trimEnd('/').endsWith("/discography") && !url.contains("listing") && !url.contains("per-project") && !url.contains("chronological")) {
                        out += url to text(m.groupValues[2])
                    }
                }
            }
            out.distinctBy { it.first.trimEnd('/') }
        }
        val wants = (albums + titles).map(::plain).filter { it.length >= 3 }
        val candidates = list.filter { (url, t) ->
            val p = plain(t); val slug = plain(url.trimEnd('/').substringAfterLast('/'))
            wants.any { w -> (p.isNotEmpty() && (p.contains(w) || (w.contains(p) && p.length >= 5))) || slug.contains(w) }
        }.take(6)
        for ((url, _) in candidates) {
            val html = get(url, 1_500, "text/html") ?: continue
            val page = text(html)
            val trackRe = Regex("""(?m)^\s*(\d{1,2})[.)]\s*(.+?)\s*\((\d{1,2}:\d{2})\)(.*)$""")
            val tracks = trackRe.findAll(page).toList()
            val hit = tracks.firstOrNull { m ->
                sureTrack(titles, seconds, m.groupValues[2], mmss(m.groupValues[3])) && !otherVersion(titles.first(), m.groupValues[2])
            } ?: continue
            val track = hit.groupValues[1].toIntOrNull() ?: number
            val f = SongDna.Found()
            f.sources += "canta-per-me.net"
            f.links += SongDna.Link("canta-per-me.net", url)
            f.album = Regex("(?s)<h1[^>]*>(.*?)</h1>").find(html)?.groupValues?.get(1)?.let(::text)?.ifBlank { null }
                ?: Regex("(?s)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.substringAfterLast('»')?.trim()
            Regex("""[A-Z]{2,5}-\d{3,5}""").findAll(page).map { it.value }.distinct().toList().takeIf { it.isNotEmpty() }
                ?.let { f.catalog = it.joinToString(" / ") }
            Regex("""(?i)(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},\s+(\d{4})""").find(page)?.let { m ->
                val month = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
                    .indexOf(m.groupValues[1].lowercase()) + 1
                val day = Regex("\\d{1,2}").find(m.value.substringAfter(' '))?.value?.padStart(2, '0') ?: "01"
                f.releaseDate = "${m.groupValues[2]}-${month.toString().padStart(2, '0')}-$day"
            }
            // Tie-ins written beside the track ("(PMMM … theme)").
            Regex("""\(([^()]{6,120})\)""").findAll(hit.groupValues[4]).map { it.groupValues[1].trim() }
                .filter { it.contains(Regex("(?i)theme|opening|ending|insert|anime|game|movie|主題歌|挿入歌")) }.forEach { f.uses += it }
            // The release's credits, those of this track ("EMIKO (1, 2)") or of all of them.
            f.credits += roleLines(page.replace(trackRe, ""), "canta-per-me.net", track)
            Regex("""(?:https://canta-per-me\.net)?/images/covers/[^"'\s)]+?-l\.(?:jpg|jpeg|png)""").findAll(html)
                .map { it.value.let { u -> if (u.startsWith("/")) base + u else u } }.distinct().take(8)
                .forEach { f.covers += SongDna.Cover(it, "canta-per-me.net") }
            return f
        }
        return null
    }

    // ── niconico 大百科 (楽曲情報) ─────────────────────────────────────────

    private suspend fun nicoPedia(titles: List<String>, artist: String): SongDna.Found? {
        for (t in titles.filter { CrossScript.hasJapanese(it) || it.length >= 3 }.take(2)) {
            val url = "https://dic.nicovideo.jp/a/${enc(t).replace("+", "%20")}"
            val html = get(url, 1_000, "text/html") ?: continue
            val rows = Regex("(?s)<tr[^>]*>\\s*<th[^>]*>(.*?)</th>\\s*<td[^>]*>(.*?)</td>").findAll(html)
                .map { text(it.groupValues[1]) to text(it.groupValues[2]) }.toList()
            if (rows.isEmpty()) continue
            fun cell(vararg k: String) = rows.firstOrNull { (h, _) -> k.any { h.contains(it) } }?.second?.ifBlank { null }
            val who = cell("アーティスト", "歌手", "歌", "ボーカル") ?: continue
            if (!sameArtist(artist, who) && !who.split(Regex("[、,/・]")).any { sameArtist(artist, it.trim()) }) continue
            val name = cell("曲名", "タイトル")
            if (name != null && !sameTitle(titles, name)) continue
            val f = SongDna.Found()
            f.sources += "niconico 大百科"
            f.links += SongDna.Link("niconico 大百科", url)
            val lines = rows.joinToString("\n") { (h, v) -> "$h：$v" }
            f.credits += roleLines(lines, "niconico 大百科")
            cell("発売日", "リリース")?.let { d ->
                Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""").find(d)?.let { m ->
                    f.releaseDate = "${m.groupValues[1]}-${m.groupValues[2].padStart(2, '0')}-${m.groupValues[3].padStart(2, '0')}"
                }
            }
            cell("主題歌", "タイアップ", "使用")?.let { f.uses += it }
            // The article's first paragraph.
            Regex("(?s)<p[^>]*>(.*?)</p>").findAll(html.substringAfter("article", html)).map { text(it.groupValues[1]) }
                .firstOrNull { it.length > 40 }?.let { f.facts += SongDna.Fact(SongDna.clip(it, 700), "niconico 大百科", url) }
            return f
        }
        return null
    }

    // ── MediaWiki wikis (series, Generasia) ───────────────────────────────

    /** Wiki markup to plain text: links, templates, references and formatting removed. */
    private fun unwiki(s: String): String {
        var t = s.replace(Regex("(?s)<ref[^>]*/>|<ref[^>]*>.*?</ref>"), "")
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("(?i)<br\\s*/?>"), ", ")
            .replace(Regex("<[^>]+>"), "")
        // {{nihongo|English|日本語|romaji}} → "English (日本語)"; {{lang|ja|…}} → its text; other templates out.
        t = t.replace(Regex("""\{\{\s*nihongo\s*\|([^|}]*)\|([^|}]*)[^}]*\}\}""", RegexOption.IGNORE_CASE)) { m ->
            listOf(m.groupValues[1].trim(), m.groupValues[2].trim()).filter { it.isNotEmpty() }.let { if (it.size == 2) "${it[0]} (${it[1]})" else it.firstOrNull().orEmpty() }
        }
        t = t.replace(Regex("""\{\{\s*(?:lang|ruby|j|jap)\s*\|(?:[a-z]{2}\|)?([^|}]*)[^}]*\}\}""", RegexOption.IGNORE_CASE)) { it.groupValues[1] }
        repeat(3) { t = t.replace(Regex("""\{\{[^{}]*\}\}"""), "") }
        t = t.replace(Regex("""\[\[(?:[^|\]]*\|)?([^\]]*)\]\]"""), "$1")
            .replace(Regex("""\[https?://\S+\s+([^\]]+)\]"""), "$1")
            .replace(Regex("""\[https?://\S+\]"""), "")
            .replace("'''", "").replace("''", "")
        return t.replace(Regex("[ \\t]+"), " ").trim()
    }

    /** "| lyricist = …" lines of every template, as "key: value". */
    private fun infobox(wikitext: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("""(?m)^\s*\|\s*([^=|\n]{2,40}?)\s*=\s*(.+)$""").findAll(wikitext).forEach { m ->
            val k = m.groupValues[1].trim().lowercase()
            val v = unwiki(m.groupValues[2]).trim().trim(',').trim()
            if (v.isNotBlank() && k !in out) out[k] = v
        }
        return out
    }

    private val apiPath = ConcurrentHashMap<String, String>()

    private suspend fun wikiJson(w: Wiki, query: String): JSONObject? {
        val host = java.net.URL(w.api).host
        val apis = listOfNotNull(apiPath[host], w.api, w.api.replace("/w/api.php", "/api.php"), w.api.replace("/api.php", "/w/api.php")).distinct()
        for (a in apis) {
            val o = json("$a?$query&format=json&formatversion=2") ?: continue
            apiPath[host] = a
            return o
        }
        return null
    }

    private suspend fun wiki(w: Wiki, titles: List<String>, artist: String, seconds: Int?): SongDna.Found? {
        var page: String? = null
        for (t in titles.take(2)) {
            val hits = wikiJson(w, "action=query&list=search&srlimit=6&srsearch=${enc(t)}")
                ?.optJSONObject("query")?.optJSONArray("search") ?: continue
            for (i in 0 until hits.length()) {
                val title = hits.optJSONObject(i)?.optString("title") ?: continue
                val bare = title.replace(Regex("""\s*\((?:song|single|canción|曲|シングル)[^)]*\)""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*\([^)]*\)$"""), "")
                if (sameTitle(titles, bare) || sameTitle(titles, title)) { page = title; break }
            }
            if (page != null) break
        }
        val p = page ?: return null
        val parsed = wikiJson(w, "action=parse&prop=wikitext&redirects=1&page=${enc(p)}")?.optJSONObject("parse") ?: return null
        val wikitext = parsed.opt("wikitext").let { if (it is JSONObject) it.optString("*") else it?.toString().orEmpty() }
        if (wikitext.isBlank()) return null
        val box = infobox(wikitext)
        // Very sure: the page names the song's artist (its infobox, or its text).
        val who = box.entries.filter { (k, _) -> listOf("artist", "singer", "performer", "vocal", "歌", "band").any { k.contains(it) } }.map { it.value }
        val textAll = unwiki(wikitext)
        val artistOk = who.any { v -> v.split(Regex("[,、&/]")).any { sameArtist(artist, it.trim()) } } ||
            SongDna.firstArtist(artist).let { a -> a.length >= 3 && plain(textAll).contains(plain(a)) }
        if (!artistOk) return null
        val f = SongDna.Found()
        val url = w.page + enc(p.replace(' ', '_')).replace("+", "%20").replace("%2F", "/")
        f.sources += w.name
        f.links += SongDna.Link(w.name, url)
        val roleText = box.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        f.credits += roleLines(roleText, w.name).filter { DnaRoles.group(it.role) != DnaRoles.Group.LABEL }
        // A release date only when the page's length is this recording's.
        val len = box.entries.firstOrNull { (k, _) -> k.contains("length") || k.contains("duration") || k == "time" }?.value
            ?.let { Regex("""\d{1,2}:\d{2}""").find(it)?.value }?.let(::mmss)
        if (len == null || seconds == null || kotlin.math.abs(len - seconds) <= 3) {
            box.entries.firstOrNull { (k, _) -> k.contains("release") || k.contains("released") || k == "date" }?.value
                ?.let { Regex("""\d{4}(?:-\d{2}-\d{2})?""").find(it)?.value }?.let { f.releaseDate = it }
        }
        box.entries.filter { (k, _) -> listOf("series", "anime", "game", "usage", "used", "theme", "opening", "ending", "tie-in", "tie in").any { k.contains(it) } }
            .map { it.value }.filter { it.length in 3..160 }.forEach { f.uses += it }
        // The introduction: the text before the first heading, its templates gone.
        val intro = unwiki(wikitext.substringBefore("\n==")).split('\n').map { it.trim() }
            .filter { it.length > 40 && !it.startsWith("|") && !it.startsWith("{") }.joinToString("\n")
        if (intro.length > 40) f.facts += SongDna.Fact(SongDna.clip(intro, 1200), w.name, url)
        return f
    }

    /** Runs the sources for [song], each within its time, those that answered and those that didn't. */
    suspend fun lookUp(
        song: Song,
        context: String,
        album: String?,
        credits: List<SongDna.Credit>,
        noWriters: Boolean,
        ask: suspend (String, Long, suspend () -> SongDna.Found?) -> SongDna.Found?,
    ): List<SongDna.Found> = coroutineScope {
        sourcesFor(song, context, album, credits, noWriters)
            .map { (name, block) -> async(Dispatchers.IO) { ask(name, 60_000, block) } }
            .mapNotNull { it.await() }
    }
}
