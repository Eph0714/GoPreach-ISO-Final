package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.emfitsolutions.gopreach.ui.components.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import org.koin.compose.viewmodel.koinViewModel

/**
 * Circuit Overseer -> Received Reports: the one place the overseer's reports live. Two categories, each opening its own list:
 *  - Field Service Report: the monthly reports the assigned congregations submitted (frozen snapshots, with Mark as Received / Return for
 *    Editing / Cancel), counted as awaiting review and received.
 *  - Circuit Attendance Report: the consolidated report of the overseer's congregations.
 * It only counts and routes; nothing here reads or changes report data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedReportsHubScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenFieldService: () -> Unit,
    onOpenCircuitAttendance: () -> Unit,
    viewModel: CircuitReportsInboxViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
    attendance: ReceivedAttendanceViewModel = koinViewModel(),
) {
    val congregations by remember(currentPersonId) { people.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = remember(congregations) { congregations.map { it.id }.toSet() }
    val items by remember(ids, currentPersonId) { viewModel.items(ids, currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val awaiting = items.count { it.status.status == CoReportStatus.SUBMITTED }
    val received = items.count { it.status.status == CoReportStatus.RECEIVED }
    val returned = items.count { it.status.status == CoReportStatus.RETURNED }
    val unreadAttendance by remember(currentPersonId, ids) { attendance.unread(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val newAttendance = unreadAttendance.count { it.congregationId in ids }

    Scaffold(
        circuitTab = com.emfitsolutions.gopreach.ui.components.CircuitTab.RECEIVED,
        topBar = {
            TopAppBar(
                title = { Text("Received Reports") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Reports submitted to you by your assigned congregations.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryCard(
                    icon = Icons.Rounded.Assessment,
                    title = "Field Service Report",
                    description = "Review monthly field service reports submitted by assigned congregations.",
                    onClick = onOpenFieldService,
                ) {
                    if (awaiting > 0) CoStatusBadge("$awaiting awaiting review", CoKind.Primary)
                    CoStatusBadge("$received received", CoKind.Success)
                    if (returned > 0) CoStatusBadge("$returned returned", CoKind.Warning)
                }
                CategoryCard(
                    icon = Icons.Rounded.HowToReg,
                    title = "Circuit Attendance Report",
                    description = "Review circuit attendance reports submitted by assigned congregations.",
                    onClick = onOpenCircuitAttendance,
                ) {
                    if (newAttendance > 0) CoStatusBadge("$newAttendance new", CoKind.Danger)
                    CoStatusBadge("${congregations.size} congregation${if (congregations.size == 1) "" else "s"}", CoKind.Neutral)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    badges: @Composable () -> Unit,
) {
    CoCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { badges() }
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
