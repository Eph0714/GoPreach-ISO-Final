package com.emfitsolutions.gopreach.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.hilt.navigation.compose.hiltViewModel
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublisherSchedulesScreen(
    congregationId: String?,
    currentPersonId: String,
    onBack: () -> Unit,
    viewModel: HouseholderAssignmentViewModel = hiltViewModel(),
) {
    val publishers by (congregationId?.let { viewModel.assignablePublishers(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = null)

    // Plain name filter — same congregation-scoped list [publishers] already
    // is, just narrowed client-side so a Publisher can jump straight to one
    // name in a larger congregation instead of scrolling the whole list.
    var query by rememberSaveable { mutableStateOf("") }
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
