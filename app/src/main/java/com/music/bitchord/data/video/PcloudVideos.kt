package com.music.bitchord.data.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * BitChord Audio: your own videos in pCloud as video mode's pictures — an anime's
 * opening or ending, a concert you keep there.
 *
 * Only the folders you choose are looked through (never the whole drive), and only
 * when you ask (the pCloud search in the video picker); what was found is kept as an
 * index, so a song can be matched with one of your videos with no request at all.
 */
object PcloudVideos {
    private const val TAG = "VideoMode"
    private const val PREFS = "bitchord_pcloud_videos"
    private const val KEY_FOLDERS = "folders"
    private const val INDEX = "pcloud_videos.json"
    /** A scan this recent is reused for a search; older, the folders are listed again. */
    private const val FRESH_MS = 60 * 60 * 1000L
    private const val MAX_FOLDERS = 400
    private val VIDEO = Regex("(?i)\\.(mp4|m4v|mkv|webm|mov|avi|ts|flv|wmv)$")

    data class Video(val url: String, val name: String, val folder: String, val size: Long?)

    private var app: Context? = null
    private val _folders = MutableStateFlow<List<String>>(emptyList())
    /** The folders looked through, as WebDAV URLs. */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()
    @Volatile private var index: List<Video> = emptyList()
    @Volatile private var indexedAt = 0L
    private val scanning = Mutex()
    private val thumbs = Semaphore(2)

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _folders.value = p.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().sorted()
        runCatching {
            val o = JSONObject(File(context.filesDir, INDEX).readText())
            indexedAt = o.optLong("at")
            val a = o.optJSONArray("items") ?: JSONArray()
            index = (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                Video(e.optString("u"), e.optString("n"), e.optString("f"), e.optLong("s").takeIf { it > 0 })
            }
        }
    }

    /** Your login, for reading a file of yours. */
    fun headers(): Map<String, String> =
        WebDavConfig.basicAuthHeader(AppSettings.webdavUsername.value, AppSettings.webdavPassword.value)
            ?.let { mapOf("Authorization" to it) }.orEmpty()

    fun setFolders(list: List<String>) {
        val clean = list.map { it.trimEnd('/') + "/" }.distinct().sorted()
        _folders.value = clean
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putStringSet(KEY_FOLDERS, clean.toSet())?.apply()
        // Other folders: listed again now, once, so your videos can be matched to songs.
        indexedAt = 0L
        if (clean.isNotEmpty()) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { scan(force = true) } }
    }

    fun nameOf(url: String): String =
        runCatching { URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8") }.getOrDefault(url.substringAfterLast('/'))

    /** The videos in your chosen folders (and the folders inside them), listed again when stale or [force]d. */
    suspend fun scan(force: Boolean = false): List<Video> = scanning.withLock {
        if (!force && indexedAt > 0 && System.currentTimeMillis() - indexedAt < FRESH_MS) return index
        val roots = _folders.value
        if (roots.isEmpty()) return emptyList()
        val user = AppSettings.webdavUsername.value
        val pass = AppSettings.webdavPassword.value
        val found = ArrayList<Video>()
        withContext(Dispatchers.IO) {
            val queue = ArrayDeque(roots)
            val seen = HashSet<String>()
            var listed = 0
            while (queue.isNotEmpty() && listed < MAX_FOLDERS) {
                val dir = queue.removeFirst()
                if (!seen.add(dir.trimEnd('/').lowercase())) continue
                listed++
                val entries = WebDavClient.listFiles(dir, user, pass).getOrNull()
                entries?.filter { VIDEO.containsMatchIn(it.url) || it.contentType?.startsWith("video/") == true }
                    ?.forEach { e -> found += Video(e.url, e.displayName.ifBlank { nameOf(e.url) }, nameOf(dir), e.size) }
                WebDavClient.listFolders(dir, user, pass).getOrNull()?.forEach { queue.addLast(it.url) }
            }
            TrackLog.d(TAG, "pCloud videos: ${found.size} in $listed folder(s)")
        }
        index = found
        indexedAt = System.currentTimeMillis()
        app?.let { ctx ->
            runCatching {
                File(ctx.filesDir, INDEX).writeText(JSONObject().apply {
                    put("at", indexedAt)
                    put("items", JSONArray().apply {
                        found.forEach { v -> put(JSONObject().put("u", v.url).put("n", v.name).put("f", v.folder).put("s", v.size ?: 0L)) }
                    })
                }.toString())
            }
        }
        found
    }

    private fun words(text: String): List<String> =
        text.split(Regex("[\\s_\\-.,()\\[\\]【】「」]+")).map(OwnFileMatcher::key).filter { it.isNotEmpty() }

    /** Your videos whose file or folder name holds every word of [query] (any script), best first. */
    suspend fun search(query: String): List<Video> {
        val all = scan()
        val wanted = words(query)
        if (wanted.isEmpty()) return all.take(40)
        return all.mapNotNull { v ->
            val hay = OwnFileMatcher.key(v.name) + " " + OwnFileMatcher.key(v.folder) + " " +
                runCatching { OwnFileMatcher.allKeys(v.name.substringBeforeLast('.')).joinToString(" ") { it.removePrefix("~") } }.getOrDefault("")
            val hits = wanted.count { it in hay }
            if (hits == 0) null else v to hits
        }.sortedByDescending { it.second }.filter { it.second * 2 >= wanted.size }.map { it.first }.take(40)
    }

    /**
     * One of your videos named like [song] — from the folders already looked through, with no
     * request: its title (in any script) in the file's name.
     */
    fun bestFor(song: Song): Video? {
        if (index.isEmpty()) return null
        val keys = runCatching { OwnFileMatcher.allKeys(song.title) }.getOrDefault(emptySet())
            .map { it.removePrefix("~") }.filter { it.length >= 4 }
        if (keys.isEmpty()) return null
        return index.firstOrNull { v ->
            val name = v.name.substringBeforeLast('.')
            val hay = OwnFileMatcher.key(name) + " " +
                runCatching { OwnFileMatcher.allKeys(name).joinToString(" ") { it.removePrefix("~") } }.getOrDefault("")
            keys.any { it in hay }
        }
    }

    /** A frame of [video], small, kept on the device: read off the file in pCloud the first time. */
    suspend fun thumbnail(video: Video): File? {
        val ctx = app ?: return null
        val dir = File(ctx.cacheDir, "pcloud-thumbs").apply { mkdirs() }
        val file = File(dir, "${video.url.hashCode().toUInt()}.jpg")
        if (file.isFile && file.length() > 0) return file
        return thumbs.withPermit {
            if (file.isFile && file.length() > 0) return@withPermit file
            withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(video.url, headers())
                    val length = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    // A moment into it: the first frame is often black.
                    val at = (minOf(15_000L, length / 4)) * 1000
                    val frame: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    } else {
                        r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    frame ?: return@withContext null
                    file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    file
                } catch (e: Exception) {
                    TrackLog.w(TAG, "pCloud video thumbnail for '${video.name}' failed: ${e.message}")
                    null
                } finally {
                    runCatching { r.release() }
                }
            }
        }
    }

    /** The root to choose folders from: the server your library is on. */
    fun serverRoot(): String? {
        val url = AppSettings.webdavUrl.value.takeIf { it.isNotBlank() } ?: return null
        return runCatching { java.net.URI(url).let { "${it.scheme}://${it.host}/" } }.getOrNull()
    }
}
