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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * List View / Table View for any record or report screen that is not built on [UniversalReport] (which has its own switch).
 *
 *  - [rememberRecordViewMode] keeps the choice per screen ([screenKey]) across rotation.
 *  - [RecordViewToggle] is the two-chip switch, worded exactly like the other reports ("List View" / "Table View").
 *  - [recordItems] puts the records into a lazy list as the screen's own cards (List View) or as one scrollable table whose columns describe
 *    the same data (Table View). Tapping a table row runs [onRowClick], the same action tapping the card runs.
 */
@Composable
fun rememberRecordViewMode(screenKey: String, defaultTable: Boolean = false): RecordViewMode {
    var table by rememberSaveable(screenKey) { mutableStateOf(defaultTable) }
    val hScroll = rememberScrollState()
    return RecordViewMode(table, hScroll) { table = it }
}

/** [hScroll] is shared by the pinned heading and every row of Table View, so they slide sideways together. */
class RecordViewMode(val table: Boolean, val hScroll: ScrollState, val set: (Boolean) -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordViewToggle(mode: RecordViewMode, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !mode.table, onClick = { mode.set(false) }, label = { Text("List View") })
        FilterChip(selected = mode.table, onClick = { mode.set(true) }, label = { Text("Table View") })
    }
}

/** Adds [items] to a lazy list in the chosen view. [card] is the screen's existing List View item. */
@OptIn(ExperimentalFoundationApi::class)
fun <T> LazyListScope.recordItems(
    mode: RecordViewMode,
    items: List<T>,
    key: (T) -> Any,
    columns: List<UniversalColumn<T>>,
    onRowClick: ((T) -> Unit)? = null,
    card: @Composable (T) -> Unit,
) {
    if (mode.table) {
        recordTableItems(items, columns, mode.hScroll, onRowClick, key)
    } else {
        items(items.size, key = { key(items[it]) }) { card(items[it]) }
    }
}

/** One scrollable table: pinned look heading row, one row per record, every column a fixed width so rows line up. */
@Composable
fun <T> RecordTable(items: List<T>, columns: List<UniversalColumn<T>>, onRowClick: ((T) -> Unit)? = null, modifier: Modifier = Modifier) {
    val head = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.horizontalScroll(rememberScrollState())) {
        Row { columns.forEach { c -> RecordCell(c.title, c.width, header = true, fill = head) } }
        items.forEachIndexed { index, item ->
            Row(modifier = if (onRowClick != null) Modifier.clickable { onRowClick(item) } else Modifier) {
                val fill = if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else Color.Transparent
                columns.forEach { c -> RecordCell(c.value(item), c.width, fill = fill) }
            }
        }
    }
}

@Composable
private fun RecordCell(text: String, width: Dp, header: Boolean = false, fill: Color = Color.Transparent) {
    Box(
        modifier = Modifier.width(width).height(40.dp).background(fill).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
    }
}

/**
 * Table View as separate lazy items: a heading that stays pinned at the top while the records scroll, then one lazy row per record.
 * [hScroll] keeps the heading and the rows aligned when the table is wider than the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
fun <T> LazyListScope.recordTableItems(
    items: List<T>,
    columns: List<UniversalColumn<T>>,
    hScroll: ScrollState,
    onRowClick: ((T) -> Unit)? = null,
    key: ((T) -> Any)? = null,
) {
    stickyHeader(key = "record-table-head") {
        val head = MaterialTheme.colorScheme.surfaceVariant
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.horizontalScroll(hScroll)) { columns.forEach { c -> RecordCell(c.title, c.width, header = true, fill = head) } }
        }
    }
    items(items.size, key = { i -> key?.let { "rt-" + it(items[i]) } ?: "rt-row-$i" }) { index ->
        val item = items[index]
        Row(modifier = Modifier.horizontalScroll(hScroll).then(if (onRowClick != null) Modifier.clickable { onRowClick(item) } else Modifier)) {
            val fill = if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else Color.Transparent
            columns.forEach { c -> RecordCell(c.value(item), c.width, fill = fill) }
        }
    }
}

/**
 * A table placed inside a page that already scrolls: the heading stays pinned at the top of the table while its rows scroll
 * (the table body scrolls within [maxHeight]), and heading and rows slide sideways together. [header] and [row] each draw one
 * table row; [footer], when given, is a last row that scrolls with the records (e.g. a Total row).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedTable(
    rowCount: Int,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 460.dp,
    header: @Composable () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    row: @Composable (Int) -> Unit,
) {
    val hScroll = rememberScrollState()
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        stickyHeader {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) { Box(Modifier.horizontalScroll(hScroll)) { header() } }
        }
        items(rowCount) { i -> Box(Modifier.horizontalScroll(hScroll)) { row(i) } }
        footer?.let { f -> item { Box(Modifier.horizontalScroll(hScroll)) { f() } } }
    }
}
