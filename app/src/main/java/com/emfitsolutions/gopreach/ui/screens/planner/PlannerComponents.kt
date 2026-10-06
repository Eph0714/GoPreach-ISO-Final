package com.emfitsolutions.gopreach.ui.screens.planner

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.domain.MinistryStatisticsService.PersonActivitySummary
import com.emfitsolutions.gopreach.ui.components.FormDialog
import java.util.Calendar

/** Shared building blocks for every My Planner view (Day/Week/Month/Year) —
 * the inline Dashboard-embedded views (see `PublisherHomeScreen.kt`) all use
 * these same compact headers, sections, record rows and dialogs so the four
 * views look and behave identically. */

internal fun formatHoursMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** Inner padding every planner view uses below its nav header. */
internal val PlannerBodyPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

// Built per call (not cached in a top-level val) so a locale change while
// the app is running is picked up.
internal fun formatRecordDate(millis: Long): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))

private fun formatFullDate(millis: Long): String = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(millis))

/** "‹ label ›" period stepper at the top of every Planner view — a solid
 * theme-color pill (spec: "solid theme-color fill... readable contrasting
 * text"), always [MaterialTheme.colorScheme.primary] itself so it follows
 * whatever accent color the Publisher has picked, never a separate
 * hard-coded color. */
@Composable
internal fun PlannerDateNavHeader(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    // "Improve Month, Week, and Year navigation" — a compact list icon that
    // opens a jump-to-period picker (see [PeriodListDialog]), so the
    // Publisher isn't limited to paging one period at a time with the
    // arrows. Null (Day's own header) keeps the row exactly as before.
    onOpenList: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous period")
            }
            // "Smooth Motion when changing dates" — a brief cross-fade/slide
            // keyed on the label itself, so paging to the next/previous
            // period (or jumping via the list picker) never just hard-cuts
            // the text in place.
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(150)) { it / 4 })
                        .togetherWith(fadeOut(animationSpec = tween(100)))
                },
                modifier = Modifier.weight(1f),
                label = "PlannerDateNavHeaderLabel",
            ) { animatedLabel ->
                Text(
                    animatedLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next period")
            }
            if (onOpenList != null) {
                IconButton(onClick = onOpenList) {
                    Icon(Icons.AutoMirrored.Rounded.ViewList, contentDescription = "Jump to a different period")
                }
            }
        }
    }
}

/** One row of [PeriodListDialog] — title, an optional subtitle (e.g. a
 * week's date range), and a horizontal divider after it, per spec §5's
 * "consistent list design": compact, no cards, a plain touch target and a
 * separator, nothing heavier. */
@Composable
private fun PeriodListRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Select $title", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

/** "Jump to a different period" — Month/Week/Year's own List View (spec
 * §2-§5): a plain scrollable list of periods with a divider between every
 * row, opened from [PlannerDateNavHeader]'s list icon. Selecting one calls
 * [onSelect] with an arbitrary millis inside that period and dismisses. */
@Composable
internal fun PeriodListDialog(title: String, items: List<Pair<String, String?>>, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(top = 20.dp, start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { index, (itemTitle, subtitle) ->
                        PeriodListRow(title = itemTitle, subtitle = subtitle, onClick = { onSelect(index); onDismiss() })
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Cancel", color = PlannerAccent.Neutral) }
            }
        }
    }
}

/** Thin progress bar shown under the nav header until the view's first
 * data emission arrives, so zeros are never mistaken for real totals. */
@Composable
internal fun PlannerLoadingBar(isLoading: Boolean) {
    if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/** A small bold, primary-colored heading. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

// ---------------------------------------------------------------------------
// Summary strip
// ---------------------------------------------------------------------------

/** [accent] is one fixed color per metric (Hours/Credit/Return Visits/Bible
 * Studies — see [PlannerAccent]), the same one everywhere that metric shows
 * up (this strip, [PlannerGoalRow]'s Goal/Remaining, calendar dots, ...), so
 * a Publisher learns "blue = Hours" once and it holds across Today/Week/
 * Month/Year rather than each period inventing its own palette. */
internal data class PlannerStat(val key: String, val label: String, val value: String, val accent: Color = PlannerAccent.Neutral)

/** The period's four headline figures side by side, each tinted with its
 * own metric's color (spec: "add a color code for Activity Summary") so
 * they're distinguishable at a glance, not four identical gray tiles.
 * Tapping one expands (and so reveals) the section holding the records
 * behind it. */
@Composable
internal fun PlannerSummaryStrip(stats: List<PlannerStat>, onStatClick: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stats.forEach { stat ->
            // A genuinely solid fill (not a faint tint) — the tint version
            // read as barely-there gray on some devices/themes; a solid
            // color block with white (or, for the bright amber Credit
            // Hours tile, dark) text is unmistakable regardless of theme.
            val onAccent = PlannerAccent.onAccent(stat.accent)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = stat.accent,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "Show ${stat.label} records") { onStatClick(stat.key) },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stat.value,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stat.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = onAccent.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Expandable sections
// ---------------------------------------------------------------------------

/**
 * One fixed color per planner metric — "make a consistent location/color
 * code... across the entire date range": every Today/Week/Month/Year view
 * reads the exact same values here, so e.g. Hours is always blue and Goal
 * is always indigo no matter which period is showing. Fixed hex values
 * (not theme-derived) on purpose, same reasoning as [RecordDayColor] — a
 * semantic/status color, not a themed surface, so it stays recognizable
 * regardless of the Publisher's chosen accent color.
 */
internal object PlannerAccent {
    val Neutral = Color(0xFF6B6B6B)
    val Hours = Color(0xFF1E88E5)
    val CreditHours = Color(0xFFF9A825)
    val ReturnVisits = Color(0xFF2E7D32)
    val BibleStudies = Color(0xFF8E24AA)
    /** The Goal figure itself, in [PlannerGoalRow] — a distinct accent from
     * every metric above so "target" reads differently from "actual". */
    val Goal = Color(0xFF3949AB)
    /** Remaining > 0 — "not there yet, this many minutes to go." */
    val Remaining = Color(0xFFEF6C00)
    /** Remaining == 0, or Surplus > 0 — "goal met/exceeded." */
    val GoalMet = Color(0xFF2E7D32)

    /** The readable text/icon color for content sitting on a *solid* fill
     * of [accent] (see [PlannerSummaryStrip]) — white on every accent
     * except the bright amber Credit Hours one, which needs a dark color
     * for real contrast the way [RecordDayColor]'s own text already does. */
    fun onAccent(accent: Color): Color = if (accent == CreditHours) OnRecordDayColor else Color.White
}

/**
 * "Keep each value close enough to its label to clearly belong to it, while
 * maintaining a consistent vertical value column and comfortable spacing
 * between rows" (label/value alignment spec) — the shared label-column
 * width for one visual block of rows (a period view's Goal/Report/Credit
 * Hours/Return Visits/Bible Studies/Notes rows, say), so every row's value
 * lines up at the same x no matter how long its own label is.
 *
 * "Do not override any label [truncate it]" and "make it one line only" —
 * this is always the *full, untruncated* natural width of the longest
 * [labels] string (measured at [style] with [rememberTextMeasurer]; no
 * ConstraintLayout dependency in this project), plus a few dp of slack
 * (measured text vs. a rendered `Text` composable never round-trip to the
 * exact same pixel — without this slack the longest label would clip its
 * very last character). No fixed dp is ever hard-coded, and there is no
 * upper cap: every one of this app's actual label sets (short, fixed
 * strings like "Hours Goal for this Month") comfortably fits real screens
 * at one line without one, and capping it here is exactly what silently
 * truncated a label before.
 */
@Composable
internal fun rememberLabelColumnWidth(labels: List<String>, availableWidth: Dp, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style) {
        val naturalMaxPx = labels.maxOfOrNull { textMeasurer.measure(it, style = style, softWrap = false).size.width } ?: 0
        val slackPx = with(density) { 12.dp.toPx() }
        with(density) { (naturalMaxPx + slackPx).toDp() }
    }
}

/** "1 person" vs "N people" — shared by [PersonSection]'s real summary and
 * by the value-column-width measurement callers build alongside it, so the
 * two can never quietly drift into different wording. */
internal fun personCountSummary(count: Int): String = if (count == 1) "1 person" else "$count people"

internal object PlannerSectionKey {
    const val REPORT = "report"
    const val HOURS = "hours"
    const val CREDIT = "credit"
    const val RETURN_VISITS = "returnVisits"
    const val BIBLE_STUDIES = "bibleStudies"
    const val DAYS = "days"
    const val CALENDAR = "calendar"
    const val MONTHS = "months"
    const val GOALS = "goals"
    const val TIMER = "timer"
}

/** Which planner sections are expanded — survives rotation/process death. */
internal class PlannerExpansionState(initial: List<String>) {
    private val expanded = mutableStateListOf<String>().apply { addAll(initial) }
    fun isExpanded(key: String): Boolean = key in expanded
    fun toggle(key: String) { if (key in expanded) expanded.remove(key) else expanded.add(key) }
    fun expand(key: String) { if (key !in expanded) expanded.add(key) }
    fun snapshot(): List<String> = expanded.toList()
}

@Composable
internal fun rememberPlannerExpansionState(vararg initiallyExpanded: String): PlannerExpansionState =
    rememberSaveable(saver = listSaver(save = { it.snapshot() }, restore = { PlannerExpansionState(it) })) {
        PlannerExpansionState(initiallyExpanded.toList())
    }

/** The small outlined +/− glyph that sits right after a section title. */
@Composable
private fun ExpandToggleGlyph(expanded: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (expanded) Icons.Rounded.Remove else Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A collapsible planner section: `Title (+)` when collapsed, `Title (−)`
 * when expanded, with its records directly underneath. The glyph is small
 * and placed immediately beside the title, but the entire header row is the
 * touch target (≥ 44dp tall, full width), so it stays easy to hit.
 * [summary] is shown right-aligned on the header so the key figure is
 * visible even while collapsed.
 */
@Composable
internal fun PlannerExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    // "The value column should remain aligned vertically across all rows" —
    // when this section's header sits alongside other label/value rows
    // (Goal, Notes, ...) in the same block, the caller passes the shared
    // width [rememberLabelColumnWidth] computed for that whole block, so
    // this row's own summary starts at the exact same x as every sibling's
    // value. Null (the default) keeps the title at its own natural width,
    // for a section used on its own.
    labelColumnWidth: Dp? = null,
    // Same idea one column over — "Align the + icon in Report for this
    // [period], Credit Hours, Return Visit, Bible Studies, Ministry Timer"
    // — every section's own trailing expand/collapse glyph lines up at the
    // same x too, regardless of how long (or absent, e.g. Ministry Timer's
    // own section has no summary at all) this row's own summary is.
    valueColumnWidth: Dp? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse $title" else "Expand $title", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Title stays on the left; the summary now sits right next to the
            // expand/collapse glyph at the row's far right edge (same edge
            // every StepperRow's +/- cluster uses), pushed there by
            // Arrangement.SpaceBetween rather than hugging the title.
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (labelColumnWidth != null) Modifier.width(labelColumnWidth) else Modifier,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 12.dp).then(if (valueColumnWidth != null) Modifier.width(valueColumnWidth) else Modifier),
                    )
                } else if (valueColumnWidth != null) {
                    // No summary on this row (Ministry Timer) — an empty spacer
                    // the same width as every sibling's value keeps the glyph
                    // that follows lined up with theirs anyway.
                    Spacer(modifier = Modifier.padding(end = 12.dp).width(valueColumnWidth))
                }
                ExpandToggleGlyph(expanded)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp)) { content() }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** One tappable record inside a section: title/subtitle on the left, the
 * figure on the right, and a chevron signalling it opens a detail view. */
@Composable
internal fun PlannerRecordRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onClickLabel: String? = null,
    emphasize: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasize) FontWeight.Bold else null,
                color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun PlannerEmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * The four record sections every period view shares — Hours/Minutes days,
 * Credit Hour entries, Return Visit people, Bible Study people — each row
 * opening its own detail. Return Visit / Bible Study rows are one per
 * unique person, so each list's length is exactly that period's count.
 */
@Composable
internal fun PlannerRecordSections(
    records: PlannerPeriodRecords,
    expansion: PlannerExpansionState,
    categoryName: (String) -> String,
    onOpenCredit: (CreditHourRecord) -> Unit,
    onAddCredit: () -> Unit,
    /** True when the period's month report is already submitted — Credit Hours can't be added and the full Return Visit / Bible Study lists are closed. */
    periodLocked: Boolean = false,
    onOpenPerson: (PipelineStage, String) -> Unit,
    /** Opens the full Return Visit / Bible Study list (where new people
     * and visits are added). */
    onOpenPersonList: (PipelineStage) -> Unit,
    /** Null hides the Hours/Minutes section (the Day view edits hours with
     * its own steppers instead). */
    onOpenDay: ((Long) -> Unit)?,
    showDates: Boolean = true,
    // Same shared columns every other row in this period view's block
    // uses — see [rememberLabelColumnWidth]'s doc comment.
    labelColumnWidth: Dp? = null,
    valueColumnWidth: Dp? = null,
) {
    val visibility = LocalPlannerVisibility.current
    if (onOpenDay != null && visibility.hours) {
        PlannerExpandableSection(
            title = "Hours / Minutes",
            expanded = expansion.isExpanded(PlannerSectionKey.HOURS),
            onToggle = { expansion.toggle(PlannerSectionKey.HOURS) },
            summary = formatHoursMinutes(records.hourDays.sumOf { it.totalMinutes }),
            labelColumnWidth = labelColumnWidth,
            valueColumnWidth = valueColumnWidth,
        ) {
            if (records.hourDays.isEmpty()) PlannerEmptyHint("No ministry time logged in this period.")
            // Hours already sent in a submitted report are shown as read-only "Submitted: …".
            val plannerLock = LocalPlannerLock.current
            records.hourDays.mapNotNull { plannerLock.submittedHoursLabel(it.dayStart) }.distinct()
                .forEach { PlannerEmptyHint("$it (read-only)") }
            records.hourDays.forEach { day ->
                PlannerRecordRow(
                    title = formatRecordDate(day.dayStart),
                    subtitle = day.note,
                    trailing = formatHoursMinutes(day.totalMinutes),
                    onClickLabel = "Open this day",
                    onClick = { onOpenDay(day.dayStart) },
                )
            }
        }
    }

    if (visibility.creditHours) PlannerExpandableSection(
        title = "Credit Hours",
        expanded = expansion.isExpanded(PlannerSectionKey.CREDIT),
        onToggle = { expansion.toggle(PlannerSectionKey.CREDIT) },
        summary = formatHoursMinutes(records.creditRecords.sumOf { it.totalMinutes }),
        labelColumnWidth = labelColumnWidth,
        valueColumnWidth = valueColumnWidth,
    ) {
        if (records.creditRecords.isEmpty()) PlannerEmptyHint("No Credit Hours in this period.")
        records.creditRecords.forEach { record ->
            val subtitle = listOfNotNull(
                if (showDates) formatRecordDate(record.resolvedDayStart()) else null,
                record.note,
            ).joinToString(" · ")
            PlannerRecordRow(
                title = categoryName(record.categoryId),
                subtitle = subtitle,
                trailing = formatHoursMinutes(record.totalMinutes),
                onClickLabel = "Open Credit Hours entry",
                onClick = { onOpenCredit(record) },
            )
        }
        if (periodLocked) {
            PlannerEmptyHint(PLANNER_LOCKED_MESSAGE)
        } else {
            TextButton(onClick = onAddCredit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Credit Hours", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (visibility.returnVisits) PersonSection(
        title = "Return Visits",
        key = PlannerSectionKey.RETURN_VISITS,
        people = records.returnVisits,
        activityNoun = "visit",
        emptyText = "No Return Visits in this period.",
        expansion = expansion,
        showDates = showDates,
        onOpen = { onOpenPerson(PipelineStage.RETURN_VISIT, it) },
        onOpenList = { onOpenPersonList(PipelineStage.RETURN_VISIT) },
        listLocked = periodLocked,
        labelColumnWidth = labelColumnWidth,
        valueColumnWidth = valueColumnWidth,
    )
    if (visibility.bibleStudies) PersonSection(
        title = "Bible Studies",
        key = PlannerSectionKey.BIBLE_STUDIES,
        people = records.bibleStudies,
        activityNoun = "session",
        emptyText = "No Bible Studies in this period.",
        expansion = expansion,
        showDates = showDates,
        onOpen = { onOpenPerson(PipelineStage.BIBLE_STUDY, it) },
        onOpenList = { onOpenPersonList(PipelineStage.BIBLE_STUDY) },
        listLocked = periodLocked,
        labelColumnWidth = labelColumnWidth,
        valueColumnWidth = valueColumnWidth,
    )
}

@Composable
private fun PersonSection(
    title: String,
    key: String,
    people: List<PersonActivitySummary>,
    activityNoun: String,
    emptyText: String,
    expansion: PlannerExpansionState,
    showDates: Boolean,
    onOpen: (String) -> Unit,
    onOpenList: () -> Unit,
    /** The period is inside a submitted month: the full list is closed. */
    listLocked: Boolean = false,
    labelColumnWidth: Dp? = null,
    valueColumnWidth: Dp? = null,
) {
    PlannerExpandableSection(
        title = title,
        expanded = expansion.isExpanded(key),
        onToggle = { expansion.toggle(key) },
        summary = personCountSummary(people.size),
        labelColumnWidth = labelColumnWidth,
        valueColumnWidth = valueColumnWidth,
    ) {
        if (people.isEmpty()) PlannerEmptyHint(emptyText)
        val plannerLock = LocalPlannerLock.current
        people.forEach { person ->
            val count = person.periodActivities
            // A person whose visits fall in a month that was already reported stays visible for
            // reference, marked Submitted — never selectable for another report.
            val submitted = person.lastVisitDateInPeriod?.let(plannerLock::isLocked) == true
            val subtitle = buildList {
                add("$count $activityNoun${if (count == 1) "" else "s"}")
                if (showDates) person.lastVisitDateInPeriod?.let { add("last ${formatRecordDate(it)}") }
                // "Time Consumed" is retired (spec §43) — never shown here or
                // anywhere else in the Return Visit / Bible Study interface.
            }.joinToString(" · ")
            PlannerRecordRow(
                title = person.name.ifBlank { "(no name)" },
                subtitle = subtitle,
                trailing = if (submitted) "Submitted" else null,
                onClickLabel = "Open ${person.name}",
                onClick = { onOpen(person.interestedPersonId) },
            )
        }
        TextButton(onClick = onOpenList, enabled = !listLocked, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Open all $title", style = MaterialTheme.typography.labelLarge)
        }
        if (listLocked) PlannerEmptyHint(PLANNER_LOCKED_MESSAGE)
    }
}

// ---------------------------------------------------------------------------
// Steppers / goal rows
// ---------------------------------------------------------------------------

@Composable
internal fun CircleStepButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    // 40dp touch target (IconButton also enforces the 48dp minimum
    // interactive size) around a smaller 28dp visible circle.
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun StepperRow(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    // Manual entry alongside +/- (typing a big number beats dozens of taps).
    // [currentValue] is the plain integer behind [value] (the display string
    // can be formatted, e.g. "3h"); tapping the number opens a small dialog
    // to type a replacement directly. Both null (the default) keeps a row
    // stepper-only, unchanged from before.
    currentValue: Int? = null,
    onValueEntered: ((Int) -> Unit)? = null,
    // Color-codes just the number (e.g. [PlannerAccent.Goal] for a Goal
    // stepper) — null keeps the row's normal, uncolored look.
    valueColor: Color? = null,
    // Same idea for the label itself (e.g. "Hours Goal for this Month") —
    // null keeps it the row's normal, unemphasized look.
    labelColor: Color? = null,
    // "The value column should remain aligned vertically across all rows" —
    // see [PlannerExpandableSection]'s identical parameter; passed the same
    // shared width whenever this stepper sits in a block with other label/
    // value rows (Goal rows, Hours, Minutes, ...), so the stepper cluster
    // starts at the same x as every sibling's value rather than being
    // pushed to the far edge of the screen by [Arrangement.SpaceBetween].
    labelColumnWidth: Dp? = null,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    val editable = onValueEntered != null && currentValue != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        // Always pin the +/- cluster to the far right edge, same as before
        // labelColumnWidth existed — the shared label width still keeps
        // labels lined up, but no longer drags the stepper in from the edge.
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (labelColor != null) FontWeight.Bold else null,
            color = labelColor ?: Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // padding BEFORE width: the 4dp inset is taken from the row's
            // space first, so the Text itself still gets the full
            // [labelColumnWidth] to render in — reversing the order shrinks
            // the text's own available width by 4dp, which is exactly what
            // was quietly forcing "Hours Goal for this Day" onto two lines.
            modifier = Modifier.padding(start = 4.dp).then(if (labelColumnWidth != null) Modifier.width(labelColumnWidth) else Modifier),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleStepButton(Icons.Rounded.Remove, "Decrease $label", onDecrement)
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: Color.Unspecified,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(44.dp)
                    .then(
                        if (editable) {
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "Enter $label manually") { showEditDialog = true }
                        } else {
                            Modifier
                        },
                    ),
            )
            CircleStepButton(Icons.Rounded.Add, "Increase $label", onIncrement)
        }
    }
    if (showEditDialog && editable) {
        NumberEntryDialog(
            label = label,
            initial = currentValue!!,
            onDismiss = { showEditDialog = false },
            onSave = { onValueEntered!!(it); showEditDialog = false },
        )
    }
}

/** The small "type it directly" dialog [StepperRow] opens when its number is
 * tapped — one numeric field, pre-filled with the current value. */
@Composable
private fun NumberEntryDialog(label: String, initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter $label") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull()?.coerceAtLeast(0) ?: initial) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Compact goal line: stepper plus a one-line Remaining/Surplus caption. */
@Composable
internal fun PlannerGoalRow(
    label: String,
    goalHours: Int,
    remainingMinutes: Int,
    surplusMinutes: Int,
    onSetGoal: (Int) -> Unit,
    labelColumnWidth: Dp? = null,
) {
    // "Emphasize with color code the Goal Hour, Remaining Hour" — Goal is
    // always [PlannerAccent.Goal]; Remaining/Surplus switches between "not
    // there yet" and "met/exceeded" colors, same rule and same two colors
    // in every one of Today/Week/Month/Year's own goal row (this one
    // composable backs all four, so that consistency is automatic).
    val goalMet = remainingMinutes == 0
    Column(modifier = Modifier.fillMaxWidth()) {
        StepperRow(
            label = label,
            value = "${goalHours}h",
            onDecrement = { onSetGoal(goalHours - 1) },
            onIncrement = { onSetGoal(goalHours + 1) },
            currentValue = goalHours,
            onValueEntered = onSetGoal,
            valueColor = PlannerAccent.Goal,
            labelColor = PlannerAccent.Goal,
            labelColumnWidth = labelColumnWidth,
        )
        // "Remaining Hours must have the same visual size and importance as
        // Hours Goal" — same label-left/value-right row shape, same
        // titleSmall/bold value styling as every stepper's own value, not a
        // small caption underneath it. Surplus (goal already met/exceeded)
        // gets its own second line the same way, rather than being crammed
        // onto Remaining's line with a separator.
        PlannerStaticValueRow(
            label = "Remaining Hours",
            value = formatHoursMinutes(remainingMinutes),
            valueColor = if (goalMet) PlannerAccent.GoalMet else PlannerAccent.Remaining,
            labelColumnWidth = labelColumnWidth,
        )
        if (surplusMinutes > 0) {
            PlannerStaticValueRow(
                label = "Surplus",
                value = formatHoursMinutes(surplusMinutes),
                valueColor = PlannerAccent.GoalMet,
                labelColumnWidth = labelColumnWidth,
            )
        }
    }
}

/** A plain, non-interactive label-left/value-right line — "Remaining Hours,"
 * "Surplus," and any other read-only figure that needs the exact same
 * visual weight as a [StepperRow]'s own value (titleSmall/bold), just
 * without a stepper. Kept separate from [StepperRow] itself rather than
 * calling it with no-op callbacks, since there's no +/- cluster to reserve
 * space for here — the value sits flush at the row's right edge on its own. */
@Composable
internal fun PlannerStaticValueRow(label: String, value: String, valueColor: Color? = null, labelColumnWidth: Dp? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).then(if (labelColumnWidth != null) Modifier.width(labelColumnWidth) else Modifier),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: Color.Unspecified,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
internal fun ValueEditRow(label: String, value: String, onEdit: () -> Unit, labelColumnWidth: Dp? = null, valueColumnWidth: Dp? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Edit $label", onClick = onEdit)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label stays on the left; value now sits right next to "Edit" at the
        // row's far right edge, same edge every stepper's +/- cluster uses,
        // rather than hugging the label.
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (labelColumnWidth != null) Modifier.width(labelColumnWidth) else Modifier,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .then(if (valueColumnWidth != null) Modifier.width(valueColumnWidth) else Modifier),
            )
            Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun MonthlyGoalEditDialog(initialGoalHours: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var goalHours by remember { mutableStateOf(initialGoalHours) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hours Goal for This Month") },
        text = {
            StepperRow(
                label = "Goal",
                value = "${goalHours}h",
                onDecrement = { goalHours = (goalHours - 1).coerceAtLeast(0) },
                onIncrement = { goalHours += 1 },
                currentValue = goalHours,
                onValueEntered = { goalHours = it },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(goalHours) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    FormDialog(
        onDismissRequest = onDismiss,
        title = "Note",
        onConfirm = { onSave(text) },
        hasUnsavedChanges = text != initial,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Note") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Credit Hours dialogs
// ---------------------------------------------------------------------------

internal const val NO_CREDIT_CATEGORIES_MESSAGE = "No Credit Hour categories available."
internal const val NO_CREDIT_CATEGORIES_PUBLISHER_HINT = "Please contact an administrator to configure Credit Hour categories."

/**
 * Add / Edit a Credit Hours entry: Category, Date, Hours, Minutes, Notes.
 *
 * Bug fix ("categories cannot be found in the dropdown"): the old form drew
 * a separate "Choose" button stacked *inside* the same Box as the read-only
 * field (so it sat hidden underneath it), anchored its menu to that button,
 * and captured `categories.firstOrNull()` once at first composition —
 * before the category list had even loaded. This uses a proper
 * [ExposedDropdownMenuBox] anchored to the field itself, keeps the selection
 * as an id (so it survives the list loading/refreshing and reloads an
 * existing entry's saved category in Edit mode), and says so plainly when
 * no categories are configured instead of showing an empty menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreditHourEntryDialog(
    existing: CreditHourRecord?,
    initialDayStart: Long,
    /** Active categories; `null` while still loading. */
    activeCategories: List<CreditHourCategory>?,
    allCategories: List<CreditHourCategory>,
    onDismiss: () -> Unit,
    onSave: (categoryId: String, dayStart: Long, hours: Int, minutes: Int, note: String?) -> Unit,
) {
    val context = LocalContext.current
    val initialDay = existing?.resolvedDayStart()?.takeIf { it > 0L } ?: initialDayStart
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var dayStart by remember { mutableStateOf(initialDay) }
    var hoursText by remember { mutableStateOf(existing?.hours?.toString() ?: "") }
    var minutesText by remember { mutableStateOf(existing?.minutes?.toString() ?: "") }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // An entry whose category was later deactivated still shows (and can
    // keep) that category in Edit mode; new entries only offer active ones.
    val options = remember(activeCategories, allCategories, existing?.categoryId) {
        val active = activeCategories.orEmpty()
        val keep = existing?.categoryId?.let { id -> allCategories.firstOrNull { it.id == id && active.none { a -> a.id == id } } }
        (active + listOfNotNull(keep)).sortedBy { it.name.lowercase() }
    }
    val selected = options.firstOrNull { it.id == categoryId }
    val noCategories = activeCategories != null && options.isEmpty()

    val hasUnsavedChanges = existing?.let {
        categoryId != it.categoryId || dayStart != initialDay || hoursText != it.hours.toString() ||
            minutesText != it.minutes.toString() || note != it.note.orEmpty()
    } ?: (categoryId != null || hoursText.isNotBlank() || minutesText.isNotBlank() || note.isNotBlank())

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existing == null) "Add Credit Hours" else "Edit Credit Hours",
        confirmEnabled = !noCategories && activeCategories != null,
        hasUnsavedChanges = hasUnsavedChanges,
        errorMessage = errorMessage,
        onConfirm = {
            val hours = hoursText.toIntOrNull() ?: 0
            val minutes = minutesText.toIntOrNull() ?: 0
            errorMessage = when {
                selected == null -> "Select a Credit Hour category."
                hours * 60 + minutes <= 0 -> "Enter the hours and/or minutes."
                else -> null
            }
            if (errorMessage == null && selected != null) onSave(selected.id, dayStart, hours, minutes, note)
        },
    ) {
        when {
            activeCategories == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Loading categories…", style = MaterialTheme.typography.bodyMedium)
            }
            noCategories -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(NO_CREDIT_CATEGORIES_MESSAGE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                val openManager = LocalOpenCreditCategoryManager.current
                if (openManager != null) {
                    TextButton(onClick = { onDismiss(); openManager() }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Credit Hour Category")
                    }
                } else {
                    Text(NO_CREDIT_CATEGORIES_PUBLISHER_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = selected?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Category") },
                    placeholder = { Text("Select a category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    options.forEach { category ->
                        DropdownMenuItem(
                            text = {
                                Text(if (category.active) category.name else "${category.name} (inactive)")
                            },
                            onClick = { categoryId = category.id; menuOpen = false; errorMessage = null },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        }

        Box {
            OutlinedTextField(
                value = formatFullDate(dayStart),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Date") },
                trailingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            // A read-only field swallows taps, so an invisible overlay opens
            // the picker from anywhere on it.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button, onClickLabel = "Choose date") {
                        val calendar = Calendar.getInstance().apply { timeInMillis = dayStart }
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                dayStart = Calendar.getInstance().apply {
                                    set(year, month, day, 0, 0, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH),
                        ).show()
                    },
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hoursText,
                onValueChange = { hoursText = it.filter(Char::isDigit).take(3); errorMessage = null },
                label = { Text("Hours") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = minutesText,
                onValueChange = { minutesText = it.filter(Char::isDigit).take(3); errorMessage = null },
                label = { Text("Minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Notes (optional)") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Read-only detail of one Credit Hours entry, with Edit/Delete when the
 * signed-in user owns it. */
@Composable
internal fun CreditHourRecordDetailDialog(
    record: CreditHourRecord,
    categoryName: String,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credit Hours") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine("Category", categoryName)
                DetailLine("Date", formatFullDate(record.resolvedDayStart()))
                DetailLine("Hours", record.hours.toString())
                DetailLine("Minutes", record.minutes.toString())
                DetailLine("Notes", record.note?.takeIf { it.isNotBlank() } ?: "—")
            }
        },
        confirmButton = {
            Row {
                if (canEdit) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onEdit) { Text("Edit") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Delete this entry?") },
            text = { Text("${formatHoursMinutes(record.totalMinutes)} of $categoryName will be removed from every planner total.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// Per-Publisher section visibility + category-admin shortcut
// ---------------------------------------------------------------------------

/** Which My Planner sections the signed-in Publisher has chosen to show
 * (see [com.emfitsolutions.gopreach.data.model.PlannerSection]). Hidden
 * sections disappear from the summary and the section list; their records
 * are untouched. Provided once by the home screen for every planner view. */
data class PlannerVisibility(
    val hours: Boolean = true,
    val creditHours: Boolean = true,
    val returnVisits: Boolean = true,
    val bibleStudies: Boolean = true,
) {
    val anyVisible: Boolean get() = hours || creditHours || returnVisits || bibleStudies
}

internal val LocalPlannerVisibility = androidx.compose.runtime.staticCompositionLocalOf { PlannerVisibility() }

/** Non-null only for a user allowed to manage Credit Hour categories —
 * opens that management screen (used by the empty-category state). */
internal val LocalOpenCreditCategoryManager = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
