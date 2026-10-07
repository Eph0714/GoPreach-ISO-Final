package com.emfitsolutions.gopreach.ui.screens.home

import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.action_cancel
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_confirm_to_main_form
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_confirm_to_side_panel
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_move_module
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_move_to_main_form
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_move_to_side_panel
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_moved_success
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_reset_confirm
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_reset_confirm_button
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_reset_success
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_reset_title
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_side_panel_empty
import com.emfitsolutions.gopreach.shared.resources.dashboard_layout_side_panel_title
import com.emfitsolutions.gopreach.shared.resources.home_exit_cancel
import com.emfitsolutions.gopreach.shared.resources.home_exit_confirm
import com.emfitsolutions.gopreach.shared.resources.home_exit_message
import com.emfitsolutions.gopreach.shared.resources.home_exit_title
import com.emfitsolutions.gopreach.shared.resources.home_no
import com.emfitsolutions.gopreach.shared.resources.home_status_all_synced_suffix
import com.emfitsolutions.gopreach.shared.resources.home_status_offline
import com.emfitsolutions.gopreach.shared.resources.home_status_online
import com.emfitsolutions.gopreach.shared.resources.home_status_pending_sync_suffix
import com.emfitsolutions.gopreach.shared.resources.home_switch_to_admin
import com.emfitsolutions.gopreach.shared.resources.home_tile_announcement_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_announcement_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_bible_study_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_bible_study_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_find_location_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_find_location_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_forwarded_to_me_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_forwarded_to_me_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_group_chat_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_group_chat_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_householder_visit_history_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_householder_visit_history_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_incoming_assignments_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_incoming_assignments_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_meeting_cart_assignment_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_meeting_cart_assignment_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_monthly_report_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_monthly_report_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_assignments_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_assignments_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_bible_text_record_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_bible_text_record_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_calendar_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_calendar_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_reports_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_reports_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_total_hours_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_my_total_hours_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_publisher_schedules_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_publisher_schedules_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_return_visit_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_return_visit_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_searching_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_searching_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_share_my_location_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_share_my_location_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_territory_map_subtitle
import com.emfitsolutions.gopreach.shared.resources.home_tile_territory_map_title
import com.emfitsolutions.gopreach.shared.resources.home_welcome
import com.emfitsolutions.gopreach.shared.resources.home_yes
import com.emfitsolutions.gopreach.shared.resources.role_label_publisher
import com.emfitsolutions.gopreach.platform.rememberPermissionRequester
import com.emfitsolutions.gopreach.platform.AppPermission
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.mutableStateListOf
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.ui.screens.planner.plannerPersonListRoute
import com.emfitsolutions.gopreach.ui.screens.planner.plannerPersonRoute
import com.emfitsolutions.gopreach.ui.screens.planner.LocalOpenCreditCategoryManager
import com.emfitsolutions.gopreach.ui.screens.planner.LocalPlannerVisibility
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerSectionsDialog
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerVisibility
import com.emfitsolutions.gopreach.ui.screens.planner.UnifiedPlannerHeader
import com.emfitsolutions.gopreach.data.model.PlannerSection
import com.emfitsolutions.gopreach.data.model.isPlannerSectionVisible
import com.emfitsolutions.gopreach.ui.components.DateRange
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AssignmentInd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.DashboardModuleId
import com.emfitsolutions.gopreach.data.model.DashboardModuleLocation
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.mainFormModules
import com.emfitsolutions.gopreach.data.model.sidePanelModules
import com.emfitsolutions.gopreach.ui.components.DateRangeFilterBar
import com.emfitsolutions.gopreach.ui.components.NotificationBell
import com.emfitsolutions.gopreach.ui.components.ProfileMenuButton
import com.emfitsolutions.gopreach.ui.components.QuickDateRange
import com.emfitsolutions.gopreach.ui.components.RoundIconActionButton
import com.emfitsolutions.gopreach.ui.components.SyncToServerButton
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.navigation.Destinations
import com.emfitsolutions.gopreach.ui.screens.announcements.ManageAnnouncementsViewModel
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationCenterViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerComparativeContent
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerDayContent
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerDayViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerMonthContent
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerMonthViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerWeekContent
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerWeekViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerYearContent
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerYearViewModel
import androidx.compose.ui.window.DialogProperties

/**
 * Landing point for the Ministry Report App / Publisher context (spec §5.2).
 *
 * Redesigned per a supplied reference mockup: a gradient welcome header, a
 * "Dashboard" card wrapping the existing date-range filter, a two-column
 * feature tile grid with notification-style badges, a Quick Summary of real
 * (never fabricated — spec §1/§24) figures, a sync card, and a bottom
 * navigation bar. Colors are drawn entirely from [MaterialTheme.colorScheme]
 * (the user's chosen [com.emfitsolutions.gopreach.ui.theme.ThemeColorOption],
 * purple by default) rather than the reference's own green/amber/blue
 * per-tile palette — this app's design language deliberately stays to one
 * accent color, tonal shades only (see Color.kt: "do not leave old
 * green/blue/yellow accents").
 *
 * "Role-Based Publisher Dashboard" spec §1/§21 — the square statistic cards
 * differ by [PublisherCategory]: Pioneer gets My Bible Studies/My Return
 * Visits/Preaching Hours; Regular/Unbaptized get My Bible Studies/Attended
 * Preaching only, never Preaching Hours or Preaching Time Record access
 * (spec §17/§20). Every value comes from [PublisherDashboardViewModel]'s
 * real database queries — never hard-coded.
 */
@Composable
fun PublisherHomeScreen(
    onSwitchToAdmin: (() -> Unit)?,
    onNavigate: (String) -> Unit,
    // Super-Admin / Admin — lets the Credit Hours form's empty state link
    // straight to category management; Publishers get a "contact an
    // administrator" message instead.
    canManageCreditHourCategories: Boolean = false,
    viewModel: HomeViewModel = koinViewModel(),
    dashboardViewModel: PublisherDashboardViewModel = koinViewModel(),
    announcementsViewModel: ManageAnnouncementsViewModel = koinViewModel(),
    publisherForwardViewModel: com.emfitsolutions.gopreach.ui.screens.pipeline.PublisherForwardRequestsViewModel = koinViewModel(),
    householderAssignmentViewModel: com.emfitsolutions.gopreach.ui.screens.householderassignment.HouseholderAssignmentViewModel = koinViewModel(),
    notificationCenterViewModel: NotificationCenterViewModel = koinViewModel(),
    groupChatViewModel: com.emfitsolutions.gopreach.ui.screens.groupchat.GroupChatViewModel = koinViewModel(),
    // "Publishers App – Customizable Module Navigation Redesign".
    layoutViewModel: PublisherDashboardLayoutViewModel = koinViewModel(),
) {
    val session by viewModel.state.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val showToast = rememberActionToast()
    val currentPersonId = session.person?.id.orEmpty()
    LaunchedEffect(currentPersonId) { layoutViewModel.setPersonId(currentPersonId) }
    val moduleLayout by layoutViewModel.layout.collectAsStateWithLifecycle()
    // "Multiple Role Login Detection & Role Selection" spec §7 — this screen
    // only ever renders when the session's own active role already resolved
    // to Publisher (see GoPreachNavGraph's routing), so its own assignment
    // *is* [session.activeRoleAssignment] — no separate re-scan needed (the
    // old scan here also predates the ACTIVE-status bugfix that resolving
    // through activeRoleAssignment already carries).
    val ownPublisherAssignment = session.activeRoleAssignment
    val category = (ownPublisherAssignment?.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category
    val isPioneer = isPioneerCategory(category)

    val congregations by announcementsViewModel.congregations.collectAsStateWithLifecycle()
    val congregationName = congregations.firstOrNull { it.id == ownPublisherAssignment?.congregationId }?.name

    // "Announcement Module" — the notification balloon's unseen count, scoped
    // to this Publisher's own congregation.
    val unseenAnnouncementsFlow = remember(ownPublisherAssignment?.congregationId, currentPersonId) {
        announcementsViewModel.unseenCountFor(ownPublisherAssignment?.congregationId, currentPersonId)
    }
    val unseenAnnouncements by unseenAnnouncementsFlow.collectAsStateWithLifecycle(initialValue = 0)

    // Unified notification balloon (spec: transfer requests, announcements,
    // calendar schedule — no Monthly Report category for a Publisher) —
    // congregation-scoped to this Publisher's own congregation, never "all".
    val notificationItemsFlow = remember(ownPublisherAssignment?.congregationId, currentPersonId) {
        notificationCenterViewModel.itemsForPublisher(currentPersonId, ownPublisherAssignment?.congregationId)
    }
    val visibleNotificationItemsFlow = remember(notificationItemsFlow, currentPersonId) {
        notificationCenterViewModel.visibleItemsFor(notificationItemsFlow, currentPersonId)
    }
    val notificationItems by visibleNotificationItemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val notificationUnseenFlow = remember(visibleNotificationItemsFlow, currentPersonId) {
        notificationCenterViewModel.unseenCountFor(visibleNotificationItemsFlow, currentPersonId)
    }
    val notificationUnseenCount by notificationUnseenFlow.collectAsStateWithLifecycle(initialValue = 0)
    // "Fix the Notification Sound system" — the sound-triggering side of the
    // balloon above (and of Group Chat/Transfer Request updates below) now
    // runs Application-wide via NotificationSoundCoordinator (started once
    // from GoPreachApp.onCreate()), not nested in this Composable — see its
    // own doc comment for why: a Composable tied to this one screen's
    // lifecycle could never reliably ring while the Publisher was anywhere
    // else in the app, or while it was merely backgrounded.

    // "Group Chat Setting" Chat Box icon (spec §6) — same membership-by-
    // personId flow AdminHomeScreen wires in, so it's the identical set of
    // group chats whether this account is currently in its Publisher or a
    // higher-rank context.
    val chatBoxEntriesFlow = remember(currentPersonId) { groupChatViewModel.chatBoxEntriesFor(currentPersonId) }
    val chatBoxEntries by chatBoxEntriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // "FORWARD TO OTHER PUBLISHER" — this Publisher's own incoming queue
    // count, for the "Forwarded to Me" tile's badge (same pattern as the
    // Announcement badge above).
    val incomingForwardsFlow = remember(currentPersonId) { publisherForwardViewModel.incomingRequestsFor(currentPersonId) }
    val incomingForwards by incomingForwardsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // "House Holder Assignment" — this Publisher's own incoming queue count,
    // for the "Incoming Assignments" tile's badge (same pattern as
    // "Forwarded to Me"'s above).
    val incomingAssignmentsFlow = remember(currentPersonId) { householderAssignmentViewModel.incomingAssignmentsFor(currentPersonId) }
    val incomingAssignments by incomingAssignmentsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // "Back Button and Page Navigation" spec §7 — this is the Main Form for the
    // Publisher context; there's nothing left in the nav stack to pop to here,
    // so Back needs its own "Exit GoPreach?" confirmation rather than silently
    // closing the app.
    val activity = LocalContext.current as? ComponentActivity
    var showExitConfirm by remember { mutableStateOf(false) }
    BackHandler { showExitConfirm = true }

    // Monthly Report reminder notifications (see ReminderWorker) need this
    // granted on Android 13+ — asked once here, the Main Form, rather than
    // buried behind a settings toggle nobody would find.
    val notificationPermissionLauncher = rememberPermissionRequester() {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(AppPermission.NOTIFICATIONS)
        }
    }
    if (showExitConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(Res.string.home_exit_title)) },
            text = { Text(stringResource(Res.string.home_exit_message)) },
            confirmButton = { TextButton(onClick = { activity?.finish() }) { Text(stringResource(Res.string.home_exit_confirm)) } },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(Res.string.home_exit_cancel)) } },
        )
    }

    // "Publishers App – Customizable Module Navigation Redesign" — every
    // module this Publisher can reach, then split across the two panels
    // per their own saved [moduleLayout] (spec §5: per-account, restored on
    // any device). [allTiles] only needs to change when the badge counts or
    // Pioneer-gating actually do; [moduleLayout] changing (a move, a reset,
    // or the initial load from another device) re-splits the same catalog
    // without re-fetching anything.
    val allTiles = publisherModuleTiles(isPioneer, unseenAnnouncements, incomingForwards.size, incomingAssignments.size)
    val tilesById = remember(allTiles) { allTiles.associateBy { it.id } }
    val mainFormTiles = remember(moduleLayout, tilesById) { moduleLayout.mainFormModules().mapNotNull { tilesById[it] } }
    val sidePanelTiles = remember(moduleLayout, tilesById) { moduleLayout.sidePanelModules().mapNotNull { tilesById[it] } }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    // "If the publisher opens a form [from the Side Panel] and closes it, do
    // not close the entire side panel — continue to where it was left" —
    // tapping a Side Panel tile always closes the drawer to reveal the
    // screen it opens (normal drawer behavior), but that used to be the end
    // of it: backing out of that screen left the Publisher on a fully closed
    // drawer, with no way back to it except reopening and re-navigating from
    // scratch. [rememberSaveable] survives this composable being disposed
    // while that screen is on top and restored (via the back stack entry's
    // own SavedStateRegistry) when the Publisher returns to it, so the
    // one-shot [LaunchedEffect] below can reopen the drawer automatically —
    // its scroll position ([rememberScrollState] inside
    // [SidePanelDrawerContent] is itself already `rememberSaveable`) comes
    // back exactly where it was, same as the drawer's own open state.
    var reopenSidePanelOnReturn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reopenSidePanelOnReturn) {
            reopenSidePanelOnReturn = false
            drawerState.open()
        }
    }
    // The module a long-press just targeted, and (once chosen) which panel
    // the Publisher is being asked to confirm moving it to — null/null means
    // no dialog is showing (spec §2's exact two-step flow: action menu,
    // then confirmation).
    var pendingModuleId by remember { mutableStateOf<DashboardModuleId?>(null) }
    var pendingTarget by remember { mutableStateOf<DashboardModuleLocation?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val movedSuccessMessage = stringResource(Res.string.dashboard_layout_moved_success)
    val resetSuccessMessage = stringResource(Res.string.dashboard_layout_reset_success)

    val onLongPressModule: (DashboardModuleId) -> Unit = { id ->
        pendingModuleId = id
        pendingTarget = null
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidePanelDrawerContent(
                tiles = sidePanelTiles,
                onNavigate = { route -> reopenSidePanelOnReturn = true; drawerScope.launch { drawerState.close() }; onNavigate(route) },
                onLongPressModule = onLongPressModule,
                onResetLayout = { showResetConfirm = true },
            )
        },
    ) {
    // "Remove the Home, Reports, Calendar, Bible Study from main form" — the
    // bottom navigation bar those four (plus Profile, moved into the profile
    // menu above) used to live in is gone; every one of those destinations
    // remains reachable from the Side Panel exactly as before, this only
    // removes the second, redundant path to them. No more weight(1f) split
    // with a bottom bar below it — the scrolling content is the whole screen.
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            com.emfitsolutions.gopreach.ui.components.AlarmRingingBanner()
            com.emfitsolutions.gopreach.ui.components.OfflineSessionBanner()
            PublisherWelcomeHeader(
                greetingName = session.person?.firstName?.takeIf { it.isNotBlank() } ?: "there",
                fullName = session.person?.fullName ?: "—",
                profileImageUrl = session.person?.profileImageUrl,
                congregationName = congregationName,
                categoryLabel = category?.displayLabel(),
                isOnline = isOnline,
                pendingSyncCount = pendingSyncCount,
                notificationItems = notificationItems,
                notificationUnseenCount = notificationUnseenCount,
                onOpenNotifications = { notificationCenterViewModel.markAllSeen(currentPersonId) },
                onNotificationClick = onNavigate,
                onDismissNotification = { notificationCenterViewModel.dismiss(it, currentPersonId) },
                onClearAllNotifications = { notificationCenterViewModel.dismissAll(notificationItems, currentPersonId) },
                chatBoxEntries = chatBoxEntries,
                onOpenGroupChat = { chatId -> onNavigate(Destinations.groupChatDetail(chatId)) },
                onViewAllGroupChats = { onNavigate(Destinations.GROUP_CHAT_SETTING) },
                onOpenSettings = { onNavigate(Destinations.SETTINGS) },
                onOpenAccountSettings = { onNavigate(Destinations.ACCOUNT_SETTINGS) },
                onOpenSidePanel = { drawerScope.launch { drawerState.open() } },
                onImagePicked = { uri ->
                    viewModel.updateProfileImage(uri, onImageUploadFailed = {
                        showToast("Profile image failed to upload. Try again.")
                    })
                },
                onSignOut = viewModel::signOut,
                onSwitchRole = if (session.roleOptions.size > 1) viewModel::switchRole else null,
            )

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // "Make the sync to server smaller, put it on the right
                // upper side, make it simple. Hide 'Keep Your Data Safe' and
                // 'Sync your data regularly...'" — that whole card (title,
                // message, status indicator, full-width button) is gone; the
                // actual sync control is now the compact icon button in the
                // header above (see PublisherWelcomeHeader), and the header's
                // own Online/Offline status caption already covers what
                // SyncStatusIndicator showed here.
                if (currentPersonId.isNotBlank()) {
                    // "Merge the Dashboard and My Planner" — one unified card:
                    // header (today's date, reporting period, Planner Sections
                    // settings), the Today/Week/Month/Year selector (still the
                    // shared DateRangeStore every report screen uses), then the
                    // matching planner view. The old separate Dashboard card
                    // (date-range picker + Show/Hide toggle) is gone.
                    val plannerDateRange by dashboardViewModel.dateRange.collectAsStateWithLifecycle()
                    val plannerDayViewModel: PlannerDayViewModel = koinViewModel()
                    val plannerWeekViewModel: PlannerWeekViewModel = koinViewModel()
                    val plannerMonthViewModel: PlannerMonthViewModel = koinViewModel()
                    val plannerYearViewModel: PlannerYearViewModel = koinViewModel()

                    // "Dashboard Date Range = My Planner Date Range" (Final
                    // Integration Rule) — every time the Dashboard's range
                    // actually changes to one of the four quick presets, snap
                    // the matching planner view to that exact period, even if
                    // the Publisher had previously paged forward/back inside
                    // it, and remember which view that was. Custom isn't one
                    // of the spec's four named planner views, so it's left
                    // alone — whichever view was already showing just stays.
                    var activePlannerView by remember { mutableStateOf(QuickDateRange.TODAY) }
                    // Drill-down trail (Year → Month → Day, Week → Day):
                    // tapping a month/day inside a planner view opens that
                    // period in the finer view and remembers where it came
                    // from, so "Back to …" (or system Back) returns there.
                    // Any Dashboard range change resets it — the Dashboard
                    // stays the one source of truth for the planner period.
                    val plannerTrail = remember { mutableStateListOf<QuickDateRange>() }
                    fun drillInto(target: QuickDateRange) {
                        plannerTrail.add(activePlannerView)
                        activePlannerView = target
                    }
                    BackHandler(enabled = plannerTrail.isNotEmpty()) {
                        activePlannerView = plannerTrail.removeAt(plannerTrail.lastIndex)
                    }
                    val openPlannerPerson: (PipelineStage, String) -> Unit = { stage, personId -> onNavigate(plannerPersonRoute(stage, personId)) }
                    val openPlannerPersonList: (PipelineStage) -> Unit = { onNavigate(plannerPersonListRoute(it)) }
                    LaunchedEffect(plannerDateRange) {
                        plannerTrail.clear()
                        when (plannerDateRange.option) {
                            QuickDateRange.TODAY -> {
                                plannerDayViewModel.goToDate(plannerDateRange.startMillis)
                                activePlannerView = QuickDateRange.TODAY
                            }
                            QuickDateRange.THIS_WEEK -> {
                                plannerWeekViewModel.goToWeek(plannerDateRange.startMillis)
                                activePlannerView = QuickDateRange.THIS_WEEK
                            }
                            QuickDateRange.THIS_MONTH -> {
                                plannerMonthViewModel.goToMonth(plannerDateRange.startMillis)
                                activePlannerView = QuickDateRange.THIS_MONTH
                            }
                            QuickDateRange.THIS_YEAR -> {
                                plannerYearViewModel.goToYear(plannerDateRange.startMillis)
                                activePlannerView = QuickDateRange.THIS_YEAR
                            }
                            QuickDateRange.CUSTOM -> Unit
                        }
                    }

                    // Bug fix: tapping a calendar day (Month) or a month (Year)
                    // used to only re-point the *hidden* Day/Month view model
                    // without ever switching to it, so the tap did nothing
                    // visible. Every drill-down now switches views too.
                    val openPlannerDay: (Long) -> Unit = { dayStart ->
                        plannerDayViewModel.goToDate(dayStart)
                        drillInto(QuickDateRange.TODAY)
                    }
                    var showPlannerSections by remember { mutableStateOf(false) }
                    // "Compare" is its own selection, independent of the
                    // Dashboard/Planner shared [plannerDateRange] — see
                    // [UnifiedPlannerHeader]'s isCompareActive/onSelectCompare
                    // doc comment for why it can't just be a QuickDateRange.
                    var showCompareView by remember { mutableStateOf(false) }
                    val plannerVisibility = PlannerVisibility(
                        hours = moduleLayout.isPlannerSectionVisible(PlannerSection.HOURS),
                        creditHours = moduleLayout.isPlannerSectionVisible(PlannerSection.CREDIT_HOURS),
                        returnVisits = moduleLayout.isPlannerSectionVisible(PlannerSection.RETURN_VISITS),
                        bibleStudies = moduleLayout.isPlannerSectionVisible(PlannerSection.BIBLE_STUDIES),
                    )
                    // Months whose report is already submitted are closed in My Planner.
                    val plannerReportViewModel: com.emfitsolutions.gopreach.ui.screens.planner.PlannerReportViewModel = koinViewModel()
                    val submittedMonths by remember(currentPersonId) { plannerReportViewModel.submittedMonths(currentPersonId) }
                        .collectAsStateWithLifecycle(initialValue = emptyMap())
                    val plannerLock = remember(submittedMonths) { com.emfitsolutions.gopreach.ui.screens.planner.PlannerLock(submittedMonths) }
                    CompositionLocalProvider(
                        com.emfitsolutions.gopreach.ui.screens.planner.LocalPlannerLock provides plannerLock,
                        LocalPlannerVisibility provides plannerVisibility,
                        LocalOpenCreditCategoryManager provides
                            if (canManageCreditHourCategories) ({ onNavigate(Destinations.CREDIT_HOUR_CATEGORIES) }) else null,
                    ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                    ) {
                        UnifiedPlannerHeader(
                            range = plannerDateRange,
                            backLabel = plannerTrail.lastOrNull()?.let { previous ->
                                when (previous) {
                                    QuickDateRange.THIS_WEEK -> "Weekly"
                                    QuickDateRange.THIS_MONTH -> "Monthly"
                                    QuickDateRange.THIS_YEAR -> "Yearly"
                                    else -> "Today"
                                }
                            },
                            onBack = { activePlannerView = plannerTrail.removeAt(plannerTrail.lastIndex) },
                            onOpenSections = { showPlannerSections = true },
                            onSelectPeriod = { option ->
                                showCompareView = false
                                dashboardViewModel.setDateRange(
                                    when (option) {
                                        QuickDateRange.THIS_WEEK -> DateRange.thisWeek()
                                        QuickDateRange.THIS_MONTH -> DateRange.thisMonth()
                                        QuickDateRange.THIS_YEAR -> DateRange.thisYear()
                                        else -> DateRange.today()
                                    },
                                )
                            },
                            isCompareActive = showCompareView,
                            onSelectCompare = { showCompareView = true },
                        )
                        run {
                            if (showCompareView) {
                                PlannerComparativeContent(currentPersonId = currentPersonId)
                            } else when (activePlannerView) {
                                QuickDateRange.THIS_WEEK -> PlannerWeekContent(
                                    currentPersonId = currentPersonId,
                                    viewModel = plannerWeekViewModel,
                                    onOpenDay = openPlannerDay,
                                    onOpenPerson = openPlannerPerson,
                                    onOpenPersonList = openPlannerPersonList,
                                )
                                QuickDateRange.THIS_MONTH -> PlannerMonthContent(
                                    currentPersonId = currentPersonId,
                                    viewModel = plannerMonthViewModel,
                                    onOpenDay = openPlannerDay,
                                    onOpenPerson = openPlannerPerson,
                                    onOpenPersonList = openPlannerPersonList,
                                    isPioneer = isPioneer,
                                    onSendReport = { periodMonth -> onNavigate(Destinations.monthlyReportForMonth(periodMonth)) },
                                )
                                QuickDateRange.THIS_YEAR -> PlannerYearContent(
                                    currentPersonId = currentPersonId,
                                    viewModel = plannerYearViewModel,
                                    onOpenMonth = { monthStart ->
                                        plannerMonthViewModel.goToMonth(monthStart)
                                        drillInto(QuickDateRange.THIS_MONTH)
                                    },
                                    onOpenDay = openPlannerDay,
                                    onOpenPerson = openPlannerPerson,
                                    onOpenPersonList = openPlannerPersonList,
                                )
                                else -> PlannerDayContent(currentPersonId, plannerDayViewModel, onNavigate)
                            }
                        }
                        Spacer(modifier = Modifier.heightIn(min = 4.dp))
                    }
                    }
                    if (showPlannerSections) {
                        PlannerSectionsDialog(
                            visibility = plannerVisibility,
                            onToggle = { section, visible -> layoutViewModel.setPlannerSectionVisible(section, visible) },
                            onDismiss = { showPlannerSections = false },
                        )
                    }
                    // "Do not show duplicate timer. Remove the other one." —
                    // the standalone Ministry Timer card that used to render
                    // here unconditionally is gone; the one embedded inside
                    // My Planner → Today (see PlannerDayContent) is the only
                    // one now, so it only shows while Today is the active
                    // view — same underlying Firestore-backed session either
                    // way, this just stops it appearing twice at once.
                }

                // "Recently Visited" — the latest Return Visit / Bible Study visits this Publisher is allowed to see
                // (same visibility rules as the modules); opening one goes through the module, which re-checks access.
                ownPublisherAssignment?.congregationId?.let { publisherCongregationId ->
                    DashboardActivitySections(
                        viewer = RecentlyVisitedViewer.Publisher(currentPersonId, publisherCongregationId),
                        onOpen = { item ->
                            onNavigate(
                                when (item.person.pipelineStage) {
                                    com.emfitsolutions.gopreach.data.model.PipelineStage.BIBLE_STUDY -> Destinations.bibleStudyPerson(item.person.id)
                                    com.emfitsolutions.gopreach.data.model.PipelineStage.RETURN_VISIT -> Destinations.returnVisitPerson(item.person.id)
                                    com.emfitsolutions.gopreach.data.model.PipelineStage.SEARCHING -> Destinations.searchingPerson(item.person.id)
                                },
                            )
                        },
                    )
                }

                FeatureTileGrid(
                    tiles = mainFormTiles,
                    onNavigate = onNavigate,
                    onLongPressModule = onLongPressModule,
                )

                if (onSwitchToAdmin != null) {
                    Card(modifier = Modifier.fillMaxWidth(), onClick = onSwitchToAdmin) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(Res.string.home_switch_to_admin), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
    }
    } // ModalNavigationDrawer content

    // "Long Press Icon → Select Move → Confirm → Module Moves → Dashboard
    // Refreshes" (spec §2/§11) — the two-step dialog itself; [moduleLayout]
    // already reflects the move the instant it's saved, since it's the same
    // StateFlow this whole screen renders from, so there is nothing else to
    // "refresh" here.
    val pendingId = pendingModuleId
    if (pendingId != null) {
        val tile = tilesById[pendingId]
        val currentLocation = if (pendingId in moduleLayout.sidePanelModules()) DashboardModuleLocation.SIDE_PANEL else DashboardModuleLocation.MAIN_FORM
        val target = pendingTarget
        if (target == null) {
            // Step 1 — "Move Module" action menu.
            val moveTarget = if (currentLocation == DashboardModuleLocation.MAIN_FORM) DashboardModuleLocation.SIDE_PANEL else DashboardModuleLocation.MAIN_FORM
            val moveLabel = stringResource(
                if (moveTarget == DashboardModuleLocation.SIDE_PANEL) Res.string.dashboard_layout_move_to_side_panel else Res.string.dashboard_layout_move_to_main_form,
            )
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true),
                onDismissRequest = { pendingModuleId = null },
                title = { Text(tile?.title ?: stringResource(Res.string.dashboard_layout_move_module)) },
                text = {
                    TextButton(onClick = { pendingTarget = moveTarget }, modifier = Modifier.fillMaxWidth()) {
                        Text(moveLabel)
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { pendingModuleId = null }) { Text(stringResource(Res.string.action_cancel)) } },
            )
        } else {
            // Step 2 — confirmation (spec §2: "Do you want to move this
            // module to the Side Panel/Main Form?").
            AlertDialog(
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
                onDismissRequest = { pendingModuleId = null; pendingTarget = null },
                title = { Text(stringResource(Res.string.dashboard_layout_move_module)) },
                text = {
                    Text(
                        stringResource(
                            if (target == DashboardModuleLocation.SIDE_PANEL) Res.string.dashboard_layout_confirm_to_side_panel else Res.string.dashboard_layout_confirm_to_main_form,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        layoutViewModel.moveModule(pendingId, target)
                        pendingModuleId = null
                        pendingTarget = null
                        showToast(movedSuccessMessage)
                    }) { Text(stringResource(Res.string.home_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingModuleId = null; pendingTarget = null }) { Text(stringResource(Res.string.home_no)) }
                },
            )
        }
    }

    // "Reset Dashboard Layout" (spec §9).
    if (showResetConfirm) {
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(Res.string.dashboard_layout_reset_title)) },
            text = { Text(stringResource(Res.string.dashboard_layout_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    layoutViewModel.resetLayout()
                    showResetConfirm = false
                    drawerScope.launch { drawerState.close() }
                    showToast(resetSuccessMessage)
                }) { Text(stringResource(Res.string.dashboard_layout_reset_confirm_button)) }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** Welcome header — a flat, solid panel in the theme's own primary color
 * (never hard-coded green/blue, and no gradient — "use solid color in
 * themes, not gradient color"), rounded at the bottom to read as one soft
 * panel, matching the reference's shape. */
@Composable
private fun PublisherWelcomeHeader(
    greetingName: String,
    fullName: String,
    profileImageUrl: String?,
    congregationName: String?,
    categoryLabel: String?,
    isOnline: Boolean,
    pendingSyncCount: Int,
    notificationItems: List<com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItem>,
    notificationUnseenCount: Int,
    onOpenNotifications: () -> Unit,
    onNotificationClick: (String) -> Unit,
    onDismissNotification: (com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItem) -> Unit,
    onClearAllNotifications: () -> Unit,
    chatBoxEntries: List<com.emfitsolutions.gopreach.ui.components.ChatBoxEntry>,
    onOpenGroupChat: (String) -> Unit,
    onViewAllGroupChats: () -> Unit,
    onOpenSettings: () -> Unit,
    // "Publishers App – Customizable Module Navigation Redesign" — opens the
    // Side Panel drawer (spec §4), same hamburger-icon convention the Admin
    // Main Form already uses for its own Side Panel.
    onOpenSidePanel: () -> Unit,
    onImagePicked: (android.net.Uri) -> Unit,
    onSignOut: () -> Unit,
    onSwitchRole: (() -> Unit)? = null,
    // "Add Module: Preaching Availability" — a Publisher's only path to
    // AccountSettingsScreen (name/username/password, and now Preaching
    // Availability); see ProfileMenuButton's own doc comment on why this
    // was missing before.
    onOpenAccountSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            )
            .padding(bottom = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenSidePanel) {
                    Icon(Icons.Rounded.Menu, contentDescription = stringResource(Res.string.dashboard_layout_side_panel_title), tint = Color.White)
                }
                Box(modifier = Modifier.weight(1f))
                // Unified notification balloon — transfer requests,
                // announcements, calendar schedule; opening it marks
                // everything currently in scope seen (see
                // NotificationCenterViewModel.markAllSeen).
                NotificationBell(
                    items = notificationItems,
                    unseenCount = notificationUnseenCount,
                    onOpen = onOpenNotifications,
                    onItemClick = { onNotificationClick(it.route) },
                    onDismiss = onDismissNotification,
                    onClearAll = onClearAllNotifications,
                )
                com.emfitsolutions.gopreach.ui.components.ChatBoxIcon(
                    entries = chatBoxEntries,
                    onOpenGroupChat = onOpenGroupChat,
                    onViewAll = onViewAllGroupChats,
                )
                // "Add a refresh button only not sync" — a plain re-fetch
                // from the server, deliberately separate from "Sync to
                // Server" right next to it: this one never touches the
                // local pending-upload queue.
                com.emfitsolutions.gopreach.ui.components.RefreshButton()
                // "Make the sync to server smaller, put it on the right
                // upper side, make it simple" — the compact icon variant,
                // right in the header's own icon row.
                SyncToServerButton(compact = true)
                // "Move the Profile next to Account Settings" — the standalone
                // top-bar Settings gear (used to sit here) moved into the
                // profile dropdown, right next to Account Settings, instead
                // of its own icon. Folds Sign Out into the same menu (name +
                // role, View/Update Profile Image, Log Out) too.
                ProfileMenuButton(
                    fullName = fullName,
                    roleLabel = categoryLabel ?: stringResource(Res.string.role_label_publisher),
                    profileImageUrl = profileImageUrl,
                    onImagePicked = onImagePicked,
                    onSignOut = onSignOut,
                    onSwitchRole = onSwitchRole,
                    onOpenAccountSettings = onOpenAccountSettings,
                    onOpenSettings = onOpenSettings,
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    stringResource(Res.string.home_welcome, greetingName),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val subtitle = listOfNotNull(congregationName, categoryLabel).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                }
                // Preserves the pre-existing behavior exactly (only "Online"
                // ever shows for the online case, no sync suffix) — this is
                // just the same three pieces now sourced from strings.xml.
                val statusCaption = if (isOnline) {
                    stringResource(Res.string.home_status_online)
                } else {
                    stringResource(Res.string.home_status_offline) + if (pendingSyncCount > 0) {
                        stringResource(Res.string.home_status_pending_sync_suffix, pendingSyncCount)
                    } else {
                        stringResource(Res.string.home_status_all_synced_suffix)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "Show the icon if online and the other online users
                    // just like before" — restores the 🟢/🔴 connectivity
                    // glyph and the tappable Online Users count next to it,
                    // same as the old SyncStatusIndicator row.
                    Text(if (isOnline) "🟢 " else "🔴 ", style = MaterialTheme.typography.bodySmall)
                    Text(
                        statusCaption,
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    com.emfitsolutions.gopreach.ui.components.OnlineUsersIndicator(
                        modifier = Modifier.padding(start = 12.dp),
                        textColor = Color.White.copy(alpha = 0.75f),
                        onlineTint = Color(0xFF69F0AE),
                        offlineTint = Color(0xFFFF8A80),
                    )
                }
            }
        }
    }
}

/** One feature tile — an icon circle (theme tonal color), title/subtitle,
 * and an optional notification-style badge count. Long-pressing opens the
 * "Move Module" action menu (spec §2/§11); a short haptic tick and a
 * momentary scale-up on press are the "slightly enlarge the selected icon...
 * subtle selection effect" (spec §12) — the move itself never happens until
 * the Publisher explicitly confirms a destination in the dialog that follows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    badgeCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = stringResource(Res.string.dashboard_layout_move_module),
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 4.dp),
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(iconContainerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint)
                }
                // weight(1f) so this column is actually constrained to the
                // space left after the icon, instead of measuring at its own
                // "wanted" text width — without it, a longer title/subtitle
                // (e.g. "Forwarded to Me", "Share My Location") could overflow
                // past the icon into the Card's rounded edge and get visually
                // clipped there instead of wrapping/ellipsizing in place.
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badgeCount > 0) {
                Badge(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text(badgeCount.toString()) }
            }
        }
    }
}

/** Two-column grid of every Main Form destination this Publisher can reach —
 * Preaching Time Record ("My Total Hours") is Pioneer-only (spec §17/§20).
 * Icon circles alternate between [MaterialTheme.colorScheme.primaryContainer]
 * and `.secondaryContainer` — still one theme, just enough tonal variety to
 * tell tiles apart at a glance, the way the reference's per-tile colors did
 * without reintroducing arbitrary hues. */
/** One catalog entry for a Publisher's customizable Main Form/Side Panel
 * module (spec: "Publishers App – Customizable Module Navigation Redesign").
 * [id] is the permanent, stored identity ([DashboardModuleId]); everything
 * else here is display-only and safe to change freely without touching any
 * Publisher's saved layout. */
private data class PublisherModuleTile(
    val id: DashboardModuleId,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val badge: Int = 0,
)

/** Every module a Publisher can reach today, still gated by the exact same
 * conditions [FeatureTileGrid] always used ([isPioneer] for "My Total
 * Hours") — customization only changes *where* an already-authorized module
 * is drawn, never which modules exist (spec §10). */
@Composable
private fun publisherModuleTiles(
    isPioneer: Boolean,
    unseenAnnouncements: Int,
    pendingPublisherForwards: Int,
    pendingHouseholderAssignments: Int,
): List<PublisherModuleTile> = buildList {
    add(PublisherModuleTile(DashboardModuleId.MONTHLY_REPORT, stringResource(Res.string.home_tile_monthly_report_title), stringResource(Res.string.home_tile_monthly_report_subtitle), Icons.Rounded.Assignment, Destinations.MONTHLY_REPORT))
    // "Allow the publisher to see all his submitted Report record" —
    // its own tile since MONTHLY_REPORT's form only ever shows the
    // current/previous month, not the full history.
    add(PublisherModuleTile(DashboardModuleId.MY_SUBMITTED_REPORTS, stringResource(Res.string.home_tile_my_reports_title), stringResource(Res.string.home_tile_my_reports_subtitle), Icons.AutoMirrored.Rounded.ListAlt, Destinations.MY_SUBMITTED_REPORTS))
    add(PublisherModuleTile(DashboardModuleId.SEARCHING, stringResource(Res.string.home_tile_searching_title), stringResource(Res.string.home_tile_searching_subtitle), Icons.Rounded.PersonSearch, Destinations.SEARCHING))
    add(PublisherModuleTile(DashboardModuleId.RETURN_VISIT, stringResource(Res.string.home_tile_return_visit_title), stringResource(Res.string.home_tile_return_visit_subtitle), Icons.Rounded.PeopleAlt, Destinations.RETURN_VISIT))
    add(PublisherModuleTile(DashboardModuleId.BIBLE_STUDY, stringResource(Res.string.home_tile_bible_study_title), stringResource(Res.string.home_tile_bible_study_subtitle), Icons.AutoMirrored.Rounded.MenuBook, Destinations.BIBLE_STUDY))
    // "FORWARD TO OTHER PUBLISHER" — this Publisher's own incoming queue.
    add(PublisherModuleTile(DashboardModuleId.FORWARDED_TO_ME, stringResource(Res.string.home_tile_forwarded_to_me_title), stringResource(Res.string.home_tile_forwarded_to_me_subtitle), Icons.AutoMirrored.Rounded.Forward, Destinations.PUBLISHER_FORWARD_REQUESTS, pendingPublisherForwards))
    // "House Holder Assignment" — this Publisher's own incoming queue, same
    // "actionable inbox with a live badge" shape as "Forwarded to Me" above.
    add(PublisherModuleTile(DashboardModuleId.INCOMING_HOUSEHOLDER_ASSIGNMENTS, stringResource(Res.string.home_tile_incoming_assignments_title), stringResource(Res.string.home_tile_incoming_assignments_subtitle), Icons.Rounded.AssignmentInd, Destinations.INCOMING_HOUSEHOLDER_ASSIGNMENTS, pendingHouseholderAssignments))
    // "House Holder Visit History" — a read-only, consolidated view of
    // this Publisher's own congregation's Searching/Return Visit/Bible
    // Study records and their visit history (see
    // HouseholderVisitHistoryScreen's own doc comment); Super-Admin
    // reaches the same screen unscoped via the drawer instead.
    add(PublisherModuleTile(DashboardModuleId.HOUSEHOLDER_VISIT_HISTORY, stringResource(Res.string.home_tile_householder_visit_history_title), stringResource(Res.string.home_tile_householder_visit_history_subtitle), Icons.Rounded.History, Destinations.HOUSEHOLDER_VISIT_HISTORY))
    if (isPioneer) {
        add(PublisherModuleTile(DashboardModuleId.MY_TOTAL_HOURS, stringResource(Res.string.home_tile_my_total_hours_title), stringResource(Res.string.home_tile_my_total_hours_subtitle), Icons.Rounded.Timer, Destinations.PREACHING_TIME_RECORD))
    }
    // "My Bible Text Record" module — every Publisher, not just Pioneers.
    // A distinct icon from "Bible Study" (also MenuBook) — this is a
    // personal saved-reference collection, not the ministry module.
    add(PublisherModuleTile(DashboardModuleId.MY_BIBLE_TEXT_RECORD, stringResource(Res.string.home_tile_my_bible_text_record_title), stringResource(Res.string.home_tile_my_bible_text_record_subtitle), Icons.Rounded.Bookmarks, Destinations.MY_BIBLE_TEXT_RECORD))
    add(PublisherModuleTile(DashboardModuleId.MY_CALENDAR, stringResource(Res.string.home_tile_my_calendar_title), stringResource(Res.string.home_tile_my_calendar_subtitle), Icons.Rounded.CalendarMonth, Destinations.CALENDAR))
    add(PublisherModuleTile(DashboardModuleId.SHARE_MY_LOCATION, stringResource(Res.string.home_tile_share_my_location_title), stringResource(Res.string.home_tile_share_my_location_subtitle), Icons.Rounded.LocationOn, Destinations.SHARE_LOCATION))
    add(PublisherModuleTile(DashboardModuleId.FIND_LOCATION, stringResource(Res.string.home_tile_find_location_title), stringResource(Res.string.home_tile_find_location_subtitle), Icons.Rounded.Navigation, Destinations.FIND_LOCATION))
    // "Add the Territory Module in Publisher. The publisher can see all
    // the location but cannot edit or delete, view only" — the screen
    // itself has no edit/delete actions for anyone anymore (see
    // TerritoryMapScreen), so reaching it here is already read-only by
    // construction; scoped to the Publisher's own congregation (see
    // GoPreachNavGraph's MANAGE_TERRITORIES composable).
    add(PublisherModuleTile(DashboardModuleId.TERRITORY_MAP, stringResource(Res.string.home_tile_territory_map_title), stringResource(Res.string.home_tile_territory_map_subtitle), Icons.Rounded.Map, Destinations.MANAGE_TERRITORIES_BASE))
    // "The record will be seen in the publishers module... called
    // 'Meeting Assignments.' The publisher will see only meeting
    // assignments under their congregation" — read-only, see
    // GoPreachNavGraph's PUBLISHER_MEETING_ASSIGNMENTS composable.
    add(PublisherModuleTile(DashboardModuleId.MEETING_CART_ASSIGNMENT, stringResource(Res.string.home_tile_meeting_cart_assignment_title), stringResource(Res.string.home_tile_meeting_cart_assignment_subtitle), Icons.Rounded.Event, Destinations.PUBLISHER_MEETING_ASSIGNMENTS))
    // "Add a Button under Meeting [and Cart] Assignment[:] 'My
    // Assignments'... the publisher can see all the assignments under
    // his name" — a cross-cut of every Midweek/Public Talk/Cart
    // Assignment record naming this publisher, not just the module's
    // own currently-selected week/date (see GoPreachNavGraph's
    // MY_ASSIGNMENTS composable / MeetingAssignmentsViewModel
    // .myAssignmentsFor).
    add(PublisherModuleTile(DashboardModuleId.MY_ASSIGNMENTS, stringResource(Res.string.home_tile_my_assignments_title), stringResource(Res.string.home_tile_my_assignments_subtitle), Icons.Rounded.Assignment, Destinations.MY_ASSIGNMENTS))
    add(PublisherModuleTile(DashboardModuleId.ANNOUNCEMENT, stringResource(Res.string.home_tile_announcement_title), stringResource(Res.string.home_tile_announcement_subtitle), Icons.Rounded.Campaign, Destinations.PUBLISHER_ANNOUNCEMENTS, unseenAnnouncements))
    // "Group Chat Setting" module — also reachable from the persistent
    // Chat Box icon in the header (see PublisherWelcomeHeader), this
    // tile is just a second, more discoverable entry point to the same
    // GROUP_CHAT_SETTING list.
    add(PublisherModuleTile(DashboardModuleId.GROUP_CHAT, stringResource(Res.string.home_tile_group_chat_title), stringResource(Res.string.home_tile_group_chat_subtitle), Icons.AutoMirrored.Rounded.Chat, Destinations.GROUP_CHAT_SETTING))
    // "Make a module for publisher to see the available schedule of the
    // other publishers" — same PublisherSchedulesScreen Account Settings'
    // own "View Other Publishers' Schedules" link already opens, now also
    // reachable as its own Main Form tile; congregation-scoped by the
    // Destinations.PUBLISHER_SCHEDULES composable itself, not this tile.
    add(PublisherModuleTile(DashboardModuleId.PUBLISHER_SCHEDULES, stringResource(Res.string.home_tile_publisher_schedules_title), stringResource(Res.string.home_tile_publisher_schedules_subtitle), Icons.Rounded.Schedule, Destinations.PUBLISHER_SCHEDULES))
    // My Planner is no longer a movable module (see this function's other
    // callers) — it's a fixed card directly on the Main Form now, so it's
    // deliberately absent from this customizable catalog.
}

/** The Main Form grid — [tiles] is already the resolved, ordered set for
 * this panel (see [PublisherDashboardLayoutViewModel]/[DashboardModuleLayout
 * .mainFormModules]); this composable only lays them out and wires long-press
 * (spec §2/§11: "Long Press Icon → Select Move"). */
@Composable
private fun FeatureTileGrid(
    tiles: List<PublisherModuleTile>,
    onNavigate: (String) -> Unit,
    onLongPressModule: (DashboardModuleId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { index, tile ->
                    val useSecondary = (tiles.indexOf(tile)) % 2 == 1
                    FeatureTile(
                        title = tile.title,
                        subtitle = tile.subtitle,
                        icon = tile.icon,
                        iconContainerColor = if (useSecondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                        iconTint = if (useSecondary) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        badgeCount = tile.badge,
                        onClick = { onNavigate(tile.route) },
                        onLongClick = { onLongPressModule(tile.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Odd tile count on the last row — balance it so the lone
                // tile stays the same width as every other one instead of
                // stretching to fill the row alone.
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The Side Panel drawer's own module list — same modules, tucked away
 * instead of on the Main Form grid (spec §4); long-press works identically
 * here so a module can be moved back (spec §2's "If the module is currently
 * on the Side Panel"). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidePanelModuleRow(tile: PublisherModuleTile, onNavigate: (String) -> Unit, onLongPressModule: (DashboardModuleId) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onNavigate(tile.route) }, onLongClick = { onLongPressModule(tile.id) })
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(tile.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tile.badge > 0) {
            Badge { Text(tile.badge.toString()) }
        }
    }
}

/**
 * "Publishers App – Customizable Module Navigation Redesign" — the drawer
 * content for the Side Panel (spec §4): every module this Publisher has
 * moved (or defaults to) off the Main Form, plus a "Reset Dashboard Layout"
 * action (spec §9). Long-pressing any row here opens the same move dialog
 * as a Main Form tile — the dialog itself is hosted once, inline, near the
 * end of [PublisherHomeScreen] (see its own "Long Press Icon → Select Move"
 * comment), driven by the same `pendingModuleId`/`pendingTarget` state this
 * row's [onLongPressModule] callback sets.
 */
@Composable
private fun SidePanelDrawerContent(
    tiles: List<PublisherModuleTile>,
    onNavigate: (String) -> Unit,
    onLongPressModule: (DashboardModuleId) -> Unit,
    onResetLayout: () -> Unit,
) {
    ModalDrawerSheet {
        Text(
            stringResource(Res.string.dashboard_layout_side_panel_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(20.dp),
        )
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (tiles.isEmpty()) {
                Text(
                    stringResource(Res.string.dashboard_layout_side_panel_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                tiles.forEach { tile -> SidePanelModuleRow(tile, onNavigate, onLongPressModule) }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onResetLayout),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp))
            Text(
                stringResource(Res.string.dashboard_layout_reset_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

private fun PublisherCategory.displayLabel(): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }


// "Fix the Notification Sound system" — the two notifiers that used to live
// here (PublisherForwardNotifier, ForwardToCongregationSenderNotifier) are
// now ported into NotificationSoundCoordinator, running at Application scope
// instead of nested in this screen — see that class's own doc comment.
