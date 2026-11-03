package com.music.bitchord.ui.components

import com.music.bitchord.data.LikedCleaner
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ArtistLinks
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BitChord Audio: after you pick a copy for a liked song, the other entries of
 * Liked Music that are the same song — its video upload, another upload, the
 * album version liked separately — are offered for merging: kept as one (the
 * song you are on), the others un-liked. Not versions of their own: a live
 * take, a remix, an acoustic version stay.
 */
object MergeProposal {
    data class Proposal(val keep: Song, val others: List<Song>, val copy: Song)

    private val _state = MutableStateFlow<Proposal?>(null)
    val state: StateFlow<Proposal?> = _state.asStateFlow()

    fun dismiss() { _state.value = null }

    /** The copy picked goes with the entry kept. */
    fun carryChoice(p: Proposal) {
        val c = p.copy
        if (com.music.bitchord.data.webdav.WebDavConfig.isWebDavId(c.videoId)) {
            com.music.bitchord.data.sources.PreferredSource.chooseFile(p.keep, c)
        } else {
            com.music.bitchord.data.sources.PreferredSource.chooseSong(p.keep, c)
        }
    }

    fun check(song: Song, copy: Song) {
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val items = LikedCleaner.items()
                if (items.none { it.id == song.videoId }) return@launch // not a liked song
                val keys = LikedCleaner.keysOf(song.title, song.artist) + LikedCleaner.keysOf(copy.title, copy.artist)
                val version = LikedCleaner.versionOf(song.title)
                fun sameArtist(a: String) =
                    OwnFileMatcher.sameArtist(song.artist, a) || OwnFileMatcher.sameArtist(copy.artist, a) ||
                        ArtistLinks.linked(song.artist, a) || ArtistLinks.linked(a, song.artist)
                val others = items.filter { it.id != song.videoId && it.id != copy.videoId }
                    .filter { LikedCleaner.keysOf(it.song.title, it.song.artist).any { k -> k in keys } }
                    .filter { sameArtist(it.song.artist) }
                    .filter { LikedCleaner.versionOf(it.song.title) == version }
                    .map { it.song }
                if (others.isEmpty()) return@launch
                // The entry kept: a song row over a video upload, one with its
                // album over one without; the copy you picked goes with it.
                val all = listOf(song) + others
                val keep = all.sortedWith(
                    compareBy<Song> { if (it.isVideo) 1 else 0 }
                        .thenBy { if (it.albumName.isNullOrBlank()) 1 else 0 }
                        .thenBy { if (it.videoId == song.videoId) 0 else 1 },
                ).first()
                _state.value = Proposal(keep, all.filter { it.videoId != keep.videoId }, copy)
            }
        }
    }
}
