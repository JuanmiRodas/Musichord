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

    private fun get(url: String): String? = runCatching {
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
    suspend fun find(context: Context, song: Song): List<Cover> = withContext(Dispatchers.IO) {
        val names = runCatching { JapaneseNames.of(song) }.getOrDefault(JapaneseNames.Names.NONE)
        val artist = song.artist.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).first().trim()
        val album = song.albumName?.takeIf { it.isNotBlank() }
        val title = song.title.replace(Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]"""), " ").trim()
        val jaArtist = names.artists.firstOrNull()
        val jaTitle = names.titles.firstOrNull()
        val albumQueries = listOfNotNull(album?.let { "$it $artist" }, album?.let { a -> jaArtist?.let { "$a $it" } }).distinct()
        val songQueries = listOfNotNull("$title $artist", jaTitle?.let { "$it ${jaArtist ?: artist}" }).distinct()
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
}
