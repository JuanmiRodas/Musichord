package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.playback.QueueBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * BitChord Audio: mixes made from your own listening — the idea of LastWave's
 * generator (Clash-Projects, GPL-3.0), built here on what this app already
 * knows: your listening stats, Liked Music, your YouTube Music history and
 * YouTube Music radios. No Last.fm needed. Songs you said not to recommend
 * ([NoRecommend]) never appear; at most [PER_ARTIST] per artist.
 */
object MixGenerator {

    enum class Mode { MY_MIX, NEVER_HEARD, REDISCOVER, ARTIST }

    const val SIZE = 35
    private const val PER_ARTIST = 2

    /** A finished mix: its name and its songs. */
    data class Mix(val mode: Mode, val title: String, val songs: List<Song>)

    suspend fun generate(mode: Mode, artistQuery: String = "", title: String): Result<Mix> = runCatching {
        val songs = when (mode) {
            Mode.MY_MIX -> myMix()
            Mode.NEVER_HEARD -> neverHeard()
            Mode.REDISCOVER -> rediscover()
            Mode.ARTIST -> artistMix(artistQuery)
        }
        check(songs.isNotEmpty()) { "Nothing found for this mix yet — listen a bit more and try again" }
        Mix(mode, title, songs)
    }

    // ---- the modes ---------------------------------------------------------

    /** Your favourites (a third) among songs like them from their radios. */
    private suspend fun myMix(): List<Song> {
        val favourites = (topSongs(ReplayPeriod.THIS_MONTH) + topSongs(ReplayPeriod.ALL_TIME) + liked().shuffled().take(20))
            .distinctBy { it.videoId }
        if (favourites.isEmpty()) return emptyList()
        val seeds = favourites.shuffled().take(8)
        val radio = radios(seeds)
        val picks = favourites.shuffled().take(SIZE / 3)
        return capped((picks + radio).shuffled(), emptyList())
    }

    /** Only songs you have never played or liked, from the radios of your favourites. */
    private suspend fun neverHeard(): List<Song> {
        val known = known()
        val seeds = (topSongs(ReplayPeriod.ALL_TIME).take(15) + liked().shuffled().take(15)).distinctBy { it.videoId }
        if (seeds.isEmpty()) return emptyList()
        val radio = radios(seeds.shuffled().take(12))
        return capped(radio.shuffled(), known)
    }

    /** Liked songs you haven't played this year. */
    private suspend fun rediscover(): List<Song> {
        return capped(liked().shuffled(), known = topSongs(ReplayPeriod.THIS_YEAR, 2_000))
    }

    /** An artist's own songs and the ones their radio pairs them with. */
    private suspend fun artistMix(query: String): List<Song> {
        val q = query.trim()
        require(q.isNotEmpty()) { "Type an artist" }
        val found = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty()
            .filterIsInstance<SearchResult.Track>().map { it.song }
        val own = found.filter { it.artist.contains(q, ignoreCase = true) }.ifEmpty { found }.take(8)
        if (own.isEmpty()) return emptyList()
        val radio = radios(own.take(4))
        return capped(own.take(6) + radio.shuffled(), emptyList(), perArtist = 6)
    }

    // ---- helpers -------------------------------------------------------------

    private suspend fun topSongs(period: ReplayPeriod, limit: Int = 40): List<Song> =
        runCatching { ListeningStats.summary(period).songs.take(limit).map { it.song } }.getOrDefault(emptyList())

    private fun liked(): List<Song> =
        LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty().filterNot { it.isVideo }

    /** Everything already heard or liked: stats, Liked Music, YouTube Music history. */
    private suspend fun known(): List<Song> =
        topSongs(ReplayPeriod.ALL_TIME, 5_000) + liked() + YtMusicRepository.recentHistory().getOrNull().orEmpty()

    /** The radios of [seeds], in parallel. Only YouTube ids seed a radio. */
    private suspend fun radios(seeds: List<Song>): List<Song> = coroutineScope {
        seeds.map { seed ->
            async {
                val id = runCatching { com.music.bitchord.playback.youtubeSeedFor(seed) }.getOrNull()
                    ?: return@async emptyList()
                if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return@async emptyList()
                YtMusicRepository.radio(id).getOrNull().orEmpty().drop(1).take(15)
            }
        }.awaitAll().flatten()
    }

    /** [songs] in order, without repeats, excluded or [known] songs, [perArtist] each. */
    private fun capped(songs: List<Song>, known: List<Song>, perArtist: Int = PER_ARTIST): List<Song> {
        // Indexed once: ids, and titles with their artists, so thousands of
        // known songs cost a lookup rather than a comparison each.
        val knownIds = known.mapTo(HashSet()) { it.videoId }
        val knownTitles = HashMap<String, MutableList<Set<String>>>()
        known.forEach { k ->
            knownTitles.getOrPut(QueueBuilder.normalisedTitle(k.title)) { ArrayList() } += QueueBuilder.artistSet(k.artist)
        }
        fun isKnown(s: Song): Boolean {
            if (s.videoId in knownIds) return true
            val artists = knownTitles[QueueBuilder.normalisedTitle(s.title)] ?: return false
            val mine = QueueBuilder.artistSet(s.artist)
            return artists.any { it.isEmpty() || mine.isEmpty() || it.any(mine::contains) }
        }
        val out = ArrayList<Song>()
        val perArtistCount = HashMap<String, Int>()
        for (s in songs) {
            if (out.size >= SIZE) break
            if (s.isVideo || NoRecommend.isExcluded(s)) continue
            if (out.any { QueueBuilder.isSameRecording(it, s) }) continue
            if (isKnown(s)) continue
            val artist = s.artist.substringBefore(',').trim().lowercase()
            val n = perArtistCount[artist] ?: 0
            if (n >= perArtist) continue
            perArtistCount[artist] = n + 1
            out += s
        }
        return out
    }
}
