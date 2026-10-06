package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The label every record list shows: "Record Found: 35". One format everywhere. */
fun recordFoundText(count: Int): String = "Record Found: $count"

/**
 * The standard "Record Found: X" indicator for any screen that lists records. [count] must be the size of the
 * list that is actually on screen — after search, filters, congregation scope and record status have all been
 * applied — so it always matches what the user sees. It is shown at zero too (alongside the screen's own empty
 * message), never hidden. Compact, bold and left-aligned so it sits quietly above the list or under the filters.
 */
@Composable
fun RecordFound(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = recordFoundText(count),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
