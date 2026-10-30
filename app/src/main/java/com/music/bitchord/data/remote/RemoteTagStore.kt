package com.music.bitchord.data.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: the tags already read from remote files, kept on the device.
 *
 * Keyed by the file's URL and checked against its size and modification date,
 * so an edited file is read again and an unchanged one never is. A file whose
 * head held no readable tags is stored as such ([RemoteTags.Tags] with nothing
 * set) so it is not fetched again on every visit either.
 */
object RemoteTagStore {

    data class Stamp(val size: Long?, val modified: String?)

    private data class Entry(val stamp: Stamp, val tags: RemoteTags.Tags)

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var file: File? = null

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        file = File(context.filesDir, "remote_tags.json")
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = file?.takeIf { it.isFile } ?: return@runCatching
                val root = JSONObject(f.readText())
                root.keys().forEach { url ->
                    val o = root.getJSONObject(url)
                    entries[url] = Entry(
                        Stamp(o.optLong("size", -1).takeIf { it >= 0 }, o.optString("mod").ifBlank { null }),
                        tagsFrom(o.getJSONObject("t")),
                    )
                }
            }
            loaded = true
        }
    }

    /** The tags for [url] if they were read from this same version of the file. */
    fun get(url: String, stamp: Stamp): RemoteTags.Tags? {
        ensureLoaded()
        val e = entries[url] ?: return null
        return e.tags.takeIf { e.stamp == stamp || (stamp.size == null && stamp.modified == null) }
    }

    fun put(url: String, stamp: Stamp, tags: RemoteTags.Tags) {
        ensureLoaded()
        entries[url] = Entry(stamp, tags)
    }

    /** Writes the store out; called once a batch of reads is done, not per file. */
    @Synchronized
    fun flush() {
        val f = file ?: return
        runCatching {
            val root = JSONObject()
            entries.forEach { (url, e) ->
                root.put(
                    url,
                    JSONObject().apply {
                        e.stamp.size?.let { put("size", it) }
                        e.stamp.modified?.let { put("mod", it) }
                        put("t", json(e.tags))
                    },
                )
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun json(t: RemoteTags.Tags) = JSONObject().apply {
        t.title?.let { put("title", it) }
        t.artist?.let { put("artist", it) }
        t.album?.let { put("album", it) }
        t.albumArtist?.let { put("albumArtist", it) }
        t.trackNumber?.let { put("track", it) }
        t.durationMs?.let { put("dur", it) }
        t.sampleRate?.let { put("rate", it) }
        t.bitDepth?.let { put("depth", it) }
        if (t.lossless) put("lossless", true)
        t.codec?.let { put("codec", it) }
        if (t.altTitles.isNotEmpty()) put("altTitles", JSONArray(t.altTitles))
        if (t.altArtists.isNotEmpty()) put("altArtists", JSONArray(t.altArtists))
    }

    private fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.int(k: String) = if (has(k)) getInt(k) else null
    private fun JSONObject.strings(k: String) =
        optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private fun tagsFrom(o: JSONObject) = RemoteTags.Tags(
        title = o.str("title"),
        artist = o.str("artist"),
        album = o.str("album"),
        albumArtist = o.str("albumArtist"),
        trackNumber = o.int("track"),
        durationMs = if (o.has("dur")) o.getLong("dur") else null,
        sampleRate = o.int("rate"),
        bitDepth = o.int("depth"),
        lossless = o.optBoolean("lossless", false),
        codec = o.str("codec"),
        altTitles = o.strings("altTitles"),
        altArtists = o.strings("altArtists"),
    )
}
