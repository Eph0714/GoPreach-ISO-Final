package com.emfitsolutions.gopreach.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.DualPeriodFilter
import com.emfitsolutions.gopreach.ui.components.countFormat
import com.emfitsolutions.gopreach.ui.components.MonthRange
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.charts.LineSeries
import com.emfitsolutions.gopreach.ui.components.charts.MultiSeriesLineChart
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Searching / Return Visit / Bible Study counts for one calendar month. */
data class StageCounts(val searching: Int, val returnVisit: Int, val bibleStudy: Int) {
    fun of(stage: PipelineStage): Int = when (stage) {
        PipelineStage.SEARCHING -> searching
        PipelineStage.RETURN_VISIT -> returnVisit
        PipelineStage.BIBLE_STUDY -> bibleStudy
    }

    operator fun plus(other: StageCounts) = StageCounts(searching + other.searching, returnVisit + other.returnVisit, bibleStudy + other.bibleStudy)

    companion object {
        val Zero = StageCounts(0, 0, 0)
    }
}

@HiltViewModel
class ComparativeReportViewModel @Inject constructor(
    private val interestedPersonRepository: InterestedPersonRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Month-by-month counts for [range]: records of [congregationId] that entered each stage during each month — the
     * same `stageEnteredAt` the Consolidated Report and Publisher Dashboard count Bible Studies by (it equals the
     * creation time for a record that started in that stage). Only the congregation's own active records are counted.
     */
    fun monthlyCounts(congregationId: String, range: MonthRange): Flow<List<Pair<Long, StageCounts>>> =
        interestedPersonRepository.observeAll().map { people ->
            val mine = people.filter { it.congregationId == congregationId && it.status == RecordStatus.ACTIVE }
            range.months().map { monthStart ->
                val end = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, 1) }.timeInMillis
                val inMonth = mine.filter { (it.stageEnteredAt.takeIf { t -> t > 0L } ?: it.createdAt) in monthStart until end }
                monthStart to StageCounts(
                    searching = inMonth.count { it.pipelineStage == PipelineStage.SEARCHING },
                    returnVisit = inMonth.count { it.pipelineStage == PipelineStage.RETURN_VISIT },
                    bibleStudy = inMonth.count { it.pipelineStage == PipelineStage.BIBLE_STUDY },
                )
            }
        }
}

private val ColorA = Color(0xFF1E88E5)
private val ColorB = Color(0xFFEF6C00)
private val SearchingColor = Color(0xFF00897B)
private val ReturnVisitColor = Color(0xFF1E88E5)
private val BibleStudyColor = Color(0xFF8E24AA)

/**
 * Admin "Comparative Report": Searching, Return Visit and Bible Study counts for two separate month ranges (first vs
* second, any lengths), chosen with the shared [DualPeriodFilter]. Each period keeps its month-by-month counts for a
 * line graph; totals and the difference are shown side by side on wide screens, stacked on narrow ones.
 * [fixedCongregationId] is the access boundary; a Super-Admin (null) must pick a Congregation first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparativeReportScreen(
    fixedCongregationId: String?,
    onBack: () -> Unit,
    viewModel: ComparativeReportViewModel = hiltViewModel(),
) {
    var congregationFilter by rememberCongregationContext("comparative_report")
    val congregationId = fixedCongregationId ?: congregationFilter
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // Defaults to last month vs this month, applied straight away; Compare re-runs it for any other two ranges.
    var periods by remember { mutableStateOf(MonthRange.ofMonth(1) to MonthRange.ofMonth(0)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comparative Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (fixedCongregationId == null) {
                CongregationFilterDropdown(
                    congregations = congregations,
                    selectedCongregationId = congregationFilter,
                    onSelected = { congregationFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (congregationId == null) {
                SelectCongregationPrompt()
                return@Column
            }
            val (a, b) = periods
            val rowsA by remember(congregationId, a) { viewModel.monthlyCounts(congregationId, a) }.collectAsStateWithLifecycle(initialValue = emptyList())
            val rowsB by remember(congregationId, b) { viewModel.monthlyCounts(congregationId, b) }.collectAsStateWithLifecycle(initialValue = emptyList())
            val totalA = rowsA.fold(StageCounts.Zero) { acc, (_, c) -> acc + c }
            val totalB = rowsB.fold(StageCounts.Zero) { acc, (_, c) -> acc + c }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DualPeriodFilter(initialA = a, initialB = b, onApply = { newA, newB -> periods = newA to newB })

                // The graph and summary sit directly in the report, labelled with the actual ranges chosen above.
                val labelA = a.label()
                val labelB = b.label()
                val metrics = remember(rowsA, rowsB) {
                    listOf<com.emfitsolutions.gopreach.ui.components.GraphMetric>(
                        com.emfitsolutions.gopreach.ui.components.GraphMetric("Searching", ::countFormat, rowsA.map { it.second.searching.toDouble() }, rowsB.map { it.second.searching.toDouble() }),
                        com.emfitsolutions.gopreach.ui.components.GraphMetric("Return Visit", ::countFormat, rowsA.map { it.second.returnVisit.toDouble() }, rowsB.map { it.second.returnVisit.toDouble() }),
                        com.emfitsolutions.gopreach.ui.components.GraphMetric("Bible Study", ::countFormat, rowsA.map { it.second.bibleStudy.toDouble() }, rowsB.map { it.second.bibleStudy.toDouble() }),
                    )
                }
                var metricIndex by remember { mutableStateOf(0) }
                Text("$labelA vs $labelB", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                com.emfitsolutions.gopreach.ui.components.ComparativeGraphReport(
                    labelA = labelA,
                    labelB = labelB,
                    monthsA = rowsA.map { it.first },
                    monthsB = rowsB.map { it.first },
                    metrics = metrics,
                    selected = metricIndex,
                    onSelect = { metricIndex = it },
                )
                Text(
                    "Counts are the records that entered each stage during each month, for this congregation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun stageName(stage: PipelineStage): String = when (stage) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}
