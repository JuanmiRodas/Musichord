package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TAG = "BitChord"

/**
 * SoundCloud as a song source: its public web API, reached with the same
 * anonymous `client_id` soundcloud.com's own pages use.
 *
 * Its catalogue is what nobody else has — remixes, bootlegs, DJ edits,
 * self-released and deleted tracks — but its streams are 128 kbps MP3 (or
 * 64 kbps Opus), below YouTube's. So it ranks after YouTube: it is never
 * used to "upgrade" a track, only when picked by hand in the player's
 * source sheet, or for what the other catalogues can't play.
 *
 * Tracks that are preview-only (a Go+ 30-second snip) or geo-blocked are
 * left out of the results, for the same reason [NetEaseSource] drops VIP
 * tracks: they would stop after the intro.
 */
class SoundCloudSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.SOUNDCLOUD
    override val displayName: String get() = config.label.ifBlank { SourceKind.SOUNDCLOUD.label }

    override suspend fun health(): SourceHealth =
        if (ClientId.get() != null) SourceHealth.Ok()
        else SourceHealth.Down("Couldn't reach SoundCloud")

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ SoundCloud search query=\"$query\" limit=$limit")
        val body = api("https://api-v2.soundcloud.com/search/tracks") {
            parameter("q", query)
            parameter("limit", (limit * 2).coerceIn(10, 30))
        } ?: return emptyList()
        val all = runCatching {
            json.parseToJsonElement(body).jsonObject["collection"]?.jsonArray
        }.getOrNull().orEmpty().mapNotNull { it as? JsonObject }
        val playable = all.filter(::fullyPlayable)
        TrackLog.d(TAG, "  ✓ SoundCloud returned ${all.size} tracks, ${playable.size} playable" +
            playable.take(5).joinToString(prefix = ": ", separator = "; ") {
                val (title, artist) = titleAndArtist(it)
                "'$title' by '$artist' ${(it.long("full_duration") ?: it.long("duration") ?: 0) / 1000}s id=${it.long("id")}"
            }.takeIf { playable.isNotEmpty() }.orEmpty())

        return playable.take(limit).mapNotNull { raw ->
            val id = raw.long("id") ?: return@mapNotNull null
            val (title, artist) = titleAndArtist(raw)
            if (title.isBlank()) return@mapNotNull null
            val publisher = raw["publisher_metadata"] as? JsonObject
            Song(
                videoId = SourceRegistry.trackKey(config.id, id.toString()),
                title = title,
                artist = artist.ifBlank { "Unknown Artist" },
                albumName = publisher?.str("album_title")?.ifBlank { null },
                // `-large` is 100px; `-t500x500` is the same file at 500.
                thumbnailUrl = (raw.str("artwork_url")
                    ?: (raw["user"] as? JsonObject)?.str("avatar_url"))
                    ?.replace("-large.", "-t500x500."),
                durationText = (raw.long("full_duration") ?: raw.long("duration"))
                    ?.let { sourceDurationText(it / 1000) },
                sourceQuality = "LOW",
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ SoundCloud stream trackId=$trackId request=$request")
        val track = api("https://api-v2.soundcloud.com/tracks/$trackId") {}
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (track == null || !fullyPlayable(track)) {
            TrackLog.w(TAG, "  ✗ SoundCloud track $trackId isn't fully playable here")
            return null
        }
        val authorization = track.str("track_authorization")
        val transcodings = ((track["media"] as? JsonObject)?.get("transcodings") as? JsonArray)
            .orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.bool("snipped") != true }
        // Progressive MP3 first — one plain file, seekable, the fewest moving
        // parts. Then HLS, which Media3 plays too: AAC (Go+ only, 160) above
        // MP3 (128) above Opus (64).
        val ranked = transcodings.sortedBy { t ->
            val protocol = (t["format"] as? JsonObject)?.str("protocol").orEmpty()
            val mime = (t["format"] as? JsonObject)?.str("mime_type").orEmpty()
            when {
                protocol == "progressive" -> 0
                "mp4" in mime || "aac" in mime -> 1
                "mpeg" in mime -> 2
                else -> 3
            }
        }
        for (t in ranked) {
            val lookup = t.str("url") ?: continue
            val resolved = api(lookup) {
                if (authorization != null) parameter("track_authorization", authorization)
            }?.let { runCatching { json.parseToJsonElement(it).jsonObject.str("url") }.getOrNull() }
                ?: continue
            val format = t["format"] as? JsonObject
            val mime = format?.str("mime_type").orEmpty()
            val (codec, kbps) = when {
                "opus" in mime || "ogg" in mime -> "opus" to 64
                "mp4" in mime || "aac" in mime -> "aac" to 160
                else -> "mp3" to 128
            }
            TrackLog.d(TAG, "  ✓ SoundCloud ${format?.str("protocol")} $codec ~${kbps}kbps ${resolved.take(96)}")
            return SourceStream(url = resolved, format = StreamFormat(codec = codec, kbps = kbps))
        }
        TrackLog.w(TAG, "  ✗ SoundCloud had no usable rendition for $trackId")
        return null
    }

    /** GET with the client id, renewing it once if SoundCloud says it went stale. */
    private suspend fun api(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String? {
        repeat(2) { attempt ->
            val id = ClientId.get(refresh = attempt > 0) ?: return null
            val response: HttpResponse = runCatching {
                client.get(url) {
                    parameter("client_id", id)
                    block()
                }
            }.onFailure { TrackLog.w(TAG, "  ✗ SoundCloud ${url.substringAfter("soundcloud.com").take(40)} failed: ${it.message}") }
                .getOrNull() ?: return null
            when (response.status.value) {
                in 200..299 -> return response.bodyAsText()
                401, 403 -> if (attempt == 0) return@repeat
                else -> {
                    TrackLog.w(TAG, "  ✗ SoundCloud HTTP ${response.status.value} for ${url.substringAfter("soundcloud.com").take(40)}")
                    return null
                }
            }
        }
        return null
    }

    /**
     * The anonymous client id soundcloud.com's own scripts carry. It rotates
     * every few weeks, so it is read from the site rather than written here,
     * and read again whenever the API turns the current one away.
     */
    private object ClientId {
        private val lock = Mutex()
        @Volatile private var cached: String? = null
        @Volatile private var failedAt = 0L

        suspend fun get(refresh: Boolean = false): String? {
            if (!refresh) cached?.let { return it }
            return lock.withLock {
                if (!refresh) cached?.let { return@withLock it }
                // Don't hammer the site while it's unreachable.
                if (System.currentTimeMillis() - failedAt < 60_000) return@withLock cached
                val found = scrape()
                if (found == null) failedAt = System.currentTimeMillis() else cached = found
                found ?: cached
            }
        }

        private suspend fun scrape(): String? = runCatching {
            val page = client.get("https://soundcloud.com/").bodyAsText()
            val scripts = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
                .findAll(page).map { it.groupValues[1] }.toList()
            // The id is in one of the last bundles; read from the end.
            for (src in scripts.asReversed()) {
                val js = runCatching { client.get(src).bodyAsText() }.getOrNull() ?: continue
                Regex("""client_id\s*[:=]\s*"([A-Za-z0-9]{32})"""").find(js)?.let {
                    TrackLog.d(TAG, "SoundCloud client id refreshed")
                    return@runCatching it.groupValues[1]
                }
            }
            TrackLog.w(TAG, "SoundCloud client id not found in ${scripts.size} scripts")
            null
        }.onFailure { TrackLog.w(TAG, "SoundCloud client id lookup failed: ${it.message}") }.getOrNull()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val client by lazy {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 8_000
                    connectTimeoutMillis = 4_000
                    socketTimeoutMillis = 8_000
                }
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                    headers.append(HttpHeaders.Origin, "https://soundcloud.com")
                    headers.append(HttpHeaders.Referrer, "https://soundcloud.com/")
                }
                expectSuccess = false
            }
        }

        /** Not a Go+ preview, not blocked where the phone is. */
        fun fullyPlayable(raw: JsonObject): Boolean {
            val policy = raw.str("policy")?.uppercase()
            if (policy == "SNIP" || policy == "BLOCK") return false
            if (raw.bool("streamable") == false) return false
            val full = raw.long("full_duration")
            val dur = raw.long("duration")
            // A snip announces itself this way too: 30s served of a 4-minute track.
            return !(full != null && dur != null && full - dur > 5_000)
        }

        /**
         * SoundCloud has one title field and an uploader. Labels and labels'
         * channels fill [publisher_metadata] with the real artist; personal
         * uploads often write "Artist - Title" into the title instead, which
         * is split here so the match against the song isn't thrown by it.
         */
        fun titleAndArtist(raw: JsonObject): Pair<String, String> {
            val title = raw.str("title").orEmpty().trim()
            val publisherArtist = (raw["publisher_metadata"] as? JsonObject)?.str("artist")?.trim()
            val uploader = (raw["user"] as? JsonObject)?.str("username")?.trim().orEmpty()
            if (!publisherArtist.isNullOrBlank()) {
                val stripped = title.removePrefix("$publisherArtist - ").removePrefix("$publisherArtist – ")
                return stripped.trim() to publisherArtist
            }
            val dash = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(title)
            if (dash != null) return dash.groupValues[2].trim() to dash.groupValues[1].trim()
            return title to uploader
        }

        fun JsonObject.str(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

        fun JsonObject.long(key: String): Long? =
            runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

        fun JsonObject.bool(key: String): Boolean? =
            runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
    }
}
