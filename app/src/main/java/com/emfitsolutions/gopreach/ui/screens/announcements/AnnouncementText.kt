package com.emfitsolutions.gopreach.ui.screens.announcements

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The basic formatting an announcement supports, typed as plain text so it travels and stores safely: **bold**, _italic_ and "- " bullet lines.
 * Nothing else is interpreted (no links or markup), so an announcement can never carry anything but styled text.
 */
fun formatAnnouncementText(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        val line = if (raw.startsWith("- ")) "•  " + raw.drop(2) else raw
        var bold = false
        var italic = false
        var i = 0
        fun style() = SpanStyle(fontWeight = if (bold) FontWeight.Bold else null, fontStyle = if (italic) FontStyle.Italic else null)
        while (i < line.length) {
            when {
                line.startsWith("**", i) -> { bold = !bold; i += 2 }
                line[i] == '_' && isItalicMarker(line, i, italic) -> { italic = !italic; i++ }
                else -> {
                    val next = (i + 1 until line.length).firstOrNull { line.startsWith("**", it) || line[it] == '_' } ?: line.length
                    withStyle(style()) { append(line.substring(i, next)) }
                    i = next
                }
            }
        }
    }
}

/** An underscore is a style marker only at the edge of a word (so file_names and snake_case stay as typed). */
private fun isItalicMarker(line: String, at: Int, open: Boolean): Boolean {
    val before = line.getOrNull(at - 1)
    val after = line.getOrNull(at + 1)
    return if (open) before != null && !before.isWhitespace() && (after == null || !after.isLetterOrDigit())
    else (before == null || before.isWhitespace() || !before.isLetterOrDigit()) && after != null && !after.isWhitespace()
}

@Composable
fun AnnouncementText(text: String, modifier: Modifier = Modifier) {
    val formatted = remember(text) { formatAnnouncementText(text) }
    Text(formatted, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}
