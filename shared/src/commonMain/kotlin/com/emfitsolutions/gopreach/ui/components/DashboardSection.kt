package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A titled group of [DashboardTile]s (spec §4: "Group related functions
 * logically" — Main Functions / Reports / System, not a random grid) that
 * wraps to as many rows as needed via [FlowRow] — the "avoid overcrowding"
 * requirement (spec §10) without a separate scrollable-tab or drawer-only
 * approach: a phone gets 3 tiles per row, a tablet fits more, nothing is
 * ever clipped or forced to shrink to fit a fixed column count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}
