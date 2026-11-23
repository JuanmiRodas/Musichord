package bitchord.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Covers chosen by the listener, one per album (or per loose song): `~/Music/.bitchord-live/covers/
 * approved.json` and the images beside it. The build writes an approved cover into every song of
 * that album (and writes them again when the approval changes).
 */
object CoverChoice {
    val dir: File get() = File(Live.dir, "covers").apply { mkdirs() }
    private val file get() = File(dir, "approved.json")
    @Volatile private var cache: Pair<Long, JSONObject>? = null

    private fun db(): JSONObject = synchronized(this) {
        val m = file.lastModified()
        cache?.takeIf { it.first == m }?.second ?: (runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())).also { cache = m to it }
    }

    /** The album an item belongs to, for covers: its album and album artist, or the song itself. */
    fun keyOf(i: Builder.Item): String =
        if (i.album.isNotBlank()) "A|" + Builder.pkey(i.albumArtist.ifBlank { i.artist }) + "|" + Builder.albumKey(i.album) else "S|" + i.key

    fun approvedFor(root: String, i: Builder.Item): File? = db().optJSONObject(keyOf(i))?.optString("file")?.let { File(dir, it) }?.takeIf { it.isFile }

    @Synchronized fun approve(key: String, image: File, source: String, url: String?) {
        val hash = hash(image.readBytes())
        val name = "$hash.${image.extension.ifBlank { "jpg" }}"
        val keep = File(dir, name)
        if (!keep.exists()) image.copyTo(keep)
        val o = db()
        o.put(key, JSONObject().put("file", name).put("source", source).put("url", url ?: JSONObject.NULL).put("at", System.currentTimeMillis()))
        file.writeText(o.toString(1)); cache = null
    }

    fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * Proposals of covers to review in the app, for every album of the plan (and every loose song):
 * what the song carries now ("antes") and what was found ("propuesta"), from MusicBrainz/Cover
 * Art Archive and VGMdb (sure when the album's track lengths match), Deezer and iTunes (by album,
 * artist and number of tracks), and for songs without an album, the single they were released
 * on. ≥ 90 %: same album, same artist, same tracks — quick to approve together; below, to look
 * at one by one. Nothing found: a Google Images search to open, and a field for an image's
 * address. Nothing changes until a proposal is approved.
 */
object CoverReview {
    data class Candidate(val source: String, val url: String, val confidence: Int, val title: String, val local: String?)
    data class Proposal(
        val key: String, val album: String, val artist: String, val folder: String, val songs: Int,
        val current: String?, val candidates: List<Candidate>, val status: String, // "propuesta", "igual", "sin propuesta", "aprobada", "descartada"
    )

    private val file get() = File(CoverChoice.dir, "proposals.json")
    private val cacheDir get() = File(CoverChoice.dir, "cache").apply { mkdirs() }

    fun load(): List<Proposal> = runCatching {
        val a = JSONObject(file.readText()).getJSONArray("proposals")
        (0 until a.length()).map { k ->
            val o = a.getJSONObject(k)
            Proposal(o.getString("key"), o.optString("album"), o.optString("artist"), o.optString("folder"), o.optInt("songs"), o.optString("current").ifBlank { null },
                o.optJSONArray("candidates")?.let { c -> (0 until c.length()).map { j -> c.getJSONObject(j).let { x -> Candidate(x.getString("source"), x.getString("url"), x.getInt("confidence"), x.optString("title"), x.optString("local").ifBlank { null }) } } }.orEmpty(),
                o.optString("status", "propuesta"))
        }
    }.getOrDefault(emptyList())

    @Synchronized fun save(list: List<Proposal>) {
        val a = JSONArray(list.map { p ->
            JSONObject().put("key", p.key).put("album", p.album).put("artist", p.artist).put("folder", p.folder).put("songs", p.songs).put("current", p.current ?: "")
                .put("status", p.status).put("candidates", JSONArray(p.candidates.map { c -> JSONObject().put("source", c.source).put("url", c.url).put("confidence", c.confidence).put("title", c.title).put("local", c.local ?: "") }))
        })
        val tmp = File(file.parentFile, "proposals.json.tmp")
        tmp.writeText(JSONObject().put("updated", System.currentTimeMillis()).put("proposals", a).toString())
        tmp.renameTo(file)
    }

    fun setStatus(key: String, status: String) = save(load().map { if (it.key == key) it.copy(status = status) else it })

    fun approve(p: Proposal, c: Candidate) {
        val f = c.local?.let { File(it) }?.takeIf { it.isFile } ?: download(c.url) ?: return
        CoverChoice.approve(p.key, f, c.source, c.url)
        setStatus(p.key, "aprobada")
    }

    /** An image's address pasted by the listener (from a Google search): a candidate of its own. */
    fun addManual(key: String, url: String): Boolean {
        val f = download(url) ?: return false
        save(load().map { if (it.key == key) it.copy(candidates = listOf(Candidate("manual", url, 50, "elegida a mano", f.path)) + it.candidates, status = "propuesta") else it })
        return true
    }

    fun googleUrl(p: Proposal) = "https://www.google.com/search?tbm=isch&q=" + URLEncoder.encode("${p.artist} ${p.album.ifBlank { p.folder.substringAfterLast('/') }} cover", "UTF-8")

    private fun download(url: String): File? {
        val bytes = AlbumArt.fetch(url, 300) ?: return null
        if (bytes.size < 3000 || runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() == null) return null
        val f = File(cacheDir, CoverChoice.hash(bytes) + ".jpg")
        if (!f.exists()) f.writeBytes(bytes)
        return f
    }

    /** 64-bit average hash: two pictures of the same cover (any size) differ in a few bits. */
    private fun ahash(f: File): Long? = runCatching {
        val img = ImageIO.read(f) ?: return null
        val small = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply { drawImage(img, 0, 0, 8, 8, null); dispose() }
        val px = (0 until 64).map { small.getRGB(it % 8, it / 8).let { c -> ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3 } }
        val avg = px.average()
        px.foldIndexed(0L) { k, acc, v -> if (v >= avg) acc or (1L shl k) else acc }
    }.getOrNull()
    private fun same(a: File, b: File): Boolean { val x = ahash(a) ?: return false; val y = ahash(b) ?: return false; return java.lang.Long.bitCount(x xor y) <= 8 }

    /** What a song carries now: its album folder's picture or its own embedded one. */
    private fun current(i: Builder.Item, covers: Covers?): File? {
        CoverChoice.approvedFor("", i)?.let { return it }
        i.cover?.let { covers?.forFolder(it) }?.let { return it }
        val out = File(cacheDir, "cur-" + CoverChoice.hash(i.track.path.toByteArray()) + ".jpg")
        if (out.isFile) return out
        val (code, _) = Tools.run(Tools.ffmpeg, "-nostdin", "-v", "error", "-y", "-i", i.track.path, "-an", "-map", "0:v?", "-c:v", "copy", "-frames:v", "1", "-f", "image2", out.path, timeoutSec = 60)
        return out.takeIf { code == 0 && it.length() > 0 }
    }

    private fun sameText(a: String, b: String) = Builder.albumKey(a) == Builder.albumKey(b) || Words.plain(a) == Words.plain(b)
    private fun sameArtist(a: String, b: String) = Builder.pkey(a.substringBefore(" (")) == Builder.pkey(b.substringBefore(" (")) || Words.plain(a).let { x -> x.length >= 4 && Words.plain(b).contains(x) }

    private fun deezer(album: String, artist: String, songs: Int): List<Candidate> {
        val q = URLEncoder.encode(if (artist.isBlank()) "album:\"$album\"" else "artist:\"${artist.substringBefore(" (")}\" album:\"$album\"", "UTF-8")
        val data = AlbumArt.json("https://api.deezer.com/search/album?q=$q&limit=5", 250)?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }.mapNotNull { o ->
            val url = o.optString("cover_xl").ifBlank { return@mapNotNull null }
            val t = o.optString("title"); val ar = o.optJSONObject("artist")?.optString("name").orEmpty()
            val conf = when {
                sameText(t, album) && sameArtist(ar, artist) && o.optInt("nb_tracks") >= songs -> 92
                sameText(t, album) && sameArtist(ar, artist) -> 85
                sameText(t, album) -> 70
                else -> 45
            }
            Candidate("Deezer", url, conf, "$t — $ar", null)
        }
    }

    private fun itunes(term: String, entity: String, album: String, artist: String, songs: Int): List<Candidate> {
        val r = AlbumArt.json("https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&entity=$entity&limit=5", 3100)?.optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { o ->
            val url = o.optString("artworkUrl100").ifBlank { return@mapNotNull null }.replace("100x100bb", "1200x1200bb")
            val t = o.optString("collectionName"); val ar = o.optString("artistName")
            val conf = when {
                entity == "album" && sameText(t, album) && sameArtist(ar, artist) && o.optInt("trackCount") >= songs -> 91
                entity == "album" && sameText(t, album) && sameArtist(ar, artist) -> 84
                entity == "song" && sameArtist(ar, artist) && Words.plain(o.optString("trackName")) == Words.plain(album) -> 80
                sameText(t, album) -> 65
                else -> 40
            }
            Candidate("iTunes", url, conf, "$t — $ar", null)
        }
    }

    /**
     * Looks for covers for every album of [plan] not reviewed yet (or whose songs changed), until
     * [deadline]; what was found is kept as it goes (an interrupted review goes on next time).
     */
    fun run(root: String, plan: Builder.Plan, deadline: Long = Long.MAX_VALUE) {
        val covers = Covers(root)
        val old = load().associateBy { it.key }.toMutableMap()
        val groups = plan.copy.groupBy(CoverChoice::keyOf)
        Live.begin("Portadas", root); Live.total = groups.size
        var n = 0
        for ((key, items) in groups.entries.sortedByDescending { it.value.size }) {
            if (Live.stop || System.currentTimeMillis() > deadline) break
            Live.done.incrementAndGet()
            val prev = old[key]
            if (prev != null && prev.songs == items.size && prev.status != "sin propuesta") continue
            val i = items.first()
            val album = i.album
            val artist = i.albumArtist.ifBlank { i.artist }
            Live.current = "$artist — ${album.ifBlank { i.title }}"; Live.write()
            val cur = runCatching { current(i, covers) }.getOrNull()
            val found = ArrayList<Candidate>()
            if (album.isNotBlank() && items.size >= 5) {
                val a = AlbumArt.Album(File(i.target).parentFile, i.target.removePrefix(root).trimStart('/'), album, artist, i.year, items.mapNotNull { it.track.seconds }, i.target.removePrefix(root).trimStart('/').substringBefore('/'))
                for (src in listOf(AlbumArt::musicbrainz, AlbumArt::vgmdb)) {
                    runCatching { src(a) }.getOrDefault(emptyList()).filter { AlbumArt.sure(a, it) }.forEach { r ->
                        r.images.firstOrNull { (kind, _) -> kind.contains("front", true) || kind.contains("cover", true) }?.let { (_, url) -> found += Candidate(r.source, url, 96, r.title, null) }
                    }
                    if (found.isNotEmpty()) break
                }
            }
            // Spotify (Japan's catalogue, then Spain's): the same album with as many songs or more.
            if (album.isNotBlank()) Spotify.checked(artist, album)?.takeIf { it.optBoolean("found") && it.optString("cover").isNotBlank() }?.let { c ->
                val n = c.optJSONArray("tracks")?.length() ?: 0
                found += Candidate("Spotify", c.getString("cover"), if (n >= items.size) 94 else 80, c.optString("name"), null)
            }
            if (album.isNotBlank()) {
                found += runCatching { deezer(album, artist, items.size) }.getOrDefault(emptyList())
                if (found.none { it.confidence >= 90 }) found += runCatching { itunes("${artist.substringBefore(" (")} $album", "album", album, artist, items.size) }.getOrDefault(emptyList())
            } else {
                found += runCatching { itunes("${artist.substringBefore(" (")} ${i.title}", "song", i.title, artist, 1) }.getOrDefault(emptyList())
            }
            val best = found.filter { it.confidence >= 60 }.sortedByDescending { it.confidence }.distinctBy { it.url }.take(3)
                .mapNotNull { c -> download(c.url)?.let { c.copy(local = it.path) } }
            val status = when {
                best.isEmpty() -> "sin propuesta"
                cur != null && best.first().local?.let { same(File(it), cur) } == true -> "igual"
                else -> "propuesta"
            }
            old[key] = Proposal(key, album, artist, File(i.target).parent.removePrefix(root).trimStart('/'), items.size, cur?.path, best, status)
            if (++n % 10 == 0) save(old.values.toList())
        }
        save(old.values.toList())
        Live.end("Portadas: ${old.values.count { it.status == "propuesta" }} propuestas, ${old.values.count { it.status == "igual" }} ya correctas, ${old.values.count { it.status == "sin propuesta" }} sin propuesta")
    }
}
