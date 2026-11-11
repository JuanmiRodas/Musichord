package com.music.bitchord.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicVideo
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.video_quality
import com.music.bitchord.sharedui.resources.video_quality_auto
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import coil3.request.ImageRequest
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.theme.HideSystemBars
import kotlinx.coroutines.delay

/** How long the controls stay over a playing video untouched. */
private const val VIDEO_CONTROLS_IDLE_MS = 3_500L

/**
 * A music video on a phone held sideways: the picture is the whole screen,
 * letterboxed in black, with the system bars away. A tap brings the controls
 * up over it — the title, the scrubber and the transport — and they stand
 * down again a few seconds after the last touch while it plays, as in every
 * video player. [onExitFullscreen] goes back to the ordinary landscape player.
 */
@Composable
internal fun FullscreenVideoPlayer(
    song: Song,
    isPlaying: Boolean,
    artRequest: ImageRequest,
    onRenderedChanged: (Boolean) -> Unit,
    rendered: Boolean,
    onExitFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** BitChord Audio: video mode's karaoke lyrics, over the bottom of the picture. */
    lyrics: (@Composable (Modifier) -> Unit)? = null,
    /** BitChord Audio: video mode's buttons — lyrics on/off, the video's own sound. */
    lyricsOn: Boolean = true,
    onToggleLyrics: (() -> Unit)? = null,
    originalAudio: Boolean = false,
    onToggleOriginalAudio: (() -> Unit)? = null,
    translationOn: Boolean = false,
    onToggleTranslation: (() -> Unit)? = null,
    /** BitChord Audio: the animated cover to show while the picture loads. */
    loadingCanvas: com.music.bitchord.data.canvas.CanvasArtwork? = null,
    /** BitChord Audio: a double tap's skip, ±10 s. */
    onSeekBy: ((Long) -> Unit)? = null,
) {
    HideSystemBars(true)
    var controlsShown by remember { mutableStateOf(true) }
    // Bumped by every touch on the controls, so the idle timer starts over.
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, isPlaying, touches) {
        if (controlsShown && isPlaying) {
            delay(VIDEO_CONTROLS_IDLE_MS)
            controlsShown = false
        }
    }

    // BitChord Audio: which side a double tap skipped, for its little "10 s" mark.
    var skipped by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(skipped) {
        if (skipped != null) {
            delay(650)
            skipped = null
        }
    }
    val seekBy by rememberUpdatedState(onSeekBy)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsShown = !controlsShown },
                    onDoubleTap = { at ->
                        val seek = seekBy ?: return@detectTapGestures
                        val forward = at.x > size.width / 2f
                        seek(if (forward) 10_000L else -10_000L)
                        skipped = (if (forward) 1 else -1) to System.nanoTime()
                    },
                )
            },
    ) {
        // Black with a spinner until the picture's first frame lands.
        if (!rendered) VideoLoadingBackdrop(Modifier.fillMaxSize(), artRequest = artRequest, canvas = loadingCanvas, isPlaying = isPlaying)
        MusicVideoSurface(
            videoId = song.videoId,
            fit = true,
            onRenderedChanged = onRenderedChanged,
            modifier = Modifier.fillMaxSize(),
        )
        if (lyrics != null) {
            // Lifted clear of the scrubber while the controls are up.
            val lift by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (controlsShown) 84.dp else 22.dp,
                label = "videoLyricsLift",
            )
            AnimatedVisibility(
                visible = lyricsOn,
                enter = fadeIn(tween(260)),
                exit = fadeOut(tween(260)),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .videoLyricsScrim()
                        .padding(top = 56.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    lyrics(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(start = 72.dp, end = 72.dp, bottom = lift),
                    )
                }
            }
        }
        // BitChord Audio: where a double tap skipped to.
        skipped?.let { (side, _) ->
            Box(
                modifier = Modifier
                    .align(if (side > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 64.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (side > 0) "10 s ›" else "‹ 10 s",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.62f),
                            0.30f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    )
                    // Any touch on the controls starts the idle timer over.
                    // Watched, not taken: a tap on bare picture still reaches
                    // the box below and puts them away.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                touches++
                            }
                        }
                    }
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    VideoCornerControls(
                        fullscreen = true,
                        onToggleFullscreen = onExitFullscreen,
                        onOpenQuality = onOpenQuality,
                        extras = {
                            onToggleOriginalAudio?.let {
                                VideoChromeButton(
                                    icon = if (originalAudio) Icons.Rounded.MusicVideo else Icons.Rounded.MusicNote,
                                    active = originalAudio,
                                    onClick = it,
                                )
                            }
                            onToggleLyrics?.let {
                                VideoChromeButton(icon = Icons.Rounded.Subtitles, active = lyricsOn, onClick = it)
                            }
                            if (lyricsOn) onToggleTranslation?.let {
                                VideoChromeButton(icon = Icons.Rounded.Translate, active = translationOn, onClick = it)
                            }
                        },
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp),
                ) {
                    transport()
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    scrubber()
                }
            }
        }
    }
}

/**
 * What stands in for a music video until its first frame lands: black, with a
 * spinner, rather than the still artwork. Shared by the portrait card and the
 * fullscreen landscape player.
 */
@Composable
internal fun VideoLoadingBackdrop(
    modifier: Modifier = Modifier,
    /** BitChord Audio: video mode's stand-in — the cover, breathing, until the picture lands. */
    artRequest: ImageRequest? = null,
    /** BitChord Audio: the song's animated cover, shown in its place when there is one. */
    canvas: com.music.bitchord.data.canvas.CanvasArtwork? = null,
    isPlaying: Boolean = true,
) {
    if (artRequest == null && canvas == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
        }
        return
    }
    val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "videoWait")
    val alpha by breath.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween<Float>(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "videoWaitAlpha",
    )
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (canvas != null) {
            CanvasArtworkPlayer(canvas = canvas, isPlaying = isPlaying, modifier = Modifier.fillMaxSize())
        } else if (artRequest != null) {
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

/**
 * The two glass boxes in a music video's top-right corner: the quality box —
 * the web player's VideoQuality, which reads the rendition on screen and opens
 * the quality drawer — and the full-screen button beside it.
 */
@Composable
internal fun VideoCornerControls(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenQuality: () -> Unit,
    modifier: Modifier = Modifier,
    /** BitChord Audio: video mode's own buttons, before the rest. */
    extras: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        extras()
        VideoQualityBox(onClick = onOpenQuality)
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(onClick = onToggleFullscreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the quality box and drawer say is playing: the held height, else the one on screen. */
private fun MusicVideoQualityUi.selected(): Int? =
    if (held == null || held !in heights) activeHeight else held

/**
 * The playing video's quality: 360p, 480p, HD, FullHD, 2K, 4K — what is on
 * screen, which is not always what was picked (4K held, then a video shot in
 * 1080p). Nothing until the renditions are known and there is a choice to make.
 */
@Composable
private fun VideoQualityBox(onClick: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    if (quality.heights.size < 2) return
    val label = quality.selected()?.let(::videoQualityLabel) ?: "Auto"
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "videoQualityLabel",
        ) { text ->
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * The quality list, in the same drawer as the audio output: Auto, then every
 * height this video offers, the one playing marked. Picking one holds it from
 * now on — for the next video too, where that video has it.
 */
@Composable
internal fun VideoQualitySheet(hazeState: HazeState, onDismiss: () -> Unit) {
    val quality by PlayerPlatform.host.musicVideoQuality.collectAsStateWithLifecycle()
    val selected = quality.selected()
    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.video_quality),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QualityRow(
                name = stringResource(Res.string.video_quality_auto),
                detail = if (quality.held == null) selected?.let(::videoQualityLabel) else null,
                active = quality.held == null,
            ) {
                PlayerPlatform.host.setMusicVideoQuality(null)
                onDismiss()
            }
            quality.heights.forEach { height ->
                QualityRow(
                    name = videoQualityLabel(height),
                    detail = "${height}p",
                    active = quality.held != null && selected == height,
                ) {
                    PlayerPlatform.host.setMusicVideoQuality(height)
                    onDismiss()
                }
            }
        }
    }
}

/** One row of [VideoQualitySheet], drawn as the audio output drawer draws an output. */
@Composable
private fun QualityRow(name: String, detail: String?, active: Boolean, onSelect: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !active,
            ) {
                haptics.play(Haptic.Select)
                onSelect()
            }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = Color.White.copy(alpha = if (active) 1f else 0.85f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}


/** BitChord Audio: one of video mode's buttons on the picture, lit while [active]. */
@Composable
internal fun VideoChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color.White.copy(alpha = 0.32f) else Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (active) 1f else 0.7f),
            modifier = Modifier.size(19.dp),
        )
    }
}
