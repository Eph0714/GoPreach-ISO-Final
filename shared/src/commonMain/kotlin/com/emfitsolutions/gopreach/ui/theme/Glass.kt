package com.emfitsolutions.gopreach.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the screen is showing the dark theme (decided from the theme's own background, so it follows the app setting, not only the phone's). */
@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * An accent colour that is readable as TEXT or an icon on the current surface. Accents are picked to look right on light backgrounds; on the dark theme
 * they are lightened (a deep indigo or green would nearly disappear on near-black), on the light theme they are used as they are.
 */
@Composable
fun readableAccent(accent: Color): Color = if (isDarkSurface() && accent.luminance() < 0.45f) lerp(accent, Color.White, 0.5f) else accent

/**
 * Background and text colour for one cell of a report table. A transparent cell takes the theme's own text colour (the old fixed black text vanished on
 * the dark theme). A light pastel fill stays pastel with dark text on the light theme; on the dark theme it becomes a soft translucent tint under the
 * theme's text colour, so headings and highlights keep their colour coding without glaring white boxes.
 */
@Composable
fun cellColors(fill: Color): Pair<Color, Color> {
    val onSurface = MaterialTheme.colorScheme.onSurface
    if (fill == Color.Transparent || fill.alpha < 0.05f) return Color.Transparent to onSurface
    val light = fill.luminance() > 0.5f
    return when {
        isDarkSurface() && light -> fill.copy(alpha = 0.24f) to onSurface
        isDarkSurface() -> fill to Color.White
        light -> fill to Color(0xFF1B1B1B)
        else -> fill to Color.White
    }
}

/** The thin line between table cells: visible on both themes. */
@Composable
fun cellBorder(): Color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDarkSurface()) 0.6f else 0.9f)

/**
 * A frosted-glass panel: a translucent, softly tinted gradient with a bright hairline along the top-left edge fading out toward the bottom-right, and a
 * faint shadow. It keeps the theme's own text colours readable (the tint is subtle and the fill is never lighter than the text on dark, nor darker on
 * light). [tint] colours the glass (default: the theme's primary).
 */
@Composable
fun Modifier.glass(shape: Shape = RoundedCornerShape(18.dp), tint: Color = MaterialTheme.colorScheme.primary, elevation: Dp = 6.dp): Modifier {
    val dark = isDarkSurface()
    val base = MaterialTheme.colorScheme.surface
    val top = lerp(base, tint, if (dark) 0.14f else 0.06f).copy(alpha = if (dark) 0.78f else 0.86f)
    val bottom = lerp(base, tint, if (dark) 0.05f else 0.02f).copy(alpha = if (dark) 0.92f else 0.96f)
    val edgeStart = Color.White.copy(alpha = if (dark) 0.30f else 0.95f)
    val edgeEnd = (if (dark) Color.White else tint).copy(alpha = if (dark) 0.05f else 0.12f)
    return this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .background(Brush.linearGradient(listOf(top, bottom)))
        .border(1.dp, Brush.linearGradient(listOf(edgeStart, edgeEnd)), shape)
}

/** A card with the [glass] look. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    Box(modifier.glass(shape, tint)) { content() }
}

/**
 * The background of the big coloured header bars (welcome header, login hero, banner). On the light theme it is the solid primary colour. On the dark
 * theme the primary colour is a LIGHT tone, which makes the white header text hard to read, so the bar becomes a deep, softly graded shade of it.
 */
@Composable
fun Modifier.heroBackground(): Modifier {
    val primary = MaterialTheme.colorScheme.primary
    return if (isDarkSurface()) {
        this.background(Brush.verticalGradient(listOf(lerp(primary, Color.Black, 0.42f), lerp(primary, Color.Black, 0.68f))))
    } else {
        this.background(primary)
    }
}
