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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.layout
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
    // Full screen belongs to the list on screen: leaving it (back, or the list emptying) puts the app bar and filters back.
    DisposableEffect(Unit) { onDispose { RecordFullScreen.active = false } }
    return RecordViewMode(table, hScroll) { table = it }
}

/** [hScroll] is shared by the pinned heading and every row of Table View, so they slide sideways together. */
class RecordViewMode(val table: Boolean, val hScroll: ScrollState, val set: (Boolean) -> Unit)

/** One slim row of three round icon buttons (List, Table, Full Screen), so the switch takes no more room than the "Record Found" line it sits beside. */
@Composable
fun RecordViewToggle(mode: RecordViewMode, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable
    fun Choice(selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
        androidx.compose.material3.IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp).background(if (selected) primary.copy(alpha = 0.15f) else Color.Transparent, androidx.compose.foundation.shape.CircleShape),
        ) { Icon(icon, contentDescription = label, tint = if (selected) primary else idle, modifier = Modifier.size(22.dp)) }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Choice(!mode.table, Icons.Rounded.ViewAgenda, "List View") { mode.set(false) }
        Choice(mode.table, Icons.Rounded.TableChart, "Table View") { mode.set(true) }
        Choice(RecordFullScreen.active, if (RecordFullScreen.active) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (RecordFullScreen.active) "Exit Full Screen" else "Full Screen") {
            RecordFullScreen.active = !RecordFullScreen.active
        }
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
    /** The list's own spacing between items, taken back out of the table rows so they join into one table. */
    gap: Dp = 8.dp,
    card: @Composable (T) -> Unit,
) {
    if (mode.table) {
        recordTableItems(items, columns, mode.hScroll, onRowClick, key, gap)
    } else {
        items(items.size, key = { key(items[it]) }) { card(items[it]) }
    }
}

/** One scrollable table: pinned look heading row, one row per record, every column a fixed width so rows line up. */
@Composable
fun <T> RecordTable(items: List<T>, columns: List<UniversalColumn<T>>, onRowClick: ((T) -> Unit)? = null, modifier: Modifier = Modifier) {
    val head = TableHeadFill
    Column(modifier = modifier.horizontalScroll(rememberScrollState())) {
        Row { RecordCell("No.", TableNoWidth, header = true, fill = head, center = true); columns.forEach { c -> RecordCell(c.title, c.width, header = true, fill = head) } }
        items.forEachIndexed { index, item ->
            Row(modifier = if (onRowClick != null) Modifier.clickable { onRowClick(item) } else Modifier) {
                val fill = Color.Transparent
                RecordCell((index + 1).toString(), TableNoWidth, center = true); columns.forEach { c -> RecordCell(c.value(item), c.width) }
            }
        }
    }
}

/**
 * Joins a table row to the one above it. A lazy list spaces its items (usually 8 dp), which would float every table row as its own bar;
 * this takes that spacing back out of the row's height so the rows touch and the vertical lines run through the whole table.
 */
fun Modifier.joinTableRow(gap: Dp = 8.dp): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val g = gap.roundToPx()
    layout(placeable.width, (placeable.height - g).coerceAtLeast(0)) { placeable.placeRelative(0, 0) }
}

/** The shared table look (the Field Service Report's): 34 dp rows, hairline cell borders, bold light-blue heading, plain body rows. */
val TableHeadFill = Color.Transparent

/** Width of the numbered first column every record table starts with, like the Field Service Report's No. column. */
val TableNoWidth = 36.dp

@Composable
private fun RecordCell(text: String, width: Dp, header: Boolean = false, fill: Color = Color.Transparent, center: Boolean = false) {
    val (cellBg, cellFg) = com.emfitsolutions.gopreach.ui.theme.cellColors(fill)
    Box(
        modifier = Modifier.width(width).height(34.dp).background(cellBg).border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()).padding(horizontal = 6.dp),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, color = cellFg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
    gap: Dp = 8.dp,
) {
    stickyHeader(key = "record-table-head") {
        val head = TableHeadFill
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).joinTableRow(gap)) {
            Row(Modifier.horizontalScroll(hScroll)) { RecordCell("No.", TableNoWidth, header = true, fill = head, center = true); columns.forEach { c -> RecordCell(c.title, c.width, header = true, fill = head) } }
        }
    }
    items(items.size, key = { i -> key?.let { "rt-" + it(items[i]) } ?: "rt-row-$i" }) { index ->
        val item = items[index]
        Row(modifier = Modifier.joinTableRow(gap).horizontalScroll(hScroll).then(if (onRowClick != null) Modifier.clickable { onRowClick(item) } else Modifier)) {
            RecordCell((index + 1).toString(), TableNoWidth, center = true); columns.forEach { c -> RecordCell(c.value(item), c.width) }
        }
    }
}

/**
 * Table View in sections: the pinned column heading once, then for each of [groups] a bold name row (with its count) followed by that
 * group's rows. Used where records belong to a group (e.g. publishers by FS Group).
 */
@OptIn(ExperimentalFoundationApi::class)
fun <T> LazyListScope.recordGroupedTableItems(
    groups: Map<String, List<T>>,
    columns: List<UniversalColumn<T>>,
    hScroll: ScrollState,
    onRowClick: ((T) -> Unit)? = null,
    key: (T) -> Any,
    gap: Dp = 8.dp,
) {
    stickyHeader(key = "record-table-head") {
        val head = TableHeadFill
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).joinTableRow(gap)) {
            Row(Modifier.horizontalScroll(hScroll)) { RecordCell("No.", TableNoWidth, header = true, fill = head, center = true); columns.forEach { c -> RecordCell(c.title, c.width, header = true, fill = head) } }
        }
    }
    groups.forEach { (name, members) ->
        item(key = "rt-group-$name") {
            Row(Modifier.joinTableRow(gap).horizontalScroll(hScroll)) {
                Box(
                    Modifier.width((TableNoWidth.value + columns.sumOf { it.width.value.toDouble() }).dp).height(34.dp).background(MaterialTheme.colorScheme.primaryContainer)
                        .border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("$name · ${members.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        items(members.size, key = { i -> "rt-" + key(members[i]) }) { index ->
            val item = members[index]
            Row(modifier = Modifier.joinTableRow(gap).horizontalScroll(hScroll).then(if (onRowClick != null) Modifier.clickable { onRowClick(item) } else Modifier)) {
                RecordCell((index + 1).toString(), TableNoWidth, center = true); columns.forEach { c -> RecordCell(c.value(item), c.width) }
            }
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
