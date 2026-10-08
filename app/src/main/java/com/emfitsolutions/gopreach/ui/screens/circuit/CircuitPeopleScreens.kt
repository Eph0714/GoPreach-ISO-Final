package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.export.CircuitPublisherExporter
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.SortByAlpha
import com.emfitsolutions.gopreach.ui.components.co.CoButton
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.components.co.CoCongregationCard
import com.emfitsolutions.gopreach.ui.components.co.CoFullScreenButton
import com.emfitsolutions.gopreach.ui.components.co.CoFullScreenEffect
import com.emfitsolutions.gopreach.ui.components.co.CoKind
import com.emfitsolutions.gopreach.ui.components.co.CoSearchField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.ui.components.CongregationContextStore
import com.emfitsolutions.gopreach.ui.components.RecordFound
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel

private const val ANY = ""

private fun categoryOptions() = listOf(ANY to "All categories") + PublisherCategory.entries
    .filter { it != PublisherCategory.REMOVED_PUBLISHER }.map { it.name to it.displayName }

/**
 * Circuit Overseer → Publishers. Reached from a Quick Access tile (category preset), the Congregation Overview, or
 * the side panel. Read-only: search, congregation / category / status / FS Group filters, sorted by name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitPublishersScreen(
    currentPersonId: String,
    initialCategory: String?,
    initialCongregationId: String?,
    onBack: () -> Unit,
    viewModel: CircuitPeopleViewModel = koinViewModel(),
) {
    val congregations by remember(currentPersonId) { viewModel.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    // A congregation passed in (Congregation Overview, a Quick Access tile with one chosen) becomes the selection.
    remember(initialCongregationId) { initialCongregationId?.takeIf { it.isNotBlank() }?.let { CircuitScopeStore.selectCongregation(it) }; 0 }
    // No congregation chosen = nothing is loaded or listed: the Circuit Overseer must pick one first (never "all").
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)
    val congregationId = selected?.id
    var category by remember { mutableStateOf(initialCategory?.takeIf { it.isNotBlank() }) }
    var status by remember { mutableStateOf<String?>(null) }
    var groupName by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var descending by remember { mutableStateOf(false) }
    var tableView by remember { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    val cat = category?.let { runCatching { PublisherCategory.valueOf(it) }.getOrNull() }
    val all by remember(currentPersonId, congregationId, cat) {
        if (congregationId == null) kotlinx.coroutines.flow.emptyFlow() else viewModel.publishers(currentPersonId, congregationId, cat)
    }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val rows = all
        .filter { status == null || it.status == status }
        .filter { groupName == null || it.groupName == groupName }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .let { if (descending) it.reversed() else it }
    val context = androidx.compose.ui.platform.LocalContext.current
    val headings by remember(currentPersonId) { viewModel.headings(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val circuitLabel by remember(currentPersonId) { viewModel.circuitLabel(currentPersonId) }.collectAsStateWithLifecycle(initialValue = "")
    val sections = publisherSections(rows, headings)
    val totals = publisherTotals(rows)
    val filterText = listOfNotNull(
        cat?.let { "Category: ${it.displayName}" }, status?.let { "Status: $it" }, groupName?.let { "FS Group: $it" },
        query.takeIf { it.isNotBlank() }?.let { "Search: $it" },
    ).joinToString(", ")

    val showCongregation = false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cat?.displayName?.let { "$it (${all.size})" } ?: "Publishers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { if (selected != null) CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
            )
        },
    ) { padding ->
        if (selected == null) {
            SelectCongregationPrompt(
                congregations = congregations,
                onSelect = { CircuitScopeStore.selectCongregation(it) },
                modifier = Modifier.padding(padding),
                hint = cat?.let { "Choose a congregation under your assigned Circuit to view its ${it.displayName} records." },
            )
            return@Scaffold
        }
        val tableScroll = rememberScrollState()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectedCongregationBar(selected.name, onChange = { CircuitScopeStore.selectCongregation(null); groupName = null }, title = "Publishers")
                    if (!fullScreen) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CoSearchField(query, { query = it }, "Search name...")
                    SimpleDropdown(
                        label = "Category",
                        selectedLabel = cat?.displayName ?: "All categories",
                        options = categoryOptions(),
                        onSelected = { category = it.ifEmpty { null } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            SimpleDropdown(
                                label = "Status",
                                selectedLabel = status ?: "All",
                                options = listOf(ANY to "All") + all.map { it.status }.distinct().sorted().map { it to it },
                                onSelected = { status = it.ifEmpty { null } },
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            SimpleDropdown(
                                label = "FS Group",
                                selectedLabel = groupName ?: "All",
                                options = listOf(ANY to "All") + all.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted().map { it to it },
                                onSelected = { groupName = it.ifEmpty { null } },
                            )
                        }
                    }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !tableView, onClick = { tableView = false }, label = { Text("List View") })
                        FilterChip(selected = tableView, onClick = { tableView = true }, label = { Text("Table View") })
                        CoButton(if (descending) "Name: Z → A" else "Name: A → Z", { descending = !descending }, kind = CoKind.Secondary, icon = Icons.Rounded.SortByAlpha)
                    }
                    RecordFound(rows.size)
                    PublisherSummary(
                        sections = sections, circuitLabel = circuitLabel, totals = totals,
                        onPrint = { CircuitPublisherExporter.print(context, circuitLabel, filterText, sections) },
                        onExcel = { CircuitPublisherExporter.shareExcel(context, circuitLabel, filterText, sections) },
                    )
                }
            }
            if (rows.isEmpty()) item { Text("No publishers match.", style = MaterialTheme.typography.bodyMedium) }
            if (tableView) {
                if (rows.isNotEmpty()) publisherTableItems(rows, showCongregation, tableScroll)
            } else {
                itemsIndexed(rows, key = { _, it -> it.personId + it.congregationId }) { i, it -> PublisherDetailCard(it, i, showCongregation) }
            }
        }
    }
}

@Composable
private fun PersonCard(row: CircuitPersonRow, showCongregation: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(row.categoryLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (showCongregation) Text("Congregation: ${row.congregationName}", style = MaterialTheme.typography.bodySmall)
            if (row.groupName.isNotBlank()) Text("FS Group: ${row.groupName}", style = MaterialTheme.typography.bodySmall)
            Text("Status: ${row.status}", style = MaterialTheme.typography.bodySmall)
            if (row.contact.isNotBlank()) Text("Contact: ${row.contact}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Circuit Overseer → Elders & Ministerial Servants (view-only), optionally narrowed to one congregation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitLeadersScreen(
    currentPersonId: String,
    initialCongregationId: String?,
    onBack: () -> Unit,
    viewModel: CircuitPeopleViewModel = koinViewModel(),
) {
    val congregations by remember(currentPersonId) { viewModel.congregations(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    remember(initialCongregationId) { initialCongregationId?.takeIf { it.isNotBlank() }?.let { CircuitScopeStore.selectCongregation(it) }; 0 }
    val selected = validCongregation(congregations, CircuitScopeStore.congregation)
    val congregationId = selected?.id
    var query by remember { mutableStateOf("") }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    CoFullScreenEffect(fullScreen)
    androidx.activity.compose.BackHandler(enabled = fullScreen) { fullScreen = false }
    val leaders by remember(currentPersonId, congregationId) {
        if (congregationId == null) kotlinx.coroutines.flow.emptyFlow() else viewModel.leaders(currentPersonId, congregationId)
    }
        .collectAsStateWithLifecycle(initialValue = emptyList<CircuitPersonRow>() to emptyList())
    val filter: (CircuitPersonRow) -> Boolean = { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val elders = leaders.first.filter(filter)
    val servants = leaders.second.filter(filter)
    val showCongregation = false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Elders & Ministerial Servants") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { if (selected != null) CoFullScreenButton(fullScreen) { fullScreen = !fullScreen } },
            )
        },
    ) { padding ->
        if (selected == null) {
            SelectCongregationPrompt(congregations, onSelect = { CircuitScopeStore.selectCongregation(it) }, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        val people = elders.map { "Elder" to it } + servants.map { "Ministerial Servant" to it }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SelectedCongregationBar(selected.name, onChange = { CircuitScopeStore.selectCongregation(null) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), title = "Elders & Servants")
            com.emfitsolutions.gopreach.ui.components.UniversalReport(
                title = "Elders & Ministerial Servants",
                details = listOf("Congregation" to selected.name),
                items = people,
                key = { (type, row) -> type + row.personId + row.roleLabel + row.congregationId },
                columns = listOf(
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<Pair<String, CircuitPersonRow>>("Name", 200.dp) { it.second.name },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<Pair<String, CircuitPersonRow>>("Type", 150.dp) { it.first },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<Pair<String, CircuitPersonRow>>("Role", 170.dp) { it.second.categoryLabel },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<Pair<String, CircuitPersonRow>>("FS Group", 120.dp) { it.second.groupName },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<Pair<String, CircuitPersonRow>>("Status", 90.dp) { it.second.status },
                    com.emfitsolutions.gopreach.ui.components.UniversalColumn<Pair<String, CircuitPersonRow>>("Contact", 130.dp) { it.second.contact },
                ),
                searchText = { (type, r) -> listOf(r.name, type, r.categoryLabel, r.groupName, r.contact) },
                filters = listOf(
                    com.emfitsolutions.gopreach.ui.components.UniversalFilter<Pair<String, CircuitPersonRow>>("type", "Types", listOf("Elder" to "Elders", "Ministerial Servant" to "Ministerial Servants")) { p, v -> p.first == v },
                ),
                sorts = listOf(
                    com.emfitsolutions.gopreach.ui.components.UniversalSort<Pair<String, CircuitPersonRow>>("az", "Name A–Z", compareBy { it.second.name.lowercase() }),
                    com.emfitsolutions.gopreach.ui.components.UniversalSort<Pair<String, CircuitPersonRow>>("za", "Name Z–A", compareByDescending { it.second.name.lowercase() }),
                ),
                summary = { shown ->
                    listOf(
                        "Total Records" to shown.size.toString(),
                        "Elders" to shown.count { it.first == "Elder" }.toString(),
                        "Ministerial Servants" to shown.count { it.first != "Elder" }.toString(),
                    )
                },
                generatedBy = "Circuit Overseer",
                card = { (_, row) -> PersonCard(row, showCongregation) },
                emptyMessage = "No elders or ministerial servants found.",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun monthLabel(start: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(start))

/** Circuit Overseer → Congregation List: one card per assigned congregation with people counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitCongregationsScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenCongregation: (String) -> Unit,
    viewModel: CircuitPeopleViewModel = koinViewModel(),
) {
    val summaries by remember(currentPersonId) { viewModel.summaries(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val cardStats by koinViewModel<CongregationStatsViewModel>().stats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Congregations") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        com.emfitsolutions.gopreach.ui.components.UniversalReport(
            title = "Congregations",
            details = emptyList(),
            items = summaries,
            key = { it.congregation.id },
            columns = listOf(
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Code", 80.dp) { it.congregation.code },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Congregation", 200.dp) { it.congregation.name },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("City / Municipality", 160.dp) { it.congregation.cityMunicipality.orEmpty() },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Coordinator", 160.dp) { it.coordinator },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Publishers", 90.dp) { it.publishers.toString() },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Reg. Pioneers", 100.dp) { (it.counts[PublisherCategory.REGULAR_PIONEER] ?: 0).toString() },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Aux. Pioneers", 100.dp) { (it.counts[PublisherCategory.AUXILIARY_PIONEER] ?: 0).toString() },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Unbaptized", 90.dp) { (it.counts[PublisherCategory.UNBAPTIZED_PUBLISHER] ?: 0).toString() },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Elders", 70.dp) { it.elders.toString() },
                com.emfitsolutions.gopreach.ui.components.UniversalColumn<CongregationPeopleSummary>("Ministerial Servants", 130.dp) { it.servants.toString() },
            ),
            searchText = { listOf(it.congregation.name, it.congregation.code, it.congregation.cityMunicipality.orEmpty(), it.coordinator) },
            sorts = listOf(
                com.emfitsolutions.gopreach.ui.components.UniversalSort<CongregationPeopleSummary>("az", "Name A–Z", compareBy { it.congregation.name.lowercase() }),
                com.emfitsolutions.gopreach.ui.components.UniversalSort<CongregationPeopleSummary>("largest", "Most Publishers", compareByDescending { it.publishers }),
            ),
            summary = { shown ->
                listOf(
                    "Total Congregations" to shown.size.toString(),
                    "Publishers" to shown.sumOf { it.publishers }.toString(),
                    "Regular Pioneers" to shown.sumOf { it.counts[PublisherCategory.REGULAR_PIONEER] ?: 0 }.toString(),
                    "Auxiliary Pioneers" to shown.sumOf { it.counts[PublisherCategory.AUXILIARY_PIONEER] ?: 0 }.toString(),
                    "Unbaptized Publishers" to shown.sumOf { it.counts[PublisherCategory.UNBAPTIZED_PUBLISHER] ?: 0 }.toString(),
                    "Elders" to shown.sumOf { it.elders }.toString(),
                    "Ministerial Servants" to shown.sumOf { it.servants }.toString(),
                )
            },
            generatedBy = "Circuit Overseer",
            card = { s -> CoCongregationCard(s.congregation, cardStats[s.congregation.id], onClick = { onOpenCongregation(s.congregation.id) }) },
            onRowClick = { onOpenCongregation(it.congregation.id) },
            emptyMessage = "No congregations are assigned to this account yet.",
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Circuit Overseer → one congregation: its information and a single place to jump to its people and reports
 * without going back to the dashboard. (Territory is added with the Territory Map phase.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircuitCongregationOverviewScreen(
    currentPersonId: String,
    congregationId: String,
    onBack: () -> Unit,
    onOpenPublishers: (category: String?) -> Unit,
    onOpenLeaders: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenTerritory: () -> Unit,
    viewModel: CircuitPeopleViewModel = koinViewModel(),
) {
    val summaries by remember(currentPersonId) { viewModel.summaries(currentPersonId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val s = summaries.firstOrNull { it.congregation.id == congregationId }
    val publishers by remember(currentPersonId, congregationId) { viewModel.publishers(currentPersonId, congregationId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val groups = publishers.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.congregation?.name ?: "Congregation") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (s == null) {
            Text("This congregation is not part of your circuit.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CoCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Congregation Information", style = MaterialTheme.typography.titleSmall)
                        Text(s.congregation.name, style = MaterialTheme.typography.titleMedium)
                        if (s.congregation.address.isNotBlank()) Text(s.congregation.address, style = MaterialTheme.typography.bodySmall)
                        Text("Publishers: ${s.publishers}", style = MaterialTheme.typography.bodySmall)
                        Text("Field Service Groups: ${if (groups.isEmpty()) "—" else groups.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("People", style = MaterialTheme.typography.titleSmall) }
            item { LinkRow("Publishers", s.publishers) { onOpenPublishers(null) } }
            item { LinkRow("Elders", s.elders) { onOpenLeaders() } }
            item { LinkRow("Ministerial Servants", s.servants) { onOpenLeaders() } }
            items(CIRCUIT_QUICK_CATEGORIES) { c -> LinkRow(c.displayName.lowercase().replaceFirstChar { it.uppercase() } + "s", s.counts[c]) { onOpenPublishers(c.name) } }
            item { Text("Territory", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
            item {
                CoButton("View Territory Map", { CircuitScopeStore.selectCongregation(congregationId); onOpenTerritory() }, icon = Icons.Rounded.Map, fillWidth = true)
            }
            item { Text("Reports", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
            item {
                CoButton("Field Service Report", { CircuitScopeStore.selectCongregation(congregationId); onOpenReports() }, icon = Icons.Rounded.Assessment, fillWidth = true)
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, count: Int, onClick: () -> Unit) {
    CoCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- Publisher records: every field of the Person record the Circuit Overseer may see (never the sign-in details) ----

/** List View card: every available field of the publisher, one labelled line each. */
@Composable
private fun PublisherDetailCard(row: CircuitPersonRow, index: Int, showCongregation: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(row.categoryLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            PUBLISHER_COLUMNS.filter { it.title !in setOf("No.", "Name", "Category") && (showCongregation || it.title != "Congregation") }.forEach { column ->
                val value = column.value(row, index)
                if (value != "—") Text("${column.title}: $value", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Table View: one row per publisher, every available field as a column; scrolls sideways, and the heading row stays pinned while scrolling down. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.publisherTableItems(
    rows: List<CircuitPersonRow>, showCongregation: Boolean, hScroll: androidx.compose.foundation.ScrollState,
) {
    val columns = PUBLISHER_COLUMNS.filter { showCongregation || it.title != "Congregation" }
    stickyHeader(key = "publisher-table-head") {
        val border = MaterialTheme.colorScheme.outlineVariant
        Row(modifier = Modifier.horizontalScroll(hScroll).background(MaterialTheme.colorScheme.primaryContainer)) {
            columns.forEach { c ->
                Text(
                    c.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(c.width.dp).border(0.5.dp, border).padding(6.dp),
                )
            }
        }
    }
    itemsIndexed(rows, key = { _, r -> "tbl-" + r.personId + r.congregationId }) { i, r ->
        val border = MaterialTheme.colorScheme.outlineVariant
        Row(
            modifier = Modifier.horizontalScroll(hScroll)
                .background(if (i % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            columns.forEach { c ->
                Text(
                    c.value(r, i), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(c.width.dp).border(0.5.dp, border).padding(6.dp),
                )
            }
        }
    }
}

/** Heading (Congregation / Coordinator / Code), totals and the Print-PDF / Excel buttons above the publisher records. */
@Composable
private fun PublisherSummary(
    sections: List<PublisherReportSection>,
    circuitLabel: String,
    totals: PublisherTotals,
    onPrint: () -> Unit,
    onExcel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val single = sections.singleOrNull()
            if (single != null) {
                Text("Congregation: ${single.heading.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Coordinator: ${single.heading.coordinator}", style = MaterialTheme.typography.bodyMedium)
                Text("Congregation Code: ${single.heading.code.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(circuitLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${sections.size} congregations — each is printed with its own heading and totals.", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Total Publishers: ${totals.total}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            totals.byCategory.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { (c, n) -> Text("${c.displayName}: $n", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                CoButton("Print / PDF", onPrint, kind = CoKind.Secondary, icon = Icons.Rounded.Print, enabled = totals.total > 0)
                CoButton("Excel", onExcel, kind = CoKind.Secondary, icon = Icons.Rounded.TableChart, enabled = totals.total > 0)
            }
        }
    }
}
