package com.emfitsolutions.gopreach.ui.screens.fieldservicereport

import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoStatusBadge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
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
    CoReportStatus.RETURNED -> "Returned for editing"
    CoReportStatus.CANCELLED -> "Cancelled by the Circuit Overseer"
}

/** How many reporting months one send may include (each one is its own snapshot). */
private const val MAX_SNAPSHOT_MONTHS = 24

/** How far back the month picker reaches: five years of service months. */
private const val MONTHS_BACK = 60

private fun monthStartAt(baseMillis: Long, monthsBack: Int): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = baseMillis
    add(java.util.Calendar.MONTH, -monthsBack)
    set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun yearOf(month: Long): Int = java.util.Calendar.getInstance().apply { timeInMillis = month }.get(java.util.Calendar.YEAR)

/**
 * Sending the report to the Circuit Overseer: Admin / Coordinator Elder / Service Overseer / Secretary (and the Super-Admin).
 *
 * Select Reporting Months -> Validate -> Preview -> Send. The Admin picks one month, or ticks any months (consecutive or not, from
 * any year up to the current month by the SERVER's date). Every month becomes its own independent, frozen snapshot of the report as it
 * is at that moment, with its own version and status; sending never changes the congregation's live records. While a snapshot only
 * awaits receipt the live month stays editable ("Changes Since Submission" shows when it has changed); the Circuit Overseer receiving
 * a snapshot locks the month until it is returned for editing.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SendToCircuitPanel(
    congregation: Congregation,
    actorPersonId: String,
    viewModel: FieldServiceReportViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val monthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val shortFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }
    val stampFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }

    // The current month is the SERVER's, not just the device's.
    var clock by remember { mutableStateOf<com.emfitsolutions.gopreach.data.remote.ServerClock.Reading?>(null) }
    LaunchedEffect(Unit) { clock = viewModel.serverNow() }
    val baseNow = clock?.now ?: System.currentTimeMillis()
    val monthOptions = remember(monthStartAt(baseNow, 0)) { (0 until MONTHS_BACK).map { monthStartAt(baseNow, it) } } // newest first

    // The Report Month(s) are ALWAYS chosen here by the user - nothing is preselected and the device's date never decides them.
    var multiMode by remember(congregation.id) { mutableStateOf(false) }
    var singleMonth by remember(congregation.id) { mutableStateOf<Long?>(null) }
    var picked by remember(congregation.id) { mutableStateOf<Set<Long>>(emptySet()) }
    val months: List<Long> = remember(multiMode, singleMonth, picked) {
        if (multiMode) picked.toList().sorted() else listOfNotNull(singleMonth)
    }
    val tooMany = months.size > MAX_SNAPSHOT_MONTHS
    val monthsReady = months.isNotEmpty() && !tooMany
    val periodLabel = when {
        months.isEmpty() -> "the selected month"
        months.size == 1 -> monthFormat.format(Date(months.first()))
        else -> months.size.toString() + " months (" + shortFormat.format(Date(months.first())) + " - " + shortFormat.format(Date(months.last())) + ")"
    }

    val statuses by remember(congregation.id) { viewModel.submissionsFor(congregation.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    fun monthStatus(month: Long): CoMonthStatus? = statuses.firstOrNull { it.id == coReportId(congregation.id, month) }
    fun statusOf(month: Long): CoReportStatus = monthStatus(month)?.status ?: CoReportStatus.NOT_SUBMITTED
    val overviews by remember(congregation.id, months) { viewModel.publisherOverviews(congregation.id, months) }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val changedMonths by remember(congregation.id, months) { viewModel.changesSince(congregation.id, months) }.collectAsStateWithLifecycle(initialValue = emptySet())
    val overseerName by remember(congregation.id) { viewModel.assignedOverseerName(congregation.id) }.collectAsStateWithLifecycle(initialValue = null)
    val requireAll by viewModel.requireAllPublishersSubmitted.collectAsStateWithLifecycle(initialValue = false)

    val notSubmittedTotal = months.filter { statusOf(it) != CoReportStatus.RECEIVED }.sumOf { overviews[it]?.notSubmitted ?: 0 }
    val sendCandidates = months.filter { statusOf(it) != CoReportStatus.RECEIVED }
    val soleMonth = months.singleOrNull()
    val soleStatus = soleMonth?.let { statusOf(it) } ?: CoReportStatus.NOT_SUBMITTED
    val soleCurrent = soleMonth?.let { monthStatus(it) }

    var confirmSend by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }
    var validations by remember { mutableStateOf<List<MonthValidation>?>(null) }
    var plans by remember { mutableStateOf<List<MonthSnapshotPlan>?>(null) }
    var confirmations by remember { mutableStateOf<Map<Long, Map<String, PublisherCategory>>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MonthSendResult>?>(null) }
    var failure by remember { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Every selected month is validated on its own, then the plan of each month that can be sent (category per publisher in that month,
    // what needs confirming) is worked out - all before anything is sent.
    LaunchedEffect(confirmSend) {
        if (confirmSend) {
            validations = null
            plans = null
            confirmations = emptyMap()
            val checked = viewModel.validateMonths(congregation.id, months)
            validations = checked
            plans = checked.filter { it.sendable }.map { viewModel.planMonth(congregation.id, it.month) }
        }
    }

    fun runSend(toSend: List<Long>) {
        val expected = validations.orEmpty().associate { it.month to it.version }
        busy = true
        scope.launch {
            val done = viewModel.submitMonths(congregation.id, toSend, actorPersonId, confirmations, expected)
            // Keep earlier months' outcomes when only the failed ones are retried.
            val previous = results.orEmpty().filter { old -> done.none { it.month == old.month } }
            results = (previous + done).sortedBy { it.month }
            busy = false
        }
    }

    fun runUndo(month: Long) {
        busy = true
        scope.launch {
            val problem = viewModel.undoSubmission(congregation.id, month, actorPersonId).messageOrNull()
            if (problem != null) failure = "Couldn't complete that" to problem else message = "The sending was undone."
            busy = false
        }
    }

    val accent = Color(0xFFF2B705) // GoPreach medium yellow
    fun kindOf(s: CoReportStatus): CoKind = when (s) {
        CoReportStatus.RECEIVED -> CoKind.Success
        CoReportStatus.RETURNED, CoReportStatus.CANCELLED -> CoKind.Warning
        CoReportStatus.SUBMITTED -> CoKind.Primary
        CoReportStatus.NOT_SUBMITTED -> CoKind.Neutral
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header: who it goes to.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = accent, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Send to Circuit Overseer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        overseerName ?: "No Circuit Overseer assigned",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overseerName != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Month(s)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.FilterChip(selected = !multiMode, onClick = { multiMode = false; message = null }, label = { Text("One month") })
                    androidx.compose.material3.FilterChip(selected = multiMode, onClick = { multiMode = true; message = null }, label = { Text("Several months") })
                }
                if (!multiMode) {
                    com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown(
                        label = "Month",
                        selectedLabel = singleMonth?.let { monthFormat.format(Date(it)) } ?: "Choose a month",
                        options = monthOptions.map { it.toString() to monthFormat.format(Date(it)) },
                        onSelected = { singleMonth = it.toLong(); message = null },
                    )
                } else {
                    val eligible = monthOptions.filter { statusOf(it) != CoReportStatus.RECEIVED }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = { picked = eligible.take(MAX_SNAPSHOT_MONTHS).toSet(); message = null }) { Text("Select all") }
                        TextButton(onClick = { picked = emptySet() }) { Text("Clear") }
                        Spacer(Modifier.weight(1f))
                        Text(picked.size.toString() + " selected", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    monthOptions.groupBy { yearOf(it) }.toSortedMap(compareByDescending { it }).forEach { (year, inYear) ->
                        Text(year.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            inYear.sorted().forEach { m ->
                                val st = statusOf(m)
                                androidx.compose.material3.FilterChip(
                                    selected = m in picked, enabled = st != CoReportStatus.RECEIVED,
                                    onClick = { picked = if (m in picked) picked - m else picked + m; message = null },
                                    label = { Text(monthFormat.format(Date(m)).substringBefore(' ').take(3)) },
                                    leadingIcon = if (st != CoReportStatus.NOT_SUBMITTED) ({ Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                                )
                            }
                        }
                    }
                }
                if (clock?.fromServer == false) {
                    Text("Using this device's date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tooMany) Text("Up to $MAX_SNAPSHOT_MONTHS months at a time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // Status of what is selected
            if (months.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        months.forEach { m ->
                            val st = monthStatus(m)
                            val status = st?.status ?: CoReportStatus.NOT_SUBMITTED
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shortFormat.format(Date(m)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (m in changedMonths) CoStatusBadge("Changed", CoKind.Warning)
                                CoStatusBadge(status.workflowLabel + (st?.takeIf { it.version > 0 && status != CoReportStatus.NOT_SUBMITTED }?.let { " · v" + it.version } ?: ""), kindOf(status))
                            }
                        }
                        if (soleStatus == CoReportStatus.RETURNED && soleCurrent?.returnReason != null) {
                            Text("Returned: " + soleCurrent.returnReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (soleStatus == CoReportStatus.CANCELLED && soleCurrent?.cancelReason != null) {
                            Text("Cancelled: " + soleCurrent.cancelReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (!soleCurrent?.coRemarks.isNullOrBlank()) {
                            Text("CO: " + soleCurrent?.coRemarks, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (notSubmittedTotal > 0) {
                            Text(notSubmittedTotal.toString() + " publisher" + (if (notSubmittedTotal == 1) "" else "s") + " not reported yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Switch(
                    checked = requireAll,
                    onCheckedChange = { scope.launch { viewModel.setRequireAllPublishersSubmitted(it, actorPersonId) } },
                )
                Text("Wait for every publisher's report", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }

            // Send
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = monthsReady && !busy && sendCandidates.isNotEmpty(),
                    onClick = { confirmSend = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) {
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            sendCandidates.size > 1 -> "Send " + sendCandidates.size + " months"
                            soleStatus == CoReportStatus.SUBMITTED && soleMonth != null -> "Send update"
                            soleStatus == CoReportStatus.RETURNED && soleMonth != null -> "Send corrected report"
                            else -> "Send"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (soleMonth != null && soleStatus.canUndo) {
                    TextButton(enabled = !busy, onClick = { confirmUndo = true }, modifier = Modifier.fillMaxWidth()) { Text("Undo sending") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    // ---- preview: every month validated on its own, what will and will not be sent, and any category that must be confirmed first ----
    if (confirmSend) {
        val checked = validations
        val ready = plans
        val sendable = checked.orEmpty().filter { it.sendable }
        val needs = ready?.flatMap { it.needs }.orEmpty()
        val confirmedCount = needs.count { confirmations[it.month]?.containsKey(it.personId) == true }
        val allConfirmed = ready != null && confirmedCount == needs.size
        AlertDialog(
            onDismissRequest = { if (!busy) confirmSend = false },
            title = { Text("Send to Circuit Overseer?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Congregation: " + congregation.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Assigned Circuit Overseer: " + (overseerName ?: "none assigned"),
                        color = if (overseerName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text("Selected reports: " + months.size)
                    if (checked == null) {
                        Text("Checking each month...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        checked.forEach { v ->
                            Column {
                                Text(
                                    monthFormat.format(Date(v.month)) + " - " + if (v.sendable) "will be sent" else "will not be sent (" + v.check.label.lowercase() + ")",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (v.sendable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Text(v.message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Reports to send: " + sendable.size + (if (checked.size > sendable.size) " - " + (checked.size - sendable.size) + " skipped" else ""), fontWeight = FontWeight.SemiBold)
                    }
                    if (ready == null && checked != null && sendable.isNotEmpty()) {
                        Text("Preparing the snapshots...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (ready != null) {
                        Text("Publishers included: " + (ready.maxOfOrNull { it.publisherCount } ?: 0))
                        val changes = ready.flatMap { plan -> plan.changes.map { plan.month to it } }
                        Text("Category changes affecting the report: " + changes.size)
                        changes.take(10).forEach { (m, c) ->
                            Text("  " + shortFormat.format(Date(m)) + ": " + c.name + " " + c.from + " -> " + c.to, style = MaterialTheme.typography.bodySmall)
                        }
                        if (changes.size > 10) Text("  and " + (changes.size - 10) + " more", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Categories needing your confirmation: " + needs.size,
                            fontWeight = FontWeight.SemiBold,
                            color = if (needs.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        if (needs.isNotEmpty()) {
                            Text(
                                "These publishers have no service category on record for the month. Choose the category each one had IN that month - it is never guessed from today's profile.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val suggestible = needs.filter { it.current != null && confirmations[it.month]?.containsKey(it.personId) != true }
                            if (suggestible.isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    val next = confirmations.toMutableMap()
                                    suggestible.forEach { n -> next[n.month] = (next[n.month] ?: emptyMap()) + (n.personId to n.current!!) }
                                    confirmations = next
                                }) { Text("Use today's category for all (" + suggestible.size + ")") }
                            }
                            needs.groupBy { it.month }.forEach { (m, list) ->
                                Text(monthFormat.format(Date(m)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                list.forEach { need ->
                                    val chosen = confirmations[m]?.get(need.personId)
                                    com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown(
                                        label = need.name,
                                        selectedLabel = chosen?.displayName ?: "Choose the category",
                                        options = PublisherCategory.entries.filter { it != PublisherCategory.REMOVED_PUBLISHER }.map { it.name to it.displayName },
                                        onSelected = { name ->
                                            confirmations = confirmations + (m to ((confirmations[m] ?: emptyMap()) + (need.personId to PublisherCategory.valueOf(name))))
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Each month is saved as its own snapshot with its own version. Your live records are not changed by sending; they stay editable until the Circuit Overseer receives that month.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = checked != null && sendable.isNotEmpty() && allConfirmed && !busy, onClick = {
                    confirmSend = false
                    results = null
                    runSend(sendable.map { it.month })
                }) { Text("Send") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }

    // ---- the result of every month on its own; failed months can be retried without re-sending the saved ones ----
    results?.let { done ->
        val failed = done.filter { it.state == MonthSendState.FAILED }
        val sent = done.count { it.state == MonthSendState.SENT }
        AlertDialog(
            onDismissRequest = { if (!busy) results = null },
            title = { Text(if (failed.isEmpty()) "Sent to Circuit Overseer" else if (sent == 0) "Nothing Was Sent" else "Partly Sent") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    done.forEach { r ->
                        val (mark, color) = when (r.state) {
                            MonthSendState.SENT -> "Sent to CO - Awaiting Receipt" to MaterialTheme.colorScheme.primary
                            MonthSendState.ALREADY_SENT -> "Already sent" to MaterialTheme.colorScheme.onSurfaceVariant
                            MonthSendState.FAILED -> "Failed" to MaterialTheme.colorScheme.error
                        }
                        Column {
                            Text(monthFormat.format(Date(r.month)) + (r.version?.takeIf { it > 0 }?.let { " - version $it" } ?: ""), fontWeight = FontWeight.SemiBold)
                            Text(mark + (r.at?.takeIf { r.state == MonthSendState.SENT && it > 0 }?.let { " - " + stampFormat.format(Date(it)) } ?: ""), color = color, style = MaterialTheme.typography.bodySmall)
                            r.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
                        }
                    }
                    if (failed.isNotEmpty()) {
                        Text(
                            "Months that were sent are saved and will not be sent again. Retry only re-sends the failed months.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busy) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                if (failed.isNotEmpty()) TextButton(enabled = !busy, onClick = { runSend(failed.map { it.month }) }) { Text("Retry " + failed.size + " failed") }
                else TextButton(onClick = { results = null; picked = emptySet(); message = if (sent > 0) sent.toString() + " report" + (if (sent == 1) "" else "s") + " sent to the Circuit Overseer." else null }) { Text("Done") }
            },
            dismissButton = { if (failed.isNotEmpty()) TextButton(enabled = !busy, onClick = { results = null }) { Text("Close") } },
        )
    }

    if (confirmUndo && soleMonth != null) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo Report Sending?") },
            text = {
                Text(
                    "The " + monthFormat.format(Date(soleMonth)) + " report goes back to Draft. The Circuit Overseer will no longer see it. " +
                        "Any CO remarks already entered are kept in the history.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUndo = false; runUndo(soleMonth) }) { Text("Undo Sending") }
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
                "Submitted ${stamp.format(Date(status.submittedAt))} by ${status.submittedByName}",
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
                // The Circuit Overseer account's one button system: the main action full width, the other two share the next row.
                if (status.status == CoReportStatus.SUBMITTED) {
                    CoButton(
                        "Mark as Received", { run { viewModel.receiveMonth(status.id, remarks, actorPersonId) } },
                        kind = CoKind.Success, icon = Icons.Rounded.CheckCircle, enabled = !busy, fillWidth = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CoButton(
                        "Save Remarks", { run { viewModel.saveCoRemarks(status.id, remarks, actorPersonId) } },
                        modifier = Modifier.weight(1f), kind = CoKind.Secondary, icon = Icons.Rounded.Save,
                        enabled = !busy && remarks != status.coRemarks.orEmpty(),
                    )
                    if (status.status == CoReportStatus.SUBMITTED || status.status == CoReportStatus.RECEIVED) {
                        CoButton(
                            "Return", { returning = true }, modifier = Modifier.weight(1f),
                            kind = CoKind.Warning, icon = Icons.AutoMirrored.Rounded.Undo, enabled = !busy,
                        )
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
            title = { Text("Return to Congregation") },
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

/** "1  Select Reporting Month" - a numbered step label with an icon, in the GoPreach yellow accent. */
@Composable
private fun PanelStepHeader(number: Int, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = accent, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
