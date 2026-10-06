package com.emfitsolutions.gopreach.ui.components.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A single metric card in a 2-column grid — a plain, neutral card with a
 * label and a bold value underneath. Per explicit request, no icon and no
 * per-item color coding: every card uses the same neutral surface and text
 * colors, distinguished only by its label/value, not a color or a glyph.
 *
 * Clickable (spec: "make the button clickable... show the details inside
 * when clicked") — [onClick] is required, not optional, so every card in
 * the grid behaves the same way; the caller decides what "details" means
 * for that particular figure (see [com.emfitsolutions.gopreach.ui.screens.dashboard.DashboardStatsContent]).
 */
@Composable
fun StatCard(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
