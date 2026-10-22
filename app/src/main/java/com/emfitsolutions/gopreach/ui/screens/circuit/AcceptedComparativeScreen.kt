package com.emfitsolutions.gopreach.ui.screens.circuit

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.emfitsolutions.gopreach.domain.ComparisonNarration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.ui.graphics.vector.ImageVector
import com.emfitsolutions.gopreach.domain.AttendanceComparative
import com.emfitsolutions.gopreach.domain.ComparisonSummary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.emfitsolutions.gopreach.ui.components.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.export.ComparativeReportData
import com.emfitsolutions.gopreach.data.export.ComparativeStatisticsExporter
import com.emfitsolutions.gopreach.domain.AcceptedComparative
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Metric
import com.emfitsolutions.gopreach.domain.AcceptedComparative.MonthState
import com.emfitsolutions.gopreach.domain.AcceptedComparative.Period
import com.emfitsolutions.gopreach.domain.ConsolidatedSheet
import com.emfitsolutions.gopreach.domain.ConsolidatedSheets
import androidx.compose.foundation.layout.Spacer
import com.emfitsolutions.gopreach.ui.components.co.CoCard
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.SimpleDropdown
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun monthAt(offsetFromNow: Int): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, offsetFromNow); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun nextMonth(month: Long): Long = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis

/** One period the Circuit Overseer is still choosing (either end may be missing). */
private data class PeriodDraft(val from: Long? = null, val to: Long? = null) {
    val complete: Boolean get() = from != null && to != null
    fun toPeriod() = Period(from!!, to!!)
}

private val periodColors = listOf(Color(0xFF1E88E5), Color(0xFFF2B705), Color(0xFF2E7D32), Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00897B))

private enum class ComparisonView(val label: String) { GRAPH("Graph"), LIST("List"), DETAILS("Details") }

/** The two kinds of comparison the Circuit Overseer can generate. */
private enum class CompareType(val label: String, val blurb: String, val icon: ImageVector) {
    FIELD_SERVICE("Field Service Report", "Publishers, pioneers, hours and Bible studies", Icons.Rounded.Assessment),
    ATTENDANCE("Circuit Attendance Report", "Midweek and Weekend meeting attendance", Icons.Rounded.HowToReg),
}

@Composable
private fun ComparisonTypeCards(selected: CompareType?, compact: Boolean, onSelect: (CompareType) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CompareType.entries.forEach { t ->
            val on = t == selected
            Surface(
                onClick = { onSelect(t) }, shape = RoundedCornerShape(16.dp),
                color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(t.icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (!compact) Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** First period = dark grey, second period = GoPreach's medium yellow (the same on screen and in the printed graph). */
private val firstBarColor = Color(0xFF3A3A3A)
private val secondBarColor = Color(0xFFF2B705)

/** The selected congregation and both reporting periods, prominent above every comparison. */
@Composable
private fun PeriodsHeader(congregation: String, first: String, second: String) {
    CoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(congregation, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(firstBarColor))
                Text("  $first", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(secondBarColor))
                Text("  $second", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** "▲ +6 (+5.0%)": increased = yellow, decreased = dark grey, unchanged = outlined, so the three read apart at a glance. */
@Composable
private fun ChangeChip(r: ComparisonSummary.Row) {
    val label = if (r.direction == ComparisonSummary.Direction.UNAVAILABLE) "No data" else r.changeText + (r.percent?.let { "  " + r.percentText } ?: "")
    val (container, content) = when (r.direction) {
        ComparisonSummary.Direction.INCREASED -> secondBarColor.copy(alpha = 0.35f) to MaterialTheme.colorScheme.onSurface
        ComparisonSummary.Direction.DECREASED -> firstBarColor to Color.White
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50), color = container,
        border = if (container == Color.Transparent) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1) }
}

@Composable
private fun BarLine(value: Double?, top: Double, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            if (value != null && top > 0.0) {
                Box(Modifier.fillMaxWidth((value / top).toFloat().coerceIn(0f, 1f).let { if (value > 0.0) it.coerceAtLeast(0.02f) else 0f }).fillMaxHeight().background(color))
            }
        }
        Text(text, Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

/** Graph view: one pair of bars per figure, each pair on its own scale starting at zero, with the real values written on them. */
@Composable
private fun ComparisonBarsView(summary: ComparisonSummary.Result) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        summary.rows.forEach { r ->
            val top = maxOf(r.first ?: 0.0, r.second ?: 0.0)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    ChangeChip(r)
                }
                BarLine(r.first, top, firstBarColor, r.firstText)
                BarLine(r.second, top, secondBarColor, r.secondText)
            }
        }
        summary.unavailableNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** List view: Metric | previous | current | change (with the percentage underneath) - compact enough for a phone, the same table that is printed. */
@Composable
private fun ComparisonListView(summary: ComparisonSummary.Result) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("Metric", Modifier.weight(1.5f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(summary.firstLabel, Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 2)
            Text(summary.secondLabel, Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 2)
            Text("Change", Modifier.weight(1.1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        HorizontalDivider()
        summary.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(r.firstText, Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                Text(r.secondText, Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                    Text(r.changeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    if (r.direction != ComparisonSummary.Direction.UNAVAILABLE) Text(r.percentText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        summary.unavailableNote?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The automatic Comparison Summary shown after every generated comparison (see [ComparisonSummary]). */
@Composable
private fun ComparisonSummaryView(summary: ComparisonSummary.Result) {
    CoCard(Modifier.fillMaxWidth(), accent = secondBarColor) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Comparison Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            summary.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * "Read Report": the generated comparison read aloud by the phone's own speech engine (see [ReportNarrator]), with Play / Pause / Resume / Stop /
 * Read Again, a speed choice, a clear state, and the same words as readable text. It lives only while the report is on screen, so changing the
 * type, congregation or periods (or leaving the screen) stops and releases the narration, and the next report gets its own text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadReportPanel(narrator: ReportNarrator, script: List<String>, label: String = "Read Report") {
    var open by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    val state = narrator.state
    val listenPref = org.koin.compose.koinInject<com.emfitsolutions.gopreach.data.repository.ListenButtonPreference>()
    // The Listen button always stays; the player under it opens and closes. The Publisher's voice choices (Settings, or in the player) apply to every player.
    val voiceGender by listenPref.gender.collectAsStateWithLifecycle()
    val languageChoice by listenPref.language.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(voiceGender, languageChoice) { narrator.applyPreferences(voiceGender, languageChoice.narration?.code) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            // Tapping Listen opens the player and starts reading; tapping it again closes the player (and stops the reading).
            onClick = { if (open) { narrator.stop(); open = false } else { open = true; narrator.play() } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = secondBarColor, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Headphones, contentDescription = null, modifier = Modifier.size(22.dp))
            Text("  " + label, fontWeight = FontWeight.Bold)
        }
        if (open) {
            CoCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (state == NarrationState.PLAYING || state == NarrationState.PAUSED) {
                            Text("${narrator.index + 1} of ${narrator.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = state != NarrationState.PLAYING && state != NarrationState.UNAVAILABLE, onClick = { narrator.play() }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)); Text(if (state == NarrationState.PAUSED) "  Resume" else "  Play")
                        }
                        OutlinedButton(enabled = state == NarrationState.PLAYING, onClick = { narrator.pause() }) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Pause")
                        }
                        OutlinedButton(enabled = state == NarrationState.PLAYING || state == NarrationState.PAUSED, onClick = { narrator.stop() }) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Stop")
                        }
                        OutlinedButton(enabled = state != NarrationState.UNAVAILABLE, onClick = { narrator.readAgain() }) {
                            Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Read Again")
                        }
                    }
                    // The Publisher's voice choices - the same ones as in Settings: male or female voice, and the language it reads in.
                    Text("Voice", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        com.emfitsolutions.gopreach.domain.VoiceGender.entries.forEach { g ->
                            FilterChip(selected = voiceGender == g, onClick = { listenPref.setGender(g) }, label = { Text(g.label) })
                        }
                    }
                    Text("Language", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        com.emfitsolutions.gopreach.domain.ListenLanguageChoice.entries.forEach { c ->
                            FilterChip(selected = languageChoice == c, onClick = { listenPref.setLanguage(c) }, label = { Text(c.label) })
                        }
                    }
                    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Speed", style = MaterialTheme.typography.labelLarge)
                        listOf(0.75f to "0.75x", 1.0f to "1x", 1.25f to "1.25x", 1.5f to "1.5x").forEach { (value, label) ->
                            FilterChip(selected = narrator.rate == value, onClick = { narrator.changeRate(value) }, label = { Text(label) })
                        }
                    }
                    narrator.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    narrator.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide text" else "Show text") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { narrator.stop(); open = false }) { Text("Hide player", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (showText) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            script.forEachIndexed { i, line ->
                                Text(
                                    line, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (state != NarrationState.STOPPED && i == narrator.index) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun periodLines(periods: List<Pair<String, Pair<Int, Int>>>, noun: String) =
    periods.map { (label, counts) -> label to "${counts.first} of ${counts.second} month${if (counts.second == 1) "" else "s"} $noun" }

private fun fieldServiceExport(
    congregationName: String, result: AcceptedComparative.Result, sheets: List<ConsolidatedSheet>, summary: ComparisonSummary.Result, view: ComparisonView,
    author: String, noun: String,
) = ComparativeReportData(
    congregationName = congregationName,
    periodA = "", periodB = "",
    generatedBy = author,
    generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
    calculationMode = "",
    comparison = summary.table(),
    monthly = if (view == ComparisonView.DETAILS) result.monthly else emptyList(),
    notes = emptyList(),
    title = "Field Service Report Comparison",
    periodLines = periodLines(result.periods.map { it.label to (it.available to it.expected) }, noun),
    comparisonHeading = "Comparison",
    monthlyHeading = "Monthly Breakdown",
    sheets = if (view == ComparisonView.DETAILS) sheets else emptyList(),
    summaryParagraphs = summary.paragraphs,
    graph = if (view == ComparisonView.GRAPH) summary.rows else emptyList(),
    footnotes = listOfNotNull(summary.unavailableNote),
)

private fun attendanceExport(congregationName: String, r: AttendanceComparative.Result, view: ComparisonView, author: String, noun: String, dayLabel: (Long) -> String) = ComparativeReportData(
    congregationName = congregationName,
    periodA = "", periodB = "",
    generatedBy = author,
    generatedAt = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date()),
    calculationMode = "",
    comparison = r.comparison,
    monthly = if (view == ComparisonView.DETAILS) r.monthly else emptyList(),
    extraTables = if (view == ComparisonView.DETAILS) r.periods.flatMap { AttendanceComparative.sheetTables(it, dayLabel) } else emptyList(),
    notes = emptyList(),
    title = "Circuit Attendance Report Comparison",
    periodLines = periodLines(r.periods.map { it.label to (it.available to it.expected) }, noun),
    comparisonHeading = "Comparison",
    monthlyHeading = "Monthly Breakdown",
    summaryParagraphs = r.summary.paragraphs,
    graph = if (view == ComparisonView.GRAPH) r.summary.rows else emptyList(),
    footnotes = listOfNotNull(r.summary.unavailableNote),
)

/**
 * Circuit Overseer -> Comparative Report: report type first (Field Service Report or Circuit Attendance Report), then the congregation, then
 * the two reporting periods. On Generate the whole report - actual period labels, month counts, snapshot coverage, figures, differences,
 * warnings, the automatic Comparison Summary, and what Print / PDF / Excel produce - is rebuilt from the ACCEPTED monthly snapshots of exactly
 * those periods. Changing the type, the congregation or any period discards the previous report at once, so nothing stale can be shown or exported.
 * See [AcceptedComparative], [AttendanceComparative] and [ComparisonSummary] for the calculation rules.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AcceptedComparativeScreen(
    currentPersonId: String,
    onBack: () -> Unit,
    onOpenSnapshot: (reportId: String) -> Unit,
    onOpenSubmittedReports: () -> Unit,
    /** Opens a month's frozen attendance snapshot (the month cards of the Details view). */
    onOpenAttendanceSnapshot: (reportId: String) -> Unit = {},
    /** True for a congregation's own admins: the same report and screen, limited to their congregation and to the reports they have SENT to the Circuit Overseer. */
    adminMode: Boolean = false,
    /** The admin's own congregation (locked); null for the Super-Admin, who chooses. Only used in [adminMode]. */
    adminCongregationId: String? = null,
    viewModel: AcceptedComparativeViewModel = koinViewModel(),
    people: CircuitPeopleViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val noun = if (adminMode) "sent" else "received"
    val author = if (adminMode) "Congregation Admin" else "Circuit Overseer"
    val adminCongregations by remember(adminMode, adminCongregationId) { viewModel.congregationsFor(adminCongregationId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val circuitCongregations by remember(currentPersonId, adminMode) {
        if (adminMode) kotlinx.coroutines.flow.flowOf(emptyList()) else people.congregations(currentPersonId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val congregations = if (adminMode) adminCongregations else circuitCongregations
    var adminPicked by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = if (adminMode) congregations.firstOrNull { it.id == (adminCongregationId ?: adminPicked) } else validCongregation(congregations, CircuitScopeStore.congregation)
    fun chooseCongregation(id: String?) { if (adminMode) adminPicked = id else CircuitScopeStore.selectCongregation(id) }
    val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val monthLabel = { m: Long -> monthFmt.format(Date(m)) }
    val dayFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val dayLabel = { d: Long -> dayFmt.format(Date(d)) }
    val monthOptions = remember { (0 downTo -59).map { monthAt(it) } } // this month back five years
    val thisMonth = remember { monthAt(0) }
    var typeName by rememberSaveable { mutableStateOf<String?>(null) }
    val type = typeName?.let { CompareType.valueOf(it) }

    Scaffold(
        circuitTab = com.emfitsolutions.gopreach.ui.components.CircuitTab.COMPARATIVE,
        topBar = {
            TopAppBar(
                title = { Text("Comparative Report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { if (!adminMode && type != null) TextButton(onClick = onOpenSubmittedReports) { Text("Submitted by congregations") } },
            )
        },
    ) { padding ->
        if (type == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("What do you want to compare?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (adminMode) "Choose the report type. Only the months you have sent to the Circuit Overseer are used." else "Choose the report type. Only the months you have marked as received are used.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ComparisonTypeCards(null, compact = false) { typeName = it.name }
            }
            return@Scaffold
        }
        if (selected == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.padding(16.dp)) { ComparisonTypeCards(type, compact = true) { typeName = it.name } }
                SelectCongregationPrompt(
                    congregations = congregations, onSelect = { chooseCongregation(it) }, modifier = Modifier.weight(1f),
                    hint = "Choose a congregation to compare its $noun reports.",
                )
            }
            return@Scaffold
        }

        // Everything below belongs to ONE congregation and ONE report type: choosing another starts from scratch.
        val drafts = remember(selected.id) { mutableStateListOf(PeriodDraft(), PeriodDraft()) }
        var generated by remember(selected.id, typeName) { mutableStateOf<List<Period>?>(null) }
        var viewName by rememberSaveable(selected.id) { mutableStateOf(ComparisonView.LIST.name) }
        val comparisonView = ComparisonView.valueOf(viewName)
        val months by remember(selected.id) { viewModel.months(selected.id, includeSent = adminMode) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val attendanceMonths by remember(selected.id) { viewModel.attendanceMonths(selected.id, includeSent = adminMode) }.collectAsStateWithLifecycle(initialValue = emptyList())

        val allComplete = drafts.all { it.complete }
        val problem = if (allComplete) {
            AcceptedComparative.validate(drafts.map { it.toPeriod() }, thisMonth) { AcceptedComparative.monthsOf(it, ::nextMonth).size }
        } else null
        // The report is derived from the generated periods and the accepted snapshots; any change to the selection clears it first.
        val result = if (type == CompareType.FIELD_SERVICE) generated?.let { AcceptedComparative.build(selected.name, it, months, ::nextMonth, monthLabel, noun) } else null
        val attendanceResult = if (type == CompareType.ATTENDANCE) generated?.let { AttendanceComparative.build(selected.name, it[0], it[1], attendanceMonths, ::nextMonth, monthLabel, noun) } else null
        // Always consolidated as the whole congregation: one sheet per chosen period, like the Field Service Report.
        val sheets = result?.periods?.map { ConsolidatedSheets.of(selected.name, it, monthLabel) }.orEmpty()
        val fieldServiceSummary = result?.let { ComparisonSummary.forFieldService(it, noun) }

        // The narration lives here, outside the scrolling list: scrolling the player off screen must never stop or release the speech.
        // Its text is rebuilt from the report on screen, and an empty text (the selection changed, no report) stops whatever was playing.
        val narrationScript = (fieldServiceSummary ?: attendanceResult?.summary)?.let { ComparisonNarration.script(type.label, selected.name, it) }.orEmpty()
        val narrator = remember { ReportNarrator(context, emptyList()) }
        LaunchedEffect(narrationScript) { narrator.load(narrationScript) }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) narrator.pause() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); narrator.shutdown() }
        }

        fun selectionChanged(block: () -> Unit) { generated = null; block() }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (adminMode && adminCongregationId != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("COMPARATIVE REPORT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            } else item { SelectedCongregationBar(selected.name, onChange = { chooseCongregation(null) }, title = "Comparative Report") }
            item { ComparisonTypeCards(type, compact = true) { selectionChanged { typeName = it.name } } }
            item {
                Text(
                    if (adminMode) "Choose the two reporting periods to compare. Only months you have sent to the Circuit Overseer are used." else "Choose the two reporting periods to compare. Only months whose report you have marked as received are used.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            itemsIndexed(drafts.toList()) { index, draft ->
                val tint = periodColors[index % periodColors.size]
                CoCard(Modifier.fillMaxWidth(), accent = tint) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(tint))
                            val title = if (draft.complete && draft.from!! <= draft.to!!) AcceptedComparative.periodLabel(draft.toPeriod(), monthLabel)
                            else if (index == 0) "First reporting period" else "Second reporting period"
                            Text("  $title", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        SimpleDropdown(
                            label = "From month",
                            selectedLabel = draft.from?.let(monthLabel) ?: "Select the first month",
                            options = monthOptions.map { it.toString() to monthLabel(it) },
                            onSelected = { picked ->
                                selectionChanged {
                                    val from = picked.toLong()
                                    drafts[index] = draft.copy(from = from, to = draft.to?.takeIf { it >= from })
                                }
                            },
                        )
                        SimpleDropdown(
                            label = "To month",
                            selectedLabel = draft.to?.let(monthLabel) ?: "Select the last month",
                            options = monthOptions.filter { draft.from == null || it >= draft.from }.map { it.toString() to monthLabel(it) },
                            onSelected = { picked -> selectionChanged { drafts[index] = draft.copy(to = picked.toLong()) } },
                        )
                        if (draft.complete && draft.from!! <= draft.to!!) {
                            val span = AcceptedComparative.monthsOf(draft.toPeriod(), ::nextMonth)
                            val accepted = span.count { m ->
                                if (type == CompareType.FIELD_SERVICE) months.any { it.month == m && it.state == MonthState.ACCEPTED }
                                else attendanceMonths.any { it.month == m && it.state == MonthState.ACCEPTED }
                            }
                            Text(
                                span.size.toString() + (if (span.size == 1) " month" else " months") + " - $noun reports: $accepted of ${span.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (accepted == span.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else if (!allComplete) Text("Choose the From and To months of both periods.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = allComplete && problem == null,
                        onClick = { generated = drafts.map { it.toPeriod() } },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Generate Comparison", fontWeight = FontWeight.Bold) }
                }
            }

            val summary = fieldServiceSummary ?: attendanceResult?.summary
            if (summary != null) {
                item { PeriodsHeader(selected.name, summary.firstLabel, summary.secondLabel) }
                item { ReadReportPanel(narrator, narrationScript) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComparisonView.entries.forEach { v -> FilterChip(selected = viewName == v.name, onClick = { viewName = v.name }, label = { Text(v.label) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fun exportNow() = if (result != null) fieldServiceExport(selected.name, result, sheets, summary, comparisonView, author, noun) else attendanceExport(selected.name, attendanceResult!!, comparisonView, author, noun, dayLabel)
                            OutlinedButton(onClick = { ComparativeStatisticsExporter.print(context, exportNow()) }) {
                                Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Print / PDF")
                            }
                            OutlinedButton(onClick = { ComparativeStatisticsExporter.shareExcel(context, exportNow()) }) {
                                Icon(Icons.Rounded.TableChart, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Excel")
                            }
                        }
                    }
                }
                when (comparisonView) {
                    ComparisonView.GRAPH -> item { ComparisonBarsView(summary) }
                    ComparisonView.LIST -> item { ComparisonListView(summary) }
                    ComparisonView.DETAILS -> {
                        if (result != null) {
                            itemsIndexed(sheets) { index, sheet -> SheetView(sheet, periodColors[index % periodColors.size]) }
                            itemsIndexed(result.periods) { index, p -> MonthlyPeriod(p, periodColors[index % periodColors.size], monthLabel, adminMode, onOpenSnapshot) }
                        } else if (attendanceResult != null) {
                            attendanceResult.periods.forEach { p ->
                                AttendanceComparative.sheetTables(p, dayLabel).forEach { (title, rows) ->
                                    item { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                                    item { ReportGrid(rows, firstColumn = 190.dp, column = 100.dp) }
                                }
                            }
                            itemsIndexed(attendanceResult.periods) { index, p -> AttendanceMonthlyPeriod(p, periodColors[index % periodColors.size], monthLabel, adminMode, onOpenAttendanceSnapshot) }
                        }
                    }
                }
                // The summary always closes the comparison, whichever view is open.
                item { ComparisonSummaryView(summary) }
            }
        }
    }
}




@Composable
private fun MonthlyPeriod(p: AcceptedComparative.PeriodResult, color: Color, monthLabel: (Long) -> String, adminModeLabel: Boolean, onOpenSnapshot: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Text(
                "  ${p.label} - ${p.expected} months, ${p.available} accepted",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            )
        }
        p.rows.forEach { row ->
            CoCard(Modifier.fillMaxWidth(), accent = if (row.accepted) null else MaterialTheme.colorScheme.error) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(monthLabel(row.month), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (row.accepted) (if (adminModeLabel) "Sent" else "Received") else row.input.state.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (row.accepted) {
                            fun v(m: Metric) = row.values[m]?.let { if (m == Metric.HOURS) AcceptedComparative.oneDecimal(it) else it.toLong().toString() } ?: "-"
                            Text(
                                "Publishers ${v(Metric.PUBLISHERS)} - RP ${v(Metric.REGULAR_PIONEERS)} - AP ${v(Metric.AUXILIARY_PIONEERS)} - Bible Studies ${v(Metric.BIBLE_STUDIES)} - Hours ${v(Metric.HOURS)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("No figures - this month is not counted as zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    row.input.report?.takeIf { row.accepted }?.let { r -> TextButton(onClick = { onOpenSnapshot(r.id) }) { Text("Open") } }
                }
            }
        }
    }
}

/** The Details view of an attendance comparison: one card per month of a period - what it has, and "Open" for the frozen snapshot behind it. */
@Composable
private fun AttendanceMonthlyPeriod(p: AttendanceComparative.PeriodResult, color: Color, monthLabel: (Long) -> String, adminModeLabel: Boolean, onOpenSnapshot: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Text("  ${p.label} - ${p.expected} months, ${p.available} with a report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        p.rows.forEach { row ->
            CoCard(Modifier.fillMaxWidth(), accent = if (row.accepted) null else MaterialTheme.colorScheme.error) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(monthLabel(row.month), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (row.accepted) (if (adminModeLabel) "Sent" else "Received") else row.input.state.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (row.accepted) {
                            fun avg(v: Double?) = v?.let { ComparisonSummary.number(it) } ?: "-"
                            Text(
                                "Midweek ${avg(row.midweekAverage)} (${row.midweekCount} meetings) - Weekend ${avg(row.weekendAverage)} (${row.weekendCount} meetings)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("No figures - this month is not counted as zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    row.input.snapshot?.takeIf { row.accepted }?.let { s -> TextButton(onClick = { onOpenSnapshot(s.id) }) { Text("Open") } }
                }
            }
        }
    }
}
/** A table of already-formatted text (first row = headings), scrolling sideways when it is wide. */
@Composable
private fun ReportGrid(rows: List<List<String>>, firstColumn: Dp = 190.dp, column: Dp = 110.dp) {
    if (rows.isEmpty()) return
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { r, cells ->
            Row {
                cells.forEachIndexed { c, text ->
                    val (cellBg, cellFg) = com.emfitsolutions.gopreach.ui.theme.cellColors(if (r == 0) Color(0xFFDDE6F2) else Color.Transparent)
                    Box(
                        Modifier.width(if (c == 0) firstColumn else column).height(34.dp)
                            .background(cellBg)
                            .border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()).padding(horizontal = 6.dp),
                        contentAlignment = if (c == 0) Alignment.CenterStart else Alignment.Center,
                    ) { Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal, color = cellFg, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * One bar per selected month, in chronological order, coloured by period. A month without an accepted snapshot has no bar (marked
 * "n/a"), never a zero-height one. With nothing to draw it says so instead of inventing values.
 */
@OptIn(ExperimentalLayoutApi::class)

private val peachFill = Color(0xFFFCE4D6)
private val blueFill = Color(0xFFBDD7EE)
private val greenFill = Color(0xFFC6EFCE)
private val totalFill = Color(0xFFFFFF00)

/**
 * One period as the Field Service Report sheet, consolidated as the whole congregation: Status and Publisher's Name, then No. of
 * Reports / Hours / Bible Studies under each status, Remarks, and the yellow Total row - the same layout the congregations use.
 */
@Composable
private fun SheetView(sheet: ConsolidatedSheet, color: Color) {
    val table = remember(sheet) { ConsolidatedSheets.toTable(sheet) }
    val rc = sheet.reportColumns.size
    val hc = sheet.hourColumns.size
    val numberWidth = 34.dp
    val statusWidth = 56.dp
    val nameWidth = 190.dp
    val cellWidth = 50.dp
    val remarksWidth = 200.dp
    val line = com.emfitsolutions.gopreach.ui.theme.cellBorder()
    fun widthOf(i: Int, last: Int): Dp = when {
        i == 0 -> numberWidth
        i == 1 -> statusWidth
        i == 2 -> nameWidth
        i == last -> remarksWidth
        else -> cellWidth
    }
    fun fillOf(i: Int): Color = when {
        i < 3 -> Color.Transparent
        i < 3 + rc -> peachFill
        i < 3 + rc + hc -> blueFill
        i < 3 + rc + hc + rc -> greenFill
        else -> Color.Transparent
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Text("  " + sheet.congregationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(sheet.reportTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text("Congregation: " + sheet.congregationName + "   -   FS Groups: Consolidated, all FS Groups", style = MaterialTheme.typography.bodySmall)
        Text(
            "Months with a report: ${sheet.monthsAccepted} of ${sheet.monthsExpected}",
            style = MaterialTheme.typography.bodySmall,
            color = if (sheet.monthsAccepted == sheet.monthsExpected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (sheet.rows.isEmpty()) {
            Text("No report in this period, so there are no publishers to list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val drawRow: @Composable (Int) -> Unit = { r ->
            val cells = table[r]
                val header = r == 0
                val total = r == table.size - 1
                Row {
                    cells.forEachIndexed { i, text ->
                        val last = cells.size - 1
                        val sheetFill = if (total && i >= 2) totalFill else if (header) fillOf(i) else Color.Transparent
                        val (cellBg, cellFg) = com.emfitsolutions.gopreach.ui.theme.cellColors(sheetFill)
                        Box(
                            Modifier.width(widthOf(i, last)).height(34.dp)
                                .background(cellBg)
                                .border(if (total) 1.5.dp else 0.5.dp, if (total) MaterialTheme.colorScheme.onSurface else line)
                                .padding(horizontal = 4.dp),
                            contentAlignment = if (i == 2 || i == last) Alignment.CenterStart else if (i < 2 && !header) Alignment.CenterEnd.takeIf { i == 0 } ?: Alignment.CenterStart else Alignment.Center,
                        ) {
                            Text(
                                if (header && i == 0) "" else text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (header || total) FontWeight.Bold else FontWeight.Normal,
                                color = cellFg, maxLines = 1,
                            )
                        }
                    }
                }
            }
        com.emfitsolutions.gopreach.ui.components.PinnedTable(
            rowCount = (table.size - 2).coerceAtLeast(0),
            header = {
                Column {
            // band: No. of Reports | Hours | Bible Studies
            Row {
                Spacer(Modifier.width(numberWidth + statusWidth + nameWidth))
                Box(Modifier.width(cellWidth * rc).height(34.dp).background(com.emfitsolutions.gopreach.ui.theme.cellColors(peachFill).first).border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()), contentAlignment = Alignment.Center) { Text("No. of Reports", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black) }
                if (hc > 0) Box(Modifier.width(cellWidth * hc).height(34.dp).background(com.emfitsolutions.gopreach.ui.theme.cellColors(blueFill).first).border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()), contentAlignment = Alignment.Center) { Text("Hours", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black) }
                Box(Modifier.width(cellWidth * rc).height(34.dp).background(com.emfitsolutions.gopreach.ui.theme.cellColors(greenFill).first).border(0.5.dp, com.emfitsolutions.gopreach.ui.theme.cellBorder()), contentAlignment = Alignment.Center) { Text("Bible Studies", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black) }
            }
                    drawRow(0)
                }
            },
            footer = if (table.size > 1) ({ drawRow(table.size - 1) }) else null,
        ) { n -> drawRow(n + 1) }
    }
}