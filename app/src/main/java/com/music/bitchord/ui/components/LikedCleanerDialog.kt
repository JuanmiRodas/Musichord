package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.LikedCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** BitChord Audio: opened from Settings. */
object LikedCleanerUi {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()
    fun show() { _open.value = true }
    fun close() { _open.value = false }
}

/**
 * BitChord Audio: the Liked Music cleaner.
 *
 *  - **Duplicates**: the same song liked more than once, each group with its
 *    best copy marked; "Keep the best of each" ticks all the others at once.
 *  - **Similar**: songs sharing a title but not the same (another artist,
 *    version or length) — to look over.
 *  - **All**: every liked song, by artist or album or not grouped, a tick on a
 *    group's header ticks the whole group; quick filters for songs only on
 *    YouTube quality, songs liked only on this phone, versions (live, remix…).
 *
 * The length tolerance and whether versions count as the same apply to the
 * first two tabs. Ticked songs are un-liked (on YouTube Music too) in one go,
 * after a confirmation.
 */
@Composable
fun LikedCleanerDialog(
    unlike: suspend (List<String>, (Int) -> Unit) -> Int,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<LikedCleaner.Item>?>(null) }
    var tolerance by remember { mutableIntStateOf(3) }
    var versionsAlike by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { items = withContext(Dispatchers.Default) { LikedCleaner.items() } }
    val all = items
    var duplicates by remember { mutableStateOf<List<LikedCleaner.Group>>(emptyList()) }
    var similar by remember { mutableStateOf<List<LikedCleaner.Group>>(emptyList()) }
    LaunchedEffect(all, tolerance, versionsAlike) {
        if (all == null) return@LaunchedEffect
        duplicates = withContext(Dispatchers.Default) { LikedCleaner.duplicates(all, tolerance, versionsAlike) }
        similar = withContext(Dispatchers.Default) { LikedCleaner.similar(all, tolerance, versionsAlike) }
    }

    fun matches(it: LikedCleaner.Item): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase(Locale.ROOT)
        return listOfNotNull(it.song.title, it.song.artist, it.song.albumName).any { f -> f.lowercase(Locale.ROOT).contains(q) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cleaner_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cleaner_close)) }
                }
                Text(
                    all?.let { stringResource(R.string.cleaner_count, it.size) } ?: stringResource(R.string.browser_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.cleaner_tab_duplicates, duplicates.size)) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.cleaner_tab_similar, similar.size)) })
                    Tab(tab == 2, { tab = 2 }, text = { Text(stringResource(R.string.cleaner_tab_all)) })
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.cleaner_search)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (tab != 2) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cleaner_tolerance, tolerance), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                        Slider(value = tolerance.toFloat(), onValueChange = { tolerance = it.toInt() }, valueRange = 0f..15f, steps = 14, modifier = Modifier.weight(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { versionsAlike = !versionsAlike }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.cleaner_versions_alike), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Switch(checked = versionsAlike, onCheckedChange = { versionsAlike = it })
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        all == null -> Text(stringResource(R.string.browser_loading), modifier = Modifier.padding(16.dp))
                        tab == 0 -> GroupList(duplicates.filter { g -> g.items.any(::matches) }, selected, markBest = true, empty = stringResource(R.string.cleaner_no_duplicates))
                        tab == 1 -> GroupList(similar.filter { g -> g.items.any(::matches) }, selected, markBest = false, empty = stringResource(R.string.cleaner_no_similar))
                        else -> AllList(all.filter(::matches), selected)
                    }
                }
                HorizontalDivider()
                progress?.let { (done, total) ->
                    LinearProgressIndicator(progress = { done / total.toFloat().coerceAtLeast(1f) }, modifier = Modifier.fillMaxWidth())
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tab == 0 && duplicates.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            duplicates.forEach { g -> val keep = g.best.id; g.items.forEach { if (it.id != keep && it.id !in selected) selected += it.id } }
                        }) { Text(stringResource(R.string.cleaner_keep_best_all)) }
                    }
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected.clear() }) { Text(stringResource(R.string.cleaner_clear)) }
                    }
                    Button(onClick = { confirm = true }, enabled = selected.isNotEmpty() && progress == null) {
                        Text(stringResource(R.string.cleaner_unlike, selected.size))
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.cleaner_confirm_title, selected.size)) },
            text = { Text(stringResource(R.string.cleaner_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val ids = selected.toList()
                    progress = 0 to ids.size
                    scope.launch {
                        unlike(ids) { done -> progress = done to ids.size }
                        selected.removeAll(ids)
                        items = withContext(Dispatchers.Default) { LikedCleaner.items().filter { it.id !in ids } }
                        progress = null
                    }
                }) { Text(stringResource(R.string.cleaner_unlike, selected.size)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupList(groups: List<LikedCleaner.Group>, selected: SnapshotStateList<String>, markBest: Boolean, empty: String) {
    if (groups.isEmpty()) {
        Text(empty, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups, key = { g -> g.items.joinToString("|") { it.id } }) { g ->
            val best = g.best.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${g.items.first().song.title} · ${g.items.size}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (markBest) {
                        TextButton(onClick = {
                            g.items.forEach { if (it.id == best) selected.remove(it.id) else if (it.id !in selected) selected += it.id }
                        }) { Text(stringResource(R.string.cleaner_keep_best)) }
                    }
                }
                g.items.forEach { item -> ItemRow(item, item.id in selected, isBest = markBest && item.id == best) { toggle(selected, item.id) } }
            }
        }
    }
}

private enum class Grouping { ARTIST, ALBUM, NONE }

@Composable
private fun AllList(all: List<LikedCleaner.Item>, selected: SnapshotStateList<String>) {
    var grouping by remember { mutableStateOf(Grouping.ARTIST) }
    var onlyYouTube by remember { mutableStateOf(false) }
    var onlyLocal by remember { mutableStateOf(false) }
    var onlyVersions by remember { mutableStateOf(false) }
    val shown = all.filter {
        (!onlyYouTube || it.quality == "YouTube") &&
            (!onlyLocal || it.id in com.music.bitchord.data.LocalLikes.liked.value) &&
            (!onlyVersions || it.version.isNotEmpty())
    }
    val groups: List<Pair<String, List<LikedCleaner.Item>>> = remember(shown, grouping) {
        when (grouping) {
            Grouping.ARTIST -> shown.groupBy { it.song.artist.split(',').first().trim() }.toList().sortedBy { it.first.lowercase(Locale.ROOT) }
            Grouping.ALBUM -> shown.groupBy { (it.song.albumName ?: "—") + " · " + it.song.artist.split(',').first().trim() }.toList().sortedBy { it.first.lowercase(Locale.ROOT) }
            Grouping.NONE -> listOf("" to shown)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(grouping == Grouping.ARTIST, { grouping = Grouping.ARTIST }, { Text(stringResource(R.string.cleaner_by_artist)) })
            FilterChip(grouping == Grouping.ALBUM, { grouping = Grouping.ALBUM }, { Text(stringResource(R.string.cleaner_by_album)) })
            FilterChip(grouping == Grouping.NONE, { grouping = Grouping.NONE }, { Text(stringResource(R.string.cleaner_no_groups)) })
            FilterChip(onlyYouTube, { onlyYouTube = !onlyYouTube }, { Text(stringResource(R.string.cleaner_only_youtube)) })
            FilterChip(onlyLocal, { onlyLocal = !onlyLocal }, { Text(stringResource(R.string.cleaner_only_local)) })
            FilterChip(onlyVersions, { onlyVersions = !onlyVersions }, { Text(stringResource(R.string.cleaner_only_versions)) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cleaner_shown, shown.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { shown.forEach { if (it.id !in selected) selected += it.id } }) { Text(stringResource(R.string.cleaner_select_shown)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (name, list) ->
                if (grouping != Grouping.NONE) {
                    item(key = "h:$name") {
                        val allIn = list.all { it.id in selected }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (allIn) list.forEach { selected.remove(it.id) } else list.forEach { if (it.id !in selected) selected += it.id } }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = allIn, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text("$name · ${list.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                items(list, key = { "i:$name:${it.id}" }) { item ->
                    ItemRow(item, item.id in selected, isBest = false) { toggle(selected, item.id) }
                }
            }
        }
    }
}

private fun toggle(selected: SnapshotStateList<String>, id: String) {
    if (id in selected) selected.remove(id) else selected += id
}

@Composable
private fun ItemRow(item: LikedCleaner.Item, checked: Boolean, isBest: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (isBest) "★ " else "") + item.song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(item.song.artist, item.song.albumName, item.song.durationText, item.quality).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
