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
                Source("Escritorio · Música", "$home/Documents/Backup desktop/Música"),
                Source("Música(1)", "$home/Music/Música(1)"),
                Source("Discografías", "$home/Downloads/Discografías"),
                Source("BitChord (descargas del móvil)", "$home/pCloud Drive/Music/BitChord"),
                Source("Sobrantes de la Biblioteca", "$home/pCloud Drive/Sobrantes de la Biblioteca"),
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

    /** Sources the user moved (old → new): followed by the sources, the manifest and the quarantine. */
    val MOVES = listOf(
        "/Users/juanmi/Desktop/Música" to "/Users/juanmi/Documents/Backup desktop/Música",
    )

    /** [path] under a moved source, where it is now. */
    fun moved(path: String): String {
        val p = Words.nfc(path)
        for ((old, new) in MOVES) if (p == Words.nfc(old) || p.startsWith(Words.nfc(old) + "/")) return Words.nfc(new) + p.removePrefix(Words.nfc(old))
        return path
    }

    fun load(): List<Source> {
        val list = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { Source.from(a.getJSONObject(it)) }
        }.getOrElse { Source.defaults().also(::save) }
        var fixed = list.map { s -> if (!File(Paths.resolve(s.path)).exists() && moved(s.path) != s.path) s.copy(path = moved(s.path)) else s }
        // Sources added after the list was saved: the phone's downloads (YouTube Music) since 1.0.28.
        Source.defaults().filter { d -> (d.name.startsWith("BitChord") || d.name.startsWith("Sobrantes")) && fixed.none { Words.nfc(it.path) == Words.nfc(d.path) } }.forEach { fixed = fixed + it }
        if (fixed != list) runCatching { save(fixed) }
        return fixed.map { it.copy(path = Paths.resolve(it.path)) }
    }

    fun save(list: List<Source>) = file.writeText(JSONArray(list.map { it.toJson() }).toString(2))
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
    private val localFile = File(Live.dir, "covers-" + Integer.toHexString(Words.nfc(root).hashCode()) + ".json")
    private val dbFile = File(root, ".bitchord-library/covers.json")
    private val db: JSONObject = listOf(localFile, dbFile).filter { it.isFile }.sortedByDescending { it.lastModified() }
        .firstNotNullOfOrNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() } ?: JSONObject()
    private val folders = db.optJSONObject("folders") ?: JSONObject().also { db.put("folders", it) }
    private val songs = db.optJSONObject("songs") ?: JSONObject().also { db.put("songs", it) }

    /** The picture already taken for an album folder, without looking (null: not looked at or none). */
    @Synchronized fun known(folder: String): String? = folders.optString(folder).ifBlank { null }

    /** The picture of an album folder: its cover image file, else the first embedded picture. */
    @Synchronized fun forFolder(folder: String): File? {
        if (folders.has(folder)) return folders.optString(folder).ifBlank { null }?.let { File(dir, it) }?.takeIf { it.isFile }
        val f = File(folder)
        val image = f.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { n -> listOf("cover", "folder", "front", "albumart").indexOfFirst { n.name.lowercase().startsWith(it) }.let { if (it < 0) 9 else it } }
            ?.firstOrNull { !it.name.lowercase().contains("small") && !it.name.lowercase().contains("back") }
        val tmp = File(dir, ".tmp-" + java.util.UUID.randomUUID() + ".jpg")
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

    @Synchronized fun recordPrimary(key: String, folder: String?) {
        val s = songs.optJSONObject(key) ?: JSONObject().also { songs.put(key, it) }
        folder?.let { forFolder(it)?.let { f -> s.put("primary", f.name) } }
    }

    /** The other releases' covers of [key], proposed (unless already decided). */
    @Synchronized fun propose(key: String, otherFolders: List<String>) {
        val s = songs.optJSONObject(key) ?: JSONObject().also { songs.put(key, it) }
        val alts = s.optJSONObject("alternatives") ?: JSONObject().also { s.put("alternatives", it) }
        for (f in otherFolders) {
            val c = forFolder(f)?.name ?: continue
            if (c == s.optString("primary") || alts.has(c)) continue
            alts.put(c, JSONObject().put("status", "pending").put("from", f))
        }
    }

    data class Proposal(val key: String, val primary: File?, val alternative: File, val from: String)

    @Synchronized fun pending(): List<Proposal> = songs.keys().asSequence().flatMap { k ->
        val s = songs.getJSONObject(k)
        val alts = s.optJSONObject("alternatives") ?: return@flatMap emptySequence()
        alts.keys().asSequence().filter { alts.getJSONObject(it).optString("status") == "pending" }.map { c ->
            Proposal(k, s.optString("primary").ifBlank { null }?.let { File(dir, it) }, File(dir, c), alts.getJSONObject(c).optString("from"))
        }
    }.toList()

    @Synchronized fun decide(key: String, cover: String, approve: Boolean) {
        songs.optJSONObject(key)?.optJSONObject("alternatives")?.optJSONObject(cover)?.put("status", if (approve) "approved" else "rejected")
    }

    @Synchronized fun save(publish: ((File) -> Unit)? = null) = runCatching {
        val text = db.toString()
        val tmp = File(localFile.parentFile, localFile.name + "." + java.util.UUID.randomUUID() + ".tmp")
        tmp.writeText(text)
        check(tmp.length() > 0 && runCatching { JSONObject(tmp.readText()) }.isSuccess) { "covers.json incompleto" }
        java.nio.file.Files.move(tmp.toPath(), localFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        if (publish != null) publish(localFile) else {
            dbFile.parentFile.mkdirs()
            val libTmp = File(dbFile.parentFile, ".tmp-" + java.util.UUID.randomUUID() + "-covers.json")
            libTmp.writeText(text)
            if (!libTmp.renameTo(dbFile)) { dbFile.writeText(text); libTmp.delete() }
        }
    }
}

/**
 * Freeing space: a source folder (an artist's, a collection's) whose every song is in the
 * library at least as good can go — first copied to the online archive and checked, then
 * deleted from the Mac.
 */
object FreeSpace {
    data class Folder(val source: Source, val path: String, val files: Int, val bytes: Long, val covered: Int)

    /**
     * A source song is covered when the library holds its recording (or the plan leaves it out
     * on purpose: karaoke, a format never copied — the archive keeps it all anyway).
     */
    fun scan(sources: List<Source>, tracks: List<Track>, root: String): List<Folder> {
        val plan = Builder.plan(sources, tracks.filter { !it.path.startsWith("$root/") }, root)
        val placed = plan.copy.associateBy { it.key }
        fun covered(t: Track): Boolean {
            val k = plan.keyOf[t.path] ?: return true
            return placed[k]?.let { File(it.target).exists() } ?: true
        }
        return sources.filter { it.mode == "all" }.flatMap { s ->
            tracks.filter { it.path.startsWith(s.path + "/") }
                .groupBy { s.path + "/" + it.path.removePrefix(s.path + "/").substringBefore('/') }
                .filterKeys { File(it).isDirectory }
                .map { (folder, list) ->
                    val bytes = File(folder).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    Folder(s, folder, list.size, bytes, list.count(::covered))
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
