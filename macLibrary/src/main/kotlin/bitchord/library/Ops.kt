package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What the app and the command line share, inside the library itself
 * (`<Música>/.bitchord-library/`, synced by pCloud):
 *  - `report.json`: the last read of the library (summary, to-do);
 *  - `plan.json`: the renames/moves proposed, as paths relative to the library;
 *  - `journal.jsonl`: every move done, so any of them can be undone;
 *  - `log.txt`: what the app and the command line did, for Claude to read.
 */
class Ops(val root: String) {
    val state = File(root, ".bitchord-library").apply { mkdirs() }
    private val journal = File(state, "journal.jsonl")
    private val planFile = File(state, "plan.json")
    // On the Mac's own disk (see Live.dir): pCloud Drive shows files late and doesn't take appends.
    private val logFile = File(Live.dir, "log.txt")

    /**
     * One line per thing done. pCloud Drive doesn't take appends, so the last 4000 lines are
     * kept in memory and the whole file is written again (at most every 2 s, and at the end).
     */
    fun log(who: String, text: String) {
        val line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " [$who] " + text
        println(line)
        synchronized(Ops) {
            if (lines.isEmpty()) runCatching { logFile.readLines().takeLast(4000).forEach { lines.addLast(it) } }
            lines.addLast(line)
            while (lines.size > 4000) lines.removeFirst()
            val now = System.currentTimeMillis()
            if (now - written > 2000 || text.startsWith("build:")) { written = now; flush() }
        }
    }

    fun flush() = synchronized(Ops) {
        runCatching {
            val tmp = File(Live.dir, "log.txt.tmp")
            tmp.writeText(lines.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(logFile)) { logFile.writeText(tmp.readText()); tmp.delete() }
        }
    }

    companion object {
        private val lines = ArrayDeque<String>()
        @Volatile private var written = 0L
    }

    fun rel(path: String) = path.removePrefix(root).trimStart('/')
    fun abs(rel: String) = File(root, rel).path

    // ── Report ──

    fun report(tracks: List<Track>, issues: Map<String, List<Audit.Issue>>, todos: List<Todo>, extra: List<String>): JSONObject {
        val lib = tracks.filter { it.path.startsWith(root) }
        val other = tracks - lib.toSet()
        fun counts(list: List<Track>) = JSONObject()
            .put("files", list.size)
            .put("mp3_320", list.count { it.mp3_320 })
            .put("lossless", list.count { it.lossless })
            .put("mp3_under_320", list.count { it.codec == "mp3" && !it.mp3_320 })
            .put("forbidden_format", list.count { !it.lossless && it.codec != "mp3" })
            .put("fake_320", list.count { it.fake320 == true })
            .put("fake_320_checked", list.count { it.fake320 != null })
            .put("no_cover", list.count { !it.hasCover })
        val issueCounts = JSONObject()
        Audit.Issue.entries.forEach { i -> issueCounts.put(i.name, lib.count { i in issues[it.path].orEmpty() }) }
        val r = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("root", root)
            .put("library", counts(lib))
            .put("to_merge", counts(other).put("folders", JSONArray(extra)))
            .put("issues", issueCounts)
            .put("todo", JSONArray(todos.take(2000).map {
                JSONObject().put("group", it.group).put("what", it.what).put("reason", it.reason).put("count", it.count)
                    .put("paths", JSONArray(it.paths.take(50).map(::rel)))
            }))
        File(state, "report.json").writeText(r.toString(2))
        File(state, "todo.csv").writeText(Todos.csv(todos))
        return r
    }

    // ── Names plan ──

    data class Move(val from: String, val to: String)

    /** The agreed names, as moves inside the library (only files whose name or folder changes). */
    fun planNames(tracks: List<Track>): List<Move> {
        val lib = tracks.filter { it.path.startsWith(root) && !it.path.contains("/.bitchord-library/") && !it.path.contains("/_Archivo BitChord/") }
        val moves = lib.mapNotNull { t -> Naming.target(root, t)?.takeIf { it != t.path }?.let { Move(rel(t.path), rel(it)) } }
        // Two files wanting the same name: neither moves (shown in the plan as a clash).
        val clash = moves.groupBy { it.to.lowercase() }.filter { it.value.size > 1 }.keys
        val ok = moves.filter { it.to.lowercase() !in clash }
        planFile.writeText(JSONObject()
            .put("at", System.currentTimeMillis())
            .put("moves", JSONArray(ok.map { JSONObject().put("from", it.from).put("to", it.to) }))
            .put("clashes", JSONArray(moves.filter { it.to.lowercase() in clash }.map { JSONObject().put("from", it.from).put("to", it.to) }))
            .toString(2))
        return ok
    }

    fun loadPlan(): List<Move> = runCatching {
        val a = JSONObject(planFile.readText()).getJSONArray("moves")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Move(o.getString("from"), o.getString("to")) } }
    }.getOrDefault(emptyList())

    /**
     * The plan's moves whose source starts with [only] (a folder, or everything), each
     * written to the journal before the next. A target already there is skipped, never
     * overwritten. Empty folders left behind stay (pCloud and you can remove them).
     */
    fun apply(only: String?, dryRun: Boolean, who: String): Pair<Int, Int> {
        val batch = "batch-" + System.currentTimeMillis()
        var done = 0
        var skipped = 0
        for (m in loadPlan().filter { only == null || it.from.startsWith(only) }) {
            val from = File(abs(m.from))
            val to = File(abs(m.to))
            if (!from.isFile || to.exists()) { skipped++; continue }
            if (dryRun) { println("${m.from}\n  → ${m.to}"); done++; continue }
            to.parentFile.mkdirs()
            if (from.renameTo(to)) {
                Scanner.moved(from.path, to.path)
                journal.appendText(JSONObject().put("batch", batch).put("from", m.from).put("to", m.to).put("at", System.currentTimeMillis()).toString() + "\n")
                done++
            } else skipped++
        }
        if (!dryRun) Scanner.saveIndex()
        if (!dryRun) log(who, "apply ${only ?: "(all)"}: $done moved, $skipped skipped ($batch)")
        return done to skipped
    }

    /** Puts the last batch (or [batch]) of moves back where they were. */
    fun undo(batch: String?, who: String): Int {
        val lines = runCatching { journal.readLines() }.getOrDefault(emptyList()).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val target = batch ?: lines.lastOrNull()?.optString("batch") ?: return 0
        var n = 0
        lines.filter { it.optString("batch") == target }.reversed().forEach { o ->
            val from = File(abs(o.getString("to")))
            val to = File(abs(o.getString("from")))
            if (from.isFile && !to.exists()) {
                to.parentFile.mkdirs()
                if (from.renameTo(to)) { Scanner.moved(from.path, to.path); n++ }
            }
        }
        Scanner.saveIndex()
        journal.writeText(lines.filter { it.optString("batch") != target }.joinToString("") { it.toString() + "\n" })
        log(who, "undo $target: $n moved back")
        return n
    }

    fun batches(): List<Pair<String, Int>> = runCatching { journal.readLines() }.getOrDefault(emptyList())
        .mapNotNull { runCatching { JSONObject(it).optString("batch") }.getOrNull() }
        .groupingBy { it }.eachCount().toList()

    /** What to paste to Claude: the summary and the last lines of the log. */
    fun forClaude(): String = buildString {
        appendLine("BitChord Library — informe")
        runCatching { appendLine(JSONObject(File(state, "report.json").readText()).let { r ->
            JSONObject().put("library", r.get("library")).put("to_merge", r.get("to_merge")).put("issues", r.get("issues")).toString(2)
        }) }
        appendLine("Plan de nombres: ${loadPlan().size} movimientos pendientes; lotes hechos: ${batches().joinToString { "${it.first} (${it.second})" }}")
        appendLine("Versión de la app: ${Updater.current}")
        runCatching { appendLine("— Última tarea —\n" + File(Live.dir, "live.json").readText()) }
        runCatching { CoverReview.load().let { p -> appendLine("Portadas: " + p.groupingBy { it.status }.eachCount().entries.joinToString { "${it.value} ${it.key}" }) } }
        runCatching { appendLine("— Plan —\n" + File(Live.dir, "plan-overview.txt").readText()) }
        runCatching { File(Live.dir, "no-sustituidas.txt").readLines().take(80).forEach { appendLine(it) } }
        runCatching { File(Live.dir, "update.log").readLines().takeLast(8).takeIf { it.isNotEmpty() }?.let { appendLine("— Actualizaciones —"); it.forEach { l -> appendLine(l) } } }
        appendLine("— Últimas líneas del registro —")
        runCatching { logFile.readLines().takeLast(40).forEach { appendLine(it) } }
    }
}
