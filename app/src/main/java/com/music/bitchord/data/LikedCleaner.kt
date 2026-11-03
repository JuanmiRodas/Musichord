package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.JapaneseReading
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.webdav.WebDavConfig
import java.util.Locale

/**
 * BitChord Audio: the Liked Music cleaner's logic — which liked songs are the
 * same song twice (or more), which only look alike, and which copy of each is
 * the best one to keep. Nothing here changes anything; the dialog un-likes
 * what you select.
 *
 * The same song: the same title (in any spelling — romaji or Japanese, accents
 * or none, with or without "(Remastered)"), the same artist (any name order,
 * linked names), lengths within the tolerance you set, and — unless you say
 * versions count as the same — the same kind of version (a live take, a
 * remix, an acoustic or karaoke version is a song of its own).
 */
object LikedCleaner {

    data class Item(
        val song: Song,
        /** Position in Liked Music: 0 is the most recently liked. */
        val position: Int,
        /** Best copy's quality: lossless 9000+, else kbps (YouTube ~128). */
        val rank: Int,
        /** "pCloud FLAC", "JioSaavn 320", "YouTube" — where the best copy is. */
        val quality: String,
        val seconds: Int?,
        val version: String,
    ) {
        val id: String get() = song.videoId
    }

    /** Every liked song: YouTube's Liked Music, then the ones liked only here. */
    fun items(): List<Item> {
        val yt = LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty()
        val local = LocalLikes.liked.value.values.filter { s -> yt.none { it.videoId == s.videoId } }
        return (yt + local).mapIndexed { i, s ->
            val (rank, label) = quality(s)
            Item(s, i, rank, label, seconds(s.durationText), versionOf(s.title))
        }
    }

    private fun quality(s: Song): Pair<Int, String> {
        if (WebDavConfig.isWebDavId(s.videoId)) {
            val r = OwnFileMatcher.qualityRank(s)
            return r to if (r >= 9_000) "pCloud lossless" else if (r > 0) "pCloud $r kbps" else "pCloud"
        }
        if (SourceRegistry.parseTrackKey(s.videoId) != null) return SourceMap.SAAVN_KBPS to "JioSaavn ${SourceMap.SAAVN_KBPS}"
        val e = SourceMap.entry(s.videoId)
        val file = when {
            e?.file == null -> 0
            e.lossless -> 9_000
            else -> e.kbps
        }
        val saavn = if (e?.saavn != null) SourceMap.SAAVN_KBPS else 0
        return when {
            file >= 9_000 -> 9_000 to "pCloud lossless"
            file > 0 && file >= saavn && file > YOUTUBE_KBPS -> file to "pCloud $file kbps"
            saavn > 0 -> saavn to "JioSaavn $saavn"
            else -> YOUTUBE_KBPS to "YouTube"
        }
    }

    const val YOUTUBE_KBPS = 128

    private val NOTE = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
    private val DASH_NOTE = Regex("""\s+[-–—]\s+.*$""")
    private val GUEST = Regex("""\s+(feat\.?|ft\.?|featuring|with)\s.*$""", RegexOption.IGNORE_CASE)
    private val VERSION_WORDS = listOf(
        "live", "en vivo", "directo", "acoustic", "acústic", "unplugged", "remix", "mix", "edit", "karaoke",
        "instrumental", "inst", "off vocal", "cover", "demo", "tv size", "short", "orchestra", "piano",
        "sped up", "slowed", "ライブ", "カラオケ", "リミックス", "インスト",
    )

    /** The kind of version a title says it is ("live", "remix" …), or "" for the song itself. */
    fun versionOf(title: String): String {
        val notes = (NOTE.findAll(title).map { it.value } + listOfNotNull(DASH_NOTE.find(title)?.value))
            .joinToString(" ").lowercase(Locale.ROOT)
        return VERSION_WORDS.filter { w -> Regex("""(^|[^\p{L}])${Regex.escape(w)}([^\p{L}]|$)""").containsMatchIn(notes) }
            .sorted().joinToString(",")
    }

    /** The keys a title is known by: as written (notes off) and as it sounds. */
    private fun keys(title: String): Set<String> {
        val bare = title.replace(NOTE, " ").replace(DASH_NOTE, "").replace(GUEST, "").trim().ifBlank { title }
        val plain = OwnFileMatcher.key(bare)
        val sound = JapaneseReading.romaji(bare)?.let(JapaneseReading::soundKey)?.takeIf { it.length >= 4 }?.let { "~$it" }
        return setOfNotNull(plain.takeIf { it.isNotEmpty() }, sound)
    }

    data class Group(val items: List<Item>) {
        /** The copy to keep: best quality, then a known album, then the earliest liked. */
        val best: Item get() = items.sortedWith(
            compareByDescending<Item> { it.rank }
                .thenByDescending { if (it.song.albumName.isNullOrBlank()) 0 else 1 }
                .thenByDescending { if (LocalLikes.isYouTubeId(it.id)) 1 else 0 }
                .thenByDescending { it.position },
        ).first()
    }

    /**
     * Songs liked more than once: groups of two or more, worst groups (most
     * copies) first. [toleranceSec]: how far apart the lengths may be.
     * [versionsAlike]: a live take or remix counts as the same song.
     */
    fun duplicates(items: List<Item>, toleranceSec: Int, versionsAlike: Boolean): List<Group> =
        cluster(items) { a, b ->
            sameArtist(a, b) && closeLength(a, b, toleranceSec) && (versionsAlike || a.version == b.version)
        }

    /**
     * Songs that share a title but aren't counted as the same — another
     * artist, another version, another length: for a look, not a sweep.
     */
    fun similar(items: List<Item>, toleranceSec: Int, versionsAlike: Boolean): List<Group> =
        cluster(items) { _, _ -> true }.filter { g ->
            // Not when the whole group is simply one song liked twice.
            val d = duplicates(g.items, toleranceSec, versionsAlike)
            d.size != 1 || d[0].items.size != g.items.size
        }

    private fun sameArtist(a: Item, b: Item) =
        OwnFileMatcher.sameArtist(a.song.artist, b.song.artist) ||
            com.music.bitchord.data.sources.ArtistLinks.linked(a.song.artist, b.song.artist) ||
            com.music.bitchord.data.sources.ArtistLinks.linked(b.song.artist, a.song.artist)

    private fun closeLength(a: Item, b: Item, tol: Int): Boolean {
        val x = a.seconds ?: return true
        val y = b.seconds ?: return true
        return kotlin.math.abs(x - y) <= tol
    }

    /** Groups of items sharing a title key and joined by [same] (transitively). */
    private fun cluster(items: List<Item>, same: (Item, Item) -> Boolean): List<Group> {
        val byKey = HashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, it -> keys(it.song.title).forEach { k -> byKey.getOrPut(k) { ArrayList() } += i } }
        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }; return r }
        for (bucket in byKey.values) {
            if (bucket.size < 2) continue
            for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                val a = bucket[i]; val b = bucket[j]
                if (find(a) != find(b) && same(items[a], items[b])) parent[find(a)] = find(b)
            }
        }
        return items.indices.groupBy(::find).values
            .filter { it.size > 1 }
            .map { idx -> Group(idx.map { items[it] }) }
            .sortedWith(compareByDescending<Group> { it.items.size }.thenBy { it.items.first().song.title.lowercase(Locale.ROOT) })
    }

    private fun seconds(text: String?): Int? = text?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
}
