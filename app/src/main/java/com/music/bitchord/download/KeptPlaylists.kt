package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
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
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: playlists kept downloaded, the way Spotify does it — the
 * download switch at the top of a playlist (Liked Music included). Off for
 * every list until you turn it on.
 *
 * While on, every song of the list is downloaded, and songs added later too.
 * A song that leaves the list (removed, or un-liked) is deleted from the phone
 * [REMOVE_AFTER_MS] later — unless it came back meanwhile, another kept list
 * still has it, or you downloaded it yourself. Turning the switch off deletes
 * the list's songs the same way.
 *
 * Only songs downloaded *for* a kept list are ever deleted here: [owners]
 * records which lists brought each song in.
 */
object KeptPlaylists {

    const val LIKED = "liked"
    private const val REMOVE_AFTER_MS = 30_000L
    private const val KEY_ON = "on"
    private const val KEY_OWNERS = "owners"
    private const val TAG = "BitChord"

    private var prefs: SharedPreferences? = null
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _on = MutableStateFlow<Set<String>>(emptySet())
    /** The lists kept downloaded, by [key]. */
    val on: StateFlow<Set<String>> = _on.asStateFlow()

    /** Song id → the kept lists it was downloaded for. */
    private val owners = ConcurrentHashMap<String, MutableSet<String>>()
    /** The latest full song list seen for each kept list. */
    private val latest = ConcurrentHashMap<String, Set<String>>()
    private val pendingRemoval = ConcurrentHashMap<String, Job>()

    fun init(context: Context) {
        app = context.applicationContext
        val p = context.getSharedPreferences("bitchord_kept_playlists", Context.MODE_PRIVATE)
        prefs = p
        _on.value = p.getStringSet(KEY_ON, emptySet()).orEmpty().toSet()
        runCatching {
            val o = JSONObject(p.getString(KEY_OWNERS, "{}") ?: "{}")
            o.keys().forEach { id ->
                val a = o.getJSONArray(id)
                owners[id] = ConcurrentHashMap.newKeySet<String>().apply { for (i in 0 until a.length()) add(a.getString(i)) }
            }
        }
    }

    /** The list's key: one id for Liked Music however it was opened, and a playlist without its "VL". */
    fun key(browseId: String): String = when {
        browseId in com.music.bitchord.data.LikedMusicCache.IDS || browseId == "VLLM" -> LIKED
        browseId.startsWith("VL") -> browseId.removePrefix("VL")
        else -> browseId
    }

    fun isOn(browseId: String): Boolean = key(browseId) in _on.value

    /** The switch. [songs]: the list as shown, downloaded right away when turned on. */
    fun set(browseId: String, on: Boolean, songs: List<Song>, title: String?) {
        val k = key(browseId)
        _on.value = if (on) _on.value + k else _on.value - k
        prefs?.edit()?.putStringSet(KEY_ON, _on.value)?.apply()
        TrackLog.d(TAG, "kept downloaded: '$title' ${if (on) "on" else "off"}")
        if (on) {
            sync(browseId, songs, complete = false, title = title)
        } else {
            latest.remove(k)
            val mine = owners.filterValues { k in it }.keys.toList()
            mine.forEach { id -> disown(id, k) }
        }
    }

    /**
     * The songs of [browseId] as just loaded. New ones are downloaded; with
     * [complete] (the whole list, not its first page) songs no longer in it are
     * let go of.
     */
    fun sync(browseId: String, songs: List<Song>, complete: Boolean, title: String?) {
        val k = key(browseId)
        if (k !in _on.value) return
        val ctx = app ?: return
        val ids = songs.map { it.videoId }.toSet()
        // A list come back much shorter is a partial load, not a purge.
        val before = latest[k]
        val trusted = complete && (before == null || ids.size >= before.size * 0.8)
        if (trusted) {
            latest[k] = ids
            owners.filterValues { k in it }.keys.filter { it !in ids }.forEach { disown(it, k) }
        }
        val newOwned = ArrayList<String>()
        val missing = songs.filter { s ->
            val id = s.videoId
            if (id.startsWith("content:") || id.startsWith("file:")) return@filter false
            pendingRemoval.remove(id)?.cancel()
            val have = runCatching { Downloads.localCopyOf(id) }.getOrNull() != null
            val active = Downloads.active.value[id]
            val inFlight = active is DownloadState.Queued || active is DownloadState.Running
            // Owned only when downloaded for this list, never what you saved yourself.
            if (!have) {
                if (owners.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(k)) newOwned += id
            } else {
                owners[id]?.let { if (it.add(k)) newOwned += id }
            }
            !have && !inFlight
        }
        if (newOwned.isNotEmpty()) save()
        if (missing.isEmpty() || !AppSettings.downloadsAllowedNow) return
        TrackLog.d(TAG, "kept downloaded: ${missing.size} songs of '${title ?: k}' to download")
        Downloads.enqueueAll(ctx, missing, title)
    }

    /** Whether [videoId] is on the phone because a kept list has it. */
    fun owned(videoId: String): Boolean = owners[videoId]?.isNotEmpty() == true

    private fun disown(id: String, k: String) {
        val set = owners[id] ?: return
        set.remove(k)
        save()
        if (set.isNotEmpty()) return
        val ctx = app ?: return
        pendingRemoval.remove(id)?.cancel()
        pendingRemoval[id] = scope.launch {
            delay(REMOVE_AFTER_MS)
            pendingRemoval.remove(id)
            // Back in a kept list meanwhile (re-liked, added again): kept.
            if (owners[id]?.isNotEmpty() == true || latest.values.any { id in it }) return@launch
            owners.remove(id)
            save()
            Downloads.cancel(id)
            val gone = runCatching { Downloads.delete(ctx, id) }.getOrDefault(false)
            TrackLog.d(TAG, "kept downloaded: $id left its list — download ${if (gone) "deleted" else "already gone"}")
        }
    }

    private fun save() {
        val o = JSONObject()
        owners.forEach { (id, set) -> if (set.isNotEmpty()) o.put(id, JSONArray(set.toList())) }
        prefs?.edit()?.putString(KEY_OWNERS, o.toString())?.apply()
    }
}
