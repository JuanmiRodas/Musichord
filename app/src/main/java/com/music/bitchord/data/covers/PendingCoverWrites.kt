package com.music.bitchord.data.covers

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavRepository

/**
 * BitChord Audio: covers waiting to be written into your pCloud files. Saving a
 * cover into a file means downloading and uploading the whole file, so on
 * mobile data it waits here (kept across restarts) and is done on Wi-Fi, in the
 * background ([com.music.bitchord.data.BackgroundWork]).
 */
object PendingCoverWrites {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_pending_covers"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** [url] to be written into each of [files] (pCloud file ids). */
    fun add(files: List<String>, url: String) {
        val edit = prefs?.edit() ?: return
        files.forEach { edit.putString(it, url) }
        edit.apply()
    }

    val count: Int get() = prefs?.all?.size ?: 0

    /** Writes the covers waiting, while on Wi-Fi. Returns how many were written. */
    suspend fun flush(): Int {
        val context = appContext ?: return 0
        val waiting = prefs?.all?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.orEmpty()
        if (waiting.isEmpty()) return 0
        var written = 0
        val images = HashMap<String, CoverWriter.Image?>()
        for ((fileId, url) in waiting) {
            if (AppSettings.meteredConnection.value != false) break
            val file: Song = WebDavRepository.library().firstOrNull { it.videoId == fileId } ?: run {
                prefs?.edit()?.remove(fileId)?.apply(); null
            } ?: continue
            val image = images.getOrPut(url) { CoverWriter.fetch(url) } ?: continue
            when (runCatching { CoverWriter.saveInto(context, file, image) }.getOrNull()) {
                CoverWriter.Outcome.Saved -> { written++; prefs?.edit()?.remove(fileId)?.apply() }
                is CoverWriter.Outcome.Skipped -> prefs?.edit()?.remove(fileId)?.apply()
                else -> Unit // failed: tried again next time
            }
        }
        if (written > 0) TrackLog.d(TAG, "covers: $written waiting covers written into your files")
        return written
    }
}
