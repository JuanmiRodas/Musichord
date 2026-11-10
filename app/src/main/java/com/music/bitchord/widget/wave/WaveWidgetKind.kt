package com.music.bitchord.widget.wave

import androidx.annotation.LayoutRes
import com.music.bitchord.R

enum class WaveWidgetKind(
    @LayoutRes val layoutRes: Int,
    val targetCellWidth: Int,
    val targetCellHeight: Int,
) {
    STRIP(R.layout.widget_wave_strip, 4, 1),
    GLASS(R.layout.widget_wave_glass, 2, 2),
    CIRCULAR(R.layout.widget_wave_circular, 2, 2),
}
