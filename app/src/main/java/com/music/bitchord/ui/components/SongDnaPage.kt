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
import com.music.bitchord.data.dna.DnaPeople
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

    // BitChord Audio: a person's page over the song's (a stack: from one person to who they worked with).
    var people by remember(song.videoId) { mutableStateOf(listOf<Pair<SongDna.Credit, List<String>>>()) }
    androidx.activity.compose.BackHandler(enabled = people.isNotEmpty()) { people = people.dropLast(1) }
    val dnaChanges by SongDna.changes.collectAsStateWithLifecycle()
    val peopleChanges by DnaPeople.changes.collectAsStateWithLifecycle()
    // Who is on how many of your songs, and with how many others — one pass over what is known.
    val index by produceState<Map<String, Pair<Int, Int>>>(emptyMap(), dnaChanges) {
        value = withContext(Dispatchers.Default) { runCatching { libraryIndex() }.getOrDefault(emptyMap()) }
    }
    // Your songs by their DNA key, to play one from a person's page.
    val pool by produceState<Map<String, Song>>(emptyMap(), song.videoId) {
        value = withContext(Dispatchers.IO) { runCatching { libraryPool() }.getOrDefault(emptyMap()) }
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

    // BitChord Audio: nothing said about the song by any source — looked up on the web
    // (Genius's note, the Wikipedia articles that name it), with a spinner meanwhile.
    var researched by remember(song.videoId) { mutableStateOf<SongDna.Dna?>(null) }
    val needsResearch = !loading && dna != null && dna!!.facts.isEmpty() && "web research" !in dna!!.sources
    LaunchedEffect(song.videoId, needsResearch) {
        if (needsResearch) researched = runCatching { SongDna.research(song) }.getOrNull()
    }

    val top = people.lastOrNull()
    if (top != null) {
        PersonPage(
            credit = top.first,
            rolesHere = top.second,
            backLabel = if (people.size > 1) people[people.size - 2].first.name else song.title,
            peopleChanges = peopleChanges,
            index = index,
            modifier = modifier,
            contentPadding = contentPadding,
            onBack = { people = people.dropLast(1) },
            onPerson = { c -> people = people + (c to emptyList()) },
            onPlayLibrary = { d ->
                val s = pool[d.key]
                if (s != null) SongDnaActions.play?.invoke(s) else playOther(d.title, d.artist, null)
            },
            onPlayOther = { t, a, u -> playOther(t, a, u) },
            onClose = onClose,
        )
        return
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

        val d0 = dna
        val d = researched?.takeIf { it.key == d0?.key } ?: d0
        if (needsResearch && researched == null) {
            item("researching") {
                SectionCard(stringResource(R.string.song_dna_about), badge = null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Dim, strokeWidth = 1.75.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.song_dna_researching), color = Dim, fontSize = 13.sp)
                    }
                }
            }
        }
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

        // ── Contributors: the people, with their pictures, as SongDNA shows them ──
        val contributors = d?.credits.orEmpty()
            .filter { DnaRoles.group(it.role) != DnaRoles.Group.LABEL }
            .groupBy { SongDna.personKey(it.name) }
            .values.map { list -> list.sortedBy { DnaRoles.group(it.role).ordinal } }
            .sortedBy { DnaRoles.group(it.first().role).ordinal }
        if (contributors.isNotEmpty()) {
            item("contributors") {
                SectionCard(stringResource(R.string.song_dna_contributors)) {
                    contributors.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            pair.forEach { list ->
                                val c = mergedCredit(list)
                                val counts = index[SongDna.personKey(c.name)]
                                ContributorCard(
                                    credit = c,
                                    image = c.image ?: DnaPeople.cached(c.name)?.image.also { peopleChanges },
                                    roles = list.map { it.role }.map(::prettyRole).distinct(),
                                    songs = counts?.first ?: 0,
                                    collaborators = counts?.second ?: 0,
                                    modifier = Modifier.weight(1f),
                                ) { people = people + (c to list.map { it.role }.distinct()) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
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
            d?.catalog?.let { add(R.string.song_dna_d_catalog to it) }
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

// ── People ─────────────────────────────────────────────────────────────────

/** Every id any of a person's credits carries, on one credit. */
private fun mergedCredit(list: List<SongDna.Credit>): SongDna.Credit = list.reduce { a, b ->
    a.copy(
        mbid = a.mbid ?: b.mbid, geniusId = a.geniusId ?: b.geniusId, discogsId = a.discogsId ?: b.discogsId,
        ytId = a.ytId ?: b.ytId, image = a.image ?: b.image,
    )
}

/** person → (songs of yours they are on, people they worked with on them). */
private fun libraryIndex(): Map<String, Pair<Int, Int>> {
    val songs = HashMap<String, Int>()
    val with = HashMap<String, HashSet<String>>()
    for (d in SongDna.all()) {
        val keys = d.credits.filter { DnaRoles.group(it.role) != DnaRoles.Group.LABEL }.map { SongDna.personKey(it.name) }.toSet()
        for (k in keys) {
            songs[k] = (songs[k] ?: 0) + 1
            with.getOrPut(k) { HashSet() }.addAll(keys - k)
        }
    }
    return songs.mapValues { (k, n) -> n to (with[k]?.size ?: 0) }
}

private fun libraryPool(): Map<String, Song> {
    val all = com.music.bitchord.data.LikedMusicCache.likedSongs.value +
        com.music.bitchord.data.LocalLikes.liked.value.values +
        runCatching { com.music.bitchord.data.webdav.WebDavRepository.library() }.getOrDefault(emptyList()) +
        com.music.bitchord.data.OwnLibrary.uploads.value
    val out = HashMap<String, Song>()
    for (s in all) out.putIfAbsent(SongDna.keyOf(s), s)
    return out
}

@Composable
private fun Avatar(image: String?, name: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF2E3138)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Ink, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold,
        )
        if (image != null) {
            AsyncImage(
                model = image, contentDescription = name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

@Composable
private fun ContributorCard(
    credit: SongDna.Credit,
    image: String?,
    roles: List<String>,
    songs: Int,
    collaborators: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(image, credit.name, 92)
        Text(
            credit.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        val roleLine = roles.first() + if (roles.size > 1) " " + stringResource(R.string.song_dna_more_roles, roles.size - 1) else ""
        Text(
            roleLine, color = Ink.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (songs > 0) {
            Text(
                listOfNotNull(
                    stringResource(R.string.song_dna_in_library_count, songs),
                    stringResource(R.string.song_dna_collab_count, collaborators).takeIf { collaborators > 0 },
                ).joinToString(" · "),
                color = Dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** A person's page: who they are, what they did here, in your library, and everything else. */
@Composable
private fun PersonPage(
    credit: SongDna.Credit,
    rolesHere: List<String>,
    backLabel: String,
    peopleChanges: Int,
    index: Map<String, Pair<Int, Int>>,
    modifier: Modifier,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPerson: (SongDna.Credit) -> Unit,
    onPlayLibrary: (SongDna.Dna) -> Unit,
    onPlayOther: (String, String, String?) -> Unit,
    onClose: (() -> Unit)?,
) {
    val uri = LocalUriHandler.current
    var loading by remember(credit.name) { mutableStateOf(DnaPeople.cached(credit.name) == null) }
    val person by produceState(DnaPeople.cached(credit.name), credit.name) {
        loading = true
        value = runCatching { DnaPeople.get(credit) }.getOrNull() ?: value
        loading = false
    }
    val library by produceState<List<DnaPeople.LibrarySong>>(emptyList(), credit.name, index) {
        value = withContext(Dispatchers.Default) { DnaPeople.inLibrary(credit.name) }
    }
    val collaborators by produceState<List<Pair<SongDna.Credit, Int>>>(emptyList(), credit.name, index) {
        value = withContext(Dispatchers.Default) { DnaPeople.collaborators(credit.name) }
    }
    var allWorks by remember(credit.name) { mutableStateOf(false) }
    val state = rememberLazyListState()
    val p = person
    LazyColumn(modifier = modifier, state = state, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("back") {
            Text(
                "‹  " + stringResource(R.string.song_dna_back_to, backLabel),
                color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }
        item("who") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(credit.image ?: p?.image, credit.name, 140)
                Text(
                    credit.name, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                )
                if (rolesHere.isNotEmpty()) {
                    Text(
                        stringResource(R.string.song_dna_p_on_song) + ": " + rolesHere.map(::prettyRole).distinct().joinToString(", "),
                        color = Ink.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                p?.facts?.forEach { Text(it, color = Dim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                index[SongDna.personKey(credit.name)]?.let { (n, w) ->
                    Text(
                        listOfNotNull(
                            stringResource(R.string.song_dna_in_library_count, n),
                            stringResource(R.string.song_dna_collab_count, w).takeIf { w > 0 },
                        ).joinToString(" · "),
                        color = Dim, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = {
                    val id = p?.ytBrowseId ?: credit.ytId
                    onClose?.invoke()
                    val open = ArtistPicker.open
                    if (id != null && id.startsWith("UC") && open != null) open(id)
                    else ArtistPicker.goTo(Song(videoId = "", title = "", artist = credit.name, thumbnailUrl = null))
                }) { Text(stringResource(R.string.song_dna_artist_profile), color = Ink, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (library.isNotEmpty()) {
            item("lib") {
                SectionCard(stringResource(R.string.song_dna_p_library), badge = library.size.toString()) {
                    library.take(40).forEach { l ->
                        SongRow(
                            l.dna.covers.firstOrNull()?.url, l.dna.title,
                            listOf(l.dna.artist, l.roles.map(::prettyRole).distinct().joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                        ) { onPlayLibrary(l.dna) }
                    }
                }
            }
        }
        if (collaborators.isNotEmpty()) {
            item("collabs") {
                SectionCard(stringResource(R.string.song_dna_p_collaborators)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(collaborators) { (c, n) ->
                            Column(
                                Modifier.width(84.dp).clip(RoundedCornerShape(10.dp)).clickable { onPerson(c) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(c.image ?: DnaPeople.cached(c.name)?.image.also { peopleChanges }, c.name, 64)
                                Text(
                                    c.name, color = Ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                                )
                                Text(stringResource(R.string.song_dna_in_library_count, n), color = Dim, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        item("works") {
            SectionCard(stringResource(R.string.song_dna_p_works), badge = p?.works?.size?.takeIf { it > 0 }?.toString()) {
                val works = p?.works.orEmpty()
                when {
                    works.isEmpty() && loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Dim, strokeWidth = 1.75.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.song_dna_p_looking), color = Dim, fontSize = 13.sp)
                    }
                    works.isEmpty() -> Text(stringResource(R.string.song_dna_p_none), color = Dim, fontSize = 13.sp)
                    else -> {
                        (if (allWorks) works else works.take(25)).forEach { w ->
                            SongRow(
                                w.art, w.title,
                                listOfNotNull(
                                    w.artist.ifBlank { null }, w.year,
                                    w.role.split(", ").filter { it.isNotBlank() }.map(::prettyRole).distinct().joinToString(", ").ifBlank { null },
                                ).joinToString(" · "),
                            ) { onPlayOther(w.title, w.artist.ifBlank { credit.name }, w.url) }
                        }
                        if (!allWorks && works.size > 25) {
                            Text(
                                stringResource(R.string.song_dna_show_all, works.size), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp).clickable { allWorks = true },
                            )
                        }
                    }
                }
            }
        }
        p?.about?.let { text ->
            item("about") { SectionCard(stringResource(R.string.song_dna_p_about), badge = p.aboutSource) { Expandable(text) } }
        }
        p?.links?.takeIf { it.isNotEmpty() }?.let { links ->
            item("links") {
                SectionCard(stringResource(R.string.song_dna_sources)) {
                    links.forEach { l ->
                        Text(
                            l.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(l.url) } }.padding(vertical = 6.dp),
                        )
                    }
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

private val ROLES_ES = mapOf(
    "performer" to "Intérprete", "main artist" to "Artista principal", "featured" to "Artista invitado/a",
    "composer" to "Compositor/a", "lyricist" to "Letrista", "writer" to "Autor/a", "songwriter" to "Autor/a",
    "arranger" to "Arreglista", "producer" to "Productor/a", "co-producer" to "Coproductor/a", "executive producer" to "Productor/a ejecutivo/a",
    "engineer" to "Ingeniero/a", "mix" to "Mezcla", "mixed by" to "Mezcla", "mastering" to "Masterización", "recording" to "Grabación",
    "vocals" to "Voz", "lead vocals" to "Voz principal", "background vocals" to "Coros", "vocal" to "Voz", "instrument" to "Instrumento",
    "guitar" to "Guitarra", "bass guitar" to "Bajo", "drums" to "Batería", "piano" to "Piano", "keyboard" to "Teclados", "strings" to "Cuerdas",
    "programming" to "Programación", "orchestrator" to "Orquestación", "conductor" to "Director/a", "track" to "En una canción",
)

/** "mix (assistant)" → "Mix (assistant)"; common roles in Spanish when the phone is. */
private fun prettyRole(r: String): String {
    val t = r.trim().replace('_', ' ')
    if (java.util.Locale.getDefault().language == "es") ROLES_ES[t.lowercase()]?.let { return it }
    return t.replaceFirstChar { it.uppercase() }
}

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
