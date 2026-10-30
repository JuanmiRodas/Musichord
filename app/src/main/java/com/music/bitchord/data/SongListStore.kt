package com.music.bitchord.data

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: a list of [Song]s saved to a file and read back — the shared
 * storage behind [LikedMusicCache] and the saved remote libraries.
 *
 * [Song] is not serializable upstream, so the fields worth keeping are written
 * by hand. Queue placement and anything that only means something while a
 * track is playing are left out on purpose.
 */
object SongListStore {

    data class Saved(val songs: List<Song>, val savedAt: Long)

    fun load(file: File): Saved? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("songs")
        val songs = (0 until array.length()).map { songFrom(array.getJSONObject(it)) }
        Saved(songs, root.optLong("savedAt", 0L))
    }.getOrNull()

    fun save(file: File, songs: List<Song>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            songs.forEach { array.put(json(it)) }
            val root = JSONObject().put("savedAt", System.currentTimeMillis()).put("songs", array)
            // Written aside and renamed, so a process killed mid-write keeps the old list.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun json(s: Song): JSONObject = JSONObject().apply {
        put("videoId", s.videoId)
        put("title", s.title)
        put("artist", s.artist)
        s.thumbnailUrl?.let { put("thumbnailUrl", it) }
        s.durationText?.let { put("durationText", it) }
        s.artistId?.let { put("artistId", it) }
        s.albumId?.let { put("albumId", it) }
        s.albumName?.let { put("albumName", it) }
        if (s.isVideo) put("isVideo", true)
        s.setVideoId?.let { put("setVideoId", it) }
        s.isExplicit?.let { put("isExplicit", it) }
        s.localUri?.let { put("localUri", it) }
        s.localPath?.let { put("localPath", it) }
        s.trackNumber?.let { put("trackNumber", it) }
        s.sourceQuality?.let { put("sourceQuality", it) }
        if (s.sourceHiRes) put("sourceHiRes", true)
        s.playbackSource?.let { put("playbackSource", it) }
        s.playbackSourceType?.let { put("playbackSourceType", it.name) }
        s.playbackSourceId?.let { put("playbackSourceId", it) }
        if (s.artists.isNotEmpty()) {
            put(
                "artists",
                JSONArray().apply {
                    s.artists.forEach { a ->
                        put(JSONObject().put("name", a.name).apply { a.browseId?.let { put("browseId", it) } })
                    }
                },
            )
        }
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun songFrom(o: JSONObject): Song {
        val artists = o.optJSONArray("artists")?.let { a ->
            (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                ArtistRef(name = x.getString("name"), browseId = x.str("browseId"))
            }
        }.orEmpty()
        return Song(
            videoId = o.getString("videoId"),
            title = o.getString("title"),
            artist = o.getString("artist"),
            thumbnailUrl = o.str("thumbnailUrl"),
            durationText = o.str("durationText"),
            artistId = o.str("artistId"),
            artists = artists,
            albumId = o.str("albumId"),
            albumName = o.str("albumName"),
            isVideo = o.optBoolean("isVideo", false),
            setVideoId = o.str("setVideoId"),
            isExplicit = if (o.has("isExplicit")) o.getBoolean("isExplicit") else null,
            localUri = o.str("localUri"),
            localPath = o.str("localPath"),
            trackNumber = if (o.has("trackNumber")) o.getInt("trackNumber") else null,
            sourceQuality = o.str("sourceQuality"),
            sourceHiRes = o.optBoolean("sourceHiRes", false),
            playbackSource = o.str("playbackSource"),
            playbackSourceType = o.str("playbackSourceType")?.let { n ->
                PlaybackSourceType.entries.firstOrNull { it.name == n }
            },
            playbackSourceId = o.str("playbackSourceId"),
        )
    }
}
