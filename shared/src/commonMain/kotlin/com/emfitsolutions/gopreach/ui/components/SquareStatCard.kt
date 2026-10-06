package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * "Role-Based Publisher Dashboard" spec §2-§6/§29 — a square (1:1)
 * statistic card: title first, then a large, visually dominant value,
 * clickable through to the record/report it summarizes. Deliberately its
 * own component, not a reuse of [com.emfitsolutions.gopreach.ui.components
 * .charts.StatCard] (the Admin Dashboard's non-square KPI card) — this
 * spec's explicit "square-shaped," "value more visually prominent than the
 * title," and "do not shrink text to force a square" requirements don't fit
 * that component's shape.
 *
 * [aspectRatio(1f)] plus the caller sizing this with `Modifier.weight(1f)`
 * inside a `Row` (see [PublisherHomeScreen]) is what makes it "responsive to
 * different screen sizes" (spec §5) — the square scales with the row's own
 * width, never a fixed dp size that would look tiny on a tablet or crowd a
 * small phone.
 */
@Composable
fun SquareStatCard(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
            )
            Text(
                value,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
