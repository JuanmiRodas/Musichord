package com.music.bitchord.playback.cast

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastSession
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * BitChord Audio: the phone's side of "BitChord Connect" — our own app on the
 * speaker (docs/receiver/index.html), which holds the whole queue itself, as
 * Spotify's does. The speaker decides what plays and when it moves on; it
 * goes from song to song with no phone connected. The phone:
 *
 *  - hands it the queue (a window of it, topped up as it is played through),
 *  - finds the link for each song when the speaker asks ("need"),
 *  - says what the listener did (skip, a tap in the queue, repeat),
 *  - follows what the speaker says it is doing ("state") — so the app, opened
 *    on this phone or any other, shows what is playing there.
 *
 * Used only when the session is with that app (see [CastConnect]); every other
 * receiver is driven as before, by [CastPlayback].
 */
class ConnectLink(
    private val scope: CoroutineScope,
    private val session: CastSession,
    private val localPlayer: () -> ExoPlayer?,
    private val resolve: suspend (MediaItem) -> CastStream?,
    private val onChanged: () -> Unit,
) {
    private val tag = "BitChordCast"

    /** The ids the speaker was last given, so a skip to one of them is just a jump. */
    private var sentIds: List<String> = emptyList()

    /** The phone moving to follow the speaker — not the listener skipping. */
    private var following = false
    private var queueJob: Job? = null
    private var helloJob: Job? = null
    private var gotHello = false

    /** What the speaker last said. */
    @Volatile
    var lastState: JSONObject? = null
        private set

    private val callback = Cast.MessageReceivedCallback { _, ns, message ->
        if (ns != CastConnect.NAMESPACE) return@MessageReceivedCallback
        val m = runCatching { JSONObject(message) }.getOrNull() ?: return@MessageReceivedCallback
        scope.launch { onMessage(m) }
    }

    /**
     * Joined: asks the speaker what it holds. [pushPhone]: the listener was
     * playing on the phone and chose the speaker — the phone's queue goes over
     * (as Spotify moves playback onto a device); otherwise a speaker already
     * playing is taken up as it is.
     */
    private var pushed = false
    private var startPosition = 0L
    private var startPlay = false

    fun start(pushPhone: Boolean, positionMs: Long, play: Boolean) {
        startPosition = positionMs
        startPlay = play
        pushed = pushPhone
        runCatching { session.setMessageReceivedCallbacks(CastConnect.NAMESPACE, callback) }
        TrackLog.d(tag, "connect: joined the BitChord speaker (push phone queue: $pushPhone)")
        if (pushPhone) {
            sendQueue(current = localPlayer()?.currentMediaItem?.mediaId, positionMs = positionMs, play = play, keep = false)
        }
        send(JSONObject().put("type", "hello"))
        helloJob = scope.launch {
            delay(4_000)
            // No answer: an older speaker app, or the message was lost — the phone's queue then.
            if (!gotHello && !pushPhone) sendQueue(localPlayer()?.currentMediaItem?.mediaId, positionMs, play, keep = false)
        }
    }

    fun stop() {
        queueJob?.cancel()
        helloJob?.cancel()
        runCatching { session.removeMessageReceivedCallbacks(CastConnect.NAMESPACE) }
    }

    private fun send(o: JSONObject) {
        runCatching { session.sendMessage(CastConnect.NAMESPACE, o.toString()) }
            .onFailure { TrackLog.w(tag, "connect: couldn't send ${o.optString("type")}: ${it.message}") }
    }

    // ---- the queue ---------------------------------------------------------

    private fun songJson(item: MediaItem): JSONObject {
        val song = item.toSong()
        val md = item.mediaMetadata
        return JSONObject()
            .put("title", md.title?.toString().orEmpty())
            .put("artist", md.artist?.toString().orEmpty())
            .put("album", md.albumTitle?.toString().orEmpty())
            .put("artwork", md.artworkUri?.toString()?.takeIf { it.startsWith("http") })
            .put("json", runCatching { LastPlayed.songToJson(song) }.getOrNull())
    }

    /**
     * The phone's queue, from a little before [current] to as far ahead as one
     * message holds, to the speaker.
     */
    private fun sendQueue(current: String?, positionMs: Long, play: Boolean, keep: Boolean) {
        val player = localPlayer() ?: return
        if (player.mediaItemCount == 0) return
        val at = current?.let { id -> (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id } }
            ?: player.currentMediaItemIndex.coerceAtLeast(0)
        val items = JSONArray()
        val ids = ArrayList<String>()
        var size = 0
        val from = (at - 10).coerceAtLeast(0)
        for (i in from until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val entry = JSONObject().put("id", item.mediaId).put("song", songJson(item))
            size += entry.toString().length
            if (size > 55_000 && i > at) break
            items.put(entry)
            ids += item.mediaId
        }
        sentIds = ids
        val currentId = player.getMediaItemAt(at).mediaId
        send(
            JSONObject().put("type", "queue").put("items", items).put("current", currentId)
                .put("position", positionMs).put("play", play).put("keep", keep),
        )
        TrackLog.d(tag, "connect: queue sent (${ids.size} songs, on ${player.getMediaItemAt(at).mediaMetadata.title})")
    }

    /** The listener skipped or picked a song on the phone. */
    fun onPhoneMoved(item: MediaItem) {
        if (following) return
        if (item.mediaId in sentIds) {
            send(JSONObject().put("type", "jump").put("id", item.mediaId))
        } else {
            sendQueue(item.mediaId, 0L, play = true, keep = false)
        }
    }

    /** The phone's queue was edited (added to, reordered, AutoPlay): sent again, the song playing kept. */
    fun onQueueChanged() {
        queueJob?.cancel()
        queueJob = scope.launch {
            delay(800)
            val current = lastState?.optString("id")?.ifEmpty { null } ?: localPlayer()?.currentMediaItem?.mediaId
            sendQueue(current, 0L, play = true, keep = true)
        }
    }

    fun repeat(mode: Int) {
        // Player: 0 off, 1 one, 2 all — the speaker uses the same numbers.
        send(JSONObject().put("type", "repeat").put("mode", mode))
    }

    fun next() = send(JSONObject().put("type", "next"))
    fun previous() = send(JSONObject().put("type", "prev"))

    // ---- what the speaker says ---------------------------------------------

    private suspend fun onMessage(m: JSONObject) {
        when (m.optString("type")) {
            "state" -> onState(m)
            "need" -> answerNeed(m.optString("id"), m.optBoolean("viaPhone"))
            "more" -> {
                val current = lastState?.optString("id")?.ifEmpty { null } ?: localPlayer()?.currentMediaItem?.mediaId
                sendQueue(current, 0L, play = true, keep = true)
            }
            "ended" -> {
                // The speaker played its last one: what comes after it here (AutoPlay may have added some).
                val player = localPlayer() ?: return
                val last = m.optString("lastId")
                val at = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == last }
                if (at != null && at + 1 < player.mediaItemCount) {
                    sendQueue(player.getMediaItemAt(at + 1).mediaId, 0L, play = true, keep = false)
                }
            }
        }
    }

    private suspend fun answerNeed(id: String, viaPhone: Boolean) {
        val player = localPlayer() ?: return
        val item = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }.firstOrNull { it.mediaId == id }
            ?: lastItems[id]
        if (item == null) {
            send(JSONObject().put("type", "stream").put("id", id).put("error", "unknown"))
            return
        }
        if (viaPhone) CastRouting.proxyOnly.add(id)
        val started = SystemClock.elapsedRealtime()
        val stream = runCatching { resolve(item) }.getOrNull()
        if (stream == null) {
            TrackLog.w(tag, "connect: no link for ${item.mediaMetadata.title}")
            send(JSONObject().put("type", "stream").put("id", id).put("error", "none"))
            return
        }
        TrackLog.d(tag, "connect: link for ${item.mediaMetadata.title} in ${SystemClock.elapsedRealtime() - started}ms")
        send(
            JSONObject().put("type", "stream").put("id", id).put("url", stream.url)
                .put("mime", stream.mimeType).put("art", stream.artworkUrl),
        )
    }

    /** Songs the speaker holds that this phone's queue doesn't (taken up from another phone). */
    private val lastItems = HashMap<String, MediaItem>()

    private fun onState(m: JSONObject) {
        lastState = m
        if (!gotHello) gotHello = true
        val player = localPlayer()
        // The speaker's songs, when it sent them: a phone with nothing (or
        // another queue) takes the speaker's up.
        val items = m.optJSONArray("items")
        val speakerSongs = ArrayList<MediaItem>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val json = o.optJSONObject("song")?.optString("json")?.ifEmpty { null } ?: continue
                val song = LastPlayed.songFromJson(json) ?: continue
                val mi = song.toMediaItem()
                lastItems[o.optString("id")] = mi
                speakerSongs += mi
            }
            m.optJSONArray("ids")?.let { arr -> sentIds = (0 until arr.length()).map { arr.optString(it) } }
        }
        val id = m.optString("id").ifEmpty { null } ?: run {
            // The speaker holds nothing yet: this phone's queue goes over.
            if (!pushed && (localPlayer()?.mediaItemCount ?: 0) > 0) {
                pushed = true
                sendQueue(localPlayer()?.currentMediaItem?.mediaId, startPosition, startPlay, keep = false)
            }
            onChanged()
            return
        }
        pushed = true
        if (player != null && player.currentMediaItem?.mediaId != id) {
            val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }
            following = true
            try {
                if (index != null) {
                    player.seekTo(index, m.optLong("position").coerceAtLeast(0L))
                } else if (speakerSongs.isNotEmpty()) {
                    // Not in this phone's queue: the speaker's queue becomes the phone's.
                    val at = speakerSongs.indexOfFirst { it.mediaId == id }.coerceAtLeast(0)
                    player.setMediaItems(speakerSongs, at, m.optLong("position").coerceAtLeast(0L))
                    player.playWhenReady = false
                    player.prepare()
                    TrackLog.d(tag, "connect: took up the speaker's queue (${speakerSongs.size} songs)")
                } else {
                    lastItems[id]?.let { mi ->
                        val at = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
                        player.addMediaItem(at, mi)
                        player.seekTo(at, m.optLong("position").coerceAtLeast(0L))
                    } ?: send(JSONObject().put("type", "hello"))
                }
            } finally {
                following = false
            }
        }
        onChanged()
    }

    /** The speaker's own idea of its position, for while its media status hasn't caught up. */
    fun positionMs(): Long = lastState?.optLong("position") ?: C.TIME_UNSET
}

/** BitChord Audio: our own speaker app, when its id is set in Settings. */
object CastConnect {
    const val NAMESPACE = "urn:x-cast:com.bitchord.connect"
    private const val PREFS = "bitchord_cast"
    private const val KEY = "connectAppId"

    @Volatile
    private var cached: String? = null

    fun appId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.trim()?.uppercase()?.ifEmpty { null }
            .also { cached = it }

    fun setAppId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (id.isNullOrBlank()) remove(KEY) else putString(KEY, id.trim().uppercase()) }.apply()
        cached = id?.trim()?.uppercase()?.ifEmpty { null }
    }

    /** Whether [session] is with our speaker app. */
    fun isOurs(session: CastSession): Boolean {
        val id = cached ?: return false
        return runCatching { session.applicationMetadata?.applicationId }.getOrNull()?.equals(id, ignoreCase = true) == true
    }
}
