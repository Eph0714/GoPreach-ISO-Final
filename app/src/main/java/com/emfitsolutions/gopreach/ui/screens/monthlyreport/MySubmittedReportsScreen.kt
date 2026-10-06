package com.emfitsolutions.gopreach.ui.screens.monthlyreport

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.data.model.displayName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.domain.MonthlyReportCalculator
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Allow the publisher to see all his submitted Report record" — every
 * Monthly Report this Publisher has ever filed, newest first, read-only
 * (editing stays on [MonthlyReportScreen], which is deliberately limited to
 * the current/previous month — see [MySubmittedReportsViewModel]'s doc
 * comment for why that screen alone can't answer "show me everything I've
 * submitted").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySubmittedReportsScreen(
    publisherPersonId: String,
    onBack: () -> Unit,
    viewModel: MySubmittedReportsViewModel = hiltViewModel(),
) {
    val reportsFlow = remember(publisherPersonId, viewModel) { viewModel.reportsFor(publisherPersonId) }
    val reports by reportsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_tile_my_reports_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (reports.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordFound(0)
                Text(
                    stringResource(R.string.my_reports_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RecordFound(reports.size) }
                items(reports, key = { it.id }) { report ->
                    SubmittedReportCard(report)
                }
            }
        }
    }
}

private val periodFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

@Composable
private fun SubmittedReportCard(report: MonthlyReport) {
    val isPioneer = MonthlyReportCalculator.isPioneerCategory(report.category)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(periodFormat.format(Date(report.periodMonth)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StatusChip(report.status)
            }
            Text(
                report.category.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.my_reports_bible_studies_conducted, report.bibleStudiesCount), style = MaterialTheme.typography.bodyMedium)
            if (isPioneer) {
                Text(stringResource(R.string.my_reports_hours_rendered, (report.hoursRendered ?: 0.0).toString()), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.my_reports_participated, if (report.participatedInPreaching == true) stringResource(R.string.home_yes) else stringResource(R.string.home_no)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (report.submittedAt != null) {
                Text(
                    stringResource(R.string.my_reports_submitted_at, formatRecordTimestamp(report.submittedAt!!)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: ReportStatus) {
    val (containerColor, contentColor) = when (status) {
        ReportStatus.POSTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ReportStatus.SUBMITTED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ReportStatus.DRAFT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ReportStatus.RETURNED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ReportStatus.CORRECTED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Text(
            status.label(),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
