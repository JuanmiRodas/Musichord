package com.music.bitchord.ui.player

import android.content.Context
import android.widget.Toast
import com.music.bitchord.data.covers.CoverOverrides
import com.music.bitchord.data.covers.CoverSources
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: where the player's quick buttons reach the app's own work — the same
 * functions the ⋯ menu calls, set by the activity that owns them.
 */
object QuickActionBridge {
    @Volatile var handler: ((id: String, song: Song) -> Unit)? = null

    /** The player's buttons editor is up (opened from a long press on ⋯). */
    val editorOpen = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The album's covers found for a song, the best few: what a tap on the cover button goes through. */
    private val covers = ConcurrentHashMap<String, List<String>>()

    /**
     * [song] with the next of its album's covers — the top of what the cover search
     * finds for its album (your files' own cover first), shown on the song and the copy
     * it plays from. Only shown: the files are written from the cover picker (a long press).
     */
    fun cycleCover(context: Context, song: Song) {
        val app = context.applicationContext
        scope.launch {
            val list = covers[song.videoId] ?: runCatching { CoverSources.find(app, song, null) }.getOrDefault(emptyList())
                .map { it.url }.distinct().take(6)
                .also { if (it.isNotEmpty()) covers[song.videoId] = it }
            if (list.size < 2 && list.firstOrNull() == CoverOverrides.get(song)) {
                withContext(Dispatchers.Main) { Toast.makeText(app, com.music.bitchord.R.string.cover_no_alternatives, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            if (list.isEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(app, com.music.bitchord.R.string.cover_no_alternatives, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val now = list.indexOf(CoverOverrides.get(song))
            val next = (now + 1) % list.size
            val copies = listOfNotNull(runCatching { CoverOverrides.pickedCopyOf(song) }.getOrNull()) +
                runCatching { CoverSources.filesOf(song).take(1) }.getOrDefault(emptyList())
            CoverOverrides.set((listOf(song) + copies).distinctBy { it.videoId }, list[next])
            withContext(Dispatchers.Main) {
                Toast.makeText(app, app.getString(com.music.bitchord.R.string.cover_cycled, next + 1, list.size), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
