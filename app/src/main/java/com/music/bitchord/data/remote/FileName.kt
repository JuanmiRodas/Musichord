package com.music.bitchord.data.remote

import java.text.Normalizer
import java.util.Locale

/**
 * BitChord Audio: a track's title, artist and album guessed from its file name
 * and folders — for files whose tags haven't been read yet, or have none.
 *
 * Collections mid-tidy mix every convention, so the parts of a name (split on
 * " - ") are placed by what is known around them rather than by position
 * alone: the artist is the part matching the artist folder, an album the part
 * matching the album folder, a four-digit part the year. Whatever is left
 * before the artist is the title, and any further part there is another name
 * for the same song — the original-script title beside a romanized one.
 */
object FileName {

    data class Parsed(
        val title: String,
        val artist: String?,
        val album: String?,
        val track: Int?,
        /** Other names for the title found in the file name. */
        val alternatives: List<String>,
    )

    private val TRACK_PREFIX = Regex("""^\s*(\d{1,3})(?:\s*[.\-_)]\s*|\s+)""")
    private val YEAR = Regex("""^(19|20)\d{2}$""")

    fun parse(fileName: String, artistFolder: String?, albumFolder: String?): Parsed {
        var base = fileName.substringBeforeLast('.').ifBlank { fileName }.trim()
        val track = TRACK_PREFIX.find(base)?.let { m ->
            base = base.substring(m.range.last + 1)
            m.groupValues[1].toIntOrNull()
        }
        val parts = base.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.size > 1 && YEAR.matches(parts.last())) parts.removeAt(parts.lastIndex)
        if (parts.isEmpty()) return Parsed(base.ifBlank { fileName }, null, null, track, emptyList())
        if (parts.size == 1) return Parsed(parts[0], artistFolder, albumFolder, track, emptyList())

        val artistKey = artistFolder?.let(::key)?.takeIf { it.isNotEmpty() }
        val at = artistKey?.let { a -> parts.indexOfFirst { key(it) == a } }?.takeIf { it >= 0 }
        return when {
            // The artist folder names one of the parts: title(s) before it, album after.
            at != null && at > 0 -> Parsed(
                title = parts[0],
                artist = parts[at],
                album = parts.getOrNull(at + 1) ?: albumFolder,
                track = track,
                alternatives = parts.subList(1, at).toList(),
            )
            // "Artist - Title", with the artist first.
            at == 0 -> Parsed(
                title = parts[1],
                artist = parts[0],
                album = parts.getOrNull(2) ?: albumFolder,
                track = track,
                alternatives = emptyList(),
            )
            // No folder to go by: three or more parts read as Title - Artist - Album
            // (what taggers write); two as the older Artist - Title.
            parts.size >= 3 -> Parsed(parts[0], parts[1], parts[2], track, emptyList())
            else -> Parsed(parts[1], parts[0], albumFolder, track, emptyList())
        }
    }

    /** Letters and digits only, any script, compatibility-folded — for comparing names. */
    fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
