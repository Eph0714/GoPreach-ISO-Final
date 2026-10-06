package com.emfitsolutions.gopreach.ui.screens.pipeline

import android.Manifest
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.Gender
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.SupportingImage
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.model.VisitOutcome
import com.emfitsolutions.gopreach.ui.components.CoordinatesValue
import com.emfitsolutions.gopreach.ui.components.ClickableCoordinatesText
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.DateOnlyField
import com.emfitsolutions.gopreach.ui.components.DateTimeField
import com.emfitsolutions.gopreach.ui.components.TimeOnlyField
import com.emfitsolutions.gopreach.ui.components.DeleteChoiceDialog
import com.emfitsolutions.gopreach.ui.components.EditSectionHeader
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.ManualCoordinatesDialog
import com.emfitsolutions.gopreach.ui.components.ReadOnlyField
import com.emfitsolutions.gopreach.ui.components.SupportingImageSection
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.DialogProperties

private fun PipelineStage.label(): String = when (this) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

/** "Searching Interested Person / Return Visit / Bible Study" — the full
 * spec wording for the reverse-status-movement confirmation dialog and
 * success message specifically, kept separate from [label] (used
 * everywhere else in this screen as the shorter "Searching") so this one
 * feature's exact required copy doesn't change any other screen's wording. */
private fun PipelineStage.fullLabel(): String = if (this == PipelineStage.SEARCHING) "Searching Interested Person" else label()

/** The single stage one step *forward* of [this] in the Searching → Return
 * Visit → Bible Study sequence, or null from Bible Study (the end of the
 * line). */
private fun PipelineStage.nextStage(): PipelineStage? = when (this) {
    PipelineStage.SEARCHING -> PipelineStage.RETURN_VISIT
    PipelineStage.RETURN_VISIT -> PipelineStage.BIBLE_STUDY
    PipelineStage.BIBLE_STUDY -> null
}

/** "Add Reverse Status Movement" — the single stage one step *backward* of
 * [this], or null from Searching (nothing precedes it). Only Return Visit ↔
 * Searching Interested Person and Bible Study ↔ Return Visit are ever
 * reachable in one move either direction — never Searching ↔ Bible Study
 * directly, in either direction. */
private fun PipelineStage.previousStage(): PipelineStage? = when (this) {
    PipelineStage.SEARCHING -> null
    PipelineStage.RETURN_VISIT -> PipelineStage.SEARCHING
    PipelineStage.BIBLE_STUDY -> PipelineStage.RETURN_VISIT
}

/** "Visited by" for a Return Visit, "Studied by" for a Bible Study (spec's
 * own wording for each module's Visit History). */
private fun PipelineStage.visitorLabel(): String = if (this == PipelineStage.BIBLE_STUDY) "Studied by" else "Visited by"

/**
 * "Redesign the Publisher Dashboard" spec — one screen for all three
 * pipeline stages (Searching / Return Visit / Bible Study), since a record at
 * any of them is the same [InterestedPerson] entity (see [PipelineStage]).
 * Only [stage] and the derived [PipelineStage.label]/[PipelineStage.visitorLabel]
 * differ what's shown: full create/edit fields + [MOVE TO RETURN VISIT] only
 * at Searching; [MOVE TO BIBLE STUDY] only at Return Visit; visit logging at
 * Return Visit/Bible Study. [FORWARD TO OTHER CONGREGATION] and [FORWARD TO
 * OTHER PUBLISHER] (same-congregation hand-off, accept/decline by the
 * target publisher, no Service Overseer step) are both available at every
 * stage, Searching included — the same transfer logic Return Visit/Bible
 * Study already had.
 */
@Composable
fun PipelineScreen(
    publisherPersonId: String,
    currentPersonId: String,
    congregationId: String,
    stage: PipelineStage,
    canPermanentlyDelete: Boolean,
    // "Only an administrator or explicitly authorized role may manage
    // another Publisher's Visit History" (spec §8) — every route reaching
    // this screen already scopes [publisherPersonId] to the signed-in
    // session's own records (see GoPreachNavGraph), so this is false at every
    // one of those call sites and only true for [SuperAdminInterestedRecordsScreen]'s
    // cross-congregation, cross-publisher view; kept as its own parameter
    // (default false) rather than re-deriving a role here, same pattern as
    // [canPermanentlyDelete].
    canManageAllVisitHistory: Boolean = false,
    // My Planner record rows deep-link straight to one person's detail
    // (see Destinations.returnVisitPerson/bibleStudyPerson). Back from that
    // detail returns to wherever the user came from (the planner), not to
    // this screen's list they never saw.
    initialPersonId: String? = null,
    onBack: () -> Unit,
    viewModel: PipelineViewModel = koinViewModel(),
) {
    var selectedPerson by remember { mutableStateOf<InterestedPerson?>(null) }
    var initialPersonResolved by remember { mutableStateOf(initialPersonId == null) }
    if (!initialPersonResolved) {
        val people by remember(publisherPersonId, congregationId, stage) { viewModel.visibleFor(publisherPersonId, congregationId, stage) }
            .collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(people) {
            val loaded = people ?: return@LaunchedEffect
            selectedPerson = loaded.firstOrNull { it.id == initialPersonId }
            initialPersonResolved = true
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val current = selectedPerson
    if (current == null) {
        PipelineListScreen(
            publisherPersonId = publisherPersonId,
            currentPersonId = currentPersonId,
            congregationId = congregationId,
            stage = stage,
            canPermanentlyDelete = canPermanentlyDelete,
            onBack = onBack,
            onOpenPerson = { selectedPerson = it },
            viewModel = viewModel,
        )
    } else {
        val congregationName by remember(current.congregationId) { viewModel.congregationName(current.congregationId) }.collectAsStateWithLifecycle(initialValue = null)
        PipelinePersonDetailScreen(
            person = current,
            currentPersonId = currentPersonId,
            congregationName = congregationName ?: "—",
            stage = stage,
            canManageAllVisitHistory = canManageAllVisitHistory,
            onBack = { if (initialPersonId != null && current.id == initialPersonId) onBack() else selectedPerson = null },
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PipelineListScreen(
    publisherPersonId: String,
    currentPersonId: String,
    congregationId: String,
    stage: PipelineStage,
    canPermanentlyDelete: Boolean,
    onBack: () -> Unit,
    onOpenPerson: (InterestedPerson) -> Unit,
    viewModel: PipelineViewModel,
) {
    val peopleFlow = remember(publisherPersonId, congregationId, stage) { viewModel.visibleFor(publisherPersonId, congregationId, stage) }
    val allPeople by peopleFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showInactive by remember { mutableStateOf(false) }
    val people = allPeople.filter { showInactive || it.status == RecordStatus.ACTIVE }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InterestedPerson?>(null) }
    val showToast = rememberActionToast()

    // Bug fix: surfaces a save failure (e.g. from a corrupt/oversized photo)
    // as a toast instead of the app silently closing — see
    // PipelineViewModel.errorEvents' doc comment.
    LaunchedEffect(Unit) { viewModel.errorEvents.collect { showToast(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stage.label()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            // "The Publisher can directly add from Return Visit and Bible
            // Study Module... use the same entity as the Searching Fields.
            // If it save under Return Visit Module then the status will be
            // automatically 'Return Visit'" — a new record can now be
            // created directly at whichever stage screen it's added from,
            // not just Searching; [PipelinePersonDialog] sets the new
            // record's initial [InterestedPerson.pipelineStage] to this
            // [stage] rather than always defaulting to Searching. A record
            // still gains further stages the normal way too ([MOVE TO
            // RETURN VISIT]/[MOVE TO BIBLE STUDY] on an existing record) —
            // this is an additional entry point, not a replacement for that
            // one.
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New ${stage.label()} Record")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showInactive, onCheckedChange = { showInactive = it })
                Text("Show Inactive")
            }
            if (people.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordFound(0)
                    Text("No records yet.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { RecordFound(people.size) }
                    items(people, key = { it.id }) { person ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onOpenPerson(person) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(person.name, style = MaterialTheme.typography.titleMedium)
                                    Text(person.address, style = MaterialTheme.typography.bodySmall)
                                    if (person.publisherPersonId != publisherPersonId) {
                                        val ownerName by remember(person.publisherPersonId) { viewModel.personName(person.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                                        Text(
                                            if (person.publisherPersonId.isBlank()) "Unassigned" else "Assigned to: ${ownerName ?: "—"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (person.status == RecordStatus.INACTIVE) {
                                        Text("Inactive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (person.pendingForwardRequestId != null) {
                                        ForwardStatusBadge(person = person, viewModel = viewModel)
                                    }
                                    if (person.pendingPublisherForwardRequestId != null) {
                                        PublisherForwardStatusBadge(person = person, viewModel = viewModel)
                                    }
                                }
                                if (person.publisherPersonId != publisherPersonId) {
                                    // Someone else's (or an unassigned) record: view / add visit only, never delete or reactivate.
                                } else if (person.status == RecordStatus.ACTIVE) {
                                    IconButton(onClick = { pendingDelete = person }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                                } else {
                                    IconButton(onClick = { viewModel.setStatus(person, RecordStatus.ACTIVE, currentPersonId); showToast("\"${person.name}\" reactivated.") }) {
                                        Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Reactivate")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        PipelinePersonDialog(
            existingPerson = null,
            publisherPersonId = publisherPersonId,
            congregationId = congregationId,
            currentPersonId = currentPersonId,
            stage = stage,
            onSave = { viewModel.save(it); showToast("Record added.") },
            onDismiss = { showCreateDialog = false },
            viewModel = viewModel,
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        DeleteChoiceDialog(
            recordLabel = toDelete.name,
            canPermanentlyDelete = canPermanentlyDelete,
            onDismiss = { pendingDelete = null },
            onMoveToInactive = { viewModel.setStatus(toDelete, RecordStatus.INACTIVE, currentPersonId) },
            onDeletePermanently = { viewModel.permanentlyDelete(toDelete, currentPersonId) },
        )
    }
}

@Composable
private fun ForwardStatusBadge(person: InterestedPerson, viewModel: PipelineViewModel) {
    val requestFlow = remember(person.id) { viewModel.forwardRequestFor(person) }
    val request by requestFlow.collectAsStateWithLifecycle(initialValue = null)
    val r = request ?: return
    val (text, color) = when (r.status) {
        ForwardRequestStatus.PENDING -> "Forward status: Pending (${r.toCongregationNameSnapshot})" to MaterialTheme.colorScheme.tertiary
        ForwardRequestStatus.ACCEPTED -> "Forward status: Accepted (${r.toCongregationNameSnapshot})" to MaterialTheme.colorScheme.primary
        ForwardRequestStatus.DECLINED -> "Forward status: Declined" to MaterialTheme.colorScheme.error
        ForwardRequestStatus.CANCELLED -> "Forward status: Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun PublisherForwardStatusBadge(person: InterestedPerson, viewModel: PipelineViewModel) {
    val requestFlow = remember(person.id) { viewModel.publisherForwardRequestFor(person) }
    val request by requestFlow.collectAsStateWithLifecycle(initialValue = null)
    val r = request ?: return
    val (text, color) = when (r.status) {
        ForwardRequestStatus.PENDING -> "Forward status: Pending (${r.toPublisherNameSnapshot})" to MaterialTheme.colorScheme.tertiary
        ForwardRequestStatus.ACCEPTED -> "Forward status: Accepted (${r.toPublisherNameSnapshot})" to MaterialTheme.colorScheme.primary
        ForwardRequestStatus.DECLINED -> "Forward status: Declined" to MaterialTheme.colorScheme.error
        ForwardRequestStatus.CANCELLED -> "Forward status: Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** The same create/edit form (spec's full Searching field list) for every
 * stage — spec: "the Publisher can directly add from Return Visit and Bible
 * Study Module... use the same entity as the Searching Fields." A brand-new
 * record ([existingPerson] null) is created with [PipelineStage] set to
 * whichever [stage] this dialog was opened from — "if it save under Return
 * Visit Module then the status will be automatically 'Return Visit'" — so
 * adding from the Bible Study screen skips straight to Bible Study without
 * a separate [MOVE TO...] step, same for Return Visit. Editing an existing
 * record ([existingPerson] non-null) never changes its stage here — [stage]
 * is unused in that path — a record's stage still only ever advances via
 * [MOVE TO RETURN VISIT]/[MOVE TO BIBLE STUDY] on [PipelinePersonDetailScreen]. */
@Composable
internal fun PipelinePersonDialog(
    existingPerson: InterestedPerson?,
    publisherPersonId: String,
    congregationId: String,
    currentPersonId: String,
    stage: PipelineStage,
    onSave: (InterestedPerson) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PipelineViewModel,
    /** Find Location hand-off: coordinates the form should open with on a
     * brand-new record. Held only as form state — nothing is written until
     * the user saves — so opening this form never creates a record by
     * itself. Ignored when editing ([existingPerson] non-null). */
    initialCoordinates: CoordinatesValue? = null,
    /** Non-null shows the "ASSIGN PUBLISHER" picker (an authorized non-
     * Publisher creating a record); null means the record is simply owned by
     * [publisherPersonId] as before, with no assignment step. */
    assignablePublishers: List<Person>? = null,
) {
    var name by remember { mutableStateOf(existingPerson?.name.orEmpty()) }
    var spouse by remember { mutableStateOf(existingPerson?.spouse.orEmpty()) }
    // "Place of Origin" — still stored in [InterestedPerson.address]; see that
    // field's doc comment. An older record whose only value lives in the
    // legacy separate placeOrigin field opens with that value instead of blank.
    var address by remember { mutableStateOf(existingPerson?.address.orEmpty().ifBlank { existingPerson?.placeOrigin.orEmpty() }) }
    var assignedPublisherId by remember { mutableStateOf(publisherPersonId) }
    var isSubmitted by remember { mutableStateOf(false) }
    var locationDetection by remember { mutableStateOf(LocationDetection.NONE) }
    var province by remember { mutableStateOf(existingPerson?.province) }
    var cityMunicipality by remember { mutableStateOf(existingPerson?.cityMunicipality) }
    var barangay by remember { mutableStateOf(existingPerson?.barangay) }
    var children by remember { mutableStateOf(existingPerson?.children.orEmpty()) }
    var religion by remember { mutableStateOf(existingPerson?.religion.orEmpty()) }
    var ageText by remember { mutableStateOf(existingPerson?.ageYears?.toString().orEmpty()) }
    var language by remember { mutableStateOf(existingPerson?.language.orEmpty()) }
    var literaturePlace by remember { mutableStateOf(existingPerson?.literaturePlace.orEmpty()) }
    var remarks by remember { mutableStateOf(existingPerson?.remarks.orEmpty()) }
    var notes by remember { mutableStateOf(existingPerson?.notes.orEmpty()) }
    var contact by remember { mutableStateOf(existingPerson?.contact.orEmpty()) }
    var gender by remember { mutableStateOf(existingPerson?.gender) }
    var image by remember { mutableStateOf(existingPerson?.primarySupportingImage) }
    val originalCoordinates = remember(existingPerson) {
        existingPerson?.takeIf { it.hasGpsLocation }?.let { CoordinatesValue(it.gpsLat!!, it.gpsLng!!, it.gpsAccuracy) }
    }
    var coordinates by remember { mutableStateOf(originalCoordinates ?: initialCoordinates.takeIf { existingPerson == null }) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // "It can be automatic if the publisher will capture the coordinates,
    // the system will automatically fill-up the City, Municipalities, Town
    // and barangay" — fires every time [coordinates] changes (a fresh
    // capture, a manual lat/lng entry, or an edit of either), skipping the
    // very first composition when it's just re-loading an existing record's
    // already-saved coordinates unchanged. Only overwrites a level the
    // reverse-geocode actually resolved, per resolveAddressLevels' own doc
    // comment — never clears a level the publisher already picked by hand.
    //
    // Province/Municipality/Barangay/coordinates are all optional — a failed
    // or unavailable lookup only changes [locationDetection] (which drives the
    // message under CURRENT ADDRESS), it never blocks saving.
    // Looks the Current Address up from [c] and fills Province, Municipality,
    // Barangay and City. Shared by the automatic fill (a new capture) and the
    // "Update the Current Address" button (the record's existing coordinates).
    suspend fun detectCurrentAddress(c: CoordinatesValue) {
        locationDetection = LocationDetection.RESOLVING
        val resolved = viewModel.resolveAddressLevels(c.lat, c.lng)
        if (resolved == null || (resolved.province == null && resolved.cityMunicipality == null && resolved.barangay == null)) {
            locationDetection = LocationDetection.FAILED
            return
        }
        resolved.province?.let { province = it }
        // A barangay left over from a different municipality would be wrong, so
        // when the municipality changes the barangay follows the new lookup
        // (blank if it couldn't be determined).
        if (resolved.cityMunicipality != null && resolved.cityMunicipality != cityMunicipality) barangay = resolved.barangay
        resolved.cityMunicipality?.let { cityMunicipality = it }
        resolved.barangay?.let { barangay = it }
        locationDetection = LocationDetection.DETECTED
    }

    LaunchedEffect(coordinates) {
        val c = coordinates
        if (c == null) {
            locationDetection = LocationDetection.NONE
            return@LaunchedEffect
        }
        if (c == originalCoordinates) return@LaunchedEffect
        detectCurrentAddress(c)
    }
    val addressScope = rememberCoroutineScope()

    fun submit() {
        if (isSubmitted) return
        val message = requiredFieldsMessage(
            "Name" to name.isNotBlank(),
            "Place of Origin" to address.isNotBlank(),
            "Gender" to (gender != null),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        isSubmitted = true
        val coordinatesChanged = coordinates != originalCoordinates
        val now = System.currentTimeMillis()
        val base = existingPerson ?: InterestedPerson(
            publisherPersonId = if (assignablePublishers != null) assignedPublisherId else publisherPersonId,
            congregationId = congregationId,
            createdAt = now,
            createdByPersonId = currentPersonId,
            // "If it save under Return Visit Module then the status will be
            // automatically in 'Return Visit'" — a brand-new record starts
            // at whichever stage this dialog was opened from, not always
            // Searching.
            pipelineStage = stage,
            stageEnteredAt = now,
        )
        onSave(
            base.copy(
                name = name.trim(),
                gender = gender,
                spouse = spouse.trim().ifBlank { null },
                address = address.trim(),
                province = province,
                cityMunicipality = cityMunicipality,
                barangay = barangay,
                children = children.trim().ifBlank { null },
                religion = religion.trim().ifBlank { null },
                ageYears = ageText.toIntOrNull(),
                language = language.trim().ifBlank { null },
                literaturePlace = literaturePlace.trim().ifBlank { null },
                remarks = remarks.trim().ifBlank { null },
                notes = notes.trim().ifBlank { null },
                contact = contact.trim().ifBlank { null },
                supportingImages = listOfNotNull(image),
                gpsLat = coordinates?.lat,
                gpsLng = coordinates?.lng,
                gpsAccuracy = coordinates?.accuracyMeters ?: existingPerson?.gpsAccuracy.takeIf { !coordinatesChanged },
                gpsCapturedAt = if (coordinatesChanged) coordinates?.let { now } else existingPerson?.gpsCapturedAt,
                gpsCapturedBy = if (coordinatesChanged) coordinates?.let { currentPersonId } else existingPerson?.gpsCapturedBy,
                gpsUpdatedAt = if (coordinatesChanged) coordinates?.let { now } else existingPerson?.gpsUpdatedAt,
            ),
        )
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existingPerson == null) "New ${stage.label()} Record" else "Edit Record",
        onConfirm = ::submit,
        confirmLabel = if (existingPerson == null) "Add" else "Save",
        errorMessage = errorMessage,
        maxContentHeight = 620.dp,
        hasUnsavedChanges = name != existingPerson?.name.orEmpty() || spouse != existingPerson?.spouse.orEmpty() ||
            address != existingPerson?.address.orEmpty() || province != existingPerson?.province ||
            cityMunicipality != existingPerson?.cityMunicipality || barangay != existingPerson?.barangay ||
            children != existingPerson?.children.orEmpty() || religion != existingPerson?.religion.orEmpty() ||
            ageText != existingPerson?.ageYears?.toString().orEmpty() ||
            language != existingPerson?.language.orEmpty() || literaturePlace != existingPerson?.literaturePlace.orEmpty() ||
            remarks != existingPerson?.remarks.orEmpty() || notes != existingPerson?.notes.orEmpty() || contact != existingPerson?.contact.orEmpty() ||
            gender != existingPerson?.gender || image != existingPerson?.primarySupportingImage ||
            coordinates != originalCoordinates || assignedPublisherId != publisherPersonId,
    ) {
                // "Move the capture coordinates in the upper part of the
                // enrollment" — captured (or manually entered) first, so the
                // City/Municipality/Barangay dropdowns just below already
                // have a best-effort fill-up by the time the publisher
                // reaches them (see the LaunchedEffect above this dialog's
                // submit() for the reverse-geocode-and-fill logic).
                EditSectionHeader("Coordinates")
                CoordinatesEditorField(coordinates = coordinates, onChange = { coordinates = it }, viewModel = viewModel)

                // "CURRENT ADDRESS" — grouped in one container, kept visibly
                // apart from PLACE OF ORIGIN below. Every level is optional.
                CurrentAddressGroup(
                    province = province,
                    cityMunicipality = cityMunicipality,
                    barangay = barangay,
                    detection = locationDetection,
                    // Editing a record that already has coordinates: re-derive the address from them.
                    onUpdateFromCoordinates = if (existingPerson != null) coordinates?.let { c -> { addressScope.launch { detectCurrentAddress(c) }; Unit } } else null,
                    onChanged = { p, c, b -> province = p; cityMunicipality = c; barangay = b },
                )

                EditSectionHeader("Personal Information")
                OutlinedTextField(value = name, onValueChange = { name = it.uppercase() }, label = { Text("Name") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                Row {
                    Gender.entries.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = gender == g, onClick = { gender = g })
                            Text(g.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                OutlinedTextField(value = spouse, onValueChange = { spouse = it.uppercase() }, label = { Text("Spouse (optional)") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                // "Add a dropdown for City, Municipalities, Town Barangay...
                // The publisher will browse manually, however it can be
                // automatic if the publisher will capture the coordinates" —
                // manual browsing lives in [CurrentAddressGroup] above; the
                // automatic half is the LaunchedEffect(coordinates) earlier
                // in this function, which reverse-geocodes whatever was
                // captured/entered in the Coordinates section and fills
                // those three in, still fully editable afterward.
                // GpsLocationSection on the record's own detail screen (used
                // to *update* an already-saved record's location later) does
                // the same auto-fill independently.
                EditSectionHeader("Place of Origin")
                OutlinedTextField(value = address, onValueChange = { address = it.uppercase() }, label = { Text("Place of Origin") }, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())

                EditSectionHeader("Other Details")
                OutlinedTextField(value = children, onValueChange = { children = it.uppercase() }, label = { Text("Children (optional)") }, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = religion, onValueChange = { religion = it.uppercase() }, label = { Text("Religion (optional)") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ageText, onValueChange = { ageText = it.filter { c -> c.isDigit() } }, label = { Text("Age (optional)") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = language, onValueChange = { language = it.uppercase() }, label = { Text("Language (optional)") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = literaturePlace, onValueChange = { literaturePlace = it.uppercase() }, label = { Text("Literature Place (optional)") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = remarks, onValueChange = { remarks = it }, label = { Text("Remarks (optional)") }, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") }, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("Contact (optional)") }, singleLine = true, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())

                EditSectionHeader("Supporting Information")
                SupportingImageSection(currentImage = image, onImageConfirmed = { image = it }, onClear = { image = null })

                // "ASSIGN PUBLISHER" — only for an authorized non-Publisher
                // creating a new record. Optional: left on "None" the record
                // is saved unassigned (and stays eligible for the House
                // Holder Assignment module). Never defaults to the creator.
                if (existingPerson == null && assignablePublishers != null) {
                    EditSectionHeader("ASSIGN PUBLISHER")
                    AssignPublisherPicker(
                        publishers = assignablePublishers,
                        selectedId = assignedPublisherId,
                        onSelected = { assignedPublisherId = it },
                    )
                }

                if (existingPerson != null) {
                    val createdByName by remember(existingPerson.createdByPersonId) { viewModel.personName(existingPerson.createdByPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                    val assignedName by remember(existingPerson.publisherPersonId) { viewModel.personName(existingPerson.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                    EditSectionHeader("System Information")
                    ReadOnlyField("Record ID", existingPerson.id)
                    ReadOnlyField("Status", existingPerson.status.name)
                    ReadOnlyField("Stage", existingPerson.pipelineStage.label())
                    ReadOnlyField("Date Created", formatRecordTimestamp(existingPerson.createdAt))
                    ReadOnlyField("Date Updated", formatRecordTimestamp(existingPerson.updatedAt))
                    ReadOnlyField("Created By", createdByName ?: existingPerson.createdByPersonId)
                    ReadOnlyField("Assigned Publisher", assignedName ?: existingPerson.publisherPersonId)
                }
    }
}

/** How far the automatic Current Address lookup got — drives the message under
 * CURRENT ADDRESS ([NONE] shows nothing, e.g. before any coordinates exist or
 * when just re-opening a saved record). */
private enum class LocationDetection { NONE, RESOLVING, DETECTED, FAILED }

/** The "CURRENT ADDRESS" container: Province / Municipality / Barangay in one
 * bordered card, plus the automatic-detection status line. All three levels
 * are optional and manually editable whether or not detection worked. */
@Composable
private fun CurrentAddressGroup(
    province: String?,
    cityMunicipality: String?,
    barangay: String?,
    detection: LocationDetection,
    onUpdateFromCoordinates: (() -> Unit)?,
    onChanged: (province: String?, cityMunicipality: String?, barangay: String?) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CURRENT ADDRESS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            when (detection) {
                LocationDetection.RESOLVING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Detecting location…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
                LocationDetection.DETECTED -> {
                    Text("✓ Location detected automatically", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (barangay.isNullOrBlank()) {
                        Text("Barangay could not be determined. You may enter it manually.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LocationDetection.FAILED -> Text(
                    "Location could not be determined. You may enter the Current Address manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LocationDetection.NONE -> Unit
            }
            if (onUpdateFromCoordinates != null) {
                OutlinedButton(
                    onClick = onUpdateFromCoordinates,
                    enabled = detection != LocationDetection.RESOLVING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Update the Current Address")
                }
            }
            com.emfitsolutions.gopreach.ui.components.PhilippineAddressPicker(
                province = province,
                cityMunicipality = cityMunicipality,
                barangay = barangay,
                onChanged = onChanged,
                allowManualEntry = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Single-select publisher dropdown for [PipelinePersonDialog]'s ASSIGN
 * PUBLISHER section; "None" clears the selection (blank id = unassigned). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignPublisherPicker(publishers: List<Person>, selectedId: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = publishers.firstOrNull { it.id == selectedId }?.fullName ?: "None (leave unassigned)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Assigned Publisher") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None (leave unassigned)") }, onClick = { onSelected(""); expanded = false })
            publishers.forEach { publisher ->
                DropdownMenuItem(text = { Text(publisher.fullName) }, onClick = { onSelected(publisher.id); expanded = false })
            }
        }
    }
    publishers.firstOrNull { it.id == selectedId }?.remarks?.trim()?.takeIf { it.isNotEmpty() }?.let {
        Text("Remarks: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (publishers.isEmpty()) {
        Text("No publishers available in this congregation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoordinatesEditorField(coordinates: CoordinatesValue?, onChange: (CoordinatesValue?) -> Unit, viewModel: PipelineViewModel) {
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    // A successful capture is applied straight away — no "Confirm" step. (The
    // Current Address is then detected from it by the form's own effect.)
    fun runCapture() {
        captureError = null
        isCapturing = true
        coroutineScope.launch {
            val result = viewModel.captureCurrentLocation()
            isCapturing = false
            if (result == null) captureError = "Could not get a GPS fix. Make sure location is turned on and try again, or enter the Current Address manually." else onChange(CoordinatesValue(result.lat, result.lng, result.accuracyMeters))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCapture() else captureError = "Location permission was denied, so coordinates could not be captured. You may enter the Current Address manually."
    }
    fun startCapture() {
        if (viewModel.hasLocationPermission()) runCapture() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    when {
        isCapturing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text("Getting current location…")
        }
        coordinates != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Latitude: ${"%.6f".format(coordinates.lat)}", style = MaterialTheme.typography.bodyMedium)
            Text("Longitude: ${"%.6f".format(coordinates.lng)}", style = MaterialTheme.typography.bodyMedium)
            if (coordinates.accuracyMeters != null) Text("Accuracy: ${coordinates.accuracyMeters.toInt()} meters", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { startCapture() }) { Text("Recapture Coordinates") }
                OutlinedButton(onClick = { onChange(null) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { startCapture() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Capture Coordinates")
            }
            OutlinedButton(onClick = { showManualEntry = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Enter Location Manually")
            }
        }
    }
    if (captureError != null) Text(captureError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (showManualEntry) {
        ManualCoordinatesDialog(initial = coordinates, onConfirm = { onChange(it); showManualEntry = false }, onDismiss = { showManualEntry = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PipelinePersonDetailScreen(
    person: InterestedPerson,
    currentPersonId: String,
    congregationName: String,
    stage: PipelineStage,
    canManageAllVisitHistory: Boolean = false,
    onBack: () -> Unit,
    viewModel: PipelineViewModel,
) {
    // Bug fix: startVisitSync() returns a cold Flow (a callbackFlow wrapping
    // the actual Firestore listener registration) — calling it without
    // collecting is a no-op, the listener never actually registers. Must be
    // .collect()ed to do anything.
    LaunchedEffect(person.id) { viewModel.startVisitSync(person.id).collect {} }
    val visitsFlow = remember(person.id) { viewModel.visitsFor(person.id) }
    val visits by visitsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val livePersonFlow = remember(person.id) {
        viewModel.observePerson(person.id).map { it ?: person }
    }
    val livePerson by livePersonFlow.collectAsStateWithLifecycle(initialValue = person)
    var showAddVisit by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showForwardToPublisherDialog by remember { mutableStateOf(false) }
    var selectedVisit by remember { mutableStateOf<Visit?>(null) }
    var pendingEditVisit by remember { mutableStateOf<Visit?>(null) }
    var pendingDeleteVisit by remember { mutableStateOf<Visit?>(null) }
    // "Add Reverse Status Movement" — a tapped Move-to button only *requests*
    // the change here; the actual advanceStage() call waits for the
    // confirmation dialog below (see [pendingStageChange]'s own AlertDialog).
    var pendingStageChange by remember { mutableStateOf<PipelineStage?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    // Spec §44 — Visit History rows show the recorded date and time
    // together, compactly, e.g. "Sep 23, 2026 · 5:55 PM".
    val visitDateTimeFormat = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }
    val sortedVisits = remember(visits) { visits.sortedByDescending { it.visitDate } }
    val createdByName by remember(livePerson.createdByPersonId) { viewModel.personName(livePerson.createdByPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val assignedPublisherName by remember(livePerson.publisherPersonId) { viewModel.personName(livePerson.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val forwardRequestFlow = remember(livePerson.pendingForwardRequestId) { viewModel.forwardRequestFor(livePerson) }
    val forwardRequest by forwardRequestFlow.collectAsStateWithLifecycle(initialValue = null)
    val publisherForwardRequestFlow = remember(livePerson.pendingPublisherForwardRequestId) { viewModel.publisherForwardRequestFor(livePerson) }
    val publisherForwardRequest by publisherForwardRequestFlow.collectAsStateWithLifecycle(initialValue = null)
    val showToast = rememberActionToast()

    // "Territory Map Return Visit Permissions" — opening this screen for a
    // record another Publisher owns (only possible via the Territory Map;
    // every other route into this screen already scopes to the caller's own
    // records) never grants edit/delete rights over the *parent* record
    // itself, only View + (for Return Visit) Add Visit History. A Bible
    // Study is stricter still: [canAddVisit] below excludes it entirely
    // unless [isOwner] — this permission is never shared with Bible Study,
    // per that spec's own explicit exclusion. [canManageAllVisitHistory] is
    // the pre-existing Super-Admin override (SuperAdminInterestedRecordsScreen)
    // and always wins regardless of stage/ownership. A hidden/disabled
    // button is never the real enforcement — see firestore.rules'
    // `interestedPeople`/`visits` rules for the actual one; this only keeps
    // the UI from offering an action the backend would reject anyway.
    val isOwner = currentPersonId == livePerson.publisherPersonId
    val canManageParent = isOwner || canManageAllVisitHistory
    val canAddVisit = when (stage) {
        PipelineStage.SEARCHING -> true
        PipelineStage.BIBLE_STUDY -> canManageParent
        PipelineStage.RETURN_VISIT -> true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(livePerson.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // "The Edit Return Visit button must not be available to
                    // Publisher B" — only the record's own owner (or the
                    // Super-Admin override) ever sees this, regardless of
                    // stage.
                    if (canManageParent) {
                        IconButton(onClick = { showEditDialog = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                    }
                },
            )
        },
        floatingActionButton = {
            // "Add Visit" only applies once there's an actual visit history to
            // keep — Searching has none yet (spec's own module description).
            if (canAddVisit) {
                FloatingActionButton(onClick = { showAddVisit = true }) { Icon(Icons.Rounded.Add, contentDescription = "Log Visit") }
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // "If necessary, display the parent record as: Return Visit —
            // View Only" — shown once, above every section, whenever the
            // signed-in Publisher isn't this record's owner.
            if (!canManageParent) {
                item {
                    Text(
                        "${stage.fullLabel()} — View Only",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                EditSectionHeader("Personal Information")
                Text("Name: ${livePerson.name}", style = MaterialTheme.typography.bodyMedium)
                // "House Holder Visit History" spec §5/§20 — the current
                // status shown prominently alongside the rest of the
                // personal information, using the exact same PipelineStage
                // this screen is already scoped to (never a second,
                // independently-tracked status value).
                Text("Status: ${stage.fullLabel()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                // "Assigned Publisher: Publisher A" — the record's owner,
                // shown to every viewer (Territory Map's whole point is
                // letting another Publisher see whose record this is), never
                // implied to change just because someone else opened it.
                Text("Assigned Publisher: ${assignedPublisherName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                val assignedRemarks by remember(livePerson.publisherPersonId) { viewModel.personRemarks(livePerson.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                assignedRemarks?.let { Text("Publisher Remarks: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Gender: ${livePerson.gender?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Spouse: ${livePerson.spouse ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                // "Although the filter is simplified... the record must
                // still contain and display: Province / Municipality/City /
                // Barangay / Complete Address" (spec §3) — the same
                // structured fields [address] already sits alongside, shown
                // explicitly here rather than only ever driving the address
                // picker/filters behind the scenes.
                Text("Place of Origin: ${livePerson.address.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                // Records saved before the "Address → Place of Origin" rename
                // may carry a separate legacy Place Origin value; keep it
                // visible rather than silently hiding stored data.
                livePerson.placeOrigin?.takeIf { it.isNotBlank() && it != livePerson.address }?.let {
                    Text("Previously Recorded Place Origin: $it", style = MaterialTheme.typography.bodyMedium)
                }
                Text("CURRENT ADDRESS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Text("Province: ${livePerson.province ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Municipality/City: ${livePerson.cityMunicipality ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Barangay: ${livePerson.barangay ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Children: ${livePerson.children ?: "—"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Religion: ${livePerson.religion ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Age: ${livePerson.ageYears ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Language: ${livePerson.language ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Literature Place: ${livePerson.literaturePlace ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Congregation: $congregationName", style = MaterialTheme.typography.bodyMedium)
                Text("Contact: ${livePerson.contact?.takeIf { it.isNotBlank() } ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Remarks: ${livePerson.remarks ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                SupportingImagePreview(livePerson.primarySupportingImage)
            }
            item {
                EditSectionHeader("Location", modifier = Modifier.padding(top = 16.dp))
                GpsLocationSection(person = livePerson, currentPersonId = currentPersonId, canEdit = canManageParent, viewModel = viewModel)
            }
            item {
                EditSectionHeader("Notes", modifier = Modifier.padding(top = 16.dp))
                Text(livePerson.notes ?: "No notes recorded.", style = MaterialTheme.typography.bodyMedium)
            }
            // Stage-advance / Forward actions — Searching module's spec:
            // "Every record saved were having a button next to their names
            // [MOVE TO RETURN VISIT MODULE, FORWARD TO OTHER CONGREGATION]".
            // "FORWARD TO OTHER CONGREGATION" (same [ForwardRequest] flow) is
            // offered at every stage; "FORWARD TO OTHER PUBLISHER" only at
            // Return Visit/Bible Study, per spec. Stacked full-width buttons
            // (not side-by-side) — two long labels in one Row used to overflow
            // past the screen edge on a normal phone width, which read as
            // "not presentable." "Territory Map Return Visit Permissions" —
            // every action here changes the *parent* record (status, owner,
            // congregation), so none of it is offered to a Publisher who
            // isn't this record's owner; they only ever get View + Add Visit
            // History (see [canAddVisit]/the FAB above).
            if (canManageParent) {
                item {
                    EditSectionHeader("Actions", modifier = Modifier.padding(top = 16.dp))
                    PipelineActionButtons(
                        stage = stage,
                        forwardRequest = forwardRequest,
                        publisherForwardRequest = publisherForwardRequest,
                        onAdvanceStage = { newStage -> pendingStageChange = newStage },
                        onShowForwardDialog = { showForwardDialog = true },
                        onShowForwardToPublisherDialog = { showForwardToPublisherDialog = true },
                        onCancelForward = {
                            viewModel.cancelForward(forwardRequest!!, currentPersonId)
                            showToast("Forward request cancelled.")
                        },
                        onCancelPublisherForward = {
                            viewModel.cancelPublisherForward(publisherForwardRequest!!, currentPersonId)
                            showToast("Forward request cancelled.")
                        },
                    )
                }
            }
            run {
                item { EditSectionHeader("System Information", modifier = Modifier.padding(top = 16.dp))
                    Text("Date Created: ${formatRecordTimestamp(livePerson.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Date Updated: ${formatRecordTimestamp(livePerson.updatedAt)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Created By: ${createdByName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                }
                item { EditSectionHeader("Visit History", modifier = Modifier.padding(top = 16.dp)) }
                if (sortedVisits.isEmpty()) {
                    item { Text("No visits logged yet.", style = MaterialTheme.typography.bodySmall) }
                }
                itemsIndexed(sortedVisits, key = { _, visit -> visit.id }) { index, visit ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { selectedVisit = visit }) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(visitDateTimeFormat.format(Date(visit.visitDate)), style = MaterialTheme.typography.titleSmall)
                                    if (index == 0) Text("  •  Latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(visit.outcome.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                                if (visit.topicDiscussed != null) Text("Remarks/Topic: ${visit.topicDiscussed}", style = MaterialTheme.typography.bodySmall)
                                val visitorName by remember(visit.publisherPersonId) { viewModel.personName(visit.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                                Text("${stage.visitorLabel()}: ${visitorName ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                val recordedByName by remember(visit.createdByPersonId) { viewModel.personName(visit.createdByPersonId) }.collectAsStateWithLifecycle(initialValue = null)
                                Text("Recorded by: ${recordedByName ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                if (visit.hasVisitLocation) {
                                    ClickableCoordinatesText(lat = visit.visitLat!!, lng = visit.visitLng!!, style = MaterialTheme.typography.bodySmall, prefix = "Coordinates: ")
                                } else {
                                    Text("Coordinates: Not available", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            // "Each Publisher may edit or delete only the Visit
                            // History entries that they personally created"
                            // (spec §8) for a Return Visit/Searching record —
                            // a hidden button isn't the real enforcement (see
                            // saveVisit/deleteVisit's own doc comments for the
                            // backstop check, and firestore.rules for the
                            // actual one), but another Publisher's entry shows
                            // no action controls at all rather than a
                            // disabled one, per spec §17. "Territory Map
                            // Return Visit Permissions" spec §3/§7 — a Bible
                            // Study has no such per-entry ownership: only that
                            // record's own enrolled Publisher may touch *any*
                            // of its visit history, regardless of who
                            // actually logged each entry.
                            val canManageThisVisit = canManageAllVisitHistory ||
                                if (stage == PipelineStage.BIBLE_STUDY) isOwner else visit.createdByPersonId == currentPersonId
                            if (canManageThisVisit) {
                                Row {
                                    IconButton(onClick = { pendingEditVisit = visit }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit visit") }
                                    IconButton(onClick = { pendingDeleteVisit = visit }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete visit") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedVisit?.let { visit ->
        // Same per-entry ownership rule as the row's own Edit/Delete icons
        // (see their own comment above): a Bible Study's entire visit
        // history is owner-only, a Return Visit's is per-entry-creator-only,
        // and the Super-Admin override always wins either way.
        val canManageThisVisit = canManageAllVisitHistory ||
            if (stage == PipelineStage.BIBLE_STUDY) isOwner else visit.createdByPersonId == currentPersonId
        VisitDetailDialog(
            visit = visit,
            stage = stage,
            dateFormat = dateFormat,
            canEdit = canManageThisVisit,
            onEdit = { selectedVisit = null; pendingEditVisit = visit },
            onDelete = { selectedVisit = null; pendingDeleteVisit = visit },
            onDismiss = { selectedVisit = null },
            viewModel = viewModel,
        )
    }

    if (showAddVisit) {
        AddVisitDialog(
            existingVisit = null,
            interestedPersonId = person.id,
            // "The new Return Visit history must automatically record:
            // Visited By: Publisher B" — the Publisher actually logging this
            // visit, never the parent record's own owner (that would be
            // [person.publisherPersonId], wrong for exactly the cross-
            // Publisher case this feature exists for).
            publisherPersonId = currentPersonId,
            currentPersonId = currentPersonId,
            stage = stage,
            onSave = {
                viewModel.saveVisit(it, currentPersonId, canManageAllVisitHistory, stage, livePerson.publisherPersonId)
                showToast("Visit logged.")
            },
            onDismiss = { showAddVisit = false },
        )
    }

    val toEditVisit = pendingEditVisit
    if (toEditVisit != null) {
        AddVisitDialog(
            existingVisit = toEditVisit,
            interestedPersonId = person.id,
            // Irrelevant for an edit — existingVisit != null preserves its
            // own already-stored publisherPersonId unchanged (see
            // AddVisitDialog's own submit()).
            publisherPersonId = toEditVisit.publisherPersonId,
            currentPersonId = currentPersonId,
            stage = stage,
            onSave = {
                viewModel.saveVisit(it, currentPersonId, canManageAllVisitHistory, stage, livePerson.publisherPersonId, existingVisit = toEditVisit)
                showToast("Visit updated.")
            },
            onDismiss = { pendingEditVisit = null },
        )
    }

    // "Delete (Permanently)" — deleteVisit has always been a real, hard
    // delete (Visit carries no RecordStatus/inactive concept to soft-delete
    // into); this was previously wired straight to the trash icon with no
    // confirmation at all, so a stray tap wiped a Visit History entry with
    // zero chance to back out. A confirmation now guards it, matching every
    // other permanent-delete flow in this app.
    val toDeleteVisit = pendingDeleteVisit
    if (toDeleteVisit != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDeleteVisit = null },
            title = { Text("Delete Visit?") },
            text = { Text("This will permanently delete the visit logged on ${dateFormat.format(Date(toDeleteVisit.visitDate))}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVisit(person.id, toDeleteVisit, currentPersonId, canManageAllVisitHistory, stage, livePerson.publisherPersonId)
                    showToast("Visit deleted.")
                    pendingDeleteVisit = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteVisit = null }) { Text("Cancel") } },
        )
    }

    // "Add Reverse Status Movement" — one confirmation dialog for every
    // stage move, forward or backward: "Move Bible Study to Return Visit?"
    // / "This record will be moved from Bible Study to Return Visit. Do you
    // want to continue?" for a forward move, and the spec's own "moved
    // back" wording for a backward one. Confirming reuses the exact same
    // advanceStage() the forward-only flow already used — see that
    // function's own doc comment for why nothing about the underlying
    // status-change/history/module-sync logic needed to change at all.
    val toStage = pendingStageChange
    if (toStage != null) {
        val movingBackward = toStage == livePerson.pipelineStage.previousStage()
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingStageChange = null },
            title = { Text("Move ${livePerson.pipelineStage.fullLabel()} to ${toStage.fullLabel()}?") },
            text = {
                Text(
                    if (movingBackward) {
                        "This record will be moved back from ${livePerson.pipelineStage.fullLabel()} to ${toStage.fullLabel()}. Do you want to continue?"
                    } else {
                        "This record will be moved from ${livePerson.pipelineStage.fullLabel()} to ${toStage.fullLabel()}. Do you want to continue?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val fromLabel = livePerson.pipelineStage.fullLabel()
                    viewModel.advanceStage(livePerson, toStage, currentPersonId)
                    showToast("Status successfully changed from $fromLabel to ${toStage.fullLabel()}.")
                    pendingStageChange = null
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { pendingStageChange = null }) { Text("Cancel") } },
        )
    }

    if (showEditDialog) {
        PipelinePersonDialog(
            existingPerson = livePerson,
            publisherPersonId = person.publisherPersonId,
            congregationId = livePerson.congregationId,
            currentPersonId = currentPersonId,
            // Irrelevant for an edit (existingPerson != null short-circuits
            // the new-record construction below) — passed only because
            // this stage-aware dialog now always needs one.
            stage = stage,
            onSave = { viewModel.save(it); showToast("Record saved.") },
            onDismiss = { showEditDialog = false },
            viewModel = viewModel,
        )
    }

    if (showForwardDialog) {
        ForwardToCongregationDialog(
            person = livePerson,
            ownCongregationName = congregationName,
            currentPersonId = currentPersonId,
            onDismiss = { showForwardDialog = false },
            viewModel = viewModel,
        )
    }

    if (showForwardToPublisherDialog) {
        ForwardToPublisherDialog(
            person = livePerson,
            currentPersonId = currentPersonId,
            onDismiss = { showForwardToPublisherDialog = false },
            viewModel = viewModel,
        )
    }
}

/** The Actions section's buttons — stacked full-width, each with a leading
 * icon and a mixed-case label, rather than the previous side-by-side
 * ALL-CAPS buttons ("FORWARD TO OTHER CONGREGATION" next to "FORWARD TO
 * OTHER PUBLISHER" in one Row) that overflowed past the screen edge on a
 * normal phone width. */
@Composable
private fun PipelineActionButtons(
    stage: PipelineStage,
    forwardRequest: ForwardRequest?,
    publisherForwardRequest: PublisherForwardRequest?,
    onAdvanceStage: (PipelineStage) -> Unit,
    onShowForwardDialog: () -> Unit,
    onShowForwardToPublisherDialog: () -> Unit,
    onCancelForward: () -> Unit,
    onCancelPublisherForward: () -> Unit,
) {
    val iconModifier = Modifier.size(18.dp).padding(end = 8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // "Add Reverse Status Movement" — Searching only ever offers the
        // forward move (nothing precedes it); Bible Study only the backward
        // one (nothing follows it); Return Visit is the one stage with
        // both, per the spec's own table:
        //   Searching Interested Person -> Return Visit
        //   Return Visit -> Searching Interested Person OR Bible Study
        //   Bible Study -> Return Visit
        // Never a direct Searching<->Bible Study jump — nextStage()/
        // previousStage() only ever return the immediately adjacent stage.
        val nextStage = stage.nextStage()
        val previousStage = stage.previousStage()
        if (nextStage != null) {
            Button(onClick = { onAdvanceStage(nextStage) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = iconModifier)
                Text("Move to ${nextStage.label()}")
            }
        }
        if (previousStage != null) {
            OutlinedButton(onClick = { onAdvanceStage(previousStage) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = iconModifier)
                Text("Move to ${previousStage.label()}")
            }
        }
        OutlinedButton(
            onClick = onShowForwardDialog,
            enabled = forwardRequest?.status != ForwardRequestStatus.PENDING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = iconModifier)
            Text("Forward to Other Congregation")
        }
        // "Use the same logic in transferring to other publisher in Return
        // Visit and Bible Study" — Searching now offers the exact same
        // "Forward to Other Publisher" flow (receiving publisher accepts/
        // declines, no Service Overseer step) Return Visit/Bible Study
        // already had; forwardToPublisher()/the receiving side's accept
        // flow were already entirely stage-agnostic (never reads or
        // assumes pipelineStage), so this was purely a UI gate, not a data-
        // layer change.
        OutlinedButton(
            onClick = onShowForwardToPublisherDialog,
            enabled = publisherForwardRequest?.status != ForwardRequestStatus.PENDING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Forward, contentDescription = null, modifier = iconModifier)
            Text("Forward to Other Publisher")
        }
        ForwardStatusLine(forwardRequest, onCancel = onCancelForward)
        PublisherForwardStatusLine(publisherForwardRequest, onCancel = onCancelPublisherForward)
    }
}

@Composable
private fun ForwardStatusLine(r: ForwardRequest?, onCancel: () -> Unit) {
    if (r == null) return
    Text(
        when (r.status) {
            ForwardRequestStatus.PENDING -> "Forward to congregation: Pending — sent to ${r.toCongregationNameSnapshot}"
            ForwardRequestStatus.ACCEPTED -> "Forward to congregation: Accepted by ${r.toCongregationNameSnapshot} — assigned to ${r.assignedToPublisherNameSnapshot ?: "—"}"
            ForwardRequestStatus.DECLINED -> "Forward to congregation: Declined by ${r.toCongregationNameSnapshot}"
            ForwardRequestStatus.CANCELLED -> "Forward to congregation: Cancelled"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    // "Add a cancel request to all transfer[s]... applicable if the process
    // is not yet accepted by the receiving user" — only offered while the
    // receiving Service Overseer hasn't acted on it yet.
    if (r.status == ForwardRequestStatus.PENDING) {
        TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) { Text("Cancel Request") }
    }
}

@Composable
private fun PublisherForwardStatusLine(r: PublisherForwardRequest?, onCancel: () -> Unit) {
    if (r == null) return
    Text(
        when (r.status) {
            ForwardRequestStatus.PENDING -> "Forward to publisher: Pending — sent to ${r.toPublisherNameSnapshot}"
            ForwardRequestStatus.ACCEPTED -> "Forward to publisher: Accepted by ${r.toPublisherNameSnapshot}"
            ForwardRequestStatus.DECLINED -> "Forward to publisher: Declined by ${r.toPublisherNameSnapshot}"
            ForwardRequestStatus.CANCELLED -> "Forward to publisher: Cancelled"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (r.status == ForwardRequestStatus.PENDING) {
        TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) { Text("Cancel Request") }
    }
}

/** "FORWARD TO OTHER CONGREGATION" spec flow — search field over
 * name/language, filtered client-side (the congregation list is already
 * app-wide mirrored, and never large enough to warrant a server-side query). */
@Composable
private fun ForwardToCongregationDialog(
    person: InterestedPerson,
    ownCongregationName: String,
    currentPersonId: String,
    onDismiss: () -> Unit,
    viewModel: PipelineViewModel,
) {
    val congregationsFlow = remember(person.congregationId) { viewModel.otherCongregations(person.congregationId) }
    val congregations by congregationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fromPublisherName by remember(person.publisherPersonId) { viewModel.personName(person.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Congregation?>(null) }
    val filtered = remember(congregations, query) {
        if (query.isBlank()) congregations
        else congregations.filter { c -> c.name.contains(query, ignoreCase = true) || c.languages.any { it.contains(query, ignoreCase = true) } }
    }
    val showToast = rememberActionToast()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val target = selected
        val message = requiredFieldsMessage("Congregation" to (target != null))
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.forward(person, target!!, ownCongregationName, fromPublisherName ?: "—", currentPersonId)
        showToast("Forward request sent to ${target.name}.")
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Forward to Other Congregation",
        onConfirm = ::submit,
        confirmLabel = "Send Request",
        errorMessage = errorMessage,
        maxContentHeight = 420.dp,
        hasUnsavedChanges = selected != null,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; selected = null },
            label = { Text("Search by congregation name or language") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.id }) { c ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selected = c; query = c.name },
                    colors = if (selected?.id == c.id) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium)
                        if (c.languages.isNotEmpty()) Text(c.languages.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (filtered.isEmpty()) item { Text("No matching congregation.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** "FORWARD TO OTHER PUBLISHER" spec flow — search field over publisher
 * name, scoped to the record's own congregation (this flow never crosses
 * congregations); the receiving publisher themselves accepts/declines it
 * (see [PublisherForwardRequestsScreen]), so there's no "assign to" step
 * here — picking who to send it to *is* the whole dialog. */
@Composable
private fun ForwardToPublisherDialog(
    person: InterestedPerson,
    currentPersonId: String,
    onDismiss: () -> Unit,
    viewModel: PipelineViewModel,
) {
    val publishersFlow = remember(person.congregationId, person.publisherPersonId) {
        viewModel.otherPublishers(person.congregationId, person.publisherPersonId)
    }
    val publishers by publishersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fromPublisherName by remember(person.publisherPersonId) { viewModel.personName(person.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Person?>(null) }
    val filtered = remember(publishers, query) {
        if (query.isBlank()) publishers else publishers.filter { it.fullName.contains(query, ignoreCase = true) }
    }
    val showToast = rememberActionToast()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val target = selected
        val message = requiredFieldsMessage("Publisher" to (target != null))
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.forwardToPublisher(person, target!!, fromPublisherName ?: "—", currentPersonId)
        showToast("Forward request sent to ${target.fullName}.")
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Forward to Other Publisher",
        onConfirm = ::submit,
        confirmLabel = "Send Request",
        errorMessage = errorMessage,
        maxContentHeight = 420.dp,
        hasUnsavedChanges = selected != null,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; selected = null },
            label = { Text("Search by publisher name") },
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.id }) { p ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { selected = p; query = p.fullName },
                    colors = if (selected?.id == p.id) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
                ) {
                    Text(p.fullName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
            }
            if (filtered.isEmpty()) item { Text("No other publisher available in your congregation.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SupportingImagePreview(image: SupportingImage?) {
    if (image == null || image.base64Jpeg.isBlank()) return
    val bitmap = remember(image.base64Jpeg) {
        runCatching {
            val bytes = android.util.Base64.decode(image.base64Jpeg, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Supporting image",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(160.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun GpsLocationSection(person: InterestedPerson, currentPersonId: String, canEdit: Boolean, viewModel: PipelineViewModel) {
    val coroutineScope = rememberCoroutineScope()
    var isCapturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    fun runCapture() {
        captureError = null
        isCapturing = true
        coroutineScope.launch {
            val result = viewModel.captureCurrentLocation()
            isCapturing = false
            // Saved straight away — no "Confirm" step.
            if (result == null) captureError = "Could not get a GPS fix. Make sure location is turned on and try again, or enter the Current Address manually." else {
                viewModel.saveGpsLocation(person, result.lat, result.lng, result.accuracyMeters, currentPersonId)
                showToast("Location saved.")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCapture() else captureError = "Location permission was denied, so coordinates could not be captured. You may enter the Current Address manually."
    }
    fun startCapture() {
        if (viewModel.hasLocationPermission()) runCapture() else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text("Interested Person Location", style = MaterialTheme.typography.titleMedium)
        when {
            isCapturing -> Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text("Getting current location…")
            }
            person.hasGpsLocation -> Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("GPS Location Captured", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
                    }
                    ClickableCoordinatesText(lat = person.gpsLat!!, lng = person.gpsLng!!, label = person.name.ifBlank { null })
                    if (person.gpsAccuracy != null) Text("Accuracy: ${person.gpsAccuracy!!.toInt()} meters", style = MaterialTheme.typography.bodyMedium)
                    if (person.gpsCapturedAt != null) Text("Captured: ${formatRecordTimestamp(person.gpsCapturedAt!!)}", style = MaterialTheme.typography.bodySmall)
                    // "Cannot Change the parent address/location" — a
                    // Publisher who doesn't own this record sees the
                    // captured location above, never these mutation controls.
                    if (canEdit) {
                        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { startCapture() }) { Text("Recapture Coordinates") }
                            OutlinedButton(onClick = { showClearConfirm = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear Location") }
                        }
                    }
                }
            }
            else -> Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (canEdit) "No location captured" else "No location captured yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canEdit) {
                    Button(onClick = { startCapture() }) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Capture Coordinates")
                    }
                    OutlinedButton(onClick = { showManualEntry = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Enter Location Manually")
                    }
                }
            }
        }
        if (captureError != null) Text(captureError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
    }

    if (showManualEntry) {
        ManualCoordinatesDialog(
            initial = person.takeIf { it.hasGpsLocation }?.let { CoordinatesValue(it.gpsLat!!, it.gpsLng!!, it.gpsAccuracy) },
            onConfirm = { value ->
                viewModel.saveGpsLocation(person, value.lat, value.lng, value.accuracyMeters, currentPersonId)
                showToast("Location saved.")
                showManualEntry = false
            },
            onDismiss = { showManualEntry = false },
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear GPS Location?") },
            text = { Text("This will remove the saved GPS coordinates from this record.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearGpsLocation(person, currentPersonId)
                        showToast("Location cleared.")
                        showClearConfirm = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVisitDialog(
    /** Non-null makes this an edit of an existing Visit History entry
     * (spec: "allow the publisher to Add, edit and delete... the Visit
     * History") — pre-fills every field from it and preserves its id/
     * createdAt/createdByPersonId on save, only ever changing the fields
     * this form actually edits. */
    existingVisit: Visit?,
    interestedPersonId: String,
    publisherPersonId: String,
    currentPersonId: String,
    stage: PipelineStage,
    onSave: (Visit) -> Unit,
    onDismiss: () -> Unit,
) {
    // "Visit Date"/"Visit Time" — two separate editable fields over one
    // underlying timestamp (spec §40/§47), defaulting to the device's
    // current date and time for a new entry (spec §41), or the existing
    // visit's own saved moment when editing (spec §49) — never a fixed
    // application date/time.
    var visitDateTime by remember { mutableStateOf(existingVisit?.visitDate ?: System.currentTimeMillis()) }
    var topic by remember { mutableStateOf(existingVisit?.topicDiscussed.orEmpty()) }
    var outcome by remember { mutableStateOf(existingVisit?.outcome ?: VisitOutcome.NOT_AT_HOME) }
    var followUpDate by remember { mutableStateOf(existingVisit?.followUpDate) }
    val initialVisitDateTime = remember { existingVisit?.visitDate ?: visitDateTime }

    fun submit() {
        val base = existingVisit ?: Visit(
            interestedPersonId = interestedPersonId,
            publisherPersonId = publisherPersonId,
            createdAt = System.currentTimeMillis(),
            createdByPersonId = currentPersonId,
        )
        onSave(
            base.copy(
                visitDate = visitDateTime,
                visitTime = visitDateTime,
                topicDiscussed = topic.trim().ifBlank { null },
                outcome = outcome,
                // "Time Consumed" is retired (spec §43) — never entered or
                // shown again, but an older entry's own already-stored value
                // (if any) is preserved rather than silently zeroed out.
                timeConsumedMinutes = existingVisit?.timeConsumedMinutes ?: 0,
                followUpDate = followUpDate,
            )
        )
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = if (existingVisit == null) "Log Visit" else "Edit Visit",
        onConfirm = ::submit,
        confirmLabel = if (existingVisit == null) "Save" else "Save Changes",
        maxContentHeight = 480.dp,
        hasUnsavedChanges = visitDateTime != initialVisitDateTime || topic != existingVisit?.topicDiscussed.orEmpty() ||
            outcome != (existingVisit?.outcome ?: VisitOutcome.NOT_AT_HOME) || followUpDate != existingVisit?.followUpDate,
    ) {
                DateOnlyField(label = "Visit Date", valueMillis = visitDateTime, onValueChange = { visitDateTime = it })
                TimeOnlyField(label = "Visit Time", valueMillis = visitDateTime, onValueChange = { visitDateTime = it })
                OutlinedTextField(value = topic, onValueChange = { topic = it.uppercase() }, label = { Text("Remarks / Topic Discussed (optional)") }, visualTransformation = VisualTransformation.None, modifier = Modifier.fillMaxWidth())
                VisitOutcomeDropdown(selected = outcome, onSelected = { outcome = it })
                DateTimeField(label = "Follow-up Date (optional)", valueMillis = followUpDate, onValueChange = { followUpDate = it })
                if (followUpDate != null) TextButton(onClick = { followUpDate = null }) { Text("Clear Follow-up Date") }
    }
}

@Composable
private fun VisitDetailDialog(
    visit: Visit,
    stage: PipelineStage,
    dateFormat: SimpleDateFormat,
    // Whether the signed-in Publisher may edit/delete this specific visit —
    // same rule the row's own Edit/Delete icons already use (see the call
    // site); false hides both actions here too, a disabled button is never
    // the real enforcement (see saveVisit/deleteVisit's own doc comments for
    // the backstop check, firestore.rules for the actual one).
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: PipelineViewModel,
) {
    val visitorName by remember(visit.publisherPersonId) { viewModel.personName(visit.publisherPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val recordedByName by remember(visit.createdByPersonId) { viewModel.personName(visit.createdByPersonId) }.collectAsStateWithLifecycle(initialValue = null)
    val visitDateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val visitTimeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Visit Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Spec §44 — Visit Date and Visit Time shown as their own
                // separate lines (matching the two separate fields they're
                // edited as), even though both read from the one
                // [Visit.visitDate] timestamp. Time Consumed is retired
                // (spec §43) — never shown here or anywhere else.
                ReadOnlyField("Visit Date", visitDateFormat.format(Date(visit.visitDate)))
                ReadOnlyField("Visit Time", visitTimeFormat.format(Date(visit.visitDate)))
                ReadOnlyField("Status", visit.outcome.name.replace('_', ' '))
                ReadOnlyField("Remarks / Topic Discussed", visit.topicDiscussed ?: "—")
                ReadOnlyField(stage.visitorLabel(), visitorName ?: "—")
                ReadOnlyField("Follow-up Date", visit.followUpDate?.let { dateFormat.format(Date(it)) } ?: "—")
                // "House Holder Visit History" spec §7 — this visit's own
                // coordinates, clickable (same geo: intent every other
                // saved-coordinate display in this app already uses) when
                // present, plain "Not available" text when not — never the
                // parent record's own current GPS.
                if (visit.hasVisitLocation) {
                    Column {
                        Text("Coordinates", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ClickableCoordinatesText(lat = visit.visitLat!!, lng = visit.visitLng!!, prefix = "")
                    }
                } else {
                    ReadOnlyField("Coordinates", "Not available")
                }
                ReadOnlyField("Recorded By", recordedByName ?: "—")
                ReadOnlyField("Logged", formatRecordTimestamp(visit.createdAt))
            }
        },
        confirmButton = {
            Row {
                if (canEdit) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onEdit) { Text("Edit") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisitOutcomeDropdown(selected: VisitOutcome, onSelected: (VisitOutcome) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name.replace('_', ' '),
            onValueChange = {},
            readOnly = true,
            label = { Text("Status") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VisitOutcome.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.name.replace('_', ' ')) }, onClick = { onSelected(s); expanded = false })
            }
        }
    }
}
