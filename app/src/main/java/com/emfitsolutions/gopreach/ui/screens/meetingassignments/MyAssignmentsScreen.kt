package com.emfitsolutions.gopreach.ui.screens.meetingassignments

import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Add a Button under Meeting [and Cart] Assignment[:] 'My Assignments'...
 * the publisher can see all the assignments under his name" — a single,
 * read-only cross-cut of every Midweek Meeting Schedule/Public Talk and
 * Watchtower Study/Cart Assignment record in the signed-in Publisher's own
 * congregation that names them (see [MeetingAssignmentsViewModel
 * .myAssignmentsFor]'s doc comment for how the free-text assignee fields are
 * matched). Publisher-only, own-congregation-only — there is no Add/Edit/
 * Delete here, this is purely "what am I assigned to."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAssignmentsScreen(
    currentPersonName: String,
    congregationId: String?,
    onBack: () -> Unit,
    viewModel: MeetingAssignmentsViewModel = koinViewModel(),
) {
    val rowsFlow = remember(congregationId, currentPersonName) { viewModel.myAssignmentsFor(congregationId, currentPersonName) }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Assignments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (congregationId == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No congregation to show yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (rows.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                RecordFound(0)
                Text(
                    "You have no Midweek, Public Talk, or Cart assignments yet.",
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
                item { RecordFound(rows.size) }
                items(rows, key = { it.rowKey() }) { row -> MyAssignmentCard(row, dateFormat) }
            }
        }
    }
}

/** Stable per-row key for [LazyColumn] — each [MyAssignmentRow] variant's
 * own natural id (a Midweek row has no Firestore doc id of its own, only its
 * parent schedule's, so its section/particular is folded in too). */
private fun MyAssignmentRow.rowKey(): String = when (this) {
    is MyAssignmentRow.Midweek -> "midweek-$weekStart-$sectionLabel-$particular"
    is MyAssignmentRow.PublicTalk -> "publictalk-${row.id}"
    is MyAssignmentRow.Cart -> "cart-${row.id}"
}

@Composable
private fun MyAssignmentCard(row: MyAssignmentRow, dateFormat: SimpleDateFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (row) {
                is MyAssignmentRow.Midweek -> {
                    Text("Midweek Meeting Schedule", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(formatWeekRange(row.weekStart), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(row.sectionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.particular + formatDurationSuffix(row.durationMinutes), style = MaterialTheme.typography.bodyMedium)
                    Text("Assigned to: ${row.assignedTo}", style = MaterialTheme.typography.bodySmall)
                }
                is MyAssignmentRow.PublicTalk -> {
                    Text("Public Talk and Watchtower Study", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(dateFormat.format(Date(row.row.date)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Your role: ${row.matchedRoles.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                    if (row.row.theme.isNotBlank()) Text("Theme: ${row.row.theme}", style = MaterialTheme.typography.bodySmall)
                }
                is MyAssignmentRow.Cart -> {
                    Text("Cart Assignment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(dateFormat.format(Date(row.row.date)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Location: ${row.row.location}", style = MaterialTheme.typography.bodyMedium)
                    Text("Publishers: ${row.row.publishers}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
