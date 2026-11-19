package com.music.bitchord.data.cleanup

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.playback.smart.AudioCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: the library assistant — Gemini working through your pCloud folders on
 * its own, under the same conditions as the night work (the hours, Wi-Fi networks and
 * charging set for the library check), within the free plan's daily allowance.
 *
 * For each folder it proposes the release; what it is sure of it writes (tags, cover,
 * lyrics — undoable from its history), what it is less sure of it leaves for you to
 * review (editable), and where it is lost it asks — the questions show as a badge on
 * your profile photo. You can talk to it: tell it to leave a folder alone, what a folder
 * is ("those are the Naruto openings and endings"), or answer its questions; it
 * remembers, and looks the folder up again with what you said.
 */
object Assistant {

    private const val TAG = "BitChord"
    const val FILE = "assistant.json"
    /** Gemini's free plan: a little over a request a minute keeps under its per-minute and daily caps. */
    private const val DAILY_CALLS = 180
    private const val SURE = 0.85

    data class Question(val key: String, val path: String, val text: String, val at: Long)
    data class Message(val fromMe: Boolean, val text: String, val at: Long)
    data class Done(val key: String, val path: String, val album: String, val artist: String, val at: Long, val undone: Boolean = false)

    data class State(
        val on: Boolean = true,
        val running: Boolean = false,
        val current: String? = null,
        val waiting: AudioCheck.Wait? = null,
        val callsToday: Int = 0,
        val questions: List<Question> = emptyList(),
        val chat: List<Message> = emptyList(),
        val history: List<Done> = emptyList(),
        val thinking: Boolean = false,
        val reviewCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What wants your attention: questions it asked. For the badge on your profile photo. */
    private val _attention = MutableStateFlow(0)
    val attention: StateFlow<Int> = _attention.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private var store: File? = null
    @Volatile private var job: Job? = null

    // Memory: what you told it.
    private val excludedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val hints = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Old tags per file id, to undo what it wrote: folder key → list of (file id, title, artist, album, track). */
    private val undo = java.util.concurrent.ConcurrentHashMap<String, List<Array<String?>>>()
    private var day = ""

    fun init(context: Context) {
        app = context.applicationContext
        store = File(context.filesDir, FILE)
        load()
    }

    // ── Memory ──

    private fun norm(path: String) = path.trim().trim('/').lowercase()

    fun excluded(path: String): Boolean {
        val p = norm(path)
        return excludedPaths.any { e -> p == e || p.startsWith("$e/") }
    }

    /** What you said about [path] (or a folder above it), and your general notes. */
    fun hintFor(path: String): String? {
        val p = norm(path)
        val own = hints.entries.filter { (k, _) -> p == k || p.startsWith("$k/") }.sortedBy { it.key.length }.map { it.value }
        return (own + notes).joinToString(". ").ifBlank { null }
    }

    fun addHint(path: String, text: String) {
        val k = norm(path)
        hints[k] = listOfNotNull(hints[k], text).joinToString(". ")
        save()
    }

    /** A folder settled: its question goes. */
    fun forget(key: String) {
        val qs = _state.value.questions.filterNot { it.key == key }
        _state.value = _state.value.copy(questions = qs)
        _attention.value = qs.size
        save()
    }

    fun setOn(on: Boolean) {
        _state.value = _state.value.copy(on = on)
        save()
        if (on) start()
    }

    // ── Working on its own ──

    @Volatile private var watcher: Job? = null

    /** Phase 2: a video you just liked is looked at within minutes, whatever the hour. */
    private fun watchNewLikes() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            delay(60_000)
            while (true) {
                if (_state.value.on && Gemini.ready) {
                    rollDay()
                    val (fresh, _) = LikedVideos.todo()
                    for (v in fresh.take(5)) {
                        if (_state.value.callsToday >= DAILY_CALLS) break
                        count()
                        _state.value = _state.value.copy(current = v.title)
                        runCatching { LikedVideos.handle(v, ::askAbout) }
                            .onFailure { TrackLog.w(TAG, "liked videos: '${v.title}' failed: ${it.message}") }
                        delay(15_000)
                    }
                }
                delay(2 * 60_000L)
            }
        }
    }

    fun start() {
        watchNewLikes()
        if (job?.isActive == true) return
        val context = app ?: return
        job = scope.launch {
            delay(90_000)
            while (true) {
                if (!_state.value.on || !Gemini.ready) { delay(10 * 60_000L); continue }
                val w = AudioCheck.waitFor(context)
                _state.value = _state.value.copy(waiting = w)
                if (w != null) { delay(5 * 60_000L); continue }
                rollDay()
                if (_state.value.callsToday >= DAILY_CALLS) { delay(30 * 60_000L); continue }
                // Phase 2 first: the videos you liked long ago, one at a time.
                val backlog = LikedVideos.todo().second.firstOrNull()
                if (backlog != null) {
                    _state.value = _state.value.copy(running = true, current = backlog.title)
                    count()
                    runCatching { LikedVideos.handle(backlog, ::askAbout) }
                        .onFailure { TrackLog.w(TAG, "liked videos: '${backlog.title}' failed: ${it.message}") }
                    delay(20_000)
                    continue
                }
                val asked = _state.value.questions.map { it.key }.toSet()
                val g = runCatching { LibraryCleanup.groups() }.getOrDefault(emptyList())
                    .firstOrNull { !LibraryCleanup.isDone(it.key) && !LibraryCleanup.waiting.containsKey(it.key) && it.key !in asked }
                if (g == null) {
                    _state.value = _state.value.copy(running = false, current = null)
                    delay(60 * 60_000L)
                    continue
                }
                _state.value = _state.value.copy(running = true, current = g.path)
                count()
                val p = runCatching { LibraryCleanup.proposeFor(g, hintFor(g.path)) }
                    .onFailure { TrackLog.w(TAG, "assistant: ${g.path} failed: ${it.message}") }
                    .getOrNull()
                when {
                    p == null -> ask(g, "No sé qué es «${g.path.substringAfterLast('/')}». ¿Me das una pista (artista, anime, juego, año…)?")
                    p.confidence >= SURE && p.question == null -> {
                        TrackLog.d(TAG, "assistant: sure (${"%.2f".format(p.confidence)}) — writing ${p.artist} — ${p.album} into ${g.path}")
                        LibraryCleanup.markDone(g.key, "auto")
                        runCatching { LibraryCleanup.write(g, p, convert = false) }
                        _state.value = _state.value.copy(
                            history = (listOf(Done(g.key, g.path, p.album.ifBlank { "—" }, p.artist, System.currentTimeMillis())) + _state.value.history).take(500),
                        )
                        save()
                    }
                    p.confidence < 0.5 && p.question != null -> {
                        LibraryCleanup.waiting[g.key] = p
                        ask(g, p.question)
                    }
                    else -> {
                        TrackLog.d(TAG, "assistant: not sure (${"%.2f".format(p.confidence)}) about ${g.path} — left for review")
                        LibraryCleanup.waiting[g.key] = p
                        _state.value = _state.value.copy(reviewCount = LibraryCleanup.waiting.size)
                    }
                }
                // Gemini's free plan allows a few requests a minute: one folder every 20 s.
                delay(20_000)
            }
        }
    }

    private fun ask(g: LibraryCleanup.Group, text: String) = askAbout(g.key, g.path, text)

    private fun askAbout(key: String, path: String, text: String) {
        val qs = (_state.value.questions.filterNot { it.key == key } + Question(key, path, text, System.currentTimeMillis()))
        _state.value = _state.value.copy(questions = qs, reviewCount = LibraryCleanup.waiting.size)
        _attention.value = qs.size
        TrackLog.d(TAG, "assistant: asks about $path: $text")
        save()
    }

    private fun rollDay() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        if (today != day) {
            day = today
            _state.value = _state.value.copy(callsToday = 0)
        }
    }

    private fun count() {
        rollDay()
        _state.value = _state.value.copy(callsToday = _state.value.callsToday + 1)
    }

    // ── Undo ──

    /** The tags a folder had before the assistant (or you) wrote over them. */
    fun recordUndo(g: LibraryCleanup.Group, p: LibraryCleanup.Proposal) {
        undo[g.key] = g.files.map { arrayOf(it.id, it.title, it.artist, it.album, it.track?.toString()) }
        save()
    }

    /** Writes a folder's old tags back (the cover and lyrics it added stay). */
    fun undo(d: Done) {
        val context = app ?: return
        val old = undo[d.key] ?: return
        scope.launch {
            for (f in old) {
                val id = f[0] ?: continue
                runCatching {
                    TagWriter.intoPcloud(context, id, TagWriter.Change(text = CoverTags.Text(title = f[1], artist = f[2], album = f[3], track = f[4])))
                }
            }
            LibraryCleanup.reopen(d.key)
            _state.value = _state.value.copy(history = _state.value.history.map { if (it === d) it.copy(undone = true) else it })
            TrackLog.d(TAG, "assistant: undid ${d.path}")
            save()
        }
    }

    // ── Talking ──

    /** What you said; the assistant answers, and does what it understood. */
    fun send(text: String) {
        if (text.isBlank()) return
        val mine = Message(true, text.trim(), System.currentTimeMillis())
        _state.value = _state.value.copy(chat = (_state.value.chat + mine).takeLast(200), thinking = true)
        scope.launch {
            val reply = runCatching { talk(text.trim()) }.getOrNull()
                ?: "No he podido contestar ahora (¿hay clave de Gemini y conexión?)."
            _state.value = _state.value.copy(
                chat = (_state.value.chat + Message(false, reply, System.currentTimeMillis())).takeLast(200),
                thinking = false,
            )
            save()
        }
    }

    private suspend fun talk(text: String): String? {
        if (!Gemini.ready) return "Necesito una clave de Gemini para poder hablar (pégala arriba en esta pantalla)."
        count()
        val folders = runCatching { LibraryCleanup.groups() }.getOrDefault(emptyList())
        val paths = JSONArray(folders.map { it.path }.take(2500))
        val qs = JSONArray(_state.value.questions.map { JSONObject().put("path", it.path).put("question", it.text) })
        val recent = JSONArray(_state.value.chat.takeLast(12).map { (if (it.fromMe) "Owner: " else "You: ") + it.text })
        val prompt = """
You are the assistant that tidies the owner's music library (folders in pCloud). You speak Spanish, briefly and warmly.
Folders still to tidy (paths): $paths
Your open questions: $qs
What the owner told you before — excluded: ${JSONArray(excludedPaths)}, folder notes: ${JSONObject(hints as Map<*, *>)}, general notes: ${JSONArray(notes)}
Recent conversation: $recent
The owner now says: "$text"
Work out what they want and answer ONLY a JSON object:
{"reply": "your answer to them, in Spanish",
 "actions": [
   {"type": "exclude", "path": "a folder path from the list (or a parent of several)"},
   {"type": "include", "path": "..."},
   {"type": "hint", "path": "...", "text": "what the folder is, as precise as you can make it — research it if useful (e.g. the list of Naruto openings and endings with their artists)"},
   {"type": "note", "text": "a general rule to remember for every folder"},
   {"type": "redo", "path": "a folder to look at again"}
 ]}
Use the exact paths from the list. Several actions are fine; none is fine.
""".trimIndent()
        val o = Gemini.ask(prompt, null, null) ?: return null
        val actions = o.optJSONArray("actions") ?: JSONArray()
        val done = ArrayList<String>()
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            val path = a.optString("path").takeIf { it.isNotBlank() }
            when (a.optString("type")) {
                "exclude" -> path?.let { excludedPaths += norm(it); clearFor(it); done += "excluida $it" }
                "include" -> path?.let { p -> excludedPaths.removeAll { it == norm(p) }; done += "incluida $p" }
                "hint" -> path?.let { p -> a.optString("text").takeIf { it.isNotBlank() }?.let { addHint(p, it); reopenFor(p); done += "pista para $p" } }
                "note" -> a.optString("text").takeIf { it.isNotBlank() }?.let { notes += it; done += "nota" }
                "redo" -> path?.let { reopenFor(it); done += "repasar $it" }
            }
        }
        if (done.isNotEmpty()) TrackLog.d(TAG, "assistant: from the chat — ${done.joinToString("; ")}")
        save()
        if (done.isNotEmpty()) start()
        return o.optString("reply").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun foldersUnder(path: String) = runCatching { LibraryCleanup.groups() }.getOrDefault(emptyList())
        .filter { norm(it.path) == norm(path) || norm(it.path).startsWith(norm(path) + "/") }

    private fun clearFor(path: String) {
        val keys = foldersUnder(path).map { it.key }.toSet()
        keys.forEach { LibraryCleanup.waiting.remove(it) }
        val qs = _state.value.questions.filterNot { it.key in keys || norm(it.path).startsWith(norm(path)) }
        _state.value = _state.value.copy(questions = qs, reviewCount = LibraryCleanup.waiting.size)
        _attention.value = qs.size
    }

    /** Looked at again: its question and old proposal go, the folder is open again. */
    private fun reopenFor(path: String) {
        clearFor(path)
        foldersUnder(path).forEach { if (LibraryCleanup.isDone(it.key)) LibraryCleanup.reopen(it.key) }
    }

    /** An answer to one of its questions, from the review screen. */
    fun answer(q: Question, text: String) {
        if (q.key.startsWith("video:")) {
            LikedVideos.answer(q.key.removePrefix("video:"), text)
            forget(q.key)
            start()
            return
        }
        addHint(q.path, text)
        reopenFor(q.path)
        save()
        start()
    }

    // ── Storage ──

    private fun save() {
        val s = _state.value
        val o = JSONObject()
            .put("on", s.on)
            .put("day", day).put("calls", s.callsToday)
            .put("excluded", JSONArray(excludedPaths))
            .put("hints", JSONObject(hints as Map<*, *>))
            .put("notes", JSONArray(notes))
            .put("questions", JSONArray(s.questions.map { JSONObject().put("k", it.key).put("p", it.path).put("t", it.text).put("at", it.at) }))
            .put("chat", JSONArray(s.chat.takeLast(100).map { JSONObject().put("m", it.fromMe).put("t", it.text).put("at", it.at) }))
            .put("history", JSONArray(s.history.take(500).map { JSONObject().put("k", it.key).put("p", it.path).put("al", it.album).put("ar", it.artist).put("at", it.at).put("u", it.undone) }))
            .put("undo", JSONObject().apply { undo.forEach { (k, v) -> put(k, JSONArray(v.map { JSONArray(it.toList()) })) } })
        runCatching { store?.writeText(o.toString()) }
        app?.let { com.music.bitchord.data.sync.SettingsSync.changed(it) }
    }

    fun load() {
        val text = runCatching { store?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val o = JSONObject(text)
            excludedPaths.clear(); hints.clear(); notes.clear(); undo.clear()
            o.optJSONArray("excluded")?.let { a -> (0 until a.length()).forEach { excludedPaths += a.getString(it) } }
            o.optJSONObject("hints")?.let { h -> h.keys().forEach { hints[it] = h.getString(it) } }
            o.optJSONArray("notes")?.let { a -> (0 until a.length()).forEach { notes += a.getString(it) } }
            o.optJSONObject("undo")?.let { u ->
                u.keys().forEach { k ->
                    val a = u.getJSONArray(k)
                    undo[k] = (0 until a.length()).map { i -> val r = a.getJSONArray(i); Array(r.length()) { j -> r.optString(j).takeIf { s -> s != "null" && !r.isNull(j) } } }
                }
            }
            day = o.optString("day")
            val qs = o.optJSONArray("questions")?.let { a -> (0 until a.length()).map { val q = a.getJSONObject(it); Question(q.getString("k"), q.getString("p"), q.getString("t"), q.optLong("at")) } }.orEmpty()
            val chat = o.optJSONArray("chat")?.let { a -> (0 until a.length()).map { val m = a.getJSONObject(it); Message(m.optBoolean("m"), m.optString("t"), m.optLong("at")) } }.orEmpty()
            val history = o.optJSONArray("history")?.let { a -> (0 until a.length()).map { val d = a.getJSONObject(it); Done(d.getString("k"), d.getString("p"), d.optString("al"), d.optString("ar"), d.optLong("at"), d.optBoolean("u")) } }.orEmpty()
            _state.value = _state.value.copy(on = o.optBoolean("on", true), callsToday = o.optInt("calls"), questions = qs, chat = chat, history = history)
            _attention.value = qs.size
        }
    }
}
