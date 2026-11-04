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
    /** [a] and [b] (ids) are two songs: never matched, nothing remembered between them. */
    fun unlinkIds(a: String, b: String) {
        NotSame.add(a, b)
        SourceMap.forget(a)
        SourceMap.forget(b)
        if (SourceMap.saavnKeyOf(a) == b) SourceMap.forgetSaavn(a)
        if (SourceMap.saavnKeyOf(b) == a) SourceMap.forgetSaavn(b)
        LocalLikes.unlink(a, b)
        TrackLog.d("BitChord", "unlinked $a from $b")
    }

    fun unlink(song: Song, other: Song) {
        NotSame.add(song.videoId, other.videoId)
        if (!OwnFileMatcher.sameArtist(song.artist, other.artist)) ArtistLinks.forget(song.artist, other.artist)
        SourceMap.forget(song.videoId)
        SourceMap.forget(other.videoId)
        if ((PreferredSource.get(song) as? PreferredSource.File)?.id == other.videoId) PreferredSource.clear(song)
        if ((PreferredSource.get(song) as? PreferredSource.Chosen)?.song?.videoId == other.videoId) PreferredSource.clear(song)
        if (SourceMap.saavnKeyOf(song.videoId) == other.videoId) SourceMap.forgetSaavn(song.videoId)
        LocalLikes.unlink(song.videoId, other.videoId)
        TrackLog.d("BitChord", "unlinked \"${song.title}\" (${song.artist}) from \"${other.title}\" (${other.artist})")
    }

    /**
     * What [song] is tied to, as (other id, what to show): the pCloud file or
     * JioSaavn copy it would play as, a copy picked for it, and — when [song]
     * is itself a stand-in (the player's song) — the song it replaced.
     */
    fun linksOf(song: Song): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        LocalLikes.youTubeOf(song.videoId)?.takeIf { it != song.videoId }?.let { orig ->
            val o = LocalLikes.songFor(orig) ?: LikedMusicCache.likedSongs.value.firstOrNull { it.videoId == orig }
            out[orig] = o?.let { "Replaces ${it.title} · ${it.artist}" } ?: "Replaces another song"
        }
        val sec = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.fold(0) { a, p -> a * 60 + p }?.takeIf { it > 0 }
        (runCatching { OwnFileMatcher.decide(song.title, song.artist, sec, song.albumName, song.videoId) }.getOrNull() as? OwnFileMatcher.Play)
            ?.file?.takeIf { it.videoId != song.videoId }
            ?.let { f -> out.putIfAbsent(f.videoId, "Plays as ${f.title} · ${f.artist} · ${f.albumName.orEmpty()} (pCloud)") }
        SourceMap.saavnKeyOf(song.videoId)?.takeIf { SourceMap.saavnPlayable() }
            ?.let { k -> out.putIfAbsent(k, "Plays as JioSaavn's copy") }
        (PreferredSource.get(song) as? PreferredSource.Chosen)?.song?.takeIf { it.videoId != song.videoId }
            ?.let { c -> out.putIfAbsent(c.videoId, "Plays as ${c.title} · ${c.artist} (picked)") }
        return out.toList()
    }

    /** The menu's "not the same song": [song] and [otherId] each play as themselves from now on. */
    fun unlinkFrom(song: Song, otherId: String) {
        val other = LocalLikes.songFor(otherId)
        if (other != null) unlink(song, other) else unlinkIds(song.videoId, otherId)
        unlinkIds(song.videoId, otherId)
        // The song it replaced keeps its own copy choices clean too.
        LocalLikes.songFor(otherId)?.let { o ->
            if ((PreferredSource.get(o) as? PreferredSource.File)?.id == song.videoId) PreferredSource.clear(o)
        }
    }
}
