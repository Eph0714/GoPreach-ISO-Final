package com.emfitsolutions.gopreach.ui.screens.home

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.ScopeType
import com.emfitsolutions.gopreach.ui.components.DashboardHero
import com.emfitsolutions.gopreach.ui.components.DashboardSection
import com.emfitsolutions.gopreach.ui.components.DashboardTile
import com.emfitsolutions.gopreach.ui.components.GoPreachSidePanelContent
import com.emfitsolutions.gopreach.ui.components.NotificationBell
import com.emfitsolutions.gopreach.ui.components.ProfileMenuButton
import com.emfitsolutions.gopreach.ui.components.QuickAction
import com.emfitsolutions.gopreach.ui.components.SyncToServerButton
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.navigation.Destinations
import com.emfitsolutions.gopreach.ui.screens.dashboard.DashboardStatsContent
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationCenterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties

/** Landing point for the Admin context (spec §5.1) — a Side Panel (spec's
 * "Role-Based Dashboard, Side Panel & Graphical Reports" enhancement) plus a
 * hero (greeting + live sync/connectivity status + quick actions) over an
 * icon-grid dashboard, every tile/side-panel entry role-gated per the spec §3
 * permission matrix. The Side Panel and the tile grid below share the exact
 * same gating booleans, computed once here — one source of truth for "what
 * can this session navigate to," not two that could silently drift apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHomeScreen(
    onSwitchToPublisher: (() -> Unit)?,
    /** Super-Admin always; an Admin only if explicitly granted MANAGE_USERS
     * (spec §2/§14 — "Admin can manage users only if explicitly authorized"). */
    canManageUsers: Boolean,
    /** Account Management spec §5 permission matrix — Super-Admin, Admin,
     * Coordinator Elder, Service Overseer (+ Secretary). Computed once in
     * GoPreachNavGraph, same as every other role-gating boolean here. */
    canManageAccountCredentials: Boolean,
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
    notificationCenterViewModel: NotificationCenterViewModel = koinViewModel(),
    groupChatViewModel: com.emfitsolutions.gopreach.ui.screens.groupchat.GroupChatViewModel = koinViewModel(),
) {
    val session by viewModel.state.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    // "Multiple Role Login Detection & Role Selection" spec §7 — the
    // session's single active role (auto for a one-role account, or
    // whichever SelectRoleScreen entry the user picked), not always the most
    // senior Admin-track role the account happens to hold.
    val role = session.activeAdminRole
    val currentPersonId = session.person?.id.orEmpty()

    val isSuperAdmin = role == AdminRole.SUPER_ADMIN
    // "REDESIGN THE CIRCUIT OVERSEER AND OTHER USER DASHBOARD" spec —
    // Circuit Overseer (and any other future grant-based restricted role)
    // carries no built-in access of its own (see AdminRole.CIRCUIT_OVERSEER's
    // doc comment); every capability they have comes from their own
    // UserAccessGrant instead. Checked by permission only, not scope, here —
    // this only decides whether a drawer item is *offered at all*; the
    // screen it opens, and firestore.rules underneath it, still enforce the
    // grant's actual congregation/group scope on every read and write.
    val grantPermissions = session.grant?.resolvedPermissions.orEmpty()
    val canEnrollCoordinatorElder = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION
    val canEnrollRegularElderOrPublisher = canEnrollCoordinatorElder || role == AdminRole.COORDINATOR_ELDER
    // "CREATING PUBLISHER" spec — Service Overseer can also create/manage
    // Publishers under their own congregation, on top of everyone
    // [canEnrollRegularElderOrPublisher] already covers. Kept as its own flag
    // so Service Overseer doesn't also gain Regular Elder enrollment access.
    val canEnrollPublisher = canEnrollRegularElderOrPublisher || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY ||
        Permission.MANAGE_PUBLISHERS in grantPermissions
    // New Service Overseer role — unlike Coordinator Elder enrollment, a
    // Coordinator Elder *can* create one (same three-role set as Regular
    // Elder/Publisher enrollment above).
    val canEnrollServiceOverseer = canEnrollRegularElderOrPublisher
    // "MINISTERIAL ACCOUNT" spec — same enroller set as Service Overseer
    // (Super-Admin/Admin-own-congregation/Coordinator Elder), but with no
    // per-congregation cap: multiple Ministerial Servants are allowed.
    // Ministerial Servant is a RegularElderRole-adjacent AdminRole stored in
    // the same `roleAssignments` collection Regular Elder is — firestore.rules
    // gates that whole collection on VIEW_ELDERS/MANAGE_ELDERS regardless of
    // which AdminRole a given document actually holds, so a grant-based
    // Circuit Overseer's MANAGE_ELDERS permission already covers this at the
    // data layer; this just surfaces the matching drawer item too.
    val canEnrollMinisterialServant = canEnrollRegularElderOrPublisher || Permission.MANAGE_ELDERS in grantPermissions
    // "Announcement Module" — Super-Admin (any congregation), Admin/
    // Coordinator Elder (own congregation only). No Service Overseer/
    // Ministerial Servant per the spec's explicit access list.
    val canManageAnnouncements = canEnrollRegularElderOrPublisher
    // "Consolidated Monthly Report" spec — Service Overseer, Coordinator
    // Elder, Admin (own congregation), and Super-Admin (all congregations).
    val canViewConsolidatedReport = canEnrollRegularElderOrPublisher || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY
    // "Field Service Group Report" — same viewer set as the Consolidated
    // Report, widened to also include Regular Elder ("Use the entities for
    // Admin, Coordinator/Elder, Regular Elder, Service Overseer"): Super-
    // Admin (every congregation), Admin/Coordinator Elder/Service Overseer/
    // Regular Elder (own congregation only).
    val canViewFieldServiceGroupReport = canViewConsolidatedReport || role == AdminRole.REGULAR_ELDER
    // "Forward to Other Congregation" incoming review queue — Super-Admin/
    // Admin/Coordinator Elder/Service Overseer keep full Accept/Decline/
    // Assign access (same viewer set as the Consolidated Report); Regular
    // Elder/Ministerial Servant also see it now (notification balloon spec:
    // "Incoming approval request for transfer [All]"), but strictly
    // view-only — see GoPreachNavGraph's FORWARD_REQUESTS composable, which
    // computes that separately and is the actual enforcement (this flag only
    // decides whether the drawer/balloon offer the screen at all).
    val canViewForwardRequests = canViewConsolidatedReport || role == AdminRole.REGULAR_ELDER || role == AdminRole.MINISTERIAL_SERVANT
    // "House Holder Assignment" module — spec's own exact access list:
    // Super-Admin (all congregations), Admin/Service Overseer (own
    // congregation only) — deliberately narrower than [canViewForwardRequests]
    // above (no Coordinator Elder/Regular Elder/Ministerial Servant; the
    // spec never names them). Secretary gets the same access Service
    // Overseer already does everywhere else in this app.
    val canManageHouseholderAssignment = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
        role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY
    // Recently Visited needs somewhere to open a record: the Publisher Assignment module, or the scoped read-only list.
    val showRecentlyVisited = canManageHouseholderAssignment || role == AdminRole.COORDINATOR_ELDER
    // "Manage Publisher Report" module — Super-Admin (every congregation),
    // Admin/Coordinator Elder/Service Overseer (own congregation only); same
    // access set as the Consolidated Report. A Circuit Overseer with any of
    // the report-view permissions also reaches it, but always read-only —
    // see GoPreachNavGraph's MANAGE_PUBLISHER_REPORTS composable, which
    // computes that separately: firestore.rules blocks every grant holder
    // from writing `monthlyReports` regardless of permission ("A restricted
    // user's report access is view-only in every one of the spec's own
    // worked examples"), so this module can never grant them edit rights,
    // only viewing/printing.
    // Regular Elder/Ministerial Servant also reach this now (notification
    // balloon spec: "Incoming Publisher Monthly Reports [Not for
    // Publisher]") — same "widen visibility, not authority" call as
    // [canViewForwardRequests] above; GoPreachNavGraph's `canEditPublisherReports`
    // already excludes both of them, so they land here read-only.
    val canManagePublisherReports = canViewConsolidatedReport || role == AdminRole.REGULAR_ELDER || role == AdminRole.MINISTERIAL_SERVANT ||
        grantPermissions.any {
            it == Permission.VIEW_PUBLISHER_REPORTS || it == Permission.VIEW_GROUP_REPORTS || it == Permission.VIEW_CONGREGATION_REPORTS
        }
    // Control Panel: full access for Super-Admin, own-congregation for Admin (spec §3 permission matrix).
    val canAccessControlPanel = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION
    // User logs: Super-Admin (all) and Admin/Coordinator Elder (own congregation); Regular Elder has no access.
    val canViewUserLogs = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION || role == AdminRole.COORDINATOR_ELDER
    // "Contact Record" module — "Super Admin can see all congregation
    // Contact Records. Admin, Congregation Elder, Regular Elder, and
    // Service Overseer can only see Contact Records from their own
    // congregation" (Call/Message/Search/Filter redesign) — widened from
    // Super-Admin/Coordinator Elder/Regular Elder only to also include
    // Admin and Service Overseer, per that explicit access list.
    val canViewContactRecord = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
        role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY || role == AdminRole.REGULAR_ELDER
    // Publishers/Groups: Super-Admin/Admin/Coordinator Elder only (spec §3 permission matrix — Regular Elder ❌).
    val canManagePublishersAndGroups = canViewUserLogs
    // "Territory Map" — "allow the publishers, coordinator elders, regular
    // elders, service overseer and admin to see... their congregation only,
    // however the super admin can see all territory in all congregation" —
    // its own (wider than [canManagePublishersAndGroups]) set: Super-Admin,
    // Admin, Coordinator Elder, Service Overseer, and Regular Elder. Always
    // view-only regardless of role — the screen itself has no edit/delete
    // action for anyone (see TerritoryMapScreen).
    val canViewTerritoryMap = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
        role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY || role == AdminRole.REGULAR_ELDER
    // Spec §15 — "Elders should be able to see Interested Person information
    // according to their existing Congregation/Group access scope." Own
    // Congregation/Group only, read-only (see ElderInterestedRecordsScreen).
    // Excludes Super-Admin, who already has the separate, full read/write
    // ALL_INTERESTED_RECORDS drawer item instead — this scoped screen would
    // be redundant (and congregationId-less) for that role.
    val canViewInterestedPeopleScope = role == AdminRole.ADMIN_PER_CONGREGATION || role == AdminRole.COORDINATOR_ELDER ||
        role == AdminRole.SERVICE_OVERSEER || role == AdminRole.REGULAR_ELDER
    // "Meeting Assignments" — "the Coordinator-elder, elder, service
    // overseer, admin can make an assignment for the meetings, the super
    // admin can do so [too]" — same role set as [canViewTerritoryMap] today,
    // kept as its own flag since the two features may diverge later.
    val canEditMeetingAssignments = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
        role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY || role == AdminRole.REGULAR_ELDER
    // "CREATING GROUPS" spec — Service Overseer can also create/manage
    // Groups under their own congregation, same as Coordinator Elder,
    // without gaining the wider Publisher-management access above.
    // RBAC: resolved from every role the user holds, so a Group Overseer/Servant/Assistant (own group only)
    // reaches the screen too; the screen itself narrows what they can see and do.
    val canManageGroups = canManagePublishersAndGroups || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY ||
        Permission.MANAGE_GROUPS in grantPermissions ||
        com.emfitsolutions.gopreach.domain.GroupAccessScope.resolve(session.person, session.roleAssignments).canOpen
    // Territory Assignment module — same five built-in roles as the spec
    // calls for (Super-Admin/Admin/Coordinator Elder via
    // canManagePublishersAndGroups, plus Service Overseer/Secretary), same
    // direct-role-check pattern as [canManageGroups] just above rather than
    // routing through [PermissionChecker.hasPermission]. Deliberately NO
    // `Permission.MANAGE_TERRITORY_ASSIGNMENTS in grantPermissions` branch
    // (unlike canManageGroups) — firestore.rules' canManageTerritoryAssignmentsFor
    // has no grant-based path at all, by design (its own comment explains why:
    // avoiding the "restrictedAllows() always true for built-in roles" gap
    // this feature was specifically built to close). A grant-only branch here
    // would show the Add button to a restricted user whose every save would
    // then be rejected server-side — this stays an exact mirror of the
    // server's fixed role list instead.
    val canManageTerritoryAssignments = canManagePublishersAndGroups || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY
    // Drawer-only widening of the Regular Elder item for a grant-based
    // Circuit Overseer — deliberately *not* folded into
    // [canEnrollRegularElderOrPublisher] itself, since that flag also drives
    // Service Overseer/Ministerial Servant enrollment and the Consolidated
    // Report below, none of which MANAGE_ELDERS implies.
    val canManageRegularEldersForDrawer = canEnrollRegularElderOrPublisher || Permission.MANAGE_ELDERS in grantPermissions
    // "Elder Dashboard Consistent with Admin/Super-Admin Dashboard" spec §1 —
    // every admin-track role (including both Elder roles) now navigates
    // through the same Side Panel-driven Main Form; the old tile-grid body
    // (quick actions, DashboardTile grid, "Sign Out" tile) is hidden for all
    // of them, not just Super-Admin/Admin. This used to leave Coordinator/
    // Regular Elder on the old tile-grid layout — a real, visible
    // inconsistency, not a deliberate role distinction — while
    // Super-Admin/Admin already had the clean drawer+stats Main Form.
    // Elder-appropriate destinations are still reachable, just through the
    // drawer (see GoPreachSidePanelContent, already gated by the same
    // canManagePublishersAndGroups/canEnrollRegularElderOrPublisher/etc.
    // booleans below, which are already Elder-aware).
    // "REDESIGN THE CIRCUIT OVERSEER AND OTHER USER DASHBOARD" spec: "Make the
    // Dashboard and main form the same as the admin user" — Circuit Overseer
    // (and any other future grant-based restricted role) gets the same
    // drawer+stats shell as every built-in Admin-track role, instead of the
    // old tile-grid layout it was still stuck on.
    val hideMainFormButtons = isSuperAdmin || role == AdminRole.ADMIN_PER_CONGREGATION ||
        role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY || role == AdminRole.REGULAR_ELDER ||
        role == AdminRole.MINISTERIAL_SERVANT || role == AdminRole.CIRCUIT_OVERSEER
    // Same scoping GoPreachNavGraph's standalone Dashboard Reports route uses
    // (see its `ownCongregationId ?: ownGroupAssignment?.congregationId`) —
    // reproduced here so the graphical Summary embedded below is scoped
    // identically wherever it's shown. This was missing the Regular Elder
    // fallback (their own RoleAssignment has a groupId, not a congregationId
    // set directly), which meant a Regular Elder's embedded Main Form summary
    // silently showed zero congregations' worth of data — a real scope bug,
    // not just a missing UI to reach the (correctly-scoped) standalone route.
    // resolvedRoleTypeOrNull() (never throws), not resolvedRoleType() — this
    // runs unconditionally on every Main Form composition, so one corrupt/
    // unparseable RoleAssignment used to crash the app immediately after a
    // correct login instead of just being skipped like it holds no such role.
    val activeAssignment = session.activeRoleAssignment
    val ownCongregationId = activeAssignment?.takeIf {
        (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in setOf(AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.MINISTERIAL_SERVANT)
    }?.congregationId
    val ownGroupAssignment = activeAssignment?.takeIf {
        (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER
    }
    val ownGroupCongregationId = ownGroupAssignment?.congregationId
    // A Circuit Overseer has no congregationId of their own to fall back to
    // (see AdminRole.CIRCUIT_OVERSEER's doc comment) — without this, the
    // `else` branch below would resolve to an *empty* set for them (not
    // "all"), silently showing zero congregations' worth of data on their
    // own dashboard. Their real scope lives on the grant instead: null (no
    // filter) for ALL_CONGREGATIONS, or the exact congregation list for
    // SELECTED_CONGREGATIONS. A SELECTED_GROUPS grant has no congregation-
    // level scope to derive here, so it stays empty rather than guessing.
    val grantScopeCongregationIds: Set<String>? = session.grant?.let { grant ->
        when (grant.resolvedScopeType) {
            ScopeType.ALL_CONGREGATIONS -> null
            ScopeType.SELECTED_CONGREGATIONS -> grant.scopeCongregationIds.toSet()
            ScopeType.SELECTED_GROUPS -> emptySet()
        }
    }
    val visibleCongregationIds: Set<String>? = when {
        isSuperAdmin -> null
        role == AdminRole.CIRCUIT_OVERSEER -> grantScopeCongregationIds
        else -> setOfNotNull(ownCongregationId ?: ownGroupCongregationId)
    }

    // Unified notification balloon (spec: "Add a notification balloon for
    // Service Overseer, Elders, Admin, publisher and super admin") — every
    // admin-track role except a grant-based Circuit Overseer (not named in
    // the spec's role list; their own scope model is entirely different).
    // [visibleCongregationIds] is the exact same scoping this screen already
    // uses everywhere else ("Admin, Elder, Ministerial... can only see the
    // notification within their congregation, however the super admin can
    // see all congregation notification"). Monthly Report items only appear
    // for a role that already has [canManagePublisherReports] — the balloon
    // surfaces existing access, it never grants new access of its own.
    val showNotificationBell = role != AdminRole.CIRCUIT_OVERSEER
    val notificationItemsFlow = remember(visibleCongregationIds, canManagePublisherReports) {
        notificationCenterViewModel.itemsForAdmin(visibleCongregationIds, false)
    }
    val visibleNotificationItemsFlow = remember(notificationItemsFlow, currentPersonId) {
        notificationCenterViewModel.visibleItemsFor(notificationItemsFlow, currentPersonId)
    }
    val notificationItems by visibleNotificationItemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val notificationUnseenFlow = remember(visibleNotificationItemsFlow, currentPersonId) {
        notificationCenterViewModel.unseenCountFor(visibleNotificationItemsFlow, currentPersonId)
    }
    val notificationUnseenCount by notificationUnseenFlow.collectAsStateWithLifecycle(initialValue = 0)
    // "Fix the Notification Sound system" — sound for this balloon (and for
    // Forward Requests/Group Chat below) now fires from
    // NotificationSoundCoordinator, Application-wide, not nested here — see
    // its own doc comment.

    // "Group Chat Setting" Chat Box icon (spec §6) — every active member
    // gets this regardless of account context; membership is keyed by
    // personId (GroupChat.participantIds), so it needs no role-specific
    // wiring beyond currentPersonId, already resolved above.
    val chatBoxEntriesFlow = remember(currentPersonId) { groupChatViewModel.chatBoxEntriesFor(currentPersonId) }
    val chatBoxEntries by chatBoxEntriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val showToast = rememberActionToast()

    // "Back should return only to the navigation menu" — set right before a
    // drawer item's own onNavigate/onSwitchToPublisher/onSignOut closes the
    // drawer and navigates away; survives that trip (rememberSaveable, tied
    // to this destination's own backstack entry) so the LaunchedEffect below
    // can tell "just came back from a screen the side menu opened" apart
    // from every other way of landing on this Main Form (first sign-in,
    // switching from Publisher context, etc.), which should still show the
    // plain dashboard, not the menu snapping back open uninvited.
    var reopenDrawerOnReturn by rememberSaveable { mutableStateOf(false) }

    // Navigation-Compose restores this composable's saved DrawerState
    // (rememberDrawerState is itself Saveable) when returning here from a
    // side-panel-opened screen — including, occasionally, still mid-"Open"
    // if the animated drawerState.close() below got cancelled by
    // navigating away before its animation finished (this composable's own
    // coroutineScope is torn down the moment Compose Navigation swaps in
    // the destination screen). That left the drawer springing back open on
    // its own the next time the Main Form was shown, which read as "the
    // back button closes the entire side panel" — the drawer visibly
    // slamming open, then shut, instead of a plain Back to the Main Form.
    // Snapping closed unconditionally on every (re)composition removed that
    // race outright — now refined to *reopen* instead, but only on the one
    // path (`reopenDrawerOnReturn`) that means "the user tapped Back on a
    // screen the drawer itself opened," so it reads as "Back just returns
    // to the menu" rather than closing it entirely.
    LaunchedEffect(Unit) {
        if (reopenDrawerOnReturn) {
            reopenDrawerOnReturn = false
            drawerState.open()
        } else {
            drawerState.snapTo(DrawerValue.Closed)
        }
    }

    // "Back Button and Page Navigation" spec §6/§7 — this is the Main Form
    // (root/home screen): the drawer, if open, must close on Back *before*
    // anything else (never navigate away or exit while it's open), and Back
    // from here otherwise means "exit the app," which needs its own
    // confirmation rather than silently closing GoPreach — Compose Navigation
    // has nothing left to pop to at this destination, so an unguarded Back
    // would finish the Activity immediately with no chance to back out.
    //
    // Uses [DrawerState.targetValue], not [DrawerState.isOpen] (which reads
    // `currentValue`, only updated once the open animation *finishes*) — a
    // Back press during the brief window while the drawer is still animating
    // open used to read as "drawer closed," so it fell through to the
    // exit-confirmation dialog instead of just dismissing the side menu the
    // user was still looking at. `targetValue` flips the instant `.open()`
    // is called, so Back reliably just closes the side menu the whole time
    // it's open or opening, never skipping past it to "Exit GoPreach?".
    val activity = LocalContext.current as? ComponentActivity
    var showExitConfirm by remember { mutableStateOf(false) }
    val drawerOpenOrOpening = drawerState.targetValue == DrawerValue.Open
    BackHandler(enabled = drawerOpenOrOpening) {
        coroutineScope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerOpenOrOpening) {
        showExitConfirm = true
    }
    if (showExitConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.home_exit_title)) },
            text = { Text(stringResource(R.string.home_exit_message)) },
            confirmButton = { TextButton(onClick = { activity?.finish() }) { Text(stringResource(R.string.home_exit_confirm)) } },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(R.string.home_exit_cancel)) } },
        )
    }

    // Pull-to-refresh here is deliberately decoupled from both app-update
    // checking and from uploading pending changes (spec: "Refresh, Automatic
    // Updates, Offline Sync" — the three must stay completely independent;
    // this used to call both syncScheduler.requestSyncNow() *and*
    // updateViewModel.checkManually(), which was exactly the bug that spec
    // called out). See HomeViewModel.refreshData()'s doc comment for what
    // Refresh actually still does.
    var isRefreshing by remember { mutableStateOf(false) }

    // Drag from the side panel to Quick Access: shared drag state, the modules the side panel currently offers, and the overlay origin.
    val dragState = remember { QuickAccessDragState() }
    var sideItems by remember { mutableStateOf<List<com.emfitsolutions.gopreach.ui.components.SideItem>>(emptyList()) }
    var contentOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GoPreachSidePanelContent(
                activeRoute = Destinations.ADMIN_HOME,
                canManageCongregationsAndAdmins = isSuperAdmin,
                canEnrollCoordinatorElder = canEnrollCoordinatorElder,
                canEnrollServiceOverseer = canEnrollServiceOverseer,
                canEnrollMinisterialServant = canEnrollMinisterialServant,
                canManageAnnouncements = canManageAnnouncements,
                canViewFieldServiceReport = canViewFieldServiceGroupReport,
                // Deleted Records: Super-Admin and the roles that can manage their congregation's records.
                canViewDeletedRecords = isSuperAdmin || canEnrollPublisher,
                canViewForwardRequests = canViewForwardRequests,
                canManageHouseholderAssignment = canManageHouseholderAssignment,
                canEnrollRegularElderOrPublisher = canManageRegularEldersForDrawer,
                canEnrollPublisher = canEnrollPublisher,
                canManagePublishersAndGroups = canManagePublishersAndGroups,
                canManageGroups = canManageGroups,
                canManageTerritoryAssignments = canManageTerritoryAssignments,
                canManageTerritories = canViewTerritoryMap,
                canEditMeetingAssignments = canEditMeetingAssignments,
                canAccessControlPanel = canAccessControlPanel,
                isSuperAdmin = isSuperAdmin,
                canViewUserLogs = canViewUserLogs,
                canManageUsers = canManageUsers,
                canManageAccountCredentials = canManageAccountCredentials,
                canViewContactRecord = canViewContactRecord,
                // Same role set: Super-Admin, Admin, Coordinator Elder, Regular
                // Elder, Service Overseer, Secretary.
                canManageSessionTimeout = canViewContactRecord,
                canEnterManualFieldService = role in com.emfitsolutions.gopreach.ui.screens.manualreport.ManualEntryRoles,
                canViewInterestedPeopleScope = canViewInterestedPeopleScope,
                onSwitchToPublisher = onSwitchToPublisher?.let { switchAction ->
                    { coroutineScope.launch { drawerState.close() }; switchAction() }
                },
                onNavigate = { route ->
                    // Tapping Back on whatever this opens should return to
                    // the side menu, not the plain dashboard underneath it.
                    reopenDrawerOnReturn = true
                    coroutineScope.launch { drawerState.close() }
                    onNavigate(route)
                },
                onItemsAvailable = { sideItems = it },
                dragState = dragState,
                onDragStarted = { coroutineScope.launch { drawerState.close() } },
                onSignOut = {
                    coroutineScope.launch { drawerState.close() }
                    viewModel.signOut()
                },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { contentOrigin = it.positionInRoot() }) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                coroutineScope.launch {
                    viewModel.refreshData()
                    // Every screen already renders off a continuously-live Room
                    // cache (see HomeViewModel.refreshData()) — this brief delay is
                    // purely so the pull gesture gives visible feedback rather than
                    // resolving instantly, not a real network wait.
                    delay(400)
                    isRefreshing = false
                }
            },
        ) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                com.emfitsolutions.gopreach.ui.components.AlarmRingingBanner()
                com.emfitsolutions.gopreach.ui.components.OfflineSessionBanner()
                DashboardHero(
                    greetingName = session.person?.firstName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.greeting_fallback_name),
                    roleLabel = role?.name?.replace('_', ' ') ?: stringResource(R.string.role_label_admin_fallback),
                    isOnline = isOnline,
                    pendingSyncCount = pendingSyncCount,
                    leadingAction = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    topEndAction = {
                        // "Remove the setting from the main form... put the
                        // notification bell on the upper right side of the
                        // form next to user image" — the gear icon used to
                        // sit here between the two; Settings itself is still
                        // reachable from the profile menu (see
                        // ProfileMenuButton's onOpenSettings doc comment).
                        if (showNotificationBell) {
                            NotificationBell(
                                items = notificationItems,
                                unseenCount = notificationUnseenCount,
                                onOpen = { notificationCenterViewModel.markAllSeen(currentPersonId) },
                                onItemClick = { onNavigate(it.route) },
                                onDismiss = { notificationCenterViewModel.dismiss(it, currentPersonId) },
                                onClearAll = { notificationCenterViewModel.dismissAll(notificationItems, currentPersonId) },
                            )
                        }
                        com.emfitsolutions.gopreach.ui.components.ChatBoxIcon(
                            entries = chatBoxEntries,
                            onOpenGroupChat = { chatId -> onNavigate(Destinations.groupChatDetail(chatId)) },
                            onViewAll = { onNavigate(Destinations.GROUP_CHAT_SETTING) },
                        )
                        // "Add a refresh button only not sync" — a plain
                        // re-fetch from the server, separate from the full
                        // "Sync to Server" button below.
                        com.emfitsolutions.gopreach.ui.components.RefreshButton()
                        ProfileMenuButton(
                            fullName = session.person?.fullName ?: "—",
                            roleLabel = role?.name?.replace('_', ' ') ?: stringResource(R.string.role_label_admin_fallback),
                            profileImageUrl = session.person?.profileImageUrl,
                            onImagePicked = { uri ->
                                viewModel.updateProfileImage(uri, onImageUploadFailed = {
                                    showToast("Profile image failed to upload. Try again.")
                                })
                            },
                            onSignOut = viewModel::signOut,
                            onOpenSettings = { onNavigate(Destinations.SETTINGS) },
                        )
                    },
                    quickActions = if (hideMainFormButtons) {
                        emptyList()
                    } else {
                        buildList {
                            if (role != null) add(QuickAction(stringResource(R.string.home_dashboard_header), Icons.Rounded.BarChart) { onNavigate(Destinations.DASHBOARD_REPORTS) })
                            if (canManagePublishersAndGroups) add(QuickAction(stringResource(R.string.dashboard_quick_action_publishers), Icons.Rounded.People) { onNavigate(Destinations.MANAGE_PUBLISHERS) })
                            if (canManagePublishersAndGroups) add(QuickAction(stringResource(R.string.side_groups), Icons.Rounded.Groups) { onNavigate(Destinations.MANAGE_GROUPS) })
                            if (role != null) add(QuickAction(stringResource(R.string.home_nav_calendar), Icons.Rounded.CalendarMonth) { onNavigate(Destinations.CALENDAR) })
                        }
                    },
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    SyncToServerButton()

                    // "My Group / My Congregation" summary card — an Elder's own
                    // scope (already enforced everywhere via visibleCongregationIds)
                    // labeled with its actual name, so the numbers below aren't the
                    // only way to tell which congregation/group they refer to.
                    if (role == AdminRole.COORDINATOR_ELDER) {
                        MyScopeSummaryCard(congregationId = ownCongregationId, groupId = null)
                    } else if (role == AdminRole.REGULAR_ELDER) {
                        MyScopeSummaryCard(congregationId = ownGroupCongregationId, groupId = ownGroupAssignment?.groupId)
                    }

                    // Super-Admin/Admin: the graphical Summary (KPI cards + charts)
                    // shows directly on the main form instead of a tile grid — every
                    // *navigation* button (Publishers, Groups, Enrollment, Control
                    // Panel, Sign Out, ...) moved to the Side Panel, but the
                    // dashboard's own reporting content stays front and center here.
                    if (hideMainFormButtons) {
                        DashboardStatsContent(
                            visibleCongregationIds = visibleCongregationIds,
                            recentlyVisited = if (!showRecentlyVisited) null else { stats ->
                                // Super-Admin: nothing is shown until a Congregation is selected (stats.congregationId is blank for "All").
                                val viewer = stats.congregationId.takeIf { it.isNotBlank() }?.let { RecentlyVisitedViewer.Admin(it) }
                                DashboardActivitySections(
                                    viewer = viewer,
                                    onOpen = { item ->
                                        if (canManageHouseholderAssignment) {
                                            com.emfitsolutions.gopreach.ui.screens.pipeline.PublisherAssignmentPreset.set(item.person.id)
                                            com.emfitsolutions.gopreach.ui.components.CongregationContextStore.set("publisher_assignment", item.person.congregationId)
                                            onNavigate(Destinations.PUBLISHER_ASSIGNMENT)
                                        } else {
                                            onNavigate(Destinations.SCOPED_INTERESTED_RECORDS)
                                        }
                                    },
                                )
                            },
                            quickAccess = { stats ->
                                val allowedQuickAccess = buildSet {
                                    if (canManageTerritoryAssignments) add(QuickAccessItem.TERRITORY_ASSIGNMENT)
                                    if (canViewTerritoryMap) add(QuickAccessItem.TERRITORY_MAP)
                                    if (canManageHouseholderAssignment) add(QuickAccessItem.PUBLISHER_ASSIGNMENT)
                                    if (canManageHouseholderAssignment) add(QuickAccessItem.COMPARATIVE_REPORT)
                                    if (canEnrollPublisher) addAll(listOf(QuickAccessItem.PUBLISHER_MODULE, QuickAccessItem.REGULAR_PIONEERS, QuickAccessItem.AUXILIARY_PIONEERS, QuickAccessItem.SPECIAL_PIONEERS, QuickAccessItem.UNBAPTIZED_PUBLISHERS))
                                    if (canViewFieldServiceGroupReport) add(QuickAccessItem.FIELD_SERVICE_REPORT)
                                    if (canManageRegularEldersForDrawer) add(QuickAccessItem.TOTAL_ELDERS)
                                    if (canEnrollMinisterialServant) add(QuickAccessItem.TOTAL_MINISTERIAL)
                                }
                                QuickAccessSection(
                                    personId = currentPersonId,
                                    stats = stats,
                                    allowed = allowedQuickAccess,
                                    sideItems = sideItems,
                                    dragState = dragState,
                                    onOpen = { entry ->
                                        // Pioneer cards open the Publishers list already filtered to that category; every other card is plain navigation.
                                        com.emfitsolutions.gopreach.ui.screens.publishers.PublisherListPreset.set(entry.category)
                                        onNavigate(entry.route)
                                    },
                                )
                            },
                        )
                    }

                    if (!hideMainFormButtons) {
                        if (role != null) {
                            DashboardSection(stringResource(R.string.dashboard_section_graphical_reports)) {
                                DashboardTile(stringResource(R.string.dashboard_tile_reports_dashboard), Icons.Rounded.BarChart, { onNavigate(Destinations.DASHBOARD_REPORTS) })
                            }
                        }

                        if (canManagePublishersAndGroups) {
                            DashboardSection(stringResource(R.string.dashboard_section_management)) {
                                DashboardTile(stringResource(R.string.dashboard_quick_action_publishers), Icons.Rounded.People, { onNavigate(Destinations.MANAGE_PUBLISHERS) })
                                DashboardTile(stringResource(R.string.side_groups), Icons.Rounded.Groups, { onNavigate(Destinations.MANAGE_GROUPS) })
                            }
                        }

                        // Wider than the Management section above — also
                        // reaches Service Overseer/Regular Elder, own
                        // congregation only (see canViewTerritoryMap).
                        if (canViewTerritoryMap || canEditMeetingAssignments) {
                            DashboardSection(stringResource(R.string.dashboard_section_territory)) {
                                if (canViewTerritoryMap) {
                                    DashboardTile(stringResource(R.string.side_territory_map), Icons.Rounded.Map, { onNavigate(Destinations.MANAGE_TERRITORIES_BASE) })
                                }
                                if (canEditMeetingAssignments) {
                                    DashboardTile(stringResource(R.string.home_tile_meeting_cart_assignment_title), Icons.Rounded.Event, { onNavigate(Destinations.MEETING_ASSIGNMENTS) })
                                }
                            }
                        }

                        if (role != null) {
                            DashboardSection(stringResource(R.string.dashboard_section_ministry)) {
                                DashboardTile(stringResource(R.string.side_group_chat_setting), Icons.Rounded.Chat, { onNavigate(Destinations.GROUP_CHAT_SETTING) })
                                DashboardTile(stringResource(R.string.dashboard_tile_share_location), Icons.Rounded.LocationOn, { onNavigate(Destinations.SHARE_LOCATION) })
                                DashboardTile(stringResource(R.string.home_tile_find_location_title), Icons.Rounded.Navigation, { onNavigate(Destinations.FIND_LOCATION) })
                                DashboardTile(stringResource(R.string.home_nav_calendar), Icons.Rounded.CalendarMonth, { onNavigate(Destinations.CALENDAR) })
                            }
                        }

                        if (isSuperAdmin || canEnrollCoordinatorElder || canEnrollRegularElderOrPublisher) {
                            DashboardSection(stringResource(R.string.side_section_enrollment)) {
                                if (isSuperAdmin) {
                                    DashboardTile(stringResource(R.string.side_congregations_groups), Icons.Rounded.AccountBalance, { onNavigate(Destinations.MANAGE_CONGREGATIONS) })
                                    DashboardTile(stringResource(R.string.side_admins), Icons.Rounded.AdminPanelSettings, { onNavigate(Destinations.MANAGE_ADMINS) })
                                }
                                if (canEnrollRegularElderOrPublisher) {
                                    // "Consolidate Elder, Coordinator Elder,
                                    // Service Overseer and Secretary
                                    // Enrollment" — one "Elders" tile
                                    // replaces the separate Coordinator
                                    // Elder/Regular Elder tiles this old
                                    // tile-grid layout used to show (see
                                    // SidePanel's own identical
                                    // consolidation for the drawer this
                                    // grid is otherwise superseded by).
                                    DashboardTile(stringResource(R.string.side_elders), Icons.Rounded.PersonAdd, { onNavigate(Destinations.MANAGE_ELDERS) })
                                    DashboardTile(stringResource(R.string.side_publisher), Icons.Rounded.PersonAdd, { onNavigate(Destinations.ENROLL_PUBLISHER) })
                                }
                            }
                        }

                        if (canAccessControlPanel || isSuperAdmin || canViewUserLogs || canManageUsers || canManageAccountCredentials) {
                            DashboardSection(stringResource(R.string.dashboard_section_system)) {
                                if (canAccessControlPanel) {
                                    DashboardTile(stringResource(R.string.dashboard_tile_control_panel), Icons.Rounded.Tune, { onNavigate(Destinations.CONTROL_PANEL) })
                                }
                                if (isSuperAdmin) {
                                    DashboardTile(stringResource(R.string.side_backup_restore), Icons.Rounded.Backup, { onNavigate(Destinations.BACKUP_RESTORE) })
                                }
                                if (canViewUserLogs) {
                                    DashboardTile(stringResource(R.string.side_user_logs), Icons.Rounded.History, { onNavigate(Destinations.USER_LOGS) })
                                }
                                if (canManageUsers) {
                                    DashboardTile(stringResource(R.string.side_user_management), Icons.Rounded.ManageAccounts, { onNavigate(Destinations.MANAGE_USERS) })
                                }
                                if (canManageAccountCredentials) {
                                    DashboardTile(stringResource(R.string.side_account_management), Icons.Rounded.ManageAccounts, { onNavigate(Destinations.ACCOUNT_MANAGEMENT) })
                                }
                                if (canAccessControlPanel) {
                                    DashboardTile(stringResource(R.string.side_credit_hour_categories), Icons.Rounded.Timer, { onNavigate(Destinations.CREDIT_HOUR_CATEGORIES) })
                                }
                            }
                        }

                        DashboardSection(stringResource(R.string.dashboard_section_account)) {
                            DashboardTile(stringResource(R.string.side_account_settings), Icons.Rounded.Password, { onNavigate(Destinations.ACCOUNT_SETTINGS) })
                            if (onSwitchToPublisher != null) {
                                DashboardTile(stringResource(R.string.side_ministry_report_app), Icons.Rounded.SwapHoriz, onSwitchToPublisher)
                            }
                            DashboardTile(stringResource(R.string.side_sign_out), Icons.AutoMirrored.Rounded.Logout, viewModel::signOut)
                        }
                    }
                }
            }
        }
        // Chip that follows the finger while a side-panel module is dragged toward Quick Access.
        dragState.item?.let { dragged ->
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.offset {
                    androidx.compose.ui.unit.IntOffset(
                        (dragState.rootPos.x - contentOrigin.x).toInt() - 24.dp.roundToPx(),
                        (dragState.rootPos.y - contentOrigin.y).toInt() - 56.dp.roundToPx(),
                    )
                },
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(dragged.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(dragged.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        } // drag overlay Box
    }
}

// "Fix the Notification Sound system" — ForwardRequestNotifier used to live
// here; its logic is now covered by NotificationSoundCoordinator's unified
// item watcher (Application-scoped), which already includes pending
// cross-congregation Forward Requests in its Transfer Request category.
