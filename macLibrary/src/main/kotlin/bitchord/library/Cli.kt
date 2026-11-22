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
  sources           Las fuentes (solo lectura) y sus reglas
  build             Construye la biblioteca y la ordena con el plan (mueve lo ya copiado,
                    copia lo que falta, quita lo que sobra): --source "<nombre>",
                    --dry-run para ver el plan, --only <texto> para filtrar lo que se enseña
  albums            Información y escaneos de los álbumes de 5+ canciones (VGMdb, MusicBrainz,
                    pocketmonsters.net, canta-per-me.net) en <álbum>/Imágenes; --dry-run, --only
  quarantine        Lo que nunca entra en la biblioteca (quarantine.json)
  covers            Portadas alternativas propuestas pendientes de aprobar
  free              Carpetas de las fuentes ya cubiertas por la biblioteca (para liberar espacio)
  archive           Copia a --to <carpeta> y comprueba las carpetas cubiertas (--folder <texto>);
                    con --delete, después las borra del Mac

Opciones:
  --root <carpeta>        La biblioteca (por defecto la de la app)
  --extra <a,b>           Carpetas por integrar (por defecto las de la app)
  --max-seconds <n>       Para scan y fake320: parar a tiempo y seguir en la siguiente vuelta
  --only <carpeta>        Relativa a la biblioteca, p. ej. "Garnet Crow"
  --dry-run               Enseñar sin mover
"""

/** The command line: `BitChord Library <command> …` (or the jar, `java -jar bitchord-library-cli.jar …`). */
fun cli(args: Array<String>, inApp: Boolean = false) = runBlocking {
    fun opt(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    fun flag(name: String) = "--$name" in args
    val cmd = args.firstOrNull() ?: "help"
    val settings = Store.load()
    val root = Paths.resolve(opt("root") ?: settings.root).trimEnd('/')
    val sources = Sources.load()
    val extra = opt("extra")?.split(',')?.map { Paths.resolve(it.trim()).trimEnd('/') }?.filter { it.isNotEmpty() } ?: settings.extra
    val folders = (listOf(root) + extra).filter { File(it).isDirectory }
    val deadline = System.currentTimeMillis() + (opt("max-seconds")?.toLongOrNull() ?: 86_400L) * 1000
    if (cmd != "help") File(root).mkdirs()
    val ops = Ops(root)
    if (!inApp) Scanner.loadIndex()

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
        "sources" -> sources.forEach { s ->
            println("${s.name}: ${s.path} (${if (File(s.path).isDirectory) "ok" else "NO ENCONTRADA"}, ${s.mode}${s.target?.let { " → $it" } ?: ""})")
        }
        "build" -> {
            val wanted = opt("source")
            val chosen = sources.filter { wanted == null || it.name.contains(wanted, ignoreCase = true) }
            if (chosen.isEmpty()) { println("No hay ninguna fuente «$wanted». Mira «sources»."); return@runBlocking }
            val known = Scanner.known(listOf(root) + chosen.map { it.path })
            val plan = Builder.plan(chosen, known, root)
            val only = opt("only")
            val items = plan.copy.filter { only == null || it.target.contains(only, ignoreCase = true) || it.track.path.contains(only, ignoreCase = true) }
            println("Fuentes: ${chosen.joinToString { it.name }} · ${known.size - Scanner.known(listOf(root)).size} archivos leídos")
            println("En la biblioteca ${items.size}: ${items.count { it.reason == "MP3 320" }} MP3 320, ${items.count { it.reason.startsWith("sin pérdida") }} desde sin pérdida, " +
                "${items.count { it.reason.contains("mientras") }} por debajo de 320 (mientras tanto, en la to-do)")
            println("No se copian ${plan.skipped.size}: " + plan.skipped.groupingBy { it.second.substringBefore(":") }.eachCount().entries.joinToString { "${it.value} ${it.key}" })
            File(ops.state, "build-plan.csv").writeText(buildString {
                appendLine("origen,destino,motivo")
                items.forEach { appendLine("\"${it.track.path}\",\"${it.target}\",\"${it.reason}\"") }
                plan.skipped.forEach { (t, why) -> appendLine("\"${t.path}\",,\"$why\"") }
            })
            if (flag("dry-run")) {
                items.take(30).forEach { println("${it.track.path.removePrefix(it.source.path)}\n  → ${it.target.removePrefix(root)}  [${it.reason}]") }
                println("— Lista completa en ${File(ops.state, "build-plan.csv").path}")
                return@runBlocking
            }
            println(Engine.build(chosen, known, root))
        }
        "albums" -> {
            val res = AlbumArt.run(root, opt("only"), flag("dry-run"), deadline) { println("… $it") }
            res.forEach { println("${it.album.rel}: ${it.note}${it.release?.let { r -> " — ${r.url}" } ?: ""}") }
            println("— ${res.count { it.note.startsWith("seguro") }} seguros, ${res.sumOf { it.saved }} imágenes guardadas, ${res.count { it.note.startsWith("dudoso") }} dudosos, ${res.count { it.release == null }} sin resultados")
        }
        "quarantine" -> Builder.Quarantine.load(root).let { q ->
            println("Carpetas en cuarentena: ${q.folders.joinToString()}")
            q.files.entries.forEach { println("${it.key}  (${it.value})") }
            println("— ${q.files.size} archivos sueltos en cuarentena")
        }
        "covers" -> Covers(root).pending().let { p ->
            p.take(40).forEach { println("${it.key}: ${it.alternative.name} (de ${it.from})") }
            println("— ${p.size} portadas alternativas por revisar (apruébalas o descártalas en la app, pestaña To-do)")
        }
        "free", "archive" -> {
            val folders = FreeSpace.scan(sources, Scanner.known(listOf(root) + sources.map { it.path }), root)
            val full = folders.filter { it.covered == it.files }
            if (cmd == "free") {
                full.forEach { println("LISTA  ${"%.1f".format(it.bytes / 1e9)} GB  ${it.source.name} / ${File(it.path).name} (${it.files} canciones)") }
                folders.filter { it.covered < it.files }.take(30).forEach { println("FALTAN ${it.files - it.covered} de ${it.files}  ${it.source.name} / ${File(it.path).name}") }
                println("— ${full.size} carpetas listas para archivar: ${"%.1f".format(full.sumOf { it.bytes } / 1e9)} GB")
            } else {
                val to = opt("to") ?: return@runBlocking println("Falta --to <carpeta de archivo>")
                val only = opt("folder")
                full.filter { only == null || File(it.path).name.contains(only, ignoreCase = true) }.forEach { f ->
                    if (System.currentTimeMillis() > deadline) return@forEach
                    FreeSpace.archive(f, Paths.resolve(to), delete = flag("delete"))
                        .onSuccess { println("ARCHIVADA ${File(f.path).name} → $it") }.onFailure { println("ERROR ${File(f.path).name}: ${it.message}") }
                }
                ops.log("cli", "archive ${only ?: "(all ready)"} → $to${if (flag("delete")) " (deleted from the Mac)" else ""}")
            }
        }
        else -> println(HELP)
    }
}
