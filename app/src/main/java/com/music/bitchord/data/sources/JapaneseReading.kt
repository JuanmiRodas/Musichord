package com.music.bitchord.data.sources

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BitChord Audio: how a Japanese title sounds, in romaji — "暁の車" →
 * "akatsukinokuruma" — so it meets the same song filed as "Akatsuki no
 * Kuruma".
 *
 * Kanji have no reading by rule, so a dictionary gives it (Kuromoji, the
 * IPADIC readings); the kana that come out go through the app's own Hepburn
 * ([TrackMatcher.romajiOf]). The dictionary takes a moment and some memory to
 * load, so it is loaded once, in the background; until then nothing is read
 * and the other ways of matching carry on.
 *
 * [soundKey] then folds the spellings romaji goes by — long vowels written or
 * not (Shinkokyu / Shinkokyuu, Toukyou / Tokyo), "wo"/"o", "he"/"e",
 * "ha"/"wa" particles, Hepburn and Kunrei (shi/si, tsu/tu, ji/zi) — so either
 * side's way of writing lands on the same key.
 */
object JapaneseReading {

    @Volatile
    private var tokenizer: com.atilika.kuromoji.ipadic.Tokenizer? = null
    private val starting = AtomicBoolean(false)
    private val cache = ConcurrentHashMap<String, String>()

    private val JAPANESE = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")

    fun isJapanese(text: String): Boolean = JAPANESE.containsMatchIn(text)

    val ready: Boolean get() = tokenizer != null

    /** Loads the dictionary in the background, once. */
    fun warmUp() {
        if (!starting.compareAndSet(false, true)) return
        Thread {
            runCatching { tokenizer = com.atilika.kuromoji.ipadic.Tokenizer() }
                .onFailure { starting.set(false) }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            name = "kanji-readings"
        }.start()
    }

    /** The tokenizer, loading it here and now if the background load hasn't finished. Not on the main thread. */
    @Synchronized
    fun tokenizerNow(): com.atilika.kuromoji.ipadic.Tokenizer? {
        tokenizer?.let { return it }
        return runCatching { com.atilika.kuromoji.ipadic.Tokenizer() }.getOrNull()?.also { tokenizer = it }
    }

    /** [text] in romaji, or null while the dictionary isn't loaded. */
    fun romaji(text: String): String? {
        if (!isJapanese(text)) return text
        val t = tokenizer ?: run { warmUp(); return null }
        return cache.getOrPut(text) {
            val kana = runCatching {
                t.tokenize(text).joinToString("") { tok ->
                    tok.reading?.takeIf { it.isNotBlank() && it != "*" } ?: tok.surface
                }
            }.getOrDefault(text)
            TrackMatcher.romajiOf(kana)
        }
    }

    private val FOLDS = listOf(
        "ou" to "o", "oo" to "o", "uu" to "u", "ii" to "i", "aa" to "a", "ee" to "e",
        "wo" to "o", "he" to "e", "ha" to "a", "wa" to "a",
        "tsu" to "tu", "shi" to "si", "chi" to "ti", "sha" to "sya", "sh" to "sy", "ji" to "zi", "fu" to "hu",
    )

    /** Letters and digits of the romaji, with its spelling variants folded together. */
    fun soundKey(romaji: String): String {
        var s = java.text.Normalizer.normalize(romaji, java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' }
        for ((a, b) in FOLDS) s = s.replace(a, b)
        return s
    }
}
