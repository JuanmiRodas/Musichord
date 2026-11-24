package com.music.bitchord.data.covers

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.jiosaavn.JioSaavnService
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.remote.RemoteArtworkStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.JapaneseNames
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: every cover a song could have, from everywhere worth asking.
 *
 * Yours first — the cover in each of your pCloud files that is this song, and
 * the pictures in their folders; the YouTube Music song and its album. Then
 * the catalogues: JioSaavn, Apple (Japanese and US storefronts — the Japanese
 * one carries most anime and J-pop releases at full size), Deezer, MusicBrainz's
 * Cover Art Archive, NetEase (Chinese and Japanese releases), and VGMdb (anime,
 * game and Japanese releases, through vgmdb.info). Each asked by album and by
 * song, in romaji and in Japanese where the Japanese names are known.
 */
object CoverSources {

    /** A cover on offer: where it is, which place it comes from, what it is the cover of. */
    data class Cover(val url: String, val source: String, val detail: String = "")

    private val http by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private const val AGENT = "BitChordAudio/1.9 (https://github.com/JuanmiRodas/Musichord)"

    private fun get(url: String): String? = if (url.contains("://vgmdb.info/")) com.music.bitchord.data.sources.VgmdbNet.text(url) else runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/json").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Your pCloud files that are [song]: itself, the one it plays from, the best match. */
    fun filesOf(song: Song): List<Song> {
        val out = LinkedHashMap<String, Song>()
        if (WebDavConfig.isWebDavId(song.videoId)) out[song.videoId] = song
        SourceMap.entry(song.videoId)?.file?.let(SourceMap::fileSong)?.let { out.putIfAbsent(it.videoId, it) }
        val sec = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.fold(0) { a, p -> a * 60 + p }?.takeIf { it > 0 }
        runCatching { OwnFileMatcher.matches(song.title, song.artist, sec, song.albumName).firstOrNull() }.getOrNull()
            ?.let { out.putIfAbsent(it.videoId, it) }
        return out.values.toList()
    }

    /** Covers for [song], grouped by place; each place gives up after a few seconds. */
    /** BitChord Audio: what is searched by default — what the search field starts with. */
    fun defaultQuery(song: Song): String {
        val artist = song.artist.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).first().trim()
        return listOf(song.albumName?.takeIf { it.isNotBlank() } ?: song.title, artist).joinToString(" ").trim()
    }

    suspend fun find(context: Context, song: Song, custom: String? = null): List<Cover> = withContext(Dispatchers.IO) {
        val names = runCatching { JapaneseNames.of(song) }.getOrDefault(JapaneseNames.Names.NONE)
        val artist = song.artist.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).first().trim()
        val album = song.albumName?.takeIf { it.isNotBlank() }
        val title = song.title.replace(Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]"""), " ").trim()
        val jaArtist = names.artists.firstOrNull()
        val jaTitle = names.titles.firstOrNull()
        // BitChord Audio: the official Latin names too (the names database).
        val official = com.music.bitchord.data.names.OfficialNames
        val latinArtist = official.get(com.music.bitchord.data.names.OfficialNames.Kind.ARTIST, artist, null)?.takeIf { it.source.official }?.latin
        val latinAlbum = album?.let { official.get(com.music.bitchord.data.names.OfficialNames.Kind.ALBUM, it, artist) }?.takeIf { it.source.official }?.latin
        val latinTitle = official.get(com.music.bitchord.data.names.OfficialNames.Kind.TITLE, song.title, artist)?.takeIf { it.source.official }?.latin
        val custom = custom?.trim()?.takeIf { it.isNotEmpty() }
        val albumQueries = if (custom != null) listOf(custom) else listOfNotNull(
            album?.let { "$it $artist" },
            album?.let { a -> jaArtist?.let { "$a $it" } },
            latinAlbum?.let { "$it ${latinArtist ?: artist}" },
        ).distinct()
        val songQueries = if (custom != null) listOf(custom) else listOfNotNull(
            "$title $artist",
            jaTitle?.let { "$it ${jaArtist ?: artist}" },
            latinTitle?.let { "$it ${latinArtist ?: artist}" },
        ).distinct()
        val online = AppSettings.meteredConnection.value != null

        coroutineScope {
            fun place(block: suspend () -> List<Cover>) = async { withTimeoutOrNull(10_000) { runCatching { block() }.getOrNull() }.orEmpty() }
            val parts = listOf(
                place { own(context, song) },
                place { youtube(song, albumQueries + songQueries.take(1)) },
                if (online) place { jioSaavn(albumQueries, songQueries) } else place { emptyList() },
                if (online) place { itunes(albumQueries, songQueries) } else place { emptyList() },
                if (online) place { deezer(albumQueries, songQueries) } else place { emptyList() },
                if (online) place { coverArtArchive(album, artist, title) } else place { emptyList() },
                if (online) place { netease(albumQueries + songQueries) } else place { emptyList() },
                if (online) place { vgmdb(listOfNotNull(album, jaTitle, title)) } else place { emptyList() },
            )
            parts.flatMap { it.await() }.distinctBy { it.url }
        }
    }

    private suspend fun own(context: Context, song: Song): List<Cover> {
        val out = ArrayList<Cover>()
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val files = filesOf(song)
        for (f in files) {
            RemoteArtworkStore.resolveArt(context, f.copy(thumbnailUrl = null))?.let { out += Cover(it, "pCloud", "in the file") }
            f.thumbnailUrl?.let { out += Cover(it, "pCloud", "folder") }
        }
        // Pictures in each file's folder: cover.jpg, folder.jpg, scans.
        for (dir in files.mapNotNull { WebDavConfig.fileUrlOf(it.videoId)?.substringBeforeLast('/')?.plus("/") }.distinct().take(2)) {
            WebDavClient.listFiles(dir, u, p).getOrNull().orEmpty()
                .filter { !it.isCollection && WebDavClient.isImageFile(it.displayName) }
                .take(12).forEach { out += Cover(it.url, "pCloud", it.displayName) }
        }
        // The YouTube song a file of yours stands in for.
        LocalLikes.youTubeOf(song.videoId)?.let { yt ->
            LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs?.firstOrNull { it.videoId == yt }
        }?.thumbnailUrl?.let { out += Cover(it.artworkAt(1200) ?: it, "YouTube Music", "liked song") }
        return out
    }

    private suspend fun youtube(song: Song, queries: List<String>): List<Cover> {
        val out = ArrayList<Cover>()
        song.thumbnailUrl?.takeIf { !WebDavConfig.isWebDavId(song.videoId) }?.let { out += Cover(it.artworkAt(1200) ?: it, "YouTube Music", "this song") }
        for (q in queries.take(2)) {
            YtMusicRepository.search(q, SearchFilter.ALBUMS).getOrNull().orEmpty().take(6).forEach { r ->
                val item = (r as? SearchResult.Browse)?.item ?: return@forEach
                item.thumbnailUrl?.let { out += Cover(it.artworkAt(1200) ?: it, "YouTube Music", item.title) }
            }
        }
        return out
    }

    private suspend fun jioSaavn(albums: List<String>, songs: List<String>): List<Cover> {
        val out = ArrayList<Cover>()
        fun big(url: String) = url.replace(Regex("""\d{2,3}x\d{2,3}"""), "500x500")
        for (q in albums) JioSaavnService.searchAlbums(q).take(5).forEach { a ->
            if (a.image.isNotBlank()) out += Cover(big(a.image), "JioSaavn", a.title)
        }
        for (q in songs) JioSaavnService.searchSongs(q).take(5).forEach { s ->
            if (s.image.isNotBlank()) out += Cover(big(s.image), "JioSaavn", s.title)
        }
        return out
    }

    private fun itunes(albums: List<String>, songs: List<String>): List<Cover> {
        val out = ArrayList<Cover>()
        fun big(url: String) = url.replace(Regex("""\d+x\d+bb"""), "1400x1400bb")
        for (country in listOf("JP", "US")) {
            for (q in albums) {
                val body = get("https://itunes.apple.com/search?term=${enc(q)}&country=$country&media=music&entity=album&limit=6") ?: continue
                val rows = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    r.optString("artworkUrl100").takeIf { it.isNotBlank() }?.let { out += Cover(big(it), "Apple Music $country", r.optString("collectionName")) }
                }
            }
            for (q in songs) {
                val body = get("https://itunes.apple.com/search?term=${enc(q)}&country=$country&media=music&entity=song&limit=6") ?: continue
                val rows = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    r.optString("artworkUrl100").takeIf { it.isNotBlank() }?.let { out += Cover(big(it), "Apple Music $country", r.optString("collectionName")) }
                }
            }
        }
        return out
    }

    private fun deezer(albums: List<String>, songs: List<String>): List<Cover> {
        val out = ArrayList<Cover>()
        for (q in albums) {
            val rows = get("https://api.deezer.com/search/album?q=${enc(q)}&limit=6")?.let { JSONObject(it).optJSONArray("data") } ?: continue
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                r.optString("cover_xl").takeIf { it.isNotBlank() }?.let { out += Cover(it, "Deezer", r.optString("title")) }
            }
        }
        for (q in songs) {
            val rows = get("https://api.deezer.com/search?q=${enc(q)}&limit=6")?.let { JSONObject(it).optJSONArray("data") } ?: continue
            for (i in 0 until rows.length()) {
                val a = rows.getJSONObject(i).optJSONObject("album") ?: continue
                a.optString("cover_xl").takeIf { it.isNotBlank() }?.let { out += Cover(it, "Deezer", a.optString("title")) }
            }
        }
        return out
    }

    /** MusicBrainz's release groups by album (or song) and artist, each group's front cover from the Cover Art Archive. */
    private fun coverArtArchive(album: String?, artist: String, title: String): List<Cover> {
        val q = if (album != null) "releasegroup:\"$album\" AND artist:\"$artist\"" else "recording:\"$title\" AND artist:\"$artist\""
        val path = if (album != null) "release-group" else "recording"
        val body = get("https://musicbrainz.org/ws/2/$path?query=${enc(q)}&fmt=json&limit=6") ?: return emptyList()
        val o = JSONObject(body)
        val groups = LinkedHashMap<String, String>()
        o.optJSONArray("release-groups")?.let { arr ->
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { groups[it.optString("id")] = it.optString("title") }
        }
        o.optJSONArray("recordings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val rels = arr.getJSONObject(i).optJSONArray("releases") ?: continue
                for (j in 0 until minOf(rels.length(), 3)) {
                    val g = rels.getJSONObject(j).optJSONObject("release-group") ?: continue
                    groups.putIfAbsent(g.optString("id"), rels.getJSONObject(j).optString("title"))
                }
            }
        }
        return groups.entries.filter { it.key.isNotBlank() }.take(6)
            .map { (id, name) -> Cover("https://coverartarchive.org/release-group/$id/front-1200", "Cover Art Archive", name) }
    }

    private fun netease(queries: List<String>): List<Cover> {
        val out = ArrayList<Cover>()
        for (q in queries.take(3)) {
            val body = get("https://music.163.com/api/search/get?s=${enc(q)}&type=10&limit=6&offset=0") ?: continue
            val albums = JSONObject(body).optJSONObject("result")?.optJSONArray("albums") ?: continue
            for (i in 0 until albums.length()) {
                val a = albums.getJSONObject(i)
                a.optString("picUrl").takeIf { it.isNotBlank() }?.let { out += Cover("$it?param=1200y1200", "NetEase", a.optString("name")) }
            }
        }
        return out
    }

    /** VGMdb (through vgmdb.info): albums by name, each album's full-size cover. */
    private fun vgmdb(queries: List<String>): List<Cover> {
        val out = ArrayList<Cover>()
        for (q in queries.distinct().take(2)) {
            val body = get("https://vgmdb.info/search/albums/${enc(q).replace("+", "%20")}?format=json") ?: continue
            val albums = JSONObject(body).optJSONObject("results")?.optJSONArray("albums") ?: continue
            for (i in 0 until minOf(albums.length(), 4)) {
                val link = albums.getJSONObject(i).optString("link").takeIf { it.isNotBlank() } ?: continue
                val detail = get("https://vgmdb.info/$link?format=json")?.let { JSONObject(it) } ?: continue
                val pic = detail.optString("picture_full").ifBlank { detail.optString("picture_small") }
                if (pic.isNotBlank()) out += Cover(pic, "VGMdb", detail.optJSONObject("names")?.optString("en") ?: detail.optString("name"))
            }
        }
        return out
    }

    // ── BitChord Audio: the album's other files, the web, and what each picture is ──

    /** The covers inside (and beside) the other files of the album: each song's own picture. */
    suspend fun fromAlbumFiles(context: Context, mates: List<Song>): List<Cover> = withContext(Dispatchers.IO) {
        val out = ArrayList<Cover>()
        for (m in mates.take(30)) {
            val files = runCatching { filesOf(m) }.getOrDefault(emptyList()).take(1)
            for (f in files) {
                runCatching { RemoteArtworkStore.resolveArt(context, f.copy(thumbnailUrl = null)) }.getOrNull()
                    ?.let { out += Cover(it, "Album file", m.title) }
                f.thumbnailUrl?.let { out += Cover(it, "Album file", m.title) }
            }
            if (files.isEmpty()) m.thumbnailUrl?.let { out += Cover(it.artworkAt(1200) ?: it, "Album song", m.title) }
        }
        out.distinctBy { it.url }
    }

    /**
     * Pictures from a web image search (Bing Images, read as a browser would) for [query]:
     * the full-size picture of each result, with the site it is on.
     */
    suspend fun fromWeb(query: String): List<Cover> = withContext(Dispatchers.IO) {
        val html = runCatching {
            http.newCall(
                Request.Builder().url("https://www.bing.com/images/search?q=${enc(query)}&form=HDRSC2&first=1")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
                    .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                    .build(),
            ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyList()
        Regex("""\bm="(\{[^"]+\})"""").findAll(html).mapNotNull { m ->
            val json = m.groupValues[1].replace("&quot;", "\"").replace("&amp;", "&")
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return@mapNotNull null
            val url = o.optString("murl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val site = o.optString("purl").let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }.orEmpty()
            Cover(url, "Web", site)
        }.distinctBy { it.url }.take(40).toList()
    }

    /** What a picture is: its width × height and its size in bytes (either may be unknown). */
    data class Probe(val width: Int?, val height: Int?, val bytes: Long?)

    /** Reads the start of the picture at [url] — its header holds its size — and its length. */
    suspend fun probe(url: String): Probe? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext null
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", AGENT).header("Range", "bytes=0-65535")
                .apply {
                    if (url.contains("webdav", ignoreCase = true)) {
                        WebDavConfig.basicAuthHeader(AppSettings.webdavUsername.value, AppSettings.webdavPassword.value)?.let { header("Authorization", it) }
                    }
                }.build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.body?.contentLength()?.takeIf { it > 0 && r.code == 200 }
                val head = r.body?.byteStream()?.use { input ->
                    val buf = ByteArray(65536)
                    var n = 0
                    while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                    buf.copyOf(n)
                } ?: ByteArray(0)
                val dims = dimensions(head)
                Probe(dims?.first, dims?.second, total)
            }
        }.getOrNull()
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or ((b[i + 2].toInt() and 0xFF) shl 16)

    /** Width and height from a JPEG, PNG, WebP or GIF header. */
    private fun dimensions(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        // PNG
        if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) {
            val w = (u16(b, 16) shl 16) or u16(b, 18); val h = (u16(b, 20) shl 16) or u16(b, 22)
            return w to h
        }
        // GIF
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) return le16(b, 6) to le16(b, 8)
        // WebP
        if (b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()) {
            val kind = String(b, 12, 4, Charsets.US_ASCII)
            return when (kind) {
                "VP8 " -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
                "VP8L" -> {
                    val v = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                    ((v and 0x3FFF) + 1) to (((v shr 14) and 0x3FFF) + 1)
                }
                "VP8X" -> (le24(b, 24) + 1) to (le24(b, 27) + 1)
                else -> null
            }
        }
        // JPEG: walk the markers to the frame header.
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < b.size) {
                if (b[i] != 0xFF.toByte()) { i++; continue }
                val marker = b[i + 1].toInt() and 0xFF
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xFF) { i++; continue }
                val len = u16(b, i + 2)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    return u16(b, i + 7) to u16(b, i + 5)
                }
                i += 2 + len
            }
        }
        return null
    }
}
