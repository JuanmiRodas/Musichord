package com.music.bitchord.data

import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: your own keys for free services (Settings › Services) — typed by you in
 * the app, kept encrypted on this phone ([AppSettings] secrets), never logged or synced.
 * Without a key a service is simply skipped. [test] asks each service the same question
 * the Mac library app asks (`Keys.test`), so both say the same about a key.
 */
object ServiceKeys {
    enum class Service { LASTFM, DISCOGS, FANART }

    sealed interface Check {
        data object Works : Check
        data object Rejected : Check
        data object SecretRejected : Check
        data object Missing : Check
        data class Offline(val why: String) : Check
        data class Odd(val code: Int) : Check
    }

    private val http by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    private fun call(url: String): Pair<Int, String> = runCatching {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "BitChordAudio/1.0 (https://github.com/JuanmiRodas/Musichord)")
                .build(),
        ).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
    }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Whether [service] takes [key] (and, for Last.fm, [secret]). */
    suspend fun test(service: Service, key: String, secret: String = ""): Check = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.isEmpty()) return@withContext Check.Missing
        val (code, body) = when (service) {
            Service.LASTFM -> call("https://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=Cher&api_key=${enc(k)}&format=json")
            Service.DISCOGS -> call("https://api.discogs.com/oauth/identity?token=${enc(k)}")
            Service.FANART -> call("https://webservice.fanart.tv/v3/music/cc197bad-dc9c-440d-a5b5-d52ba2e14234?api_key=${enc(k)}")
        }
        if (code == -1) return@withContext Check.Offline(body)
        val bad = when (service) {
            Service.LASTFM -> body.contains("\"error\":10") || body.contains("\"error\": 10") || code == 403
            else -> code == 401 || code == 403
        }
        when {
            bad -> Check.Rejected
            code !in 200..299 -> Check.Odd(code)
            service == Service.LASTFM && secret.isNotBlank() -> lastFmSecret(k, secret.trim())
            else -> Check.Works
        }
    }

    /** A signed call (auth.getToken): Last.fm answers error 13 when the secret is wrong. */
    private fun lastFmSecret(key: String, secret: String): Check {
        val sig = md5("api_key${key}methodauth.getToken$secret")
        val (code, body) = call("https://ws.audioscrobbler.com/2.0/?method=auth.getToken&api_key=${enc(key)}&api_sig=$sig&format=json")
        return when {
            code == -1 -> Check.Offline(body)
            body.contains("\"error\":13") || body.contains("\"error\": 13") -> Check.SecretRejected
            body.contains("\"token\"") -> Check.Works
            else -> Check.Odd(code)
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** After the Last.fm key or secret changed: scrobbling picks them up if you are signed in. */
    fun applyLastFm() {
        val session = AppSettings.lastfmSessionKey.value
        val key = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (session.isBlank() || key.isBlank() || secret.isBlank()) return
        runCatching {
            LastFM.configure(
                endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT },
                apiKey = key,
                secret = secret,
                sessionKey = session,
            )
        }
    }
}
