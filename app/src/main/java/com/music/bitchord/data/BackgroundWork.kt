package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.covers.PendingCoverWrites
import com.music.bitchord.data.lyrics.LyricsKeeper
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BitChord Audio: the slow jobs that make the library better, done in the
 * background while on Wi-Fi and stopped (to carry on later, where they left
 * off) as soon as the phone is on mobile data:
 *
 *  1. covers waiting to be written into your pCloud files ([PendingCoverWrites]);
 *  2. the source map — your best file and JioSaavn's copy for every liked song
 *     ([SourceMap.fill]), kept on the phone and in pCloud, so it isn't redone;
 *  3. the lyrics of every liked song, kept for good ([LyricsKeeper]);
 *  4. songs liked only here, liked on YouTube Music too ([LikeLinker]).
 *
 * Run when Wi-Fi comes on, and every half hour while it stays on (each job
 * only does what is still left).
 */
object BackgroundWork {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        PendingCoverWrites.init(context)
        LyricsKeeper.init(context)
        LikeLinker.init(context)
        scope.launch {
            AppSettings.meteredConnection.collect { metered ->
                if (metered == false) start() else job?.cancel()
            }
        }
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // A moment for the connection (and the library) to settle.
            delay(20_000)
            while (AppSettings.meteredConnection.value == false) {
                runCatching { PendingCoverWrites.flush() }
                runCatching { SourceMap.fill() }
                runCatching { LyricsKeeper.prefetchLiked() }
                runCatching { LikeLinker.run() }
                delay(30 * 60 * 1000L)
            }
        }
    }
}
