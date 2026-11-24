package com.music.bitchord.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArt
import com.music.bitchord.data.canvas.ArtistImages

/**
 * BitChord Audio: everything found of an artist — Apple Music's photo and logo, then
 * fanart.tv (with your key), TheAudioDB and Wikimedia Commons — opened from the artist
 * page's photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistGallerySheet(artist: String, apple: AppleArtistArt?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pictures by remember(artist) { mutableStateOf<List<ArtistImages.Picture>?>(null) }
    var open by remember { mutableStateOf<ArtistImages.Picture?>(null) }
    LaunchedEffect(artist, apple) {
        val extra = listOfNotNull(
            apple?.takeIf { it.keyed }?.heroUrl?.let { ArtistImages.Picture(it, ArtistImages.Kind.PHOTO, "Apple Music") },
            apple?.takeIf { it.keyed }?.logoUrl?.let { ArtistImages.Picture(it, ArtistImages.Kind.LOGO, "Apple Music") },
        )
        pictures = ArtistImages.gallery(artist, extra)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.artist_gallery), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = pictures
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.artist_gallery_empty), modifier = Modifier.padding(vertical = 24.dp))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.url }) { p ->
                        Column(Modifier.clickable { open = p }) {
                            AsyncImage(
                                model = p.url,
                                contentDescription = null,
                                contentScale = if (p.kind == ArtistImages.Kind.LOGO) ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(if (p.kind == ArtistImages.Kind.PHOTO) 1f else 16f / 9f)
                                    .clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)),
                            )
                            Text(
                                "${p.source} · ${p.kind.name.lowercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    open?.let { p ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { open = null },
            text = {
                AsyncImage(model = p.url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(stringResource(R.string.artist_gallery_open)) }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text(p.source) } },
        )
    }
}
