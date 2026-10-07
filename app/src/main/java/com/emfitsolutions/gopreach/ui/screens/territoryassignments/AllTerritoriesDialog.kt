package com.emfitsolutions.gopreach.ui.screens.territoryassignments

import com.emfitsolutions.gopreach.data.print.escapeHtml
import androidx.compose.foundation.background
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.data.print.OrientationMode
import com.emfitsolutions.gopreach.data.print.PrintOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Print
import androidx.compose.ui.platform.LocalContext
import com.emfitsolutions.gopreach.data.print.ReportPrinter
import com.emfitsolutions.gopreach.data.print.ReportTable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.viewmodel.koinViewModel
import com.emfitsolutions.gopreach.ui.components.GroupColorPalette
import com.emfitsolutions.gopreach.ui.components.map.NamedBoundary
import com.emfitsolutions.gopreach.ui.components.map.HideSystemBarsEffect
import com.emfitsolutions.gopreach.ui.components.map.OsmBoundaryMap

private enum class AllTerritoriesMode(val label: String) { MAP("Map View"), LIST("List View") }

/** One barangay across every Field Service Group, with the Group it belongs
 * to (for color + the province/municipality a boundary lookup needs). */
private data class AllTerritoriesEntry(
    val barangay: GroupTerritoryBarangay,
    val groupName: String,
    val groupColorHex: String?,
)

/**
 * "Show All Territories" — every barangay across every Field Service Group
 * in one place, as either a single combined map (each barangay outlined in
 * its own Group's color) or a spreadsheet-style list (Municipality, then one
 * column per Group, following the same "41475 - Territories" layout
 * Secretaries already print/share — see the reference image this was built
 * from) with each column's header tinted by that Group's own
 * [GroupColorPalette] color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTerritoriesDialog(
    rows: List<GroupTerritoryRow>,
    onDismiss: () -> Unit,
    viewModel: TerritoryAssignmentsViewModel = koinViewModel(),
) {
    var mode by remember { mutableStateOf(AllTerritoriesMode.MAP) }
    val context = LocalContext.current
    var fullScreenRequested by remember { mutableStateOf(false) }
    val fullScreen = fullScreenRequested && mode == AllTerritoriesMode.MAP

    Dialog(onDismissRequest = { if (fullScreen) fullScreenRequested = false else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBarsEffect(fullScreen)
        Scaffold(
            topBar = {
                if (!fullScreen) Column {
                    TopAppBar(
                        title = { Text("All Territories") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                            }
                        },
                        actions = {
                            // "Print / PDF" of the Municipality x FS Group list (system print dialog, Save as PDF).
                            val colors = MaterialTheme.colorScheme
                            val dark = androidx.compose.foundation.isSystemInDarkTheme()
                            IconButton(onClick = {
                                if (mode == AllTerritoriesMode.LIST) {
                                    // List View prints exactly what's on screen: the same Municipality bars, the same
                                    // colored Group columns, the same layout — not a different plain table.
                                    ReportPrinter.printHtml(
                                        context,
                                        "All Territories",
                                        buildAllTerritoriesListHtml(
                                            blocks = allTerritoriesBlocks(rows),
                                            barColor = colors.primaryContainer,
                                            barTextColor = colors.onPrimaryContainer,
                                            // Paper is white, so body text stays dark even when the app is in dark mode.
                                            textColor = if (dark) Color(0xFF1C1B1F) else colors.onSurface,
                                            mutedTextColor = if (dark) Color(0xFF49454F) else colors.onSurfaceVariant,
                                        ),
                                        PrintOptions(OrientationMode.LANDSCAPE),
                                    )
                                    return@IconButton
                                }
                                ReportPrinter.print(
                                    context,
                                    ReportTable(
                                        title = "All Territories",
                                        count = rows.sumOf { it.municipalities.size },
                                        countLabel = "Total Assignments",
                                        columns = listOf("Municipality", "FS Group", "Barangays"),
                                        rows = rows.flatMap { row ->
                                            row.municipalities.map { m ->
                                                listOf(m.assignment.muncityName, row.group?.name ?: "Unknown Group", m.barangays.joinToString(", ") { it.barangayName })
                                            }
                                        }.sortedWith(compareBy({ it[0] }, { it[1] })),
                                    ),
                                )
                            }) { Icon(Icons.Rounded.Print, contentDescription = "Print or save as PDF") }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        AllTerritoriesMode.entries.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { mode = m },
                                label = { Text(m.label) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (mode) {
                    AllTerritoriesMode.MAP -> AllTerritoriesMapView(
                        rows = rows,
                        viewModel = viewModel,
                        fullScreen = fullScreen,
                        onFullScreenChange = { fullScreenRequested = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AllTerritoriesMode.LIST -> AllTerritoriesListView(rows = rows, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun AllTerritoriesMapView(
    rows: List<GroupTerritoryRow>,
    viewModel: TerritoryAssignmentsViewModel,

    fullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,    modifier: Modifier,
) {
    val entries = remember(rows) {
        rows.flatMap { row ->
            row.municipalities.flatMap { m ->
                m.barangays.map { b ->
                    AllTerritoriesEntry(
                        barangay = GroupTerritoryBarangay(row.provinceName, m.assignment.muncityName, b.barangayName),
                        groupName = row.group?.name ?: "Unknown Group",
                        groupColorHex = row.group?.color,
                    )
                }
            }
        }
    }
    var boundaries by remember(entries) { mutableStateOf<List<NamedBoundary>?>(null) }
    var drillDown by remember(entries) { mutableStateOf<AllTerritoriesEntry?>(null) }

    LaunchedEffect(entries) {
        boundaries = entries.mapNotNull { e ->
            viewModel.boundaryGeometry(e.barangay.province, e.barangay.municipality, e.barangay.barangayName)
                ?.let { geometryJson -> NamedBoundary(e.barangay.barangayName, geometryJson, e.groupColorHex) }
        }
    }

    Box(modifier = modifier) {
        val resolved = boundaries
        when {
            resolved == null -> Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            resolved.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No boundary map available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> OsmBoundaryMap(
                boundaries = resolved,
                onBoundaryClick = { name -> drillDown = entries.find { it.barangay.barangayName == name } },
                exportTitle = "All Territories",
                fullScreen = fullScreen,
                onFullScreenChange = onFullScreenChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    drillDown?.let { e ->
        BarangayBoundaryDialog(
            province = e.barangay.province,
            municipality = e.barangay.municipality,
            barangayName = e.barangay.barangayName,
            boundaryColorHex = e.groupColorHex,
            onDismiss = { drillDown = null },
        )
    }
}

/** One Municipality's own row in the list — [cells] is one entry per Group
 * (in the same order for every municipality), empty [Cell.barangayNames]
 * when that Group has nothing assigned there. */
private data class MunicipalityBlock(val municipalityName: String, val cells: List<Cell>)
private data class Cell(val groupName: String, val colorHex: String?, val barangayNames: List<String>)

/** The list's data: one block per Municipality, with one cell per Field Service Group (same order everywhere). */
private fun allTerritoriesBlocks(rows: List<GroupTerritoryRow>): List<MunicipalityBlock> {
    val groups = rows.mapNotNull { it.group }.distinctBy { it.id }.sortedWith(com.emfitsolutions.gopreach.domain.GroupNameOrder)
    val municipalityNames = rows.flatMap { row -> row.municipalities.map { it.assignment.muncityName } }.distinct().sortedBy { it }
    return municipalityNames.map { muniName ->
        val cells = groups.map { group ->
            val barangayNames = rows.find { it.group?.id == group.id }
                ?.municipalities
                ?.find { it.assignment.muncityName == muniName }
                ?.barangays
                ?.map { it.barangayName }
                ?: emptyList()
            Cell(group.name, group.color, barangayNames)
        }
        MunicipalityBlock(muniName, cells)
    }
}

private fun Color.toCssHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
private fun Color.toCssRgba(alpha: Float): String =
    "rgba(${(red * 255).roundToInt()},${(green * 255).roundToInt()},${(blue * 255).roundToInt()},$alpha)"

/**
 * The List View as printable HTML, built to match the on-screen list one for one: a rounded Municipality bar
 * (primary-container color, bold centered title), then a row of Group columns, each with a solid Group-colored
 * header (white bold name) over a body tinted 16% with that same color listing the barangays ("—" when none).
 * Sizes are the on-screen dp values as CSS px. Columns wrap onto the next line rather than scrolling sideways,
 * so every Group fits on paper.
 */
private fun buildAllTerritoriesListHtml(
    blocks: List<MunicipalityBlock>,
    barColor: Color,
    barTextColor: Color,
    textColor: Color,
    mutedTextColor: Color,
): String = buildString {
    fun e(s: String) = escapeHtml(s)
    append("<html><head><meta charset=\"utf-8\"><style>")
    append("@page{size:landscape;margin:10mm} ")
    append("*{-webkit-print-color-adjust:exact;print-color-adjust:exact;box-sizing:border-box} ")
    append("body{font-family:sans-serif;margin:0;padding:16px;color:${textColor.toCssHex()}} ")
    append(".block{margin-bottom:20px;page-break-inside:avoid} ")
    append(".bar{background:${barColor.toCssHex()};color:${barTextColor.toCssHex()};border-radius:8px;padding:10px 0;text-align:center;font-size:16px;font-weight:bold} ")
    append(".cols{display:flex;flex-wrap:wrap;margin-top:6px} ")
    append(".col{width:150px;padding-right:6px;margin-bottom:6px} ")
    append(".head{color:#fff;font-size:12px;font-weight:bold;text-align:center;padding:6px 0;border-radius:6px 6px 0 0} ")
    append(".body{padding:8px;font-size:12px;border-radius:0 0 6px 6px} ")
    append(".body div{margin:0} .none{color:${mutedTextColor.toCssHex()}}")
    append("</style></head><body>")
    val barangayTotal = blocks.sumOf { b -> b.cells.sumOf { it.barangayNames.size } }
    append("<div style=\"font-weight:bold;font-size:13px;margin-bottom:8px\">Total Municipalities: ${blocks.size}  •  Total Barangays: $barangayTotal</div>")
    blocks.forEach { block ->
        append("<div class=\"block\"><div class=\"bar\">").append(e(block.municipalityName)).append("</div><div class=\"cols\">")
        block.cells.forEach { cell ->
            val swatch = GroupColorPalette.parseHex(cell.colorHex ?: GroupColorPalette.UNASSIGNED_COLOR)
            append("<div class=\"col\"><div class=\"head\" style=\"background:${swatch.toCssHex()}\">").append(e(cell.groupName)).append("</div>")
            append("<div class=\"body\" style=\"background:${swatch.toCssRgba(0.16f)}\">")
            if (cell.barangayNames.isEmpty()) append("<div class=\"none\">—</div>")
            else cell.barangayNames.forEach { append("<div>").append(e(it)).append("</div>") }
            append("</div></div>")
        }
        append("</div></div>")
    }
    // The mandatory end-of-report Summary.
    append("<div style=\"margin-top:10px;font-size:12px\"><b>Summary</b><br>Total Municipalities: ${blocks.size}<br>Total Barangays: $barangayTotal<br>")
    append("Assigned Groups: ${blocks.flatMap { it.cells }.map { it.groupName }.distinct().size}</div>")
    append("</body></html>")
}

@Composable
private fun AllTerritoriesListView(rows: List<GroupTerritoryRow>, modifier: Modifier) {
    val blocks = remember(rows) { allTerritoriesBlocks(rows) }

    if (blocks.isEmpty()) {
        Column(modifier = modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RecordFound(0)
            Text(
                "No territory assignments yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
    ) {
        item { RecordFound(blocks.size) }
        items(blocks, key = { it.municipalityName }) { block ->
            Column {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        block.municipalityName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    block.cells.forEach { cell ->
                        val swatch = GroupColorPalette.parseHex(cell.colorHex ?: GroupColorPalette.UNASSIGNED_COLOR)
                        Column(modifier = Modifier.width(150.dp).padding(end = 6.dp)) {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(swatch)
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    cell.groupName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                                    .background(swatch.copy(alpha = 0.16f))
                                    .padding(8.dp),
                            ) {
                                if (cell.barangayNames.isEmpty()) {
                                    Text(
                                        "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    cell.barangayNames.forEach { name ->
                                        Text(name, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
