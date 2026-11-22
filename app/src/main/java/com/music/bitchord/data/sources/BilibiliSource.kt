package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.video.Bilibili

private const val TAG = "BitChord"

/**
 * BitChord Audio: bilibili as a song source — its public video search and the
 * audio-only DASH rendition its player gets without an account.
 *
 * Ranked below YouTube and asked only when YouTube can't play a song here and
 * nothing above it has it (see [SourceResolver.lastResortFor]); then only an
 * upload with the same title, a shared artist and the same runtime plays.
 * Uploads that say they are something else — a cover, a piano or karaoke
 * version, a live take, a sped-up edit — are left out of the results before
 * the matcher even sees them.
 */
class BilibiliSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.BILIBILI
    override val displayName: String get() = config.label.ifBlank { SourceKind.BILIBILI.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
        target: TrackMatcher.Target?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ bilibili search query=\"$query\" limit=$limit")
        val wantsOther = NOT_THE_RECORDING.containsMatchIn(query)
        val hits = Bilibili.search(query)
        val kept = hits.filter { wantsOther || !NOT_THE_RECORDING.containsMatchIn(it.title) }
        TrackLog.d(TAG, "  ✓ bilibili returned ${hits.size} videos, ${kept.size} kept" +
            kept.take(5).joinToString(prefix = ": ", separator = "; ") { "'${it.title}' by '${it.author}' ${it.durationText}" }
                .takeIf { kept.isNotEmpty() }.orEmpty())
        return kept.take(limit).mapNotNull { hit ->
            val (title, artists) = split(hit.title)
            if (title.isBlank()) return@mapNotNull null
            Song(
                videoId = SourceRegistry.trackKey(config.id, hit.bvid),
                title = title,
                // The credit an upload carries is anywhere: a 【tag】, "Artist - Title",
                // or only the uploader. All of them, so a shared artist is found.
                artist = (artists + hit.author).filter { it.isNotBlank() }.distinct().joinToString(", ")
                    .ifBlank { "Unknown Artist" },
                thumbnailUrl = hit.thumbnail,
                durationText = hit.durationText,
                sourceQuality = "LOW",
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ bilibili stream $trackId")
        val audio = Bilibili.audio(trackId) ?: run {
            TrackLog.w(TAG, "  ✗ bilibili had no audio for $trackId")
            return null
        }
        return SourceStream(
            url = audio.url,
            format = StreamFormat(codec = "aac", kbps = audio.kbps.takeIf { it > 0 }),
            headers = audio.headers,
        )
    }

    private companion object {
        /** What an upload says when it isn't the studio recording. */
        val NOT_THE_RECORDING = Regex(
            "(?i)翻唱|cover|カバー|歌ってみた|弾いてみた|弾き語り|弹唱|演奏|钢琴|ピアノ|piano|吉他|guitar|ギター|" +
                "架子鼓|drum|ドラム|伴奏|karaoke|カラオケ|off ?vocal|instrumental|纯音乐|inst\\b|" +
                "\\blive\\b|现场|演唱会|ライブ|コンサート|remix|混剪|nightcore|倍速|加速|降调|升调|" +
                "教程|教学|八音盒|music ?box|オルゴール|8bit|ai翻唱|\\bai cover|reaction|reacción",
        )

        /** Tags that describe the upload, not who sings it. */
        val NOT_AN_ARTIST = Regex(
            "(?i)hi-?res|flac|无损|高音质|高音質|中日|字幕|歌词|\\bmv\\b|\\bpv\\b|4k|1080|完整版|官方|official|中字|日语|日文|动态|music video|\\bav\\b|lyric",
        )

        /** "【Artist】Title (noise)" / "Artist - Title" → title, artist candidates. */
        fun split(raw: String): Pair<String, List<String>> {
            val tags = Regex("[【\\[「『]([^】\\]」』]+)[】\\]」』]").findAll(raw).map { it.groupValues[1].trim() }.toList()
            var title = raw.replace(Regex("[【\\[]([^】\\]]+)[】\\]]"), " ")
            title = title.replace(Regex("[(（]([^)）]*)[)）]")) { m ->
                if (NOT_AN_ARTIST.containsMatchIn(m.groupValues[1])) " " else m.value
            }.replace(Regex("\\s+"), " ").trim()
            val artists = tags.filterNot { NOT_AN_ARTIST.containsMatchIn(it) }.toMutableList()
            val dash = Regex("^(.+?)\\s+[-–—/／]\\s+(.+)$").find(title)
            if (dash != null) {
                artists += dash.groupValues[1].trim()
                title = dash.groupValues[2].trim()
            }
            // 「Title」 alone, with the artist outside the quotes.
            Regex("^(.*?)「([^」]+)」(.*)$").find(raw)?.let { m ->
                if (dash == null) {
                    title = m.groupValues[2].trim()
                    artists += (m.groupValues[1] + " " + m.groupValues[3]).replace(Regex("[【】\\[\\]]"), " ").trim()
                }
            }
            return title.trim(' ', '-', '|', '｜') to artists.filter { it.isNotBlank() && it != title }
        }
    }
}
