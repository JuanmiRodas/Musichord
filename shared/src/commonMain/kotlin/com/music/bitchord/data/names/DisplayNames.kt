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

    /** The parts a name is often given in: "炎 - homura", "曲名 (Song Name)", "曲名／Title". */
    private val PARTS = Regex("""\s+-\s+|\s*[/／|｜~～〜「」『』【】()（）\[\]]\s*|\s+–\s+|\s+—\s+""")

    /** What a bracket says about a version, not a name ("TV size", "Live", "Remix"…). */
    private val VERSION_WORDS = Regex(
        """(?i)^(?:tv\s*size|tv\s*ver(?:sion|\.)?|ver(?:sion|\.)?|live|remix|mix|edit|radio edit|instrumental|inst\.?|off\s*vocal|acoustic|demo|remaster(?:ed)?|feat\.?.*|ft\.?.*|short|full|movie\s*ver.*|anime\s*ver.*|original|bonus\s*track|explicit|clean|op|ed|theme|single|album|ep|cover|karaoke|piano|orchestra|english\s*ver.*|.*\bver\.?)$""",
    )

    /**
     * Whether [name] already carries a Latin form of itself — "炎 - homura",
     * "ロストロマンス - lost romance", "曲名 (Song Name)" — so nothing is added.
     */
    fun hasLatinPart(name: String): Boolean = PARTS.split(name).any { part ->
        val p = part.trim()
        p.count { it.isLetter() } >= 2 && !needsLatin(p) && !VERSION_WORDS.matches(p) && p.any { it.isLetter() }
    }

    private fun one(kind: Kind, original: String, artist: String?): String {
        if (original.isBlank() || !needsLatin(original) || hasLatinPart(original)) return original
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
