package com.emfitsolutions.gopreach.ui.screens.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.PlannerSection
import com.emfitsolutions.gopreach.ui.components.DateRange
import com.emfitsolutions.gopreach.ui.components.QuickDateRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top of the unified Dashboard / My Planner card: title, today's date and
 * the reporting period on the left, Planner Sections settings on the right,
 * then one compact Today / Week / Month / Year selector. The selector writes
 * the same shared date range the rest of the app's reports read, so the
 * planner and every summary always agree on the period.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedPlannerHeader(
    range: DateRange,
    /** "Weekly"/"Monthly"/... when the user drilled into a finer view. */
    backLabel: String?,
    onBack: () -> Unit,
    onOpenSections: () -> Unit,
    onSelectPeriod: (QuickDateRange) -> Unit,
    // "Add a multi-month Comparative Report" — a 5th pill alongside Today/
    // Week/Month/Year that does NOT touch the shared Dashboard/Planner
    // [range] (a Comparative Report isn't a single period), so it's tracked
    // as its own selection rather than a [QuickDateRange] value.
    isCompareActive: Boolean = false,
    onSelectCompare: () -> Unit = {},
) {
    val todayLabel = remember { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date()) }
    val periodFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val periodLabel = if (range.option == QuickDateRange.TODAY) {
        "Today"
    } else {
        "${periodFormat.format(Date(range.startMillis))} – ${periodFormat.format(Date(range.endMillis))}"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // "Add a back icon... there's no way to get back" — a real,
            // unmistakable circular icon button at the leading edge whenever
            // a drill-down (Month/Week/Year → Day, or Year → Month) is
            // active, not just a small text link easy to miss. The system
            // Back gesture/button still works too (see the BackHandler this
            // screen's caller wires up alongside onBack).
            if (backLabel != null) {
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to $backLabel")
                }
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("My Planner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (backLabel != null) "Back to $backLabel" else todayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (backLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (backLabel != null) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenSections) {
                Icon(Icons.Rounded.Tune, contentDescription = "Planner Sections", tint = MaterialTheme.colorScheme.primary)
            }
        }

        val options = listOf(
            QuickDateRange.TODAY to "Today",
            QuickDateRange.THIS_WEEK to "Week",
            QuickDateRange.THIS_MONTH to "Month",
            QuickDateRange.THIS_YEAR to "Year",
        )
        // "Selected: 100% theme color. Not selected: slightly transparent
        // version of the same theme color" — Material3's own default
        // SegmentedButton colors lean on secondaryContainer for the
        // selected state, which doesn't read as "the app's theme color" the
        // way a solid `primary` fill does, and its own unselected state is
        // a flat surface tint rather than a translucent tint of that same
        // color. Both overridden explicitly here so this always tracks
        // whatever accent color the Publisher has picked (colorScheme.primary
        // already does, live, via GoPreachTheme) rather than a separate,
        // hard-coded pair of colors.
        val segmentedColors = SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.primary,
            activeContentColor = MaterialTheme.colorScheme.onPrimary,
            activeBorderColor = MaterialTheme.colorScheme.primary,
            inactiveContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            inactiveContentColor = MaterialTheme.colorScheme.primary,
            inactiveBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp)) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = !isCompareActive && range.option == option,
                    onClick = { onSelectPeriod(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size + 1),
                    icon = {},
                    colors = segmentedColors,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            SegmentedButton(
                selected = isCompareActive,
                onClick = onSelectCompare,
                shape = SegmentedButtonDefaults.itemShape(index = options.size, count = options.size + 1),
                icon = {},
                colors = segmentedColors,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text("Compare", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        if (!isCompareActive) {
            Text(
                "Reporting period: $periodLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** "Planner Sections" — the Publisher's own show/hide switches. Changes
 * apply immediately and are saved to their account; nothing is deleted. */
@Composable
internal fun PlannerSectionsDialog(
    visibility: PlannerVisibility,
    onToggle: (PlannerSection, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Planner Sections") },
        text = {
            Column {
                Text(
                    "Choose what appears in your planner. Hiding a section never deletes its records — turn it back on anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                PlannerSection.entries.forEach { section ->
                    val checked = when (section) {
                        PlannerSection.HOURS -> visibility.hours
                        PlannerSection.CREDIT_HOURS -> visibility.creditHours
                        PlannerSection.RETURN_VISITS -> visibility.returnVisits
                        PlannerSection.BIBLE_STUDIES -> visibility.bibleStudies
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Checkbox) { onToggle(section, !checked) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(section.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
