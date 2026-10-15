package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportViewModel
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

private fun monthName(millis: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))
private fun stamp(millis: Long): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(millis))

/**
 * Admin app -> Reports -> My Submitted Reports: the Field Service Report snapshots THIS congregation sent to its Circuit Overseer, one per
 * service month and send, each opened as the frozen congregation sheet exactly as the overseer sees it. Read-only; sending, undoing and
 * re-sending stay in the Field Service Report. [fixedCongregationId] is the admin's own congregation; the Super-Admin picks one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentReportsScreen(
    currentPersonId: String,
    fixedCongregationId: String?,
    onBack: () -> Unit,
    viewModel: ReceivedReportsViewModel = koinViewModel(),
    congregationsSource: FieldServiceReportViewModel = koinViewModel(),
) {
    val congregations by congregationsSource.congregations.collectAsStateWithLifecycle(initialValue = emptyList())
    val launched = remember { SentReportsLaunch.take() }
    var picked by rememberSaveable { mutableStateOf(launched.second?.let(::congregationOfSnapshotId)) }
    val congregation = congregations.firstOrNull { it.id == (fixedCongregationId ?: picked) }
    var openId by rememberSaveable { mutableStateOf(launched.second.takeIf { launched.first != "att" }) }
    var kind by rememberSaveable { mutableStateOf(if (launched.first == "att") "att" else "fs") }
    BackHandler(enabled = openId != null) { openId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (openId != null) "Submitted Snapshot" else "My Submitted Reports") },
                navigationIcon = { IconButton(onClick = { if (openId != null) openId = null else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (fixedCongregationId == null && openId == null) {
                SimpleDropdown(
                    label = "Congregation", selectedLabel = congregation?.name ?: "Select a congregation",
                    options = congregations.map { it.id to it.name }, onSelected = { picked = it },
                )
            }
            if (congregation == null) {
                Text("Select a congregation to see the reports it sent.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (openId == null) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "fs", onClick = { kind = "fs" }, label = { Text("Field Service") })
                    FilterChip(selected = kind == "att", onClick = { kind = "att" }, label = { Text("Attendance") })
                }
            }
            if (kind == "att") {
                SentAttendance(congregation.id, currentPersonId, initialOpenId = launched.second.takeIf { launched.first == "att" })
                return@Column
            }
            val months by remember(congregation.id) { viewModel.months(currentPersonId, congregation.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
            // A month that was undone is back to Draft (no status the overseer can see): its old copy is kept as history but is not listed.
            val sentMonths = months.filter { it.report != null && it.status != null }
            val versionsOfOpen by remember(openId) {
                viewModel.versions(congregation.id, openId?.substringBeforeLast('_')?.substringAfterLast('_')?.toLongOrNull() ?: 0L)
            }.collectAsStateWithLifecycle(initialValue = emptyList())
            val open = openId?.let { id -> versionsOfOpen.firstOrNull { it.id == id } }

            if (open != null) {
                val status = months.firstOrNull { it.periodMonth == open.periodMonth }?.status
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status?.let { CoStatusBadge(it.status.workflowLabel, badge(it.status)) }
                    Text("Current version: " + (status?.version ?: open.version), style = MaterialTheme.typography.labelMedium)
                }
                if (versionsOfOpen.size > 1) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        versionsOfOpen.forEach { v ->
                            FilterChip(selected = v.id == open.id, onClick = { openId = v.id }, label = { Text("Send ${v.version}" + if (v.id == versionsOfOpen.first().id) " (latest)" else "") })
                        }
                    }
                }
                status?.returnReason?.takeIf { status.status == CoReportStatus.RETURNED && it.isNotBlank() }?.let {
                    Text("Returned: $it", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                ReceivedReportTable(open, Modifier.weight(1f), showVersion = true)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            if (sentMonths.isEmpty()) "No report has been sent yet." else "Reports Sent: ${sentMonths.size}",
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(sentMonths, key = { it.periodMonth }) { m ->
                        val copy = m.report!!
                        CoCard(Modifier.fillMaxWidth(), onClick = { openId = copy.id }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(monthName(m.periodMonth), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    m.status?.let { CoStatusBadge(it.status.workflowLabel, badge(it.status)) }
                                }
                                Text("Version ${copy.version} · sent ${stamp(copy.submittedAt)}" + copy.submittedByName.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Publishers ${copy.publisherCount} · Participated ${copy.participatedCount}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Carries "open this sent report" from the admin Comparative Report to My Submitted Reports (kind "fs" or "att" + the snapshot id). */
object SentReportsLaunch {
    var kind: String? = null
    var reportId: String? = null
    fun take(): Pair<String?, String?> = (kind to reportId).also { kind = null; reportId = null }
}

private fun badge(status: CoReportStatus): CoKind = when (status) {
    CoReportStatus.RECEIVED -> CoKind.Success
    CoReportStatus.RETURNED, CoReportStatus.CANCELLED -> CoKind.Warning
    else -> CoKind.Primary
}


/** The Meeting Attendance months this congregation sent: the same list, versions and frozen sheet the Circuit Overseer sees (read only). */
@Composable
private fun SentAttendance(congregationId: String, currentPersonId: String, initialOpenId: String? = null, viewModel: ReceivedAttendanceViewModel = koinViewModel()) {
    val months by remember(congregationId, currentPersonId) { viewModel.months(congregationId, currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var openId by rememberSaveable { mutableStateOf(initialOpenId) }
    BackHandler(enabled = openId != null) { openId = null }
    val versions by remember(openId) {
        viewModel.versions(congregationId, openId?.substringBeforeLast('_')?.substringAfterLast('_')?.toLongOrNull() ?: 0L)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val open = openId?.let { id -> versions.firstOrNull { it.id == id } }
    if (open != null) {
        val status = months.firstOrNull { it.periodMonth == open.periodMonth }?.status
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let { CoStatusBadge(it.status.workflowLabel, badge(it.status)) }
                Text("Current version: " + (status?.version ?: open.version), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { openId = null }) { Text("All months") }
            }
            if (versions.size > 1) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    versions.forEach { v -> FilterChip(selected = v.id == open.id, onClick = { openId = v.id }, label = { Text("Send ${v.version}" + if (v.id == versions.first().id) " (latest)" else "") }) }
                }
            }
            status?.returnReason?.takeIf { status.status == CoReportStatus.RETURNED && it.isNotBlank() }?.let {
                Text("Returned: $it", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            AttendanceSnapshotSheet(open, Modifier.weight(1f), showVersion = true)
        }
        return
    }
    val sent = months.filter { it.snapshot != null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(if (sent.isEmpty()) "No attendance has been sent yet." else "Months Sent: ${sent.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
        items(sent, key = { it.periodMonth }) { m ->
            val copy = m.snapshot!!
            CoCard(Modifier.fillMaxWidth(), onClick = { openId = copy.id }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(monthName(m.periodMonth), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        CoStatusBadge(m.status.status.workflowLabel, badge(m.status.status))
                    }
                    Text("Version ${copy.version} · sent ${stamp(copy.submittedAt)}" + copy.submittedByName.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Midweek ${copy.midweek.size} · Weekend ${copy.weekend.size} meetings", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}