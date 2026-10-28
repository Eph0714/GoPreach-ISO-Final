package com.emfitsolutions.gopreach.ui.screens.home

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.dashboard_quick_action_publishers
import com.emfitsolutions.gopreach.shared.resources.dashboard_section_account
import com.emfitsolutions.gopreach.shared.resources.dashboard_section_graphical_reports
import com.emfitsolutions.gopreach.shared.resources.dashboard_section_management
import com.emfitsolutions.gopreach.shared.resources.dashboard_section_ministry
import com.emfitsolutions.gopreach.shared.resources.dashboard_section_system
import com.emfitsolutions.gopreach.shared.resources.dashboard_section_territory
import com.emfitsolutions.gopreach.shared.resources.dashboard_tile_control_panel
import com.emfitsolutions.gopreach.shared.resources.dashboard_tile_reports_dashboard
import com.emfitsolutions.gopreach.shared.resources.dashboard_tile_share_location
import com.emfitsolutions.gopreach.shared.resources.greeting_fallback_name
import com.emfitsolutions.gopreach.shared.resources.home_dashboard_header
import com.emfitsolutions.gopreach.shared.resources.home_exit_cancel
import com.emfitsolutions.gopreach.shared.resources.home_exit_confirm
import com.emfitsolutions.gopreach.shared.resources.home_exit_message
import com.emfitsolutions.gopreach.shared.resources.home_exit_title
import com.emfitsolutions.gopreach.shared.resources.home_nav_calendar
import com.emfitsolutions.gopreach.shared.resources.home_tile_find_location_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_weather_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_meeting_cart_assignment_title
import com.emfitsolutions.gopreach.shared.resources.role_label_admin_fallback
import com.emfitsolutions.gopreach.shared.resources.side_account_management
import com.emfitsolutions.gopreach.shared.resources.side_account_settings
import com.emfitsolutions.gopreach.shared.resources.side_admins
import com.emfitsolutions.gopreach.shared.resources.side_backup_restore
import com.emfitsolutions.gopreach.shared.resources.side_congregations_groups
import com.emfitsolutions.gopreach.shared.resources.side_credit_hour_categories
import com.emfitsolutions.gopreach.shared.resources.side_elders
import com.emfitsolutions.gopreach.shared.resources.side_group_chat_setting
import com.emfitsolutions.gopreach.shared.resources.side_groups
import com.emfitsolutions.gopreach.shared.resources.side_ministry_report_app
import com.emfitsolutions.gopreach.shared.resources.side_publisher
import com.emfitsolutions.gopreach.shared.resources.side_section_enrollment
import com.emfitsolutions.gopreach.shared.resources.side_sign_out
import com.emfitsolutions.gopreach.shared.resources.side_territory_map
import com.emfitsolutions.gopreach.shared.resources.side_user_logs
import com.emfitsolutions.gopreach.shared.resources.side_user_management
import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.platform.AppPermission
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import com.emfitsolutions.gopreach.ui.components.AdminCapabilities
import com.emfitsolutions.gopreach.ui.components.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.rounded.Home
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
import org.jetbrains.compose.resources.stringResource
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
    // Unified multi-role account: [role] is the most senior Admin-track role (it scopes the session); every permission flag below is the
    // union of what ALL the roles consolidated into this session grant (see AdminCapabilities), so no role is ever chosen at login.
    val role = session.activeAdminRole
    val currentPersonId = session.person?.id.orEmpty()
    val caps = remember(session.person, session.roleAssignments, session.grant, session.groups) { AdminCapabilities.of(session) }

    val isSuperAdmin = caps.isSuperAdmin
    val canEnrollCoordinatorElder = caps.canEnrollCoordinatorElder
    val canEnrollRegularElderOrPublisher = caps.canEnrollRegularElderOrPublisher
    val canEnrollPublisher = caps.canEnrollPublisher
    val canEnrollServiceOverseer = caps.canEnrollServiceOverseer
    val canEnrollMinisterialServant = caps.canEnrollMinisterialServant
    val canManageAnnouncements = caps.canManageAnnouncements
    val canViewConsolidatedReport = caps.canViewConsolidatedReport
    val canViewFieldServiceGroupReport = caps.canViewFieldServiceGroupReport
    val canViewForwardRequests = caps.canViewForwardRequests
    val canManageHouseholderAssignment = caps.canManageHouseholderAssignment
    val showRecentlyVisited = caps.showRecentlyVisited
    val canManagePublisherReports = caps.canManagePublisherReports
    val canAccessControlPanel = caps.canAccessControlPanel
    val canViewUserLogs = caps.canViewUserLogs
    val canViewContactRecord = caps.canViewContactRecord
    val canManagePublishersAndGroups = caps.canManagePublishersAndGroups
    val isMinisterialServant = caps.simpleMenu
    val canViewTerritoryMap = caps.canViewTerritoryMap
    val canViewInterestedPeopleScope = caps.canViewInterestedPeopleScope
    val canEditMeetingAssignments = caps.canEditMeetingAssignments
    val canManageGroups = caps.canManageGroups
    val canManageTerritoryAssignments = caps.canManageTerritoryAssignments
    val canManageRegularEldersForDrawer = caps.canEnrollRegularElderOrPublisher
    // The Main Form is the drawer+stats shell for every Admin-track role (Circuit Overseer included), never the old tile grid.
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

    val notificationPermissionLauncher = rememberPermissionRequester() {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(AppPermission.NOTIFICATIONS)
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
            title = { Text(stringResource(Res.string.home_exit_title)) },
            text = { Text(stringResource(Res.string.home_exit_message)) },
            confirmButton = { ActionButton(ActionKind.Save, onClick = { activity?.finish() }, label = stringResource(Res.string.home_exit_confirm)) },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = { showExitConfirm = false }, label = stringResource(Res.string.home_exit_cancel)) },
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
    // The tab strip's modules for this role (the same permissions the drawer and Quick Access used for them).
    val tabsAllowed = caps.tabModules
    androidx.compose.runtime.SideEffect { com.emfitsolutions.gopreach.ui.components.AdminTabsStore.allowed = tabsAllowed }
    // The panel's profile header names the user's congregation (their scope).
    val congregationRepository = org.koin.compose.koinInject<com.emfitsolutions.gopreach.data.repository.CongregationRepository>()
    val allCongregations by remember(congregationRepository) { congregationRepository.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var sideItems by remember { mutableStateOf<List<com.emfitsolutions.gopreach.ui.components.SideItem>>(emptyList()) }
    var contentOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GoPreachSidePanelContent(
                activeRoute = Destinations.ADMIN_HOME,
                userName = session.person?.fullName.orEmpty(),
                roleLabel = session.activeRoleLabel ?: role?.name?.replace('_', ' ').orEmpty(),
                scopeLabel = when {
                    isSuperAdmin -> "All congregations"
                    role == AdminRole.CIRCUIT_OVERSEER -> "Assigned congregations"
                    else -> allCongregations.firstOrNull { it.id == session.person?.activeCongregationId }?.name.orEmpty()
                },
                profileImageUrl = session.person?.profileImageUrl,
                caps = caps,
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
                    greetingName = session.person?.firstName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.greeting_fallback_name),
                    roleLabel = "Active Role: " + (session.activeRoleLabel ?: role?.name?.replace('_', ' ') ?: stringResource(Res.string.role_label_admin_fallback)),
                    isOnline = isOnline,
                    pendingSyncCount = pendingSyncCount,
                    compact = role == AdminRole.CIRCUIT_OVERSEER,
                    footer = { com.emfitsolutions.gopreach.ui.screens.weather.WeatherSummaryLine(currentPersonId, onClick = { onNavigate(Destinations.WEATHER) }) },
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
                        if (role != AdminRole.CIRCUIT_OVERSEER) com.emfitsolutions.gopreach.ui.components.ChatBoxIcon(
                            entries = chatBoxEntries,
                            onOpenGroupChat = { chatId -> onNavigate(Destinations.groupChatDetail(chatId)) },
                            onViewAll = { onNavigate(Destinations.GROUP_CHAT_SETTING) },
                        )
                        // "Add a refresh button only not sync" — a plain
                        // re-fetch from the server, separate from the full
                        // "Sync to Server" button below.
                        if (role != AdminRole.CIRCUIT_OVERSEER) com.emfitsolutions.gopreach.ui.components.AboutServerButton()
                        com.emfitsolutions.gopreach.ui.components.RefreshButton()
                        // The Circuit Overseer's Sync to Server is a small icon up here instead of a full-width button below.
                        if (role == AdminRole.CIRCUIT_OVERSEER) SyncToServerButton(compact = true)
                        ProfileMenuButton(
                            fullName = session.person?.fullName ?: "—",
                            roleLabel = session.activeRoleLabel ?: role?.name?.replace('_', ' ') ?: stringResource(Res.string.role_label_admin_fallback),
                            online = if (role == AdminRole.CIRCUIT_OVERSEER) isOnline else null,
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
                            if (role != null) add(QuickAction(stringResource(Res.string.home_dashboard_header), Icons.Rounded.BarChart) { onNavigate(Destinations.DASHBOARD_REPORTS) })
                            if (canManagePublishersAndGroups) add(QuickAction(stringResource(Res.string.dashboard_quick_action_publishers), Icons.Rounded.People) { onNavigate(Destinations.MANAGE_PUBLISHERS) })
                            if (canManagePublishersAndGroups) add(QuickAction(stringResource(Res.string.side_groups), Icons.Rounded.Groups) { onNavigate(Destinations.MANAGE_GROUPS) })
                            if (role != null) add(QuickAction(stringResource(Res.string.home_nav_calendar), Icons.Rounded.CalendarMonth) { onNavigate(Destinations.CALENDAR) })
                            if (role != null) add(QuickAction(stringResource(Res.string.home_tile_weather_title), Icons.Rounded.WbSunny) { onNavigate(Destinations.WEATHER) })
                        }
                    },
                )
                if (role != AdminRole.CIRCUIT_OVERSEER) com.emfitsolutions.gopreach.ui.components.AdminMainTabs(com.emfitsolutions.gopreach.ui.components.AdminTab.HOME)
                else com.emfitsolutions.gopreach.ui.components.CircuitMainTabs(null)
                Column(
                    modifier = Modifier.fillMaxSize().padding(if (role == AdminRole.CIRCUIT_OVERSEER) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (role == AdminRole.CIRCUIT_OVERSEER) 8.dp else 20.dp),
                ) {
                    if (role != AdminRole.CIRCUIT_OVERSEER && hideMainFormButtons) SyncToServerButton()

                    // Circuit Overseer: circuit, name, assigned congregations — and the way into their dashboard.
                    if (role == AdminRole.CIRCUIT_OVERSEER) {
                        // The Circuit Overseer's Main Form IS the Circuit Dashboard — no administrative statistics here.
                        com.emfitsolutions.gopreach.ui.screens.circuit.CircuitDashboardBody(
                            currentPersonId = currentPersonId,
                            modifier = Modifier,
                            onOpenReport = { onNavigate(Destinations.CIRCUIT_REPORTS_INBOX) },
                            onOpenPublishers = { onNavigate(Destinations.circuitPublishers()) },
                            onOpenCongregations = { onNavigate(Destinations.CIRCUIT_CONGREGATIONS) },
                            onOpenTerritory = { onNavigate(Destinations.MANAGE_TERRITORIES_BASE) },
                            onOpenCircuitReport = { onNavigate(Destinations.CIRCUIT_REPORT) },
                            onOpenAttendance = { onNavigate(Destinations.MEETING_ATTENDANCE) },
                            onOpenComparative = { onNavigate(Destinations.CONGREGATION_COMPARATIVE) },
                            onOpenReportSubmission = { onNavigate(Destinations.REPORT_SUBMISSION) },
                            onOpenCongregation = { onNavigate(Destinations.circuitCongregation(it)) },
                            onOpenLeaders = { onNavigate(Destinations.circuitLeaders()) },
                            onOpenReceived = { onNavigate(Destinations.RECEIVED_REPORTS) },
                            onOpenSnapshot = { onNavigate(Destinations.RECEIVED_SNAPSHOTS) },
                            onOpenAnnouncements = { onNavigate(Destinations.CO_ANNOUNCEMENTS) },
                            onOpenManualRecord = { onNavigate(Destinations.CO_MANUAL_RECORD) },
                            onNavigateRoute = { onNavigate(it) },
                            onOpenFileManager = { onNavigate(Destinations.CO_FILE_MANAGER) },
                        )
                    }

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
                    if (hideMainFormButtons && role != AdminRole.CIRCUIT_OVERSEER) {
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
                                val hasCongregation = !session.person?.activeCongregationId.isNullOrBlank() && role != AdminRole.CIRCUIT_OVERSEER
                                // A Ministerial Servant keeps a short list: the modules the role already uses or only views (no management module).
                                val allowedQuickAccess = if (isMinisterialServant) buildSet {
                                    add(QuickAccessItem.TERRITORY_MAP); add(QuickAccessItem.WEATHER)
                                    if (hasCongregation) { add(QuickAccessItem.MY_ASSIGNMENTS); add(QuickAccessItem.MEETING_CART_ASSIGNMENT); add(QuickAccessItem.PUBLISHER_SCHEDULE); add(QuickAccessItem.ANNOUNCEMENT)
                                        if (session.isGroupRole) add(QuickAccessItem.FIELD_SERVICE_REPORT)   // a group-level Ministerial Servant: their own FS Group report
                                    }
                                    add(QuickAccessItem.SETTINGS)
                                } else buildSet {
                                    if (canManageTerritoryAssignments) add(QuickAccessItem.TERRITORY_ASSIGNMENT)
                                    if (canManageHouseholderAssignment) add(QuickAccessItem.PUBLISHER_ASSIGNMENT)
                                    if (canViewInterestedPeopleScope) add(QuickAccessItem.HOUSEHOLDER_RECORDS)
                                    if (role in setOf(AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY)) add(QuickAccessItem.COMPARATIVE_REPORT)
                                    if (canEnrollPublisher) addAll(listOf(QuickAccessItem.REGULAR_PIONEERS, QuickAccessItem.AUXILIARY_PIONEERS, QuickAccessItem.SPECIAL_PIONEERS, QuickAccessItem.UNBAPTIZED_PUBLISHERS))
                                    add(QuickAccessItem.SETTINGS)
                                    if (canManageGroups) add(QuickAccessItem.FIELD_SERVICE_GROUP)
                                    // The other Quick Access modules are offered exactly where the side panel offers them (or where this screen's own role check allows them), so a card never
                                    // reaches a module the role could not already open: no new permission comes from Quick Access.
                                    val offered = sideItems.map { it.route }.toSet()
                                    if (canViewTerritoryMap) add(QuickAccessItem.TERRITORY_MAP)
                                    add(QuickAccessItem.WEATHER)
                                    if (Destinations.FIELD_SERVICE_REPORT in offered) add(QuickAccessItem.FIELD_SERVICE_REPORT)
                                    if (Destinations.MANAGE_PUBLISHERS in offered) add(QuickAccessItem.PUBLISHER_MODULE)
                                    if (hasCongregation) add(QuickAccessItem.MY_ASSIGNMENTS)
                                    if (hasCongregation) add(QuickAccessItem.HOUSEHOLDER_VISIT_HISTORY)
                                    if (hasCongregation) add(QuickAccessItem.MEETING_CART_ASSIGNMENT)   // read-only for roles that may not edit (the screen decides)
                                    if (hasCongregation) add(QuickAccessItem.PUBLISHER_SCHEDULE)
                                    if (role != AdminRole.CIRCUIT_OVERSEER) add(QuickAccessItem.FIND_LOCATION)
                                    if (hasCongregation) add(QuickAccessItem.ANNOUNCEMENT)   // read-only for everyone who may not manage announcements
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
                }
            }
            // Circuit Overseer: bottom navigation (Home | Congregations | Reports | More); Home is this dashboard.
            if (role == AdminRole.CIRCUIT_OVERSEER) {
                androidx.compose.material3.NavigationBar(modifier = Modifier.align(Alignment.BottomCenter), containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(androidx.compose.material.icons.Icons.Rounded.Home, contentDescription = null) }, label = { Text("Home") })
                    NavigationBarItem(selected = false, onClick = { onNavigate(Destinations.CIRCUIT_CONGREGATIONS) }, icon = { Icon(androidx.compose.material.icons.Icons.Rounded.AccountBalance, contentDescription = null) }, label = { Text("Congregations") })
                    NavigationBarItem(selected = false, onClick = { onNavigate(Destinations.REPORT_SUBMISSION) }, icon = { Icon(androidx.compose.material.icons.Icons.Rounded.Assessment, contentDescription = null) }, label = { Text("Reports") })
                    NavigationBarItem(selected = false, onClick = { coroutineScope.launch { drawerState.open() } }, icon = { Icon(Icons.Rounded.Menu, contentDescription = null) }, label = { Text("More") })
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
