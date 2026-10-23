package com.emfitsolutions.gopreach.ui.screens.login

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

/**
 * The look of the website's sign-in page, as the app's sign-in screen: the soft lavender sky, slow drifting light and three layered misty mountains behind a
 * rounded card, with the brand colours fixed (they do not follow the phone's dynamic colours, exactly as the website's do not).
 */
internal class LoginPalette(val dark: Boolean) {
    val sky = if (dark) listOf(Color(0xFF130F26), Color(0xFF1A1433), Color(0xFF251C4A)) else listOf(Color(0xFFFAF8FF), Color(0xFFEFE9FC), Color(0xFFE2D9F8))
    val card = if (dark) Color(0xFF181527) else Color(0xFFFFFFFF)
    val line = if (dark) Color(0xFF302B4A) else Color(0xFFE1DAF3)
    val text = if (dark) Color(0xFFECE9F8) else Color(0xFF1D1A3A)
    val muted = if (dark) Color(0xFFA9A3C4) else Color(0xFF6A6585)
    val primary = if (dark) Color(0xFFA995F0) else Color(0xFF6B4FD1)
    val field = if (dark) Color(0xFF221E38) else Color(0xFFF7F4FD)
    /** The medium-yellow Sign In button and its dark label. */
    val yellow = Color(0xFFF2B705)
    val onYellow = if (dark) Color.White else Color(0xFF1F1B4D)
}

@Composable
internal fun loginFieldColors(p: LoginPalette): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = p.primary, unfocusedBorderColor = p.line, disabledBorderColor = p.line,
    focusedContainerColor = p.field, unfocusedContainerColor = p.field, disabledContainerColor = p.field,
    focusedTextColor = p.text, unfocusedTextColor = p.text, disabledTextColor = p.muted,
    focusedLeadingIconColor = p.primary, unfocusedLeadingIconColor = p.muted, disabledLeadingIconColor = p.muted,
    focusedTrailingIconColor = p.muted, unfocusedTrailingIconColor = p.muted,
    focusedPlaceholderColor = p.muted, unfocusedPlaceholderColor = p.muted, cursorColor = p.primary,
)

/** One mountain ridge in the website's 1200 x 520 drawing: a start, one explicit curve, then smooth curves through the listed points. */
private class Ridge(val startY: Float, val ctrl: Offset, val first: Offset, val rest: List<Offset>, val top: Color, val bottom: Color)

private val RIDGES = listOf(
    Ridge(300f, Offset(120f, 230f), Offset(230f, 280f), listOf(Offset(430f, 250f), Offset(640f, 300f), Offset(860f, 240f), Offset(1200f, 310f)), Color(0xBFD9CDF7), Color(0x33CBBCF3)),
    Ridge(370f, Offset(150f, 300f), Offset(280f, 350f), listOf(Offset(520f, 330f), Offset(760f, 380f), Offset(1010f, 330f), Offset(1200f, 380f)), Color(0xD9B19DF0), Color(0x59A08AE8)),
    Ridge(440f, Offset(160f, 390f), Offset(320f, 430f), listOf(Offset(620f, 420f), Offset(900f, 450f), Offset(1200f, 420f)), Color(0xFF8B72DD), Color(0xFF6C52C6)),
)

private fun Ridge.path(): Path = Path().apply {
    moveTo(0f, startY)
    quadraticTo(ctrl.x, ctrl.y, first.x, first.y)
    var control = ctrl; var at = first
    for (end in rest) { // an SVG "T": the control point is the last one mirrored through the current point
        control = Offset(2 * at.x - control.x, 2 * at.y - control.y)
        quadraticTo(control.x, control.y, end.x, end.y)
        at = end
    }
    lineTo(1200f, 520f); lineTo(0f, 520f); close()
}

@Composable
internal fun LoginBackdrop(p: LoginPalette, modifier: Modifier = Modifier) {
    val drift = rememberInfiniteTransition(label = "login-drift")
    val slow by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "slow")
    val mid by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "mid")
    val ridges = androidx.compose.runtime.remember { RIDGES.map { it to it.path() } }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(p.sky, start = Offset.Zero, end = Offset(size.width * 0.35f, size.height)))
            // two soft lights drifting slowly
            val a = Offset(size.width * 0.05f + (mid - 0.5f) * 60f, size.height * 0.08f + (slow - 0.5f) * 50f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFC9B8F6).copy(alpha = if (p.dark) 0.18f else 0.6f), Color.Transparent), a, size.width * 0.75f), size.width * 0.75f, a)
            val b = Offset(size.width * 0.98f - (mid - 0.5f) * 50f, size.height * 0.92f - (slow - 0.5f) * 60f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFA58DF0).copy(alpha = if (p.dark) 0.2f else 0.55f), Color.Transparent), b, size.width * 0.6f), size.width * 0.6f, b)
            // the mountains: wider than the screen on a phone, anchored to the bottom, each ridge drifting at its own pace
            val k = size.width * 1.3f / 1200f
            ridges.forEachIndexed { i, (ridge, path) ->
                val shift = ((if (i == 1) slow else mid) - 0.5f) * (if (i == 1) -36f else 36f)
                translate(left = -size.width * 0.15f + shift, top = size.height - 520f * k) {
                    scale(k, k, pivot = Offset.Zero) {
                        drawPath(path, Brush.verticalGradient(listOf(ridge.top, ridge.bottom), startY = ridge.startY - 90f, endY = 520f))
                    }
                }
            }
        }
    }
}
