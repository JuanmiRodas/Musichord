package com.music.bitchord.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.AlbumAppearances
import com.music.bitchord.data.SongFamily
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.dna.DnaKeys
import com.music.bitchord.data.dna.DnaRoles
import com.music.bitchord.data.dna.SongDna
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * BitChord Audio: what the song's DNA page does when something on it is tapped —
 * set by the activity, which knows how to play and open pages.
 */
object SongDnaActions {
    @Volatile var play: ((Song) -> Unit)? = null
    @Volatile var openAlbum: ((browseId: String, title: String, cover: String?) -> Unit)? = null

    private val _openRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Counted up to have the player scroll up to its DNA page. */
    val openRequests: kotlinx.coroutines.flow.StateFlow<Int> = _openRequests

    fun openInPlayer() { _openRequests.value++ }
}

private val Ink = Color.White
private val Card = Color.White.copy(alpha = 0.08f)
private val Dim = Color.White.copy(alpha = 0.62f)

/**
 * BitChord Audio: the song's DNA page — under the player, and from ⋯ → Song info
 * anywhere else. Light text on a dark ground.
 */
@Composable
fun SongDnaPage(
    song: Song,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var force by remember(song.videoId) { mutableIntStateOf(0) }
    var loading by remember(song.videoId) { mutableStateOf(true) }
    val dna by produceState(SongDna.cached(song), song.videoId, force) {
        loading = true
        value = runCatching { SongDna.get(song, force = force > 0) }.getOrNull() ?: value
        loading = false
    }
    val yt by produceState<SongInfo?>(null, song.videoId) {
        value = runCatching { SongInfoRepository.load(song) }.getOrNull()
    }
    val albums by produceState(AlbumAppearances.known(song), song.videoId) {
        value = runCatching { AlbumAppearances.search(song) }.getOrDefault(value)
    }
    val performances by produceState(SongFamily.cachedPerformances(song), song.videoId) {
        value = runCatching { SongFamily.performances(song) }.getOrDefault(value ?: emptyList())
    }
    val others by produceState(SongFamily.cachedOthers(song), song.videoId) {
        value = runCatching { SongFamily.byOtherArtists(song) }.getOrDefault(value ?: emptyList())
    }
    val file by produceState<List<Pair<Int, String>>?>(null, song.videoId) {
        value = withContext(Dispatchers.IO) { runCatching { fileRows(context, song) }.getOrDefault(emptyList()) }
    }

    fun openArtist(name: String) {
        onClose?.invoke()
        ArtistPicker.goTo(Song(videoId = "", title = "", artist = name, thumbnailUrl = null))
    }

    fun playOther(title: String, artist: String, url: String?) {
        scope.launch {
            val s = SongFamily.findToPlay(SongFamily.Other(title, artist, null, null))
            if (s != null) {
                SongDnaActions.play?.invoke(s)
            } else if (url != null) {
                runCatching { uri.openUri(url) }
            }
        }
    }

    LazyColumn(modifier = modifier, state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("head") {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.song_dna_title), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (loading) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Dim, strokeWidth = 1.75.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.song_dna_looking), color = Dim, fontSize = 13.sp)
                    }
                }
            }
        }

        val d = dna
        // ── About ──
        if (d != null && d.facts.isNotEmpty()) {
            items(d.facts) { f ->
                SectionCard(stringResource(R.string.song_dna_about), badge = f.source) {
                    Expandable(f.text)
                    f.url?.let { u ->
                        Text(
                            stringResource(R.string.song_dna_read_more, f.source),
                            color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp).clickable { runCatching { uri.openUri(u) } },
                        )
                    }
                }
            }
        }

        // ── Credits ──
        if (d != null && d.credits.isNotEmpty()) {
            item("credits") {
                SectionCard(stringResource(R.string.song_dna_credits)) {
                    val groups = d.credits.groupBy { DnaRoles.group(it.role) }.toSortedMap()
                    groups.forEach { (g, list) ->
                        Text(
                            stringResource(groupLabel(g)), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                        list.groupBy { it.name }.forEach { (name, roles) ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openArtist(name) }.padding(vertical = 5.dp),
                            ) {
                                Text(name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    roles.map { prettyRole(it.role) }.distinct().joinToString(", "),
                                    color = Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Connections (SongDNA) ──
        val relations = d?.relations.orEmpty().filter { it.kind != SongDna.TRANSLATIONS && it.kind != SongDna.TRANSLATION_OF }
        if (relations.isNotEmpty()) {
            item("connections") {
                SectionCard(stringResource(R.string.song_dna_connections)) {
                    relations.groupBy { it.kind }.forEach { (kind, list) ->
                        Text(
                            relationLabel(kind), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        list.forEach { r ->
                            SongRow(
                                art = r.art,
                                title = r.title,
                                subtitle = listOfNotNull(r.artist.ifBlank { null }, r.year).joinToString(" · "),
                            ) { playOther(r.title, r.artist, r.url) }
                        }
                    }
                }
            }
        }

        // ── Other versions ──
        val perf = performances.orEmpty()
        val oth = others.orEmpty()
        if (perf.isNotEmpty() || oth.isNotEmpty()) {
            item("versions") {
                SectionCard(stringResource(R.string.song_dna_versions)) {
                    perf.forEach { s ->
                        SongRow(s.thumbnailUrl, s.title, listOfNotNull(s.artist, s.albumName, s.durationText).joinToString(" · ")) {
                            SongDnaActions.play?.invoke(s)
                        }
                    }
                    if (oth.isNotEmpty()) {
                        Text(
                            stringResource(R.string.song_dna_other_artists), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        oth.forEach { o ->
                            SongRow(null, o.artist, listOfNotNull(o.title, o.album).joinToString(" · ")) {
                                playOther(o.title, o.artist, null)
                            }
                        }
                    }
                }
            }
        }

        // ── Where it appears ──
        val withPage = albums.filter { it.browseId != null }
        val uses = d?.uses.orEmpty()
        if (withPage.isNotEmpty() || uses.isNotEmpty()) {
            item("appears") {
                SectionCard(stringResource(R.string.song_dna_appears_on)) {
                    if (withPage.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(withPage) { a ->
                                Tile(a.cover, a.name, a.source, 112) {
                                    onClose?.invoke()
                                    SongDnaActions.openAlbum?.invoke(a.browseId!!, a.name, a.cover)
                                }
                            }
                        }
                    }
                    if (uses.isNotEmpty()) {
                        Text(
                            stringResource(R.string.song_dna_anime), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                        uses.forEach { Text(it, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
            }
        }

        // ── Alternative covers ──
        if (d != null && d.covers.isNotEmpty()) {
            item("covers") {
                SectionCard(stringResource(R.string.song_dna_covers)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(d.covers) { c -> Tile(c.url, c.label, null, 132) { CoverPicker.open(song) } }
                    }
                    Text(
                        stringResource(R.string.song_dna_change_cover), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp).clickable { CoverPicker.open(song) },
                    )
                }
            }
        }

        // ── Details ──
        val details = buildList {
            val y = yt
            (d?.album ?: y?.album ?: song.albumName)?.let { add(R.string.song_dna_d_album to it) }
            (d?.releaseDate ?: y?.releaseDate ?: y?.releaseYear)?.let { add(R.string.song_dna_d_released to it) }
            d?.label?.let { add(R.string.song_dna_d_label to it) }
            d?.genres?.takeIf { it.isNotEmpty() }?.let { add(R.string.song_dna_d_genres to it.joinToString(", ")) }
            song.durationText?.let { add(R.string.song_dna_d_duration to it) }
            d?.bpm?.let { add(R.string.song_dna_d_bpm to "$it BPM") }
            d?.stats?.get("mood")?.let { add(R.string.song_dna_d_mood to it) }
            d?.stats?.get("theme")?.let { add(R.string.song_dna_d_theme to it) }
            d?.language?.let { add(R.string.song_dna_d_language to languageName(it)) }
            d?.isrc?.let { add(R.string.song_dna_d_isrc to it) }
            d?.stats?.get("recorded_at")?.let { add(R.string.song_dna_d_recorded to it) }
            d?.stats?.get("lastfm_listeners")?.let { add(R.string.song_dna_d_listeners to compactCount(it)) }
            d?.stats?.get("lastfm_plays")?.let { add(R.string.song_dna_d_plays to compactCount(it)) }
            d?.stats?.get("genius_views")?.let { add(R.string.song_dna_d_genius_views to compactCount(it)) }
            y?.views?.let { add(R.string.song_info_views to compactCount(it)) }
            y?.likes?.let { add(R.string.song_info_likes to it) }
            y?.comments?.let { add(R.string.song_info_comments to it) }
        }
        if (details.isNotEmpty()) {
            item("details") {
                SectionCard(stringResource(R.string.song_dna_details)) {
                    details.forEach { (label, value) -> InfoRow(stringResource(label), value) }
                }
            }
        }

        // ── Your copy ──
        val rows = file
        if (!rows.isNullOrEmpty()) {
            item("file") {
                SectionCard(stringResource(R.string.song_dna_file)) {
                    rows.forEach { (label, value) -> InfoRow(stringResource(label), value, stacked = true) }
                }
            }
        }

        // ── The video's description (YouTube) ──
        yt?.description?.trim()?.takeIf { it.length > 30 }?.let { text ->
            item("ytdesc") {
                SectionCard(stringResource(R.string.song_dna_youtube)) { Expandable(text, lines = 5) }
            }
        }

        // ── Sources, and the free keys ──
        item("sources") {
            SectionCard(stringResource(R.string.song_dna_sources)) {
                val links = d?.links.orEmpty()
                if (links.isNotEmpty()) {
                    links.forEach { l ->
                        Text(
                            l.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(l.url) } }.padding(vertical = 6.dp),
                        )
                    }
                } else if (!loading) {
                    Text(stringResource(R.string.song_dna_nothing), color = Dim, fontSize = 13.sp)
                }
                d?.failed?.takeIf { it.isNotEmpty() && !loading }?.let { f ->
                    Text(
                        stringResource(R.string.song_dna_partial, f.joinToString(", ")),
                        color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { force++ }, enabled = !loading) {
                        Text(stringResource(R.string.song_dna_refresh), color = Ink)
                    }
                }
                KeysEditor()
            }
        }
    }
}

/** The page in its own window, for ⋯ → Song info outside the player. */
@Composable
internal fun SongDnaDialog(song: Song, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 28.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF121214)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                SongDnaPage(
                    song = song,
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                    onClose = onDismiss,
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.15f)))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.done), color = Ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Pieces ─────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, badge: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (badge != null) Text(badge, color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Expandable(text: String, lines: Int = 7) {
    var open by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Text(
        text,
        color = Ink.copy(alpha = 0.9f),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        maxLines = if (open) Int.MAX_VALUE else lines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!open) overflows = it.hasVisualOverflow },
    )
    if (overflows || open) {
        Text(
            stringResource(if (open) R.string.song_dna_less else R.string.song_dna_more),
            color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp).clickable { open = !open },
        )
    }
}

/** Label on the left, value on the right, the value wrapping under its own column; [stacked]: one above the other. */
@Composable
private fun InfoRow(label: String, value: String, stacked: Boolean = false) {
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(label, color = Dim, fontSize = 12.sp)
            Text(value, color = Ink, fontSize = 14.sp)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Dim, fontSize = 14.sp, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun SongRow(art: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art != null) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Card),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tile(cover: String?, title: String, subtitle: String?, size: Int, onClick: () -> Unit) {
    Column(Modifier.width(size.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = cover, contentDescription = title, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Card),
        )
        Text(title, color = Ink, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (subtitle != null) Text(subtitle, color = Dim, fontSize = 11.sp, maxLines = 1)
    }
}

/** The free keys: your Discogs token (and, if wanted, your own Last.fm key). */
@Composable
private fun KeysEditor() {
    val discogs by DnaKeys.discogs.collectAsStateWithLifecycle()
    val lastFm by DnaKeys.lastFm.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var d by remember(discogs) { mutableStateOf(discogs) }
    var l by remember(lastFm) { mutableStateOf(lastFm) }
    Text(
        stringResource(if (discogs.isBlank()) R.string.song_dna_keys_add else R.string.song_dna_keys_edit),
        color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp).clickable { open = !open },
    )
    if (!open) return
    Text(stringResource(R.string.song_dna_keys_hint), color = Dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Ink, unfocusedBorderColor = Dim,
        focusedLabelColor = Ink, unfocusedLabelColor = Dim, cursorColor = Ink,
    )
    OutlinedTextField(
        value = d, onValueChange = { d = it }, singleLine = true, colors = colors,
        label = { Text(stringResource(R.string.song_dna_discogs_token)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = l, onValueChange = { l = it }, singleLine = true, colors = colors,
        label = { Text(stringResource(R.string.song_dna_lastfm_key)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    TextButton(onClick = {
        DnaKeys.setDiscogs(d)
        DnaKeys.setLastFm(l)
        open = false
    }) { Text(stringResource(R.string.song_dna_keys_save), color = Ink) }
}

// ── Words ──────────────────────────────────────────────────────────────────

private fun groupLabel(g: DnaRoles.Group): Int = when (g) {
    DnaRoles.Group.PERFORMER -> R.string.song_dna_g_performer
    DnaRoles.Group.WRITING -> R.string.song_dna_g_writing
    DnaRoles.Group.ARRANGEMENT -> R.string.song_dna_g_arrangement
    DnaRoles.Group.PRODUCTION -> R.string.song_dna_g_production
    DnaRoles.Group.ENGINEERING -> R.string.song_dna_g_engineering
    DnaRoles.Group.LABEL -> R.string.song_dna_d_label
    DnaRoles.Group.OTHER -> R.string.song_dna_g_other
}

/** "mix (assistant)" → "Mix (assistant)"; MusicBrainz's and Genius's lower-case roles capitalised. */
private fun prettyRole(r: String): String = r.trim().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun relationLabel(kind: String): String = when (kind) {
    SongDna.SAMPLES -> stringResource(R.string.song_dna_rel_samples)
    SongDna.SAMPLED_IN -> stringResource(R.string.song_dna_rel_sampled_in)
    SongDna.INTERPOLATES -> stringResource(R.string.song_dna_rel_interpolates)
    SongDna.INTERPOLATED_BY -> stringResource(R.string.song_dna_rel_interpolated_by)
    SongDna.COVER_OF -> stringResource(R.string.song_dna_rel_cover_of)
    SongDna.COVERED_BY -> stringResource(R.string.song_dna_rel_covered_by)
    SongDna.REMIX_OF -> stringResource(R.string.song_dna_rel_remix_of)
    SongDna.REMIXED_BY -> stringResource(R.string.song_dna_rel_remixed_by)
    SongDna.LIVE_VERSION_OF -> stringResource(R.string.song_dna_rel_live_version_of)
    SongDna.PERFORMED_LIVE_AS -> stringResource(R.string.song_dna_rel_performed_live_as)
    else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun languageName(code: String): String {
    val c = when (code.lowercase()) { "jpn" -> "ja"; "eng" -> "en"; "spa" -> "es"; "kor" -> "ko"; "fra" -> "fr"; "deu" -> "de"; else -> code }
    return runCatching { java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.getDefault()) }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != c }?.replaceFirstChar { it.uppercase() } ?: code
}

// ── Where your copy is ─────────────────────────────────────────────────────

private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

/** Where [song] lives: your pCloud file, the phone's download, or YouTube Music. */
private fun fileRows(context: Context, song: Song): List<Pair<Int, String>> {
    val rows = ArrayList<Pair<Int, String>>()
    val id = song.videoId
    if (WebDavConfig.isWebDavId(id)) {
        val path = decode(Uri.parse(id.removePrefix("webdav:")).encodedPath.orEmpty())
        rows += R.string.song_dna_f_pcloud to path.trimStart('/')
        path.substringAfterLast('.', "").takeIf { it.length in 2..5 }?.let { rows += R.string.song_dna_f_format to it.uppercase() }
    }
    val local = runCatching { com.music.bitchord.download.Downloads.localCopyOf(id) }.getOrNull()
        ?: song.localUri
    if (local != null) {
        val uri = Uri.parse(local)
        var name: String? = null
        var size: Long? = null
        var folder: String? = null
        if (uri.scheme == "content") {
            runCatching {
                val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "relative_path")
                context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = c.getLong(1).takeIf { it > 0 }
                        folder = runCatching { c.getString(2) }.getOrNull()
                    }
                }
            }
        } else {
            val f = java.io.File(uri.path ?: local)
            name = f.name
            folder = f.parent
            size = f.length().takeIf { it > 0 }
        }
        val where = listOfNotNull(folder?.trimEnd('/'), name).joinToString("/").ifBlank { decode(local) }
        rows += R.string.song_dna_f_phone to where
        size?.let { rows += R.string.song_dna_f_size to String.format(java.util.Locale.getDefault(), "%.1f MB", it / 1_048_576.0) }
        if (rows.none { it.first == R.string.song_dna_f_format }) {
            name?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.let { rows += R.string.song_dna_f_format to it.uppercase() }
        }
    }
    if (rows.isEmpty()) rows += R.string.song_dna_f_where to context.getString(R.string.song_dna_f_stream)
    return rows
}
