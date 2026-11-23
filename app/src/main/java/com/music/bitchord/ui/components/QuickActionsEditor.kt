package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.PlayerQuickAction
import kotlin.math.roundToInt

/** BitChord Audio: a quick button's name in the app's language. */
@Composable
fun PlayerQuickAction.title(): String = stringResource(
    when (this) {
        PlayerQuickAction.VIDEO -> R.string.qa_video
        PlayerQuickAction.VIDEO_AUDIO -> R.string.qa_video_audio
        PlayerQuickAction.TRANSLATION -> R.string.qa_translation
        PlayerQuickAction.LYRICS -> R.string.qa_lyrics
        PlayerQuickAction.STAR -> R.string.qa_star
        PlayerQuickAction.ALBUM -> R.string.qa_album
        PlayerQuickAction.ARTIST -> R.string.qa_artist
        PlayerQuickAction.DONT_RECOMMEND -> R.string.qa_dont_recommend
        PlayerQuickAction.DOWNLOAD -> R.string.qa_download
        PlayerQuickAction.DELETE_COPY -> R.string.qa_delete_copy
        PlayerQuickAction.RADIO -> R.string.qa_radio
        PlayerQuickAction.ADD_TO_PLAYLIST -> R.string.qa_playlist
        PlayerQuickAction.SHARE -> R.string.qa_share
        PlayerQuickAction.SLEEP -> R.string.qa_sleep
        PlayerQuickAction.SOURCE -> R.string.qa_source
        PlayerQuickAction.COVER -> R.string.qa_cover
        PlayerQuickAction.INFO -> R.string.qa_info
        PlayerQuickAction.SHADE -> R.string.qa_shade
    },
)

/**
 * BitChord Audio: the player's buttons over the transport — which, and in what order.
 * Held and dragged to reorder; − takes one out, + puts one in (up to eight; the ⋯ menu
 * is always there besides).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsEditor(onDismiss: () -> Unit) {
    val saved by AppSettings.playerQuickActions.collectAsStateWithLifecycle()
    var order by remember { mutableStateOf(saved.mapNotNull { PlayerQuickAction.of(it) }) }
    fun commit(list: List<PlayerQuickAction>) {
        order = list
        AppSettings.setPlayerQuickActions(list.map { it.id })
    }
    val rowHeight = 52.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragging by remember { mutableStateOf<PlayerQuickAction?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.quick_actions_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.quick_actions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            order.forEachIndexed { index, action -> key(action) {
                val isDragged = dragging == action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDragged) 0.9f else 0.45f))
                        .pointerInput(action) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = action; dragOffset = 0f },
                                onDragEnd = { dragging = null; dragOffset = 0f },
                                onDragCancel = { dragging = null; dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val from = order.indexOf(action)
                                val steps = (dragOffset / rowPx).roundToInt()
                                val to = (from + steps).coerceIn(0, order.lastIndex)
                                if (to != from) {
                                    val list = order.toMutableList()
                                    list.add(to, list.removeAt(from))
                                    dragOffset -= (to - from) * rowPx
                                    commit(list)
                                }
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) { Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Text(action.title(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(
                        Icons.Rounded.RemoveCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clip(CircleShape).clickable { commit(order - action) }.padding(6.dp),
                    )
                }
                if (index < order.lastIndex) Spacer(Modifier.height(6.dp))
            } }
            val rest = PlayerQuickAction.entries.filter { it !in order }
            if (rest.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.quick_actions_more), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val full = order.size >= PlayerQuickAction.MAX
                for (action in rest) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !full) { commit(order + action) }
                            .padding(horizontal = 12.dp)
                            .graphicsLayer { alpha = if (full) 0.4f else 1f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Text(action.title(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { commit(PlayerQuickAction.DEFAULT) }) { Text(stringResource(R.string.quick_actions_reset)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
