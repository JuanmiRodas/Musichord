package com.music.bitchord.aod

import android.appwidget.AppWidgetHostView
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.playback.NotificationIcons
import com.music.bitchord.playback.PlaybackService
import kotlinx.coroutines.delay

/**
 * BitChord Audio: the always-on music screen — in place of the phone's own
 * always-on display while music plays (see [AodTrigger]), over the lock screen,
 * without unlocking.
 *
 * Black, dim, and moving: the clock, date, notification icons and your widgets
 * in one corner, the cover, title and artist in the opposite one, both going
 * round the corners every 30 seconds; the line being sung and the controls
 * (previous, play/pause, next, stop) in the middle, drifting a few pixels a
 * minute. A tap anywhere else goes back to the lock screen. Paused for a
 * minute, it closes by itself and the screen sleeps.
 */
class AodActivity : ComponentActivity() {

    private var future: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AodSettings.init(this)
        showing = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = AodSettings.brightness.value }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        AodTrigger.clearNotification(this)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener({ controllerState.value = runCatching { f.get() }.getOrNull() }, mainExecutor)
        }
        setContent {
            val controller by controllerState
            AodScreen(controller, onLeave = { finish() })
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { AodWidgets.host(this).startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { AodWidgets.host(this).stopListening() }
    }

    override fun onDestroy() {
        showing = false
        runCatching { unregisterReceiver(unlocked) }
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var showing = false
            private set
    }
}

@Composable
private fun AodScreen(controller: MediaController?, onLeave: () -> Unit) {
    // What is playing, read off the player (polled: cheap, and the position moves anyway).
    var meta by remember { mutableStateOf<MediaMetadata?>(null) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var pausedFor by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            meta = c.mediaMetadata
            mediaId = c.currentMediaItem?.mediaId
            playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
            position = c.currentPosition
            pausedFor = if (playing) 0L else pausedFor + 250
            // Paused a minute: closes, and the screen sleeps.
            if (pausedFor > 60_000) onLeave()
            delay(250)
        }
    }

    // The words: as on the player (kept lyrics, else what the player loaded).
    var lines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    val current by com.music.bitchord.data.lyrics.CurrentLyrics.lines.collectAsState()
    LaunchedEffect(mediaId, current) {
        val id = mediaId ?: return@LaunchedEffect
        lines = current?.takeIf { it.first == id }?.second
            ?: runCatching { com.music.bitchord.data.lyrics.EditedLyrics.get(id) }.getOrNull()
            ?: emptyList()
    }
    val timed = lines.any { it.timeMs > 0 }
    val index = if (timed) lines.indexOfLast { it.timeMs <= position } else -1
    val line = lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() }
    val next = ((index + 1) until lines.size).map { lines[it] }.firstOrNull { it.text.isNotBlank() }?.text

    var clock by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockNow()
            delay(1_000L * (60 - java.time.LocalTime.now().second).coerceAtLeast(1))
        }
    }
    var corner by remember { mutableIntStateOf(0) }
    var drift by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(Unit) {
        var step = 0
        while (true) {
            delay(30_000)
            corner = (corner + 1) % 4
            step++
            drift = IntOffset(((step * 7) % 13) - 6, ((step * 5) % 11) - 5)
        }
    }
    val icons by NotificationIcons.icons.collectAsState()
    val widgetIds by AodSettings.widgets.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onLeave),
    ) {
        fun alignment(k: Int) = when (k) {
            0 -> Alignment.TopStart
            1 -> Alignment.TopEnd
            2 -> Alignment.BottomEnd
            else -> Alignment.BottomStart
        }
        fun onRight(k: Int) = k == 1 || k == 2
        val clockCorner = (corner + 2) % 4
        Box(Modifier.fillMaxSize().offset { drift }.padding(horizontal = 24.dp, vertical = 36.dp)) {
            // The song, its cover on the side of its corner.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(alignment(corner))) {
                val right = onRight(corner)
                val cover = @Composable {
                    AsyncImage(
                        model = meta?.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).alpha(0.8f),
                    )
                }
                if (!right) { cover(); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.widthIn(max = 220.dp), horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
                    Text(meta?.title?.toString().orEmpty(), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta?.artist?.toString().orEmpty(), color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (right) { Spacer(Modifier.width(12.dp)); cover() }
            }
            // The time, the date, your notifications and widgets, opposite.
            val clockRight = onRight(clockCorner)
            Column(
                horizontalAlignment = if (clockRight) Alignment.End else Alignment.Start,
                modifier = Modifier.align(alignment(clockCorner)),
            ) {
                Text(clock.first, color = Color.White.copy(alpha = 0.85f), fontSize = 48.sp, fontWeight = FontWeight.Light)
                Text(clock.second, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                if (icons.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        icons.forEach { icon ->
                            Image(bitmap = icon, contentDescription = null, alpha = 0.7f, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                widgetIds.forEach { id -> AodWidget(id) }
            }
        }
        // The line being sung, then the controls, in the middle.
        Column(
            Modifier.align(Alignment.Center).offset { drift }.padding(horizontal = 28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (line != null) {
                Text(line, color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                if (next != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(next, color = Color.White.copy(alpha = 0.35f), fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(24.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                val tint = Color.White.copy(alpha = 0.7f)
                IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = tint, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, modifier = Modifier.size(64.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(48.dp))
                }
                IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = tint, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { controller?.pause(); onLeave() }) { Icon(Icons.Rounded.Stop, "Stop", tint = tint.copy(alpha = 0.5f), modifier = Modifier.size(30.dp)) }
            }
        }
    }
}

/** One widget from another app, dimmed for the black screen. */
@Composable
private fun AodWidget(id: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val info = remember(id) { AodWidgets.info(context, id) } ?: return
    val density = LocalDensity.current
    val heightDp = with(density) { maxOf(info.minHeight, 60).toDp() }
    AndroidView(
        factory = { ctx ->
            AodWidgets.host(ctx).createView(ctx.applicationContext, id, info).apply {
                setPadding(0, 0, 0, 0)
            } as AppWidgetHostView
        },
        modifier = Modifier.padding(top = 10.dp).width(300.dp).height(heightDp).alpha(0.75f),
    )
}

private fun clockNow(): Pair<String, String> {
    val now = java.time.LocalDateTime.now()
    val locale = java.util.Locale.getDefault()
    val time = now.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))
    val date = now.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale))
    return time to date
}
