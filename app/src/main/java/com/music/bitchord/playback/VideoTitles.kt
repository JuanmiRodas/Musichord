package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: the catalogue song behind a video YouTube doesn't pair with
 * one — a fan's lyric video, a re-upload, an "Artist - Title (Official Video)"
 * on a VEVO or "- Topic" channel.
 *
 * Those titles carry everything but the song's name: "(Lyric Video)",
 * "[Official Music Video]", "| 4K", the artist again in front. Searched as
 * they are, nothing in the catalogue matches and the video plays. Here the
 * title is cut back to the song ([cleanTitle]), the channel to the artist
 * ([cleanArtist]), and YouTube Music is asked for songs by that name. A
 * candidate is taken when the matcher accepts it, or failing that when its
 * title is the cleaned title and it shares the artist — lengths are not
 * required to agree, since a video's intro and outro are not the song's.
 */
internal object VideoTitles {

    private const val TAG = "BitChord"

    private val found = ConcurrentHashMap<String, Song>()

    /** Bracketed notes that describe the video, not the song. "(Taylor's Version)" stays. */
    private val VIDEO_NOTE = Regex(
        """\s*[(\[【「]\s*[^)\]】」]*?(official|lyric|lyrics|letra|video|vídeo|audio|visuali[sz]er|mv|m/v|pv|hd|hq|4k|8k|60fps|live session|clip|subtitulad|sub español|sub esp|traducid|vostfr|karaoke)[^)\]】」]*[)\]】」]""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = Regex("""\s*(\||｜|//|•)\s.*$""")
    private val LOOSE_NOTE = Regex("""\s+(official\s+(music\s+)?video|lyric\s+video|lyrics?|official\s+audio|mv|m/v)\s*$""", RegexOption.IGNORE_CASE)
    private val CHANNEL_NOISE = Regex("""\s*(-\s*topic|vevo|official|oficial|music|tv|channel|youtube)\s*$""", RegexOption.IGNORE_CASE)

    fun cleanArtist(artist: String): String {
        var a = artist.trim()
        repeat(3) { a = a.replace(CHANNEL_NOISE, "").trim() }
        // "TaylorSwiftVEVO" leaves "TaylorSwift": split camel case back into words.
        if (' ' !in a && a.length > 3) a = a.replace(Regex("""(?<=[a-z])(?=[A-Z])"""), " ")
        return a.ifBlank { artist }
    }

    fun cleanTitle(title: String, artist: String): String {
        var t = title.replace(TRAILING, "")
        repeat(3) { t = t.replace(VIDEO_NOTE, "") }
        t = t.replace(LOOSE_NOTE, "").trim()
        // "Artist - Title": drop the artist in front.
        if (" - " in t) {
            val (left, right) = t.split(" - ", limit = 2)
            if (TrackMatcher.sharesArtist(artist, left) || FileName.key(left) == FileName.key(cleanArtist(artist))) {
                t = right.trim()
            }
        }
        return t.trim().trim('-', '–', '—', ' ').ifBlank { title }
    }

    /** The catalogue song for [video], or null when nothing is close enough. */
    suspend fun findRelease(video: Song): Song? {
        found[video.videoId]?.let { return it }
        val artist = cleanArtist(video.artist)
        val title = cleanTitle(video.title, video.artist)
        if (title.isBlank()) return null
        val candidates = withTimeoutOrNull(12_000L) {
            YtMusicRepository.search("$title $artist", SearchFilter.SONGS).getOrNull()
        }.orEmpty()
            .mapNotNull { r ->
                when (r) {
                    is SearchResult.Track -> r.song
                    is SearchResult.TopTrack -> r.song
                    else -> null
                }
            }
            .filterNot { it.isVideo }
        if (candidates.isEmpty()) return null
        val target = TrackMatcher.Target(title = title, artist = artist, isVideo = true)
        val titleKey = FileName.key(title)
        val pick = TrackMatcher.best(candidates, target)
            ?: TrackMatcher.bestOfficialAudioForVideo(candidates, target)
            ?: candidates.firstOrNull { c ->
                FileName.key(c.title) == titleKey &&
                    (TrackMatcher.sharesArtist(artist, c.artist) || TrackMatcher.sharesArtist(video.artist, c.artist))
            }
            ?: return null
        TrackLog.d(TAG, "audio-first: '${video.title}' → '${pick.title}' by ${pick.artist} (cleaned title)", about = video.videoId)
        found[video.videoId] = pick
        return pick
    }
}
