package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The banner shown on entry/dashboard screens (login, home) — a flat, solid
 * panel in the theme's own primary color ("use solid color in themes, not
 * gradient color"), not currently wired into any screen (both login and
 * home have their own dedicated hero panels — see [GradientHero] and
 * [com.emfitsolutions.gopreach.ui.components.DashboardHero]).
 *
 * The app logo shown here (or a fallback wordmark when no custom logo has been
 * uploaded) is customizable at runtime by the Super-Admin via the Control Panel
 * (Phase 3) — this composable takes a [logoContent] slot so that swap-in is a
 * drop-in replacement, no layout change needed.
 */
@Composable
fun AppBanner(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    logoContent: (@Composable () -> Unit)? = null,
    /** Optional corner action — e.g. a settings gear on a dashboard banner.
     * Left off the login banner, which has nothing to act on yet. */
    topEndAction: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            // Theme-driven, not a hardcoded brand color, so this reads deep purple in
            // light mode and bright purple on #121212 in dark mode (spec: two distinct
            // theme identities, not just a dimmed copy of the light one).
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            logoContent?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (topEndAction != null) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                topEndAction()
            }
        }
    }
}
