package com.emfitsolutions.gopreach.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.model.isVisibleToPublisher
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PublisherVisibilitySettingsRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Who is looking at the dashboard — decides which records may appear. */
sealed interface RecentlyVisitedViewer {
    /** A Publisher: sees only what the Publisher modules would show them (own / unassigned / shared per the Admin's switches). */
    data class Publisher(val personId: String, val congregationId: String) : RecentlyVisitedViewer

    /** An Admin-track role: every record of [congregationId], the congregation its own scope (or the Super-Admin's selection) allows. */
    data class Admin(val congregationId: String) : RecentlyVisitedViewer
}

data class RecentlyVisitedItem(
    val person: InterestedPerson,
    /** The newest visit on record; null means no visit was ever recorded (the record's creation date is never used instead). */
    val lastVisit: Visit?,
    val publisherName: String?,
)

data class ActivityState(
    val recentlyVisited: List<RecentlyVisitedItem> = emptyList(),
    val followUpNeeded: List<RecentlyVisitedItem> = emptyList(),
)

@HiltViewModel
class RecentlyVisitedViewModel @Inject constructor(
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val visibilityRepository: PublisherVisibilitySettingsRepository,
    private val personRepository: PersonRepository,
) : ViewModel() {

    /** Keeps the local copy of every visit current while the sections are on screen. */
    fun syncVisits(): Flow<Unit> = visitRepository.startRemoteSyncAllForCongregationView()

    /**
     * Counts only — what the collapsed sections show. Same authorization and follow-up rule as [activity], but nothing
     * is sorted, no names are resolved and no cards are built; the full lists are only produced after "Show Records".
     * (recently visited, follow-up needed)
     */
    fun counts(viewer: RecentlyVisitedViewer, myRecordsOnly: Boolean): Flow<Pair<Int, Int>> {
        val congregationId = when (viewer) {
            is RecentlyVisitedViewer.Publisher -> viewer.congregationId
            is RecentlyVisitedViewer.Admin -> viewer.congregationId
        }
        return combine(
            interestedPersonRepository.observeAll(),
            visitRepository.observeAllVisits(),
            visibilityRepository.observeFor(congregationId),
        ) { people, visits, settings ->
            val ids = people.filter { person ->
                person.status == RecordStatus.ACTIVE && person.congregationId == congregationId &&
                    when (viewer) {
                        is RecentlyVisitedViewer.Publisher ->
                            person.isVisibleToPublisher(viewer.personId, viewer.congregationId, settings) &&
                                (!myRecordsOnly || person.publisherPersonId == viewer.personId)
                        is RecentlyVisitedViewer.Admin -> true
                    }
            }.mapTo(HashSet()) { it.id }
            val newest = HashMap<String, Long>()
            for (v in visits) if (v.interestedPersonId in ids) newest.merge(v.interestedPersonId, v.visitDate, ::maxOf)
            val cutoff = settings.followUpCutoff()
            newest.size to ids.count { (newest[it] ?: Long.MIN_VALUE) < cutoff }
        }
    }

    /**
     * Authorization comes first and is the same rule the modules use ([isVisibleToPublisher]); only then are visits
     * looked at, so a record the viewer may not open never reaches either list. [myRecordsOnly] (Publisher viewers)
     * narrows further to records assigned to that Publisher. Everything is recomputed whenever a visit, a record or the
     * follow-up setting changes, so recording a visit updates both lists at once.
     */
    fun activity(viewer: RecentlyVisitedViewer, myRecordsOnly: Boolean): Flow<ActivityState> {
        val congregationId = when (viewer) {
            is RecentlyVisitedViewer.Publisher -> viewer.congregationId
            is RecentlyVisitedViewer.Admin -> viewer.congregationId
        }
        return combine(
            interestedPersonRepository.observeAll(),
            visitRepository.observeAllVisits(),
            visibilityRepository.observeFor(congregationId),
            personRepository.observeAll(),
        ) { people, visits, settings, persons ->
            val authorized = people.filter { person ->
                person.status == RecordStatus.ACTIVE && person.congregationId == congregationId &&
                    when (viewer) {
                        is RecentlyVisitedViewer.Publisher ->
                            person.isVisibleToPublisher(viewer.personId, viewer.congregationId, settings) &&
                                (!myRecordsOnly || person.publisherPersonId == viewer.personId)
                        is RecentlyVisitedViewer.Admin -> true
                    }
            }
            val authorizedIds = authorized.mapTo(HashSet()) { it.id }
            val newestVisit = visits
                .filter { it.interestedPersonId in authorizedIds }
                .groupBy { it.interestedPersonId }
                .mapValues { (_, list) -> list.maxWith(compareBy<Visit> { it.visitDate }.thenBy { it.visitTime }.thenBy { it.createdAt }) }
            val names = persons.associate { it.id to it.fullName }
            val items = authorized.map { person ->
                RecentlyVisitedItem(person, newestVisit[person.id], person.publisherPersonId.takeIf { it.isNotBlank() }?.let { names[it] })
            }
            val cutoff = settings.followUpCutoff()
            ActivityState(
                recentlyVisited = items.filter { it.lastVisit != null }
                    .sortedWith(compareByDescending<RecentlyVisitedItem> { it.lastVisit!!.visitDate }.thenByDescending { it.lastVisit!!.visitTime }.thenByDescending { it.lastVisit!!.createdAt }),
                // No visit at all comes first, then the longest-overdue.
                followUpNeeded = items.filter { it.lastVisit == null || it.lastVisit.visitDate < cutoff }
                    .sortedBy { it.lastVisit?.visitDate ?: Long.MIN_VALUE },
            )
        }
    }
}

/** "Today", "1 day ago", "2 weeks ago", "3 months ago", "1 year ago" — calendar-based for months and years. */
fun relativeVisitTime(visitMillis: Long, now: Long = System.currentTimeMillis()): String {
    fun dayStart(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val from = dayStart(visitMillis)
    val to = dayStart(now)
    if (!from.before(to)) return "Today"
    val days = ((to.timeInMillis - from.timeInMillis) / 86_400_000L).toInt()
    if (days < 7) return if (days == 1) "1 day ago" else "$days days ago"
    var months = (to.get(Calendar.YEAR) - from.get(Calendar.YEAR)) * 12 + (to.get(Calendar.MONTH) - from.get(Calendar.MONTH))
    if (to.get(Calendar.DAY_OF_MONTH) < from.get(Calendar.DAY_OF_MONTH)) months -= 1
    return when {
        months < 1 -> (days / 7).let { if (it == 1) "1 week ago" else "$it weeks ago" }
        months < 12 -> if (months == 1) "1 month ago" else "$months months ago"
        else -> (months / 12).let { if (it == 1) "1 year ago" else "$it years ago" }
    }
}

private const val COLLAPSED_COUNT = 5

/**
 * The dashboard's "Recently Visited" and "Follow-up Needed" sections. Publisher viewers get a My Records / Show All
 * Records toggle (always starting on My Records); "all" still means only what that Publisher may see in their own
 * congregation. [viewer] null means a Super-Admin who has not picked a Congregation yet — nothing is loaded until
 * they do. Tapping a card only navigates ([onOpen]); the destination re-checks access, and an unassigned record is
 * never assigned by being opened.
 */
@Composable
fun DashboardActivitySections(
    viewer: RecentlyVisitedViewer?,
    onOpen: (RecentlyVisitedItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecentlyVisitedViewModel = hiltViewModel(),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (viewer == null) {
            Text("Recently Visited", style = MaterialTheme.typography.titleMedium)
            SelectCongregationPrompt(Modifier.fillMaxWidth())
            return@Column
        }
        LaunchedEffect(viewer) { viewModel.syncVisits().collect {} }
        ActivitySection(viewer, followUp = false, viewModel = viewModel, onOpen = onOpen)
        ActivitySection(viewer, followUp = true, viewModel = viewModel, onOpen = onOpen)
    }
}

@Composable
private fun ActivitySection(
    viewer: RecentlyVisitedViewer,
    followUp: Boolean,
    viewModel: RecentlyVisitedViewModel,
    onOpen: (RecentlyVisitedItem) -> Unit,
) {
    // Collapsed on every fresh composition (login, restart, coming back to the dashboard) — plain `remember`, never saved.
    var expandedSection by remember(viewer) { mutableStateOf(false) }
    var myRecordsOnly by remember(viewer) { mutableStateOf(viewer is RecentlyVisitedViewer.Publisher) }
    var showAll by remember(viewer, followUp, myRecordsOnly) { mutableStateOf(false) }

    // Collapsed: counts only. The record list is built (and its flow collected) only while expanded.
    var collapsedCount by remember(viewer, followUp, myRecordsOnly) { mutableStateOf(0) }
    if (!expandedSection) {
        val counts by remember(viewer, myRecordsOnly) { viewModel.counts(viewer, myRecordsOnly) }
            .collectAsStateWithLifecycle(initialValue = 0 to 0)
        collapsedCount = if (followUp) counts.second else counts.first
    }
    val state by remember(viewer, myRecordsOnly, expandedSection) {
        if (expandedSection) viewModel.activity(viewer, myRecordsOnly) else kotlinx.coroutines.flow.emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = ActivityState())
    val items = if (followUp) state.followUpNeeded else state.recentlyVisited
    val count = if (expandedSection) items.size else collapsedCount
    val title = if (followUp) "Follow-up Needed" else "Recently Visited"
    val shown = if (showAll) items else items.take(COLLAPSED_COUNT)

    Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(220)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (followUp) Icons.Rounded.Warning else Icons.Rounded.History,
                contentDescription = null,
                tint = if (followUp) Color(0xFFEF6C00) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text("$title ($count)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { expandedSection = !expandedSection }) {
                Text(if (expandedSection) "Hide Records" else "Show Records")
                Icon(
                    if (expandedSection) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (!expandedSection) return@Column
        if (viewer is RecentlyVisitedViewer.Publisher) {
            Text(
                if (myRecordsOnly) {
                    if (followUp) "These are records assigned to you that need follow-up." else "Your recently visited records."
                } else {
                    if (followUp) "All records you can see in your congregation that need follow-up." else "All recently visited records you can see in your congregation."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScopeToggle(myRecordsOnly = myRecordsOnly, onChange = { myRecordsOnly = it })
        }
        if (items.isEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (followUp) "No Follow-up Needed Records" else "No Recently Visited Records", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (followUp) "All visible records are currently up to date."
                        else "Recent Searching, Return Visit, and Bible Study activity will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            shown.forEach { item -> androidx.compose.runtime.key(item.person.id) { ActivityCard(item, followUp, onOpen) } }
            if (items.size > COLLAPSED_COUNT) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show fewer" else "Show all ${items.size}")
                }
            }
        }
    }
}

/** My Records | Show All Records — the selected side takes the primary colour. */
@Composable
private fun ScopeToggle(myRecordsOnly: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(true to "My Records", false to "Show All Records").forEach { (mine, label) ->
            val selected = myRecordsOnly == mine
            val container by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(180), label = "toggleBg")
            val content by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(180), label = "toggleFg")
            Box(
                modifier = Modifier.weight(1f).background(container, RoundedCornerShape(10.dp)).clickable { onChange(mine) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = content, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

private fun PipelineStage.cardIcon(): ImageVector = when (this) {
    PipelineStage.SEARCHING -> Icons.Rounded.Search
    PipelineStage.RETURN_VISIT -> Icons.Rounded.Autorenew
    PipelineStage.BIBLE_STUDY -> Icons.Rounded.MenuBook
}

private fun PipelineStage.cardColor(): Color = when (this) {
    PipelineStage.SEARCHING -> Color(0xFF00897B)
    PipelineStage.RETURN_VISIT -> Color(0xFF1E88E5)
    PipelineStage.BIBLE_STUDY -> Color(0xFF8E24AA)
}

private fun PipelineStage.cardLabel(): String = when (this) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

@Composable
private fun ActivityCard(item: RecentlyVisitedItem, followUp: Boolean, onOpen: (RecentlyVisitedItem) -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val accent = item.person.pipelineStage.cardColor()
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = visibleState, enter = fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 4 }) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().clickable { onOpen(item) },
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(38.dp).background(accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(item.person.pipelineStage.cardIcon(), contentDescription = item.person.pipelineStage.cardLabel(), tint = accent, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (followUp) {
                        Text("FOLLOW-UP NEEDED", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF6C00), fontWeight = FontWeight.Bold)
                    }
                    Text(item.person.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.person.pipelineStage.cardLabel(), style = MaterialTheme.typography.labelMedium, color = accent)
                    val location = listOfNotNull(item.person.barangay, item.person.cityMunicipality, item.person.province).filter { it.isNotBlank() }
                        .ifEmpty { listOf(item.person.address) }.filter { it.isNotBlank() }.joinToString(", ")
                    if (location.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.Place, contentDescription = "Location", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val last = item.lastVisit
                    if (last == null) {
                        Text("No Visit Recorded", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    } else {
                        val time = if (last.visitTime > 0L) " · ${timeFormat.format(Date(last.visitTime))}" else ""
                        Text("Last visit: ${relativeVisitTime(last.visitDate)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("${dateFormat.format(Date(last.visitDate))}$time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.publisherName == null) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFF43A047))
                        }
                        Text(
                            "Assigned To: ${item.publisherName ?: "Unassigned"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
