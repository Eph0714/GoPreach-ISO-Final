package com.emfitsolutions.gopreach.ui.screens.attendance

import com.emfitsolutions.gopreach.ui.theme.glass
import com.emfitsolutions.gopreach.ui.components.ViewSegments
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.CoMonthStatus
import com.emfitsolutions.gopreach.data.model.CoReportStatus
import com.emfitsolutions.gopreach.data.model.coReportId
import com.emfitsolutions.gopreach.data.remote.ServerClock
import com.emfitsolutions.gopreach.data.repository.messageOrNull
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthSendResult
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthSendState
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.MonthValidation
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val MAX_MONTHS = 24
private const val MONTHS_BACK = 60

private fun monthStartAt(baseMillis: Long, monthsBack: Int): Long = Calendar.getInstance().apply {
    timeInMillis = baseMillis
    add(Calendar.MONTH, -monthsBack)
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun yearOf(month: Long): Int = Calendar.getInstance().apply { timeInMillis = month }.get(Calendar.YEAR)

private fun kindOf(s: CoReportStatus): CoKind = when (s) {
    CoReportStatus.RECEIVED -> CoKind.Success
    CoReportStatus.RETURNED, CoReportStatus.CANCELLED -> CoKind.Warning
    CoReportStatus.SUBMITTED -> CoKind.Primary
    CoReportStatus.NOT_SUBMITTED -> CoKind.Neutral
}

/**
 * Sending Meeting Attendance months to the Circuit Overseer: pick the months, see where each stands, send. Each month becomes its own
 * frozen, versioned snapshot (same process as the Field Service Report); the live records stay editable until the Circuit Overseer
 * marks a month as received, which locks that month's attendance until it is returned.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendAttendancePanel(
    congregationId: String,
    actorPersonId: String,
    viewModel: AttendanceSubmissionViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val shortFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }

    var clock by remember { mutableStateOf<ServerClock.Reading?>(null) }
    LaunchedEffect(Unit) { clock = viewModel.serverNow() }
    val baseNow = clock?.now ?: System.currentTimeMillis()
    val monthOptions = remember(monthStartAt(baseNow, 0)) { (0 until MONTHS_BACK).map { monthStartAt(baseNow, it) } }

    var multiMode by remember(congregationId) { mutableStateOf(false) }
    var singleMonth by remember(congregationId) { mutableStateOf<Long?>(null) }
    var picked by remember(congregationId) { mutableStateOf<Set<Long>>(emptySet()) }
    val months: List<Long> = remember(multiMode, singleMonth, picked) { if (multiMode) picked.toList().sorted() else listOfNotNull(singleMonth) }
    val tooMany = months.size > MAX_MONTHS
    val monthsReady = months.isNotEmpty() && !tooMany

    val statuses by remember(congregationId) { viewModel.statusesFor(congregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    fun monthStatus(month: Long): CoMonthStatus? = statuses.firstOrNull { it.id == coReportId(congregationId, month) }
    fun statusOf(month: Long): CoReportStatus = monthStatus(month)?.status ?: CoReportStatus.NOT_SUBMITTED
    val changedMonths by remember(congregationId, months) { viewModel.changesSince(congregationId, months) }.collectAsStateWithLifecycle(initialValue = emptySet())
    val overseerName by remember(congregationId) { viewModel.assignedOverseerName(congregationId) }.collectAsStateWithLifecycle(initialValue = null)

    val sendCandidates = months.filter { statusOf(it) != CoReportStatus.RECEIVED }
    val soleMonth = months.singleOrNull()
    val soleStatus = soleMonth?.let { statusOf(it) } ?: CoReportStatus.NOT_SUBMITTED
    val soleCurrent = soleMonth?.let { monthStatus(it) }

    var confirmSend by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    var tableView by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var validations by remember { mutableStateOf<List<MonthValidation>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MonthSendResult>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(confirmSend) {
        if (confirmSend) {
            validations = null
            validations = viewModel.validateMonths(congregationId, months)
        }
    }

    fun runSend(toSend: List<Long>) {
        val expected = validations.orEmpty().associate { it.month to it.version }
        busy = true
        scope.launch {
            val done = viewModel.submitMonths(congregationId, toSend, actorPersonId, expected)
            val previous = results.orEmpty().filter { old -> done.none { it.month == old.month } }
            results = (previous + done).sortedBy { it.month }
            busy = false
        }
    }

    val accent = Color(0xFFF2B705)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = accent, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text("Send Attendance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        overseerName ?: "No Circuit Overseer assigned", style = MaterialTheme.typography.bodySmall,
                        color = if (overseerName != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }

            ViewSegments(listOf("Table" to Icons.Rounded.TableChart, "List" to Icons.Rounded.ViewList), if (tableView) 0 else 1) { tableView = it == 0; if (it == 0) multiMode = true }
            if (tableView) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = { picked = monthOptions.filter { statusOf(it) != CoReportStatus.RECEIVED }.take(MAX_MONTHS).toSet(); message = null }) { Text("Select all") }
                        TextButton(onClick = { picked = emptySet() }) { Text("Clear") }
                        Spacer(Modifier.weight(1f))
                        Text(picked.size.toString() + " selected", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    com.emfitsolutions.gopreach.ui.components.SendStatusTable(
                        months = monthOptions, statusOf = { monthStatus(it) }, selected = picked, monthLabel = { monthFormat.format(Date(it)) },
                        onToggle = { m -> picked = if (m in picked) picked - m else picked + m; multiMode = true; message = null },
                    )
                }
            }
            if (!tableView) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !multiMode, onClick = { multiMode = false; message = null }, label = { Text("One month") })
                    FilterChip(selected = multiMode, onClick = { multiMode = true; message = null }, label = { Text("Several months") })
                }
                if (!multiMode) {
                    SimpleDropdown(
                        label = "Month", selectedLabel = singleMonth?.let { monthFormat.format(Date(it)) } ?: "Choose a month",
                        options = monthOptions.map { it.toString() to monthFormat.format(Date(it)) }, onSelected = { singleMonth = it.toLong(); message = null },
                    )
                } else {
                    val eligible = monthOptions.filter { statusOf(it) != CoReportStatus.RECEIVED }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { picked = eligible.take(MAX_MONTHS).toSet(); message = null }) { Text("Select all") }
                        TextButton(onClick = { picked = emptySet() }) { Text("Clear") }
                        Spacer(Modifier.weight(1f))
                        Text(picked.size.toString() + " selected", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    monthOptions.groupBy { yearOf(it) }.toSortedMap(compareByDescending { it }).forEach { (year, inYear) ->
                        Text(year.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            inYear.sorted().forEach { m ->
                                val st = statusOf(m)
                                FilterChip(
                                    selected = m in picked, enabled = st != CoReportStatus.RECEIVED,
                                    onClick = { picked = if (m in picked) picked - m else picked + m; message = null },
                                    label = { Text(monthFormat.format(Date(m)).substringBefore(' ').take(3)) },
                                    leadingIcon = if (st != CoReportStatus.NOT_SUBMITTED) ({ Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                                )
                            }
                        }
                    }
                }
                if (tooMany) Text("Up to $MAX_MONTHS months at a time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (months.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        months.forEach { m ->
                            val st = monthStatus(m)
                            val status = st?.status ?: CoReportStatus.NOT_SUBMITTED
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shortFormat.format(Date(m)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (m in changedMonths) CoStatusBadge("Changed", CoKind.Warning)
                                CoStatusBadge(status.workflowLabel + (st?.takeIf { it.version > 0 && status != CoReportStatus.NOT_SUBMITTED }?.let { " · v" + it.version } ?: ""), kindOf(status))
                            }
                        }
                        if (soleStatus == CoReportStatus.RETURNED && soleCurrent?.returnReason != null) Text("Returned: " + soleCurrent.returnReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (soleStatus == CoReportStatus.CANCELLED && soleCurrent?.cancelReason != null) Text("Cancelled: " + soleCurrent.cancelReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (soleStatus == CoReportStatus.RECEIVED) Text("Received - this month's attendance is locked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = monthsReady && !busy && sendCandidates.isNotEmpty(), onClick = { confirmSend = true },
                    shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            sendCandidates.size > 1 -> "Send " + sendCandidates.size + " months"
                            soleStatus == CoReportStatus.SUBMITTED && soleMonth != null -> "Send update"
                            soleStatus == CoReportStatus.RETURNED && soleMonth != null -> "Send corrected attendance"
                            else -> "Send"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (soleMonth != null && soleStatus.canUndo) TextButton(enabled = !busy, onClick = { confirmUndo = true }, modifier = Modifier.fillMaxWidth()) { Text("Undo sending") }
                if (soleMonth != null && soleCurrent?.canDeleteSubmission == true) TextButton(enabled = !busy, onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete submission", color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (confirmSend) {
        val checked = validations
        val sendable = checked.orEmpty().filter { it.sendable }
        AlertDialog(
            onDismissRequest = { if (!busy) confirmSend = false },
            title = { Text("Send to Circuit Overseer?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Assigned Circuit Overseer: " + (overseerName ?: "none assigned"), color = if (overseerName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    if (checked == null) Text("Checking each month...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else checked.forEach { v ->
                        Column {
                            Text(
                                monthFormat.format(Date(v.month)) + " - " + if (v.sendable) "will be sent" else "will not be sent (" + v.check.label.lowercase() + ")",
                                fontWeight = FontWeight.SemiBold, color = if (v.sendable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Text(v.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Your live records are not changed by sending; they stay editable until the Circuit Overseer receives a month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = checked != null && sendable.isNotEmpty() && !busy, onClick = { confirmSend = false; results = null; runSend(sendable.map { it.month }) }) { Text("Send") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }

    results?.let { done ->
        val failed = done.filter { it.state == MonthSendState.FAILED }
        val sent = done.count { it.state == MonthSendState.SENT }
        AlertDialog(
            onDismissRequest = { if (!busy) results = null },
            title = { Text(if (failed.isEmpty()) "Sent to Circuit Overseer" else if (sent == 0) "Nothing Was Sent" else "Partly Sent") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    done.forEach { r ->
                        val (mark, color) = when (r.state) {
                            MonthSendState.SENT -> "Sent - awaiting receipt" to MaterialTheme.colorScheme.primary
                            MonthSendState.ALREADY_SENT -> "Already sent" to MaterialTheme.colorScheme.onSurfaceVariant
                            MonthSendState.FAILED -> "Failed" to MaterialTheme.colorScheme.error
                        }
                        Column {
                            Text(monthFormat.format(Date(r.month)) + (r.version?.takeIf { it > 0 }?.let { " - version $it" } ?: ""), fontWeight = FontWeight.SemiBold)
                            Text(mark, color = color, style = MaterialTheme.typography.bodySmall)
                            r.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
                        }
                    }
                }
            },
            confirmButton = {
                if (failed.isNotEmpty()) TextButton(enabled = !busy, onClick = { runSend(failed.map { it.month }) }) { Text("Retry " + failed.size + " failed") }
                else TextButton(onClick = { results = null; picked = emptySet(); message = if (sent > 0) sent.toString() + " month" + (if (sent == 1) "" else "s") + " sent." else null }) { Text("Done") }
            },
            dismissButton = { if (failed.isNotEmpty()) TextButton(enabled = !busy, onClick = { results = null }) { Text("Close") } },
        )
    }

    if (confirmUndo && soleMonth != null) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo sending?") },
            text = { Text("The " + monthFormat.format(Date(soleMonth)) + " attendance goes back to Draft and the Circuit Overseer no longer sees it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    busy = true
                    scope.launch {
                        val problem = viewModel.undo(congregationId, soleMonth, actorPersonId).messageOrNull()
                        if (problem != null) failure = problem else message = "The sending was undone."
                        busy = false
                    }
                }) { Text("Undo sending") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && soleMonth != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this submission?") },
            text = {
                Text(
                    "The " + monthFormat.format(Date(soleMonth)) + " attendance snapshot" + (if (soleStatus == CoReportStatus.SUBMITTED) "s sent to the Circuit Overseer are" else "s are") +
                        " permanently deleted and the month goes back to Draft. Your recorded attendance is not changed, and the deletion is kept in the history. " +
                        "A report the Circuit Overseer has already received can never be deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        val problem = viewModel.deleteSubmission(congregationId, soleMonth, actorPersonId).messageOrNull()
                        if (problem != null) failure = problem else message = "The submission was deleted. The month is back to Draft."
                        busy = false
                    }
                }) { Text("Delete submission", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    failure?.let { text ->
        AlertDialog(onDismissRequest = { failure = null }, title = { Text("Couldn't complete that") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } })
    }
}
