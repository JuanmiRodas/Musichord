package com.music.bitchord.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: the battery button (bottom row) — what is drawn and worked out just to
 * look good, turned off a step at a time:
 *  1. moving pictures: animated covers, the moving background, the lyrics' lift and glow
 *     and the buttons' bounce;
 *  2. also the timeline's waves, the liquid glass and the sound analysis behind them, and
 *     the next song's video made ready ahead;
 *  3. also the player on OLED black;
 * and a fourth press back to everything. Held: each of these on its own.
 */
object PowerSaving {
    enum class Feature(val id: String, val level: Int) {
        ANIMATED_COVERS("animated_covers", 1),
        BACKGROUND_MOTION("background_motion", 1),
        LYRICS_EFFECTS("lyrics_effects", 1),
        UI_ANIMATIONS("ui_animations", 1),
        WAVES("waves", 2),
        LIQUID_GLASS("liquid_glass", 2),
        SOUND_ANALYSIS("sound_analysis", 2),
        VIDEO_AHEAD("video_ahead", 2),
        // Inverted: "on" here is the OLED black (saves the screen's power).
        OLED_BLACK("oled_black", 3),
    }

    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level.asStateFlow()

    /** Feature id → kept on (for OLED_BLACK: black on). */
    private val _on = MutableStateFlow(defaults())
    val on: StateFlow<Map<String, Boolean>> = _on.asStateFlow()

    /** Set by the app: keeps the choice between runs. */
    @Volatile var store: ((level: Int, on: Map<String, Boolean>) -> Unit)? = null

    private fun defaults() = Feature.entries.associate { it.id to (it != Feature.OLED_BLACK) }

    private fun forLevel(level: Int) = Feature.entries.associate { f ->
        f.id to if (f == Feature.OLED_BLACK) level >= f.level else level < f.level
    }

    fun restore(level: Int, on: Map<String, Boolean>) {
        _level.value = level.coerceIn(0, 3)
        _on.value = defaults() + on.filterKeys { k -> Feature.entries.any { it.id == k } }
    }

    /** The button: one step further, round to everything on after the third. */
    fun cycle() = setLevel((_level.value + 1) % 4)

    fun setLevel(level: Int) {
        _level.value = level
        _on.value = forLevel(level)
        store?.invoke(_level.value, _on.value)
    }

    /** One switch of the held menu (the level stays as the button shows it). */
    fun set(feature: Feature, value: Boolean) {
        _on.value = _on.value + (feature.id to value)
        store?.invoke(_level.value, _on.value)
    }

    fun isOn(feature: Feature): Boolean = _on.value[feature.id] ?: (feature != Feature.OLED_BLACK)

    val animatedCovers get() = isOn(Feature.ANIMATED_COVERS)
    val backgroundMotion get() = isOn(Feature.BACKGROUND_MOTION)
    val lyricsEffects get() = isOn(Feature.LYRICS_EFFECTS)
    val uiAnimations get() = isOn(Feature.UI_ANIMATIONS)
    val waves get() = isOn(Feature.WAVES)
    val liquidGlass get() = isOn(Feature.LIQUID_GLASS)
    val soundAnalysis get() = isOn(Feature.SOUND_ANALYSIS)
    val videoAhead get() = isOn(Feature.VIDEO_AHEAD)
    val oledBlack get() = isOn(Feature.OLED_BLACK)
}
