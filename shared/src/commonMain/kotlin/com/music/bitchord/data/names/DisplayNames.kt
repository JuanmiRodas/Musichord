package com.music.bitchord.data.names

/**
 * BitChord Audio: names in a non-Latin script shown with their Latin form
 * beside them — "樹海の糸 · Jukai no Ito" — from the app's own names database
 * (official names first; a guessed romanization marked "≈").
 *
 * Display only: a song's title, artist and album stay exactly as they are
 * everywhere else (lookups, matching, tags). The app sets [latin].
 */
object DisplayNames {

    enum class Kind { TITLE, ARTIST, ALBUM }

    /** The Latin name and whether it is a guess, or null when there is none (yet). */
    class Latin(val text: String, val guessed: Boolean)

    /** Set by the app: the Latin name for [original] (of [kind], by [artist] where it matters). */
    var latin: ((kind: Kind, original: String, artist: String?) -> Latin?)? = null

    /** Whether [text] has letters in a script other than Latin. */
    fun needsLatin(text: String): Boolean = text.any { c ->
        c.isLetter() && c.code > 0x024F && c !in 'Ḁ'..'ỿ' && c !in 'Ａ'..'ｚ'
    }

    private fun one(kind: Kind, original: String, artist: String?): String {
        if (original.isBlank() || !needsLatin(original)) return original
        val l = latin?.invoke(kind, original.trim(), artist) ?: return original
        if (l.text.isBlank() || l.text.equals(original, ignoreCase = true)) return original
        return original + " · " + (if (l.guessed) "≈" else "") + l.text
    }

    fun title(title: String, artist: String?): String = one(Kind.TITLE, title, artist)

    fun album(album: String, artist: String?): String = one(Kind.ALBUM, album, artist)

    /** An artist credit, each name in it on its own ("A, B & C"). */
    fun artist(credit: String): String {
        if (!needsLatin(credit)) return credit
        val parts = SEPARATORS.split(credit)
        val seps = SEPARATORS.findAll(credit).map { it.value }.toList()
        return buildString {
            parts.forEachIndexed { i, p ->
                append(one(Kind.ARTIST, p, null))
                if (i < seps.size) append(seps[i])
            }
        }
    }

    private val SEPARATORS = Regex("""\s*(?:,|、|&|＆| feat\. | ft\. | x | × |/|・)\s*""")
}
