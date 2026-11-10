package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * BitChord Audio: songs you never want suggested (from LastWave's "excluded
 * songs"). Radios, AutoPlay, Top Picks and generated mixes leave them out;
 * playing one yourself still works.
 *
 * Matched by id and by recording — normalised title plus a shared artist —
 * so the music video, your pCloud file or a JioSaavn copy of the same song
 * is left out too.
 */
object NoRecommend {

    @Serializable
    data class Entry(val videoId: String, val title: String, val artist: String, val addedAt: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    private var file: File? = null
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "bitchord_no_recommend.json")
        file = f
        _entries.value = runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyList())
    }

    fun add(song: Song) {
        if (isExcluded(song)) return
        _entries.value = _entries.value + Entry(song.videoId, song.title, song.artist, System.currentTimeMillis())
        save()
    }

    fun remove(videoId: String) {
        _entries.value = _entries.value.filterNot { it.videoId == videoId }
        save()
    }

    fun clear() {
        _entries.value = emptyList()
        save()
    }

    fun isExcluded(song: Song): Boolean {
        val list = _entries.value
        if (list.isEmpty()) return false
        return list.any { e ->
            e.videoId == song.videoId ||
                QueueBuilder.isSameRecording(Song(videoId = e.videoId, title = e.title, artist = e.artist, thumbnailUrl = null), song)
        }
    }

    /** [songs] without the excluded ones. */
    fun filter(songs: List<Song>): List<Song> =
        if (_entries.value.isEmpty()) songs else songs.filterNot(::isExcluded)

    private fun save() {
        val f = file ?: return
        runCatching { f.writeText(json.encodeToString(serializer, _entries.value)) }
    }
}
