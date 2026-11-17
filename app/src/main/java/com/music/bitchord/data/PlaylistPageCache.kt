package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.Song
import java.io.File

/**
 * BitChord Audio: every playlist and album page as last seen, the way Liked
 * Music already was ([LikedMusicCache]). The saved list goes up the moment the
 * page opens — and is all there is with the phone offline — and the listing
 * fetched behind it replaces it when it arrives.
 */
object PlaylistPageCache {

    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "page_cache").apply { mkdirs() }
    }

    private fun file(browseId: String): File? =
        dir?.let { File(it, "p_" + browseId.hashCode().toUInt().toString(16) + "_" + browseId.filter(Char::isLetterOrDigit).take(40) + ".json") }

    fun load(browseId: String): List<Song>? =
        file(browseId)?.let { SongListStore.load(it) }?.songs?.takeIf { it.isNotEmpty() }

    fun save(browseId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        val f = file(browseId) ?: return
        runCatching { SongListStore.save(f, songs) }
    }
}
