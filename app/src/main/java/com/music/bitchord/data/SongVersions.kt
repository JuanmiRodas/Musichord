package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song

/**
 * BitChord Audio: liked music videos traded for the song itself.
 *
 * A like on a music video (in the app or on YouTube) puts the video into Liked
 * Music: its title says "MV", "Official Video" and the like, and its cover is a
 * video frame, not the album's. Such likes are found by their title (or by
 * being a video at all) and swapped — the song version liked, the video
 * un-liked, in the same place in the list. YouTube's own Song/Video pairing is
 * asked first, then the same matcher everything else uses (same title and
 * version, shared artist, never a cover or karaoke), so nothing is swapped on
 * a guess. The song menu offers the same swap by hand for any liked song.
 */
object SongVersions {

    /** What a music video's title says about it. */
    private val VIDEO_WORDS = Regex(
        "(?i)(" +
            "\\bp\\.?v\\b|\\bm\\.?v\\b|\\bm/v\\b|" +
            "music\\s*video|official\\s+video|offical\\s+video|lyric\\s+video|official\\s+visuali[sz]er|" +
            "video\\s*clip|videoclip|v[ií]deo\\s+oficial|clip\\s+officiel|offizielles\\s+video|" +
            "ミュージック[・ ]?ビデオ|ﾐｭｰｼﾞｯｸﾋﾞﾃﾞｵ|뮤직\\s*비디오|官方\\s*MV|完整版MV" +
            ")",
    )

    fun looksLikeVideo(song: Song): Boolean = song.isVideo || saysVideo(song)

    /** Only what the title says — the automatic pass never goes by the video flag alone. */
    fun saysVideo(song: Song): Boolean = VIDEO_WORDS.containsMatchIn(song.title)

    /** The automatic pass: off (it reordered Liked Music on YouTube). */
    @Volatile var automatic: Boolean = false

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_song_versions", Context.MODE_PRIVATE)
    }

    /** Videos already looked at by the automatic pass (swapped or with no song version). */
    fun tried(videoId: String): Boolean = prefs?.getBoolean(videoId, false) == true

    fun markTried(videoId: String) {
        prefs?.edit()?.putBoolean(videoId, true)?.apply()
    }

    /** Set by the app's view model: swaps [Song]'s like for its song version by hand. */
    @Volatile var manual: ((Song) -> Unit)? = null
}
