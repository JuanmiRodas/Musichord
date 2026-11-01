package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.lyrics.LyricAliases
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.playback.OriginalVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * BitChord Audio: one library out of every place your music is — pCloud
 * (and other WebDAV folders), files on the phone, and your YouTube Music
 * library and Liked Music — with the same song from several places shown once.
 *
 * **Same song.** Title and artist compared as letters and digits only, any
 * script ([FileName.key]); each title is also tried under its other names
 * (romaji / original, from tags, file names and your romaji list — see
 * [LyricAliases]) and without its version note ("(Live)" stays a different
 * recording only when both lengths say so). Two copies more than
 * [SAME_LENGTH_MS] apart in length are different recordings.
 *
 * **Which copy plays.** Your choice for that song ([choose], from "Play
 * from…" in its menu), else [AppSettings.sourcePreference]: best quality
 * (lossless > your other files > YouTube), your files first, or YouTube
 * first. Choosing YouTube also pins the song to YouTube everywhere else
 * ([OriginalVersion]), so the source walk doesn't swap your file back in.
 */
object MergedLibrary {

    const val BROWSE_ID = "local:merged"

    enum class Origin(val label: String) { PCLOUD("pCloud"), DEVICE("Phone"), YOUTUBE("YouTube Music") }

    data class Version(val song: Song, val origin: Origin)

    data class Entry(val key: String, val versions: List<Version>, val chosen: Version)

    private const val SAME_LENGTH_MS = 6_000L
    private const val PREFS = "bitchord_merged_choices"

    private var prefs: SharedPreferences? = null

    /** Every song id on the page → its merged entry, from the last build. */
    private val byId = ConcurrentHashMap<String, Entry>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** The other copies of [song] the library knows, itself included; empty if none. */
    fun versionsOf(song: Song): List<Version> = byId[song.videoId]?.versions.orEmpty()

    /** Remembers which copy of this song plays, for good (synced with your settings). */
    fun choose(song: Song, origin: Origin?) {
        val entry = byId[song.videoId] ?: return
        val edit = prefs?.edit() ?: return
        if (origin == null) edit.remove(entry.key) else edit.putString(entry.key, origin.name)
        edit.apply()
        // YouTube chosen: keep the stream walk from swapping a file back in.
        entry.versions.filter { it.origin == Origin.YOUTUBE }.forEach { yt ->
            if (origin == Origin.YOUTUBE) OriginalVersion.pin(yt.song.videoId) else OriginalVersion.unpin(yt.song.videoId)
        }
    }

    fun choiceFor(song: Song): Origin? {
        val entry = byId[song.videoId] ?: return null
        return prefs?.getString(entry.key, null)?.let { n -> Origin.entries.firstOrNull { it.name == n } }
    }

    /** The merged library: one row per song, each the copy that plays. */
    suspend fun songs(context: Context, youtube: List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val pcloud = if (WebDavRepository.isConfigured()) WebDavRepository.library() else emptyList()
        val device = runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())
        val all = pcloud.map { Version(it, Origin.PCLOUD) } +
            device.map { Version(it, Origin.DEVICE) } +
            youtube.distinctBy { it.videoId }.map { Version(it, Origin.YOUTUBE) }
        val entries = merge(all)
        byId.clear()
        entries.forEach { e -> e.versions.forEach { v -> byId[v.song.videoId] = e } }
        entries.map { it.chosen.song }.sortedWith(compareBy({ FileName.key(it.artist) }, { FileName.key(it.title) }))
    }

    private fun merge(all: List<Version>): List<Entry> {
        // Groups by each name a song goes by; a version joins the first group
        // that one of its names and its length agree with.
        val groups = ArrayList<MutableList<Version>>()
        val index = HashMap<String, MutableList<Int>>()
        for (v in all) {
            val keys = keysOf(v.song)
            val length = v.song.durationMillis().takeIf { it > 0 }
            val target = keys.asSequence()
                .flatMap { index[it].orEmpty().asSequence() }
                .firstOrNull { g ->
                    // One copy per place: two files of yours under one name
                    // (a live take, a remaster) are two songs, never one row.
                    groups[g].none { it.origin == v.origin } &&
                        groups[g].all { other ->
                            val o = other.song.durationMillis().takeIf { it > 0 }
                            length == null || o == null || abs(length - o) <= SAME_LENGTH_MS
                        }
                }
            val g = target ?: groups.size.also { groups.add(mutableListOf()) }
            groups[g] += v
            keys.forEach { k -> index.getOrPut(k) { mutableListOf() }.let { if (g !in it) it += g } }
        }
        return groups.map { versions ->
            val key = keysOf(versions.first().song).first()
            Entry(key, versions, pick(key, versions))
        }
    }

    private fun keysOf(song: Song): List<String> {
        val artist = FileName.key(song.artist.split(ARTIST_SPLIT).firstOrNull().orEmpty())
        val titles = LyricAliases.queries(song.videoId, song.title, song.artist).map { it.title } + song.title
        return titles.flatMap { listOf(FileName.key(it), FileName.key(it.replace(QUALIFIER, " "))) }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { "$it|$artist" }
            .ifEmpty { listOf("id|${song.videoId}") }
    }

    private fun pick(key: String, versions: List<Version>): Version {
        prefs?.getString(key, null)?.let { n ->
            versions.firstOrNull { it.origin.name == n }?.let { return it }
        }
        // The pick from the player's source line.
        when (val c = com.music.bitchord.data.sources.PreferredSource.get(versions.first().song)) {
            is com.music.bitchord.data.sources.PreferredSource.File ->
                versions.firstOrNull { it.song.videoId == c.id }?.let { return it }
            is com.music.bitchord.data.sources.PreferredSource.Chosen -> Unit
            com.music.bitchord.data.sources.PreferredSource.YouTube ->
                versions.firstOrNull { it.origin == Origin.YOUTUBE }?.let { return it }
            null -> Unit
        }
        val order: (Version) -> Int = when (AppSettings.sourcePreference.value) {
            AppSettings.SOURCE_YOUTUBE_FIRST -> { v -> if (v.origin == Origin.YOUTUBE) 0 else 1 + rank(v) }
            AppSettings.SOURCE_OWN_FIRST -> { v -> if (v.origin == Origin.YOUTUBE) 10 else rank(v) }
            else -> ::rank
        }
        return versions.minByOrNull(order) ?: versions.first()
    }

    private val KBPS = Regex("""(\d{2,4})\s*KBPS""")

    /** Best quality first: your lossless or 256+ kbps files, then YouTube, then your smaller files. */
    private fun rank(v: Version): Int {
        val q = v.song.sourceQuality.orEmpty().uppercase()
        val lossless = v.song.sourceHiRes || listOf("FLAC", "ALAC", "WAV", "AIFF").any { it in q }
        val kbps = KBPS.find(q)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            v.origin != Origin.YOUTUBE && lossless -> 0
            v.origin != Origin.YOUTUBE && kbps != null &&
                kbps >= com.music.bitchord.data.sources.RemoteLibrarySource.MIN_KBPS -> 1
            v.origin == Origin.YOUTUBE -> 2
            v.origin != Origin.YOUTUBE && kbps == null -> 3
            else -> 4
        }
    }

    fun isOwnFile(song: Song): Boolean = WebDavConfig.isWebDavId(song.videoId) || song.localUri != null

    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)
    private val QUALIFIER = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")
}
