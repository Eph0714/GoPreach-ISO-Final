package com.emfitsolutions.gopreach.ui.screens.ministry

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.HistoryEntry
import com.emfitsolutions.gopreach.data.repository.MinistryHistoryRepository
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MinistryHistoryState(val entries: List<HistoryEntry> = emptyList(), val loading: Boolean = false, val hasMore: Boolean = false, val failed: Boolean = false)

class MinistryHistoryViewModel(private val repository: MinistryHistoryRepository) : ViewModel() {
    private val _state = MutableStateFlow(MinistryHistoryState())
    val state: StateFlow<MinistryHistoryState> = _state.asStateFlow()
    private var job: Job? = null

    /** Loads the first page again ([reset]) or the next one. */
    fun load(scope: Int, type: String?, query: String, reset: Boolean) {
        job?.cancel()
        val before = if (reset) null else _state.value.entries.lastOrNull()?.at
        if (reset) _state.value = MinistryHistoryState(loading = true)
        else _state.value = _state.value.copy(loading = true)
        job = viewModelScope.launch {
            val page = repository.page(scope, type, query, before)
            val keep = if (reset) emptyList() else _state.value.entries
            _state.value = MinistryHistoryState(entries = keep + page.entries, loading = false, hasMore = page.hasMore, failed = page.failed)
        }
    }
}

private val ManilaZone: ZoneId = ZoneId.of("Asia/Manila")
private val DayFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH).withZone(ManilaZone)
private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(ManilaZone)
private val FullFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH).withZone(ManilaZone)
private fun dayOf(ms: Long) = if (ms <= 0) "Earlier" else DayFormat.format(Instant.ofEpochMilli(ms))
private fun timeOf(ms: Long) = if (ms <= 0) "" else TimeFormat.format(Instant.ofEpochMilli(ms))
private fun fullOf(ms: Long) = if (ms <= 0) "—" else FullFormat.format(Instant.ofEpochMilli(ms))

private fun categoryColor(category: String): Color = when (category) {
    "ENROLLED" -> Color(0xFF2E7D32)
    "VISIT" -> Color(0xFF1565C0)
    else -> Color(0xFF8A6D1D)
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "ENROLLED" -> Icons.Rounded.Add
    "VISIT" -> Icons.Rounded.Visibility
    else -> Icons.Rounded.Edit
}

private fun stageColor(stage: String): Color = when (stage) {
    "RETURN_VISIT" -> Color(0xFF2E7D32)
    "BIBLE_STUDY" -> Color(0xFF1565C0)
    else -> Color(0xFF444444)
}

/**
 * The Ministry's History tab: everything done in Searching, Return Visit and Bible Study (enrolling, visit logs, moves, assignments, locations, edits, the Recycle
 * Bin), newest first, grouped by day. [scope] is the Show drop-down above it (0 = My, 1 = My FS Group, 2 = Congregation). Tapping an entry shows its details.
 */
@Composable
fun MinistryHistoryScreen(scope: Int, onOpenRecord: (HistoryEntry) -> Unit, modifier: Modifier = Modifier, viewModel: MinistryHistoryViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }

    LaunchedEffect(scope, type, query) {
        if (query.isNotBlank()) delay(400)   // typing settles first
        viewModel.load(scope, type, query, reset = true)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search by name or action") }, shape = RoundedCornerShape(16.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String?, String>>(null to "All", "ENROLLED" to "Enrolled", "VISIT" to "Visits", "CHANGE" to "Changes").forEach { (key, label) ->
                FilterChip(selected = type == key, onClick = { type = key }, label = { Text(label) })
            }
        }
        when {
            state.entries.isEmpty() && state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.entries.isEmpty() && state.failed -> Text("The history needs an internet connection. Check it and try again.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
            state.entries.isEmpty() -> Text("No history yet for this view.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.entries, key = { _, e -> e.id }) { i, e ->
                    val day = dayOf(e.at)
                    Column {
                        if (i == 0 || dayOf(state.entries[i - 1].at) != day) {
                            Text(day.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))
                        }
                        HistoryRow(e) { selected = e }
                    }
                }
                item {
                    if (state.hasMore) OutlinedButton(onClick = { viewModel.load(scope, type, query, reset = false) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(if (state.loading) "Loading…" else "Load more")
                    }
                }
            }
        }
    }

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Column { Text(e.summary, fontWeight = FontWeight.Bold); Text(e.recordName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rows = listOf("Date and time" to fullOf(e.at), "Record" to "${e.recordName.ifBlank { "—" }} (${e.stageLabel})", "Done by" to (e.actorName ?: "—")) + e.details
                    rows.forEach { (label, value) ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { if (e.recordExists) TextButton(onClick = { selected = null; onOpenRecord(e) }) { Text("Open Record") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(categoryColor(e.category), CircleShape), contentAlignment = Alignment.Center) {
                Icon(categoryIcon(e.category), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(e.recordName.ifBlank { "Record" }, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                e.actorName?.let { Text("By $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(timeOf(e.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(modifier = Modifier.background(stageColor(e.stage), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(e.stageLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
