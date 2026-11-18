package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.names.NameSources
import com.music.bitchord.data.names.NameSources.objects
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: a song's family, for "Appears on…":
 *  - the albums this very recording is on ([AlbumAppearances], with their covers);
 *  - its other performances by the same artist — live, acoustic, remix, a TV-size cut —
 *    from your likes, your library and YouTube Music;
 *  - the same song by other artists: only what MusicBrainz says is the same *work*
 *    (one composition, every recording of it), so a shared title alone never counts.
 */
object SongFamily {

    private const val TAG = "BitChord"

    data class Other(val title: String, val artist: String, val album: String?, val song: Song?)

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    private fun sameTitle(a: String, b: String): Boolean {
        val ka = OwnFileMatcher.allKeys(a)
        return OwnFileMatcher.allKeys(b).any { it in ka }
    }

    /** The same artist's other recordings of [song]: live, acoustic, remix… and other concerts. */
    suspend fun performances(song: Song): List<Song> = withContext(Dispatchers.IO) {
        val pool = ArrayList<Song>()
        pool += LikedMusicCache.likedSongs.value
        pool += runCatching { com.music.bitchord.data.webdav.WebDavRepository.library() }.getOrDefault(emptyList())
        pool += OwnLibrary.uploads.value
        // Asked with the artist, and by the title alone (other artists' versions).
        val bare = song.title.replace(Regex("""\s*[(\[（【].*?[)\]）】]\s*"""), " ").trim()
        val found = (withTimeoutOrNull(8_000) {
            YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS).getOrNull()
        }.orEmpty() + withTimeoutOrNull(8_000) {
            YtMusicRepository.search(bare, SearchFilter.SONGS).getOrNull()
        }.orEmpty()).mapNotNull { r ->
            when (r) {
                is SearchResult.Track -> r.song
                is SearchResult.TopTrack -> r.song
                else -> null
            }
        }
        pool += found
        pool.asSequence()
            .filter { it.videoId != song.videoId && !it.isVideo }
            .filter { sameTitle(song.title, it.title) && (OwnFileMatcher.sameArtist(song.artist, it.artist) || coverOf(song, it)) }
            .filter { !OwnFileMatcher.sameRecording(song.title, song.albumName, seconds(song), it.title, it.albumName, seconds(it)) }
            // One per recording: per kind, release and length.
            .distinctBy { OwnFileMatcher.kindsOf(it.title, it.albumName).sorted().joinToString() + "|" + it.albumName.orEmpty().lowercase() + "|" + ((seconds(it) ?: 0) / 3) }
            .take(20)
            .toList()
    }

    /**
     * Whether [b] (another artist's) is a version of [a]: one of them is on a tribute or
     * covers release, or says whose song it is ("原曲歌手", "originally by") — a shared
     * title alone is not enough.
     */
    private fun coverOf(a: Song, b: Song): Boolean {
        fun covers(s: Song) = OwnFileMatcher.kindsOf(s.title, s.albumName).let { "tribute" in it || "cover" in it } ||
            Regex("""原曲|originally|オリジナル歌手|カバー""", RegexOption.IGNORE_CASE).containsMatchIn(s.title + " " + s.albumName.orEmpty())
        return covers(a) || covers(b)
    }

    private val worksCache = ConcurrentHashMap<String, List<Other>>()

    /**
     * The same song recorded by other artists: [song]'s recording found on MusicBrainz, its
     * work (the composition), and every recording of that work credited to someone else.
     * Nothing when MusicBrainz knows no work for it — a title alone is not enough.
     */
    suspend fun byOtherArtists(song: Song): List<Other> = withContext(Dispatchers.IO) {
        val cacheKey = OwnFileMatcher.key(song.title) + "|" + OwnFileMatcher.key(song.artist)
        worksCache[cacheKey]?.let { return@withContext it }
        val result = runCatching { lookUpWork(song) }.getOrNull().orEmpty()
        worksCache[cacheKey] = result
        result
    }

    private suspend fun lookUpWork(song: Song): List<Other> {
        val title = song.title.replace(Regex("""\s*[(\[（【].*?[)\]）】]\s*"""), " ").trim()
        val names = listOf(title to song.artist)
        var workId: String? = null
        for ((t, a) in names) {
            val q = "recording:\"${NameSources.lucene(t)}\" AND artist:\"${NameSources.lucene(a.split(",", "&", " feat").first().trim())}\""
            val found = NameSources.getJson(NameSources.mb("recording", "query" to q, "limit" to "8"), musicBrainz = true) ?: continue
            val recordings = found.optJSONArray("recordings").objects().filter { it.optInt("score") >= 85 }
            for (r in recordings.take(4)) {
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                val rec = NameSources.getJson(NameSources.mb("recording/$id", "inc" to "work-rels"), musicBrainz = true) ?: continue
                workId = rec.optJSONArray("relations").objects()
                    .firstOrNull { it.optString("target-type") == "work" }
                    ?.optJSONObject("work")?.optString("id")?.takeIf { it.isNotBlank() }
                if (workId != null) break
            }
            if (workId != null) break
        }
        val work = workId ?: run {
            TrackLog.d(TAG, "song family: no MusicBrainz work for '${song.title}' — no versions by others shown")
            return emptyList()
        }
        val browse = NameSources.getJson(
            NameSources.mb("recording", "work" to work, "inc" to "artist-credits+releases", "limit" to "100"),
            musicBrainz = true,
        ) ?: return emptyList()
        val others = browse.optJSONArray("recordings").objects().mapNotNull { r ->
            val credit = r.optJSONArray("artist-credit").objects().joinToString("") { c ->
                c.optString("name") + c.optString("joinphrase")
            }.trim()
            if (credit.isBlank() || OwnFileMatcher.sameArtist(song.artist, credit)) return@mapNotNull null
            val album = r.optJSONArray("releases").objects().firstOrNull()?.optString("title")
            Other(r.optString("title"), credit, album, null)
        }.distinctBy { OwnFileMatcher.key(it.artist) }.take(25)
        TrackLog.d(TAG, "song family: '${song.title}' — work $work, ${others.size} other artists")
        return others
    }

    /** A YouTube Music copy of [other] to play: same title (any script), same artist. */
    suspend fun findToPlay(other: Other): Song? = withContext(Dispatchers.IO) {
        other.song ?: withTimeoutOrNull(8_000) {
            YtMusicRepository.search("${other.title} ${other.artist}", SearchFilter.SONGS).getOrNull()
        }.orEmpty().firstNotNullOfOrNull { r ->
            val s = when (r) {
                is SearchResult.Track -> r.song
                is SearchResult.TopTrack -> r.song
                else -> null
            } ?: return@firstNotNullOfOrNull null
            s.takeIf { sameTitle(other.title, it.title) && OwnFileMatcher.sameArtist(other.artist, it.artist) }
        }
    }
}
