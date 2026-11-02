package com.music.bitchord.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtworkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * BitChord Audio: the cover for the notification, the lock screen and the
 * system media controls.
 *
 * Media3's own loader fetches the artwork address with no credentials, so a
 * cover that sits in your pCloud folder (behind your login) never arrived, and
 * a song whose only cover is the one inside the file had no address at all —
 * the "sometimes there's no cover" in the system player. This one loads
 * through the app's image loader, which signs pCloud requests, and for a file
 * of yours with no cover beside it takes the picture embedded in the file,
 * the same one the app shows.
 */
@UnstableApi
class ArtworkBitmapLoader(context: Context) : BitmapLoader {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Artwork could not be decoded")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future { load(uri.toString()) }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        metadata.artworkUri?.let { return loadBitmap(it) }
        val id = metadata.extras?.getString(EXTRA_MEDIA_ID) ?: return null
        if (!id.startsWith("webdav:") && !id.startsWith("smb:")) return null
        return scope.future {
            val song = Song(
                videoId = id,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                thumbnailUrl = null,
                albumName = metadata.albumTitle?.toString(),
            )
            val url = RemoteArtworkStore.resolveArt(app, song) ?: error("No artwork for $id")
            load(url)
        }
    }

    private suspend fun load(url: String): Bitmap {
        val request = ImageRequest.Builder(app)
            .data(url)
            .size(NOTIFICATION_ART_PX)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(app).execute(request) as? SuccessResult
            ?: error("Artwork could not be loaded")
        return result.image.toBitmap()
    }
}
