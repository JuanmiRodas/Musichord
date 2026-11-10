package com.music.bitchord.data.scrobbling

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: scrobbles to Last.fm what other music apps play (Spotify,
 * YouTube Music, a local player…), the idea from LastWave's universal
 * scrobbler. Android lets an app read other apps' media sessions only with
 * notification access, so this runs inside a notification listener; nothing
 * of the notifications themselves is read. Uses the same rules as our own
 * scrobbling ([ScrobbleManager]): half the song or four minutes, songs over
 * 30 seconds.
 */
object OtherAppsScrobble {

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Last thing heard from another app, for the settings row. */
    val lastSeen = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("bitchord_other_scrobble", Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean("enabled", false)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    /** Whether Android has given this app notification access. */
    fun hasAccess(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(context, OtherAppsListener::class.java).flattenToString()) == true

    fun openAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** The listener Android binds once notification access is granted. */
class OtherAppsListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessions: MediaSessionManager? = null
    private val watched = HashMap<MediaSession.Token, Watch>()

    private inner class Watch(val controller: MediaController) : MediaController.Callback() {
        val scrobbler = ScrobbleManager(scope)
        var key: String? = null
        var playing = false

        fun evaluate() {
            val settings = com.music.bitchord.data.settings.AppSettings
            if (!OtherAppsScrobble.enabled.value || !settings.lastfmEnabled.value ||
                !settings.lastfmScrobbleEnabled.value || settings.lastfmSessionKey.value.isBlank()
            ) return
            scrobbler.minSongDuration = settings.scrobbleMinDuration.value
            scrobbler.scrobbleDelayPercent = settings.scrobbleDelayPercent.value
            scrobbler.scrobbleDelaySeconds = settings.scrobbleDelaySeconds.value
            scrobbler.useNowPlaying = settings.lastfmNowPlaying.value
            scrobbler.usePrimaryArtistOnly = settings.lastfmPrimaryArtistOnly.value
            val md = controller.metadata ?: return
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
            if (title.isEmpty() || artist.isEmpty()) return
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
            val nowPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            val song = Song(
                videoId = "other:${controller.packageName}:$artist:$title",
                title = title,
                artist = artist,
                thumbnailUrl = null,
                albumName = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            )
            val newKey = "$artist|$title"
            if (newKey != key) {
                key = newKey
                scrobbler.onSongStop()
                if (nowPlaying) {
                    scrobbler.onSongStart(song, duration)
                    OtherAppsScrobble.lastSeen.value = "$title · $artist (${controller.packageName})"
                    TrackLog.d("BitChord", "other app scrobble: started '$title' by $artist in ${controller.packageName}")
                }
                playing = nowPlaying
                return
            }
            if (nowPlaying != playing) {
                playing = nowPlaying
                if (nowPlaying) scrobbler.onSongResume(song) else scrobbler.onSongPause()
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
        override fun onSessionDestroyed() {
            scrobbler.onSongStop()
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        OtherAppsScrobble.init(this)
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        sessions = manager
        val me = ComponentName(this, OtherAppsListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, me)
            track(manager.getActiveSessions(me))
        }.onFailure { TrackLog.w("BitChord", "other app scrobble: can't read media sessions: ${it.message}") }
    }

    override fun onListenerDisconnected() {
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watched.values.forEach { it.controller.unregisterCallback(it); it.scrobbler.destroy() }
        watched.clear()
        super.onListenerDisconnected()
    }

    private fun track(controllers: List<MediaController>) {
        val mine = packageName
        val live = controllers.filter { it.packageName != mine }
        val liveTokens = live.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in liveTokens }.forEach { token ->
            watched.remove(token)?.let { it.controller.unregisterCallback(it); it.scrobbler.destroy() }
        }
        for (c in live) {
            if (watched.containsKey(c.sessionToken)) continue
            val w = Watch(c)
            watched[c.sessionToken] = w
            c.registerCallback(w)
            w.evaluate()
        }
    }
}
