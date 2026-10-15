package com.emfitsolutions.gopreach.ui.components

import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.SupervisorAccount
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateMapOf
import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.app_name
import com.emfitsolutions.gopreach.shared.resources.home_dashboard_header
import com.emfitsolutions.gopreach.shared.resources.home_nav_calendar
import com.emfitsolutions.gopreach.shared.resources.home_tile_find_location_title
import com.emfitsolutions.gopreach.shared.resources.home_tile_meeting_cart_assignment_title
import com.emfitsolutions.gopreach.shared.resources.side_account_management
import com.emfitsolutions.gopreach.shared.resources.side_account_settings
import com.emfitsolutions.gopreach.shared.resources.side_admins
import com.emfitsolutions.gopreach.shared.resources.side_announcements
import com.emfitsolutions.gopreach.shared.resources.side_appearance_app_logo
import com.emfitsolutions.gopreach.shared.resources.side_backup_restore
import com.emfitsolutions.gopreach.shared.resources.side_congregations_groups
import com.emfitsolutions.gopreach.shared.resources.side_contact_record
import com.emfitsolutions.gopreach.shared.resources.side_credit_hour_categories
import com.emfitsolutions.gopreach.shared.resources.side_elders
import com.emfitsolutions.gopreach.shared.resources.side_forward_requests
import com.emfitsolutions.gopreach.shared.resources.side_group_chat_setting
import com.emfitsolutions.gopreach.shared.resources.side_groups
import com.emfitsolutions.gopreach.shared.resources.side_householder_visit_history
import com.emfitsolutions.gopreach.shared.resources.side_interested_records_scoped
import com.emfitsolutions.gopreach.shared.resources.side_ministerial_servant
import com.emfitsolutions.gopreach.shared.resources.side_ministry_report_app
import com.emfitsolutions.gopreach.shared.resources.side_publisher
import com.emfitsolutions.gopreach.shared.resources.side_publisher_assignment
import com.emfitsolutions.gopreach.shared.resources.side_section_control_panel
import com.emfitsolutions.gopreach.shared.resources.side_section_enrollment
import com.emfitsolutions.gopreach.shared.resources.side_section_reports
import com.emfitsolutions.gopreach.shared.resources.side_share_location_settings
import com.emfitsolutions.gopreach.shared.resources.side_sign_out
import com.emfitsolutions.gopreach.shared.resources.side_territory_assignments
import com.emfitsolutions.gopreach.shared.resources.side_territory_map
import com.emfitsolutions.gopreach.shared.resources.side_theme_color_settings
import com.emfitsolutions.gopreach.shared.resources.side_user_logs
import com.emfitsolutions.gopreach.shared.resources.side_user_management
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.AssignmentInd
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import com.emfitsolutions.gopreach.ui.navigation.Destinations

/** One leaf item in the Side Panel's treeview (spec §2). */
data class SideItem(val label: String, val icon: ImageVector, val route: String)

/** One collapsible group (spec §2: "Side Panel Treeview" — Enrollment / Control
 * Panel / Other modules), built from whichever [items] the caller already
 * decided this session is authorized to see — this composable does no
 * permission checks of its own, matching the rest of the app's "gating
 * decisions live with the caller, not the widget" convention. */
private data class SideSection(val title: String, val items: List<SideItem>)

/**
 * The Side Panel content (spec §1-§2) — a role-filtered, collapsible-section
 * navigation drawer. [activeRoute] highlights the current destination (spec
 * §1: "Highlight the active menu"); every list here is pre-filtered by the
 * caller (see AdminHomeScreen) using the exact same booleans that already
 * gate the dashboard's own tile grid, so the drawer can never offer a route
 * the grid itself wouldn't — one source of truth for "what can this session
 * navigate to," not two that could drift apart.
 */
@Composable
fun GoPreachSidePanelContent(
    activeRoute: String?,
    canManageCongregationsAndAdmins: Boolean,
    canEnrollCoordinatorElder: Boolean,
    canEnrollServiceOverseer: Boolean,
    canEnrollMinisterialServant: Boolean,
    canManageAnnouncements: Boolean,
    canViewFieldServiceReport: Boolean,
    canViewDeletedRecords: Boolean = false,
    canViewForwardRequests: Boolean,
    canManageHouseholderAssignment: Boolean,
    canEnrollRegularElderOrPublisher: Boolean,
    /** "CREATING PUBLISHER" spec — Service Overseer can also create/manage
     * Publishers under their own congregation, in addition to everyone
     * [canEnrollRegularElderOrPublisher] already covers. */
    canEnrollPublisher: Boolean,
    canManagePublishersAndGroups: Boolean,
    /** "CREATING GROUPS" spec — Coordinator Elder *or* Service Overseer can
     * create a Group under their own congregation, in addition to everyone
     * [canManagePublishersAndGroups] already covers (Super-Admin/Admin/
     * Coordinator Elder). Service Overseer gets Groups specifically, not the
     * wider Publisher-management access. */
    canManageGroups: Boolean,
    /** Territory Assignment module — see AdminHomeScreen's own derivation
     * (same role set as [canManageGroups]: canManagePublishersAndGroups plus
     * Service Overseer/Secretary). */
    canManageTerritoryAssignments: Boolean,
    canManageTerritories: Boolean,
    canEditMeetingAssignments: Boolean,
    canAccessControlPanel: Boolean,
    isSuperAdmin: Boolean,
    canViewUserLogs: Boolean,
    canManageUsers: Boolean,
    /** Circuit Overseer module — Super-Admin only: Circuit Codes, Circuit Overseer Accounts, Circuit Assignment. */
    canManageCircuits: Boolean = false,
    /** The signed-in account is a Circuit Overseer: adds its Circuit Dashboard to REPORTS. */
    isCircuitOverseer: Boolean = false,
    /** Account Management spec §5 — Super-Admin, Admin, Coordinator Elder,
     * Service Overseer (+ Secretary). See AdminHomeScreen's own derivation. */
    canManageAccountCredentials: Boolean,
    /** "Contact Record" module — Super-Admin, Coordinator Elder, and Regular
     * Elder only (not Admin, not Service Overseer/Ministerial Servant). */
    canViewContactRecord: Boolean,
    /** "Session Timeout Setting" — Super-Admin, every Admin, every Elder. */
    canManageSessionTimeout: Boolean,
    /** Spec §15 — "Elders should be able to see Interested Person information
     * according to their existing Congregation/Group access scope": Admin/
     * Coordinator Elder/Service Overseer/Regular Elder, read-only, scoped to
     * their own congregation (or own Group). Distinct from [isSuperAdmin]'s
     * own [Destinations.ALL_INTERESTED_RECORDS] item below, which is full
     * read/write across every congregation. */
    canViewInterestedPeopleScope: Boolean,
    /** Always null since "Multiple Role Login Detection & Role Selection"
     * (spec §7/§11) retired the old mid-session Admin<->Publisher switch in
     * favor of choosing a role once at login — kept as a parameter (instead
     * of deleted outright) only so this drawer item's rendering doesn't need
     * touching if that ever changes; see GoPreachNavGraph's ADMIN_HOME
     * composable for the actual call site. */
    onSwitchToPublisher: (() -> Unit)?,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit,
    /** Super-Admin, Admin, Service Overseer, Secretary and Coordinator Elder may enter a field service record on a publisher's behalf. */
    canEnterManualFieldService: Boolean = false,
    canManageMeetingAttendance: Boolean = false,
    /** The roles that send the Field Service Report to the Circuit Overseer may also review what they sent. */
    canViewSentReports: Boolean = false,
    /** Reports every module this session may open, so Quick Access can resolve (and permission-check) cards copied from here. */
    onItemsAvailable: (List<SideItem>) -> Unit = {},
    /** Long-press-and-drag a module toward Quick Access; null disables dragging. */
    dragState: com.emfitsolutions.gopreach.ui.screens.home.QuickAccessDragState? = null,
    onDragStarted: () -> Unit = {},
    /** The signed-in user, shown compactly at the top of the panel (nothing sensitive: name, active role, scope, picture). */
    userName: String = "",
    roleLabel: String = "",
    scopeLabel: String = "",
    profileImageUrl: String? = null,
) {
    // "Admin Dashboard Menu Reorganization" spec — the drawer's existing
    // collapsible-section treeview (this composable already had exactly
    // that shape) regrouped into ENROLLMENT / REPORTS / CONTROL PANEL, in
    // that exact order (spec §1/§26-28), instead of the old Enrollment/
    // Control Panel/"Other" split. Every item keeps the *exact same* gating
    // boolean it already had — this only moves *where* an already-authorized
    // item is displayed, never who is authorized to see it (spec §34: "Do
    // not hard-code access based on menu location").
    val sections = buildList {
        // ENROLLMENT — "Everything related to enrolling and managing users,
        // publishers, congregation personnel, congregation records, and
        // organizational enrollment" (spec §2/final requirement). Territory
        // Map/Meeting & Cart Assignment/Announcements moved out to CONTROL
        // PANEL below — spec §4/§9/§36 explicitly list all three there, not
        // here.
        val enrollmentItems = buildList {
            if (canManageCongregationsAndAdmins) add(SideItem(stringResource(Res.string.side_congregations_groups), Icons.Rounded.AccountBalance, Destinations.MANAGE_CONGREGATIONS))
            if (canManageCongregationsAndAdmins) add(SideItem(stringResource(Res.string.side_admins), Icons.Rounded.AdminPanelSettings, Destinations.MANAGE_ADMINS))
            // "Consolidate Elder, Coordinator Elder, Service Overseer and
            // Secretary Enrollment" — one "Elders" item (spec §1/§43)
            // replaces the separate Coordinator Elder/Service Overseer/
            // Regular Elder items this drawer used to show; gated the same
            // way Coordinator Elder enrollment itself always was
            // ([canEnrollRegularElderOrPublisher] already implies
            // [canEnrollCoordinatorElder]/[canEnrollServiceOverseer] — see
            // AdminHomeScreen's own derivation of all three).
            if (canEnrollRegularElderOrPublisher) add(SideItem(stringResource(Res.string.side_elders), Icons.Rounded.PersonAdd, Destinations.MANAGE_ELDERS))
            if (canEnrollMinisterialServant) add(SideItem(stringResource(Res.string.side_ministerial_servant), Icons.Rounded.PersonAdd, Destinations.MANAGE_MINISTERIAL_SERVANTS))
            if (canManageGroups) add(SideItem(stringResource(Res.string.side_groups), Icons.Rounded.Groups, Destinations.MANAGE_GROUPS))
            if (canManageCircuits) {
                add(SideItem("Circuit Overseer Management", Icons.Rounded.Groups, Destinations.CIRCUIT_MANAGEMENT))
                add(SideItem("Circuit Codes", Icons.Rounded.Tune, Destinations.MANAGE_CIRCUIT_CODES))
                add(SideItem("Circuit Overseer Accounts", Icons.Rounded.ManageAccounts, Destinations.MANAGE_CIRCUIT_OVERSEERS))
                add(SideItem("Circuit Assignment", Icons.Rounded.Assignment, Destinations.CIRCUIT_ASSIGNMENT))
            }
            if (canManageTerritoryAssignments) add(SideItem(stringResource(Res.string.side_territory_assignments), Icons.Rounded.Assignment, Destinations.MANAGE_TERRITORY_ASSIGNMENTS))
            // Routes to the Manage Publishers *list* screen (which has its own
            // onAddNew FAB into ENROLL_PUBLISHER), matching every other entry
            // in this section (Congregations/Admins/Coordinator Elder/Regular
            // Elder all go to their list screen too) — this used to jump
            // straight to enrollment instead, which meant Admin/Coordinator
            // Elder had no way to reach the Publisher list/edit screen at all
            // once the old tile grid (their only other route to it) was
            // hidden for them.
            if (canEnrollPublisher) add(SideItem(stringResource(Res.string.side_publisher), Icons.Rounded.People, Destinations.MANAGE_PUBLISHERS))
        }
        if (enrollmentItems.isNotEmpty()) add(SideSection(stringResource(Res.string.side_section_enrollment), enrollmentItems))

        // REPORTS — "Everything related to reporting, report submission,
        // report management, report history, statistics, preaching records,
        // and report exports" (spec §3/final requirement). House Holder
        // Visit History and Preaching Time Records land here per spec §14/
        // §15/§36 explicitly; Interested Records/Forward Requests join them
        // as the same underlying-record review/approval domain (spec §3's
        // own "Report Approval/Unlocking" example already treats a review
        // queue as a Reports-shaped action, not Enrollment or Control Panel).
        val reportsItems = buildList {
            // "Elder Dashboard Consistent with Admin/Super-Admin Dashboard"
            // spec — these two used to be reachable only via the old tile-grid
            // Main Form body, which is now hidden for every admin-track role
            // (Super-Admin/Admin already, Coordinator/Regular Elder as of this
            // change too, see AdminHomeScreen). Every session reaching this
            // drawer already has an admin-track role, so no extra gating
            // boolean is needed here — this restores the same reach the tile
            // grid used to give everyone, rather than stranding whoever's
            // tile grid gets hidden next.
            if (isCircuitOverseer) {
                add(SideItem("Congregations", Icons.Rounded.AccountBalance, Destinations.CIRCUIT_CONGREGATIONS))
                add(SideItem("Received Reports", Icons.Rounded.Inbox, Destinations.RECEIVED_REPORTS))
                add(SideItem("Announcements", Icons.Rounded.Campaign, Destinations.CO_ANNOUNCEMENTS))
                add(SideItem("Publishers", Icons.Rounded.People, Destinations.circuitPublishers()))
                add(SideItem("Elders & Ministerial Servants", Icons.Rounded.PersonAdd, Destinations.circuitLeaders()))
                add(SideItem("Report Submission", Icons.Rounded.Assessment, Destinations.REPORT_SUBMISSION))
                add(SideItem("Meeting Attendance", Icons.Rounded.Groups, Destinations.MEETING_ATTENDANCE))
                add(SideItem("Comparative Report", Icons.Rounded.Assessment, Destinations.CONGREGATION_COMPARATIVE))
            }
            if (canManageMeetingAttendance) {
                add(SideItem("Meeting Attendance", Icons.Rounded.Groups, Destinations.MEETING_ATTENDANCE))
            }
            // The Circuit Overseer's dashboard is their Main Form (and the Circuit Dashboard above) — the general Dashboard is not offered to them.
            if (!isCircuitOverseer) add(SideItem(stringResource(Res.string.home_dashboard_header), Icons.Rounded.BarChart, Destinations.DASHBOARD_REPORTS))
            // The Circuit Overseer reaches the Field Service Report through Received Reports (above), not as a separate module.
            if (canViewFieldServiceReport && !isCircuitOverseer) add(SideItem("Field Service Report", Icons.Rounded.Assessment, Destinations.FIELD_SERVICE_REPORT))
            if (canViewSentReports && !isCircuitOverseer) add(SideItem("My Submitted Reports", Icons.Rounded.Inbox, Destinations.SENT_REPORTS))
            if (canEnterManualFieldService) add(SideItem("Manual Field Service Record", Icons.Rounded.EditNote, Destinations.MANUAL_FIELD_SERVICE))
            if (canViewDeletedRecords) add(SideItem("Deleted Records", Icons.Rounded.RestoreFromTrash, Destinations.DELETED_RECORDS))
            // "Consolidate 'Forward Request' Modules for Super Admin" — one
            // entry only. For Super-Admin, GoPreachNavGraph passes
            // isSuperAdmin = true into this same destination, which then
            // shows every status across all congregations plus edit/delete
            // (see ForwardRequestsScreen's own doc comment) instead of a
            // second, separate drawer item/screen.
            if (canViewForwardRequests) {
                add(SideItem(stringResource(Res.string.side_forward_requests), Icons.Rounded.SwapHoriz, Destinations.FORWARD_REQUESTS))
            }
            // "Add a New Module: House Holder Assignment" — Super-Admin/
            // Admin/Service Overseer only (see AdminHomeScreen's own
            // derivation of this flag for the exact access set).
            if (canManageHouseholderAssignment) {
                add(SideItem(stringResource(Res.string.side_publisher_assignment), Icons.Rounded.AssignmentInd, Destinations.PUBLISHER_ASSIGNMENT))
                add(SideItem("Comparative Report", Icons.Rounded.BarChart, Destinations.COMPARATIVE_REPORT))
            }
            if (canViewInterestedPeopleScope) add(SideItem(stringResource(Res.string.side_interested_records_scoped), Icons.Rounded.Groups, Destinations.SCOPED_INTERESTED_RECORDS))
            // "The super admin can see all congregation Search[ing]/Bible
            // Study/Return Visit record[s]... Add, Edit, [and permanently]
            // Delete the record" — Super-Admin only, unlike every other
            // Searching/Return Visit/Bible Study entry point in this app
            // (Publisher context, own records only).
            // "House Holder Visit History" — Super-Admin only in this
            // drawer; a Publisher reaches the same screen via their own Main
            // Form tile instead (see PublisherHomeScreen).
            if (isSuperAdmin) add(SideItem(stringResource(Res.string.side_householder_visit_history), Icons.AutoMirrored.Rounded.ListAlt, Destinations.HOUSEHOLDER_VISIT_HISTORY))
        }
        if (reportsItems.isNotEmpty()) add(SideSection(stringResource(Res.string.side_section_reports), reportsItems))

        // CONTROL PANEL — "Everything related to system settings,
        // configuration, assignments, communication controls, logs,
        // administrative controls, and Theme Color Settings" (spec §4/final
        // requirement).
        val controlPanelItems = buildList {
            if (isSuperAdmin) add(SideItem(stringResource(Res.string.side_backup_restore), Icons.Rounded.Backup, Destinations.BACKUP_RESTORE))
            if (canAccessControlPanel) add(SideItem(stringResource(Res.string.side_appearance_app_logo), Icons.Rounded.Tune, Destinations.CONTROL_PANEL))
            // Circuit Overseer: no Group Chat, Calendar, Share Location or Find Location.
            if (!isCircuitOverseer) add(SideItem(stringResource(Res.string.side_group_chat_setting), Icons.AutoMirrored.Rounded.Chat, Destinations.GROUP_CHAT_SETTING))
            if (canManageAnnouncements) add(SideItem(stringResource(Res.string.side_announcements), Icons.Rounded.Campaign, Destinations.MANAGE_ANNOUNCEMENTS))
            if (!isCircuitOverseer) add(SideItem(stringResource(Res.string.home_nav_calendar), Icons.Rounded.CalendarMonth, Destinations.CALENDAR))
            if (canEditMeetingAssignments) add(SideItem(stringResource(Res.string.home_tile_meeting_cart_assignment_title), Icons.Rounded.Event, Destinations.MEETING_ASSIGNMENTS))
            if (canManageTerritories) add(SideItem(stringResource(Res.string.side_territory_map), Icons.Rounded.Map, Destinations.MANAGE_TERRITORIES_BASE))
            if (!isCircuitOverseer) add(SideItem(stringResource(Res.string.side_share_location_settings), Icons.Rounded.LocationOn, Destinations.SHARE_LOCATION))
            if (!isCircuitOverseer) add(SideItem(stringResource(Res.string.home_tile_find_location_title), Icons.Rounded.Navigation, Destinations.FIND_LOCATION))
            if (canViewUserLogs) add(SideItem(stringResource(Res.string.side_user_logs), Icons.Rounded.History, Destinations.USER_LOGS))
            if (canViewContactRecord) add(SideItem(stringResource(Res.string.side_contact_record), Icons.Rounded.Contacts, Destinations.CONTACT_RECORD))
            // "Theme Color Settings — Simplified User Experience" (spec §16/
            // §24) — a per-device preference every signed-in role already
            // had (via the profile menu's Settings screen); shown here
            // unconditionally, same as Account Settings at the bottom of
            // this drawer, not gated by [canAccessControlPanel] — being
            // listed under Control Panel doesn't narrow who could already
            // reach it (spec §34).
            add(SideItem(stringResource(Res.string.side_theme_color_settings), Icons.Rounded.Palette, Destinations.THEME_COLOR_SETTINGS))
            if (canManageSessionTimeout) add(SideItem("Session Timeout Setting", Icons.Rounded.Timer, Destinations.SESSION_TIMEOUT_SETTING))
            if (canManageUsers) add(SideItem(stringResource(Res.string.side_user_management), Icons.Rounded.ManageAccounts, Destinations.MANAGE_USERS))
            if (canManageAccountCredentials) add(SideItem(stringResource(Res.string.side_account_management), Icons.Rounded.ManageAccounts, Destinations.ACCOUNT_MANAGEMENT))
            if (canAccessControlPanel) add(SideItem(stringResource(Res.string.side_credit_hour_categories), Icons.Rounded.Timer, Destinations.CREDIT_HOUR_CATEGORIES))
        }
        if (controlPanelItems.isNotEmpty()) add(SideSection(stringResource(Res.string.side_section_control_panel), controlPanelItems))
    }

    val availableItems = sections.flatMap { it.items }
    LaunchedEffect(availableItems.map { it.route }) { onItemsAvailable(availableItems) }

    // The same design system for every role: the modules this session is authorized to see (decided above, unchanged) are shown under
    // CONTROL PANEL / INPUT / RECORD / REPORTS / MANAGEMENT / SETTINGS / TOOLS - a group a role has no modules in is simply not shown.
    // Every group starts COLLAPSED and each is toggled on its own; nothing here expands one group because another was opened or because
    // a module was visited.
    val grouped = NavGroup.entries.mapNotNull { group ->
        availableItems.filter { navGroupOf(it.route) == group }.map { it.withStandardIcon() }.takeIf { it.isNotEmpty() }?.let { group to it }
    }
    val expanded = remember { mutableStateMapOf<NavGroup, Boolean>() }

    ModalDrawerSheet {
        NavProfileHeader(
            appName = stringResource(Res.string.app_name),
            userName = userName,
            roleLabel = roleLabel,
            scopeLabel = scopeLabel,
            profileImageUrl = profileImageUrl,
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(grouped, key = { it.first }) { (group, groupItems) ->
                NavGroupSection(
                    group = group,
                    expanded = expanded[group] == true,
                    containsActive = groupItems.any { it.route == activeRoute },
                    itemCount = groupItems.size,
                    onToggle = { expanded[group] = expanded[group] != true },
                ) {
                    groupItems.forEach { item -> SideDrawerItem(item, group, activeRoute, onNavigate, dragState, onDragStarted) }
                }
            }
        }
        // Footer: secondary actions, kept apart from the navigation groups; Sign out is visually distinct so it is not tapped by accident.
        HorizontalDivider()
        Column(Modifier.padding(vertical = 4.dp)) {
            FooterRow(Icons.Rounded.Settings, "Settings", selected = activeRoute == Destinations.SETTINGS || activeRoute == Destinations.ACCOUNT_SETTINGS) { onNavigate(Destinations.SETTINGS) }
            if (onSwitchToPublisher != null) FooterRow(Icons.Rounded.SwapHoriz, stringResource(Res.string.side_ministry_report_app), selected = false, onClick = onSwitchToPublisher)
            FooterRow(Icons.AutoMirrored.Rounded.Logout, stringResource(Res.string.side_sign_out), selected = false, danger = true, onClick = onSignOut)
        }
    }
}

/**
 * Where a module is shown. This is only a display decision - authorization is made by the caller's own booleans before an item ever reaches
 * the panel, and the route is never changed. A module not listed here falls under TOOLS so every item always belongs to a group.
 */
private fun navGroupOf(route: String): NavGroup = when (route) {
    Destinations.DASHBOARD_REPORTS -> NavGroup.CONTROL_PANEL

    Destinations.MANUAL_FIELD_SERVICE, Destinations.MEETING_ATTENDANCE, Destinations.MEETING_ASSIGNMENTS, Destinations.PUBLISHER_ASSIGNMENT -> NavGroup.INPUT

    Destinations.SCOPED_INTERESTED_RECORDS, Destinations.HOUSEHOLDER_VISIT_HISTORY, Destinations.FORWARD_REQUESTS, Destinations.DELETED_RECORDS,
    Destinations.CONTACT_RECORD, Destinations.USER_LOGS, Destinations.CIRCUIT_CONGREGATIONS,
    Destinations.circuitPublishers(), Destinations.circuitLeaders() -> NavGroup.RECORD

    Destinations.FIELD_SERVICE_REPORT, Destinations.CIRCUIT_FS_REPORTS, Destinations.CIRCUIT_REPORTS_INBOX, Destinations.CIRCUIT_REPORT,
    Destinations.RECEIVED_REPORTS, Destinations.RECEIVED_SNAPSHOTS, Destinations.SENT_REPORTS, Destinations.RECEIVED_ATTENDANCE,
    Destinations.REPORT_SUBMISSION, Destinations.COMPARATIVE_REPORT, Destinations.CONGREGATION_COMPARATIVE -> NavGroup.REPORTS

    Destinations.MANAGE_CONGREGATIONS, Destinations.MANAGE_ADMINS, Destinations.MANAGE_ELDERS, Destinations.MANAGE_MINISTERIAL_SERVANTS,
    Destinations.MANAGE_GROUPS, Destinations.MANAGE_PUBLISHERS, Destinations.MANAGE_TERRITORY_ASSIGNMENTS, Destinations.ACCOUNT_MANAGEMENT,
    Destinations.MANAGE_USERS, Destinations.CIRCUIT_MANAGEMENT, Destinations.MANAGE_CIRCUIT_CODES, Destinations.MANAGE_CIRCUIT_OVERSEERS,
    Destinations.CIRCUIT_ASSIGNMENT -> NavGroup.MANAGEMENT

    Destinations.CONTROL_PANEL, Destinations.THEME_COLOR_SETTINGS, Destinations.SESSION_TIMEOUT_SETTING, Destinations.CREDIT_HOUR_CATEGORIES -> NavGroup.SETTINGS

    else -> NavGroup.TOOLS
}

/** One consistent, function-based icon per module: the duplicates and look-alikes of the old drawer are replaced; the destination is untouched. */
private fun SideItem.withStandardIcon(): SideItem {
    val icon = when (route) {
        Destinations.MANAGE_ELDERS -> Icons.Rounded.SupervisorAccount
        Destinations.MANAGE_MINISTERIAL_SERVANTS -> Icons.Rounded.Badge
        Destinations.circuitLeaders() -> Icons.Rounded.SupervisorAccount
        Destinations.CIRCUIT_ASSIGNMENT -> Icons.Rounded.AltRoute
        Destinations.MANAGE_CIRCUIT_CODES -> Icons.Rounded.VpnKey
        Destinations.CREDIT_HOUR_CATEGORIES -> Icons.Rounded.Category
        Destinations.ACCOUNT_MANAGEMENT -> Icons.Rounded.Key
        Destinations.CONGREGATION_COMPARATIVE, Destinations.COMPARATIVE_REPORT -> Icons.Rounded.CompareArrows
        Destinations.CIRCUIT_REPORTS_INBOX, Destinations.RECEIVED_REPORTS -> Icons.Rounded.Inbox
        Destinations.MEETING_ATTENDANCE -> Icons.Rounded.HowToReg
        else -> this.icon
    }
    return if (icon === this.icon) this else copy(icon = icon)
}

@Composable
private fun FooterRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One side-panel entry. Tap navigates; long-press-and-drag lifts it toward the Quick Access grid (the drawer closes so the
 * grid is visible, and the finger keeps driving [dragState] in window coordinates until release). */
@Composable
private fun SideDrawerItem(
    item: SideItem,
    group: NavGroup,
    activeRoute: String?,
    onNavigate: (String) -> Unit,
    dragState: com.emfitsolutions.gopreach.ui.screens.home.QuickAccessDragState?,
    onDragStarted: () -> Unit,
) {
    var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lifted by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (lifted) 1.06f else 1f, androidx.compose.animation.core.tween(140), label = "sideLift")
    var modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onGloballyPositioned { coords = it }
    if (dragState != null) {
        modifier = modifier.pointerInput(item.route) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    lifted = true
                    dragState.item = item
                    coords?.let { dragState.rootPos = it.localToRoot(offset) }
                    onDragStarted()
                },
                onDrag = { change, _ ->
                    change.consume()
                    coords?.let { dragState.rootPos = it.localToRoot(change.position) }
                },
                onDragEnd = {
                    lifted = false
                    dragState.item?.let { dragState.onDrop?.invoke(it, dragState.rootPos) }
                    dragState.item = null
                },
                onDragCancel = {
                    lifted = false
                    dragState.item = null
                },
            )
        }
    }
    NavMenuRow(
        icon = item.icon,
        label = item.label,
        group = group,
        selected = activeRoute == item.route,
        onClick = { onNavigate(item.route) },
        modifier = modifier,
    )
}
