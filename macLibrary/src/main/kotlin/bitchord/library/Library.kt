package bitchord.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Where the app keeps its settings and its index of the library. */
object Store {
    val dir = File(System.getProperty("user.home"), "Library/Application Support/BitChord Library").apply { mkdirs() }
    private val settingsFile = File(dir, "settings.json")

    /**
     * [root]: the clean library being built (never a source). [extra]: the sources' folders,
     * read only (see sources.json for their rules).
     */
    data class Settings(
        val root: String = File(System.getProperty("user.home"), "pCloud Drive/Biblioteca").path,
        val extra: List<String> = Sources.load().map { it.path },
        val phoneTodo: String = File(System.getProperty("user.home"), "pCloud Drive/.bitchord-audio/todo-phone.json").path,
    )

    fun load(): Settings = runCatching {
        val o = JSONObject(settingsFile.readText())
        // v1 kept the old Música as "the library": it is a source now, never written to.
        if (o.optInt("v") < 2) error("old settings")
        Settings(
            root = o.optString("root").ifBlank { Settings().root },
            extra = o.optJSONArray("extra")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: Settings().extra,
            phoneTodo = o.optString("phoneTodo").ifBlank { Settings().phoneTodo },
        )
    }.getOrDefault(Settings()).let { s -> s.copy(root = Paths.resolve(s.root), extra = s.extra.map(Paths::resolve), phoneTodo = Paths.resolve(s.phoneTodo)) }

    fun save(s: Settings) {
        settingsFile.writeText(JSONObject().put("v", 2).put("root", s.root).put("extra", JSONArray(s.extra)).put("phoneTodo", s.phoneTodo).toString(2))
    }
}

/** ffprobe / ffmpeg: FFmpeg's own programs, carried inside the app; Homebrew's when they aren't. */
object Tools {
    val ffprobe: String by lazy { bundled("ffprobe") ?: system("ffprobe") ?: "ffprobe" }
    val ffmpeg: String by lazy { bundled("ffmpeg") ?: system("ffmpeg") ?: "ffmpeg" }

    private fun bundled(name: String): String? = runCatching {
        val cls = Class.forName("org.bytedeco.ffmpeg.$name")
        org.bytedeco.javacpp.Loader.load(cls)
    }.getOrNull()

    private fun system(name: String): String? =
        listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", System.getProperty("user.home") + "/.homebrew/bin/$name")
            .firstOrNull { File(it).canExecute() }

    fun run(vararg args: String, timeoutSec: Long = 120): Pair<Int, String> {
        val p = ProcessBuilder(*args).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "" }
        return p.exitValue() to (out.ifBlank { err })
    }
}

/** One audio file as ffprobe read it. */
data class Track(
    val path: String,
    val size: Long,
    val modified: Long,
    val codec: String?,
    val kbps: Int,
    val sampleRate: Int?,
    val seconds: Double?,
    val hasCover: Boolean,
    val tags: Map<String, String>,
    /** null: not checked; true: a "320" with nothing above 16.5 kHz (made from something worse). */
    val fake320: Boolean? = null,
) {
    val ext get() = path.substringAfterLast('.', "").lowercase()
    val lossless get() = codec in setOf("flac", "alac", "pcm_s16le", "pcm_s24le", "pcm_s16be", "pcm_s24be") || ext in setOf("flac", "wav", "aiff", "aif")
    val mp3_320 get() = codec == "mp3" && kbps >= 315
    val title get() = tags["title"].orEmpty()
    val artist get() = tags["artist"].orEmpty()
    val album get() = tags["album"].orEmpty()
    val albumArtist get() = tags["album_artist"] ?: tags["albumartist"] ?: tags["album artist"] ?: ""
    val year get() = (tags["date"] ?: tags["year"] ?: tags["originaldate"] ?: "").take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
    val trackNo get() = tags["track"]?.substringBefore('/')?.trim()?.toIntOrNull()
    val discNo get() = (tags["disc"] ?: tags["discnumber"])?.substringBefore('/')?.trim()?.toIntOrNull()

    fun toJson(): JSONObject = JSONObject().put("p", path).put("s", size).put("m", modified).put("c", codec ?: "").put("k", kbps)
        .put("sr", sampleRate ?: 0).put("d", seconds ?: 0.0).put("pic", hasCover).put("t", JSONObject(tags as Map<*, *>))
        .apply { fake320?.let { put("f", it) } }

    companion object {
        fun from(o: JSONObject) = Track(
            o.getString("p"), o.optLong("s"), o.optLong("m"), o.optString("c").ifBlank { null }, o.optInt("k"),
            o.optInt("sr").takeIf { it > 0 }, o.optDouble("d").takeIf { it > 0 }, o.optBoolean("pic"),
            o.optJSONObject("t")?.let { t -> t.keys().asSequence().associateWith { t.getString(it) } }.orEmpty(),
            if (o.has("f")) o.getBoolean("f") else null,
        )
    }
}

/** Reads every audio file under the folders, once: unchanged files come from the index. */
object Scanner {
    val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "ogg", "opus", "webm", "wma", "mp4", "alac", "ape", "wv")
    private val indexFile get() = File(System.getProperty("bitchord.index") ?: File(Store.dir, "index.json").path)
    private val index = ConcurrentHashMap<String, Track>()

    fun loadIndex() {
        runCatching {
            val a = JSONArray(indexFile.readText())
            for (i in 0 until a.length()) Track.from(a.getJSONObject(i)).let { index[it.path] = it }
        }
    }

    fun saveIndex() = runCatching { indexFile.writeText(JSONArray(index.values.map { it.toJson() }).toString()) }

    fun files(folders: List<String>): List<File> = folders.flatMap { root ->
        File(root).walkTopDown()
            .onEnter { d -> !d.name.startsWith(".") && d.name != "_Archivo BitChord" }
            .filter { it.isFile && !it.name.startsWith("._") && it.extension.lowercase() in AUDIO }
            .toList()
    }

    /** A file moved by a plan: its reading follows it (no need to read it again). */
    fun moved(from: String, to: String) { index.remove(from)?.let { index[to] = it.copy(path = to) } }

    /** What the index already knows under [folders] (no reading). */
    fun known(folders: List<String>): List<Track> = index.values.filter { t -> folders.any { t.path.startsWith(it) } }

    /**
     * Like [scan], but stops at [deadline] (ms since epoch), saving what it read: run it
     * again to go on. Returns whether every file was read.
     */
    suspend fun scanUntil(folders: List<String>, deadline: Long, progress: (Int, Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val all = files(folders)
        val todo = all.filter { f -> index[f.path]?.let { it.size == f.length() && it.modified == f.lastModified() } != true }
        var done = all.size - todo.size
        for (chunk in todo.chunked(64)) {
            if (System.currentTimeMillis() > deadline) { saveIndex(); return@withContext false }
            coroutineScope { chunk.map { f -> async { index[f.path] = probe(f) } }.awaitAll() }
            done += chunk.size
            progress(done, all.size)
            if (done % 512 < 64) saveIndex()
        }
        val present = all.mapTo(HashSet()) { it.path }
        index.keys.filter { k -> folders.any { k.startsWith(it) } && k !in present }.forEach { index.remove(it) }
        saveIndex()
        true
    }

    suspend fun scan(folders: List<String>, progress: (Int, Int) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        val all = files(folders)
        val done = AtomicInteger()
        val gate = Semaphore(16)
        val out = coroutineScope {
            all.map { f ->
                async {
                    gate.withPermit {
                        val known = index[f.path]
                        val t = if (known != null && known.size == f.length() && known.modified == f.lastModified()) known else probe(f)
                        index[f.path] = t
                        progress(done.incrementAndGet(), all.size)
                        t
                    }
                }
            }.awaitAll()
        }
        // Gone from disk: out of the index too.
        val present = all.mapTo(HashSet()) { it.path }
        index.keys.filter { k -> folders.any { k.startsWith(it) } && k !in present }.forEach { index.remove(it) }
        saveIndex()
        out
    }

    fun probe(f: File): Track {
        val (_, text) = Tools.run(Tools.ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", f.path, timeoutSec = 60)
        val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val fmt = j.optJSONObject("format") ?: JSONObject()
        val streams = j.optJSONArray("streams") ?: JSONArray()
        val audio = (0 until streams.length()).map { streams.getJSONObject(it) }.firstOrNull { it.optString("codec_type") == "audio" }
        val pic = (0 until streams.length()).map { streams.getJSONObject(it) }.any { it.optJSONObject("disposition")?.optInt("attached_pic") == 1 }
        val tags = HashMap<String, String>()
        fun addTags(o: JSONObject?) = o?.keys()?.forEach { k -> val v = o.optString(k); if (v.length < 2000) tags.putIfAbsent(k.lowercase(), v) }
        addTags(fmt.optJSONObject("tags")); addTags(audio?.optJSONObject("tags"))
        val br = (audio?.optString("bit_rate")?.toLongOrNull() ?: fmt.optString("bit_rate").toLongOrNull() ?: 0L) / 1000
        return Track(
            f.path, f.length(), f.lastModified(), audio?.optString("codec_name")?.ifBlank { null }, br.toInt(),
            audio?.optString("sample_rate")?.toIntOrNull(), fmt.optString("duration").toDoubleOrNull(), pic, tags,
            index[f.path]?.fake320?.takeIf { index[f.path]?.size == f.length() },
        )
    }

    /** A "320" with nothing above 16.5 kHz was made from a lower-quality file. */
    fun checkFake(t: Track): Track {
        val start = ((t.seconds ?: 60.0) / 3).toInt().coerceAtLeast(5)
        val (_, out) = Tools.run(
            Tools.ffmpeg, "-v", "info", "-ss", start.toString(), "-t", "20", "-i", t.path,
            "-af", "highpass=f=16500,highpass=f=16500,highpass=f=16500,volumedetect", "-f", "null", "-", timeoutSec = 90,
        )
        val mean = Regex("""mean_volume: (-?[\d.]+)""").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        val checked = t.copy(fake320 = mean?.let { it < -75.0 })
        index[t.path] = checked
        return checked
    }
}

/** What the library should be: MP3 320 from lossless or real 320, tagged, with its cover. */
object Audit {
    enum class Issue(val label: String) {
        FORBIDDEN_FORMAT("Formato no permitido (AAC/m4a/opus/webm/wma)"),
        LOW_BITRATE("MP3 por debajo de 320 kbps"),
        FAKE_320("Falso 320 (sin contenido por encima de 16,5 kHz)"),
        LOSSLESS("Sin pérdida: convertir a MP3 320"),
        NO_COVER("Sin portada incrustada"),
        NO_TAGS("Faltan título, artista o álbum"),
        NO_YEAR("Falta el año"),
        NO_TRACK("Falta el número de pista"),
        NO_ROMAJI("Título solo en japonés (falta romaji)"),
        DUPLICATE("Repetida (misma canción y duración)"),
    }

    /** Issues that mean the file must be replaced (rather than fixed in place). */
    val REPLACE = setOf(Issue.FORBIDDEN_FORMAT, Issue.LOW_BITRATE, Issue.FAKE_320)

    private val JAPANESE = Regex("""[\p{IsHiragana}\p{IsKatakana}\p{IsHan}]""")
    private val LATIN = Regex("""[A-Za-z]""")

    fun issues(t: Track, duplicates: Set<String>): List<Issue> = buildList {
        when {
            t.lossless -> add(Issue.LOSSLESS)
            t.codec != "mp3" -> add(Issue.FORBIDDEN_FORMAT)
            t.kbps < 315 -> add(Issue.LOW_BITRATE)
            t.fake320 == true -> add(Issue.FAKE_320)
        }
        if (!t.hasCover) add(Issue.NO_COVER)
        if (t.title.isBlank() || t.artist.isBlank() || t.album.isBlank()) add(Issue.NO_TAGS)
        if (t.year == null) add(Issue.NO_YEAR)
        if (t.trackNo == null) add(Issue.NO_TRACK)
        if (JAPANESE.containsMatchIn(t.title) && !LATIN.containsMatchIn(t.title)) add(Issue.NO_ROMAJI)
        if (t.path in duplicates) add(Issue.DUPLICATE)
    }

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        .replace(Regex("""[\(\[（【].*?[\)\]）】]"""), "").filter { it.isLetterOrDigit() }

    /** Same title, same first artist, same length (3 s): every copy but the best is a duplicate. */
    fun duplicates(tracks: List<Track>): Set<String> {
        val groups = tracks.filter { it.title.isNotBlank() }.groupBy {
            norm(it.title) + "|" + norm(it.artist.split(',', '&', '、').first()) + "|" + ((it.seconds ?: 0.0) / 3).toInt()
        }
        return groups.values.filter { it.size > 1 }.flatMap { g ->
            val best = g.maxWith(compareBy<Track>({ it.lossless }, { it.mp3_320 }, { it.kbps }))
            g.filter { it !== best }.map { it.path }
        }.toSet()
    }
}

/** One to-do line: a song, or a whole album or artist when most of it needs the same thing. */
data class Todo(val group: String, val what: String, val reason: String, val count: Int, val paths: List<String>)

object Todos {
    /** Grouped as you would fix them: an artist folder, an album folder, or a song. */
    fun build(root: String, tracks: List<Track>, issues: Map<String, List<Audit.Issue>>): List<Todo> {
        val out = ArrayList<Todo>()
        val needs = tracks.filter { t -> issues[t.path].orEmpty().any { it in Audit.REPLACE } }
        val byArtist = needs.groupBy { artistFolder(root, it.path) }
        for ((artist, list) in byArtist) {
            val inArtist = tracks.count { artistFolder(root, it.path) == artist }
            if (list.size >= 10 && list.size * 2 >= inArtist) {
                out += Todo(artist, "Discografía: $artist", "${list.size} de $inArtist canciones por sustituir", list.size, list.map { it.path })
                continue
            }
            for ((album, inAlbum) in list.groupBy { File(it.path).parentFile.path }) {
                val total = tracks.count { File(it.path).parentFile.path == album }
                if (inAlbum.size >= 3 && inAlbum.size * 2 >= total) {
                    out += Todo(artist, "Álbum: ${File(album).name}", "${inAlbum.size} de $total canciones por sustituir", inAlbum.size, inAlbum.map { it.path })
                } else inAlbum.forEach { t ->
                    out += Todo(artist, "${t.title.ifBlank { File(t.path).nameWithoutExtension }} — ${t.artist}",
                        issues[t.path].orEmpty().filter { it in Audit.REPLACE }.joinToString { it.label } + " · " + quality(t), 1, listOf(t.path))
                }
            }
        }
        return out.sortedWith(compareByDescending<Todo> { it.count }.thenBy { it.group.lowercase() })
    }

    fun phone(path: String): List<Todo> = runCatching {
        val songs = JSONObject(File(path).readText()).getJSONArray("songs")
        (0 until songs.length()).map { i ->
            val s = songs.getJSONObject(i)
            Todo("Móvil", "${s.optString("title")} — ${s.optString("artist")}",
                "Solo en el móvil como ${s.optString("format").ifBlank { "?" }}" + s.optString("album").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                1, emptyList())
        }
    }.getOrDefault(emptyList())

    fun artistFolder(root: String, path: String): String =
        path.removePrefix(root).trimStart('/').substringBefore('/').ifBlank { "(raíz)" }

    fun quality(t: Track) = (t.codec?.uppercase() ?: "?") + " " + (if (t.kbps > 0) "${t.kbps} kbps" else "")

    fun csv(todos: List<Todo>): String = buildString {
        appendLine("grupo,qué,motivo,canciones,archivos")
        todos.forEach { t ->
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            appendLine(listOf(q(t.group), q(t.what), q(t.reason), t.count.toString(), q(t.paths.joinToString(" | "))).joinToString(","))
        }
    }
}

/**
 * The agreed names: "Año - Álbum (日本語)" folders and
 * "01 - Título (日本語) - Artista (日本語) - Álbum (日本語) - Año.mp3" files, under the
 * artist's folder (album artist, else the first artist).
 */
object Naming {
    private val BAD = Regex("""[/\\:*?"<>|]""")
    private fun clean(s: String) = s.replace(BAD, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')

    fun target(root: String, t: Track): String? {
        if (t.title.isBlank() || t.artist.isBlank()) return null
        val artistDir = clean((t.albumArtist.ifBlank { t.artist.split(',', '、').first() }))
        val albumDir = clean(listOfNotNull(t.year, t.album.ifBlank { null }).joinToString(" - ").ifBlank { "Sueltas" })
        val no = t.trackNo?.let { n -> (t.discNo?.takeIf { it > 1 }?.let { "$it-" } ?: "") + n.toString().padStart(2, '0') }
        val name = listOfNotNull(no, t.title, t.artist, t.album.ifBlank { null }, t.year).joinToString(" - ") { clean(it) }
        val ext = if (t.lossless) "mp3" else t.ext
        return "$root/$artistDir/$albumDir/$name.$ext"
    }
}

/** FLAC (or another lossless file) → MP3 320 with its tags and cover; the original kept aside. */
object Convert {
    fun toMp3(t: Track, root: String): Result<String> = runCatching {
        val src = File(t.path)
        val out = File(src.parentFile, src.nameWithoutExtension + ".mp3")
        require(!out.exists()) { "Ya existe ${out.name}" }
        val (code, msg) = Tools.run(
            Tools.ffmpeg, "-v", "error", "-i", src.path, "-map", "0:a", "-map", "0:v?", "-c:a", "libmp3lame", "-b:a", "320k",
            "-c:v", "copy", "-id3v2_version", "3", "-map_metadata", "0", out.path, timeoutSec = 600,
        )
        check(code == 0 && out.length() > 0) { "ffmpeg: $msg" }
        // The original into "_Archivo BitChord", at the same place under it.
        val rel = src.path.removePrefix(root).trimStart('/')
        val archived = File(File(root, "_Archivo BitChord"), rel)
        archived.parentFile.mkdirs()
        check(src.renameTo(archived)) { "No se pudo mover el original" }
        out.path
    }
}
