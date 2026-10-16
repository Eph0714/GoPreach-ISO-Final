package com.emfitsolutions.gopreach.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.emfitsolutions.gopreach.data.model.PreachingDay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Search
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.ui.screens.householderassignment.HouseholderAssignmentViewModel
import com.emfitsolutions.gopreach.ui.screens.householderassignment.availabilitySummary

/**
 * "Add Module: Preaching Availability" — spec's own "This will be visible in
 * other publisher account," scoped to "only within their congregation" (see
 * [Person.preachingAvailableDays]'s own doc comment). Reached from Account
 * Settings' own "View Other Publishers' Schedules" link (Publisher-only),
 * so any Publisher can see who else in their congregation is available —
 * not just a Service Overseer/Admin picking who to send a House Holder
 * Assignment to, which is [HouseholderAssignmentViewModel.assignablePublishers]'s
 * only other caller. Reuses that exact same method rather than duplicating
 * the "active Publisher role in this congregation" query.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PublisherSchedulesScreen(
    congregationId: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    /** Print / save as PDF and share as Excel. Each gets the table as rows of text (first row = headings). Null hides the button. */
    onPrint: ((List<List<String>>) -> Unit)? = null,
    onExcel: ((List<List<String>>) -> Unit)? = null,
    viewModel: HouseholderAssignmentViewModel = koinViewModel(),
) {
    val publishers by (congregationId?.let { viewModel.assignablePublishers(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = null)

    // Plain name filter — same congregation-scoped list [publishers] already
    // is, just narrowed client-side so a Publisher can jump straight to one
    // name in a larger congregation instead of scrolling the whole list.
    var query by rememberSaveable { mutableStateOf("") }
    var tableView by rememberSaveable { mutableStateOf(false) }
    val filteredPublishers = remember(publishers, query) {
        val q = query.trim()
        if (q.isBlank()) publishers else publishers?.filter { it.fullName.contains(q, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Publisher Schedules") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !tableView, onClick = { tableView = false }, label = { Text("List view") })
                FilterChip(selected = tableView, onClick = { tableView = true }, label = { Text("Table view") })
            }
            if (onPrint != null || onExcel != null) {
                val rows = filteredPublishers.orEmpty()
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onPrint?.let { OutlinedButton(enabled = rows.isNotEmpty(), onClick = { it(scheduleTable(rows)) }) { Icon(Icons.Rounded.Print, contentDescription = null); Text("  Print / PDF") } }
                    onExcel?.let { OutlinedButton(enabled = rows.isNotEmpty(), onClick = { it(scheduleTable(rows)) }) { Icon(Icons.Rounded.TableChart, contentDescription = null); Text("  Excel") } }
                }
            }
            when {
                congregationId == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("No congregation assigned.", style = MaterialTheme.typography.bodyMedium) }

                filteredPublishers == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                filteredPublishers.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (query.isBlank()) "No publishers found in your congregation." else "No publishers match \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                tableView -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    stickyHeader { PublisherScheduleTableHeader() }
                    itemsIndexed(filteredPublishers, key = { _, p -> p.id }) { i, publisher -> PublisherScheduleTableRow(publisher, isSelf = publisher.id == currentPersonId, striped = i % 2 == 1) }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredPublishers, key = { it.id }) { publisher -> PublisherScheduleRow(publisher, isSelf = publisher.id == currentPersonId) }
                }
            }
        }
    }
}

@Composable
private fun PublisherScheduleRow(publisher: Person, isSelf: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                publisher.fullName + if (isSelf) " (You)" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                availabilitySummary(publisher),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val DayColumnWidth = 40.dp

/** Rows of the exported / printed table: a heading row, then one row per publisher with a check under each available day. */
internal fun scheduleTable(publishers: List<Person>): List<List<String>> {
    val head = listOf("Publisher") + PreachingDay.entries.map { it.shortLabel } + listOf("Remarks")
    return listOf(head) + publishers.map { p ->
        val on = p.preachingAvailableDays.mapNotNull { runCatching { PreachingDay.valueOf(it) }.getOrNull() }.toSet()
        listOf(p.fullName) + PreachingDay.entries.map { if (it in on) "\u2713" else "" } + listOf(p.preachingAvailabilityRemarks.orEmpty())
    }
}

@Composable
private fun TableCell(modifier: Modifier, background: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxHeight().background(background).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** Stays pinned at the top while the rows scroll, so the day columns are always labelled. */
@Composable
private fun PublisherScheduleTableHeader() {
    val bg = MaterialTheme.colorScheme.secondaryContainer
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        TableCell(Modifier.weight(1f), bg) { Text("Publisher", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
        PreachingDay.entries.forEach {
            TableCell(Modifier.width(DayColumnWidth), bg) {
                Text(it.shortLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One publisher per row, one column per day: a check where they are available. Remarks stay under the name. */
@Composable
private fun PublisherScheduleTableRow(publisher: Person, isSelf: Boolean, striped: Boolean) {
    val available = publisher.preachingAvailableDays.mapNotNull { runCatching { PreachingDay.valueOf(it) }.getOrNull() }.toSet()
    val bg = if (striped) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        TableCell(Modifier.weight(1f), bg) {
            Column {
                Text(publisher.fullName + if (isSelf) " (You)" else "", style = MaterialTheme.typography.bodyMedium)
                publisher.preachingAvailabilityRemarks?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (available.isEmpty() && publisher.preachingAvailabilityRemarks.isNullOrBlank()) {
                    Text("No availability set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PreachingDay.entries.forEach { day ->
            val on = day in available
            TableCell(Modifier.width(DayColumnWidth), bg) {
                Text(
                    if (on) "\u2713" else "",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
