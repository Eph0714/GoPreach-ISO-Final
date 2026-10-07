package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.data.export.TableReportData
import com.emfitsolutions.gopreach.data.export.TableReportExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A column of the report's Table View; [value] also feeds the Print / PDF / Excel table. */
class UniversalColumn<T>(val title: String, val width: Dp = 110.dp, val value: (T) -> String)

/** One filter: single choice among [options] (value to label); "" means All. [matches] decides whether an item passes a chosen value. */
class UniversalFilter<T>(val id: String, val label: String, val options: List<Pair<String, String>>, val matches: (T, String) -> Boolean)

class UniversalSort<T>(val id: String, val label: String, val comparator: Comparator<T>)

/**
 * The standard GoPreach report, so a screen only describes its data:
 * header (title, details, Records Found x of y) → search → filters (removable chips, Clear All) → sort → Table / List view →
 * the records → the mandatory end-of-report Summary (computed from exactly the records shown) → Print / PDF / Excel of the same records.
 *
 * [card] is the List View item; [columns] drive the Table View and the exports. [summary] gets the filtered, sorted items.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun <T> UniversalReport(
    title: String,
    details: List<Pair<String, String>>,
    items: List<T>,
    key: (T) -> Any,
    columns: List<UniversalColumn<T>>,
    searchText: (T) -> List<String>,
    summary: (List<T>) -> List<Pair<String, String>>,
    generatedBy: String,
    card: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    filters: List<UniversalFilter<T>> = emptyList(),
    sorts: List<UniversalSort<T>> = emptyList(),
    emptyMessage: String = "No records found.",
    onRowClick: ((T) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    trailingActions: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var query by rememberSaveable(title) { mutableStateOf("") }
    var table by rememberSaveable(title) { mutableStateOf(false) }
    var sortId by rememberSaveable(title) { mutableStateOf(sorts.firstOrNull()?.id) }
    // filter id -> chosen value; saved as a flat string so it survives rotation
    var chosenRaw by rememberSaveable(title) { mutableStateOf("") }
    val chosen: Map<String, String> = chosenRaw.split('\u0001').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
    fun choose(id: String, value: String) {
        val next = chosen.toMutableMap().also { if (value.isEmpty()) it.remove(id) else it[id] = value }
        chosenRaw = next.entries.joinToString("\u0001") { it.key + "=" + it.value }
    }

    val shown = items.filter { item ->
        (query.isBlank() || searchText(item).any { it.contains(query, ignoreCase = true) }) &&
            filters.all { f -> chosen[f.id]?.let { f.matches(item, it) } ?: true }
    }.let { l -> sorts.firstOrNull { it.id == sortId }?.let { s -> l.sortedWith(s.comparator) } ?: l }
    val summaryRows = summary(shown)
    val filtering = query.isNotBlank() || chosen.isNotEmpty()

    fun exportData() = TableReportData(
        title = title,
        details = details + chosen.mapNotNull { (id, v) -> filters.firstOrNull { it.id == id }?.let { f -> f.label to (f.options.firstOrNull { it.first == v }?.second ?: v) } } +
            (if (query.isNotBlank()) listOf("Search" to query) else emptyList()),
        recordsFound = if (shown.size == items.size) shown.size.toString() else "${shown.size} of ${items.size}",
        table = listOf(columns.map { it.title }) + shown.map { item -> columns.map { it.value(item) } },
        summary = listOf(listOf("Metric", "Value")) + summaryRows.map { listOf(it.first, it.second) },
        generatedBy = generatedBy,
        generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
        landscape = columns.size > 5,
    )

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- header ----
        item(key = "ur-header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                details.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    if (shown.size == items.size) "Records Found: ${items.size}" else "Records Found: ${shown.size} of ${items.size}",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search records...") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } },
                )
                filters.forEach { f ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FilterChip(selected = chosen[f.id] == null, onClick = { choose(f.id, "") }, label = { Text("All ${f.label}") })
                        f.options.filter { it.first.isNotEmpty() }.forEach { (value, label) ->
                            FilterChip(selected = chosen[f.id] == value, onClick = { choose(f.id, if (chosen[f.id] == value) "" else value) }, label = { Text(label) })
                        }
                    }
                }
                if (filtering) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.forEach { (id, v) ->
                        val f = filters.firstOrNull { it.id == id } ?: return@forEach
                        FilterChip(selected = true, onClick = { choose(id, "") }, label = { Text((f.options.firstOrNull { it.first == v }?.second ?: v) + " ×") })
                    }
                    OutlinedButton(onClick = { query = ""; chosenRaw = "" }) { Text("Clear All") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    FilterChip(selected = !table, onClick = { table = false }, label = { Text("List View") })
                    FilterChip(selected = table, onClick = { table = true }, label = { Text("Table View") })
                    sorts.forEach { s -> FilterChip(selected = sortId == s.id, onClick = { sortId = s.id }, label = { Text(s.label) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = shown.isNotEmpty(), onClick = { TableReportExporter.print(context, exportData()) }) { Text("Print / PDF") }
                    OutlinedButton(enabled = shown.isNotEmpty(), onClick = { TableReportExporter.shareExcel(context, exportData()) }) { Text("Excel") }
                    trailingActions()
                }
            }
        }
        // ---- records ----
        if (shown.isEmpty()) {
            item(key = "ur-empty") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (filtering) "No records found. Try clearing the search or filters." else emptyMessage, style = MaterialTheme.typography.bodyMedium)
                    if (filtering) OutlinedButton(onClick = { query = ""; chosenRaw = "" }) { Text("Clear Filters") }
                }
            }
        } else if (table) {
            item(key = "ur-table") {
                val head = Color(0xFFDDE6F2)
                Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Row { columns.forEach { c -> UrCell(c.title, c.width, true, head) } }
                    shown.forEach { item ->
                        Row(modifier = if (onRowClick != null) Modifier.clickableRow { onRowClick(item) } else Modifier) {
                            columns.forEach { c -> UrCell(c.value(item), c.width) }
                        }
                    }
                }
            }
        } else {
            items(shown, key = { key(it) }) { item -> card(item) }
        }
        // ---- the mandatory end-of-report Summary ----
        item(key = "ur-summary") { EndSummary(summaryRows, Modifier.padding(top = 8.dp)) }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun UrCell(text: String, width: Dp, head: Boolean = false, fill: Color = Color.Transparent) {
    Box(
        modifier = Modifier.width(width).height(34.dp).background(fill).border(0.5.dp, Color(0xFF444444)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (head) FontWeight.Bold else FontWeight.Normal, color = Color.Black, maxLines = 1) }
}
