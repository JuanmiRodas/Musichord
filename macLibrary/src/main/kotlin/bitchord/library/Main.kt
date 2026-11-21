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
}

fun main(args: Array<String>) {
    // BitChord Audio: with arguments, the same engine on the command line (see Cli.kt).
    if (args.isNotEmpty()) { cli(args); return }
    gui()
}

private fun gui() = application {
    Scanner.loadIndex()
    val state = remember { LibraryState() }
    Window(
        onCloseRequest = { Scanner.saveIndex(); exitApplication() },
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
    NAMES("Nombres", Icons.Filled.DriveFileRenameOutline),
    FREE("Liberar espacio", Icons.Filled.CleaningServices),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

@Composable
private fun App(state: LibraryState) {
    var tab by remember { mutableStateOf(Tab.SUMMARY) }
    val scope = rememberCoroutineScope()
    fun scan() {
        if (state.busy != null) return
        scope.launch {
            state.busy = "Leyendo la biblioteca y las fuentes…"
            File(state.settings.root).mkdirs()
            val folders = listOf(state.settings.root) + state.settings.extra
            state.tracks = Scanner.scan(folders.filter { File(it).isDirectory }) { done, total ->
                state.progress = done.toFloat() / total.coerceAtLeast(1)
            }
            state.recompute()
            state.busy = null
            state.message = "${state.tracks.size} archivos leídos"
            runCatching { state.ops().log("app", "scan: ${state.tracks.size} files") }
        }
    }
    LaunchedEffect(Unit) { scan() }
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
            state.busy?.let { b ->
                Text(b, color = Color.White.copy(alpha = 0.8f))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            when (tab) {
                Tab.SUMMARY -> Summary(state, scope)
                Tab.TODO -> TodoList(state)
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
            Card("Construir la biblioteca limpia") {
                Text(
                    "De cada canción se copia la mejor versión de las fuentes (sin pérdida → MP3 320; si no, MP3 320 auténtico; si no, el mejor MP3 mientras tanto, apuntado en la to-do). " +
                        "AAC, m4a, opus, webm y wma no se copian. Las fuentes no se tocan nunca.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                val sources = remember { Sources.load() }
                // All sources at once: each song placed and taken from its best copy across them all.
                var allPreview by remember(state.tracks) { mutableStateOf<String?>(null) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Todas las fuentes (recomendado)", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(allPreview ?: "Cada canción una sola vez, de su mejor copia entre todas las fuentes", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                    }
                    OutlinedButton(enabled = state.busy == null, onClick = {
                        val p = Builder.plan(sources, state.tracks, state.settings.root)
                        allPreview = "Se copiarían ${p.copy.size} (${p.copy.count { it.reason.startsWith("MP3 320") }} MP3 320, ${p.copy.count { it.reason.startsWith("sin pérdida") }} desde sin pérdida, " +
                            "${p.copy.count { it.reason.contains("mientras") }} por debajo, a la to-do) · no se copian ${p.skipped.size}"
                    }) { Text("Ver qué se copia") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = state.busy == null, onClick = {
                        scope.launch {
                            state.busy = "Construyendo la biblioteca con todas las fuentes…"
                            val p = Builder.plan(sources, state.tracks, state.settings.root)
                            var ok = 0
                            var failed = 0
                            withContext(Dispatchers.IO) {
                                val covers = Covers(state.settings.root)
                                p.copy.forEachIndexed { i, item ->
                                    Builder.copy(item, covers).onSuccess { ok++; covers.propose(item.key, p.alternatives[item.key].orEmpty()) }
                                        .onFailure { failed++; state.ops().log("app", "build error ${item.track.path}: ${it.message}") }
                                    state.progress = (i + 1f) / p.copy.size.coerceAtLeast(1)
                                    state.busy = "Construyendo: ${i + 1} de ${p.copy.size} (${ok} copiadas, $failed con error)"
                                    if (i % 50 == 0) covers.save()
                                }
                                covers.save()
                            }
                            state.ops().log("app", "build (all sources): $ok copied, $failed failed")
                            state.busy = null
                            state.message = "$ok copiadas" + if (failed > 0) ", $failed con error (ver registro)" else ""
                        }
                    }) { Text("Construir todo") }
                }
                Text("O una sola fuente:", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                sources.forEach { s ->
                    var preview by remember(state.tracks) { mutableStateOf<String?>(null) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(preview ?: s.path, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                        }
                        OutlinedButton(enabled = state.busy == null, onClick = {
                            val p = Builder.plan(listOf(s), state.tracks, state.settings.root)
                            preview = "Se copiarían ${p.copy.size} (" + p.copy.groupingBy { it.reason }.eachCount().entries.joinToString { "${it.value} ${it.key}" } +
                                ") · no se copian ${p.skipped.size}"
                        }) { Text("Ver qué se copia") }
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = state.busy == null, onClick = {
                            scope.launch {
                                state.busy = "Copiando desde ${s.name}…"
                                val p = Builder.plan(listOf(s), state.tracks, state.settings.root)
                                var ok = 0
                                var failed = 0
                                withContext(Dispatchers.IO) {
                                    val covers = Covers(state.settings.root)
                                    p.copy.forEachIndexed { i, item ->
                                        Builder.copy(item, covers).onSuccess { ok++; covers.propose(item.key, p.alternatives[item.key].orEmpty()) }
                                            .onFailure { failed++; state.ops().log("app", "build error ${item.track.path}: ${it.message}") }
                                        state.progress = (i + 1f) / p.copy.size.coerceAtLeast(1)
                                        if (i % 50 == 0) covers.save()
                                    }
                                    covers.save()
                                }
                                state.ops().log("app", "build ${s.name}: $ok copied, $failed failed")
                                state.busy = null
                                state.message = "$ok copiadas desde ${s.name}" + if (failed > 0) ", $failed con error (ver registro)" else ""
                            }
                        }) { Text("Construir") }
                    }
                }
            }
        }
        item {
            Card("Hablar con Claude") {
                Text(
                    "Copia el resumen y las últimas líneas del registro para pegarlo en la conversación. Claude también puede usar el mismo motor desde la línea de comandos: " +
                        "\"/Applications/BitChord Library.app/Contents/MacOS/BitChord Library\" status (o scan, todo, plan, apply, undo, convert, claude).",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                )
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
        Button(onClick = {
            val s = Store.Settings(Paths.resolve(root.trim()), extra.lines().map { Paths.resolve(it.trim()) }.filter { it.isNotEmpty() }, Paths.resolve(phone.trim()))
            File(s.root).mkdirs()
            Store.save(s)
            state.settings = s
            state.recompute()
            state.message = "Ajustes guardados. Pulsa ↻ para volver a leer."
        }) { Text("Guardar") }
        Text("ffprobe: ${Tools.ffprobe}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        Text("ffmpeg: ${Tools.ffmpeg}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}
