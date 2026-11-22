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
 * Which library file is which song (`<library>/.bitchord-library/built.json`, version 2 — the
 * contract with the phone in LIBRARY_LINK.md): for each file, a stable `id` (also in the MP3, tag
 * TXXX:BITCHORD_ID), its `source` and every other source `copies` merged into it, its length and
 * names; plus the last 5000 `moves` and `removed` files. Kept on the Mac (`~/Music/.bitchord-live`)
 * and put in the library after each step. Reads version 1 (file → source) too.
 */
class Manifest(root: String) {
    data class Entry(val id: String, val source: String, val copies: List<String> = emptyList(), val seconds: Double? = null,
                     val title: String? = null, val artist: String? = null, val album: String? = null)

    private val local = File(Live.dir, "built-" + Integer.toHexString(Words.nfc(root).hashCode()) + ".json")
    private val inLibrary = File(root, ".bitchord-library/built.json")
    private val entries = LinkedHashMap<String, Entry>()
    private val moves = ArrayList<JSONObject>()
    private val removed = ArrayList<JSONObject>()

    init {
        val text = listOf(local, inLibrary).filter { it.isFile }.maxByOrNull { it.lastModified() }?.let { runCatching { it.readText() }.getOrNull() }
        runCatching {
            val o = JSONObject(text!!)
            val f = o.getJSONObject("files")
            f.keys().forEach { k ->
                val v = f.get(k)
                entries[Words.nfc(k)] = if (v is JSONObject) Entry(
                    v.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, Words.nfc(v.optString("source")),
                    v.optJSONArray("copies")?.let { a -> (0 until a.length()).map { Words.nfc(a.getString(it)) } }.orEmpty(),
                    v.optDouble("seconds").takeIf { !it.isNaN() }, v.optString("title").ifBlank { null }, v.optString("artist").ifBlank { null }, v.optString("album").ifBlank { null },
                ) else Entry(java.util.UUID.randomUUID().toString(), Words.nfc(v.toString()))
            }
            o.optJSONArray("moves")?.let { a -> (0 until a.length()).forEach { moves += a.getJSONObject(it) } }
            o.optJSONArray("removed")?.let { a -> (0 until a.length()).forEach { removed += a.getJSONObject(it) } }
        }
    }

    @Synchronized fun source(rel: String): String? = entries[rel]?.source
    @Synchronized fun id(rel: String): String? = entries[rel]?.id
    @Synchronized fun has(rel: String) = entries.containsKey(rel)

    /** [rel] now holds [item] (made from [source]); its id is kept when it had one ([keepId]). */
    @Synchronized fun put(rel: String, item: Builder.Item, source: String = Words.nfc(item.track.path), keepId: String? = null): String {
        val id = keepId ?: entries[rel]?.id ?: java.util.UUID.randomUUID().toString()
        entries[rel] = Entry(id, source, item.copies.map { Words.nfc(it) }.filter { it != source }, item.track.seconds, item.title, item.artist, item.album)
        return id
    }

    @Synchronized fun adopt(rel: String, source: String) { if (!entries.containsKey(rel)) entries[rel] = Entry(java.util.UUID.randomUUID().toString(), source) }

    @Synchronized fun move(from: String, to: String, item: Builder.Item) {
        val old = entries.remove(from)
        val id = put(to, item, old?.source ?: Words.nfc(item.track.path), old?.id)
        moves += JSONObject().put("id", id).put("from", from).put("to", to).put("at", System.currentTimeMillis())
        while (moves.size > 5000) moves.removeAt(0)
    }

    @Synchronized fun remove(rel: String, keptAs: String? = null) {
        val old = entries.remove(rel) ?: return
        removed += JSONObject().put("id", old.id).put("path", rel).put("keptAs", keptAs ?: JSONObject.NULL).put("at", System.currentTimeMillis())
        while (removed.size > 5000) removed.removeAt(0)
    }

    @Synchronized fun json(): String = JSONObject().put("version", 2).put("updated", System.currentTimeMillis())
        .put("files", JSONObject().apply {
            entries.forEach { (k, e) ->
                put(k, JSONObject().put("id", e.id).put("source", e.source).put("copies", JSONArray(e.copies)).put("seconds", e.seconds ?: JSONObject.NULL)
                    .put("title", e.title ?: JSONObject.NULL).put("artist", e.artist ?: JSONObject.NULL).put("album", e.album ?: JSONObject.NULL))
            }
        })
        .put("moves", JSONArray(moves)).put("removed", JSONArray(removed)).toString()

    /** Written on the Mac at once (a .tmp then renamed); [publish] puts it in the library too. */
    fun save(publish: ((File) -> Unit)? = null) = runCatching {
        val tmp = File(local.parentFile, local.name + ".tmp")
        tmp.writeText(json())
        tmp.renameTo(local)
        if (publish != null) publish(local) else { inLibrary.parentFile.mkdirs(); inLibrary.writeText(local.readText()) }
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
        val lib: Lib = if (PCloud.isDrive(root)) PCloudLib.open(root) ?: run {
            val msg = "La biblioteca está en pCloud Drive: inicia sesión en pCloud en Ajustes para construirla (la app trabaja con pCloud directamente)"
            Live.end(msg); return msg
        } else LocalLib(root)
        val bySource = HashMap<String, Builder.Item>()
        plan.copy.forEach { i -> i.copies.forEach { bySource[Words.nfc(it)] = i } }
        val byTarget = plan.copy.associateBy { rel(it.target).lowercase() }
        val skippedPaths = plan.skipped.mapTo(HashSet()) { Words.nfc(it.first.path) }
        val known = tracks.associateBy { Words.nfc(it.path) }
        val temp = File(System.getProperty("user.home"), "Music/.bitchord-staging").apply { mkdirs() }
        // built.json goes into the library through the same way as the songs (pCloud's API there).
        val publish: (File) -> Unit = { f -> val t = File(temp, "built-" + System.nanoTime() + ".json"); f.copyTo(t, true); lib.put(t, ".bitchord-library/built.json"); t.delete() }

        // What the library holds now.
        Live.phase = "mirando lo que ya hay (${lib.name})"
        val present = runCatching { lib.list() }.getOrElse { val msg = "No se pudo leer la biblioteca: ${it.message}"; Live.end(msg); return msg }
        val held = HashMap<String, LibFile>() // item's target → file holding it
        val unwanted = ArrayList<LibFile>()
        val leftovers = ArrayList<LibFile>()
        for (f in present.values.sortedBy { it.rel }) {
            val src = manifest.source(f.rel)
            val item = src?.let { bySource[it] } ?: byTarget[f.rel.lowercase()]
            when {
                item == null && src != null && src in skippedPaths -> unwanted += f // made by the app, now left out (karaoke, quarantine…)
                item == null -> leftovers += f
                held.containsKey(item.target) -> unwanted += f // a second copy of one song (or a pCloud "(1)" copy)
                else -> held[item.target] = f
            }
        }
        // Files the app made before it kept a manifest: told by length and title (from what the app read of them).
        val byLength = plan.copy.filter { it.target !in held }.groupBy { ((it.track.seconds ?: 0.0) * 2).toInt() }
        val unknown = ArrayList<String>()
        for (f in leftovers) {
            val t = lib.local(f)?.let { Scanner.get(it.path) }
            val s = t?.seconds
            val name = Words.plain(File(f.rel).nameWithoutExtension)
            val match = if (s == null) null else (-1..1).flatMap { byLength[(s * 2).toInt() + it].orEmpty() }
                .filter { it.target !in held && kotlin.math.abs((it.track.seconds ?: 0.0) - s) < 1.0 && Words.plain(it.title).let { w -> w.length >= 2 && name.contains(w) } }
                .distinctBy { it.target }.singleOrNull()
            if (match != null) { held[match.target] = f; manifest.adopt(f.rel, Words.nfc(match.track.path)) } else unknown += f.rel
        }
        Live.unmatched = unknown
        if (unknown.isNotEmpty()) ops.log("app", "library: ${unknown.size} files not made by the app and matching no song (left where they are): ${unknown.take(5)}")
        if (unwanted.size > 50 && plan.copy.size < present.size) { ops.log("app", "build: plan smaller than the library (sources not read?) — nothing removed"); unwanted.clear() }

        Live.total = plan.copy.size + unwanted.size
        // 1. Remove what the plan no longer wants (only files the app made, or second copies).
        Live.phase = "quitando lo que sobra"
        for (f in unwanted) {
            if (Live.stop) break
            Live.current = f.rel
            val err = lib.delete(f)
            if (err == null) {
                Live.deleted.incrementAndGet()
                val kept = manifest.source(f.rel)?.let { bySource[it] }?.let { held[it.target] }?.let { manifest.id(it.rel) }
                manifest.remove(f.rel, kept); ops.log("app", "removed ${f.rel}")
            }
            else Live.error("no se pudo quitar ${f.rel}: $err")
            Live.done.incrementAndGet(); Live.write()
        }
        // 2. Put each song held at its place (a move: nothing is copied or uploaded again).
        Live.phase = "ordenando lo ya copiado"
        val pending = ArrayDeque(plan.copy.filter { it.target in held })
        val placed = ArrayList<Pair<Builder.Item, LibFile>>()
        val replace = ArrayList<Builder.Item>()
        var stuck = 0
        while (pending.isNotEmpty() && !Live.stop) {
            val i = pending.removeFirst()
            val cur = held[i.target]!!
            val target = rel(i.target)
            Live.current = target
            val curSource = manifest.source(cur.rel)
            val better = curSource != null && curSource != Words.nfc(i.track.path) && Builder.rank(i.track) > (known[curSource]?.let { Builder.rank(it) } ?: 9)
            if (cur.actual == target) {
                manifest.put(target, i, curSource ?: Words.nfc(i.track.path), manifest.id(target))
                if (better) replace += i else placed += i to cur; Live.done.incrementAndGet(); continue
            }
            if (lib.exists(target) && cur.rel.lowercase() != target.lowercase()) {
                if (++stuck <= pending.size + 1) { pending.addLast(i); continue }
                Live.error("sitio ocupado: $target"); Live.done.incrementAndGet(); continue
            }
            stuck = 0
            val err = lib.move(cur, target)
            if (err == null) {
                manifest.move(cur.rel, target, i)
                lib.local(cur)?.let { old -> Scanner.moved(old.path, File(root, target).path) }
                Live.moved.incrementAndGet()
                ops.log("app", "moved ${cur.rel} → $target")
                if (better) replace += i else placed += i to cur.copy(rel = target, actual = target)
            } else { Live.error("no se pudo mover ${cur.rel}: $err"); ops.log("app", "error moving ${cur.rel} → $target: $err") }
            Live.done.incrementAndGet()
            if (Live.done.get() % 200 == 0) manifest.save(publish)
            Live.write()
        }
        manifest.save(publish)

        // 3. Write what is missing, what has wrong tags and what has a better copy now — each made
        // on the Mac from its source, then put in the library (uploaded, on pCloud).
        fun differs(i: Builder.Item): Boolean {
            val t = Scanner.get(File(root, rel(i.target)).path)?.tags ?: return false // unknown tags: left as they are
            fun same(k: String, v: String?) = Words.nfc(t[k].orEmpty()).trim() == Words.nfc(v.orEmpty()).trim()
            return !(same("title", i.title) && same("artist", i.artist) && same("album", i.album) && same("album_artist", i.albumArtist) &&
                (t["date"] ?: t["year"]).orEmpty().take(4) == i.year.orEmpty() && t["track"]?.substringBefore('/')?.trim()?.toIntOrNull() == i.trackNo)
        }
        val missing = plan.copy.filter { it.target !in held && !lib.exists(rel(it.target)) }
        val rewrite = placed.map { it.first }.filter(::differs) + replace
        val work = rewrite.map { it to "rewrite" } + missing.map { it to "copy" }
        Live.total = Live.done.get() + work.size
        Live.phase = "escribiendo: ${missing.size} que faltan, ${rewrite.size} con etiquetas o copia mejor"
        val pool = Executors.newFixedThreadPool(workers)
        for ((i, why) in work) pool.submit {
            if (Live.stop) return@submit
            val target = rel(i.target)
            Live.current = target
            val tmp = File(temp, System.nanoTime().toString() + ".mp3")
            val id = manifest.id(target) ?: java.util.UUID.randomUUID().toString()
            Builder.copy(i.copy(target = tmp.path), covers, id = id).onSuccess {
                val err = lib.put(tmp, target)
                if (err == null) {
                    manifest.put(target, i, keepId = id)
                    Scanner.put(libraryTrack(i, File(root, target), tmp.length().takeIf { it > 0 } ?: i.track.size))
                    if (why == "copy") { Live.copied.incrementAndGet(); synchronized(covers) { covers.propose(i.key, plan.alternatives[i.key].orEmpty()) } } else Live.moved.incrementAndGet()
                    ops.log("app", (if (why == "copy") "copied ${i.track.path} → " else "rewritten ") + "$target [${i.reason}]")
                } else { Live.error("no se pudo escribir $target: $err"); ops.log("app", "error writing $target: $err") }
            }.onFailure { Live.error("${i.track.path}: ${it.message}"); ops.log("app", "error converting ${i.track.path}: ${it.message}") }
            tmp.delete()
            if (Live.done.incrementAndGet() % 100 == 0) { manifest.save(publish); synchronized(covers) { covers.save() } }
            Live.write()
        }
        pool.shutdown()
        while (!pool.awaitTermination(2, TimeUnit.SECONDS)) Live.write()
        manifest.save(publish); covers.save(); Scanner.saveIndex()
        // 4. Empty folders go.
        if (!Live.stop) { Live.phase = "quitando carpetas vacías"; Live.write(force = true); runCatching { lib.tidy() } }
        val summary = (if (Live.stop) "Parado" else "Terminado") +
            ": ${Live.copied.get()} copiadas, ${Live.moved.get()} movidas o corregidas, ${Live.deleted.get()} quitadas, ${Live.failed.get()} con error" +
            (if (unknown.isNotEmpty()) ", ${unknown.size} archivos sin identificar (en live.json)" else "")
        ops.log("app", "build: $summary")
        ops.flush()
        Live.end(summary)
        return summary
    }

    /** A file the app just wrote, as the index will know it. */
    private fun libraryTrack(i: Builder.Item, f: File, size: Long) = i.track.copy(
        path = f.path, size = size, modified = System.currentTimeMillis(), codec = "mp3",
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
