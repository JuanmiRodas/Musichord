package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * BitChord Audio: the canvas button's three faces — an album sleeve with its record (still
 * covers), a tall phone-shaped loop with a play mark (Spotify's canvas), and a sleeve with
 * motion arcs beside it (animated covers).
 */
internal object CanvasIcons {
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},$y H${x + w - r} A$r,$r 0 0 1 ${x + w},${y + r} V${y + h - r} A$r,$r 0 0 1 ${x + w - r},${y + h} " +
            "H${x + r} A$r,$r 0 0 1 $x,${y + h - r} V${y + r} A$r,$r 0 0 1 ${x + r},$y Z "

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy A$r,$r 0 1 0 ${cx + r},$cy A$r,$r 0 1 0 ${cx - r},$cy Z "

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    /** A sleeve (outlined) with its record peeking out, the label's hole in it. */
    val StillCover: ImageVector by lazy {
        icon(
            "stillCover",
            rr(2.5f, 4f, 14f, 16f, 2.5f) + rr(4.3f, 5.8f, 10.4f, 12.4f, 1.2f) +
                circle(9.5f, 12f, 3.2f) + circle(9.5f, 12f, 1.1f) +
                "M17.2,6.2 A6.3,6.3 0 0 1 17.2,17.8 Z",
        )
    }

    /** A tall loop (outlined) with a play mark inside. */
    val SpotifyCanvas: ImageVector by lazy {
        icon(
            "spotifyCanvas",
            rr(6f, 2f, 12f, 20f, 3f) + rr(7.8f, 3.8f, 8.4f, 16.4f, 1.8f) +
                "M10.3,8.6 L15.4,12 L10.3,15.4 Z",
        )
    }

    /** A sleeve (outlined) with a play mark, and motion arcs at its side. */
    val AnimatedCover: ImageVector by lazy {
        icon(
            "animatedCover",
            rr(2f, 5f, 14f, 14f, 2.5f) + rr(3.8f, 6.8f, 10.4f, 10.4f, 1.2f) +
                "M7.3,9 L12.3,12 L7.3,15 Z " +
                rr(17.6f, 7f, 1.6f, 10f, 0.8f) + rr(20.6f, 9f, 1.6f, 6f, 0.8f),
        )
    }
}
