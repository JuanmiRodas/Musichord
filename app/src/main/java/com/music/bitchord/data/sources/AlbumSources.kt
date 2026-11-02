package com.music.bitchord.data.sources

import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.jiosaavn.JioSaavnService
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlin.math.abs

/**
 * BitChord Audio: "the rest of the album from here too". When a copy of a
 * song is picked from a source, the other songs of its album in Liked Music
 * and the library are matched in that same source — on the album's own track
 * list where the source has one (your pCloud folder, JioSaavn's album), else
 * by searching the source song by song — and each match is remembered as that
 * song's choice, as if it had been picked by hand.
 */
object AlbumSources {

    private const val TAG = "BitChord"

    /** The YouTube library's songs, as last loaded (set by the view model). */
    @Volatile
    var librarySongs: List<Song> = emptyList()

    /** The other songs of [song]'s album that you have in Liked Music or the library. */
    fun albumMates(song: Song, picked: Song? = null): List<Song> {
        val album = song.albumName?.takeIf { it.isNotBlank() } ?: picked?.albumName?.takeIf { it.isNotBlank() } ?: return emptyList()
        val all = LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty() +
            LocalLikes.liked.value.values + librarySongs
        val ownKey = PreferredSource.keyOf(song.title, song.artist)
        return all.filter { s ->
            !s.albumName.isNullOrBlank() && OwnFileMatcher.sameAlbum(album, s.albumName) &&
                (OwnFileMatcher.sameArtist(song.artist, s.artist) || ArtistLinks.linked(s.artist, song.artist)) &&
                PreferredSource.keyOf(s.title, s.artist) != ownKey
        }.distinctBy { PreferredSource.keyOf(it.title, it.artist) ?: it.videoId }
    }

    /** Matches [mates] in [picked]'s source and remembers each match. Returns how many were matched. */
    suspend fun apply(song: Song, picked: Song, mates: List<Song> = albumMates(song, picked)): Int {
        if (mates.isEmpty()) return 0
        val candidates: List<Song>? = when {
            WebDavConfig.isWebDavId(picked.videoId) -> {
                // Your files of that album: the same album tag, or the same folder.
                val folder = picked.videoId.substringBeforeLast('/')
                WebDavRepository.library().filter { f ->
                    f.videoId.substringBeforeLast('/') == folder || OwnFileMatcher.sameAlbum(picked.albumName, f.albumName)
                }
            }
            else -> SourceRegistry.parseTrackKey(picked.videoId)?.let { (configId, _) ->
                if (SourceRegistry.config(configId)?.kind == SourceKind.JIOSAAVN) saavnAlbum(configId, picked) else null
            }
        }
        var matched = 0
        for (mate in mates) {
            val pool = candidates ?: searchSource(picked, mate) ?: continue
            val best = best(mate, pool) ?: continue
            PreferredSource.chooseSong(mate, best)
            LocalLikes.link(best, mate)
            matched++
        }
        TrackLog.d(TAG, "album sources: ${picked.albumName}: $matched of ${mates.size} songs matched in the same source")
        return matched
    }

    /** JioSaavn's album of [picked], as songs of that source. */
    private suspend fun saavnAlbum(configId: String, picked: Song): List<Song>? {
        val album = picked.albumName ?: return null
        val hits = JioSaavnService.searchAlbums("$album ${picked.artist.substringBefore(',')}")
        val hit = hits.firstOrNull { OwnFileMatcher.sameAlbum(album, it.title) && (it.artists.isBlank() || OwnFileMatcher.sameArtist(picked.artist, it.artists)) }
            ?: hits.firstOrNull { OwnFileMatcher.sameAlbum(album, it.title) }
            ?: return null
        return JioSaavnService.albumSongs(hit.id).map { raw ->
            Song(
                videoId = SourceRegistry.trackKey(configId, raw.id),
                title = raw.title,
                artist = raw.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name }.ifBlank { picked.artist },
                thumbnailUrl = raw.image.replace(Regex("150x150|50x50"), "500x500").replace(Regex("^http://"), "https://"),
                albumName = raw.moreInfo.album.ifBlank { album },
                durationText = raw.moreInfo.duration.toIntOrNull()?.let { "%d:%02d".format(it / 60, it % 60) },
            )
        }.takeIf { it.isNotEmpty() }
    }

    /** [mate] searched for in [picked]'s source, kept to the same album. */
    private suspend fun searchSource(picked: Song, mate: Song): List<Song>? {
        val (configId, _) = SourceRegistry.parseTrackKey(picked.videoId) ?: return null
        val source = SourceRegistry.instance(configId) ?: return null
        return runCatching { source.search("${mate.title} ${mate.artist}", 10, false, null) }.getOrNull()
            ?.filter { OwnFileMatcher.sameAlbum(picked.albumName, it.albumName) || it.albumName.isNullOrBlank() }
    }

    /**
     * The copy among [pool] that is [song]: a title spelling in common (romaji
     * or Japanese), a close spelling or most words shared — or, on the same
     * album, the same length to the second. Within 6 seconds always.
     */
    fun best(song: Song, pool: List<Song>): Song? {
        val sec = seconds(song.durationText)
        val keys = OwnFileMatcher.titleKeys(song.title) + runCatching { OwnFileMatcher.soundKeys(song.title) }.getOrDefault(emptySet())
        return pool.mapNotNull { c ->
            val cSec = seconds(c.durationText)
            val drift = if (sec == null || cSec == null) 3 else abs(sec - cSec)
            if (drift > 6) return@mapNotNull null
            val cKeys = OwnFileMatcher.titleKeys(c.title) + runCatching { OwnFileMatcher.soundKeys(c.title) }.getOrDefault(emptySet())
            val score = when {
                cKeys.any { it in keys } -> 3
                OwnFileMatcher.nearSpelling(song.title, c.title) -> 2
                wordOverlap(song.title, c.title) >= 0.5 -> 1
                sec != null && cSec != null && drift <= 1 -> 1
                else -> 0
            }
            if (score == 0) null else Triple(c, score, drift)
        }.sortedWith(compareByDescending<Triple<Song, Int, Int>> { it.second }.thenBy { it.third }).firstOrNull()?.first
    }

    private fun wordOverlap(a: String, b: String): Double {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
    }

    private fun seconds(text: String?): Int? = text?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
}
