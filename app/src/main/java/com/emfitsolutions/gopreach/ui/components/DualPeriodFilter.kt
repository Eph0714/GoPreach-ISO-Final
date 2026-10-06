package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Calendar
import java.util.Date
import java.util.Locale

/** An inclusive run of calendar months, each stored as the millisecond start of that month. */
data class MonthRange(val start: Long, val end: Long) {
    /** Null when the range is usable; otherwise the message to show. The selection itself is never silently changed. */
    fun error(): String? = if (end < start) "End month cannot be earlier than the start month." else null

    val isValid: Boolean get() = error() == null

    /** Every month in the range, oldest first (empty when invalid). */
    fun months(): List<Long> {
        if (!isValid) return emptyList()
        val result = mutableListOf<Long>()
        val cursor = Calendar.getInstance().apply { timeInMillis = start }
        while (cursor.timeInMillis <= end && result.size < MAX_MONTHS) {
            result += cursor.timeInMillis
            cursor.add(Calendar.MONTH, 1)
        }
        return result
    }

    val monthCount: Int get() = months().size

    /** "July 2025 – January 2026", or just "July 2025" for a single month. */
    fun label(format: SimpleDateFormat = labelFormat()): String =
        if (start == end) format.format(Date(start)) else "${format.format(Date(start))} – ${format.format(Date(end))}"

    companion object {
        const val MAX_MONTHS = 60
        private fun labelFormat() = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        fun monthStart(monthsAgo: Int = 0): Long = Calendar.getInstance().apply {
            add(Calendar.MONTH, -monthsAgo)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** A single-month range [monthsAgo] months back. */
        fun ofMonth(monthsAgo: Int = 0) = monthStart(monthsAgo).let { MonthRange(it, it) }
    }
}

/** Months offered by the pickers: the last [MonthRange.MAX_MONTHS] months up to and including this one, newest first. */
fun pickableMonths(): List<Long> = (0 until MonthRange.MAX_MONTHS).map { MonthRange.monthStart(it) }

/**
 * The one Period A / Period B selector shared by every report that compares two month ranges (Comparative Report,
 * Field Service Report, future reports) so they all validate the same way. Each period has its own start and end month;
 * the two periods may be different lengths. Nothing is applied until [applyLabel] is tapped, and an invalid range
 * (end before start) is flagged in place and blocks Apply — it is never silently corrected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DualPeriodFilter(
    initialA: MonthRange,
    initialB: MonthRange,
    onApply: (MonthRange, MonthRange) -> Unit,
    modifier: Modifier = Modifier,
    applyLabel: String = "Compare",
) {
    var a by remember { mutableStateOf(initialA) }
    var b by remember { mutableStateOf(initialB) }
    val months = remember { pickableMonths() }
    val format = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val errorA = a.error()
    val errorB = b.error()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PeriodRow("First Range", a, months, format, errorA) { a = it }
            PeriodRow("Second Range", b, months, format, errorB) { b = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onApply(a, b) },
                enabled = errorA == null && errorB == null,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Rounded.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(applyLabel, fontSize = 13.sp)
            }
            Text(
                "First: ${a.monthCount} month(s) · Second: ${b.monthCount} month(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeriodRow(title: String, range: MonthRange, months: List<Long>, format: SimpleDateFormat, error: String?, onChange: (MonthRange) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonthChip(range.start, months, format, isError = error != null) { onChange(range.copy(start = it)) }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "to", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            MonthChip(range.end, months, format, isError = error != null) { onChange(range.copy(end = it)) }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Compact month picker: a ~34dp chip that opens a scrollable list of months. */
@Composable
private fun MonthChip(selected: Long, months: List<Long>, format: SimpleDateFormat, isError: Boolean, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(34.dp),
    ) {
        Row(modifier = Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(format.format(Date(selected)), fontSize = 13.sp, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose month", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(modifier = Modifier.height(280.dp).width(150.dp).verticalScroll(rememberScrollState())) {
                // A selected month older than the list (e.g. a restored value) is still offered so it can be re-picked.
                (if (selected in months) months else listOf(selected) + months).forEach { month ->
                    DropdownMenuItem(text = { Text(format.format(Date(month)), fontSize = 13.sp) }, onClick = { onSelect(month); expanded = false })
                }
            }
        }
    }
}
