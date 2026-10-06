package com.emfitsolutions.gopreach.ui.components

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The reusable "Congregation + Date Range" filter — spec's Main Form date
 * range filtering, built once so every applicable report screen can use the
 * same control instead of a bespoke picker per screen (spec §7/§8).
 * [range] defaults to [DateRange.thisMonth] wherever a caller first creates
 * one (spec §4 — "This Month" selected on open, calculated live, never
 * hard-coded). Congregation selection, where applicable, stays that screen's
 * own existing dropdown — this bar is just the date half — so scope/
 * permission enforcement is untouched (spec §10): a date range only ever
 * narrows *within* whatever congregation(s) the caller already resolved the
 * signed-in session is authorized to see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeFilterBar(
    range: DateRange,
    onRangeChange: (DateRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    fun pickDate(initialMillis: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialMillis }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply { set(year, month, day) }
                onPicked(picked.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Date Range", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // A scrollable Row, not a wrapping one — five chips (four presets +
        // Custom once active) stay on one line on any phone width rather than
        // crowding the Main Form (spec §14), matching the reference's
        // "[ Today ] [ This Week ] [ This Month ] [ This Year ]" single-row look.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QuickDateRange.entries.filter { it != QuickDateRange.CUSTOM || range.option == QuickDateRange.CUSTOM }) { option ->
                FilterChip(
                    selected = range.option == option,
                    onClick = {
                        onRangeChange(
                            when (option) {
                                QuickDateRange.TODAY -> DateRange.today()
                                QuickDateRange.THIS_WEEK -> DateRange.thisWeek()
                                QuickDateRange.THIS_MONTH -> DateRange.thisMonth()
                                QuickDateRange.THIS_YEAR -> DateRange.thisYear()
                                QuickDateRange.CUSTOM -> range
                            },
                        )
                    },
                    label = { Text(option.label.uppercase()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    pickDate(range.startMillis) { newStart ->
                        onRangeChange(DateRange.custom(newStart, range.endMillis))
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("From: ${dateFormat.format(range.startMillis)}") }
            OutlinedButton(
                onClick = {
                    pickDate(range.endMillis) { newEnd ->
                        onRangeChange(DateRange.custom(range.startMillis, newEnd))
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("To: ${dateFormat.format(range.endMillis)}") }
        }
    }
}
