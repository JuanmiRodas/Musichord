package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteTagStore
import com.music.bitchord.data.webdav.PcloudPreset
import com.music.bitchord.data.webdav.WebDavAuth
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import java.text.Normalizer
import java.util.Locale

/**
 * BitChord Audio: your own files (the pCloud / WebDAV library) as a source,
 * so a YouTube song you also have is played from your copy.
 *
 * It sits first in the source walk ([SourceKind.MY_LIBRARY]), and answers from
 * the library as last listed — on the device, so a search costs nothing and
 * works offline. The resolver's usual rules then decide whether the copy is
 * used: it has to be the same recording ([TrackMatcher], length included) and
 * worth it over YouTube's own stream ([SourceResolver.beatsYouTubeAac]), which
 * a FLAC always is. A song you'd rather hear from YouTube is pinned to it from
 * the player as with any other source.
 */
class RemoteLibrarySource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.MY_LIBRARY
    override val displayName: String
        get() = config.label.ifBlank {
            if (PcloudPreset.isPcloud(com.music.bitchord.data.settings.AppSettings.webdavUrl.value)) "pCloud" else kind.label
        }

    override suspend fun health(): SourceHealth = when {
        !WebDavRepository.isConfigured() -> SourceHealth.Rejected("Connect pCloud or a WebDAV library under Sources")
        WebDavRepository.library().isEmpty() -> SourceHealth.Ok("Open the library once to index it")
        else -> SourceHealth.Ok("${WebDavRepository.library().size} songs")
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        // "YouTube first" in Settings: your files never stand in for YouTube's.
        if (com.music.bitchord.data.settings.AppSettings.sourcePreference.value ==
            com.music.bitchord.data.settings.AppSettings.SOURCE_YOUTUBE_FIRST
        ) return emptyList()
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return WebDavRepository.library()
            .asSequence()
            .map { song -> song to norm("${song.title} ${song.artist} ${song.albumName.orEmpty()}") }
            .filter { (_, hay) -> words.all { it in hay } }
            .map { it.first }
            .take(limit)
            .toList()
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val url = WebDavConfig.fileUrlOf(trackId) ?: return null
        val tags = RemoteTagStore.get(url, RemoteTagStore.Stamp(null, null))
        val codec = tags?.codec?.lowercase(Locale.ROOT)
            ?: url.substringAfterLast('.', "").lowercase(Locale.ROOT).ifBlank { null }
        val headers = WebDavAuth.authHeader?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to it) }.orEmpty()
        return SourceStream(
            url = url,
            format = StreamFormat(
                codec = codec,
                kbps = tags?.kbps?.takeIf { it > 0 } ?: estimatedKbps(url, tags?.durationMs),
                sampleRateHz = tags?.sampleRate,
                bitDepth = tags?.bitDepth,
            ),
            headers = headers,
            durationSec = tags?.durationMs?.let { (it / 1000).toInt() },
            sourceConfigId = configId,
        )
    }

    /** Size over length when the frames haven't been read yet. */
    private fun estimatedKbps(url: String, durationMs: Long?): Int? {
        val size = RemoteTagStore.sizeOf(url) ?: return null
        val ms = durationMs?.takeIf { it > 1000 } ?: return null
        return (size * 8 / ms).toInt()
    }

    companion object {
        /** Below this a lossy file of yours loses to YouTube's own stream. */
        /** Your choice in Settings: 256 unless changed. */
        val MIN_KBPS: Int get() = com.music.bitchord.data.settings.AppSettings.ownFileMinKbps.value

        /**
         * Whether your copy should play instead of YouTube's, under the
         * listener's preference: always ("my files first"), never ("YouTube
         * first"), or — the default, "best quality" — when it is lossless or
         * at least [MIN_KBPS]. A bitrate not known yet is worked out from the
         * file's size over the song's length.
         */
        fun qualifies(stream: SourceStream, targetDurationSec: Int?): Boolean {
            val pref = com.music.bitchord.data.settings.AppSettings.sourcePreference.value
            if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_YOUTUBE_FIRST) return false
            if (pref == com.music.bitchord.data.settings.AppSettings.SOURCE_OWN_FIRST) return true
            if (stream.format.isLossless == true) return true
            val kbps = stream.format.kbps?.takeIf { it > 0 }
                ?: RemoteTagStore.sizeOf(stream.url)?.let { size ->
                    targetDurationSec?.takeIf { it > 0 }?.let { (size * 8 / 1000 / it).toInt() }
                }
                ?: return false
            return kbps >= MIN_KBPS || kbps > youTubeKbps(null)
        }

        /** YouTube's usual audio: Opus at about 160 kbps. */
        const val YOUTUBE_TYPICAL_KBPS = 160

        /** YouTube's bitrate for [videoId]: as measured when it last played, else the usual. */
        fun youTubeKbps(videoId: String?): Int =
            videoId?.let { com.music.bitchord.data.NerdStats.pickedBitrateKbps(it) }?.takeIf { it > 0 }
                ?: YOUTUBE_TYPICAL_KBPS
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
