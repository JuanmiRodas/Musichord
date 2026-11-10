package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TAG = "BitChord"

/**
 * NetEase Cloud Music (music.163.com) as a song source, in the same shape as
 * [JioSaavnSource]: a public catalogue searched without an account, streamed
 * as MP3 at up to 320 kbps.
 *
 * Its strength is East Asian music — Japanese, Chinese and Korean releases
 * that JioSaavn rarely has, filed under their original-script names (which
 * [SourceResolver] already searches by, through the Japanese-name variants).
 *
 * Only tracks the catalogue marks as playable without a subscription are
 * offered: a VIP-only track returns no address, or a 30-second preview,
 * and handing that up would be a track that stops after the intro.
 */
class NetEaseSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.NETEASE
    override val displayName: String get() = config.label.ifBlank { SourceKind.NETEASE.label }

    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ NetEase search query=\"$query\" limit=$limit")
        val body = runCatching {
            client.submitForm(
                url = "https://music.163.com/api/cloudsearch/pc",
                formParameters = parameters {
                    append("s", query)
                    append("type", "1")
                    append("limit", (limit * 2).coerceIn(10, 30).toString())
                    append("offset", "0")
                },
            ).bodyAsText()
        }.onFailure { TrackLog.w(TAG, "  ✗ NetEase search failed: ${it.message}") }.getOrNull()
            ?: return emptyList()
        val songs = runCatching {
            json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("songs")?.jsonArray
        }.getOrNull() ?: return emptyList<Song>().also { TrackLog.d(TAG, "  ✓ NetEase returned 0 tracks") }

        val playable = songs.mapNotNull { it as? JsonObject }.filter(::freelyPlayable)
        TrackLog.d(TAG, "  ✓ NetEase returned ${songs.size} tracks, ${playable.size} playable" +
            playable.take(5).joinToString(prefix = ": ", separator = "; ") {
                "'${it.str("name")}' by '${artistsOf(it)}' ${(it.long("dt") ?: 0) / 1000}s id=${it.long("id")}"
            }.takeIf { playable.isNotEmpty() }.orEmpty())

        return playable.take(limit).mapNotNull { raw ->
            val id = raw.long("id") ?: return@mapNotNull null
            val album = raw["al"] as? JsonObject
            Song(
                videoId = SourceRegistry.trackKey(config.id, id.toString()),
                title = raw.str("name") ?: return@mapNotNull null,
                artist = artistsOf(raw).ifBlank { "Unknown Artist" },
                albumName = album?.str("name")?.ifBlank { null },
                thumbnailUrl = album?.str("picUrl")?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("^http://"), "https://")?.let { "$it?param=500y500" },
                durationText = raw.long("dt")?.let { sourceDurationText(it / 1000) },
                sourceQuality = "HIGH",
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ NetEase stream trackId=$trackId request=$request")
        val entry = runCatching {
            val body = client.get("https://music.163.com/api/song/enhance/player/url") {
                parameter("ids", "[$trackId]")
                parameter("br", "320000")
            }.bodyAsText()
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
        }.onFailure { TrackLog.w(TAG, "  ✗ NetEase stream lookup failed: ${it.message}") }.getOrNull()

        val url = entry?.str("url")?.takeIf { it.startsWith("http") }
        // A preview: the catalogue hands back a clip with `freeTrialInfo` set
        // rather than refusing outright. Playing it would cut out mid-song.
        val trial = entry?.get("freeTrialInfo")?.let { it is JsonObject }
        if (url == null || trial == true) {
            TrackLog.w(TAG, "  ✗ NetEase had no full stream for $trackId (code=${entry?.get("code")}, trial=$trial)")
            return null
        }
        val kbps = entry.long("br")?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        if (kbps != null && kbps <= MIN_USABLE_KBPS) {
            TrackLog.w(TAG, "  ✗ NetEase only offered ${kbps}kbps for $trackId; not worth playing")
            return null
        }
        val codec = entry.str("type")?.lowercase()?.ifBlank { null } ?: "mp3"
        TrackLog.d(TAG, "  ✓ NetEase ${kbps ?: "?"}kbps $codec ${url.take(96)}")
        return SourceStream(
            url = url.replace(Regex("^http://"), "https://"),
            format = StreamFormat(codec = codec, kbps = kbps),
            headers = mapOf(HttpHeaders.Referrer to "https://music.163.com/"),
        )
    }

    private companion object {
        /** Below this NetEase is worse than the YouTube stream it would replace. */
        const val MIN_USABLE_KBPS = 128

        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val client by lazy {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 7_000
                    connectTimeoutMillis = 4_000
                    socketTimeoutMillis = 7_000
                }
                defaultRequest {
                    headers.append(HttpHeaders.Referrer, "https://music.163.com/")
                    headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                    headers.append(HttpHeaders.Cookie, "os=pc; appver=2.10.6")
                }
                expectSuccess = false
            }
        }

        /**
         * Whether the catalogue says this plays in full with no subscription.
         * `fee` 1 and 4 are VIP / paid album; `privilege.pl` 0 is "no
         * playable rate at all" (removed, or not licensed here); `st` < 0 is
         * taken down.
         */
        fun freelyPlayable(raw: JsonObject): Boolean {
            val fee = raw.int("fee") ?: 0
            if (fee == 1 || fee == 4) return false
            val privilege = raw["privilege"] as? JsonObject ?: return true
            if ((privilege.int("st") ?: 0) < 0) return false
            return (privilege.int("pl") ?: 1) > 0
        }

        fun artistsOf(raw: JsonObject): String =
            (raw["ar"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) }
                .joinToString(", ")

        fun JsonObject.str(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

        fun JsonObject.long(key: String): Long? =
            runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

        fun JsonObject.int(key: String): Int? =
            runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
    }
}

/** "m:ss", the form [Song.durationText] holds. */
internal fun sourceDurationText(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
