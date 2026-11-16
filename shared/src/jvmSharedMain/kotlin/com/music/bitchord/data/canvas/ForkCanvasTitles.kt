package com.music.bitchord.data.canvas

import java.text.Normalizer
import java.util.Locale

/*
 * Fork: title matching for the clip lookups, moved out of upstream's CanvasArtwork.kt unchanged
 * (same package, so every caller and import stays as it was).
 */

/**
 * Fork: a title folded like [normalizeForMatch] (case, accents, punctuation) but keeping the
 * letters and digits of every script, as ForkSpotifyMatch does. [normalizeForMatch] keeps only
 * a-z0-9, so a Japanese, Cyrillic or Arabic title folds to "" and every such title by a Latin-named
 * artist "matched" every other: the artist check passed and "" == "" (or Apple's `contains("")`),
 * and a song got another song's clip. [normalizeForMatch] itself is left alone: artist-name keys
 * elsewhere (Navidrome pictures, history) are built on it.
 */
fun String.normalizeTitleForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ") // marks kept: kana voicing, Indic vowel signs (Latin accents are gone above)
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Fork: whether two titles are the same song's: equal once folded by [normalizeTitleForMatch], and
 * never when the wanted one folds to nothing (all punctuation or symbols): such a title can't be
 * told from any other, so it matches none rather than all.
 */
fun titlesMatch(got: String, wanted: String): Boolean {
    val want = wanted.normalizeTitleForMatch()
    return want.isNotEmpty() && got.normalizeTitleForMatch() == want
}
