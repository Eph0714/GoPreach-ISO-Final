package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The hero panel used behind the login screen's content — a solid-color take
 * on [AppBanner]'s panel with soft decorative shapes for a bit of visual
 * interest on the app's very first screen, in the same spirit as a typical
 * "welcome" hero mockup (diagonal streaks, translucent blobs) but restrained
 * to keep the look "professional and simple" rather than busy.
 *
 * "Use solid color in themes, not gradient color" — flat [MaterialTheme
 * .colorScheme.primary] instead of the earlier primaryContainer-to-primary
 * gradient, matching [com.emfitsolutions.gopreach.ui.components.DashboardHero]'s
 * own already-flat panel.
 */
@Composable
fun GradientHero(
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            // Theme-driven: deep purple in light mode, bright purple on #121212 in dark mode.
            .background(MaterialTheme.colorScheme.primary),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Two soft translucent circles, echoing the blob shapes in the reference.
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = w * 0.55f,
                center = Offset(w * 1.05f, h * 0.15f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = w * 0.4f,
                center = Offset(w * -0.1f, h * 0.9f),
            )

            // A few thin diagonal streaks for texture.
            rotate(degrees = -35f, pivot = Offset(w * 0.5f, h * 0.5f)) {
                repeat(3) { i ->
                    val x = w * (0.15f + i * 0.28f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.10f),
                        start = Offset(x, -h * 0.2f),
                        end = Offset(x, h * 1.2f),
                        strokeWidth = 3f,
                    )
                }
            }
        }
        content()
    }
}
