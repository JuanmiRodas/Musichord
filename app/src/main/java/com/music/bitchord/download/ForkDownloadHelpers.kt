package com.music.bitchord.download

import java.io.File

/*
 * Fork: the download fixes' helpers, moved out of `DownloadStore` and `Downloads` unchanged so those
 * upstream files keep only their call lines. Extensions on the two objects, so the calls inside them
 * (and the tests' `DownloadStore.forkTakeUtf8(…)`) read as before.
 */

/**
 * fork: the 255-byte ceiling is bytes, not characters: 120 CJK characters are 360 UTF-8 bytes and
 * such a title failed to download at all. Cut to [FORK_MAX_STEM_BYTES], which leaves room for the
 * extension, a " (2)" and the `.part`'s dot, timestamp and suffix ([forkPartFile]); never inside
 * a surrogate pair.
 */
internal fun DownloadStore.forkTakeUtf8(text: String, maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return text.substring(0, end)
}

/** fork: see [forkTakeUtf8]. */
internal const val FORK_MAX_STEM_BYTES = 200

/**
 * fork: [name], or the first "stem (2).ext", "stem (3).ext"… that [taken] says is free. Two
 * tracks that come to the same file name ("Intro" on two releases) can finish at once on two
 * workers, and the second rename used to replace the first's file.
 */
internal fun DownloadStore.forkFreeName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val stem = name.substring(0, dot)
    val extension = name.substring(dot)
    var n = 2
    while (taken("$stem ($n)$extension")) n++
    return "$stem ($n)$extension"
}

/**
 * fork: a `.part` of this download's own. Two tracks that come to the same file name ("Intro" on two
 * releases) run at once in four workers; sharing one, the second deleted the first's and both then
 * published whatever was left. A crash's leftovers go once they are a day old, not while one may be writing.
 */
internal fun DownloadStore.forkPartFile(folder: File, stem: String): File {
    val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    folder.listFiles { file -> file.name.startsWith("$stem.") && file.name.endsWith(".part") && file.lastModified() < dayAgo }
        ?.forEach { it.delete() }
    return File(folder, "$stem.${System.nanoTime()}.part")
}

/**
 * fork: release [id]'s tracks (its record and what its own tap asked for, [requested]) that are
 * queued or running and that no other release names, so `Downloads.deleteCollection` can call them off.
 */
internal fun Downloads.inFlightOnlyIn(
    id: String,
    collections: Map<String, SavedCollection>,
    requested: Set<String>,
    active: Map<String, DownloadState>,
): List<String> {
    val others = collections.values.filter { it.id != id }.flatMapTo(HashSet()) { it.videoIds }
    return (collections[id]?.videoIds.orEmpty() + requested).distinct().filter { videoId ->
        videoId !in others && active[videoId].let { it is DownloadState.Queued || it is DownloadState.Running }
    }
}
