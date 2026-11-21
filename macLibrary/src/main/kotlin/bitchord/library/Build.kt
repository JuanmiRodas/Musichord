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
                Source("Música (antigua)", "$home/pCloud Drive/pCloud Backup/MacBookAirdeJuanmi/Music/Música"),
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
                        Regex("^\\d{4}/.*\\[[^\\]]+\\]\\s*$", RegexOption.IGNORE_CASE) to "Singles",
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
    private val file get() = File(Store.dir, "sources.json")

    fun load(): List<Source> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { Source.from(a.getJSONObject(it)) }
    }.getOrElse { Source.defaults().also(::save) }.map { it.copy(path = Paths.resolve(it.path)) }

    fun save(list: List<Source>) = file.writeText(JSONArray(list.map { it.toJson() }).toString(2))
}

/**
 * The clean library, built from the sources: for each recording the best copy (lossless
 * made into MP3 320, else a real MP3 320, else the best MP3 meanwhile — on the to-do),
 * tagged and named as agreed. Formats the library doesn't keep (AAC, m4a, opus, webm,
 * wma) are never copied: they go to the to-do. Nothing in a source is changed.
 */
object Builder {
    data class Item(val source: Source, val track: Track, val target: String, val reason: String)
    data class Plan(val copy: List<Item>, val skipped: List<Pair<Track, String>>)

    private fun norm(s: String) = Audit.norm(s)
    private fun key(t: Track) = norm(t.title) + "|" + norm(t.artist.split(',', '&', '、', ';').first()) + "|" + ((t.seconds ?: 0.0) / 3).toInt()

    private fun rank(t: Track): Int = when {
        t.lossless -> 4
        t.mp3_320 && t.fake320 != true -> 3
        t.codec == "mp3" -> 1 + (if (t.kbps >= 256) 1 else 0)
        else -> 0
    }

    private val BAD = Regex("""[/\\:*?"<>|]""")
    private fun clean(s: String) = s.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    /** "2009 - storia" / "2003/FICTION": the album folder's year and name. */
    private fun folderAlbum(rel: String): Pair<String?, String> {
        val parts = rel.split('/')
        val albumDir = parts.getOrNull(parts.size - 2) ?: ""
        val m = Regex("""^(\d{4})\s*-\s*(.+)$""").find(albumDir)
        if (m != null) return m.groupValues[1] to m.groupValues[2]
        val year = parts.firstOrNull { Regex("""^\d{4}$""").matches(it) && it != "0000" }
        return year to albumDir
    }

    /** Where [t] of [s] goes, or why not. */
    fun place(s: Source, t: Track, targetRoot: String): Pair<String?, String> {
        if (!t.lossless && t.codec != "mp3") return null to "formato ${t.codec ?: "?"} (no se copia)"
        val rel = t.path.removePrefix(s.path).trimStart('/')
        if (s.mode == "all") return Naming.target(targetRoot, t)?.let { it to "" } ?: (null to "sin título o artista")
        if (s.exclude.any { it.containsMatchIn(rel) }) return null to "excluida"
        val albumRel = rel.substringBeforeLast('/')
        val rule = s.include.firstOrNull { (r, _) -> r.containsMatchIn("$albumRel/") } ?: return null to "fuera de las reglas"
        if (s.vocalOnly?.containsMatchIn(albumRel) == true || rule.second == "Bandas sonoras") {
            val main = s.mainArtist?.let(::norm)
            val a = norm(t.artist)
            if (a.isEmpty() || (main != null && (a == main || a == norm(s.mainArtist!!.split(' ').reversed().joinToString(" "))))) return null to "instrumental de banda sonora"
        }
        val group = rule.first.find("$albumRel/")?.let { m -> rule.second.replace(Regex("""\$(\d)""")) { g -> m.groupValues.getOrNull(g.groupValues[1].toInt()).orEmpty() } } ?: rule.second
        val (fYear, fAlbum) = folderAlbum(rel)
        val year = t.year ?: fYear
        val album = t.album.ifBlank { fAlbum }
        val albumDir = clean(listOfNotNull(year, album.ifBlank { null }).joinToString(" - ").ifBlank { "Sueltas" })
        val no = t.trackNo?.let { n -> (t.discNo?.takeIf { it > 1 }?.let { "$it-" } ?: "") + n.toString().padStart(2, '0') }
        val title = t.title.ifBlank { File(t.path).nameWithoutExtension }
        val name = listOfNotNull(no, title, t.artist.ifBlank { null }, album.ifBlank { null }, year).joinToString(" - ") { clean(it) }
        val dir = listOfNotNull(targetRoot, s.target?.let(::clean), group.takeIf { it.isNotBlank() }?.let(::clean), albumDir).joinToString("/")
        return "$dir/$name.mp3" to ""
    }

    /** What would be copied: every recording once, from its best copy; what the library already has is left. */
    fun plan(sources: List<Source>, tracks: List<Track>, targetRoot: String): Plan {
        val have = tracks.filter { it.path.startsWith("$targetRoot/") }
        val haveKeys = have.associateBy { key(it) }
        val skipped = ArrayList<Pair<Track, String>>()
        val candidates = ArrayList<Item>()
        for (s in sources) {
            for (t in tracks.filter { it.path.startsWith(s.path + "/") }) {
                val (target, why) = place(s, t, targetRoot)
                if (target == null) { skipped += t to why; continue }
                candidates += Item(s, t, target, "")
            }
        }
        val copy = candidates.groupBy { key(it.track) }.mapNotNull { (k, list) ->
            val best = list.maxWith(compareBy({ rank(it.track) }, { it.track.kbps }))
            val existing = haveKeys[k]
            when {
                existing != null && rank(existing) >= rank(best.track) -> null
                File(best.target).exists() -> null
                else -> best.copy(reason = when (rank(best.track)) {
                    4 -> "sin pérdida → MP3 320"
                    3 -> "MP3 320"
                    else -> "MP3 ${best.track.kbps} kbps (mientras tanto; a la to-do)"
                })
            }
        }
        // Two songs wanting one name: the second gets " (2)".
        val seen = HashMap<String, Int>()
        val unique = copy.map { i ->
            val n = seen.merge(i.target.lowercase(), 1, Int::plus)!!
            if (n == 1) i else i.copy(target = i.target.removeSuffix(".mp3") + " ($n).mp3")
        }
        return Plan(unique, skipped)
    }

    /** Copies (or converts) one item, its tags written as named, its cover kept. */
    fun copy(i: Item): Result<String> = runCatching {
        val out = File(i.target)
        require(!out.exists()) { "ya existe" }
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, ".part-" + out.name)
        val t = i.track
        val (fYear, fAlbum) = folderAlbum(t.path.removePrefix(i.source.path).trimStart('/'))
        val meta = buildList {
            fun m(k: String, v: String?) { if (!v.isNullOrBlank()) { add("-metadata"); add("$k=$v") } }
            m("title", t.title.ifBlank { File(t.path).nameWithoutExtension })
            m("artist", t.artist)
            m("album", t.album.ifBlank { fAlbum })
            m("album_artist", t.albumArtist.ifBlank { i.source.target ?: t.artist })
            m("date", t.year ?: fYear)
            m("grouping", i.source.target)
        }
        val codec = if (t.lossless) listOf("-c:a", "libmp3lame", "-b:a", "320k") else listOf("-c:a", "copy")
        val args = listOf(Tools.ffmpeg, "-v", "error", "-y", "-i", t.path, "-map", "0:a", "-map", "0:v?", "-c:v", "copy", "-disposition:v", "attached_pic") +
            codec + listOf("-map_metadata", "0", "-id3v2_version", "3") + meta + listOf("-f", "mp3", tmp.path)
        val (code, msg) = Tools.run(*args.toTypedArray(), timeoutSec = 900)
        check(code == 0 && tmp.length() > 0) { "ffmpeg: ${msg.take(300)}" }
        check(tmp.renameTo(out)) { "no se pudo renombrar" }
        out.path
    }
}
