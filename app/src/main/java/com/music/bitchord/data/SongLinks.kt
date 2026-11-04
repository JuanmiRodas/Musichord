package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.ArtistLinks
import com.music.bitchord.data.sources.NotSame
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.PreferredSource
import com.music.bitchord.data.sources.SourceMap

/**
 * BitChord Audio: undoing a match that was wrong — [song] played as [other]
 * (another artist's song of the same name on the same compilation, say).
 * From now on each plays as itself: the pair is never matched again, the
 * artist link that made it is forgotten, the remembered match and any shared
 * heart are dropped.
 */
object SongLinks {
    fun unlink(song: Song, other: Song) {
        NotSame.add(song.videoId, other.videoId)
        if (!OwnFileMatcher.sameArtist(song.artist, other.artist)) ArtistLinks.forget(song.artist, other.artist)
        SourceMap.forget(song.videoId)
        SourceMap.forget(other.videoId)
        if ((PreferredSource.get(song) as? PreferredSource.File)?.id == other.videoId) PreferredSource.clear(song)
        LocalLikes.unlink(song.videoId, other.videoId)
        TrackLog.d("BitChord", "unlinked \"${song.title}\" (${song.artist}) from \"${other.title}\" (${other.artist})")
    }
}
