package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.CircuitReportExporter
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import org.koin.compose.viewmodel.koinViewModel

private val NUMERIC = setOf("Total Publisher", "Auxiliary Pioneer", "Regular Publishers", "Unbaptized Publisher", "Elders", "Ministerial Servants")
private fun widthOf(title: String) = when (title) {
    "Cong #" -> 64; "Cong Name" -> 190; "City" -> 120; "Coordinator Name" -> 170; "Elders" -> 70; "Ministerial Servants" -> 120; else -> 100
}

/**
 * Circuit Overseer → Circuit Report: one consolidated, summary-level table of every congregation in ONE circuit
 * (the signed-in overseer's — or, for the Super-Admin, the circuit chosen in Circuit Overseer Management), with a TOTAL
 * row, Print / PDF and Excel. It is built from the same live records as everything else, and states how fresh they are.
 * Selecting a row opens that congregation's overview — the way into its detailed records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitReportScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenCongregation: (String) -> Unit,
    viewModel: CircuitPeopleViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val summaries by remember(currentPersonId) { viewModel.summaries(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val info by remember(currentPersonId) { viewModel.circuitInfo(currentPersonId) }.collectAsStateWithLifecycle(initialValue = "—" to "—")
    val lastSync by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val online by viewModel.isOnline.collectAsStateWithLifecycle(initialValue = true)
    val rows = circuitReportRows(summaries)
    val total = circuitReportTotal(rows)
    val header = CircuitReportHeader(circuit = info.first, overseer = info.second, totalCongregations = rows.size)
    val syncText = if (lastSync > 0) SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(lastSync)) else null
    val syncNote = when {
        !online -> "Offline — Data last synchronized ${syncText ?: "never on this device"}"
        syncText != null -> "Last synchronized $syncText"
        else -> null
    }
    val allCircuits = currentPersonId == CIRCUIT_SCOPE_ALL

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Circuit Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (allCircuits) {
            Column(modifier = Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select a Circuit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "A Circuit Report covers one circuit. Choose a circuit (and its Circuit Overseer) in Circuit Overseer Management first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }
        val hScroll = rememberScrollState()
        val border = MaterialTheme.colorScheme.outlineVariant
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("CIRCUIT REPORT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Circuit: ${header.circuit}", style = MaterialTheme.typography.bodyMedium)
                            Text("Circuit Overseer: ${header.overseer}", style = MaterialTheme.typography.bodyMedium)
                            Text("Total Congregations: ${header.totalCongregations}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Generated: " + SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    syncNote?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall,
                            color = if (!online) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (!online) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = rows.isNotEmpty(), onClick = { CircuitReportExporter.print(context, header, rows, total, syncNote) }) { Text("Print Circuit Report / PDF") }
                        OutlinedButton(enabled = rows.isNotEmpty(), onClick = { CircuitReportExporter.shareExcel(context, header, rows, total, syncNote) }) { Text("Excel") }
                    }
                    if (rows.isEmpty()) Text("No congregations are assigned to this circuit yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (rows.isNotEmpty()) {
                // The heading row stays pinned while the congregations scroll underneath; every row shares one sideways scroll.
                stickyHeader(key = "head") {
                    Row(modifier = Modifier.horizontalScroll(hScroll).height(IntrinsicSize.Min).background(MaterialTheme.colorScheme.secondaryContainer)) {
                        CIRCUIT_REPORT_COLUMNS.forEach { (title, _) ->
                            Text(
                                title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(widthOf(title).dp).fillMaxHeight().border(0.5.dp, border).padding(6.dp),
                            )
                        }
                    }
                }
                itemsIndexed(rows, key = { _, r -> "row-" + r.congregationId }) { i, r ->
                    Row(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .height(IntrinsicSize.Min)
                            .background(if (i % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .clickable { onOpenCongregation(r.congregationId) },
                    ) {
                        CIRCUIT_REPORT_COLUMNS.forEach { (title, value) ->
                            Text(
                                value(r), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(widthOf(title).dp).fillMaxHeight().border(0.5.dp, border).padding(6.dp),
                                textAlign = if (title in NUMERIC) androidx.compose.ui.text.style.TextAlign.End else null,
                            )
                        }
                    }
                }
                item(key = "total") {
                    Row(modifier = Modifier.horizontalScroll(hScroll).height(IntrinsicSize.Min).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))) {
                        CIRCUIT_REPORT_COLUMNS.forEach { (title, value) ->
                            Text(
                                value(total), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(widthOf(title).dp).fillMaxHeight().border(0.5.dp, border).padding(6.dp),
                                textAlign = if (title in NUMERIC) androidx.compose.ui.text.style.TextAlign.End else null,
                            )
                        }
                    }
                }
                item(key = "end-summary") {
                    com.emfitsolutions.gopreach.ui.components.EndSummary(
                        listOf("Total Congregations" to header.totalCongregations.toString()) +
                            CIRCUIT_REPORT_COLUMNS.filter { it.first in NUMERIC }.map { (title, value) -> title to value(total) },
                        Modifier.padding(top = 4.dp),
                    )
                }
                item(key = "hint") { Text("Tap a congregation to open its overview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
