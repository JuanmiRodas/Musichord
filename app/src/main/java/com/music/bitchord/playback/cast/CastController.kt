package com.music.bitchord.playback.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.music.bitchord.data.DebugLog as Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Which receivers are out there, and which one the music is on.
 *
 * The half of casting the screen talks to. Choosing a receiver is the Cast
 * framework's own business — selecting its route starts a session — so this
 * only lists routes, asks for one to be selected, and reports what the session
 * does about it. What *plays* on the receiver is [CastPlayback]'s, which is
 * handed each session as it begins.
 *
 * Everything here is main-thread, like the router it wraps.
 */
object CastController {
    private const val TAG = "BitChordCast"

    data class Device(
        val id: String,
        val name: String,
        val connecting: Boolean,
        val connected: Boolean,
    )

    data class State(
        /** False until the framework is up, and for good where Play Services is missing. */
        val supported: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val connecting: Boolean = false,
        /** BitChord Audio: the speaker last cast to (kept after disconnecting). */
        val recentName: String? = null,
    ) {
        val casting: Boolean get() = connectedName != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The receiver's own volume, 0..1. */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var started = false
    private var playback: CastPlayback? = null

    /** The session whose volume is being followed, so it can be let go of. */
    private var watched: CastSession? = null

    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            watched?.let { _volume.value = it.volume.toFloat().coerceIn(0f, 1f) }
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = update { it.copy(connecting = true) }

        override fun onSessionStarted(session: CastSession, sessionId: String) =
            began(session, asked = false)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "session start failed: $error")
            update { it.copy(connecting = false) }
            playback?.onConnectFailed()
        }

        override fun onSessionEnding(session: CastSession) {
            playback?.onSessionEnding(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            rememberSession(null)
            stopWatching()
            update { it.copy(connecting = false, connectedName = null) }
            playback?.onSessionEnded(error)
            publishDevices()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) =
            update { it.copy(connecting = true) }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
            began(session, asked = true)

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "session resume failed: $error; looking for the speaker to join it again")
            com.music.bitchord.data.TrackLog.d(TAG, "cast: saved session could not be resumed ($error)")
            update { it.copy(connecting = false) }
            rejoinLostSession()
        }

        // A dropped connection the framework is still trying to win back.
        // Nothing to do: the receiver keeps playing its queue meanwhile.
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /**
     * Brings the framework up, once. Safe to call from anywhere, any number of
     * times; where Google Play Services is absent it settles on unsupported and
     * the Cast row never appears.
     */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            Log.i(TAG, "Play Services unavailable; casting disabled")
            return
        }
        try {
            // The task form, so the framework's start-up work is not on the
            // main thread of whichever screen happened to ask first.
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx -> onReady(app, ctx) }
                .addOnFailureListener {
                    Log.w(TAG, "Cast framework unavailable", it)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Cast framework unavailable", e)
        }
    }

    private fun onReady(app: Context, ctx: CastContext) {
        appContext = app
        prefs = app.getSharedPreferences("bitchord_cast", Context.MODE_PRIVATE)
        castContext = ctx
        router = MediaRouter.getInstance(app)
        selector = ctx.mergedSelector
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        update { it.copy(supported = true, recentName = prefs?.getString(KEY_RECENT_NAME, null)) }
        // A session that outlived the process: the framework restores it on its
        // own and reports it through the listener, but one already live by the
        // time this runs would be reported to nobody.
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { began(it, asked = true) }
        // BitChord Audio: the app went away mid-cast (a crash) and the
        // framework hasn't brought the session back by itself: the speaker it
        // was casting to is looked for and joined, taking up what it plays.
        if (prefs?.getString(KEY_DEVICE, null) != null) {
            Handler(Looper.getMainLooper()).postDelayed({ rejoinIfLost() }, 5_000)
        }
    }

    private var appContext: Context? = null
    private var prefs: android.content.SharedPreferences? = null

    /** Set while joining a lost session: the speaker's queue is taken up, not replaced. */
    @Volatile
    private var rejoining = false

    private const val KEY_DEVICE = "lastDevice"
    private const val KEY_DEVICE_NAME = "lastDeviceName"
    private const val KEY_RECENT_ID = "recentDevice"
    private const val KEY_RECENT_NAME = "recentDeviceName"
    private const val REJOIN_SCAN_MS = 180_000L

    /** The speaker a session is on, kept until the session ends — a crash or an update leaves it. */
    private fun rememberSession(session: CastSession?) {
        val device = session?.castDevice
        prefs?.edit()?.apply {
            if (device == null) {
                remove(KEY_DEVICE)
                remove(KEY_DEVICE_NAME)
            } else {
                putString(KEY_DEVICE, device.deviceId)
                putString(KEY_DEVICE_NAME, device.friendlyName)
                putString(KEY_RECENT_ID, device.deviceId)
                putString(KEY_RECENT_NAME, device.friendlyName)
            }
        }?.apply()
        device?.friendlyName?.let { name -> update { it.copy(recentName = name) } }
    }

    /**
     * BitChord Audio: called whenever the app comes to the front — a speaker
     * still playing what the app sent it, with no session here (the app was
     * updated, crashed or was closed by the system), is looked for and joined.
     */
    fun rejoinIfLost() {
        // Any session at all — connecting, or briefly suspended (the Wi-Fi
        // dozing) — is the framework's to bring back; choosing the speaker
        // again on top of it restarted the cast.
        if (castContext?.sessionManager?.currentCastSession != null) return
        if (_state.value.connecting) return
        rejoinLostSession()
    }

    /**
     * Scans for the speaker the app was last casting to, for three minutes,
     * and joins it when found — by its id, or by its name (a speaker group can
     * come back under another id). The stock receiver is joined as it runs
     * (not restarted), so the music carries on and its queue is adopted.
     */
    fun rejoinLostSession() {
        val deviceId = prefs?.getString(KEY_DEVICE, null) ?: return
        val deviceName = prefs?.getString(KEY_DEVICE_NAME, null)
        val router = router ?: return
        val selector = selector ?: return
        if (rejoining) return
        rejoining = true
        com.music.bitchord.data.TrackLog.d(TAG, "cast: no session; looking for $deviceName ($deviceId) to join it again")
        val main = Handler(Looper.getMainLooper())
        main.post {
            fun matches(r: MediaRouter.RouteInfo): Boolean {
                if (!r.matchesSelector(selector) || r.isDefault || r.isBluetooth) return false
                val id = runCatching { com.google.android.gms.cast.CastDevice.getFromBundle(r.extras)?.deviceId }.getOrNull()
                if (id != null && id == deviceId) return true
                if (r.id.contains(deviceId)) return true
                return deviceName != null && r.name.toString() == deviceName
            }
            fun tryJoin(): Boolean {
                if (castContext?.sessionManager?.currentCastSession != null) return true
                val route = router.routes.firstOrNull(::matches) ?: return false
                // Something else playing there now (another app took the
                // speaker): left alone — joining would cut it off.
                val status = route.description?.toString().orEmpty()
                if (status.isNotBlank() && !status.contains("Default Media Receiver", ignoreCase = true)) {
                    com.music.bitchord.data.TrackLog.d(TAG, "cast: ${route.name} is on \"$status\" now; not rejoining")
                    rememberSession(null)
                    return true
                }
                com.music.bitchord.data.TrackLog.d(TAG, "cast: rejoining the session on ${route.name}")
                update { it.copy(connecting = true) }
                router.selectRoute(route)
                return true
            }
            if (tryJoin()) return@post
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (tryJoin()) r.removeCallback(this)
                }
                override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (tryJoin()) r.removeCallback(this)
                }
            }
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN or MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            main.postDelayed({
                router.removeCallback(callback)
                if (castContext?.sessionManager?.currentCastSession?.isConnected != true) {
                    rejoining = false
                    update { it.copy(connecting = false) }
                    com.music.bitchord.data.TrackLog.d(
                        TAG,
                        "cast: $deviceName not found to rejoin; seen: " + router.routes.joinToString { it.name.toString() },
                    )
                }
            }, REJOIN_SCAN_MS)
        }
    }

    /** Binds the service's playback half; it is handed any session already live. */
    fun attach(target: CastPlayback) {
        playback = target
        castContext?.sessionManager?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { target.onSessionBegan(it, resumed = true) }
    }

    fun detach(target: CastPlayback) {
        if (playback === target) playback = null
    }

    /**
     * Keeps [State.devices] current until the returned function is called.
     * [active] asks the router to scan rather than merely listen, which finds a
     * receiver in a couple of seconds instead of a minute — worth it only while
     * somebody is looking at the list.
     */
    fun discover(active: Boolean): () -> Unit {
        val router = router ?: return {}
        val selector = selector ?: return {}
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteSelected(
                router: MediaRouter,
                selected: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishDevices()

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = publishDevices()
        }
        val flags = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
            if (active) MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else 0
        router.addCallback(selector, callback, flags)
        publishDevices()
        return { router.removeCallback(callback) }
    }

    fun connect(deviceId: String) {
        val router = router ?: return
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        update { it.copy(connecting = true) }
        router.selectRoute(route)
    }

    /**
     * Ends the session. [resumeHere] says the listener asked to bring the music
     * back to this phone, rather than the receiver having gone away — see
     * [CastPlayback.onSessionEnded].
     */
    /**
     * BitChord Audio: the one-tap cast button. Casting already: brings the
     * music back to the phone. Otherwise looks for the speaker last cast to
     * (by id, or by name) and casts to it — no list to open.
     */
    fun castToRecent() {
        if (_state.value.casting) {
            disconnect(resumeHere = true)
            return
        }
        val id = prefs?.getString(KEY_RECENT_ID, null) ?: return
        val name = prefs?.getString(KEY_RECENT_NAME, null)
        val router = router ?: return
        val selector = selector ?: return
        val main = Handler(Looper.getMainLooper())
        main.post {
            fun matches(r: MediaRouter.RouteInfo): Boolean {
                if (!r.matchesSelector(selector) || r.isDefault || r.isBluetooth) return false
                val deviceId = runCatching { com.google.android.gms.cast.CastDevice.getFromBundle(r.extras)?.deviceId }.getOrNull()
                return deviceId == id || r.id.contains(id) || (name != null && r.name.toString() == name)
            }
            fun tryCast(): Boolean {
                val route = router.routes.firstOrNull(::matches) ?: return false
                com.music.bitchord.data.TrackLog.d(TAG, "cast: one tap to ${route.name}")
                update { it.copy(connecting = true) }
                router.selectRoute(route)
                return true
            }
            if (tryCast()) return@post
            update { it.copy(connecting = true) }
            var done = false
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (!done && tryCast()) { done = true; r.removeCallback(this) }
                }
                override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) {
                    if (!done && tryCast()) { done = true; r.removeCallback(this) }
                }
            }
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN or MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            main.postDelayed({
                router.removeCallback(callback)
                if (!done) {
                    update { it.copy(connecting = false) }
                    appContext?.let { c ->
                        android.widget.Toast.makeText(c, c.getString(com.music.bitchord.R.string.cast_recent_not_found, name ?: ""), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }, 20_000)
        }
    }

    /** A rejoined speaker turned out to hold nothing of ours: let go of it, quietly. */
    fun forgetAndLeave() {
        rememberSession(null)
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun disconnect(resumeHere: Boolean) {
        playback?.resumeHereOnEnd = resumeHere
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun setVolume(level: Float) {
        val value = level.coerceIn(0f, 1f)
        _volume.value = value
        try {
            castContext?.sessionManager?.currentCastSession?.volume = value.toDouble()
        } catch (e: Exception) {
            Log.w(TAG, "could not set receiver volume", e)
        }
    }

    private fun began(session: CastSession, asked: Boolean) {
        // A session joined after a crash takes up the speaker's queue.
        val resumed = asked || rejoining
        val rejoined = rejoining
        rejoining = false
        rememberSession(session)
        watch(session)
        update {
            it.copy(
                connecting = false,
                connectedName = session.castDevice?.friendlyName ?: it.connectedName ?: "Cast",
            )
        }
        publishDevices()
        val target = playback
        if (target != null) {
            target.onSessionBegan(session, resumed, rejoined)
        } else {
            // The player service isn't running (the app was restarted): it is
            // started, and adopts the session as it attaches.
            appContext?.let(::startPlayerService)
        }
    }

    private fun startPlayerService(context: Context) {
        runCatching {
            val token = androidx.media3.session.SessionToken(
                context,
                android.content.ComponentName(context, com.music.bitchord.playback.PlaybackService::class.java),
            )
            val future = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { if (future.isDone) future.get().release() else future.cancel(true) }
            }, 15_000)
        }.onFailure { Log.w(TAG, "could not start the player for the cast session", it) }
    }

    private fun watch(session: CastSession) {
        stopWatching()
        watched = session
        session.addCastListener(volumeListener)
        _volume.value = session.volume.toFloat().coerceIn(0f, 1f)
    }

    private fun stopWatching() {
        watched?.removeCastListener(volumeListener)
        watched = null
    }

    private fun publishDevices() {
        val router = router
        val selector = selector
        val devices = if (router == null || selector == null) {
            emptyList()
        } else {
            router.routes
                .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .map {
                    Device(
                        id = it.id,
                        name = it.name,
                        connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        connected = it.isSelected,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
        update { it.copy(devices = devices) }
    }

    private inline fun update(change: (State) -> State) {
        _state.value = change(_state.value)
    }
}
