package bitchord.library

import kotlinx.coroutines.runBlocking
import java.io.File

private const val HELP = """BitChord Library — línea de comandos (el mismo motor que la app)

Uso: bitchord-library <orden> [opciones]

Órdenes:
  status            Resumen de lo ya leído (no lee archivos)
  scan              Lee archivos nuevos o cambiados y escribe el informe (reanudable)
  todo              La to-do (las 60 primeras líneas; --all para todas)
  fake320           Busca falsos 320 entre los MP3 320 no revisados (reanudable)
  plan              Calcula el plan de nombres y carpetas (no mueve nada)
  apply             Aplica el plan (--only <carpeta> para una parte, --dry-run para ver)
  undo              Deshace el último lote de movimientos (--batch <id> para otro)
  convert           Convierte los FLAC de la biblioteca a MP3 320 (--only <carpeta>)
  claude            Informe para pegar en la conversación con Claude

Opciones:
  --root <carpeta>        La biblioteca (por defecto la de la app)
  --extra <a,b>           Carpetas por integrar (por defecto las de la app)
  --max-seconds <n>       Para scan y fake320: parar a tiempo y seguir en la siguiente vuelta
  --only <carpeta>        Relativa a la biblioteca, p. ej. "Garnet Crow"
  --dry-run               Enseñar sin mover
"""

/** The command line: `BitChord Library <command> …` (or the jar, `java -jar bitchord-library-cli.jar …`). */
fun cli(args: Array<String>) = runBlocking {
    fun opt(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    fun flag(name: String) = "--$name" in args
    val cmd = args.firstOrNull() ?: "help"
    val settings = Store.load()
    val root = (opt("root") ?: settings.root).trimEnd('/')
    val extra = opt("extra")?.split(',')?.map { it.trim().trimEnd('/') }?.filter { it.isNotEmpty() } ?: settings.extra
    val folders = (listOf(root) + extra).filter { File(it).isDirectory }
    val deadline = System.currentTimeMillis() + (opt("max-seconds")?.toLongOrNull() ?: 86_400L) * 1000
    if (!File(root).isDirectory && cmd != "help") { println("No encuentro la biblioteca: $root"); return@runBlocking }
    val ops = Ops(root)
    Scanner.loadIndex()

    fun computed(): Triple<List<Track>, Map<String, List<Audit.Issue>>, List<Todo>> {
        val tracks = Scanner.known(folders)
        val lib = tracks.filter { it.path.startsWith(root) }
        val dups = Audit.duplicates(lib)
        val issues = tracks.associate { it.path to Audit.issues(it, dups) }
        return Triple(tracks, issues, Todos.build(root, lib, issues))
    }

    when (cmd) {
        "status", "scan" -> {
            if (cmd == "scan") {
                val complete = Scanner.scanUntil(folders, deadline) { done, total -> if (done % 500 == 0) println("  $done / $total") }
                ops.log("cli", if (complete) "scan complete" else "scan partial (run again to go on)")
                if (!complete) println("Lectura parcial: vuelve a lanzar «scan» para seguir.")
            }
            val (tracks, issues, todos) = computed()
            println(ops.report(tracks, issues, todos, extra).let { r ->
                org.json.JSONObject().put("library", r.get("library")).put("to_merge", r.get("to_merge")).put("issues", r.get("issues")).toString(2)
            })
        }
        "todo" -> {
            val (_, _, todos) = computed()
            (if (flag("all")) todos else todos.take(60)).forEach { println("${it.group} | ${it.what} | ${it.reason}") }
            println("— ${todos.size} líneas · CSV completo en ${File(ops.state, "todo.csv").path}")
        }
        "fake320" -> {
            val todo = Scanner.known(folders).filter { it.mp3_320 && it.fake320 == null }
            var n = 0
            var fake = 0
            for (t in todo) {
                if (System.currentTimeMillis() > deadline) break
                if (Scanner.checkFake(t).fake320 == true) { fake++; println("FALSO 320: ${ops.rel(t.path)}") }
                if (++n % 50 == 0) Scanner.saveIndex()
            }
            Scanner.saveIndex()
            ops.log("cli", "fake320: $n checked, $fake fake, ${todo.size - n} left")
            println("$n revisados, $fake falsos, ${todo.size - n} pendientes")
        }
        "plan" -> {
            val moves = ops.planNames(Scanner.known(listOf(root)))
            ops.log("cli", "plan: ${moves.size} moves")
            moves.take(25).forEach { println("${it.from}\n  → ${it.to}") }
            println("— ${moves.size} movimientos en ${File(ops.state, "plan.json").path}")
        }
        "apply" -> {
            val (done, skipped) = ops.apply(opt("only"), flag("dry-run"), "cli")
            println(if (flag("dry-run")) "$done se moverían, $skipped no" else "$done movidos, $skipped saltados (se puede deshacer con «undo»)")
        }
        "undo" -> println("${ops.undo(opt("batch"), "cli")} archivos devueltos a su sitio")
        "convert" -> {
            val only = opt("only")
            val list = Scanner.known(listOf(root)).filter { it.lossless && (only == null || ops.rel(it.path).startsWith(only)) }
            var ok = 0
            for (t in list) {
                if (System.currentTimeMillis() > deadline) break
                Convert.toMp3(t, root).onSuccess { ok++; println("MP3 320: ${ops.rel(it)}") }.onFailure { println("ERROR ${ops.rel(t.path)}: ${it.message}") }
            }
            ops.log("cli", "convert: $ok of ${list.size} lossless files made MP3 320")
        }
        "claude" -> println(ops.forClaude())
        else -> println(HELP)
    }
}
