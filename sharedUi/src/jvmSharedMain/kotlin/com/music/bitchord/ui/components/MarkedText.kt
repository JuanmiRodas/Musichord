package com.music.bitchord.ui.components

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em

/** BitChord Audio: the mark your own music carries in a subtitle ("☁ pCloud"), drawn as the app's cloud icon. */
const val OWN_MARK = "☁"

/**
 * A subtitle line; a leading [OWN_MARK] is drawn as the rounded cloud icon, in the
 * text's own colour and size, as the app's other icons are.
 */
@Composable
fun MarkedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    if (!text.startsWith(OWN_MARK)) {
        Text(text = text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val rest = text.removePrefix(OWN_MARK).trimStart()
    val annotated = buildAnnotatedString {
        appendInlineContent("own", "[cloud]")
        append(" ")
        append(rest)
    }
    val inline = mapOf(
        "own" to InlineTextContent(Placeholder(1.15.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = color)
        },
    )
    Text(text = annotated, inlineContent = inline, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
