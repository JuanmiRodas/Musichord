package com.music.bitchord.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.music.bitchord.R
import com.music.bitchord.data.Http
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.video.VideoMode
import com.music.bitchord.data.video.VideoRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: a song's links to share — Spotify, YouTube Music and YouTube (its
 * music video where there is one). Spotify from your Spotify sign-in when there is
 * one, otherwise from song.link (Odesli), which maps a YouTube Music link to the others.
 */
object ShareLinks {
    data class Links(val spotify: String?, val youTubeMusic: String?, val youTube: String?)

    /** The song whose links sheet is up (a long press on Share). */
    val sheet = MutableStateFlow<Song?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val known = ConcurrentHashMap<String, Links>()
    private val client by lazy { Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun of(song: Song): Links = withContext(Dispatchers.IO) {
        known[song.videoId]?.let { return@withContext it }
        val ytId = runCatching { VideoMode.youTubeSongOf(song) }.getOrNull()
            ?: song.videoId.takeIf { LocalLikes.isYouTubeId(it) }
        val ytMusic = ytId?.let { "https://music.youtube.com/watch?v=$it" }
        val video = (VideoMode.cached(song.videoId) ?: VideoMode.linked(song))
            ?.takeIf { it.site == VideoRef.Site.YOUTUBE }?.id
            ?: song.videoId.takeIf { song.isVideo && LocalLikes.isYouTubeId(it) }
            ?: ytId
        val youTube = video?.let { "https://www.youtube.com/watch?v=$it" }
        val artist = song.artist.split(',').first().trim()
        var spotify = runCatching { com.music.bitchord.data.canvas.SpotifyCanvas.trackUrl(song.title, artist, song.albumName) }.getOrNull()
        if (spotify == null && ytMusic != null) spotify = odesliSpotify(ytMusic)
        Links(spotify, ytMusic, youTube).also { if (spotify != null) known[song.videoId] = it }
    }

    private fun odesliSpotify(url: String): String? = runCatching {
        val req = Request.Builder()
            .url("https://api.song.link/v1-alpha.1/links?userCountry=ES&url=" + URLEncoder.encode(url, "UTF-8"))
            .header("User-Agent", "BitChordAudio/1.9 (https://github.com/JuanmiRodas/Musichord)")
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful || body == null) { TrackLog.w("Share", "song.link: HTTP ${r.code}"); return@use null }
            json.parseToJsonElement(body).jsonObject["linksByPlatform"]?.jsonObject
                ?.get("spotify")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        }
    }.onFailure { TrackLog.w("Share", "song.link failed: ${it.message}") }.getOrNull()

    fun share(context: Context, title: String, url: String) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                title,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun copy(context: Context, url: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(context, R.string.share_link_copied, Toast.LENGTH_SHORT).show()
    }

    /** A tap on Share: the Spotify link straight to the share sheet (YouTube Music's if Spotify has none). */
    fun shareSpotify(context: Context, song: Song) {
        val app = context.applicationContext
        Toast.makeText(app, R.string.share_finding, Toast.LENGTH_SHORT).show()
        scope.launch {
            val links = of(song)
            val url = links.spotify ?: links.youTubeMusic ?: links.youTube
            if (url == null) {
                Toast.makeText(app, R.string.share_no_link, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (links.spotify == null) Toast.makeText(app, R.string.share_no_spotify, Toast.LENGTH_SHORT).show()
            share(app, song.title, url)
        }
    }
}
