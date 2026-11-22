package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which library file came from which source file (`<library>/.bitchord-library/built.json`):
 * how the library is put in order again when the plan changes — files move, never copied twice.
 */
class Manifest(root: String) {
    private val file = File(root, ".bitchord-library/built.json")
    val files: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())

    init {
        runCatching {
            val o = JSONObject(file.readText()).getJSONObject("files")
            o.keys().forEach { files[Words.nfc(it)] = Words.nfc(o.getString(it)) }
        }
    }

    fun save() = runCatching {
        val tmp = File(file.parentFile, "built.json.tmp")
        synchronized(files) { tmp.writeText(JSONObject().put("version", 1).put("files", JSONObject(files as Map<*, *>)).toString()) }
        tmp.renameTo(file) || run { file.writeText(tmp.readText()); tmp.delete() }
    }
}

/**
 * What the app is doing, for the window and for Claude: `~/Music/.bitchord-live/live.json`
 * (rewritten every few seconds) and one line per file in `log.txt`.
 */
object Live {
    /**
     * On the Mac's own disk (`~/Music/.bitchord-live`), not in pCloud Drive: pCloud shows a file
     * only once it has synced it, so status and orders there arrive late or half-written.
     */
    val dir: File get() = File(System.getProperty("user.home"), "Music/.bitchord-live").apply { mkdirs() }

    @Volatile var stop = false
    @Volatile var job: String? = null
    @Volatile var phase = ""
    @Volatile var current = ""
    @Volatile var total = 0
    val done = AtomicInteger()
    val copied = AtomicInteger()
    val moved = AtomicInteger()
    val deleted = AtomicInteger()
    val failed = AtomicInteger()
    private val errors = ArrayDeque<String>()
    @Volatile var unmatched: List<String> = emptyList()
    @Volatile var lastCommand = ""
    @Volatile private var started = 0L
    @Volatile private var written = 0L
    @Volatile var root: String? = null

    fun begin(name: String, root: String) {
        this.root = root; job = name; phase = "preparando"; current = ""; total = 0; stop = false
        listOf(done, copied, moved, deleted, failed).forEach { it.set(0) }
        synchronized(errors) { errors.clear() }
        started = System.currentTimeMillis()
        write(force = true)
    }

    fun error(text: String) { failed.incrementAndGet(); synchronized(errors) { errors.addLast(text); while (errors.size > 30) errors.removeFirst() } }

    fun end(summary: String) { phase = summary; job = null; current = ""; write(force = true) }

    fun summary(): String = when (val j = job) {
        null -> phase.ifBlank { "Sin tareas en marcha" }
        else -> "$j · $phase: ${done.get()} de $total (${copied.get()} copiadas, ${moved.get()} movidas, ${deleted.get()} quitadas, ${failed.get()} con error)"
    }

    @Synchronized fun write(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - written < 2000) return
        written = now
        val r = root ?: return
        runCatching {
            val o = JSONObject().put("version", Updater.current).put("pid", ProcessHandle.current().pid()).put("updated", now)
                .put("job", job ?: JSONObject.NULL).put("phase", phase).put("current", current).put("total", total)
                .put("done", done.get()).put("copied", copied.get()).put("moved", moved.get()).put("deleted", deleted.get()).put("failed", failed.get())
                .put("started", started).put("summary", summary()).put("lastCommand", lastCommand)
                .put("errors", JSONArray(synchronized(errors) { errors.toList() })).put("unmatched", JSONArray(unmatched.take(300)))
            val tmp = File(dir, "live.json.tmp")
            tmp.writeText(o.put("library", r).toString(2))
            tmp.renameTo(File(dir, "live.json"))
        }
    }
}

/**
 * Builds the library and keeps it in order with the plan: every song the plan wants is put at
 * its place — moved (re-tagged) when the library already holds it elsewhere, replaced when a
 * better copy turned up, copied when it isn't there — and every file the app made that the plan
 * no longer wants is removed. Files nobody can tell (not made by the app, matching no song)
 * are left where they are and listed. Sources are only read.
 */
object Engine {
    fun build(sources: List<Source>, tracks: List<Track>, root: String, workers: Int = 3): String {
        val ops = Ops(root)
        Live.begin("Construir y ordenar", root)
        Live.phase = "calculando el plan"
        val plan = Builder.plan(sources, tracks.filter { !it.path.startsWith("$root/") }, root)
        val manifest = Manifest(root)
        val covers = Covers(root)
        fun rel(p: String) = Words.nfc(p.removePrefix(root).trimStart('/'))
        val bySource = HashMap<String, Builder.Item>()
        plan.copy.forEach { i -> i.copies.forEach { bySource[Words.nfc(it)] = i } }
        val byTarget = plan.copy.associateBy { rel(it.target).lowercase() }
        val skippedPaths = plan.skipped.mapTo(HashSet()) { Words.nfc(it.first.path) }

        // What the library holds now (names as the disk spells them; compared in one form).
        Live.phase = "mirando lo que ya hay"
        val rootFile = File(root)
        // On pCloud Drive, files still uploading can't be moved: wait until it has uploaded everything.
        if (PCloud.isDrive(root)) PCloud.waitReady(rootFile, "antes de ordenar")
        // Folders whose name differs only in case ("NARUTO" → "Naruto"): renamed first, so songs land in the right one.
        fixCase(rootFile, plan.copy.map { rel(File(it.target).parent) }.toSet())
        val onDisk = HashMap<String, File>()
        rootFile.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() == "mp3" && !it.name.startsWith(".") }.forEach { onDisk[rel(it.path)] = it }
        val known = tracks.associateBy { Words.nfc(it.path) }
        val held = HashMap<String, String>() // item's target → library file holding it
        val unwanted = ArrayList<String>()
        val leftovers = ArrayList<String>()
        for (r in onDisk.keys.sorted()) {
            val src = manifest.files[r]
            val item = src?.let { bySource[it] } ?: byTarget[r.lowercase()]
            when {
                item == null && src != null && src in skippedPaths -> unwanted += r // made by the app, now left out (karaoke…)
                item == null -> leftovers += r
                held.containsKey(item.target) -> unwanted += r // a second copy of one song
                else -> held[item.target] = r
            }
        }
        // Files the app made before it kept a manifest, or from sources not read now: told by length and title.
        val byLength = plan.copy.filter { it.target !in held }.groupBy { ((it.track.seconds ?: 0.0) * 2).toInt() }
        val unknown = ArrayList<String>()
        for (r in leftovers) {
            if (Live.stop) break
            Live.current = r
            val f = onDisk[r]!!
            val t = Scanner.get(f.path) ?: runCatching { Scanner.probe(f) }.getOrNull()
            val s = t?.seconds
            val name = Words.plain(f.nameWithoutExtension)
            val match = if (s == null) null else (-1..1).flatMap { byLength[(s * 2).toInt() + it].orEmpty() }
                .filter { it.target !in held && kotlin.math.abs((it.track.seconds ?: 0.0) - s) < 1.0 && Words.plain(it.title).let { w -> w.length >= 2 && name.contains(w) } }
                .distinctBy { it.target }.singleOrNull()
            if (match != null) { held[match.target] = r; manifest.files.putIfAbsent(r, Words.nfc(match.track.path)) } else unknown += r
        }
        Live.unmatched = unknown
        if (unknown.isNotEmpty()) ops.log("app", "library: ${unknown.size} files not made by the app and matching no song (left where they are): ${unknown.take(5)}")
        // A plan far smaller than the library means the sources weren't read: nothing is removed then.
        if (unwanted.size > 50 && plan.copy.size < onDisk.size) { ops.log("app", "build: plan smaller than the library (sources not read?) — nothing removed"); unwanted.clear() }

        Live.total = plan.copy.size + unwanted.size
        // 1. Remove what the plan no longer wants (only files the app made, or second copies).
        Live.phase = "quitando lo que sobra"
        for (r in unwanted) {
            if (Live.stop) break
            Live.current = r
            val f = onDisk[r]!!
            val gone = io(60) { runCatching { java.nio.file.Files.delete(f.toPath()); true }.getOrElse { e ->
                // pCloud Drive may refuse to delete a file it is still uploading: out of sight instead.
                val bin = File(rootFile, ".papelera").apply { mkdirs() }
                f.renameTo(File(bin, System.nanoTime().toString() + "-" + f.name)).also { if (!it) Live.error("no se pudo quitar $r: ${e.javaClass.simpleName} ${e.message.orEmpty()}") }
            } } ?: false
            if (gone) { Live.deleted.incrementAndGet(); manifest.files.remove(r); Scanner.forget(f.path); ops.log("app", "removed $r (no song of the plan)") }
            Live.done.incrementAndGet(); Live.write()
        }
        // 2. Put each song held at its place: renamed (on pCloud Drive a rename is not uploaded
        // again); a better copy found since replaces it.
        Live.phase = "ordenando lo ya copiado"
        val pending = ArrayDeque(plan.copy.filter { it.target in held })
        val placed = ArrayList<Pair<Builder.Item, File>>()
        val replace = ArrayList<Pair<Builder.Item, File>>()
        var stuck = 0
        while (pending.isNotEmpty() && !Live.stop) {
            val i = pending.removeFirst()
            val cur = held[i.target]!!
            val target = rel(i.target)
            Live.current = target
            val curFile = onDisk[cur]!!
            val curSource = manifest.files[cur]
            val better = curSource != null && curSource != Words.nfc(i.track.path) && Builder.rank(i.track) > (known[curSource]?.let { Builder.rank(it) } ?: 9)
            val targetFile = File(i.target)
            if (curFile.path == i.target) { if (better) replace += i to curFile else placed += i to curFile; Live.done.incrementAndGet(); continue }
            if (targetFile.exists() && Words.nfc(curFile.path).lowercase() != Words.nfc(i.target).lowercase()) {
                // Its place is still taken by a song that has to move first.
                if (++stuck <= pending.size + 1) { pending.addLast(i); continue }
                Live.error("sitio ocupado: $target"); Live.done.incrementAndGet(); continue
            }
            stuck = 0
            val moved = io(60) {
                targetFile.parentFile.mkdirs()
                runCatching { java.nio.file.Files.move(curFile.toPath(), targetFile.toPath()); true }.getOrElse { ex -> lastIoError = "${ex.javaClass.simpleName}: ${ex.message}"; false } || run {
                    // Same name in another form (accents, case): through a temporary name.
                    val tmp = File(targetFile.parentFile, ".mv-" + System.nanoTime() + ".mp3")
                    curFile.renameTo(tmp) && tmp.renameTo(targetFile)
                }
            }
            if (moved == null) { Live.error("pCloud no responde al mover $cur (se deja para la próxima vez)"); Live.done.incrementAndGet(); continue }
            if (moved == true) {
                manifest.files.remove(cur)
                manifest.files[target] = curSource ?: Words.nfc(i.track.path)
                Scanner.moved(curFile.path, targetFile.path)
                Live.moved.incrementAndGet()
                ops.log("app", "moved $cur → $target")
                if (better) replace += i to targetFile else placed += i to targetFile
            } else { Live.error("no se pudo mover $cur ($lastIoError)"); ops.log("app", "error moving $cur → $target: $lastIoError") }
            Live.done.incrementAndGet()
            if (Live.done.get() % 50 == 0) manifest.save()
            Live.write()
        }
        manifest.save()
        // 2b. Tags: only files whose tags differ from the plan are rewritten (a few at a time);
        // better copies replace the old ones.
        Live.phase = "corrigiendo etiquetas y cambiando por mejores copias"
        fun differs(i: Builder.Item, f: File): Boolean {
            val t = Scanner.get(f.path)?.tags ?: return true
            fun same(k: String, v: String?) = Words.nfc(t[k].orEmpty()).trim() == Words.nfc(v.orEmpty()).trim()
            return !(same("title", i.title) && same("artist", i.artist) && same("album", i.album) && same("album_artist", i.albumArtist) &&
                (t["date"] ?: t["year"]).orEmpty().take(4) == i.year.orEmpty() && t["track"]?.substringBefore('/')?.trim()?.toIntOrNull() == i.trackNo)
        }
        val retag = placed.filter { (i, f) -> differs(i, f) }.map { it to false } + replace.map { it to true }
        Live.total += retag.size
        val tagPool = Executors.newFixedThreadPool(workers)
        for ((pair, better) in retag) tagPool.submit {
            if (Live.stop) return@submit
            val (i, f) = pair
            Live.current = rel(f.path)
            val tmp = i.copy(target = File(f.parentFile, ".new-" + f.name).path)
            File(tmp.target).delete()
            val result = if (better) Builder.copy(tmp, covers) else Builder.copy(tmp, covers, from = f)
            result.onSuccess {
                val path = f.path
                val del = runCatching { java.nio.file.Files.delete(f.toPath()); null }.getOrElse { ex -> "${ex.javaClass.simpleName}: ${ex.message}" }
                if (del == null && File(tmp.target).renameTo(File(path))) {
                    if (better) manifest.files[rel(path)] = Words.nfc(i.track.path)
                    Scanner.put(libraryTrack(i, File(path)))
                    if (better) Live.copied.incrementAndGet()
                    ops.log("app", (if (better) "replaced with a better copy " else "tags fixed ") + rel(path))
                } else { File(tmp.target).delete(); Live.error("no se pudo cambiar ${rel(path)} (${del ?: "no se pudo renombrar"})") }
            }.onFailure { Live.error("${rel(f.path)}: ${it.message}"); ops.log("app", "error fixing ${rel(f.path)}: ${it.message}") }
            if (Live.done.incrementAndGet() % 25 == 0) { manifest.save(); covers.save() }
            Live.write()
        }
        tagPool.shutdown()
        while (!tagPool.awaitTermination(2, TimeUnit.SECONDS)) Live.write()
        manifest.save(); covers.save()
        // 3. Copy what the library still lacks. Into pCloud Drive, in batches of about 10 GB: each
        // made on the Mac's own disk, then moved into pCloud, and the next one only once pCloud has
        // uploaded it (pCloud holds files it is still uploading, and its cache fills the disk).
        Live.phase = "copiando lo que falta"
        val missing = plan.copy.filter { it.target !in held && !File(it.target).exists() }
        val onPCloud = PCloud.isDrive(root)
        fun estimate(i: Builder.Item) = if (i.track.lossless) ((i.track.seconds ?: 240.0) * 40_000).toLong() else i.track.size
        val batches = if (!onPCloud) listOf(missing) else missing.fold(ArrayList<ArrayList<Builder.Item>>()) { acc, i ->
            if (acc.isEmpty() || acc.last().sumOf(::estimate) + estimate(i) > PCloud.BATCH) acc.add(ArrayList())
            acc.last().add(i); acc
        }
        val staging = File(System.getProperty("user.home"), "Music/.bitchord-staging")
        for ((n, batch) in batches.withIndex()) {
            if (Live.stop) break
            if (onPCloud) PCloud.waitReady(rootFile, "antes del lote ${n + 1} de ${batches.size}")
            Live.phase = if (onPCloud) "lote ${n + 1} de ${batches.size}: preparando en el Mac" else "copiando lo que falta"
            val pool = Executors.newFixedThreadPool(workers)
            val made = java.util.Collections.synchronizedList(ArrayList<Pair<Builder.Item, File>>())
            for (i in batch) pool.submit {
                if (Live.stop) return@submit
                Live.current = rel(i.target)
                val local = if (onPCloud) i.copy(target = File(staging, rel(i.target)).path) else i
                File(local.target).delete()
                Builder.copy(local, covers).onSuccess {
                    made += i to File(local.target)
                    synchronized(covers) { covers.propose(i.key, plan.alternatives[i.key].orEmpty()) }
                }.onFailure { Live.error("${i.track.path}: ${it.message}"); ops.log("app", "error copying ${i.track.path}: ${it.message}") }
                if (!onPCloud) Live.done.incrementAndGet()
                Live.write()
            }
            pool.shutdown()
            while (!pool.awaitTermination(2, TimeUnit.SECONDS)) Live.write()
            // Into the library (on pCloud: a move from the Mac's disk into pCloud Drive).
            if (onPCloud) Live.phase = "lote ${n + 1} de ${batches.size}: pasando a pCloud"
            for ((i, f) in made) {
                val target = File(i.target)
                val ok = if (!onPCloud) true else io(300) {
                    target.parentFile.mkdirs()
                    runCatching { java.nio.file.Files.move(f.toPath(), target.toPath()); true }.getOrElse { ex -> lastIoError = "${ex.javaClass.simpleName}: ${ex.message}"; false }
                } == true
                if (ok) {
                    manifest.files[rel(i.target)] = Words.nfc(i.track.path)
                    Scanner.put(libraryTrack(i, target))
                    Live.copied.incrementAndGet()
                    ops.log("app", "copied ${i.track.path} → ${rel(i.target)} [${i.reason}]")
                } else Live.error("no se pudo pasar a pCloud ${rel(i.target)} ($lastIoError)")
                if (onPCloud) Live.done.incrementAndGet()
                Live.write()
            }
            manifest.save(); covers.save(); Scanner.saveIndex()
        }
        if (onPCloud) staging.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        Live.done.addAndGet(plan.copy.size - missing.size - held.size)
        manifest.save(); covers.save(); Scanner.saveIndex()
        // What pCloud wouldn't delete before: deleted now if it lets.
        File(rootFile, ".papelera").listFiles()?.forEach { runCatching { java.nio.file.Files.delete(it.toPath()) } }
        // 4. Empty folders go.
        rootFile.walkBottomUp().filter { it.isDirectory && it != rootFile && !it.path.contains("/.bitchord-library") }.forEach { d ->
            val left = d.listFiles().orEmpty()
            if (left.all { it.name == ".DS_Store" }) { left.forEach { it.delete() }; d.delete() }
        }
        val summary = (if (Live.stop) "Parado" else "Terminado") +
            ": ${Live.copied.get()} copiadas, ${Live.moved.get()} movidas a su sitio, ${Live.deleted.get()} quitadas, ${Live.failed.get()} con error" +
            (if (unknown.isNotEmpty()) ", ${unknown.size} archivos sin identificar (en live.json)" else "")
        ops.log("app", "build: $summary")
        ops.flush()
        Live.end(summary)
        return summary
    }

    private fun fixCase(root: File, dirs: Set<String>) {
        val wanted = dirs.flatMap { d -> d.split('/').indices.map { n -> d.split('/').take(n + 1).joinToString("/") } }.toSortedSet(compareBy({ it.count { c -> c == '/' } }, { it }))
        for (d in wanted) {
            val parent = File(root, d.substringBeforeLast('/', ""))
            val name = d.substringAfterLast('/')
            val children = parent.listFiles().orEmpty()
            if (children.any { it.name == name }) continue
            val actual = children.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) } ?: continue
            val tmp = File(parent, ".case-" + System.nanoTime())
            if (actual.renameTo(tmp) && tmp.renameTo(File(parent, name))) Ops(root.path).log("app", "renamed folder ${actual.name} → $name") 
        }
    }

    @Volatile private var lastIoError = ""
    private val ioPool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /** A file operation that pCloud Drive may hold: given up on after [seconds] (null). */
    private fun <T> io(seconds: Long, block: () -> T): T? = runCatching { ioPool.submit(java.util.concurrent.Callable { block() }).get(seconds, TimeUnit.SECONDS) }.getOrNull()

    /** A file the app just wrote, as the index will know it. */
    private fun libraryTrack(i: Builder.Item, f: File) = i.track.copy(
        path = f.path, size = f.length(), modified = f.lastModified(), codec = "mp3",
        kbps = if (i.track.lossless) 320 else i.track.kbps, hasCover = true,
        tags = mapOf("title" to i.title, "artist" to i.artist, "album" to i.album, "album_artist" to i.albumArtist) +
            listOfNotNull(i.year?.let { "date" to it }, i.trackNo?.let { "track" to it.toString() }),
        fake320 = if (i.track.lossless) false else i.track.fake320,
    )
}

/**
 * Orders from Claude (or anyone) while the app is open: JSON files dropped in
 * `~/Music/.bitchord-live/inbox/` — {"cmd": "build"}, {"cmd": "stop"}, {"cmd": "scan"},
 * {"cmd": "update"}, {"cmd": "status"}, or {"cmd": "cli", "args": ["build", "--dry-run"]} for
 * any command-line order. Each is answered in `outbox/<same name>.txt` and the file moves to
 * `inbox/done/`.
 */
object Inbox {
    data class Order(val file: File, val cmd: String, val args: List<String>, val then: String?)

    fun take(root: String): List<Order> {
        val dir = File(Live.dir, "inbox").apply { mkdirs() }
        return dir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }.mapNotNull { f ->
            val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            val done = File(dir, "done").apply { mkdirs() }
            f.renameTo(File(done, f.name))
            if (o == null) { answer(root, f.name, "No es JSON válido"); null }
            else Order(f, o.optString("cmd"), o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), o.optString("then").ifBlank { null })
        }
    }

    fun answer(root: String, name: String, text: String) {
        runCatching { File(File(Live.dir, "outbox").apply { mkdirs() }, name.removeSuffix(".json") + ".txt").writeText(text) }
    }

    /** A command-line order run inside the app, its printout as the answer. */
    @Synchronized fun runCli(args: List<String>): String {
        val buf = java.io.ByteArrayOutputStream()
        val old = System.out
        val ps = java.io.PrintStream(buf, true, "UTF-8")
        System.setOut(ps)
        try { cli(args.toTypedArray(), inApp = true) } catch (e: Throwable) { println("ERROR: $e") } finally { System.setOut(old); ps.flush() }
        return buf.toString("UTF-8")
    }
}

/**
 * Updates the app itself from the project's Mac releases (`mac-library-1.0.N` on GitHub):
 * downloads the newest .dmg, then — once the app has closed — swaps it into place and opens it
 * again. Nothing in the library is touched.
 */
object Updater {
    val current: String by lazy { Updater::class.java.getResource("/bitchord-library-version.txt")?.readText()?.trim() ?: "0" }
    private const val API = "https://api.github.com/repos/JuanmiRodas/Musichord/releases?per_page=40"

    data class Release(val version: String, val dmg: String)

    private fun num(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }.let { p -> p.getOrElse(0) { 0 } * 1_000_000 + p.getOrElse(1) { 0 } * 1000 + p.getOrElse(2) { 0 } }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "BitChord-Library"); c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000; c.readTimeout = 20000
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
    }.getOrNull()

    private fun release(r: JSONObject): Release? {
        if (!r.optString("tag_name").startsWith("mac-library-")) return null
        val v = r.getString("tag_name").removePrefix("mac-library-")
        val assets = r.optJSONArray("assets") ?: return null
        val dmg = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".dmg") } ?: return null
        return Release(v, dmg.getString("browser_download_url"))
    }

    /** The newest Mac release: from the list, and by asking for the next versions one by one (the list can lag). */
    fun latest(): Release? {
        val listed = get(API.replace("per_page=40", "per_page=100"))?.let { t -> runCatching { JSONArray(t) }.getOrNull() }
            ?.let { a -> (0 until a.length()).mapNotNull { release(a.getJSONObject(it)) } }.orEmpty()
        val parts = current.split('.')
        val base = parts.dropLast(1).joinToString(".")
        val n = parts.lastOrNull()?.toIntOrNull()
        val probed = if (n == null || base.isEmpty()) emptyList() else (n + 1..n + 6).mapNotNull { i ->
            get("https://api.github.com/repos/JuanmiRodas/Musichord/releases/tags/mac-library-$base.$i")?.let { runCatching { release(JSONObject(it)) }.getOrNull() }
        }
        return (listed + probed).maxByOrNull { num(it.version) }
    }

    fun newer(): Release? = latest()?.takeIf { num(it.version) > num(current) }

    /** Downloads [r] and leaves a helper waiting for this app to close; the caller then quits. */
    fun prepare(r: Release, logTo: File? = null, progress: (String) -> Unit = {}): Result<Unit> = runCatching {
        val dir = File(System.getProperty("user.home"), "Library/Caches/BitChord Library").apply { mkdirs() }
        val dmg = File(dir, "BitChord-Library-${r.version}.dmg")
        progress("Descargando la versión ${r.version}…")
        var c = URL(r.dmg).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", "BitChord-Library")
        var hops = 0
        while (c.responseCode in 300..399 && hops++ < 5) { val to = c.getHeaderField("Location"); c = URL(to).openConnection() as HttpURLConnection; c.setRequestProperty("User-Agent", "BitChord-Library") }
        c.inputStream.use { input -> dmg.outputStream().use { input.copyTo(it) } }
        check(dmg.length() > 5_000_000) { "descarga incompleta" }
        val launcher = System.getProperty("jpackage.app-path")
        val app = launcher?.let { File(it).parentFile?.parentFile?.parentFile }?.takeIf { it.name.endsWith(".app") } ?: File("/Applications/BitChord Library.app")
        val script = File(dir, "update.sh")
        script.writeText(
            """
            #!/bin/bash
            set -x
            date
            while kill -0 ${ProcessHandle.current().pid()} 2>/dev/null; do sleep 0.5; done
            MP=${'$'}(mktemp -d)
            hdiutil attach -nobrowse -quiet -mountpoint "${'$'}MP" "${dmg.path}" || exit 1
            SRC=${'$'}(ls -d "${'$'}MP"/*.app | head -1)
            rm -rf "${app.path}.old"
            mv "${app.path}" "${app.path}.old" 2>/dev/null
            ditto "${'$'}SRC" "${app.path}" || mv "${app.path}.old" "${app.path}"
            hdiutil detach -quiet "${'$'}MP"
            xattr -cr "${app.path}"
            rm -rf "${app.path}.old"
            open "${app.path}"
            """.trimIndent() + "\n"
        )
        script.setExecutable(true)
        val log = logTo ?: File(dir, "update.log")
        // Detached twice over, so it outlives the app it is waiting for.
        ProcessBuilder("/bin/bash", "-c", "( nohup /bin/bash '${script.path}' > '${log.path}' 2>&1 & ) ; exit 0")
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor(10, TimeUnit.SECONDS)
        progress("Versión ${r.version} descargada: la app se cierra y se vuelve a abrir sola")
    }
}

/**
 * pCloud Drive: files it hasn't uploaded yet can't be moved or deleted, and what is written
 * there waits in its cache on the Mac's disk until uploaded. The app asks pCloud how many
 * operations it still has pending (its own database, read only); if that can't be read, it tries
 * moving a test file back and forth.
 */
object PCloud {
    const val BATCH = 10L * 1024 * 1024 * 1024
    fun isDrive(path: String) = path.contains("/pCloud Drive/") || path.endsWith("/pCloud Drive")

    /** Operations pCloud still has to upload, from ~/.pcloud/data.db; null if it can't be read. */
    fun pending(): Int? = runCatching {
        val db = File(System.getProperty("user.home"), ".pcloud/data.db")
        if (!db.isFile) return null
        val (code, out) = Tools.run("/usr/bin/sqlite3", "-readonly", db.path, "SELECT COUNT(*) FROM fstask;", timeoutSec = 20)
        if (code != 0) null else out.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
    }.getOrNull()

    /** Whether pCloud lets a file be renamed (it doesn't while uploading it). */
    private fun probe(root: File): Boolean {
        val f = File(root, ".bitchord-check")
        if (!f.exists()) { runCatching { f.writeText("ok") }; return false }
        val g = File(root, ".bitchord-check2")
        return f.renameTo(g) && g.renameTo(f)
    }

    /** Waits (telling what) until pCloud has uploaded everything and the disk has room for a batch. */
    fun waitReady(root: File, why: String) {
        var since = System.currentTimeMillis()
        var idleSince = 0L
        while (!Live.stop) {
            val p = pending()
            val free = File(System.getProperty("user.home")).usableSpace
            val roomy = free > BATCH + 3L * 1024 * 1024 * 1024
            val idle = if (p != null) p == 0 else probe(root)
            if (idle && roomy) return
            // Uploaded but the disk still full (pCloud keeps its cache): go on after 20 minutes anyway if 5 GB are free.
            if (idle) { if (idleSince == 0L) idleSince = System.currentTimeMillis() } else idleSince = 0L
            if (idle && System.currentTimeMillis() - idleSince > 1_200_000 && free > 5L * 1024 * 1024 * 1024) return
            Live.phase = "esperando a pCloud $why: " + (if (p != null) "$p operaciones por subir" else "aún subiendo") +
                (if (!roomy) " · ${free / 1_000_000_000} GB libres en el Mac" else "")
            Live.current = ""
            Live.write(force = true)
            if (System.currentTimeMillis() - since > 600_000) { Ops(root.path).log("app", "pCloud: ${Live.phase}"); since = System.currentTimeMillis() }
            Thread.sleep(30_000)
        }
    }
}
