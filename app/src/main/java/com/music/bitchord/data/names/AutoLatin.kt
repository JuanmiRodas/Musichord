package com.music.bitchord.data.names

import android.os.Build
import com.music.bitchord.data.lyrics.JapaneseRomanizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: a name's romanization, for when nothing official is known —
 * Japanese read by its dictionary (kana, and kanji unless the text is plainly
 * Chinese), any other script by ICU's transliteration (pinyin, Korean RR,
 * Cyrillic…). Always shown marked as a guess.
 */
object AutoLatin {

    private val KANA = Regex("[\\u3040-\\u30ff]")
    private val HAN = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")

    fun isJapanese(text: String): Boolean = KANA.containsMatchIn(text)

    private val cache = ConcurrentHashMap<String, String>()
    private val working: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bumped when a romanization worked out aside is ready. */
    @Volatile var onReady: (() -> Unit)? = null

    /** The romanization if already worked out; otherwise null, and it is worked out aside. */
    fun cached(text: String, context: String?): String? {
        val key = text + "\u0001" + (context?.let(::isJapanese) ?: false)
        cache[key]?.let { return it.ifEmpty { null } }
        if (working.add(key)) {
            scope.launch {
                val reading = com.music.bitchord.data.sources.JapaneseReading
                if (!reading.ready) {
                    reading.warmUp()
                    // The dictionary loads once, in a few seconds; asked again after.
                    kotlinx.coroutines.delay(3_000)
                }
                val r = of(text, context)
                if (r != null || reading.ready) cache[key] = r.orEmpty()
                working -= key
                if (r != null) onReady?.invoke()
            }
        }
        return null
    }

    /** Simplified-only characters: kanji text that is Chinese, not Japanese. */
    private fun looksChinese(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            android.icu.text.Transliterator.getInstance("Hans-Hant").transliterate(text) != text
        }.getOrDefault(false)
    }

    fun of(text: String, context: String?): String? {
        if (!DisplayNames.needsLatin(text)) return null
        val japanese = isJapanese(text) || (HAN.containsMatchIn(text) && (context?.let(::isJapanese) == true || !looksChinese(text)))
        val out = if (japanese) {
            runCatching { JapaneseRomanizer.romanize(text) }.getOrNull()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text)
            }.getOrNull()
        } else {
            null
        }
        val clean = out?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() && !DisplayNames.needsLatin(it) } ?: return null
        return clean.replaceFirstChar { it.uppercaseChar() }
    }
}
