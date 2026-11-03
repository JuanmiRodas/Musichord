package com.music.bitchord.data.webdav

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.LocalGroups

/**
 * BitChord Audio: a file of yours opens its album as a page like a YouTube
 * Music album's — the album as your pCloud library's tags group it
 * ([LocalGroups]), cover, artist, the tracks in order, play and shuffle.
 */
object PcloudAlbums {

    /** The album page of pCloud file [song], or null when its tags name no album. */
    fun pageIdOf(song: Song): String? {
        if (!WebDavConfig.isWebDavId(song.videoId)) return null
        // The library's own tag first: the page groups by it exactly.
        val album = WebDavRepository.library().firstOrNull { it.videoId == song.videoId }?.albumName?.takeIf { it.isNotBlank() }
            ?: song.albumName?.takeIf { it.isNotBlank() }
            ?: return null
        return LocalGroups.albumPageId(WebDavConfig.BROWSE_ID, album)
    }
}
