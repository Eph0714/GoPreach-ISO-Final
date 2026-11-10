package com.emfitsolutions.gopreach.ui.screens.ministry

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.model.DeletedRecord
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RecycleBinRepository
import com.emfitsolutions.gopreach.data.repository.RestoreResult
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.ui.components.ActionButton
import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One record in the Ministry Recycle Bin: a deleted one (kept whole in Deleted Records, visits included) or one marked inactive. */
data class RecycleEntry(
    val kind: String, // "deleted" | "inactive"
    /** The Deleted Records id for a deleted one, the record id for an inactive one. */
    val id: String,
    val name: String,
    val stage: PipelineStage,
    val province: String,
    val municipality: String,
    val barangay: String,
    val at: Long,
    val deletedBy: String,
    val owner: String,
    val contact: String,
    val address: String,
    val coordinates: String,
    val visits: Int?,
) {
    val typeLabel: String get() = when (stage) { PipelineStage.BIBLE_STUDY -> "Bible Study"; PipelineStage.RETURN_VISIT -> "Return Visit"; else -> "Searching" }
    val place: String get() = listOf(barangay, municipality, province).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "No address" }
}

private val MINISTRY_MODULE = "Return Visit / Bible Study"
private val MINISTRY_TYPES = setOf("Interested Person", "Return Visit", "Bible Study")

class MinistryRecycleBinViewModel(
    private val recycleBin: RecycleBinRepository,
    private val interestedPeople: InterestedPersonRepository,
    private val visits: VisitRepository,
    private val people: PersonRepository,
    private val audit: AuditLogRepository,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    private val deletedById = HashMap<String, DeletedRecord>()
    private val inactiveById = HashMap<String, InterestedPerson>()

    /** Deleted and inactive Ministry records of [congregationId] that this person may handle: their own, or any when [canHandleOthers]. */
    fun entries(congregationId: String, currentPersonId: String, canHandleOthers: Boolean): kotlinx.coroutines.flow.Flow<List<RecycleEntry>> =
        combine(recycleBin.observeAll(), interestedPeople.observeAll(), people.observeAll()) { deleted, records, persons ->
            val names = persons.associate { it.id to it.fullName }
            deletedById.clear(); inactiveById.clear()
            val out = ArrayList<RecycleEntry>()
            for (d in deleted) {
                if (d.module != MINISTRY_MODULE || d.recordType !in MINISTRY_TYPES || d.congregationId != congregationId) continue
                val items = recycleBin.itemsOf(d)
                val rec = items.firstOrNull { it.collectionPath == "interestedPeople" }?.let { runCatching { DocJson.decodeFromString(InterestedPerson.serializer(), it.json) }.getOrNull() }
                // Their own only: a deleted record shows to its owner or the person who deleted it; the congregation roles and FS Group officers ([canHandleOthers]) see the rest.
                // The server already refuses to send anyone else's; this keeps a copy left on the device by an earlier account from ever showing.
                val ownerId = rec?.publisherPersonId?.takeIf { it.isNotBlank() } ?: d.ownerPersonId
                if (!canHandleOthers && ownerId != currentPersonId && d.deletedByPersonId != currentPersonId) continue
                deletedById[d.id] = d
                out += entryOf("deleted", d.id, rec, d.label, d.deletedAt, d.deletedByName, names[rec?.publisherPersonId ?: d.ownerPersonId].orEmpty(), (items.size - 1).coerceAtLeast(0))
            }
            for (r in records) {
                if (r.status == RecordStatus.ACTIVE || r.congregationId != congregationId) continue
                if (!(canHandleOthers || r.publisherPersonId == currentPersonId)) continue
                inactiveById[r.id] = r
                out += entryOf("inactive", r.id, r, r.name, r.updatedAt, "", names[r.publisherPersonId].orEmpty(), null)
            }
            out.sortedByDescending { it.at }
        }

    private fun entryOf(kind: String, id: String, rec: InterestedPerson?, label: String, at: Long, by: String, owner: String, visitCount: Int?) = RecycleEntry(
        kind = kind, id = id, name = label.ifBlank { rec?.name.orEmpty() }, stage = rec?.pipelineStage ?: PipelineStage.SEARCHING,
        province = rec?.province.orEmpty(), municipality = rec?.cityMunicipality.orEmpty(), barangay = rec?.barangay.orEmpty(), at = at, deletedBy = by, owner = owner,
        contact = rec?.contact.orEmpty(), address = rec?.address.orEmpty(),
        coordinates = if (rec?.gpsLat != null && rec.gpsLng != null) "%.6f, %.6f".format(rec.gpsLat, rec.gpsLng) else "",
        visits = visitCount,
    )

    fun restore(entry: RecycleEntry, actorPersonId: String) {
        viewModelScope.launch {
            if (entry.kind == "deleted") {
                val d = deletedById[entry.id] ?: return@launch
                _message.value = when (val r = recycleBin.restore(d, actorPersonId)) {
                    RestoreResult.Restored -> "\"${entry.name}\" was restored to ${entry.typeLabel}."
                    is RestoreResult.Conflict -> r.message
                    is RestoreResult.Failed -> r.message
                }
            } else {
                val p = inactiveById[entry.id] ?: return@launch
                interestedPeople.save(p.copy(status = RecordStatus.ACTIVE))
                audit.log(actorPersonId = actorPersonId, action = "RESTORE_INACTIVE_INTERESTED_PERSON", targetType = "InterestedPerson", targetId = p.id, details = p.name)
                _message.value = "\"${entry.name}\" was restored to ${entry.typeLabel}."
            }
        }
    }

    fun deletePermanently(entry: RecycleEntry, actorPersonId: String) {
        viewModelScope.launch {
            if (entry.kind == "deleted") {
                val d = deletedById[entry.id] ?: return@launch
                recycleBin.permanentlyDelete(d, actorPersonId, automatic = false)
            } else {
                val p = inactiveById[entry.id] ?: return@launch
                visits.observeForInterestedPerson(p.id).first().forEach { visits.delete(p.id, it.id) }
                interestedPeople.delete(p.id)
                audit.log(actorPersonId = actorPersonId, action = "PERMANENT_DELETE_INTERESTED_PERSON", targetType = "InterestedPerson", targetId = p.id, details = p.name)
            }
            _message.value = "\"${entry.name}\" was deleted permanently."
        }
    }
}

private val ManilaZone: ZoneId = ZoneId.of("Asia/Manila")
private val WhenFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH).withZone(ManilaZone)
private fun whenOf(ms: Long) = if (ms <= 0) "an unknown date" else WhenFormat.format(Instant.ofEpochMilli(ms))
private fun norm(s: String) = s.lowercase().replace(Regex("\\b(city of|municipality of|city)\\b"), " ").replace(Regex("[^a-z0-9ñ]"), "")

private fun stageColor(stage: PipelineStage): Color = when (stage) {
    PipelineStage.RETURN_VISIT -> Color(0xFF2E7D32)
    PipelineStage.BIBLE_STUDY -> Color(0xFF1565C0)
    else -> Color(0xFF444444)
}

/**
 * The Ministry Recycle Bin: deleted and inactive Searching, Return Visit and Bible Study records in one place, filtered by name, type, date deleted, province,
 * municipality and barangay, each restorable (after a confirmation) or removable for good (after a stronger one). The same records and rules as the website.
 */
@Composable
fun MinistryRecycleBinScreen(
    congregationId: String,
    currentPersonId: String,
    canHandleOthers: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MinistryRecycleBinViewModel = koinViewModel(),
) {
    val entriesFlow = remember(congregationId, currentPersonId, canHandleOthers) { viewModel.entries(congregationId, currentPersonId, canHandleOthers) }
    val all by entriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val message by viewModel.message.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableStateOf(0) }
    var province by rememberSaveable { mutableStateOf<String?>(null) }
    var municipality by rememberSaveable { mutableStateOf<String?>(null) }
    var barangay by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<RecycleEntry?>(null) }
    var confirmPurge by remember { mutableStateOf<RecycleEntry?>(null) }

    val now = System.currentTimeMillis()
    val filtered = all.filter { e ->
        (query.isBlank() || norm(e.name).contains(norm(query))) &&
            (type == null || e.stage.name == type) &&
            (province == null || norm(e.province) == province) &&
            (municipality == null || norm(e.municipality) == municipality) &&
            (barangay == null || norm(e.barangay) == barangay) &&
            (days == 0 || (e.at > 0 && now - e.at <= days * 86_400_000L))
    }
    fun options(pick: (RecycleEntry) -> String) = all.map(pick).filter { it.isNotBlank() }.associateBy { norm(it) }.toSortedMap()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Ministry") }
            Column {
                Text("Recycle Bin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Restore a record, or delete it for good.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, placeholder = { Text("Search by person's name") },
        )
        // The record type as one row of four equal segments; everything else folded under "More filters" (with how many are on, and a Reset).
        val types = listOf<Pair<String?, String>>(null to "All", "SEARCHING" to "Searching", "RETURN_VISIT" to "Return Visit", "BIBLE_STUDY" to "Bible Study")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            types.forEachIndexed { index, (k, l) ->
                SegmentedButton(selected = type == k, onClick = { type = k }, shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size), icon = {}) {
                    Text(l, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
        }
        var showFilters by rememberSaveable { mutableStateOf(false) }
        val active = listOf(days != 0, province != null, municipality != null, barangay != null).count { it }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showFilters = !showFilters }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(14.dp)) {
                Text(if (active > 0) "More filters ($active)" else "More filters", modifier = Modifier.weight(1f), maxLines = 1)
                Icon(if (showFilters) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            if (active > 0) TextButton(onClick = { days = 0; province = null; municipality = null; barangay = null }) { Text("Reset") }
        }
        if (showFilters) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Any date", 1 to "Today", 7 to "Last 7 days", 30 to "Last 30 days").forEach { (d, l) ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlaceFilter("Province", province, options { it.province }, Modifier.weight(1f)) { province = it }
                PlaceFilter("Municipality", municipality, options { it.municipality }, Modifier.weight(1f)) { municipality = it }
                PlaceFilter("Barangay", barangay, options { it.barangay }, Modifier.weight(1f)) { barangay = it }
            }
        }
        Text("Record Found: ${filtered.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (filtered.isEmpty()) {
            Text(
                if (all.isEmpty()) "The Recycle Bin is empty." else "No deleted record matches these filters.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.kind + it.id }) { e -> RecycleCard(e, onRestore = { confirmRestore = e }, onPurge = { confirmPurge = e }) }
            }
        }
    }

    confirmRestore?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this record?") },
            text = { Text("Restore \"${e.name}\" to ${e.typeLabel}, with all its original information${if ((e.visits ?: 0) > 0) " and visit logs" else ""}?") },
            confirmButton = { ActionButton(ActionKind.Refresh, onClick = { confirmRestore = null; viewModel.restore(e, currentPersonId) }, label = "Restore") },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { confirmRestore = null }, label = "Cancel") },
        )
    }
    confirmPurge?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text("Delete Permanently?") },
            text = { Text("Are you sure you want to permanently delete this record? This action cannot be undone.") },
            confirmButton = { ActionButton(ActionKind.Delete, onClick = { confirmPurge = null; viewModel.deletePermanently(e, currentPersonId) }, label = "Delete Permanently") },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { confirmPurge = null }, label = "Cancel") },
        )
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = { viewModel.clearMessage() },
            title = { Text("Recycle Bin") },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { viewModel.clearMessage() }) { Text("OK") } },
        )
    }
}

@Composable
private fun PlaceFilter(label: String, selected: String?, options: Map<String, String>, modifier: Modifier, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            Text(selected?.let { options[it] } ?: label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { open = false; onSelect(null) })
            options.forEach { (key, shown) -> DropdownMenuItem(text = { Text(shown) }, onClick = { open = false; onSelect(key) }) }
        }
    }
}

@Composable
private fun RecycleCard(e: RecycleEntry, onRestore: () -> Unit, onPurge: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.name, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Tag(e.typeLabel, stageColor(e.stage))
                Tag(if (e.kind == "deleted") "Deleted" else "Inactive", Color(0xFF8A6D1D))
            }
            Text(e.place, style = MaterialTheme.typography.bodyMedium)
            Text((if (e.kind == "deleted") "Deleted " else "Marked inactive ") + whenOf(e.at) + (if (e.deletedBy.isNotBlank()) " by ${e.deletedBy}" else ""), style = MaterialTheme.typography.bodySmall)
            val extra = listOfNotNull(
                e.owner.takeIf { it.isNotBlank() }?.let { "Assigned publisher: $it" },
                e.contact.takeIf { it.isNotBlank() }?.let { "Contact: $it" },
                e.address.takeIf { it.isNotBlank() }?.let { "Place of origin: $it" },
                e.coordinates.takeIf { it.isNotBlank() }?.let { "Coordinates: $it" },
                e.visits?.takeIf { it > 0 }?.let { "Visit logs kept with it: $it" },
            )
            extra.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                ActionButton(ActionKind.Refresh, onClick = onRestore, label = "Restore", compact = true, modifier = Modifier.weight(0.8f))
                ActionButton(ActionKind.Delete, onClick = onPurge, label = "Delete Permanently", compact = true, modifier = Modifier.weight(1.2f))
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(modifier = Modifier.background(color, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
