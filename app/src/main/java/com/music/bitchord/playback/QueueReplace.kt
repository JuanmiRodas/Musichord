package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * BitChord Audio: [item] in the queue slot at [index], keeping that slot's
 * queue identity — its entry id, tier, and, for a song from an album or
 * playlist, its place in that list (canonical index and repeat cycle).
 *
 * Every in-place swap goes through here: your file or JioSaavn's copy in
 * place of a YouTube track, an audio cut in place of a video, a better
 * stream, a stand-in for one that won't play. They used to rebuild the item
 * from the song alone, which has no place-in-the-list field, so the swapped
 * song no longer said where it was. The list's cursor then stopped moving
 * (it only follows songs that say where they are), and topping the queue up
 * or restoring it after Android closed the app started again from that stale
 * point — the same few songs again and again, locally and on the speaker.
 */
fun Player.replaceInQueue(index: Int, item: MediaItem) {
    val old = if (index in 0 until mediaItemCount) getMediaItemAt(index) else null
    replaceMediaItem(index, if (old == null) item else item.withQueueIdentityOf(old))
}

/** [this] carrying [old]'s queue identity, where [this] has none of its own. */
fun MediaItem.withQueueIdentityOf(old: MediaItem): MediaItem =
    QueueShuffle.withQueueMetadata(
        this,
        newEntryId = old.queueEntryId ?: queueEntryId,
        newCanonicalIndex = old.canonicalIndex ?: canonicalIndex,
        newTier = old.queueTier,
        newContextCycleId = old.contextCycleId ?: contextCycleId,
    )
