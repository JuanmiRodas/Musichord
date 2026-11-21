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
 * What the app is doing, for the window and for Claude: `<library>/.bitchord-library/live.json`
 * (rewritten every few seconds) and one line per file in `log.txt`.
 */
object Live {
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

    fun write(force: Boolean = false) {
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
            File(r, ".bitchord-library/live.json").writeText(o.toString(2))
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
            if (f.delete()) { Live.deleted.incrementAndGet(); manifest.files.remove(r); Scanner.forget(f.path); ops.log("app", "removed $r (no song of the plan)") }
            else Live.error("no se pudo quitar $r")
            Live.done.incrementAndGet(); Live.write()
        }
        // 2. Put each song held at its place (and in its tags); a better copy replaces it.
        Live.phase = "ordenando lo ya copiado"
        val pending = ArrayDeque(plan.copy.filter { it.target in held })
        var stuck = 0
        while (pending.isNotEmpty() && !Live.stop) {
            val i = pending.removeFirst()
            val cur = held[i.target]!!
            val target = rel(i.target)
            Live.current = target
            val curFile = onDisk[cur]!!
            val curSource = manifest.files[cur]
            val better = curSource != null && curSource != Words.nfc(i.track.path) && Builder.rank(i.track) > (known[curSource]?.let { Builder.rank(it) } ?: 9)
            if (curFile.path == i.target && !better) { Live.done.incrementAndGet(); continue }
            val targetFile = File(i.target)
            if (targetFile.exists() && curFile.path.lowercase() != i.target.lowercase()) {
                // Its place is still taken by a song that has to move first.
                if (++stuck <= pending.size + 1) { pending.addLast(i); continue }
                Live.error("sitio ocupado: $target"); Live.done.incrementAndGet(); continue
            }
            stuck = 0
            val tmp = i.copy(target = File(targetFile.parentFile, ".new-" + targetFile.name).path)
            File(tmp.target).delete()
            val result = if (better) Builder.copy(tmp, covers) else Builder.copy(tmp, covers, from = curFile)
            result.onSuccess {
                if (curFile.path != targetFile.path) curFile.delete() else targetFile.delete()
                if (File(tmp.target).renameTo(targetFile)) {
                    manifest.files.remove(cur)
                    manifest.files[target] = if (better) Words.nfc(i.track.path) else curSource ?: Words.nfc(i.track.path)
                    Scanner.forget(curFile.path)
                    Scanner.put(libraryTrack(i, targetFile))
                    if (better) Live.copied.incrementAndGet() else Live.moved.incrementAndGet()
                    ops.log("app", (if (better) "replaced (better copy) " else "moved ") + "$cur → $target")
                } else Live.error("no se pudo renombrar $target")
            }.onFailure { Live.error("$cur: ${it.message}"); ops.log("app", "error moving $cur: ${it.message}") }
            Live.done.incrementAndGet()
            if (Live.done.get() % 25 == 0) { manifest.save(); covers.save() }
            Live.write()
        }
        manifest.save(); covers.save()
        // 3. Copy what the library still lacks, a few at a time.
        Live.phase = "copiando lo que falta"
        val missing = plan.copy.filter { it.target !in held && !File(it.target).exists() }
        val pool = Executors.newFixedThreadPool(workers)
        for (i in missing) pool.submit {
            if (Live.stop) return@submit
            Live.current = rel(i.target)
            Builder.copy(i, covers).onSuccess {
                manifest.files[rel(i.target)] = Words.nfc(i.track.path)
                synchronized(covers) { covers.propose(i.key, plan.alternatives[i.key].orEmpty()) }
                Scanner.put(libraryTrack(i, File(i.target)))
                Live.copied.incrementAndGet()
                ops.log("app", "copied ${i.track.path} → ${rel(i.target)} [${i.reason}]")
            }.onFailure { Live.error("${i.track.path}: ${it.message}"); ops.log("app", "error copying ${i.track.path}: ${it.message}") }
            if (Live.done.incrementAndGet() % 25 == 0) { manifest.save(); synchronized(covers) { covers.save() } }
            Live.write()
        }
        pool.shutdown()
        while (!pool.awaitTermination(2, TimeUnit.SECONDS)) Live.write()
        Live.done.addAndGet(plan.copy.size - missing.size - held.size)
        manifest.save(); covers.save(); Scanner.saveIndex()
        // 4. Empty folders go.
        rootFile.walkBottomUp().filter { it.isDirectory && it != rootFile && !it.path.contains("/.bitchord-library") }.forEach { d ->
            val left = d.listFiles().orEmpty()
            if (left.all { it.name == ".DS_Store" }) { left.forEach { it.delete() }; d.delete() }
        }
        val summary = (if (Live.stop) "Parado" else "Terminado") +
            ": ${Live.copied.get()} copiadas, ${Live.moved.get()} movidas a su sitio, ${Live.deleted.get()} quitadas, ${Live.failed.get()} con error" +
            (if (unknown.isNotEmpty()) ", ${unknown.size} archivos sin identificar (en live.json)" else "")
        ops.log("app", "build: $summary")
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
 * `<library>/.bitchord-library/inbox/` — {"cmd": "build"}, {"cmd": "stop"}, {"cmd": "scan"},
 * {"cmd": "update"}, {"cmd": "status"}, or {"cmd": "cli", "args": ["build", "--dry-run"]} for
 * any command-line order. Each is answered in `outbox/<same name>.txt` and the file moves to
 * `inbox/done/`.
 */
object Inbox {
    data class Order(val file: File, val cmd: String, val args: List<String>, val then: String?)

    fun take(root: String): List<Order> {
        val dir = File(root, ".bitchord-library/inbox").apply { mkdirs() }
        return dir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }.mapNotNull { f ->
            val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            val done = File(dir, "done").apply { mkdirs() }
            f.renameTo(File(done, f.name))
            if (o == null) { answer(root, f.name, "No es JSON válido"); null }
            else Order(f, o.optString("cmd"), o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), o.optString("then").ifBlank { null })
        }
    }

    fun answer(root: String, name: String, text: String) {
        runCatching { File(File(root, ".bitchord-library/outbox").apply { mkdirs() }, name.removeSuffix(".json") + ".txt").writeText(text) }
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

    fun latest(): Release? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "BitChord-Library"); c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000; c.readTimeout = 20000
        val a = JSONArray(c.inputStream.bufferedReader().readText())
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("tag_name").startsWith("mac-library-") }.mapNotNull { r ->
            val v = r.getString("tag_name").removePrefix("mac-library-")
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val dmg = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".dmg") } ?: return@mapNotNull null
            Release(v, dmg.getString("browser_download_url"))
        }.maxByOrNull { num(it.version) }
    }.getOrNull()

    fun newer(): Release? = latest()?.takeIf { num(it.version) > num(current) }

    /** Downloads [r] and leaves a helper waiting for this app to close; the caller then quits. */
    fun prepare(r: Release, progress: (String) -> Unit = {}): Result<Unit> = runCatching {
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
        ProcessBuilder("/bin/bash", "-c", "nohup '${script.path}' > '${dir.path}/update.log' 2>&1 &").start().waitFor(10, TimeUnit.SECONDS)
        progress("Versión ${r.version} descargada: la app se cierra y se vuelve a abrir sola")
    }
}
