package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One action button in the Main Form — currently only reached in practice by
 * [PublisherHomeScreen] (Coordinator/Regular Elder/Admin's own tile grid has
 * been hidden in favor of the drawer+stats Main Form since the Elder
 * Dashboard consistency work, so this component's real-world usage is
 * Publisher-only today).
 *
 * "Publishers UI — bigger, colored buttons" spec: a filled [Button] — not the
 * previous plain-outline, no-icon style — full width, taller than the stock
 * default, with its icon restored and the label in a larger weight, so it
 * reads as a primary action rather than a low-emphasis link. The fill color
 * is [MaterialTheme.colorScheme.primary], which already *is* "the current
 * theme" everywhere else in the app (the Control Panel's theme-color picker
 * changes this same token) — there's no separate hardcoded color to keep in
 * sync, using the theme token directly *is* the sync.
 */
@Composable
fun DashboardTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        // Icon at a fixed size so it never squeezes the label; the label
        // itself is capped to one line with an ellipsis rather than wrapping
        // — this Button has a fixed 56.dp height, so an unbounded/2-line
        // label would silently get clipped top and bottom instead of
        // visibly truncating.
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp).padding(end = 12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
