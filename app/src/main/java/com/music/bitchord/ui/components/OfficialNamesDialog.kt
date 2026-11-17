package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.names.DisplayNames
import com.music.bitchord.data.names.OfficialNames
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * BitChord Audio: a song's official Latin names, by hand — title, artist and
 * album. Opened from the song menu; what you write wins over every source.
 */
object OfficialNamesEditor {
    val song = MutableStateFlow<Song?>(null)

    fun open(song: Song) {
        this.song.value = song
    }
}

@Composable
fun OfficialNamesDialogHost() {
    val song by OfficialNamesEditor.song.collectAsStateWithLifecycle()
    val s = song ?: return
    OfficialNamesDialog(s) { OfficialNamesEditor.song.value = null }
}

@Composable
private fun OfficialNamesDialog(song: Song, onDismiss: () -> Unit) {
    val artist = OfficialNames.primaryArtist(song.artist)
    val album = song.albumName?.takeIf { it.isNotBlank() }
    fun current(kind: OfficialNames.Kind, original: String, by: String?) =
        OfficialNames.get(kind, original, by)?.latin.orEmpty()
    var title by remember { mutableStateOf(current(OfficialNames.Kind.TITLE, song.title, artist)) }
    var artistLatin by remember { mutableStateOf(current(OfficialNames.Kind.ARTIST, artist, null)) }
    var albumLatin by remember { mutableStateOf(album?.let { current(OfficialNames.Kind.ALBUM, it, artist) }.orEmpty()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp)).padding(20.dp)) {
            Text(
                stringResource(R.string.official_names_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                stringResource(R.string.official_names_help),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
            if (DisplayNames.needsLatin(song.title)) NameField(song.title, title) { title = it }
            if (DisplayNames.needsLatin(artist)) NameField(artist, artistLatin) { artistLatin = it }
            if (album != null && DisplayNames.needsLatin(album)) NameField(album, albumLatin) { albumLatin = it }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Box(Modifier.weight(1f))
                Text(
                    stringResource(R.string.cancel),
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                )
                Text(
                    stringResource(R.string.save),
                    modifier = Modifier.clickable {
                        if (DisplayNames.needsLatin(song.title)) OfficialNames.setManual(OfficialNames.Kind.TITLE, song.title, artist, title)
                        if (DisplayNames.needsLatin(artist)) OfficialNames.setManual(OfficialNames.Kind.ARTIST, artist, null, artistLatin)
                        if (album != null && DisplayNames.needsLatin(album)) OfficialNames.setManual(OfficialNames.Kind.ALBUM, album, artist, albumLatin)
                        onDismiss()
                    }.padding(8.dp),
                    color = Color(0xFF64A8FF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun NameField(original: String, value: String, onChange: (String) -> Unit) {
    Text(original, modifier = Modifier.padding(top = 14.dp), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
    Box(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
