package com.music.bitchord.download

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BitChord Audio: deletes the phone's file of [song] — the app's download of it, or the
 * file it is playing from — for when the wrong version was saved. A file the app didn't
 * make is deleted with Android's own confirmation. The song plays on from what is
 * buffered; the next time it is chosen, the file isn't there to be picked.
 */
suspend fun deletePhoneCopy(context: Context, song: Song, @Suppress("UNUSED_PARAMETER") controller: MediaController?) {
    if (Downloads.saved.value[song.videoId] != null) {
        val ok = Downloads.delete(context, song.videoId)
        toast(context, ok)
        return
    }
    val uri = song.localUri?.let(Uri::parse) ?: run { toast(context, false); return }
    val done = withContext(Dispatchers.IO) {
        runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { java.io.File(it).delete() } == true
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        }
    }
    if (done.getOrNull() == true) {
        TrackLog.d("BitChord", "phone copy deleted: '${song.title}' ($uri)")
        toast(context, true)
        return
    }
    // Not the app's own file: Android asks first (Android 11 and up).
    val activity = context.findActivity()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null && uri.scheme == "content") {
        runCatching {
            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            activity.startIntentSenderForResult(request.intentSender, 0, null, 0, 0, 0)
            TrackLog.d("BitChord", "phone copy: asked to delete '${song.title}' ($uri)")
        }.onFailure {
            TrackLog.w("BitChord", "phone copy: couldn't delete '${song.title}': ${it.message}")
            toast(context, false)
        }
    } else {
        TrackLog.w("BitChord", "phone copy: couldn't delete '${song.title}': ${done.exceptionOrNull()?.message}")
        toast(context, false)
    }
}

private fun toast(context: Context, ok: Boolean) {
    Toast.makeText(context, if (ok) R.string.delete_phone_copy_done else R.string.delete_phone_copy_failed, Toast.LENGTH_SHORT).show()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
