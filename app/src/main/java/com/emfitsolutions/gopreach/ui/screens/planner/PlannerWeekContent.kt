package com.emfitsolutions.gopreach.ui.screens.planner

import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.domain.WeekBounds
import com.emfitsolutions.gopreach.platform.Calendar

/** My Planner → Week (Dashboard/My Planner integration spec §4-§7) — a
 * "Weekly Report" summary, an "Hour Goal for This Week" stepper (Remaining
 * per spec §4/§13 also subtracts Credit Hours, unlike Day/Month/Year — see
 * [PlannerWeekUiState.remainingMinutes]), a Monday..Sunday breakdown with
 * the current day marked, then the week's individual records. Every day row
 * and every record row is tappable. */
@Composable
internal fun PlannerWeekContent(
    currentPersonId: String,
    viewModel: PlannerWeekViewModel,
    onOpenDay: (Long) -> Unit,
    onOpenPerson: (PipelineStage, String) -> Unit,
    onOpenPersonList: (PipelineStage) -> Unit,
) {
    val weekStart by viewModel.weekStart.collectAsStateWithLifecycle()
    // See PlannerDayContent's identical remember(currentPersonId) note —
    // stateFor() builds a fresh combine()+stateIn() pipeline each call.
    val state by remember(currentPersonId) { viewModel.stateFor(currentPersonId) }.collectAsStateWithLifecycle()
    val weekFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val weekEnd = remember(weekStart) { Calendar.getInstance().apply { timeInMillis = weekStart; add(Calendar.DAY_OF_MONTH, 6) }.timeInMillis }
    val weekLabel = "${weekFormat.format(Date(weekStart))} – ${weekFormat.format(Date(weekEnd))}"

    val creditViewModel: CreditHourEntryViewModel = koinViewModel()
    val creditDialogs = remember { CreditHourDialogState() }
    val categoryName = rememberCategoryNamer(creditViewModel)
    val expansion = rememberPlannerExpansionState(PlannerSectionKey.DAYS)

    var showWeekList by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        PlannerDateNavHeader(
            label = weekLabel,
            onPrevious = viewModel::goToPreviousWeek,
            onNext = viewModel::goToNextWeek,
            onOpenList = { showWeekList = true },
        )
        if (showWeekList) {
            // Item 3 — "Week List View": every Monday-start week that
            // overlaps the currently viewed week's own calendar month,
            // selectable, each with its actual date range as the subtitle.
            val weeks = remember(weekStart) {
                val monthCal = Calendar.getInstance().apply {
                    timeInMillis = weekStart
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val monthEnd = (monthCal.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
                val result = mutableListOf<Long>()
                var cursor = WeekBounds.of(monthCal.timeInMillis).startInclusive
                while (cursor < monthEnd) {
                    result.add(cursor)
                    cursor = Calendar.getInstance().apply { timeInMillis = cursor; add(Calendar.DAY_OF_MONTH, 7) }.timeInMillis
                }
                result
            }
            PeriodListDialog(
                title = "Select a Week",
                items = weeks.mapIndexed { index, start ->
                    val end = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_MONTH, 6) }.timeInMillis
                    "Week ${index + 1}" to "${weekFormat.format(Date(start))} – ${weekFormat.format(Date(end))}"
                },
                onDismiss = { showWeekList = false },
                onSelect = { index -> viewModel.goToWeek(weeks[index]) },
            )
        }
        PlannerLoadingBar(state.isLoading)

        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.padding(PlannerBodyPadding)) {
            // See PlannerDayContent's identical [rememberLabelColumnWidth] note.
            val labelColumnWidth = rememberLabelColumnWidth(
                labels = listOf("Hours Goal for this Week", "Days", "Hours / Minutes", "Credit Hours", "Return Visits", "Bible Studies"),
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
                    label = "Hours Goal for this Week",
                    goalHours = state.goalHours,
                    remainingMinutes = state.remainingMinutes,
                    surplusMinutes = state.surplusMinutes,
                    onSetGoal = { viewModel.setGoalHours(currentPersonId, it) },
                    labelColumnWidth = labelColumnWidth,
                )

                PlannerExpandableSection(
                    title = "Days",
                    expanded = expansion.isExpanded(PlannerSectionKey.DAYS),
                    onToggle = { expansion.toggle(PlannerSectionKey.DAYS) },
                    labelColumnWidth = labelColumnWidth,
                    valueColumnWidth = valueColumnWidth,
                ) {
                    state.dayRows.forEach { row -> WeekDayRow(row, onClick = { onOpenDay(row.dayStart) }) }
                }

                val bounds = remember(weekStart) { WeekBounds.of(weekStart) }
                PlannerRecordSections(
                    records = state.records,
                    expansion = expansion,
                    categoryName = categoryName,
                    onOpenCredit = { creditDialogs.detail = it },
                    onAddCredit = { creditDialogs.addingForDay = defaultEntryDay(bounds, weekStart) },
                    periodLocked = LocalPlannerLock.current.isLocked(weekStart) && LocalPlannerLock.current.isLocked(weekEnd),
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

/** One line per weekday: name + date, that day's RV/BS/Credit on the
 * second line, ministry time on the right. Tapping opens the Day view. */
@Composable
private fun WeekDayRow(row: PlannerWeekDayRow, onClick: () -> Unit) {
    val visibility = LocalPlannerVisibility.current
    val details = buildList {
        if (visibility.creditHours && row.creditMinutes > 0) add("Credit ${formatHoursMinutes(row.creditMinutes)}")
        if (visibility.returnVisits && row.returnVisitCount > 0) add("RV ${row.returnVisitCount}")
        if (visibility.bibleStudies && row.bibleStudyCount > 0) add("BS ${row.bibleStudyCount}")
    }.joinToString(" · ")
    PlannerRecordRow(
        title = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(row.dayStart)) + if (row.isToday) " · Today" else "",
        subtitle = details.ifBlank { null },
        trailing = if (visibility.hours) formatHoursMinutes(row.totalMinutes) else null,
        onClickLabel = "Open this day",
        emphasize = row.isToday,
        onClick = onClick,
    )
}
