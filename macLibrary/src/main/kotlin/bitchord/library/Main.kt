package bitchord.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Everything the window shows, in one place. */
class LibraryState {
    var settings by mutableStateOf(Store.load())
    var tracks by mutableStateOf<List<Track>>(emptyList())
    var issues by mutableStateOf<Map<String, List<Audit.Issue>>>(emptyMap())
    var todos by mutableStateOf<List<Todo>>(emptyList())
    var phoneTodos by mutableStateOf<List<Todo>>(emptyList())
    var busy by mutableStateOf<String?>(null)
    var progress by mutableStateOf(0f)
    var message by mutableStateOf<String?>(null)

    val libraryTracks get() = tracks.filter { it.path.startsWith(settings.root) }

    fun recompute() {
        val lib = libraryTracks
        val dups = Audit.duplicates(lib)
        issues = tracks.associate { it.path to Audit.issues(it, dups) }
        todos = Todos.build(settings.root, lib, issues)
        phoneTodos = Todos.phone(settings.phoneTodo)
        // Shared with the command line (and readable by Claude): the report in the library itself.
        runCatching { ops().report(tracks, issues, todos, settings.extra) }
    }

    fun ops() = Ops(settings.root)

    /** Re-reads the library from the index after the command line (or a plan) changed it. */
    fun reload() {
        tracks = Scanner.known(listOf(settings.root) + settings.extra)
        recompute()
    }

    var update by mutableStateOf<Updater.Release?>(null)
    lateinit var scope: kotlinx.coroutines.CoroutineScope
    /** Set by the window: quits the app (for an update). */
    var quit: () -> Unit = {}

    fun scan(then: (() -> Unit)? = null) {
        if (busy != null) return
        scope.launch {
            busy = "Leyendo la biblioteca y las fuentes…"
            File(settings.root).mkdirs()
            Live.root = settings.root
            val folders = listOf(settings.root) + settings.extra
            tracks = Scanner.scan(folders.filter { File(it).isDirectory }) { done, total ->
                progress = done.toFloat() / total.coerceAtLeast(1)
                Live.phase = "leyendo $done de $total"; Live.write()
            }
            recompute()
            busy = null
            message = "${tracks.size} archivos leídos"
            Live.phase = message.orEmpty(); Live.write(force = true)
            runCatching { ops().log("app", "scan: ${tracks.size} files") }
            then?.invoke()
        }
    }

    /** Builds and puts the library in order with the plan (see [Engine.build]). */
    fun build() {
        if (busy != null) return
        scope.launch {
            busy = "Construyendo y ordenando la biblioteca…"
            val ticker = launch { while (true) { busy = Live.summary(); progress = Live.done.get().toFloat() / Live.total.coerceAtLeast(1); kotlinx.coroutines.delay(1000) } }
            val summary = withContext(Dispatchers.IO) { runCatching { Engine.build(Sources.load(), tracks, settings.root) }.getOrElse { "Error: ${it.message}" } }
            ticker.cancel()
            busy = null
            message = summary
            reload()
        }
    }

    fun stop() { Live.stop = true; message = "Parando en cuanto termine el archivo en curso…" }

    /** Downloads the newest version and restarts into it; [then] (e.g. "build") runs after the restart. */
    fun installUpdate(then: String? = null) {
        val r = update ?: return
        scope.launch {
            // Whatever is running stops first (a scan or a build goes on where it was after the restart).
            if (busy != null) {
                Live.stop = true; busy = "Parando lo que está en marcha para actualizar…"
                withContext(Dispatchers.IO) { repeat(60) { if (Live.phase.isBlank() || Live.summary().isBlank()) return@repeat; Thread.sleep(500) } }
                Live.stop = false
            }
            busy = "Actualizando a la versión ${r.version}…"
            val ok = withContext(Dispatchers.IO) {
                if (then != null) runCatching { File(Live.dir, "resume.json").writeText(org.json.JSONObject().put("then", then).toString()) }
                Updater.prepare(r, File(Live.dir, "update.log")) { busy = it }
            }
            ok.onSuccess { Scanner.saveIndex(); quit() }.onFailure { Updater.note("failed: $it"); busy = null; message = "No se pudo actualizar: ${it.message}" }
        }
    }

    /** Orders left in the inbox (by Claude): run one at a time while the app is open. */
    suspend fun inbox() {
        for (o in withContext(Dispatchers.IO) { Inbox.take(settings.root) }) {
            Live.lastCommand = "${o.cmd} ${o.args.joinToString(" ")}".trim()
            ops().log("inbox", "order: ${Live.lastCommand}")
            val answer = when (o.cmd) {
                "status" -> Live.summary() + "\nVersión ${Updater.current}" + (update?.let { " (hay ${it.version})" } ?: "")
                "stop" -> { stop(); "Parando" }
                "scan" -> if (busy != null) "Ocupada: ${Live.summary()}" else { scan(); "Leyendo" }
                "build" -> if (busy != null) "Ocupada: ${Live.summary()}" else { build(); "Construyendo y ordenando" }
                "scan+build" -> if (busy != null) "Ocupada: ${Live.summary()}" else { scan { build() }; "Leyendo y después construyendo" }
                "update" -> {
                    update = withContext(Dispatchers.IO) { Updater.newer() }
                    if (update == null) "Ya es la última versión (${Updater.current})" else { installUpdate(o.then); "Actualizando a ${update?.version}" }
                }
                "cli" -> withContext(Dispatchers.IO) { Inbox.runCli(o.args) }
                else -> "Orden desconocida: ${o.cmd} (status, stop, scan, build, scan+build, update, cli)"
            }
            withContext(Dispatchers.IO) { Inbox.answer(settings.root, o.file.name, answer) }
            Live.write(force = true)
        }
    }
}

fun main(args: Array<String>) {
    // BitChord Audio: with arguments, the same engine on the command line (see Cli.kt).
    if (args.isNotEmpty()) { cli(args); return }
    gui()
}

private fun gui() = application {
    Scanner.loadIndex()
    val state = remember { LibraryState() }
    // Quit for good (the updater waits for this process to end before swapping the app).
    state.quit = { Scanner.saveIndex(); exitApplication(); Thread { Thread.sleep(1500); kotlin.system.exitProcess(0) }.start() }
    Window(
        onCloseRequest = { Live.stop = true; Scanner.saveIndex(); exitApplication() },
        title = "BitChord Library",
        state = rememberWindowState(width = 1200.dp, height = 820.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7FD3C8), surface = Color(0xFF15181C), background = Color(0xFF0F1114))) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(state) }
        }
    }
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SUMMARY("Resumen", Icons.Filled.Insights),
    TODO("To-do", Icons.Filled.Checklist),
    COVERS("Portadas", Icons.Filled.Image),
    NAMES("Nombres", Icons.Filled.DriveFileRenameOutline),
    FREE("Liberar espacio", Icons.Filled.CleaningServices),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

@Composable
private fun App(state: LibraryState) {
    var tab by remember { mutableStateOf(Tab.SUMMARY) }
    val scope = rememberCoroutineScope()
    state.scope = scope
    fun scan() = state.scan()
    LaunchedEffect(Unit) {
        Live.root = state.settings.root
        // After an update: what was asked to run next ("build").
        val resume = File(Live.dir, "resume.json")
        val then = runCatching { org.json.JSONObject(resume.readText()).optString("then") }.getOrNull()
        resume.delete()
        state.scan { if (then == "build") state.build() }
        state.update = withContext(Dispatchers.IO) { Updater.newer() }
    }
    // Orders from Claude, and the live status file, every few seconds.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            runCatching { state.inbox() }
            if (Live.job != null || state.busy != null || n++ % 20 == 0) Live.write(force = true)
            kotlinx.coroutines.delay(3000)
        }
    }
    Row(Modifier.fillMaxSize()) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            Spacer(Modifier.height(12.dp))
            Tab.entries.forEach { t ->
                NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scan() }, enabled = state.busy == null) { Icon(Icons.Filled.Refresh, "Volver a leer") }
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            state.update?.let { u ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hay una versión nueva: ${u.version} (tienes ${Updater.current})", color = Color.White, modifier = Modifier.weight(1f))
                    Button(onClick = { state.installUpdate() }) { Text("Actualizar y reiniciar") }
                }
                Spacer(Modifier.height(8.dp))
            }
            state.busy?.let { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                    if (Live.job != null) OutlinedButton(onClick = { state.stop() }) { Text("Parar") }
                }
                if (Live.current.isNotEmpty() && Live.job != null) Text(Live.current, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            when (tab) {
                Tab.SUMMARY -> Summary(state, scope)
                Tab.TODO -> TodoList(state)
                Tab.COVERS -> CoversPane(state, scope)
                Tab.NAMES -> Names(state)
                Tab.FREE -> FreeSpacePane(state, scope)
                Tab.SETTINGS -> SettingsPane(state)
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(18.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Line(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.weight(1f))
        Text(value, color = if (warn) Color(0xFFFFB4A9) else Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Summary(state: LibraryState, scope: kotlinx.coroutines.CoroutineScope) {
    val lib = state.libraryTracks
    val extra = state.tracks - lib.toSet()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card("Biblioteca limpia · ${lib.size} archivos") {
                Text(state.settings.root, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Line("MP3 320", lib.count { it.mp3_320 }.toString())
                Line("Sin pérdida (pendientes de convertir a MP3 320)", lib.count { it.lossless }.toString())
                Line("MP3 por debajo de 320", lib.count { it.codec == "mp3" && !it.mp3_320 }.toString(), warn = true)
                Line("AAC / m4a / opus / webm / wma", lib.count { !it.lossless && it.codec != "mp3" }.toString(), warn = true)
                Line("Falsos 320 comprobados", "${lib.count { it.fake320 == true }} de ${lib.count { it.fake320 != null }} revisados", warn = true)
            }
        }
        item {
            Card("Etiquetas y portadas") {
                Audit.Issue.entries.filter { it !in Audit.REPLACE && it != Audit.Issue.LOSSLESS }.forEach { i ->
                    Line(i.label, lib.count { i in state.issues[it.path].orEmpty() }.toString(), warn = true)
                }
            }
        }
        if (extra.isNotEmpty()) item {
            Card("Fuentes (solo lectura) · ${extra.size} archivos") {
                state.settings.extra.forEach { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) }
                Line("MP3 320", extra.count { it.mp3_320 }.toString())
                Line("Sin pérdida", extra.count { it.lossless }.toString())
                Line("Por debajo de 320 u otro formato", extra.count { !it.mp3_320 && !it.lossless }.toString(), warn = true)
                Line("Sin portada", extra.count { !it.hasCover }.toString(), warn = true)
            }
        }
        item {
            Card("Construir y ordenar la biblioteca") {
                Text(
                    "De cada canción se copia la mejor versión de las fuentes (sin pérdida → MP3 320; si no, MP3 320 auténtico; si no, el mejor MP3 mientras tanto, apuntado en la to-do). " +
                        "AAC, m4a, opus, webm y wma no se copian. Lo ya copiado se mueve a su sitio (no se copia otra vez) y lo que el plan ya no quiere se quita. Las fuentes no se tocan nunca.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                var preview by remember(state.tracks) { mutableStateOf<String?>(null) }
                preview?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = state.busy == null, onClick = {
                        scope.launch {
                            state.busy = "Calculando el plan…"
                            preview = withContext(Dispatchers.IO) { Inbox.runCli(listOf("build", "--dry-run")).lines().take(3).joinToString("\n") }
                            state.busy = null
                        }
                    }) { Text("Ver el plan") }
                    Button(enabled = state.busy == null, onClick = { state.build() }) { Text("Construir y ordenar") }
                    if (Live.job != null) OutlinedButton(onClick = { state.stop() }) { Text("Parar") }
                }
                val quarantine = remember(state.tracks) { Builder.Quarantine.load(state.settings.root) }
                if (quarantine.folders.isNotEmpty() || quarantine.files.isNotEmpty()) Text(
                    "En cuarentena (no se copian; las fuentes no se tocan): ${quarantine.folders.joinToString { it.substringAfterLast('/') }}" +
                        (if (quarantine.files.isNotEmpty()) " y ${quarantine.files.size} copias suyas en otras carpetas" else ""),
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
                if (Live.unmatched.isNotEmpty()) Text("${Live.unmatched.size} archivos de la biblioteca que la app no hizo y no reconoce se han dejado donde estaban (lista en live.json)", color = Color(0xFFFFB4A9), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Card("Información y escaneos de los álbumes") {
                Text(
                    "Para cada álbum con 5 canciones o más: portada, contraportada, libreto, discos… y sus datos (número de catálogo, fecha, créditos) de VGMdb, MusicBrainz y Cover Art Archive, " +
                        "pocketmonsters.net (Pokémon) y canta-per-me.net (Yuki Kajiura). Solo cuando es seguro que es el mismo álbum (las duraciones coinciden); se guardan en «Imágenes» dentro de la carpeta del álbum.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = state.busy == null, onClick = {
                        scope.launch {
                            state.busy = "Buscando información y escaneos…"
                            val ticker = launch { while (true) { state.busy = Live.summary() + " · " + Live.current; state.progress = Live.done.get().toFloat() / Live.total.coerceAtLeast(1); kotlinx.coroutines.delay(1000) } }
                            val res = withContext(Dispatchers.IO) { runCatching { AlbumArt.run(state.settings.root) }.getOrDefault(emptyList()) }
                            ticker.cancel()
                            state.busy = null
                            state.message = "${res.count { it.note.startsWith("seguro") }} álbumes con datos, ${res.sumOf { it.saved }} imágenes, ${res.count { it.note.startsWith("dudoso") }} dudosos (ver registro)"
                        }
                    }) { Text("Buscar escaneos") }
                }
            }
        }
        item {
            Card("Hablar con Claude") {
                Text(
                    "Claude ve lo que hace la app (live.json y log.txt en ~/Music/.bitchord-live) y le puede dar órdenes mientras está abierta " +
                        "(archivos en ~/Music/.bitchord-live/inbox: construir, parar, leer, actualizar o cualquier orden de la línea de comandos). Versión ${Updater.current}.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = state.busy == null, onClick = {
                    scope.launch {
                        state.update = withContext(Dispatchers.IO) { Updater.newer() }
                        state.message = state.update?.let { "Hay una versión nueva: ${it.version}" } ?: "Ya tienes la última versión (${Updater.current})"
                    }
                }) { Text("Buscar actualización") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val text = state.ops().forClaude()
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                    state.message = "Informe copiado: pégalo en la conversación con Claude"
                }) { Text("Copiar informe para Claude") }
            }
        }
        item {
            Card("Comprobaciones") {
                Text(
                    "Busca falsos 320: lee 20 s de cada MP3 320 y mira si hay sonido por encima de 16,5 kHz. Tarda (unos minutos por cada mil canciones).",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(enabled = state.busy == null, onClick = {
                    scope.launch {
                        state.busy = "Buscando falsos 320…"
                        val todo = state.tracks.filter { it.mp3_320 && it.fake320 == null }
                        withContext(Dispatchers.IO) {
                            val done = java.util.concurrent.atomic.AtomicInteger()
                            val checked = java.util.Collections.synchronizedMap(HashMap<String, Track>())
                            todo.parallelStream().forEach { t ->
                                checked[t.path] = Scanner.checkFake(t)
                                state.progress = done.incrementAndGet().toFloat() / todo.size.coerceAtLeast(1)
                            }
                            state.tracks = state.tracks.map { checked[it.path] ?: it }
                            Scanner.saveIndex()
                        }
                        state.recompute()
                        state.busy = null
                        state.message = "${todo.size} MP3 320 revisados: ${state.tracks.count { it.fake320 == true }} falsos"
                    }
                }) { Text("Buscar falsos 320") }
                Spacer(Modifier.height(8.dp))
                val lossless = lib.filter { it.lossless }
                Button(enabled = state.busy == null && lossless.isNotEmpty(), onClick = {
                    scope.launch {
                        state.busy = "Convirtiendo a MP3 320…"
                        var ok = 0
                        val errors = ArrayList<String>()
                        withContext(Dispatchers.IO) {
                            lossless.forEachIndexed { i, t ->
                                Convert.toMp3(t, state.settings.root).onSuccess { ok++ }.onFailure { errors += "${File(t.path).name}: ${it.message}" }
                                state.progress = (i + 1f) / lossless.size
                            }
                        }
                        state.busy = null
                        state.message = "$ok convertidas a MP3 320 (originales en _Archivo BitChord)" + if (errors.isNotEmpty()) " · ${errors.size} con error: ${errors.first()}" else ""
                    }
                }) { Text("Convertir ${lossless.size} sin pérdida a MP3 320") }
            }
        }
    }
}

@Composable
private fun TodoList(state: LibraryState) {
    var phone by remember { mutableStateOf(false) }
    var showCovers by remember { mutableStateOf(false) }
    val covers = remember(state.settings.root, state.tracks) { Covers(state.settings.root) }
    var proposals by remember(covers) { mutableStateOf(covers.pending()) }
    val list = if (phone) state.phoneTodos else state.todos
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !phone && !showCovers, onClick = { phone = false; showCovers = false }, label = { Text("Biblioteca (${state.todos.size})") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = phone && !showCovers, onClick = { phone = true; showCovers = false }, label = { Text("Solo en el móvil (${state.phoneTodos.size})") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = showCovers, onClick = { showCovers = true }, label = { Text("Portadas alternativas (${proposals.size})") })
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                val d = FileDialog(null as Frame?, "Guardar la to-do", FileDialog.SAVE).apply { file = "to-do biblioteca.csv"; isVisible = true }
                if (d.file != null) File(d.directory, d.file).writeText(Todos.csv(list))
            }) { Text("Exportar CSV") }
        }
        if (showCovers) {
            Text(
                "Portadas de otras publicaciones donde está la misma grabación. Apruébalas para que BitChord pueda mostrarlas como portadas alternativas.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(proposals.take(500)) { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(p.primary)
                        Text("  →  ", color = Color.White)
                        CoverImage(p.alternative)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.key.substringBefore('|'), color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text("De: " + File(p.from).name, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { covers.decide(p.key, p.alternative.name, false); covers.save(); proposals = covers.pending() }) { Text("Descartar") }
                        Button(onClick = { covers.decide(p.key, p.alternative.name, true); covers.save(); proposals = covers.pending() }) { Text("Aprobar") }
                    }
                }
            }
        } else {
            Text(
                "Lo que hay que sustituir por una versión MP3 320 auténtica o sin pérdida de la misma grabación, agrupado por discografía, álbum o canción.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list) { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t.what, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${t.group} · ${t.reason}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (t.paths.isNotEmpty()) TextButton(onClick = {
                            runCatching { ProcessBuilder("open", "-R", t.paths.first()).start() }
                        }) { Text("Mostrar") }
                    }
                }
            }
        }
    }
}

/**
 * Covers to review: for each album (or loose song), the cover it has now and the one found.
 * Sure ones (≥ 90 %: same album, artist and tracks) can be approved all at once; the rest one by
 * one; with nothing found, a Google Images search and a field for an image's address.
 */
@Composable
private fun CoversPane(state: LibraryState, scope: kotlinx.coroutines.CoroutineScope) {
    var list by remember { mutableStateOf(CoverReview.load()) }
    var filter by remember { mutableStateOf(0) } // 0 sure, 1 to review, 2 nothing found, 3 done
    fun reload() { list = CoverReview.load() }
    val shown = list.filter { p ->
        val c = p.candidates.firstOrNull()?.confidence ?: 0
        when (filter) {
            0 -> p.status == "propuesta" && c >= 90
            1 -> p.status == "propuesta" && c < 90
            2 -> p.status == "sin propuesta"
            else -> p.status in setOf("aprobada", "descartada", "igual")
        }
    }.sortedByDescending { it.songs }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Seguras (≥90 %)", "Revisar (<90 %)", "Sin propuesta", "Hechas").forEachIndexed { k, label ->
                val n = list.count { p -> val c = p.candidates.firstOrNull()?.confidence ?: 0
                    when (k) { 0 -> p.status == "propuesta" && c >= 90; 1 -> p.status == "propuesta" && c < 90; 2 -> p.status == "sin propuesta"; else -> p.status in setOf("aprobada", "descartada", "igual") } }
                if (filter == k) Button(onClick = { filter = k }) { Text("$label · $n") } else OutlinedButton(onClick = { filter = k }) { Text("$label · $n") }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = state.busy == null, onClick = {
                scope.launch {
                    state.busy = "Buscando portadas…"
                    val ticker = launch { while (true) { state.busy = Live.summary() + " · " + Live.current; state.progress = Live.done.get().toFloat() / Live.total.coerceAtLeast(1); kotlinx.coroutines.delay(1000) } }
                    withContext(Dispatchers.IO) { Inbox.runCli(listOf("covers-review")) }
                    ticker.cancel(); state.busy = null; reload()
                }
            }) { Text("Buscar portadas") }
            if (filter == 0 && shown.isNotEmpty()) Button(onClick = {
                scope.launch { withContext(Dispatchers.IO) { shown.forEach { p -> p.candidates.firstOrNull()?.let { CoverReview.approve(p, it) } } }; reload() }
            }) { Text("Aprobar las ${shown.size}") }
        }
        Text("Las aprobadas se escriben en las canciones en la próxima «Construir y ordenar».", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.key }) { p ->
                var pick by remember(p.key) { mutableStateOf(0) }
                var pasted by remember(p.key) { mutableStateOf("") }
                val c = p.candidates.getOrNull(pick)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { BigCover(p.current?.let(::File)); Text("Antes", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }
                    Text("→", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { BigCover(c?.local?.let(::File)); Text(c?.let { "${it.source} · ${it.confidence} %" } ?: "—", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(p.album.ifBlank { "(canción suelta)" }, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.artist} · ${p.songs} canciones · ${p.folder}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        c?.let { Text(it.title, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (c != null && p.status == "propuesta") Button(onClick = { scope.launch { withContext(Dispatchers.IO) { CoverReview.approve(p, c) }; reload() } }) { Text("Aprobar") }
                            if (p.candidates.size > 1) OutlinedButton(onClick = { pick = (pick + 1) % p.candidates.size }) { Text("Otra (${pick + 1}/${p.candidates.size})") }
                            if (p.status == "propuesta" || p.status == "sin propuesta") OutlinedButton(onClick = { CoverReview.setStatus(p.key, "descartada"); reload() }) { Text("Dejar la actual") }
                            OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(CoverReview.googleUrl(p))) } }) { Text("Buscar en Google") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(pasted, { pasted = it }, label = { Text("Dirección de una imagen") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedButton(enabled = pasted.startsWith("http"), onClick = {
                                scope.launch { val ok = withContext(Dispatchers.IO) { CoverReview.addManual(p.key, pasted.trim()) }; if (ok) { pasted = ""; reload() } else state.message = "No se pudo descargar esa imagen" }
                            }) { Text("Proponer") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigCover(f: File?) {
    val bmp = remember(f) { f?.takeIf { it.isFile }?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }.getOrNull() } }
    Box(Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        if (bmp != null) androidx.compose.foundation.Image(bmp, null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text("sin portada", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
    }
}

@Composable
private fun CoverImage(f: File?) {
    val bmp = remember(f) {
        f?.takeIf { it.isFile }?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f))) {
        if (bmp != null) androidx.compose.foundation.Image(bmp, null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

/** Source folders whose every song is already in the library, at least as good: archived online, then off the Mac. */
@Composable
private fun FreeSpacePane(state: LibraryState, scope: kotlinx.coroutines.CoroutineScope) {
    val home = System.getProperty("user.home")
    var archive by remember { mutableStateOf("$home/pCloud Drive/OnlineOnly/Antigua biblioteca de música") }
    val folders = remember(state.tracks) { FreeSpace.scan(Sources.load(), state.tracks, state.settings.root) }
    val ready = folders.filter { it.covered == it.files }
    var chosen by remember(folders) { mutableStateOf(ready.map { it.path }.toSet()) }
    var confirm by remember { mutableStateOf(false) }
    Column {
        Text("Liberar espacio", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        Text(
            "Carpetas de tus fuentes cuyas canciones ya están TODAS en la biblioteca con igual o mejor calidad. Se copian al archivo online, se comprueba que cada archivo llegó entero y solo entonces se borran del Mac. " +
                "${ready.size} listas (${"%.1f".format(ready.sumOf { it.bytes } / 1e9)} GB).",
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp),
        )
        OutlinedTextField(archive, { archive = it }, label = { Text("Archivo online") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = chosen.isNotEmpty() && state.busy == null, onClick = { confirm = true }) {
                Text("Archivar y borrar ${chosen.size} carpetas (${"%.1f".format(ready.filter { it.path in chosen }.sumOf { it.bytes } / 1e9)} GB)")
            }
            OutlinedButton(onClick = { chosen = if (chosen.isEmpty()) ready.map { it.path }.toSet() else emptySet() }) { Text(if (chosen.isEmpty()) "Marcar todas" else "Desmarcar todas") }
        }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Archivar y borrar del Mac?") },
            text = { Text("${chosen.size} carpetas se copiarán a «$archive», se comprobarán archivo por archivo y después se borrarán del Mac. La copia online queda.") },
            confirmButton = { Button(onClick = {
                confirm = false
                scope.launch {
                    state.busy = "Archivando…"
                    var ok = 0
                    val errors = ArrayList<String>()
                    withContext(Dispatchers.IO) {
                        val list = ready.filter { it.path in chosen }
                        list.forEachIndexed { i, f ->
                            FreeSpace.archive(f, Paths.resolve(archive), delete = true).onSuccess { ok++ }.onFailure { errors += "${File(f.path).name}: ${it.message}" }
                            state.progress = (i + 1f) / list.size
                        }
                    }
                    state.ops().log("app", "free space: $ok folders archived to $archive and deleted; ${errors.size} errors ${errors.take(3)}")
                    state.busy = null
                    state.message = "$ok carpetas archivadas y borradas del Mac" + if (errors.isNotEmpty()) " · ${errors.size} con error: ${errors.first()}" else ""
                }
            }) { Text("Archivar y borrar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(folders) { f ->
                val isReady = f.covered == f.files
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = f.path in chosen, enabled = isReady, onCheckedChange = { c -> chosen = if (c) chosen + f.path else chosen - f.path })
                    Column(Modifier.weight(1f)) {
                        Text("${f.source.name} / ${File(f.path).name}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (isReady) "Todas sus ${f.files} canciones están en la biblioteca" else "Faltan ${f.files - f.covered} de ${f.files} en la biblioteca",
                            color = if (isReady) Color(0xFF7FD3C8) else Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                        )
                    }
                    Text("%.2f GB".format(f.bytes / 1e9), color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun Names(state: LibraryState) {
    val root = state.settings.root
    val changes = remember(state.tracks) {
        state.libraryTracks.mapNotNull { t -> Naming.target(root, t)?.takeIf { it != t.path }?.let { t to it } }
    }
    val missing = remember(state.tracks) { state.libraryTracks.count { Naming.target(root, it) == null } }
    Column {
        Text("Vista previa de nombres", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
        Text(
            "Carpeta «Año - Álbum (日本語)» y archivo «01 - Título (日本語) - Artista (日本語) - Álbum (日本語) - Año.mp3». " +
                "${changes.size} archivos cambiarían de nombre o carpeta; $missing no tienen título o artista para nombrarlos. " +
                "Las colecciones (series, juegos) y la regla de 5 canciones por artista llegan en la siguiente fase.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp),
        )
        val ops = state.ops()
        var confirm by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            Button(onClick = { val n = ops.planNames(state.libraryTracks).size; ops.log("app", "plan: $n moves"); state.message = "Plan guardado: $n movimientos" }) { Text("Guardar plan") }
            Button(onClick = { confirm = true }, enabled = ops.loadPlan().isNotEmpty()) { Text("Aplicar plan…") }
            OutlinedButton(onClick = { val n = ops.undo(null, "app"); state.reload(); state.message = "$n archivos devueltos a su sitio" }) { Text("Deshacer último lote") }
        }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Aplicar el plan?") },
            text = { Text("Se moverán y renombrarán ${ops.loadPlan().size} archivos dentro de la biblioteca. Nada se sobrescribe y todo queda en el registro para deshacerlo.") },
            confirmButton = { Button(onClick = {
                confirm = false
                val (done, skipped) = ops.apply(null, dryRun = false, who = "app")
                state.reload()
                state.message = "$done movidos, $skipped saltados"
            }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(changes.take(3000)) { (t, to) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(10.dp)) {
                    Text(t.path.removePrefix(root), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("→ " + to.removePrefix(root), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(state: LibraryState) {
    var root by remember { mutableStateOf(state.settings.root) }
    var extra by remember { mutableStateOf(state.settings.extra.joinToString("\n")) }
    var phone by remember { mutableStateOf(state.settings.phoneTodo) }
    Column(Modifier.widthIn(max = 800.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fun pick(): String? {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val d = FileDialog(null as Frame?, "Elige una carpeta", FileDialog.LOAD).apply { isVisible = true }
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
            return d.file?.let { File(d.directory, it).path }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(root, { root = it }, label = { Text("Biblioteca limpia (se crea si no existe)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { pick()?.let { root = it } }) { Text("Elegir…") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(extra, { extra = it }, label = { Text("Fuentes que se leen (una por línea, nunca se modifican)") }, modifier = Modifier.weight(1f), minLines = 3)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { pick()?.let { extra = (extra.lines().filter { l -> l.isNotBlank() } + it).joinToString("\n") } }) { Text("Añadir…") }
        }
        Text("Las reglas de cada fuente (p. ej. qué álbumes de Yuki Kajiura se copian) están en ${File(Store.dir, "sources.json").path}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        OutlinedTextField(phone, { phone = it }, label = { Text("To-do del móvil (lo escribe BitChord)") }, modifier = Modifier.fillMaxWidth())
        var native by remember { mutableStateOf(Builder.Formats.native) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(native, { native = it; Builder.Formats.native = it })
            Text("Guardar AAC (.m4a) y Opus (.webm) tal cual, sin recomprimir. Si no, solo se copian MP3 y sin pérdida (→ MP3 320).", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        // Spotify: a second check of the albums (Client ID and secret of a free app at developer.spotify.com).
        var spId by remember { mutableStateOf(Spotify.saved()?.first.orEmpty()) }
        var spSecret by remember { mutableStateOf(Spotify.saved()?.second.orEmpty()) }
        var spState by remember { mutableStateOf(if (Spotify.ready) "guardado" else "") }
        Text("Spotify (para comprobar álbumes): crea una app gratis en developer.spotify.com → Dashboard → Create app (Redirect URI: https://localhost, Web API) y copia aquí su Client ID y Client secret.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(spId, { spId = it }, label = { Text("Spotify Client ID") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(spSecret, { spSecret = it }, label = { Text("Spotify Client secret") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                Spotify.save(spId, spSecret); spState = "comprobando…"
                Thread { spState = if (Spotify.test()) "funciona ✓" else "Spotify no lo aceptó" }.start()
            }) { Text("Guardar y comprobar") }
            Text(spState, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        // Other services' keys (all free): typed here, kept on this Mac.
        Text("Otras claves (gratis): AcoustID (acoustid.org → Register an application), Discogs (Settings → Developers → Generate new token), Last.fm (last.fm/api/account/create), fanart.tv (tu perfil → API).", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        listOf(Keys.ACOUSTID to "AcoustID (API key)", Keys.DISCOGS to "Discogs (personal access token)", Keys.LASTFM to "Last.fm (API key)", Keys.FANART to "fanart.tv (API key)").chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, label) ->
                    var v by remember { mutableStateOf(Keys.get(k).orEmpty()) }
                    var state by remember { mutableStateOf("") }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(v, { v = it; Keys.set(k, it); state = "" }, label = { Text(label) }, singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                            OutlinedButton(enabled = v.isNotBlank(), onClick = { state = "comprobando…"; Thread { state = Keys.test(k) }.start() }) { Text("Comprobar") }
                        }
                        if (state.isNotEmpty()) Text(state, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                }
            }
        }
        var comps by remember { mutableStateOf(Builder.Layout.compilationsFirst) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(comps, { comps = it; Builder.Layout.compilationsFirst = it })
            Text("Menos álbumes: cada canción se guarda una vez, en el recopilatorio o best-of más grande donde está (MusicBrainz); el móvil la sigue mostrando en su álbum original.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        Button(onClick = {
            val s = Store.Settings(Paths.resolve(root.trim()), extra.lines().map { Paths.resolve(it.trim()) }.filter { it.isNotEmpty() }, Paths.resolve(phone.trim()))
            File(s.root).mkdirs()
            Store.save(s)
            state.settings = s
            state.recompute()
            state.message = "Ajustes guardados. Pulsa ↻ para volver a leer."
        }) { Text("Guardar") }
        // pCloud: the library on pCloud Drive is handled through pCloud's API, signed in once here.
        Spacer(Modifier.height(8.dp))
        Text("pCloud", color = Color.White, fontSize = 16.sp)
        var account by remember { mutableStateOf(PCloudApi.account()) }
        if (account != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Conectada como $account. Las operaciones en la biblioteca se hacen directamente en pCloud.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { PCloudApi.logout(); account = null }) { Text("Cerrar sesión") }
            }
        } else {
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            var waitingCode by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            Text("Para construir la biblioteca en pCloud Drive la app usa pCloud directamente (mover al instante, subir y comprobar sin pasar por la caché de pCloud Drive). " +
                "Se inicia sesión como en el móvil: correo y contraseña, y el código que pCloud envía por correo. La contraseña solo se envía a pCloud y no se guarda.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(email, { email = it }, label = { Text("Correo de pCloud") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, singleLine = true, modifier = Modifier.weight(1f),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Button(enabled = email.isNotBlank() && password.isNotBlank(), onClick = {
                    scope.launch {
                        status = "Conectando…"
                        val r = withContext(Dispatchers.IO) { PCloudApi.startLogin(email.trim(), password) }
                        r.onSuccess { done ->
                            if (done) { password = ""; account = PCloudApi.account(); status = null }
                            else { waitingCode = true; status = "pCloud te ha enviado un código por correo: escríbelo aquí abajo" }
                        }.onFailure { status = it.message }
                    }
                }) { Text("Iniciar sesión") }
            }
            if (waitingCode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it }, label = { Text("Código de pCloud") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = code.isNotBlank(), onClick = {
                    scope.launch {
                        status = "Comprobando el código…"
                        val r = withContext(Dispatchers.IO) { PCloudApi.finishLogin(email.trim(), code) }
                        r.onSuccess { password = ""; code = ""; waitingCode = false; account = PCloudApi.account(); status = null }.onFailure { status = it.message }
                    }
                }) { Text("Confirmar") }
            }
            status?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 12.sp) }
            // Accounts pCloud won't sign in with email and password (signed up with Google or Apple…):
            // pCloud's own page, as on the phone, with the same app registered in pCloud's "My Apps".
            Spacer(Modifier.height(8.dp))
            Text("¿Tu cuenta entra con Google o Apple, o pCloud no acepta la contraseña? Inicia sesión en la página de pCloud, con el mismo «Client ID» que usa BitChord en el móvil " +
                "(pCloud → My Apps; dirección de retorno ${PCloudApi.REDIRECT}). Al terminar, el navegador acabará en una página que no carga: copia su dirección completa y pégala aquí.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            var clientId by remember { mutableStateOf(runCatching { File(Store.dir, "pcloud-client-id.txt").readText().trim() }.getOrDefault("")) }
            var pasted by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID de pCloud") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = clientId.isNotBlank(), onClick = {
                    runCatching { File(Store.dir, "pcloud-client-id.txt").writeText(clientId.trim()) }
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(PCloudApi.authorizeUrl(clientId))) }.onFailure { status = "No se pudo abrir el navegador: ${it.message}" }
                }) { Text("Abrir la página de pCloud") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pasted, { pasted = it }, label = { Text("Dirección final del navegador (https://localhost/bitchord-pcloud#…)") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = pasted.contains("access_token"), onClick = {
                    scope.launch {
                        status = "Comprobando…"
                        val r = withContext(Dispatchers.IO) { PCloudApi.fromRedirect(pasted) }
                        r.onSuccess { pasted = ""; account = PCloudApi.account(); status = null }.onFailure { status = it.message }
                    }
                }) { Text("Conectar") }
            }
        }
        Text("ffprobe: ${Tools.ffprobe}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        Text("ffmpeg: ${Tools.ffmpeg}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}
