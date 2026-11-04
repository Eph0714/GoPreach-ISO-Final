package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's month selection: "‹ November 2026 ›". The arrows step to the previous / next month; there is no dropdown list. An arrow with nowhere to go is dimmed.
 * [labels] are in time order, oldest first (or newest first with [newestFirst]); [selectedIndex] may be -1 when the current value is not in the list (then the arrows pick the nearest end).
 * Also used for any other short, ordered list that was a dropdown of months (for example the Earlier / Later columns of a comparison).
 */
@Composable
fun ArrowListPicker(
    labels: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    compact: Boolean = false,
    newestFirst: Boolean = false,
    shownLabel: String? = null,
) {
    val step = if (newestFirst) -1 else 1
    val previous = if (selectedIndex < 0) null else (selectedIndex - step).takeIf { it in labels.indices }
    val next = if (selectedIndex < 0) null else (selectedIndex + step).takeIf { it in labels.indices }
    val text = shownLabel ?: labels.getOrNull(selectedIndex).orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(if (compact) 10.dp else 14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(if (compact) 34.dp else 52.dp)) {
                IconButton(enabled = previous != null, onClick = { previous?.let(onPick) }, modifier = Modifier.size(if (compact) 34.dp else 48.dp)) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous", modifier = Modifier.size(if (compact) 18.dp else 24.dp))
                }
                Text(
                    text,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).widthIn(min = if (compact) 80.dp else 120.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(enabled = next != null, onClick = { next?.let(onPick) }, modifier = Modifier.size(if (compact) 34.dp else 48.dp)) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Next", modifier = Modifier.size(if (compact) 18.dp else 24.dp))
                }
            }
        }
    }
}

/** [ArrowListPicker] over a list of months (epoch millis, any order): shows [formatMonth] of the selected one and steps to the neighbouring months that exist. */
@Composable
fun ArrowMonthPicker(
    months: List<Long>,
    selected: Long,
    formatMonth: (Long) -> String,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    compact: Boolean = false,
) {
    // A selected month that is not in the list (a restored value) is still shown and can be stepped away from.
    val sorted = (if (selected in months) months else months + selected).distinct().sorted()
    ArrowListPicker(
        labels = sorted.map(formatMonth),
        selectedIndex = sorted.indexOf(selected),
        onPick = { onSelect(sorted[it]) },
        modifier = modifier.then(if (compact) Modifier else Modifier.fillMaxWidth()),
        title = title,
        compact = compact,
    )
}
