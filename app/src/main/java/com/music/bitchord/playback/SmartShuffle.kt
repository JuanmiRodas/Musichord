package com.music.bitchord.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.OwnLibrary
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * BitChord Audio: smart shuffle — the shuffle button's second press, as on Spotify.
 *
 * While on, the shuffled queue:
 *  - leaves out soundtracks (anime, games, films, series), instrumentals, Yuki Kajiura's
 *    scores (her songs with a singer stay), and a second version of a song already heard;
 *  - never plays the same artist twice running;
 *  - takes in a suggestion every few songs — from your Supermix, radios of what just
 *    played, other songs of artists you like and your own files the list doesn't have
 *    (in "Liked music"), or radios in the list's style (any other list) — picked to suit
 *    the last two songs' language;
 *  - learns: a suggestion skipped in its first 30 s doesn't come back (twice for an
 *    artist: that artist rests), and suggestions come less often; one heard to the end
 *    or liked brings them a little closer.
 * Suggestions are marked ("Smart shuffle" as where they play from) and leave the queue
 * when smart shuffle is turned off.
 */
object SmartShuffle {
    private const val TAG = "SmartShuffle"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefs: android.content.SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Shown as where a suggestion plays from. */
    @Volatile var suggestionLabel: String = "Smart shuffle"

    /** Songs between suggestions: 3 (they're welcome) to 8 (they're skipped). */
    private var gap = 4f
    private var sinceSuggestion = 0
    private const val AHEAD = 6
    private val inserted = LinkedHashSet<String>()
    private val _suggested = MutableStateFlow<Set<String>>(emptySet())
    /** The queue entries put there as suggestions (the queue marks them). */
    val suggested: StateFlow<Set<String>> = _suggested.asStateFlow()
    private fun published() { _suggested.value = inserted.toSet() }
    private val pool = ArrayDeque<Song>()
    private val blocked = HashSet<String>()
    private val restingArtists = HashMap<String, Int>()
    private val heard = HashSet<String>()
    private var refilling: Job? = null

    private var current: MediaItem? = null
    private var currentSince = 0L

    fun init(context: Context) {
        val p = context.getSharedPreferences("smart_shuffle", Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean("on", false)
        gap = p.getFloat("gap", 4f)
        blocked += p.getStringSet("blocked", emptySet()).orEmpty()
    }

    private fun save() {
        prefs?.edit()?.putBoolean("on", _enabled.value)?.putFloat("gap", gap)?.putStringSet("blocked", blocked.toSet())?.apply()
    }

    fun setEnabled(player: Player?, on: Boolean) {
        _enabled.value = on
        save()
        if (on) {
            heard.clear()
            sinceSuggestion = (gap - 1).toInt()
            player?.let { refill(it) }
            TrackLog.d(TAG, "smart shuffle on (a suggestion every ~${gap.toInt()} songs)")
        } else {
            player?.let(::removeSuggestions)
            pool.clear()
            TrackLog.d(TAG, "smart shuffle off")
        }
    }

    private fun removeSuggestions(player: Player) {
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == player.currentMediaItemIndex) continue
            val item = player.getMediaItemAt(i)
            if (entryOf(item) in inserted) player.removeMediaItem(i)
        }
        inserted.clear()
        published()
    }

    private fun entryOf(item: MediaItem): String = item.queueEntryId ?: item.mediaId

    private fun isSuggestion(item: MediaItem?) = item != null && entryOf(item) in inserted

    // ---- What is left out --------------------------------------------------------------

    private val SOUNDTRACK = Regex(
        "(?i)\\bo\\.?s\\.?t\\.?\\b|soundtrack|sound track|original (motion picture|score|television|game)|\\bscore\\b|" +
            "\\bbgm\\b|game music|music from|bande originale|banda sonora|colonna sonora|" +
            "サウンドトラック|サントラ|オリジナル・?サウンド|劇伴|原声|配乐|オリジナルスコア",
    )
    private val INSTRUMENTAL = Regex(
        "(?i)instrumental|\\binst\\.?\\b|\\(inst\\)|off ?vocal|karaoke|backing track|\\bbgm\\b|" +
            "インスト|オフボーカル|カラオケ|伴奏|純音樂|纯音乐",
    )
    private val KAJIURA = Regex("(?i)yuki kajiura|梶浦由記|梶浦 由記")

    private fun artistsOf(song: Song) = song.artist.split(Regex(",|&|、| feat\\.? | ft\\.? | x | × ", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() }

    fun excluded(song: Song): Boolean {
        val album = song.albumName.orEmpty()
        if (SOUNDTRACK.containsMatchIn(album) || SOUNDTRACK.containsMatchIn(song.title)) return true
        if (INSTRUMENTAL.containsMatchIn(song.title)) return true
        // Yuki Kajiura alone is a score; with a singer credited it's a song.
        val artists = artistsOf(song)
        if (artists.isNotEmpty() && artists.all { KAJIURA.containsMatchIn(it) }) return true
        return false
    }

    private fun versionKey(song: Song): String {
        val base = song.title
            .replace(Regex("\\s*[(\\[（【].*?[)\\]）】]"), "")
            .replace(Regex("(?i)\\s+[-–—~]\\s+.*$"), "")
            .lowercase().filter { it.isLetterOrDigit() }
        return base + "|" + artistsOf(song).firstOrNull().orEmpty().lowercase().filter { it.isLetterOrDigit() }
    }

    private fun artistKey(song: Song) = artistsOf(song).firstOrNull().orEmpty().lowercase().filter { it.isLetterOrDigit() }

    // ---- The queue moving on -------------------------------------------------------------

    /** The player moved to [item]: what smart shuffle does about it. */
    fun onTransition(player: Player, item: MediaItem?) {
        val previous = current
        val heardFor = System.currentTimeMillis() - currentSince
        current = item
        currentSince = System.currentTimeMillis()
        if (!_enabled.value || item == null) return
        // Learning from the last suggestion.
        if (previous != null && isSuggestion(previous)) {
            val prev = previous.toSong()
            if (heardFor < 30_000) {
                blocked += prev.videoId
                val a = artistKey(prev)
                val skips = (restingArtists[a] ?: 0) + 1
                restingArtists[a] = skips
                gap = (gap + 1f).coerceAtMost(8f)
                TrackLog.d(TAG, "suggestion '${prev.title}' skipped; next in ~${gap.toInt()}${if (skips >= 2) ", '${prev.artist}' rests" else ""}")
            } else {
                gap = (gap - 0.5f).coerceAtLeast(3f)
            }
            save()
        }
        val song = item.toSong()
        // Left out: skipped straight on.
        val reason = when {
            isSuggestion(item) -> null
            excluded(song) -> "soundtrack/instrumental"
            versionKey(song) in heard -> "another version already heard"
            else -> null
        }
        if (reason != null && player.hasNextMediaItem()) {
            TrackLog.d(TAG, "skipping '${song.title}' ($reason)")
            scope.launch { player.seekToNextMediaItem() }
            return
        }
        heard += versionKey(song)
        // The same artist next: something else brought forward.
        avoidSameArtistNext(player, song)
        // A suggestion due?
        if (!isSuggestion(item)) sinceSuggestion++ else sinceSuggestion = 0
        sprinkle(player)
        if (pool.size < 3) refill(player)
    }

    /** A star on a suggestion: they come a little sooner. */
    fun onLiked(videoId: String) {
        if (inserted.isEmpty()) return
        gap = (gap - 0.5f).coerceAtLeast(3f)
        save()
    }

    private fun avoidSameArtistNext(player: Player, now: Song) {
        val idx = player.currentMediaItemIndex
        if (idx + 1 >= player.mediaItemCount) return
        val a = artistKey(now)
        if (a.isEmpty() || artistKey(player.getMediaItemAt(idx + 1).toSong()) != a) return
        val swap = (idx + 2 until minOf(player.mediaItemCount, idx + 40)).firstOrNull {
            artistKey(player.getMediaItemAt(it).toSong()) != a
        } ?: return
        player.moveMediaItem(swap, idx + 1)
    }

    private fun scriptOf(s: Song): String {
        val t = s.title + s.artist
        return when {
            t.any { Character.UnicodeScript.of(it.code) in setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) } -> "ja"
            t.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL } -> "ko"
            t.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> "zh"
            else -> "latin"
        }
    }

    /** Suggestions kept spread through the songs ahead, one every ~[gap] songs, [AHEAD] of them. */
    private fun sprinkle(player: Player) {
        if (!_enabled.value || pool.isEmpty()) return
        val idx = player.currentMediaItemIndex
        if (idx < 0) return
        val g = gap.toInt().coerceAtLeast(2)
        var ahead = (idx + 1 until player.mediaItemCount).count { isSuggestion(player.getMediaItemAt(it)) }
        val last = (idx + 1 until player.mediaItemCount).lastOrNull { isSuggestion(player.getMediaItemAt(it)) }
        var at = if (last != null) last + 1 + g else idx + 1 + (g - 1 - sinceSuggestion).coerceAtLeast(0)
        var added = 0
        while (ahead < AHEAD && pool.isNotEmpty() && at <= player.mediaItemCount) {
            if (!insertAt(player, at)) break
            ahead++; added++
            at += g + 1
        }
        if (added > 0) TrackLog.d(TAG, "$added suggestions spread through the queue (one every ~$g songs)")
    }

    private fun insertAt(player: Player, at: Int): Boolean {
        val before = runCatching { player.getMediaItemAt(at - 1).toSong() }.getOrNull()
        val after = if (at < player.mediaItemCount) runCatching { player.getMediaItemAt(at).toSong() }.getOrNull() else null
        val mood = before?.let(::scriptOf)
        val near = setOfNotNull(before?.let(::artistKey), after?.let(::artistKey))
        val pick = pool.firstOrNull { scriptOf(it) == mood && artistKey(it) !in near }
            ?: pool.firstOrNull { artistKey(it) !in near }
            ?: return false
        pool.remove(pick)
        val entry = UUID.randomUUID().toString()
        val song = pick.copy(queueEntryId = entry, queueTier = QueueTier.CONTEXT, radioName = suggestionLabel)
        inserted += entry
        published()
        player.addMediaItem(at, song.toMediaItem())
        return true
    }

    // ---- Where suggestions come from -------------------------------------------------------

    private fun ok(s: Song, inList: Set<String>): Boolean {
        if (s.videoId in blocked || s.videoId in inList || excluded(s)) return false
        if ((restingArtists[artistKey(s)] ?: 0) >= 2) return false
        if (LocalLikes.liked.value.containsKey(s.videoId)) return false
        return pool.none { it.videoId == s.videoId }
    }

    private fun refill(player: Player) {
        if (refilling?.isActive == true) return
        val idx = player.currentMediaItemIndex
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val inList = queue.map { it.mediaId }.toHashSet()
        val now = player.currentMediaItem?.toSong()
        val recent = (maxOf(0, idx - 2)..idx).mapNotNull { runCatching { player.getMediaItemAt(it).toSong() }.getOrNull() }
        val liked = now?.playbackSourceId?.let { id -> id in LikedMusicCache.IDS || id == "liked" } == true ||
            now?.playbackSourceId == null && inList.size > 100
        refilling = scope.launch {
            val found = withContext(Dispatchers.IO) {
                val out = ArrayList<Song>()
                if (liked) {
                    // Your Supermix: the songs it has that you haven't liked.
                    runCatching {
                        val mix = YtMusicRepository.mixedForYou().getOrNull().orEmpty()
                            .firstOrNull { it.title.contains("supermix", true) } ?: YtMusicRepository.mixedForYou().getOrNull().orEmpty()
                            .firstOrNull { it.browseId?.removePrefix("VL")?.startsWith("RDTMAK") == true }
                        mix?.browseId?.let { b -> YtMusicRepository.browseSongs(if (b.startsWith("VL")) b else "VL$b").getOrNull()?.songs }
                    }.getOrNull().orEmpty().shuffled().take(12).let(out::addAll)
                    // Other songs by artists you like.
                    recent.lastOrNull()?.let { s ->
                        runCatching { OwnLibrary.songsBy(artistsOf(s).first()) }.getOrDefault(emptyList()).shuffled().take(3).let(out::addAll)
                    }
                    // Your own files the list doesn't have.
                    OwnLibrary.uploads.value.shuffled().take(6).let(out::addAll)
                }
                // Radios of what just played (in any list: the list's own style).
                for (s in recent.takeLast(2)) {
                    val seed = LocalLikes.youTubeOf(s.videoId) ?: s.videoId.takeIf { LocalLikes.isYouTubeId(it) } ?: continue
                    runCatching { YtMusicRepository.radio(seed).getOrNull().orEmpty() }.getOrDefault(emptyList())
                        .drop(1).take(10).let(out::addAll)
                }
                out
            }
            found.shuffled().filter { ok(it, inList) }.distinctBy { it.videoId }.forEach { pool.addLast(it) }
            while (pool.size > 30) pool.removeFirst()
            sprinkle(player)
            TrackLog.d(TAG, "suggestions ready: ${pool.size} (${if (liked) "liked music: Supermix, radios, artists, your files" else "radios in the list's style"})")
        }
    }
}
