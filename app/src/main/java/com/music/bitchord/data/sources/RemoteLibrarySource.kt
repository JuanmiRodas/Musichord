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
            format = StreamFormat(codec = codec, sampleRateHz = tags?.sampleRate, bitDepth = tags?.bitDepth),
            headers = headers,
            durationSec = tags?.durationMs?.let { (it / 1000).toInt() },
            sourceConfigId = configId,
        )
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
