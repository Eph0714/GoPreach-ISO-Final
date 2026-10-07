package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.domain.RecordScope
import com.emfitsolutions.gopreach.ui.screens.manualreport.ManualFieldServiceViewModel
import com.emfitsolutions.gopreach.ui.screens.manualreport.ManualPublisher
import com.emfitsolutions.gopreach.ui.screens.manualreport.ManualRecordInput
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.launch

/** What the Table View offers on each publisher line for a user allowed to edit it. */
class RowActions(
    val onEdit: (FieldServiceReportRow) -> Unit,
    val onDelete: (FieldServiceReportRow) -> Unit,
)

/**
 * The line above the Table View that states exactly what the active role may do here: role, scope, month and the month's
 * report status (spec: "Active Role / FS Group / Scope / Report Month / Report Status").
 */
@Composable
fun RecordScopeBar(
    activeRole: String,
    scope: RecordScope,
    congregationName: String?,
    groupName: String?,
    monthLabel: String,
    status: CoMonthStatus?,
    modifier: Modifier = Modifier,
) {
    val state = status?.status ?: CoReportStatus.NOT_SUBMITTED
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Active Role: $activeRole", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            if (scope.groupId != null) Text("FS Group: ${groupName ?: "—"}", style = MaterialTheme.typography.bodySmall)
            Text("Scope: ${scope.describe(congregationName, groupName)}", style = MaterialTheme.typography.bodySmall)
            Text("Report Month: $monthLabel", style = MaterialTheme.typography.bodySmall)
            Text(
                "Report Status: " + if (state.locksMonth) "${state.label} — locked" else state.label,
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Add or edit one publisher's record for the month, straight from the Table View. A new record offers only the publishers of
 * [publishers] (already limited to the active role's scope); an edit is fixed to its publisher. Saving goes through
 * [ManualFieldServiceViewModel.save], which re-checks role, scope and month lock before anything is written.
 */
@Composable
fun RecordEditDialog(
    month: Long,
    monthLabel: String,
    congregationId: String,
    actorPersonId: String,
    actorRole: AdminRole?,
    scopeGroupId: String?,
    publishers: List<ManualPublisher>,
    existing: List<MonthlyReport>,
    fixedPublisherId: String?,
    manual: ManualFieldServiceViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var publisherId by remember { mutableStateOf(fixedPublisherId) }
    val publisher = publishers.firstOrNull { it.person.id == publisherId }
    val current = existing.firstOrNull { it.publisherPersonId == publisherId && it.periodMonth == month }
    var hours by remember(publisherId) { mutableStateOf(current?.hoursRendered?.let { it.toInt().toString() } ?: "0") }
    var minutes by remember(publisherId) { mutableStateOf(current?.hoursRendered?.let { Math.round((it - it.toInt()) * 60).toInt().toString() } ?: "0") }
    var participated by remember(publisherId) { mutableStateOf(current?.participatedInPreaching) }
    var studies by remember(publisherId) { mutableStateOf((current?.bibleStudiesCount ?: 0).toString()) }
    var returnVisits by remember(publisherId) { mutableStateOf((current?.returnVisitsCount ?: 0).toString()) }
    var remarks by remember(publisherId) { mutableStateOf(current?.remarks.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fixedPublisherId != null) "Edit Record — $monthLabel" else "Add Record — $monthLabel") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixedPublisherId == null) {
                    SimpleDropdown(
                        label = "Publisher",
                        selectedLabel = publisher?.person?.fullName ?: "Select a publisher",
                        options = publishers.map { it.person.id to it.person.fullName },
                        onSelected = { publisherId = it },
                    )
                } else {
                    Text(publisher?.person?.fullName.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                if (publisher != null) {
                    if (publisher.isPioneer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberBox("Hours", hours, { hours = it }, Modifier.weight(1f))
                            NumberBox("Minutes", minutes, { minutes = it }, Modifier.weight(1f))
                        }
                    } else {
                        Text("Participation", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = participated == true, onClick = { participated = true })
                            Text("Yes")
                            RadioButton(selected = participated == false, onClick = { participated = false })
                            Text("No")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberBox("Bible Studies", studies, { studies = it }, Modifier.weight(1f))
                        NumberBox("Return Visits", returnVisits, { returnVisits = it }, Modifier.weight(1f))
                    }
                    OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = publisher != null && !busy,
                onClick = {
                    val p = publisher ?: return@Button
                    busy = true
                    scope.launch {
                        val message = manual.save(
                            ManualRecordInput(
                                publisherPersonId = p.person.id, month = month,
                                hours = hours.toIntOrNull() ?: 0, minutes = minutes.toIntOrNull() ?: 0,
                                participated = if (p.isPioneer) null else participated,
                                bibleStudies = studies.toIntOrNull() ?: 0, remarks = remarks, returnVisits = returnVisits.toIntOrNull() ?: 0,
                            ),
                            congregationId, actorRole, actorPersonId, scopeGroupId,
                        )
                        busy = false
                        if (message == null) onDismiss() else error = message
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberBox(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(4)) }, label = { Text(label) },
        singleLine = true, modifier = modifier,
    )
}

/** "Delete this record?" — shown before a publisher's record of the month is removed from the Table View. */
@Composable
fun RecordDeleteDialog(name: String, monthLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete record?") },
        text = { Text("$name's field service record for $monthLabel will be deleted.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
