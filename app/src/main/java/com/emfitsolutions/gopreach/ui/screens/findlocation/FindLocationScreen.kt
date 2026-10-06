package com.emfitsolutions.gopreach.ui.screens.findlocation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.location.formatCoordinatesDms
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.SavedLocation
import com.emfitsolutions.gopreach.ui.components.CoordinatesValue
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelinePersonDialog
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineViewModel
import com.emfitsolutions.gopreach.ui.components.RoundIconActionButton
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties


/**
 * "Find Location" — a Publisher types in a destination's GPS coordinates by
 * hand, and picks a travel mode (walking, car, bicycle, or public transit) to
 * jump straight into Google Maps' own turn-by-turn navigation for the
 * fastest route there, starting from wherever the device currently is.
 *
 * There's no bundled Google Directions API key in this app, so the actual
 * route calculation/"fastest route" comparison across modes is deliberately
 * handed off to the Google Maps app (or its web fallback) already on the
 * device, the same way [com.emfitsolutions.gopreach.ui.screens.sharelocation
 * .ShareLocationScreen] already opens a bare `geo:` intent rather than
 * drawing its own map.
 */
/**
 * Who is using Find Location, as far as enrolling a found coordinate into a
 * Searching / Return Visit / Bible Study record goes.
 *
 * - [isPublisher]: the active role is Publisher — records they enroll are
 *   automatically assigned to themselves.
 * - Any other role with a congregation ([congregationId], plus [groupId] for
 *   a Regular Elder scoped to one Group) creates the record as themselves and
 *   then assigns it to a publisher of that congregation via ASSIGN PUBLISHER.
 * - [isSuperAdmin] has no fixed congregation, so picks one first.
 * - Anyone else (e.g. a Circuit Overseer/custom-grant user with no
 *   congregation of their own) can still find and look around, but isn't
 *   offered enrollment — same "no access outside your existing permissions"
 *   rule the rest of the app follows.
 */
data class FindLocationEnrollmentAccess(
    val isPublisher: Boolean,
    val isSuperAdmin: Boolean,
    val congregationId: String?,
    val groupId: String? = null,
) {
    val canEnroll: Boolean get() = isSuperAdmin || !congregationId.isNullOrBlank()
}

/** A pending "Enroll to …" action: the stage chosen, the coordinate that was
 * on screen at that moment (so editing the search box afterwards can't shift
 * it), and — for Super-Admin — the congregation, filled in after their pick.
 * Purely form state: nothing is stored until the enrollment form is saved. */
private data class EnrollRequest(val stage: PipelineStage, val lat: Double, val lng: Double, val congregationId: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindLocationScreen(
    currentPersonId: String,
    enrollmentAccess: FindLocationEnrollmentAccess,
    onLookAround: (lat: Double, lng: Double) -> Unit,
    onBack: () -> Unit,
    viewModel: FindLocationViewModel = hiltViewModel(),
    pipelineViewModel: PipelineViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val showToast = rememberActionToast()
    val coroutineScope = rememberCoroutineScope()
    var enrollRequest by remember { mutableStateOf<EnrollRequest?>(null) }
    // Super-Admin only: the stage they tapped, waiting on a congregation pick.
    var pendingSuperAdminStage by remember { mutableStateOf<PipelineStage?>(null) }
    LaunchedEffect(Unit) { pipelineViewModel.errorEvents.collect { showToast(it) } }
    // "The textbox will search a coordinates or address, not just the
    // latitude and longitude" — one field that accepts either
    // "14.5995, 120.9842" or free-text like "Rizal Park, Manila".
    var coordinatesText by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showAssignDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedLocation?>(null) }

    val savedLocationsFlow = remember(currentPersonId) { viewModel.savedLocationsFor(currentPersonId) }
    val savedLocations by savedLocationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    fun submit() {
        val query = coordinatesText.trim()
        if (query.isBlank()) {
            errorText = "Enter coordinates or an address."
            return
        }
        // Try "lat, lng" first — same validated parse as before — and only
        // fall back to geocoding the whole text as an address if it isn't
        // that shape, so a well-formed coordinate pair never takes a
        // network round-trip it doesn't need.
        val parts = query.split(",").map { it.trim() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.toDoubleOrNull()
        if (parts.size == 2 && lat != null && lng != null) {
            errorText = when {
                lat < -90.0 || lat > 90.0 -> "Latitude must be between -90 and 90."
                lng < -180.0 || lng > 180.0 -> "Longitude must be between -180 and 180."
                else -> null
            }
            destination = if (errorText == null) lat to lng else null
            return
        }
        isSearching = true
        coroutineScope.launch {
            val found = viewModel.geocode(query)
            isSearching = false
            if (found != null) {
                destination = found.lat to found.lng
                errorText = null
            } else {
                destination = null
                errorText = "No location found for \"$query\". Check the spelling, or enter GPS coordinates instead."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Location") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Enter the GPS coordinates or an address of where you want to go, then pick how you're getting there to open the fastest route.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = coordinatesText,
                onValueChange = { coordinatesText = it; errorText = null },
                label = { Text("Coordinates or Address") },
                placeholder = { Text("e.g. 14.5995, 120.9842 or Rizal Park, Manila") },
                singleLine = true,
                enabled = !isSearching,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
            if (errorText != null) {
                Text(errorText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = ::submit, enabled = !isSearching, modifier = Modifier.fillMaxWidth()) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(if (isSearching) "SEARCHING…" else "FIND ROUTE")
            }

            destination?.let { (lat, lng) ->
                val address by produceState<String?>(initialValue = null, lat, lng) {
                    value = viewModel.addressFor(lat, lng)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "LOCATION FOUND",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        Text("Latitude: ${"%.6f".format(lat)}", style = MaterialTheme.typography.bodyMedium)
                        Text("Longitude: ${"%.6f".format(lng)}", style = MaterialTheme.typography.bodyMedium)
                        Text(formatCoordinatesDms(lat, lng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Location: ${address ?: "Resolving address…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LocationPreviewMap(lat = lat, lng = lng, modifier = Modifier.padding(vertical = 8.dp))
                        // "Enroll to …" opens the existing enrollment form with
                        // this coordinate (and its detected Current Address)
                        // already filled in; "Look Around" only views the
                        // spot and never creates anything. Enrollment isn't
                        // offered to roles with no congregation to enroll
                        // into (see [FindLocationEnrollmentAccess]).
                        if (enrollmentAccess.canEnroll) {
                            PipelineStage.entries.forEach { stage ->
                                Button(
                                    onClick = {
                                        if (enrollmentAccess.isSuperAdmin) {
                                            pendingSuperAdminStage = stage
                                        } else {
                                            enrollRequest = EnrollRequest(stage, lat, lng, enrollmentAccess.congregationId)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stage.enrollLabel()) }
                            }
                        }
                        OutlinedButton(onClick = { onLookAround(lat, lng) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Look Around")
                        }
                        // "The publisher can save the location and can
                        // assign a 'Searching Record', 'Return Visit'
                        // record or 'Bible Study' Record or simply save the
                        // coordinates with remarks" — both actions are
                        // offered side by side; neither one requires the
                        // other.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { showSaveDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("Save Location")
                            }
                            // Attaches this coordinate to one of the signed-in
                            // Publisher's *own existing* records — meaningless
                            // for a role that owns none.
                            if (enrollmentAccess.isPublisher) {
                                OutlinedButton(onClick = { showAssignDialog = true }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Assignment, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                    Text("Assign to Record")
                                }
                            }
                        }
                    }
                }

                OutlinedButton(onClick = { openDirections(context, lat, lng) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Get Directions")
                }
                Text(
                    "Opens Google Maps for turn-by-turn directions, starting from your current location.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (savedLocations.isNotEmpty()) {
                HorizontalDivider()
                Text("Saved Locations", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                savedLocations.forEach { saved ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(saved.remarks.ifBlank { "Saved Location" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(formatCoordinatesDms(saved.lat, saved.lng), style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                TextButton(onClick = {
                                    coordinatesText = "${saved.lat}, ${saved.lng}"
                                    destination = saved.lat to saved.lng
                                    errorText = null
                                }) { Text("Use") }
                                IconButton(onClick = { pendingDelete = saved }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete saved location")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val destinationForSave = destination
    if (showSaveDialog && destinationForSave != null) {
        SaveLocationDialog(
            lat = destinationForSave.first,
            lng = destinationForSave.second,
            onSave = { remarks ->
                viewModel.saveLocation(currentPersonId, destinationForSave.first, destinationForSave.second, remarks)
                showToast("Location saved.")
                showSaveDialog = false
            },
            onCancel = { showSaveDialog = false },
        )
    }

    if (showAssignDialog && destinationForSave != null) {
        AssignToRecordDialog(
            currentPersonId = currentPersonId,
            lat = destinationForSave.first,
            lng = destinationForSave.second,
            onAssigned = { person ->
                viewModel.assignToRecord(person, destinationForSave.first, destinationForSave.second, currentPersonId)
                showToast("Location assigned to \"${person.name}\".")
                showAssignDialog = false
            },
            onDismiss = { showAssignDialog = false },
            viewModel = viewModel,
        )
    }

    // Super-Admin has no congregation of their own, so choose one first.
    val superAdminStage = pendingSuperAdminStage
    if (superAdminStage != null && destinationForSave != null) {
        val congregations by pipelineViewModel.congregations.collectAsStateWithLifecycle()
        SelectCongregationDialog(
            congregations = congregations.filter { it.status == RecordStatus.ACTIVE }.sortedBy { it.name },
            onSelected = { congregation ->
                enrollRequest = EnrollRequest(superAdminStage, destinationForSave.first, destinationForSave.second, congregation.id)
                pendingSuperAdminStage = null
            },
            onDismiss = { pendingSuperAdminStage = null },
        )
    }

    // The existing Searching / Return Visit / Bible Study enrollment form,
    // opened with the found coordinate pre-filled (its Current Address is then
    // detected from it). Nothing is written until the form itself is saved, so
    // opening it can never leave a stray record behind.
    val request = enrollRequest
    if (request != null) {
        val congregationId = request.congregationId.orEmpty()
        val publishersFlow = remember(congregationId, enrollmentAccess.groupId) {
            pipelineViewModel.assignablePublishersFor(congregationId, enrollmentAccess.groupId)
        }
        val publishers by publishersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        PipelinePersonDialog(
            existingPerson = null,
            // A Publisher owns what they enroll; anyone else creates the
            // record unassigned and picks a publisher in ASSIGN PUBLISHER —
            // never defaulting to themselves.
            publisherPersonId = if (enrollmentAccess.isPublisher) currentPersonId else "",
            congregationId = congregationId,
            currentPersonId = currentPersonId,
            stage = request.stage,
            onSave = {
                pipelineViewModel.saveEnrolledRecord(it, currentPersonId)
                showToast("${request.stage.assignLabel()} record added.")
            },
            onDismiss = { enrollRequest = null },
            viewModel = pipelineViewModel,
            initialCoordinates = CoordinatesValue(request.lat, request.lng),
            assignablePublishers = if (enrollmentAccess.isPublisher) null else publishers,
        )
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Saved Location?") },
            text = { Text("This removes \"${toDelete.remarks.ifBlank { "this location" }}\" from your saved locations.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSavedLocation(toDelete.id)
                    showToast("Saved location deleted.")
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * "Result Found: Coordinate: Lat/Lng, Remarks: [ ], [Save] [Cancel]" — the
 * spec's exact worked example, shown right after a destination is found. The
 * remark is required (spec's example is descriptive, not optional — an
 * unlabeled saved coordinate is useless in a list of many).
 */
@Composable
private fun SaveLocationDialog(
    lat: Double,
    lng: Double,
    onSave: (remarks: String) -> Unit,
    onCancel: () -> Unit,
) {
    var remarks by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val message = requiredFieldsMessage("Remarks" to remarks.isNotBlank())
        if (message != null) {
            errorMessage = message
            return
        }
        onSave(remarks)
    }

    FormDialog(
        onDismissRequest = onCancel,
        title = "Result Found",
        onConfirm = ::submit,
        confirmLabel = "Save",
        errorMessage = errorMessage,
        maxContentHeight = 320.dp,
        hasUnsavedChanges = remarks.isNotBlank(),
    ) {
        Text("Coordinate: ${formatCoordinatesDms(lat, lng)}", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = remarks,
            onValueChange = { remarks = it },
            label = { Text("Remarks") },
            placeholder = { Text("e.g. House of Emilio Aguinaldo") },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "The publisher can... assign a 'Searching Record', 'Return Visit' record
 * or 'Bible Study' Record" — pick which of the three stages, then which of
 * the Publisher's own active records at that stage, to write the found
 * coordinate onto (see [FindLocationViewModel.assignToRecord]). */
@Composable
private fun AssignToRecordDialog(
    currentPersonId: String,
    lat: Double,
    lng: Double,
    onAssigned: (InterestedPerson) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FindLocationViewModel,
) {
    var stage by remember { mutableStateOf(PipelineStage.SEARCHING) }
    val recordsFlow = remember(currentPersonId, stage) { viewModel.recordsFor(currentPersonId, stage) }
    val records by recordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Assign to Record") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Coordinate: ${formatCoordinatesDms(lat, lng)}", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    PipelineStage.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = stage == entry,
                            onClick = { stage = entry },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = PipelineStage.entries.size),
                        ) { Text(entry.assignLabel()) }
                    }
                }
                if (records.isEmpty()) {
                    Text(
                        "No ${stage.assignLabel()} records yet under your name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    records.forEach { person ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onAssigned(person) }) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(person.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(person.address, style = MaterialTheme.typography.bodySmall)
                                if (person.hasGpsLocation) {
                                    Text(
                                        "Already has a saved location — tapping will replace it.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun PipelineStage.assignLabel(): String = when (this) {
    PipelineStage.SEARCHING -> "Searching"
    PipelineStage.RETURN_VISIT -> "Return Visit"
    PipelineStage.BIBLE_STUDY -> "Bible Study"
}

private fun PipelineStage.enrollLabel(): String = when (this) {
    PipelineStage.SEARCHING -> "Enroll to Searching Record"
    PipelineStage.RETURN_VISIT -> "Enroll to Return Visit Record"
    PipelineStage.BIBLE_STUDY -> "Enroll to Bible Study Record"
}

/** Super-Admin's "which congregation is this record for?" step before the
 * enrollment form opens. */
@Composable
private fun SelectCongregationDialog(
    congregations: List<Congregation>,
    onSelected: (Congregation) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        onDismissRequest = onDismiss,
        title = { Text("Select Congregation") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (congregations.isEmpty()) {
                    Text("No congregations available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                congregations.forEach { congregation ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onSelected(congregation) }) {
                        Text(congregation.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Launches Google Maps (falling back to any browser if the app isn't
 * installed) already positioned on turn-by-turn directions for [mode] from
 * the device's current location to ([lat], [lng]) — the same
 * `ACTION_VIEW`-a-`Uri` pattern [com.emfitsolutions.gopreach.ui.screens
 * .sharelocation.ShareLocationScreen] already uses for its `geo:` links. */
private fun openDirections(context: android.content.Context, lat: Double, lng: Double) {
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        // No app/browser can handle it (e.g. a stripped-down test device) —
        // nothing sensible to fall back to short of a Toast; swallow rather
        // than crash the Main Form.
    }
}
