package com.emfitsolutions.gopreach.ui.screens.sharelocation

import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.platform.AppPermission
import com.emfitsolutions.gopreach.platform.SimpleDateFormat
import com.emfitsolutions.gopreach.platform.Locale
import com.emfitsolutions.gopreach.platform.Date
import com.emfitsolutions.gopreach.ui.components.RecordFound
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.location.LatLng
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.LocationSharingSettings
import com.emfitsolutions.gopreach.ui.components.CongregationFilterDropdown
import com.emfitsolutions.gopreach.ui.components.SelectCongregationPrompt
import com.emfitsolutions.gopreach.ui.components.rememberCongregationContext
import com.emfitsolutions.gopreach.ui.components.FormDialog
import com.emfitsolutions.gopreach.ui.components.openCoordinatesInMaps
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.components.requiredFieldsMessage
import androidx.compose.ui.window.DialogProperties

/** "Shared Location Module — List View and Map View... same design,
 * behavior, and functionality already implemented in the Territory Map
 * Module" — same toggle concept/wording/icon convention as Territory Map's
 * own `TerritoryViewMode` (a distinct, private enum rather than a shared
 * one: two independent, unrelated screens that happen to offer the same two
 * modes shouldn't be coupled just because their labels currently match). */
private enum class ShareLocationViewMode(val label: String) { LIST("List View"), MAP("Map View") }

/**
 * "Simplify Share Location Module with Automatic Real-Time Updates" spec —
 * §6.1 Share Location. [canShareOwnLocation] gates the "share my location"
 * toggle to publishers only, per spec ("Publisher: can share own location
 * while preaching"); every role can view whoever's currently sharing within
 * their own scope. [canManageLocationSettings] additionally shows the
 * "SHARE LOCATION SETTINGS" gear (Super-Admin/Admin/Service Overseer/
 * Coordinator Elder/Regular Elder, own congregation for anyone but
 * Super-Admin). [onOpenTerritoryMap] is how a coordinate pair (this
 * publisher's own, or any "sharing now" row's) opens GoPreach's own
 * Territory Map centered on that point — internal navigation, deliberately
 * not an external Maps app, per this spec's "clicking should open the app's
 * own Map View" requirement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLocationScreen(
    currentPersonId: String,
    currentPersonName: String,
    visibleCongregationId: String?,
    canShareOwnLocation: Boolean,
    canManageLocationSettings: Boolean = false,
    ownCongregationId: String? = null,
    onBack: () -> Unit,
    onOpenTerritoryMap: (lat: Double, lng: Double, name: String) -> Unit,
    viewModel: ShareLocationViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val isSharingFlow = remember(currentPersonId) { viewModel.isSharingFor(currentPersonId) }
    val isSharing by isSharingFlow.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(currentPersonId) { viewModel.observeOwnSharedLocation(currentPersonId) }
    val myLocation by viewModel.myLocation.collectAsStateWithLifecycle()
    // "Location Acquired" vs. "published" are different: a GPS fix can exist
    // on-device yet still fail LocationSharingService's accuracy gate
    // forever (weak/indoor signal, a strict congregation threshold) — with
    // only [myLocation] to go on, that reads identically to "no fix yet,"
    // leaving the Publisher staring at "Acquiring…" with no indication
    // anything is actually happening. This distinguishes the two.
    val locationAcquired by viewModel.locationAcquired.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    // "Move the By Congregation from textbox to Dropdown same as the other
    // module filter" — a real, always-visible Congregation dropdown now,
    // replacing the free-text "type the congregation name into Search"
    // behavior; only meaningful when [visibleCongregationId] is already
    // unscoped (Super-Admin).
    var congregationFilter by rememberCongregationContext("share_location")
    val effectiveCongregationId = visibleCongregationId ?: congregationFilter
    val needsCongregation = visibleCongregationId == null && congregationFilter == null
    val rowsFlow = remember(effectiveCongregationId, searchQuery, needsCongregation) {
        if (needsCongregation) return@remember kotlinx.coroutines.flow.flowOf(emptyList())
        viewModel.rowsFor(effectiveCongregationId, searchQuery)
    }
    val rows by rowsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy – h:mm a", Locale.getDefault()) }
    val rowTimestampFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    // "The default view can be List View" — same [rows] backs both views
    // (already scoped/searched identically), so switching never loses the
    // active search/filter.
    var viewMode by remember { mutableStateOf(ShareLocationViewMode.LIST) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    val showToast = rememberActionToast()

    // "Do not show repetitive notifications every time... an automatic
    // background update occurs" — this one-time flag is reset whenever a new
    // sharing session starts, and consumed the first time a real fix lands
    // for that session, so the "Your current location has been updated."
    // toast fires exactly once per ON period, never on the many
    // near-real-time refreshes that follow it silently.
    var hasShownFirstLocationToast by remember { mutableStateOf(false) }
    var wasSharing by remember { mutableStateOf(isSharing) }
    LaunchedEffect(isSharing) {
        if (isSharing && !wasSharing) hasShownFirstLocationToast = false
        wasSharing = isSharing
    }
    LaunchedEffect(isSharing, myLocation?.capturedAt) {
        if (isSharing && myLocation != null && !hasShownFirstLocationToast) {
            hasShownFirstLocationToast = true
            showToast("Your current location has been updated.")
        }
    }

    // "Immediately display a clear system action message... Immediately
    // change the button/status to Location Sharing ON" — both happen right
    // here, synchronously with the call that actually activates sharing
    // (toggleSharing sets its own optimistic override before doing anything
    // else — see that function's doc comment), not deferred until a GPS fix
    // or server round-trip confirms anything.
    fun activateSharing() {
        viewModel.toggleSharing(true, currentPersonId, visibleCongregationId, null)
        showToast("Location sharing is now active.")
    }

    val permissionLauncher = rememberPermissionRequester() { granted ->
        if (granted) {
            activateSharing()
        } else {
            // "Provide clear instructions... rather than allowing the
            // application to fail silently" — a denied permission used to
            // just leave the Switch off with zero explanation.
            showToast("Location permission is required to share your location.")
        }
    }

    // "Show my current Coordinates" — a one-off look at where this device is
    // right now. Separate from sharing: it never turns sharing on and nothing
    // it reads is sent anywhere.
    val coordinatesScope = rememberCoroutineScope()
    var shownCoordinates by remember { mutableStateOf<LatLng?>(null) }
    var isFetchingCoordinates by remember { mutableStateOf(false) }
    var coordinatesError by remember { mutableStateOf<String?>(null) }
    fun fetchCoordinates() {
        coordinatesError = null
        isFetchingCoordinates = true
        coordinatesScope.launch {
            val fix = viewModel.currentCoordinates()
            isFetchingCoordinates = false
            if (fix != null) shownCoordinates = fix else coordinatesError = "Could not get your current coordinates. Make sure location is turned on and try again."
        }
    }
    val coordinatesPermissionLauncher = rememberPermissionRequester() { granted ->
        if (granted) fetchCoordinates() else coordinatesError = "Location permission is required to show your coordinates."
    }
    fun showMyCoordinates() {
        if (!viewModel.isLocationServicesEnabled()) {
            coordinatesError = "Location services are disabled. Please enable GPS to continue."
        } else if (viewModel.hasLocationPermission()) {
            fetchCoordinates()
        } else {
            coordinatesPermissionLauncher.launch(AppPermission.LOCATION)
        }
    }

    fun startSharing() {
        if (viewModel.isLocationServicesEnabled()) {
            if (viewModel.hasLocationPermission()) {
                activateSharing()
            } else {
                permissionLauncher.launch(AppPermission.LOCATION)
            }
        } else {
            showToast("Location services are disabled. Please enable GPS to continue.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location Sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // "Provide clear buttons, tabs, or a toggle for
                    // switching between List View and Map View" — same
                    // icon-toggle convention as Territory Map's own top-bar
                    // action.
                    IconButton(onClick = { viewMode = if (viewMode == ShareLocationViewMode.MAP) ShareLocationViewMode.LIST else ShareLocationViewMode.MAP }) {
                        Icon(
                            if (viewMode == ShareLocationViewMode.MAP) Icons.Rounded.ViewList else Icons.Rounded.Map,
                            contentDescription = if (viewMode == ShareLocationViewMode.MAP) "Switch to List View" else "Switch to Map View",
                        )
                    }
                    if (canManageLocationSettings) {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Share Location Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (canShareOwnLocation) {
                // Matches the "Location Sharing" reference design: a plain
                // "My Location" heading and a single pill-shaped Share
                // button — no status card, no switch. The same
                // consent-dialog → permission → toggleSharing flow as
                // before runs underneath; only the presentation changed.
                Text(
                    "My Location",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // Reference design: the Lat/Lng link sits directly under the
                // heading, above the Share button — only while actively
                // sharing (nothing to show otherwise).
                if (isSharing && myLocation != null) {
                    InternalCoordinatesText(
                        lat = myLocation!!.fix.lat,
                        lng = myLocation!!.fix.lng,
                        onClick = { onOpenTerritoryMap(myLocation!!.fix.lat, myLocation!!.fix.lng, currentPersonName) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else if (isSharing) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            // locationAcquired true + myLocation still null
                            // means a fix exists on-device but every one so
                            // far has failed the congregation's accuracy
                            // gate (LocationSharingService.publish) — a weak/
                            // indoor GPS signal, most often — not that
                            // nothing is happening. Saying so distinguishes a
                            // slow-but-working session from a stuck one.
                            if (locationAcquired) "Got a GPS fix, but it's not accurate enough yet — still trying for a better one…"
                            else "Acquiring your current location…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Always reachable while sharing, regardless of
                        // whether a fix has ever been published yet — the
                        // trailing stop icon on this person's own Team
                        // Locations row (below) only exists once a fix has
                        // actually landed, which would otherwise leave no way
                        // to turn sharing back off during exactly the
                        // "stuck acquiring" case above.
                        TextButton(
                            onClick = {
                                viewModel.toggleSharing(false, currentPersonId, visibleCongregationId, null)
                                showToast("Location sharing stopped successfully.")
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("Stop Sharing") }
                    }
                }
                // "Share" always starts/refreshes sharing — once already on,
                // stopping happens from the "Stop Sharing" link above (while
                // waiting for a fix) or this person's own row in Team
                // Locations below (its trailing stop-sharing icon) once one
                // lands — not from this button, matching the reference
                // design (identical Share button regardless of current
                // state). "Show my current Coordinates" sits beside it, same
                // fill color, since both are this section's primary actions.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Button(
                        onClick = {
                            if (!isSharing) {
                                // "There must be a pop up message that the user
                                // will allow the app to share location
                                // coordinates" — same app-level consent step,
                                // shown before the OS location-permission prompt.
                                showConsentDialog = true
                            }
                        },
                        enabled = !isSharing,
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Share")
                    }
                    Button(onClick = ::showMyCoordinates, enabled = !isFetchingCoordinates, shape = RoundedCornerShape(50)) {
                        if (isFetchingCoordinates) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                            Text("Getting your coordinates…", modifier = Modifier.padding(start = 8.dp))
                        } else {
                            Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("My Coordinates")
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isSharing && myLocation != null) {
                        Text(
                            "Last Updated: ${dateFormat.format(Date(myLocation!!.capturedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!isSharing) {
                        Text(
                            "Only other publishers in your congregation see it, and it stops on its own after the configured time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    shownCoordinates?.let { fix ->
                        // Clicking opens GoPreach's own Territory Map,
                        // centered here — same behavior as every other
                        // coordinates link on this screen (InternalCoordinatesText).
                        InternalCoordinatesText(
                            lat = fix.lat,
                            lng = fix.lng,
                            onClick = { onOpenTerritoryMap(fix.lat, fix.lng, currentPersonName) },
                        )
                        if (fix.accuracyMeters != null) {
                            Text("Accuracy: ${fix.accuracyMeters!!.toInt()} meters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Only shown to you — not shared.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { shownCoordinates = null; coordinatesError = null }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Hide my current location")
                        }
                    }
                    coordinatesError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }

            Text(
                "Team Locations (${rows.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(top = if (canShareOwnLocation) 0.dp else 8.dp),
            )

            if (viewMode == ShareLocationViewMode.MAP) {
                // "Use the same working map implementation and functionality
                // as the Territory Map Module... ensure the map loads
                // correctly and does not display a blank screen" — built on
                // the exact same [com.emfitsolutions.gopreach.ui.components
                // .map.LeafletMapView] wrapper Territory Map itself uses.
                ShareLocationMapView(
                    rows = rows,
                    onOpenTerritoryMap = onOpenTerritoryMap,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                if (visibleCongregationId == null) {
                    CongregationFilterDropdown(
                        congregations = congregations,
                        selectedCongregationId = congregationFilter,
                        onSelected = { congregationFilter = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search: Name, Status, Group") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (needsCongregation) {
                    SelectCongregationPrompt()
                } else if (rows.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RecordFound(0)
                        Text("No one has shared their location yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item { RecordFound(rows.size) }
                        items(rows, key = { it.person.id }) { row ->
                            val isOwnRow = row.person.id == currentPersonId
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    Icons.Rounded.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        row.person.fullName.uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        // Tapping the name opens this publisher's
                                        // location in Google Maps (or whatever
                                        // handles `geo:`) — an external app, unlike
                                        // the internal-Territory-Map coordinates
                                        // links elsewhere on this screen.
                                        modifier = Modifier.clickable { openCoordinatesInMaps(context, row.location.lat, row.location.lng, row.person.fullName) },
                                    )
                                    val statusLabel = row.category?.name?.lowercase() ?: row.person.activeAdminRole?.lowercase() ?: "publisher"
                                    Text(
                                        "$statusLabel — shared ${rowTimestampFormat.format(Date(row.location.updatedAt))}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // Only the viewer's own row carries the
                                // stop-sharing control — matches the
                                // reference design, and stopping someone
                                // else's sharing was never a feature here.
                                if (isOwnRow) {
                                    IconButton(onClick = {
                                        viewModel.toggleSharing(false, currentPersonId, visibleCongregationId, null)
                                        showToast("Location sharing stopped successfully.")
                                    }) {
                                        Icon(Icons.Rounded.LocationOff, contentDescription = "Stop sharing my location")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Share Your Location?") },
            text = {
                Text(
                    "GoPreach will share your live location coordinates with other publishers in your congregation while you're preaching. " +
                        "Sharing stops automatically after the configured time, or whenever you turn it off. Allow location sharing?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConsentDialog = false
                        startSharing()
                    },
                ) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { showConsentDialog = false }) { Text("Cancel") } },
        )
    }

    if (showSettingsDialog) {
        LocationSharingSettingsDialog(
            fixedCongregationId = ownCongregationId,
            congregations = viewModel.congregations.collectAsStateWithLifecycle().value,
            currentPersonId = currentPersonId,
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false },
        )
    }
}

/** "Make the coordinates clickable... open the app's own Map View, centered
 * on the Publisher's latest location" — deliberately separate from the
 * shared [com.emfitsolutions.gopreach.ui.components.ClickableCoordinatesText]
 * component (which the rest of the app uses to open an *external* Maps app):
 * this screen's coordinates navigate to GoPreach's own Territory Map
 * instead, so the two must not share an implementation. */
@Composable
private fun InternalCoordinatesText(
    lat: Double,
    lng: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Text(
        "Latitude: $lat   Longitude: $lng",
        style = style,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** "SHARE LOCATION SETTINGS" — Location Sharing Time + Accuracy Radius, per
 * congregation. [fixedCongregationId] null means Super-Admin (picks any
 * congregation); non-null means already scoped to that one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSharingSettingsDialog(
    fixedCongregationId: String?,
    congregations: List<Congregation>,
    currentPersonId: String,
    viewModel: ShareLocationViewModel,
    onDismiss: () -> Unit,
) {
    // Bug fix — same "Congregation/Group is required" even after picking one,
    // confirmed root-caused (and fixed) in ManageGroupsScreen's GroupDialog:
    // `rememberSaveable` (immune to a config change wiping a plain `remember`)
    // and re-deriving the resolved id fresh inside submit() rather than
    // trusting a val computed here — a real, confirmed-on-device Compose
    // staleness where that kind of pre-computed val can go stale inside a
    // local closure even though the live picked-id state stays correct.
    var pickedCongregationId by rememberSaveable { mutableStateOf(fixedCongregationId ?: congregations.firstOrNull()?.id) }
    val congregationId = fixedCongregationId ?: pickedCongregationId

    val settings by (
        if (congregationId != null) viewModel.settingsFor(congregationId)
        else kotlinx.coroutines.flow.flowOf(LocationSharingSettings())
        ).collectAsStateWithLifecycle(initialValue = LocationSharingSettings())

    var durationText by remember(congregationId, settings.sharingDurationMinutes) { mutableStateOf(settings.sharingDurationMinutes.toString()) }
    var accuracyText by remember(congregationId, settings.accuracyRadiusMeters) { mutableStateOf(settings.accuracyRadiusMeters.toString()) }
    val showToast = rememberActionToast()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val resolvedCongregationId = fixedCongregationId ?: pickedCongregationId
        val duration = durationText.toIntOrNull()
        val accuracy = accuracyText.toIntOrNull()
        val message = requiredFieldsMessage(
            "Congregation" to (resolvedCongregationId != null),
            "Location Sharing Time" to (duration != null && duration > 0),
            "Accuracy Radius" to (accuracy != null && accuracy > 0),
        )
        if (message != null) {
            errorMessage = message
            return
        }
        viewModel.saveSettings(
            LocationSharingSettings(
                congregationId = resolvedCongregationId!!,
                sharingDurationMinutes = duration!!,
                accuracyRadiusMeters = accuracy!!,
            ),
            currentPersonId,
        )
        showToast("Location sharing settings saved.")
        onDismiss()
    }

    FormDialog(
        onDismissRequest = onDismiss,
        title = "Share Location Settings",
        onConfirm = ::submit,
        confirmLabel = "Save",
        errorMessage = errorMessage,
        maxContentHeight = 420.dp,
        hasUnsavedChanges = durationText != settings.sharingDurationMinutes.toString() ||
            accuracyText != settings.accuracyRadiusMeters.toString(),
    ) {
                if (fixedCongregationId == null) {
                    CongregationSettingsDropdown(
                        congregations = congregations,
                        selectedId = pickedCongregationId,
                        onSelected = { pickedCongregationId = it },
                    )
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter { c -> c.isDigit() } },
                    label = { Text("Location Sharing Time (minutes)") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The publisher can share their location for this many minutes before it automatically stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = accuracyText,
                    onValueChange = { accuracyText = it.filter { c -> c.isDigit() } },
                    label = { Text("Accuracy Radius (meters)") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A GPS fix less accurate than this is never shared — the last good position stays visible until a better one arrives.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CongregationSettingsDropdown(congregations: List<Congregation>, selectedId: String?, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = congregations.firstOrNull { it.id == selectedId }?.name ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Congregation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            congregations.forEach { congregation ->
                DropdownMenuItem(text = { Text(congregation.name) }, onClick = { onSelected(congregation.id); expanded = false })
            }
        }
    }
}
