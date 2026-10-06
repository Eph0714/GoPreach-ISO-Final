package com.emfitsolutions.gopreach.ui.screens.planner

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CalendarViewMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CreditHourRecord
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.domain.DayBounds
import com.emfitsolutions.gopreach.domain.TimeBounds
import com.emfitsolutions.gopreach.ui.components.MinistryTimerCard
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.navigation.Destinations
import java.util.Calendar

/**
 * My Planner (spec §16-§27, extended with the Dashboard/My Planner
 * integration) — Day/Week/Month/Year content composables, embedded directly
 * on the Publisher Main Form under the Dashboard (see
 * `PublisherHomeScreen.kt`) rather than behind a separate screen/nav route.
 * Shared sections/rows/dialogs live in `PlannerComponents.kt`; the Weekly
 * view lives in `PlannerWeekContent.kt`; the per-period record lists and the
 * Credit Hours write path live in `PlannerRecords.kt`.
 *
 * Every view is: compact nav header → headline figures → goal → collapsible
 * sections whose rows each open the underlying record (a day, a Credit
 * Hours entry, or a Return Visit / Bible Study person's own detail screen).
 */

/** Route to one Return Visit / Bible Study person's existing detail screen. */
/** "Color-code yellow if there is a record inside" — one fixed amber accent
 * for "this day has something logged," used the same way in both light and
 * dark theme (a status color, not a themed surface, so it stays recognizable
 * regardless of the Publisher's chosen [com.emfitsolutions.gopreach.ui.theme
 * .ThemeColorOption]). [OnRecordDayColor] is a dark, high-contrast text/icon
 * color to sit on top of it. */
internal val RecordDayColor = Color(0xFFFFC107)
internal val OnRecordDayColor = Color(0xFF3E2E00)

internal fun plannerPersonRoute(stage: PipelineStage, interestedPersonId: String): String = when (stage) {
    PipelineStage.BIBLE_STUDY -> Destinations.bibleStudyPerson(interestedPersonId)
    else -> Destinations.returnVisitPerson(interestedPersonId)
}

/** Route to the full Return Visit / Bible Study list. */
internal fun plannerPersonListRoute(stage: PipelineStage): String =
    if (stage == PipelineStage.BIBLE_STUDY) Destinations.BIBLE_STUDY else Destinations.RETURN_VISIT

/** Today if it falls inside [bounds], else the period's first day — the
 * default date offered when adding Credit Hours from a Week/Month/Year view. */
internal fun defaultEntryDay(bounds: TimeBounds, periodStart: Long): Long {
    val today = DayBounds.of(System.currentTimeMillis()).startInclusive
    return if (bounds.contains(today)) today else periodStart
}

// ---------------------------------------------------------------------------
// Credit Hours dialogs, shared by every view
// ---------------------------------------------------------------------------

@Stable
internal class CreditHourDialogState {
    var detail by mutableStateOf<CreditHourRecord?>(null)
    var editing by mutableStateOf<CreditHourRecord?>(null)
    var addingForDay by mutableStateOf<Long?>(null)
}

@Composable
internal fun rememberCategoryNamer(viewModel: CreditHourEntryViewModel): (String) -> String {
    val all by viewModel.allCategories.collectAsStateWithLifecycle()
    return remember(all) {
        val byId = all.associateBy { it.id }
        val namer: (String) -> String = { id -> byId[id]?.name ?: "Uncategorized" }
        namer
    }
}

@Composable
internal fun CreditHourDialogsHost(
    state: CreditHourDialogState,
    currentPersonId: String,
    viewModel: CreditHourEntryViewModel,
) {
    val activeCategories by viewModel.activeCategories.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    val categoryName = rememberCategoryNamer(viewModel)
    val toast = rememberActionToast()
    LaunchedEffect(viewModel) { viewModel.messages.collect { toast(it) } }

    state.detail?.let { record ->
        CreditHourRecordDetailDialog(
            record = record,
            categoryName = categoryName(record.categoryId),
            canEdit = record.publisherPersonId == currentPersonId && !LocalPlannerLock.current.isLocked(record.resolvedDayStart()),
            onEdit = { state.detail = null; state.editing = record },
            onDelete = { state.detail = null; viewModel.delete(record) },
            onDismiss = { state.detail = null },
        )
    }

    val editing = state.editing
    val addingForDay = state.addingForDay
    if (editing != null || addingForDay != null) {
        val close = { state.editing = null; state.addingForDay = null }
        CreditHourEntryDialog(
            existing = editing,
            initialDayStart = editing?.resolvedDayStart() ?: addingForDay ?: System.currentTimeMillis(),
            activeCategories = activeCategories,
            allCategories = allCategories,
            onDismiss = close,
            onSave = { categoryId, dayStart, hours, minutes, note ->
                viewModel.save(currentPersonId, editing, categoryId, dayStart, hours, minutes, note)
                close()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Day
// ---------------------------------------------------------------------------

@Composable
internal fun PlannerDayContent(currentPersonId: String, viewModel: PlannerDayViewModel, onNavigate: (String) -> Unit) {
    val dayStart by viewModel.dayStart.collectAsStateWithLifecycle()
    // remember(currentPersonId): stateFor() builds a fresh combine()+stateIn()
    // pipeline each call — without remembering it, every recomposition would
    // start a brand-new StateFlow (reset to PlannerDayUiState()'s all-zero
    // defaults) instead of reusing the same live one, which is exactly what
    // was causing the Day/Month tabs to flash back to 0 on some taps.
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }

    val creditViewModel: CreditHourEntryViewModel = koinViewModel()
    val creditDialogs = remember { CreditHourDialogState() }
    val categoryName = rememberCategoryNamer(creditViewModel)
    val expansion = rememberPlannerExpansionState(PlannerSectionKey.REPORT, PlannerSectionKey.TIMER)

    var showNoteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        PlannerDateNavHeader(
            label = dateFormat.format(Date(dayStart)),
            onPrevious = viewModel::goToPreviousDay,
            onNext = viewModel::goToNextDay,
        )
        PlannerLoadingBar(state.isLoading)

        BoxWithConstraints(modifier = Modifier.padding(PlannerBodyPadding)) {
            // "Maintain a consistent vertical value column... on Small
            // Android phones, Large Android phones, Android tablets" — one
            // shared, non-hardcoded column width for every label/value row
            // on this screen, recomputed from this exact available width
            // (see [rememberLabelColumnWidth]'s doc comment).
            val labelColumnWidth = rememberLabelColumnWidth(
                labels = listOf("Hours Goal for this Day", "Report for this day", "Hours", "Minutes", "Credit Hours", "Return Visits", "Bible Studies", "Notes"),
                availableWidth = maxWidth,
            )
            // "Align the + icon in Report for this Day, Credit Hours, Return
            // Visit, Bible Studies, Ministry Timer" — the same idea one
            // column over: every section's own value/summary shares this
            // width too, so the trailing chevron/Edit action that follows
            // lines up across every row, not just the label.
            val valueColumnWidth = rememberLabelColumnWidth(
                labels = listOf(
                    formatHoursMinutes(state.totalMinutes),
                    formatHoursMinutes(state.records.creditRecords.sumOf { it.totalMinutes }),
                    personCountSummary(state.returnVisitCount),
                    personCountSummary(state.bibleStudyCount),
                    state.note?.takeIf { it.isNotBlank() } ?: "Add",
                ),
                availableWidth = maxWidth,
                style = MaterialTheme.typography.labelLarge,
            )
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PlannerActivitySummary(
                    stats = periodStats(
                        state.totalMinutes, state.creditHoursTotalMinutes, state.returnVisitCount, state.bibleStudyCount,
                        LocalPlannerVisibility.current, hoursKey = PlannerSectionKey.REPORT,
                    ),
                    onStatClick = expansion::expand,
                )

                // "Make a consistent location of this [Goal/Remaining] in the
                // entire date range" — the Day Goal now sits right after
                // Activity Summary, the exact same spot Week/Month/Year's own
                // [PlannerGoalRow] sits in their views, instead of being buried
                // inside a collapsed "Goals" section further down the screen.
                PlannerGoalRow(
                    label = "Hours Goal for this Day",
                    goalHours = state.dailyGoalHours,
                    remainingMinutes = state.remainingMinutes,
                    surplusMinutes = state.surplusMinutes,
                    onSetGoal = { viewModel.setDailyGoalHours(currentPersonId, it) },
                    labelColumnWidth = labelColumnWidth,
                )

                if (LocalPlannerVisibility.current.hours) PlannerExpandableSection(
                    title = "Report for this day",
                    expanded = expansion.isExpanded(PlannerSectionKey.REPORT),
                    onToggle = { expansion.toggle(PlannerSectionKey.REPORT) },
                    summary = formatHoursMinutes(state.totalMinutes),
                    labelColumnWidth = labelColumnWidth,
                    valueColumnWidth = valueColumnWidth,
                ) {
                  if (LocalPlannerLock.current.isLocked(dayStart)) {
                    PlannerEmptyHint(PLANNER_LOCKED_MESSAGE)
                    LocalPlannerLock.current.submittedHoursLabel(dayStart)?.let { PlannerEmptyHint("$it (read-only)") }
                  } else {
                    StepperRow(
                        label = "Hours",
                        value = state.hours.toString(),
                        onDecrement = { viewModel.adjustHours(currentPersonId, -1) },
                        onIncrement = { viewModel.adjustHours(currentPersonId, 1) },
                        currentValue = state.hours,
                        onValueEntered = { viewModel.setHours(currentPersonId, it) },
                        labelColumnWidth = labelColumnWidth,
                    )
                    StepperRow(
                        label = "Minutes",
                        value = state.minutes.toString(),
                        onDecrement = { viewModel.adjustMinutes(currentPersonId, -1) },
                        onIncrement = { viewModel.adjustMinutes(currentPersonId, 1) },
                        currentValue = state.minutes,
                        onValueEntered = { viewModel.setMinutes(currentPersonId, it) },
                        labelColumnWidth = labelColumnWidth,
                    )
                  }
                }

                PlannerRecordSections(
                    records = state.records,
                    expansion = expansion,
                    categoryName = categoryName,
                    onOpenCredit = { creditDialogs.detail = it },
                    onAddCredit = { creditDialogs.addingForDay = dayStart },
                    periodLocked = LocalPlannerLock.current.isLocked(dayStart),
                    onOpenPerson = { stage, personId -> onNavigate(plannerPersonRoute(stage, personId)) },
                    onOpenPersonList = { onNavigate(plannerPersonListRoute(it)) },
                    onOpenDay = null,
                    showDates = false,
                    labelColumnWidth = labelColumnWidth,
                    valueColumnWidth = valueColumnWidth,
                )

                ValueEditRow(
                    label = "Notes",
                    value = state.note?.takeIf { it.isNotBlank() } ?: "Add",
                    onEdit = { showNoteDialog = true },
                    labelColumnWidth = labelColumnWidth,
                    valueColumnWidth = valueColumnWidth,
                )

                PlannerExpandableSection(
                    title = "Ministry Timer",
                    expanded = expansion.isExpanded(PlannerSectionKey.TIMER),
                    onToggle = { expansion.toggle(PlannerSectionKey.TIMER) },
                    labelColumnWidth = labelColumnWidth,
                    valueColumnWidth = valueColumnWidth,
                ) {
                    if (LocalPlannerLock.current.isLocked(dayStart)) {
                        PlannerEmptyHint(PLANNER_LOCKED_MESSAGE)
                    } else {
                        MinistryTimerCard(publisherPersonId = currentPersonId, showLabel = false, targetDayMillis = dayStart)
                    }
                }
            }
        }
    }

    CreditHourDialogsHost(creditDialogs, currentPersonId, creditViewModel)

    if (showNoteDialog) {
        NoteDialog(
            initial = state.note.orEmpty(),
            onDismiss = { showNoteDialog = false },
            onSave = { viewModel.setNote(currentPersonId, it); showNoteDialog = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Month
// ---------------------------------------------------------------------------

/** My Planner → Month (spec §23-§25/§30). Report figures are independently
 * computed for the whole month by [PlannerMonthViewModel] via
 * [com.emfitsolutions.gopreach.domain.MinistryStatisticsService] — never
 * summed from the Day view's own numbers. */
@Composable
internal fun PlannerMonthContent(
    currentPersonId: String,
    viewModel: PlannerMonthViewModel,
    onOpenDay: (Long) -> Unit,
    onOpenPerson: (PipelineStage, String) -> Unit,
    onOpenPersonList: (PipelineStage) -> Unit,
    isPioneer: Boolean,
    // "My Planner's selected Month/Year controls the initial Monthly
    // Report month" — passed the Planner's own currently-displayed
    // [monthStart] at the moment Send Report is tapped.
    onSendReport: (periodMonth: Long) -> Unit,
) {
    val monthStart by viewModel.monthStart.collectAsStateWithLifecycle()
    // See PlannerDayContent's identical remember(currentPersonId) note above.
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }.collectAsStateWithLifecycle()
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    val creditViewModel: CreditHourEntryViewModel = koinViewModel()
    val creditDialogs = remember { CreditHourDialogState() }
    val categoryName = rememberCategoryNamer(creditViewModel)
    val expansion = rememberPlannerExpansionState(PlannerSectionKey.CALENDAR)

    var showMonthList by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        PlannerDateNavHeader(
            label = monthFormat.format(Date(monthStart)),
            onPrevious = viewModel::goToPreviousMonth,
            onNext = viewModel::goToNextMonth,
            onOpenList = { showMonthList = true },
        )
        if (showMonthList) {
            // Item 2 — "Month List View": every month of the currently
            // viewed year, selectable, one flat list instead of paging with
            // the arrows one month at a time.
            val year = remember(monthStart) { Calendar.getInstance().apply { timeInMillis = monthStart }.get(Calendar.YEAR) }
            val months = remember(year) {
                (0..11).map { monthIndex ->
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year); set(Calendar.MONTH, monthIndex); set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    monthFormat.format(Date(cal.timeInMillis)) to cal.timeInMillis
                }
            }
            PeriodListDialog(
                title = "Select a Month",
                items = months.map { (label, _) -> label to null },
                onDismiss = { showMonthList = false },
                onSelect = { index -> viewModel.goToMonth(months[index].second) },
            )
        }
        PlannerLoadingBar(state.isLoading)

        BoxWithConstraints(modifier = Modifier.padding(PlannerBodyPadding)) {
            // See PlannerDayContent's identical [rememberLabelColumnWidth] note.
            val labelColumnWidth = rememberLabelColumnWidth(
                labels = listOf("Hours Goal for this Month", "Calendar", "Hours / Minutes", "Credit Hours", "Return Visits", "Bible Studies"),
                availableWidth = maxWidth,
            )
            // See PlannerDayContent's identical [valueColumnWidth] note.
            val valueColumnWidth = rememberLabelColumnWidth(
                labels = listOf(
                    formatHoursMinutes(state.totalMinutes),
                    formatHoursMinutes(state.records.creditRecords.sumOf { it.totalMinutes }),
                    personCountSummary(state.returnVisitCount),
                    personCountSummary(state.bibleStudyCount),
                ),
                availableWidth = maxWidth,
                style = MaterialTheme.typography.labelLarge,
            )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlannerActivitySummary(
                stats = periodStats(state.totalMinutes, state.creditHoursMinutes, state.returnVisitCount, state.bibleStudyCount, LocalPlannerVisibility.current),
                onStatClick = expansion::expand,
            )
            PlannerGoalRow(
                label = "Hours Goal for this Month",
                goalHours = state.goalHours,
                remainingMinutes = state.remainingMinutes,
                surplusMinutes = state.surplusMinutes,
                onSetGoal = { viewModel.setGoalHours(currentPersonId, it) },
                labelColumnWidth = labelColumnWidth,
            )
            // "Fix the Send Report button, put it somewhere visible but not
            // too big or small" — a right-aligned pill button sized to its
            // own label (not a full-width bar spanning the whole card), so
            // it reads as one clear call-to-action beside the month's goal
            // rather than a heavy footer bar competing with everything above it.
            var showSendReportChooser by remember { mutableStateOf(false) }
            var showSendReportPreview by remember { mutableStateOf(false) }
            val context = LocalContext.current
            // Shared by "Preview" and "Send as Text" — the dialog's own
            // Send button uses this exact same string, so what the Publisher
            // previews is guaranteed to be what actually goes out.
            val reportViewModel: PlannerReportViewModel = koinViewModel()
            val reportText by remember(currentPersonId, monthStart) { reportViewModel.reportText(currentPersonId, monthStart) }
                .collectAsStateWithLifecycle(initialValue = "")
            val alreadySubmitted by remember(currentPersonId, monthStart) { reportViewModel.isSubmitted(currentPersonId, monthStart) }
                .collectAsStateWithLifecycle(initialValue = false)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { showSendReportChooser = true },
                    enabled = !alreadySubmitted,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    if (!alreadySubmitted) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (alreadySubmitted) "This Record is Already Submitted" else "Send Report", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showSendReportChooser) {
                SendReportChooserDialog(
                    onDismiss = { showSendReportChooser = false },
                    onPreview = { showSendReportChooser = false; showSendReportPreview = true },
                    onSendAsText = { showSendReportChooser = false; shareReportText(context, reportText) },
                    onOpenMyReport = { showSendReportChooser = false; onSendReport(monthStart) },
                )
            }
            if (showSendReportPreview) {
                SendReportPreviewDialog(
                    text = reportText,
                    onDismiss = { showSendReportPreview = false },
                    onSend = { showSendReportPreview = false; shareReportText(context, reportText) },
                )
            }

            var monthViewMode by rememberSaveable { mutableStateOf(MonthViewMode.CALENDAR) }
            PlannerExpandableSection(
                title = "Calendar",
                expanded = expansion.isExpanded(PlannerSectionKey.CALENDAR),
                onToggle = { expansion.toggle(PlannerSectionKey.CALENDAR) },
                summary = "${state.activeDayStarts.size} active days",
                labelColumnWidth = labelColumnWidth,
            ) {
                MonthViewModeToggle(mode = monthViewMode, onModeChange = { monthViewMode = it })
                AnimatedContent(targetState = monthViewMode, label = "monthViewMode") { mode ->
                    when (mode) {
                        MonthViewMode.CALENDAR -> MonthCalendarGrid(
                            monthStart = monthStart,
                            dailyMinutes = state.dailyMinutes,
                            dailyCreditMinutes = state.dailyCreditMinutes,
                            activeDayStarts = state.activeDayStarts,
                            onDayClick = onOpenDay,
                        )
                        MonthViewMode.LIST -> MonthListView(
                            monthStart = monthStart,
                            dailyMinutes = state.dailyMinutes,
                            dailyCreditMinutes = state.dailyCreditMinutes,
                            dailyReturnVisits = state.dailyReturnVisits,
                            dailyBibleStudies = state.dailyBibleStudies,
                            onDayClick = onOpenDay,
                        )
                    }
                }
            }

            val bounds = remember(monthStart) { com.emfitsolutions.gopreach.domain.MonthBounds.of(monthStart) }
            PlannerRecordSections(
                records = state.records,
                expansion = expansion,
                categoryName = categoryName,
                onOpenCredit = { creditDialogs.detail = it },
                onAddCredit = { creditDialogs.addingForDay = defaultEntryDay(bounds, monthStart) },
                periodLocked = LocalPlannerLock.current.isLocked(monthStart),
                onOpenPerson = onOpenPerson,
                onOpenPersonList = onOpenPersonList,
                onOpenDay = onOpenDay,
                labelColumnWidth = labelColumnWidth,
                valueColumnWidth = valueColumnWidth,
            )
        }
        }
    }

    CreditHourDialogsHost(creditDialogs, currentPersonId, creditViewModel)
}

/** The Activity Summary tiles, minus any section the Publisher has hidden.
 * [hoursKey] is the section the Hours tile expands (the Day view edits
 * hours in its own "Report for this day" section). */
internal fun periodStats(
    totalMinutes: Int,
    creditMinutes: Int,
    returnVisits: Int,
    bibleStudies: Int,
    visibility: PlannerVisibility,
    hoursKey: String = PlannerSectionKey.HOURS,
): List<PlannerStat> = buildList {
    if (visibility.hours) add(PlannerStat(hoursKey, "Hours", formatHoursMinutes(totalMinutes), PlannerAccent.Hours))
    if (visibility.creditHours) add(PlannerStat(PlannerSectionKey.CREDIT, "Credit Hours", formatHoursMinutes(creditMinutes), PlannerAccent.CreditHours))
    if (visibility.returnVisits) add(PlannerStat(PlannerSectionKey.RETURN_VISITS, "Return Visits", returnVisits.toString(), PlannerAccent.ReturnVisits))
    if (visibility.bibleStudies) add(PlannerStat(PlannerSectionKey.BIBLE_STUDIES, "Bible Studies", bibleStudies.toString(), PlannerAccent.BibleStudies))
}

/** "Activity Summary" heading + tiles, or a hint when every section is hidden. */
@Composable
internal fun PlannerActivitySummary(stats: List<PlannerStat>, onStatClick: (String) -> Unit) {
    SectionLabel("Activity Summary")
    if (stats.isEmpty()) {
        PlannerEmptyHint("All planner sections are hidden. Use Planner Sections (the tune icon above) to show them again.")
    } else {
        PlannerSummaryStrip(stats = stats, onStatClick = onStatClick)
    }
}

/** Shared shell for [SendReportChooserDialog]/[SendReportPreviewDialog] — a
 * plain [Dialog] + [Surface] instead of [AlertDialog], so every action is a
 * full-width, vertically stacked button in one column rather than
 * AlertDialog's cramped end-aligned confirm/dismiss row (which is what made
 * the three-choice chooser look lopsided — one button buried in the message
 * text, two squeezed into the corner). */
@Composable
private fun SendReportDialogShell(onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}

/** "Send Report → allow the user to select if send it as text or open the
 * My Report App" — Open My Report (the complete in-app submission path) is
 * the primary action, Preview and Send as Text are the two ways to get a
 * plain-text copy out, Cancel backs out — one clean vertical stack, solid
 * color-coded per spec (blue for the app path/preview, green for the
 * outbound send, gray for cancel), instead of three inconsistently-styled
 * buttons scattered across the dialog. */
@Composable
private fun SendReportChooserDialog(
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onSendAsText: () -> Unit,
    onOpenMyReport: () -> Unit,
) {
    SendReportDialogShell(onDismiss = onDismiss, title = "Send Report") {
        Text(
            "Send this month's report as a text message, preview it first, or open the Monthly Report form to submit it in the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onOpenMyReport,
            colors = ButtonDefaults.buttonColors(containerColor = PlannerAccent.Hours),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open My Report") }
        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Preview as Text") }
        Button(
            onClick = onSendAsText,
            colors = ButtonDefaults.buttonColors(containerColor = PlannerAccent.ReturnVisits),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send as Text") }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = PlannerAccent.Neutral) }
    }
}

/** "Preview as Text" — shows the exact message [onSend] will hand to the
 * device's share sheet, so the Publisher sees precisely what goes out before
 * committing to it. Send (the primary action) is its own full-width button;
 * Copy Text and Back share a row below it since they're equally-weighted
 * secondary actions, not a professional-looking crowded single row of three. */
@Composable
private fun SendReportPreviewDialog(text: String, onDismiss: () -> Unit, onSend: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showCopiedConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(showCopiedConfirmation) {
        if (showCopiedConfirmation) {
            kotlinx.coroutines.delay(1500)
            showCopiedConfirmation = false
        }
    }
    SendReportDialogShell(onDismiss = onDismiss, title = "Preview Report") {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        androidx.compose.animation.AnimatedVisibility(visible = showCopiedConfirmation) {
            Text("Copied to clipboard", style = MaterialTheme.typography.labelMedium, color = PlannerAccent.ReturnVisits)
        }
        Button(
            onClick = onSend,
            colors = ButtonDefaults.buttonColors(containerColor = PlannerAccent.ReturnVisits),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send as Text") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    showCopiedConfirmation = true
                },
                modifier = Modifier.weight(1f),
            ) { Text("Copy Text") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Back") }
        }
    }
}

/** Opens the device's share sheet (SMS, chat apps, email, ...) with
 * [text] pre-filled — the exact same [Intent.ACTION_SEND]/chooser pattern
 * this app already uses for sharing sign-in credentials
 * ([com.emfitsolutions.gopreach.ui.components.ShareableSetupLink]). */
private fun shareReportText(context: android.content.Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Send Report"))
}

/** Monthly Planner's Calendar/List toggle — a compact segmented control,
 * "▦ Calendar | ☷ List" per spec, remembered for the session
 * ([rememberSaveable] in the caller) but not written to the account, since
 * this is a lightweight view preference, not planner data. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MonthViewModeToggle(mode: MonthViewMode, onModeChange: (MonthViewMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(bottom = 8.dp)) {
        MonthViewMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onModeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = MonthViewMode.entries.size),
                icon = {},
            ) {
                // SegmentedButton's content slot lays out its children in a
                // Box, not a Row — without this explicit Row, the icon and
                // label render stacked on top of each other instead of
                // side by side.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (option == MonthViewMode.CALENDAR) Icons.Rounded.CalendarViewMonth else Icons.AutoMirrored.Rounded.ViewList,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(option.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private enum class MonthViewMode(val label: String) { CALENDAR("Calendar"), LIST("List") }

/** One row per day that has ministry minutes and/or Credit Hours, newest
 * first — spec §21's "simpler chronological presentation" alternative to
 * the calendar grid, reading the exact same [dailyMinutes]/
 * [dailyCreditMinutes] maps so the two views can never disagree. */
@Composable
private fun MonthListView(
    monthStart: Long,
    dailyMinutes: Map<Long, Int>,
    dailyCreditMinutes: Map<Long, Int>,
    dailyReturnVisits: Map<Long, Int>,
    dailyBibleStudies: Map<Long, Int>,
    onDayClick: (Long) -> Unit,
) {
    val visibility = LocalPlannerVisibility.current
    // Every day with anything at all recorded — Hours, Credit, Return
    // Visits or Bible Studies — so a day with only a Return Visit and no
    // ministry time still shows up (spec §30/§38), not just Hours/Credit
    // days as before.
    val days = remember(dailyMinutes, dailyCreditMinutes, dailyReturnVisits, dailyBibleStudies) {
        (dailyMinutes.keys + dailyCreditMinutes.keys + dailyReturnVisits.keys + dailyBibleStudies.keys).distinct().sortedDescending()
    }
    if (days.isEmpty()) {
        PlannerEmptyHint("No activity recorded yet this month.")
    } else {
        Column {
            days.forEach { dayStart ->
                MonthListRow(
                    dayStart = dayStart,
                    minutes = dailyMinutes[dayStart] ?: 0,
                    creditMinutes = dailyCreditMinutes[dayStart] ?: 0,
                    returnVisits = dailyReturnVisits[dayStart] ?: 0,
                    bibleStudies = dailyBibleStudies[dayStart] ?: 0,
                    visibility = visibility,
                    onClick = { onDayClick(dayStart) },
                )
            }
        }
    }
}

/** One Monthly List View row — spec §30/§38's "compact stacked list" mobile
 * presentation, not the wide Date/Hours/Credit/RV/BS table: date + hours on
 * the header line, then Credit and Return Visits/Bible Studies (whichever
 * the Publisher hasn't hidden — spec §31/§34) on their own compact line
 * underneath. The whole row opens that day (spec §35). */
@Composable
private fun MonthListRow(
    dayStart: Long,
    minutes: Int,
    creditMinutes: Int,
    returnVisits: Int,
    bibleStudies: Int,
    visibility: PlannerVisibility,
    onClick: () -> Unit,
) {
    val detailParts = buildList {
        if (visibility.creditHours && creditMinutes > 0) add("Credit ${formatHoursMinutes(creditMinutes)}")
        if (visibility.returnVisits) add("Return Visits $returnVisits")
        if (visibility.bibleStudies) add("Bible Studies $bibleStudies")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${formatRecordDate(dayStart)}", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(formatRecordDate(dayStart), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (detailParts.isNotEmpty()) {
                Text(
                    detailParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (visibility.hours) {
            Text(formatHoursMinutes(minutes), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A compact 7-column calendar — no library dependency, same "build it from
 * Compose primitives" convention the app's charts use. Each cell shows the
 * date (visually prominent) with its accumulated regular Hours/Minutes
 * underneath in small type (spec §20/§25) — a dash for a day with nothing
 * recorded, never a blank cell (spec §20.C). Today is outlined; tapping any
 * day opens it in the Day view. */
@Composable
private fun MonthCalendarGrid(
    monthStart: Long,
    dailyMinutes: Map<Long, Int>,
    dailyCreditMinutes: Map<Long, Int>,
    activeDayStarts: Set<Long>,
    onDayClick: (Long) -> Unit,
) {
    val calendar = remember(monthStart) { Calendar.getInstance().apply { timeInMillis = monthStart } }
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Calendar.DAY_OF_WEEK is 1=Sunday..7=Saturday; convert to a 0-based
    // leading-blank-cell count for a Sunday-first grid.
    val leadingBlanks = calendar.get(Calendar.DAY_OF_WEEK) - 1
    val todayStart = remember { DayBounds.of(System.currentTimeMillis()).startInclusive }
    val cells = remember(monthStart) {
        buildList<Long?> {
            repeat(leadingBlanks) { add(null) }
            for (day in 1..daysInMonth) {
                add(
                    Calendar.getInstance().apply {
                        timeInMillis = monthStart
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis,
                )
            }
        }
    }

    // "Clear grid structure... visible cell borders, clear rows and
    // columns... solid, high-contrast borders" — a real table, not just
    // colored text floating with no structure. [gridLineColor] is a solid,
    // theme-aware but always-visible line (works the same in light and
    // dark), used on every cell — including a blank leading/trailing one —
    // so the grid lines stay continuous across the whole calendar.
    val gridLineColor = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .border(1.dp, gridLineColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).border(0.5.dp, gridLineColor).padding(vertical = 4.dp),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { dayStart ->
                    Box(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).border(0.5.dp, gridLineColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (dayStart != null) {
                            val minutes = dailyMinutes[dayStart] ?: 0
                            val hasCredit = (dailyCreditMinutes[dayStart] ?: 0) > 0
                            val isActive = dayStart in activeDayStarts
                            val isToday = dayStart == todayStart
                            val dayNumber = Calendar.getInstance().apply { timeInMillis = dayStart }.get(Calendar.DAY_OF_MONTH)
                            // "Color-code yellow if there is a record inside" —
                            // a day with anything logged (hours, credit, a
                            // return visit or a bible study) gets a solid
                            // amber fill, not just a faint tint, so it reads
                            // at a glance across the whole month; animated so
                            // switching months/toggling sections crossfades
                            // instead of popping.
                            val cellColor by animateColorAsState(
                                targetValue = if (isActive) RecordDayColor else Color.Transparent,
                                label = "calendarCellColor",
                            )
                            val onCellColor = if (isActive) OnRecordDayColor else MaterialTheme.colorScheme.onSurface
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(cellColor)
                                    .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable(role = Role.Button, onClickLabel = "Open day $dayNumber, ${formatHoursMinutes(minutes)}") { onDayClick(dayStart) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Date is the visually prominent element (spec §25);
                                // the accumulated time underneath is smaller but
                                // still bold and colored, not a faint gray
                                // afterthought, so it's readable at a glance.
                                Text(
                                    dayNumber.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isActive || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = onCellColor,
                                )
                                Text(
                                    // Spec §20.C — a dash for a day with nothing
                                    // recorded, never a blank cell.
                                    if (minutes > 0) formatHoursMinutes(minutes) else "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (minutes > 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (minutes > 0) onCellColor else MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                )
                                if (hasCredit) {
                                    Box(modifier = Modifier.padding(top = 1.dp).size(4.dp).clip(CircleShape).background(onCellColor))
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Year
// ---------------------------------------------------------------------------

/** My Planner → Year (spec §26-§27). Every figure independently computed for
 * the whole year via [com.emfitsolutions.gopreach.domain.MinistryStatisticsService]
 * — never summed from the 12 monthly values. Year → Month → record detail:
 * each month row opens that month's planner. */
@Composable
internal fun PlannerYearContent(
    currentPersonId: String,
    viewModel: PlannerYearViewModel,
    onOpenMonth: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenPerson: (PipelineStage, String) -> Unit,
    onOpenPersonList: (PipelineStage) -> Unit,
) {
    val yearStart by viewModel.yearStart.collectAsStateWithLifecycle()
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }.collectAsStateWithLifecycle()
    val yearFormat = remember { SimpleDateFormat("yyyy", Locale.getDefault()) }

    val creditViewModel: CreditHourEntryViewModel = koinViewModel()
    val creditDialogs = remember { CreditHourDialogState() }
    val categoryName = rememberCategoryNamer(creditViewModel)
    val expansion = rememberPlannerExpansionState(PlannerSectionKey.MONTHS)

    var showYearList by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        PlannerDateNavHeader(
            label = yearFormat.format(Date(yearStart)),
            onPrevious = viewModel::goToPreviousYear,
            onNext = viewModel::goToNextYear,
            onOpenList = { showYearList = true },
        )
        if (showYearList) {
            // Item 4 — "Year List View": the currently viewed year plus the
            // 5 before it, selectable, newest last (same "oldest → newest"
            // convention as every other multi-period list in this file).
            val years = remember(yearStart) {
                val currentYear = Calendar.getInstance().apply { timeInMillis = yearStart }.get(Calendar.YEAR)
                ((currentYear - 5)..currentYear).map { year ->
                    Calendar.getInstance().apply {
                        set(Calendar.YEAR, year); set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
            }
            PeriodListDialog(
                title = "Select a Year",
                items = years.map { start -> yearFormat.format(Date(start)) to null },
                onDismiss = { showYearList = false },
                onSelect = { index -> viewModel.goToYear(years[index]) },
            )
        }
        PlannerLoadingBar(state.isLoading)

        BoxWithConstraints(modifier = Modifier.padding(PlannerBodyPadding)) {
            // See PlannerDayContent's identical [rememberLabelColumnWidth] note.
            val labelColumnWidth = rememberLabelColumnWidth(
                labels = listOf("Hours Goal for this Year", "Months", "Hours / Minutes", "Credit Hours", "Return Visits", "Bible Studies"),
                availableWidth = maxWidth,
            )
            // See PlannerDayContent's identical [valueColumnWidth] note.
            val valueColumnWidth = rememberLabelColumnWidth(
                labels = listOf(
                    formatHoursMinutes(state.totalMinutes),
                    formatHoursMinutes(state.records.creditRecords.sumOf { it.totalMinutes }),
                    personCountSummary(state.returnVisitCount),
                    personCountSummary(state.bibleStudyCount),
                ),
                availableWidth = maxWidth,
                style = MaterialTheme.typography.labelLarge,
            )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlannerActivitySummary(
                stats = periodStats(state.totalMinutes, state.creditHoursMinutes, state.returnVisitCount, state.bibleStudyCount, LocalPlannerVisibility.current),
                onStatClick = expansion::expand,
            )
            PlannerGoalRow(
                label = "Hours Goal for this Year",
                goalHours = state.goalHours,
                remainingMinutes = state.remainingMinutes,
                surplusMinutes = state.surplusMinutes,
                onSetGoal = { viewModel.setGoalHours(currentPersonId, it) },
                labelColumnWidth = labelColumnWidth,
            )

            PlannerExpandableSection(
                title = "Months",
                expanded = expansion.isExpanded(PlannerSectionKey.MONTHS),
                onToggle = { expansion.toggle(PlannerSectionKey.MONTHS) },
                summary = "${state.monthRows.count { it.totalMinutes > 0 || it.creditMinutes > 0 }} active",
                labelColumnWidth = labelColumnWidth,
            ) {
                state.monthRows.forEach { row ->
                    val diff = row.surplusOrMissingMinutes
                    val subtitle = buildList {
                        if (row.goalHours > 0) {
                            add("Goal ${row.goalHours}h (${if (diff >= 0) "+" else "−"}${formatHoursMinutes(kotlin.math.abs(diff))})")
                        }
                        if (LocalPlannerVisibility.current.creditHours && row.creditMinutes > 0) add("Credit ${formatHoursMinutes(row.creditMinutes)}")
                    }.joinToString(" · ")
                    PlannerRecordRow(
                        title = row.label,
                        subtitle = subtitle.ifBlank { null },
                        trailing = formatHoursMinutes(row.totalMinutes),
                        onClickLabel = "Open ${row.label}",
                        onClick = { onOpenMonth(row.monthStart) },
                    )
                }
            }

            val bounds = remember(yearStart) { com.emfitsolutions.gopreach.domain.YearBounds.of(yearStart) }
            PlannerRecordSections(
                records = state.records,
                expansion = expansion,
                categoryName = categoryName,
                onOpenCredit = { creditDialogs.detail = it },
                onAddCredit = { creditDialogs.addingForDay = defaultEntryDay(bounds, yearStart) },
                periodLocked = false, // a year spans many months; the credit dialog still refuses submitted dates
                onOpenPerson = onOpenPerson,
                onOpenPersonList = onOpenPersonList,
                onOpenDay = onOpenDay,
                labelColumnWidth = labelColumnWidth,
                valueColumnWidth = valueColumnWidth,
            )
        }
        }
    }

    CreditHourDialogsHost(creditDialogs, currentPersonId, creditViewModel)
}
