package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.FUTURE_MONTH_MESSAGE
import com.emfitsolutions.gopreach.data.model.FUTURE_MONTH_TITLE
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.model.isFutureServiceMonth
import com.emfitsolutions.gopreach.data.repository.CircuitResult
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val stamp get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

private fun statusHeadline(status: CoReportStatus?): String = when (status) {
    null, CoReportStatus.NOT_SUBMITTED -> "Not Submitted"
    CoReportStatus.SUBMITTED -> "Submitted to Circuit Overseer"
    CoReportStatus.RECEIVED -> "Received by Circuit Overseer"
    CoReportStatus.RETURNED -> "Returned for correction"
}

/**
 * Submitting the month shown to the Circuit Overseer: Admin / Coordinator Elder / Service Overseer / Secretary (and the
 * Super-Admin). It moves the status of the congregation's own report — no copy is made. Submitted and Received lock the
 * month's records; Submitted can be undone until the overseer receives it; only the overseer can return a Received month.
 */
@Composable
fun SendToCircuitPanel(
    congregation: Congregation,
    month: Long,
    monthLabel: String,
    actorPersonId: String,
    viewModel: FieldServiceReportViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val submission by remember(congregation.id, month) { viewModel.submissionFor(congregation.id, month) }
        .collectAsStateWithLifecycle(initialValue = null)
    val overview by remember(congregation.id, month) { viewModel.publisherOverview(congregation.id, month) }.collectAsStateWithLifecycle(initialValue = PublisherOverview(0, 0))
    val requireAll by viewModel.requireAllPublishersSubmitted.collectAsStateWithLifecycle(initialValue = false)
    val blockedByPublishers = requireAll && overview.notSubmitted > 0
    var confirmSend by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val current = submission
    val status = current?.status ?: CoReportStatus.NOT_SUBMITTED
    val future = isFutureServiceMonth(month, System.currentTimeMillis())

    fun run(done: String, block: suspend () -> CircuitResult) {
        busy = true
        scope.launch {
            val result = block()
            val problem = result.messageOrNull()
            if (problem != null) failure = (if (problem == FUTURE_MONTH_MESSAGE) FUTURE_MONTH_TITLE else "Couldn't complete that") to problem
            else message = done
            busy = false
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Circuit Overseer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("$monthLabel: ${statusHeadline(status)}", style = MaterialTheme.typography.bodySmall)
                    when (status) {
                        CoReportStatus.SUBMITTED -> Text(
                            "Publishers can no longer add, edit or delete records for this month. You can undo the sending until the Circuit Overseer receives it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CoReportStatus.RECEIVED -> Text(
                            "The Circuit Overseer has received this report. It can no longer be undone here — only the Circuit Overseer can return it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Unit
                    }
                    // Level 1 (publishers → congregation) before level 2 (congregation → Circuit Overseer).
                    Text(
                        "Publishers — Total: ${overview.total} · Submitted: ${overview.submitted} · Not Submitted: ${overview.notSubmitted}",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    )
                    if (overview.notSubmitted > 0 && status.canSend) {
                        Text(
                            "${overview.notSubmitted} publisher${if (overview.notSubmitted == 1) " has" else "s have"} not submitted their $monthLabel Field Service Report. " +
                                "Review the publisher submission status before submitting the consolidated congregation report to the CO." +
                                if (blockedByPublishers) " Sending is blocked until everyone has submitted." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!current?.coRemarks.isNullOrBlank()) {
                        Text("CO Remarks: ${current?.coRemarks}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (status.canSend) {
                        Button(enabled = !busy && !future && !blockedByPublishers, onClick = { confirmSend = true }) {
                            Text(if (status == CoReportStatus.RETURNED) "Send Again" else "Send Field Service Report")
                        }
                    }
                    if (status.canUndo) {
                        OutlinedButton(enabled = !busy, onClick = { confirmUndo = true }) { Text("Undo Report Sending") }
                    }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Switch(
                    checked = requireAll,
                    onCheckedChange = { scope.launch { viewModel.setRequireAllPublishersSubmitted(it, actorPersonId) } },
                )
                Text("Require every publisher to submit before sending to the CO", style = MaterialTheme.typography.bodySmall)
            }
            if (future) Text(FUTURE_MONTH_MESSAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send Field Service Report?") },
            text = {
                Text(
                    (if (overview.notSubmitted > 0) "${overview.notSubmitted} publisher(s) have not submitted yet. " else "") +
                        "The $monthLabel Field Service Report will be submitted to the Circuit Overseer. " +
                        "Publishers will no longer be able to add, edit or delete their records for this month until it is returned or the sending is undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    run("$monthLabel report submitted to the Circuit Overseer.") { viewModel.submitMonth(congregation.id, month, actorPersonId) }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }
    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo Report Sending?") },
            text = {
                Text(
                    "The $monthLabel report goes back to Not Submitted. The Circuit Overseer will no longer see it and publishers can edit " +
                        "their records for this month again. Any CO remarks already entered are kept in the history.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    run("The sending was undone.") { viewModel.undoSubmission(congregation.id, month, actorPersonId) }
                }) { Text("Undo Sending") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } },
        )
    }
    failure?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

/**
 * The Circuit Overseer's panel above the actual Field Service Report of a submitted month: its status, who sent / received it,
 * the CO Remarks for THIS month (editable), Receive / Return for Correction, and the history. Only the Circuit Overseer acts
 * ([canAct]); the Super-Admin only reads.
 */
@Composable
fun CoReviewPanel(
    status: CoMonthStatus,
    congregationName: String,
    monthLabel: String,
    actorPersonId: String,
    canAct: Boolean,
    viewModel: FieldServiceReportViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val history by remember(status.id) { viewModel.historyFor(status.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var remarks by remember(status.id, status.coRemarksAt) { mutableStateOf(status.coRemarks.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var returning by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    fun run(block: suspend () -> CircuitResult) {
        busy = true
        scope.launch {
            message = block().messageOrNull()
            busy = false
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$congregationName — $monthLabel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Status: ${status.status.label}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Submitted ${stamp.format(Date(status.submittedAt))} by ${status.submittedByName}" + if (status.version > 1) " (sent ${status.version} times)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (status.status == CoReportStatus.RECEIVED && status.receivedAt != null) {
                Text("Received ${stamp.format(Date(status.receivedAt!!))} by ${status.receivedByName.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            if (status.status == CoReportStatus.RETURNED && status.returnedAt != null) {
                Text("Returned ${stamp.format(Date(status.returnedAt!!))} by ${status.returnedByName.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            if (canAct) {
                OutlinedTextField(
                    value = remarks, onValueChange = { remarks = it }, label = { Text("CO Remarks") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.status == CoReportStatus.SUBMITTED) {
                        Button(enabled = !busy, onClick = { run { viewModel.receiveMonth(status.id, remarks, actorPersonId) } }) { Text("Mark Received") }
                    }
                    OutlinedButton(enabled = !busy && remarks != status.coRemarks.orEmpty(), onClick = { run { viewModel.saveCoRemarks(status.id, remarks, actorPersonId) } }) {
                        Text("Save Remarks")
                    }
                    if (status.status == CoReportStatus.SUBMITTED || status.status == CoReportStatus.RECEIVED) {
                        OutlinedButton(enabled = !busy, onClick = { returning = true }) { Text("Return") }
                    }
                }
            } else if (!status.coRemarks.isNullOrBlank()) {
                Text("CO Remarks: ${status.coRemarks}", style = MaterialTheme.typography.bodyMedium)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide history" else "Show history (${history.size})") }
            if (showHistory) {
                history.forEach { e ->
                    Text(
                        stamp.format(Date(e.at)) + " — " + e.action + " (" + e.userName + (if (e.userRole.isNotBlank()) ", " + e.userRole.replace('_', ' ').lowercase() else "") + ")" +
                            (e.fromStatus?.let { " · $it → ${e.toStatus}" } ?: "") +
                            (e.remarks?.takeIf { it.isNotBlank() }?.let { " · Remark: $it" } ?: "") +
                            (e.previousRemarks?.takeIf { it.isNotBlank() }?.let { " · Previous remark: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (returning) {
        AlertDialog(
            onDismissRequest = { returning = false },
            title = { Text("Return for correction") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The congregation will be able to correct this month's records and send it again. Explain what needs to be corrected:")
                    OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("CO Remarks (required)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = remarks.isNotBlank(), onClick = { returning = false; run { viewModel.returnMonth(status.id, remarks, actorPersonId) } }) { Text("Return") }
            },
            dismissButton = { TextButton(onClick = { returning = false }) { Text("Cancel") } },
        )
    }
}
