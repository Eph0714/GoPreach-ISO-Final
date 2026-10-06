package com.emfitsolutions.gopreach.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.emfitsolutions.gopreach.data.export.IncomingBibleTextImportHolder
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.ui.components.rememberActionToast
import com.emfitsolutions.gopreach.ui.screens.about.AboutScreen
import com.emfitsolutions.gopreach.ui.screens.accountmanagement.AccountManagementScreen
import com.emfitsolutions.gopreach.ui.screens.account.AccountSettingsScreen
import com.emfitsolutions.gopreach.ui.screens.credithours.CreditHourCategoriesScreen
import com.emfitsolutions.gopreach.ui.screens.account.PublisherSchedulesScreen
import com.emfitsolutions.gopreach.ui.screens.auth.ForcedPasswordChangeScreen
import com.emfitsolutions.gopreach.ui.screens.auth.ForgotPasswordScreen
import com.emfitsolutions.gopreach.ui.screens.admins.ManageAdminsScreen
import com.emfitsolutions.gopreach.ui.screens.backup.BackupRestoreScreen
import com.emfitsolutions.gopreach.ui.screens.calendar.CalendarScope
import com.emfitsolutions.gopreach.ui.screens.calendar.CalendarScreen
import com.emfitsolutions.gopreach.ui.screens.congregations.ManageCongregationsScreen
import com.emfitsolutions.gopreach.ui.screens.contactrecord.ContactRecordScreen
import com.emfitsolutions.gopreach.ui.screens.controlpanel.ControlPanelScreen
import com.emfitsolutions.gopreach.ui.screens.dashboard.DashboardReportsScreen
import com.emfitsolutions.gopreach.ui.screens.elders.ManageEldersScreen
import com.emfitsolutions.gopreach.ui.screens.elders.ManageEldersViewModel
import com.emfitsolutions.gopreach.ui.screens.elders.ManageMinisterialServantsScreen
import com.emfitsolutions.gopreach.ui.screens.enrollment.AdminEnrollmentScreen
import com.emfitsolutions.gopreach.ui.screens.enrollment.CongregationEnrollmentScreen
import com.emfitsolutions.gopreach.ui.screens.enrollment.EldersEnrollmentScreen
import com.emfitsolutions.gopreach.ui.screens.enrollment.MinisterialServantEnrollmentScreen
import com.emfitsolutions.gopreach.ui.screens.enrollment.PublisherEnrollmentScreen
import com.emfitsolutions.gopreach.ui.screens.findlocation.FindLocationEnrollmentAccess
import com.emfitsolutions.gopreach.ui.screens.findlocation.FindLocationScreen
import com.emfitsolutions.gopreach.ui.screens.groups.ManageGroupsScreen
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.TerritoryAssignmentWizardScreen
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.TerritoryAssignmentsScreen
import com.emfitsolutions.gopreach.ui.screens.home.AdminHomeScreen
import com.emfitsolutions.gopreach.ui.screens.home.PublisherHomeScreen
import com.emfitsolutions.gopreach.ui.screens.householderassignment.IncomingHouseholderAssignmentsScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.ForwardRequestsScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.PublisherForwardRequestsScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.ElderInterestedRecordsScreen
import com.emfitsolutions.gopreach.ui.screens.pipeline.PublisherAssignmentScreen
import com.emfitsolutions.gopreach.ui.screens.bibletext.BibleTextRecordScreen
import com.emfitsolutions.gopreach.ui.screens.preachingtime.PreachingTimeRecordScreen
import com.emfitsolutions.gopreach.ui.screens.monthlyreport.MonthlyReportScreen
import com.emfitsolutions.gopreach.ui.screens.monthlyreport.MySubmittedReportsScreen
import com.emfitsolutions.gopreach.ui.screens.login.LoginScreen
import com.emfitsolutions.gopreach.ui.screens.login.SelectRoleScreen
import com.emfitsolutions.gopreach.ui.screens.publishers.ManagePublishersScreen
import com.emfitsolutions.gopreach.ui.screens.announcements.AnnouncementsScreen
import com.emfitsolutions.gopreach.ui.screens.meetingassignments.MeetingAssignmentsScreen
import com.emfitsolutions.gopreach.ui.screens.meetingassignments.MyAssignmentsScreen
import com.emfitsolutions.gopreach.ui.screens.groupchat.GroupChatListScreen
import com.emfitsolutions.gopreach.ui.screens.groupchat.GroupChatScreen
import com.emfitsolutions.gopreach.ui.screens.settings.SettingsScreen
import com.emfitsolutions.gopreach.ui.screens.sharelocation.ShareLocationScreen
import com.emfitsolutions.gopreach.ui.screens.territories.TerritoryMapScreen
import com.emfitsolutions.gopreach.ui.screens.userlogs.UserLogsScreen
import com.emfitsolutions.gopreach.ui.screens.users.AddEditUserScreen
import com.emfitsolutions.gopreach.ui.screens.users.ManageUsersScreen
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.data.model.ScopeType

/**
 * Root navigation graph. Routing between Login / role-selection / forced-
 * password-change / the Admin or Publisher home is reactive, driven off
 * [SessionViewModel] (backed by [com.emfitsolutions.gopreach.domain.UserSession])
 * rather than one-off nav calls, so a session change from *any* source —
 * sign-in, sign-out, a role-selection choice, a password change completing,
 * or Firebase restoring a persisted login on app start — always lands on the
 * right screen. "Multiple Role Login Detection & Role Selection" spec §7/§11
 * — a person holding more than one active role always picks exactly one at
 * login (see [Destinations.SELECT_ROLE]) and operates only under that role's
 * own permissions/scope for the rest of the session; switching to a
 * different one of their own roles means signing out and back in, never a
 * mid-session toggle.
 */
@Composable
fun GoPreachNavGraph(
    navController: NavHostController = rememberNavController(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val currentPersonId = session.person?.id.orEmpty()
    // "Multiple Role Login Detection & Role Selection" spec §7 — "the
    // selected role must control the session... do not automatically
    // combine permissions from all roles." [currentRole] now comes from
    // [SessionState.activeRoleAssignment] (the single account a multi-role
    // user picked on SelectRoleScreen, or the only one a single-role user
    // has) instead of always the most senior Admin-track role a person
    // happens to hold — an Admin who's also, say, a Coordinator Elder in a
    // different congregation genuinely operates as only one of the two per
    // session now, whichever they chose at login.
    val currentRole = session.activeAdminRole
    // The congregation the *active* role is scoped to (spec §3 permission
    // matrix: "Own congregation" everywhere except Super-Admin's "All").
    // Bug fix history ("the publisher cannot see other publisher in shared
    // location... under the same congregation"): this used to be its own
    // independent firstOrNull scan over every RoleAssignment (first missing
    // an ACTIVE filter entirely, then merely disagreeing with whichever role
    // was picked as "current") — now derived from the exact same
    // session.activeRoleAssignment [currentRole] itself comes from, so the
    // two can never disagree about which role is actually driving this
    // session.
    val activeAssignment = session.activeRoleAssignment
    val ownCongregationId = activeAssignment?.takeIf {
        (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in setOf(AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.MINISTERIAL_SERVANT)
    }?.congregationId
    // A Regular Elder's own group (spec §3: their CRUD/view scope is "own group", not congregation-wide).
    val ownGroupAssignment = activeAssignment?.takeIf {
        (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER
    }
    // A Publisher's own group/congregation (spec §6.1: publishers share
    // within, and only see, their own group). Spec §7 "do not combine
    // permissions from multiple roles": deliberately null for anyone whose
    // *active selected role* isn't itself Publisher — a Coordinator Elder
    // who also holds an active Publisher category no longer sees Publisher-
    // only affordances like "share my own location" while operating under
    // the Coordinator Elder role they picked at login.
    val ownPublisherAssignment = activeAssignment?.takeIf { it.resolvedRoleTypeOrNull() is RoleType.Publisher }

    val targetRoute = when {
        session.isLoading -> null
        !session.isSignedIn -> Destinations.LOGIN
        session.requiresPasswordChange -> Destinations.FORCED_PASSWORD_CHANGE
        // Spec §4 — shown only for a genuinely multi-role account, and only
        // until [UserSession.selectRole] resolves it.
        session.needsRoleSelection -> Destinations.SELECT_ROLE
        session.isActivePublisherRole -> Destinations.PUBLISHER_HOME
        else -> Destinations.ADMIN_HOME // an Admin-track active role, and "no role at all" (shows an empty-state shell)
    }

    // Deleted Records: with automatic permanent deletion switched on, sweep what has outlived its retention when
    // someone signs in and every few hours while the app stays open. Only what this user manages is touched, and
    // nothing before its calculated date; with the setting off this does nothing.
    val trashMaintenance: com.emfitsolutions.gopreach.ui.screens.deletedrecords.DeletedRecordsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    LaunchedEffect(currentPersonId, currentRole, ownCongregationId) {
        if (currentPersonId.isBlank()) return@LaunchedEffect
        val access = com.emfitsolutions.gopreach.ui.screens.deletedrecords.DeletedRecordsAccess(
            personId = currentPersonId,
            isSuperAdmin = currentRole == AdminRole.SUPER_ADMIN,
            manageableCongregationId = if (currentRole in setOf(
                    AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
                )
            ) ownCongregationId else null,
        )
        while (true) {
            trashMaintenance.purgeExpired(access)
            kotlinx.coroutines.delay(6L * 60 * 60 * 1000)
        }
    }

    // "Enable Biometric Login?" — offered once, right after a password sign-in.
    com.emfitsolutions.gopreach.ui.screens.login.BiometricEnrollmentOfferHost(
        signedIn = session.isSignedIn && !session.requiresPasswordChange,
    )

    LaunchedEffect(targetRoute) {
        if (targetRoute != null && navController.currentDestination?.route != targetRoute) {
            navController.navigate(targetRoute) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Spec §9 — a Super-Admin deactivating/suspending someone mid-session must
    // actually end that session, not just block their *next* sign-in attempt.
    LaunchedEffect(session.isAccountBlocked) {
        if (session.isAccountBlocked) sessionViewModel.signOut()
    }

    // "If the receiving Publisher downloads and taps the exported file, it
    // will automatically import to his device" — once a Publisher is signed
    // in, jump straight to My Bible Text Record so
    // [com.emfitsolutions.gopreach.ui.screens.bibletext.BibleTextRecordScreen]
    // (the only place with both the Publisher's id and their existing
    // Events) can pick up and run the import. Re-armed on every sign-in
    // (not just app cold start), so a file tapped while already logged out
    // still imports right after the Publisher logs in.
    LaunchedEffect(currentPersonId) {
        if (currentPersonId.isNotBlank()) {
            IncomingBibleTextImportHolder.uri.collect { uri ->
                if (uri != null) navController.navigate(Destinations.MY_BIBLE_TEXT_RECORD)
            }
        }
    }

    // Spec §14 system messages — each keyed on the signed-in person's own id,
    // so it fires once per sign-in (a value that only exists while
    // [session.person] is non-null) rather than on every later
    // recomposition/role-assignment update.
    val showSessionToast = rememberActionToast()
    LaunchedEffect(session.person?.id) {
        session.person?.let { showSessionToast("Login successful. Welcome, ${it.fullName}.") }
    }
    LaunchedEffect(session.person?.id, session.isLoading) {
        if (!session.isLoading && session.person != null && session.roleOptions.isEmpty()) {
            showSessionToast("Your account does not have an active role assigned. Please contact the administrator.")
        }
    }

    // Circuit Overseer / custom users (spec §5-§8) carry no built-in scope —
    // everything they may see comes from their UserAccessGrant instead.
    val grant = session.grant
    val canManageUsers = currentRole == AdminRole.SUPER_ADMIN ||
        (grant?.resolvedPermissions?.contains(Permission.MANAGE_USERS) == true)
    // Account Management spec §5 permission matrix — same four (well, five,
    // counting SECRETARY alongside SERVICE_OVERSEER per this app's existing
    // convention) built-in roles the spec's own §10 tree lists; Regular
    // Elder/Ministerial Servant/Publisher/Circuit Overseer never see this
    // tile, matching the spec's explicit account-type table.
    val canManageCreditHourCategories = currentRole == AdminRole.SUPER_ADMIN || currentRole == AdminRole.ADMIN_PER_CONGREGATION
    val canManageAccountCredentials =currentRole == AdminRole.SUPER_ADMIN ||
        currentRole == AdminRole.ADMIN_PER_CONGREGATION ||
        currentRole == AdminRole.COORDINATOR_ELDER ||
        currentRole == AdminRole.SERVICE_OVERSEER ||
        currentRole == AdminRole.SECRETARY

    NavHost(navController = navController, startDestination = Destinations.LOGIN) {
        composable(Destinations.LOGIN) {
            LoginScreen(
                onForgotPasswordClick = { navController.navigate(Destinations.FORGOT_PASSWORD) },
                onSignedIn = { /* routing handled reactively above */ },
            )
        }
        composable(Destinations.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Destinations.FORGOT_PASSWORD) {
            ForgotPasswordScreen(onBack = { navController.popBackStack() })
        }
        composable(Destinations.FORCED_PASSWORD_CHANGE) {
            ForcedPasswordChangeScreen(onCompleted = { /* routing handled reactively above */ })
        }
        composable(Destinations.SELECT_ROLE) {
            SelectRoleScreen(
                personName = session.person?.fullName.orEmpty(),
                roleOptions = session.roleOptions,
                onContinue = { option -> sessionViewModel.selectRole(option.assignment.id) },
                onSignOut = { sessionViewModel.signOut() },
            )
        }
        composable(Destinations.ADMIN_HOME) {
            AdminHomeScreen(
                // "Multiple Role Login Detection & Role Selection" spec §7/
                // §11 — the mid-session Admin<->Publisher switch this used to
                // offer for a person holding both an Admin-track role and an
                // active Publisher category is retired in favor of the new
                // login-time role selector above: Publisher is now just
                // another SelectRoleScreen entry, same as Coordinator Elder
                // or Regular Elder, and switching roles mid-session (any
                // combination, not just this one) always goes through
                // sign-out + sign-in + re-selecting, per spec §11.
                onSwitchToPublisher = null,
                canManageUsers = canManageUsers,
                canManageAccountCredentials = canManageAccountCredentials,
                // Side panel/dashboard-tile navigation always sits directly on
                // top of the Main Form, never chained onto whichever menu
                // screen happened to be open before it — tapping Groups then
                // (without backing out) Publishers used to push both onto the
                // stack, so Back from Publishers went to Groups, then Back
                // again all the way past Home to the exit-confirmation/sign-
                // out path, reading as "closing everything" instead of
                // returning to the Main Form. popUpTo(ADMIN_HOME) here drops
                // any such in-between menu screen first, so Back from any one
                // of them always lands on the Main Form in a single step.
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                        popUpTo(Destinations.ADMIN_HOME) { inclusive = false }
                    }
                },
            )
        }
        composable(Destinations.PUBLISHER_HOME) {
            PublisherHomeScreen(
                // See ADMIN_HOME's own onSwitchToPublisher comment — retired
                // the same way, for the same reason.
                onSwitchToAdmin = null,
                onNavigate = { route -> navController.navigate(route) },
                canManageCreditHourCategories = canManageCreditHourCategories,
            )
        }

        composable(Destinations.ENROLL_CONGREGATION) {
            CongregationEnrollmentScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(Destinations.ENROLL_ADMIN) {
            AdminEnrollmentScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        // "Consolidate Elder, Coordinator Elder, Service Overseer and
        // Secretary Enrollment" — one Add form (Regular Elder/Coordinator
        // Elder/Service Overseer/Secretary, all via checkboxes) replaces the
        // three separate ENROLL_COORDINATOR_ELDER/ENROLL_SERVICE_OVERSEER/
        // ENROLL_REGULAR_ELDER routes those used to be.
        composable(Destinations.ENROLL_ELDER) {
            EldersEnrollmentScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(Destinations.ENROLL_MINISTERIAL_SERVANT) {
            MinisterialServantEnrollmentScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(Destinations.ENROLL_PUBLISHER) {
            PublisherEnrollmentScreen(
                currentPersonId = currentPersonId,
                visibleCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else ownCongregationId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(Destinations.MANAGE_CONGREGATIONS) {
            ManageCongregationsScreen(
                currentPersonId = currentPersonId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Destinations.ENROLL_CONGREGATION) },
            )
        }
        composable(Destinations.MANAGE_ADMINS) {
            ManageAdminsScreen(
                currentPersonId = currentPersonId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Destinations.ENROLL_ADMIN) },
            )
        }
        // "Consolidate Elder, Coordinator Elder, Service Overseer and
        // Secretary Enrollment" — one Manage screen (Regular Elder,
        // Coordinator Elder, Service Overseer, and Secretary all shown
        // together, with a Role filter to narrow) replaces the three
        // separate MANAGE_COORDINATOR_ELDERS/MANAGE_SERVICE_OVERSEERS/
        // MANAGE_REGULAR_ELDERS routes those used to be. Falls back through
        // a Regular Elder's own group's congregation, same as
        // MANAGE_ANNOUNCEMENTS/etc. above, since this module now also covers
        // Regular Elder (whose own RoleAssignment carries a groupId, not a
        // congregationId, directly).
        composable(Destinations.MANAGE_ELDERS) {
            ManageEldersScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId),
                currentPersonId = currentPersonId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Destinations.ENROLL_ELDER) },
            )
        }
        composable(Destinations.MANAGE_MINISTERIAL_SERVANTS) {
            // Same access set as Service Overseer's own list — Super-Admin,
            // Admin, and Coordinator Elder — but with no per-congregation cap.
            ManageMinisterialServantsScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else ownCongregationId,
                currentPersonId = currentPersonId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Destinations.ENROLL_MINISTERIAL_SERVANT) },
            )
        }
        composable(Destinations.BACKUP_RESTORE) {
            BackupRestoreScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.USER_LOGS) {
            UserLogsScreen(
                visibleCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else ownCongregationId,
                canDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.ACCOUNT_MANAGEMENT) {
            AccountManagementScreen(
                actingRole = currentRole,
                actingCongregationId = ownCongregationId,
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.CREDIT_HOUR_CATEGORIES) {
            // Super-Admin / Admin only (same pair the Side Panel/Admin tile
            // already gated the link on) — enforced here too so a Publisher
            // can never reach the management CRUD, even by a direct route.
            if (canManageCreditHourCategories) {
                CreditHourCategoriesScreen(onBack = { navController.popBackStack() })
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(Destinations.CONTACT_RECORD) {
            // Super-Admin sees every congregation; Coordinator Elder is
            // scoped via ownCongregationId, Regular Elder via
            // ownGroupAssignment's own congregationId — same fallback chain
            // DASHBOARD_REPORTS/REPORTS already use for this exact pair of
            // roles.
            ContactRecordScreen(
                visibleCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId),
                onBack = { navController.popBackStack() },
            )
        }
        // Spec §15 — read-only, scoped interested-people list
        // above for Admin/Coordinator Elder/Service Overseer/Regular Elder.
        // [ownGroupAssignment]'s congregationId falls back the same way every
        // other `visibleCongregationId` call site in this file already does;
        // [groupId] stays null for every scope wider than "own Group" (see
        // [ElderInterestedRecordsScreen]'s own null-means-no-narrowing convention).
        composable(Destinations.SCOPED_INTERESTED_RECORDS) {
            ElderInterestedRecordsScreen(
                congregationId = ownCongregationId ?: ownGroupAssignment?.congregationId,
                groupId = ownGroupAssignment?.groupId,
                onBack = { navController.popBackStack() },
            )
        }
        // "House Holder Visit History" — Super-Admin (every authorized
        // congregation, null) and Publisher (their own congregation) only,
        // per that module's own spec; the drawer/tile entries below are
        // gated the same way, but this is the actual scoping boundary.
        composable(Destinations.HOUSEHOLDER_VISIT_HISTORY) {
            // Bug fix ("Admin and other authorized non-Super Admin users...
            // must only be able to view House Holder Visit History records
            // belonging to their assigned congregation"): this used to pass
            // only `ownPublisherAssignment?.congregationId`, which is null
            // for every Admin-track role and Regular Elder (none of them
            // hold a Publisher assignment) — so Admin/Coordinator Elder/
            // Service Overseer/Secretary/Ministerial Servant/Regular Elder
            // all silently fell through to `null`, the exact same "every
            // congregation" scope Super-Admin gets. Now mirrors the same
            // `ownCongregationId ?: ownGroupAssignment?.congregationId ?:
            // ownPublisherAssignment?.congregationId` resolution every other
            // congregation-scoped screen in this nav graph already uses.
            com.emfitsolutions.gopreach.ui.screens.householdervisithistory.HouseholderVisitHistoryScreen(
                congregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId ?: ownPublisherAssignment?.congregationId),
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MANAGE_PUBLISHERS) {
            ManagePublishersScreen(
                currentPersonId = currentPersonId,
                visibleCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else ownCongregationId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Destinations.ENROLL_PUBLISHER) },
            )
        }
        composable(Destinations.MANAGE_GROUPS) {
            // Highest scope across ALL the user's roles, not only the one picked at login.
            val groupScope = com.emfitsolutions.gopreach.domain.GroupAccessScope.resolve(session.person, session.roleAssignments)
            ManageGroupsScreen(
                fixedCongregationId = when (groupScope) {
                    is com.emfitsolutions.gopreach.domain.GroupAccessScope.AllCongregations -> null
                    is com.emfitsolutions.gopreach.domain.GroupAccessScope.Congregation -> groupScope.congregationId
                    is com.emfitsolutions.gopreach.domain.GroupAccessScope.OwnGroup -> groupScope.congregationId
                    else -> ownCongregationId
                },
                currentPersonId = currentPersonId,
                scope = groupScope.takeIf { it.canOpen },
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
            )
        }
        // Territory Assignment opens the FS Group assignment screen directly (no hub, no Per Publisher Assignment).
        composable(Destinations.MANAGE_TERRITORY_ASSIGNMENTS) {
            TerritoryAssignmentsScreen(
                fixedCongregationId = ownCongregationId,
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                // The wizard itself offers a Congregation picker (Super-Admin
                // only, Add only) as its own first step — nothing from this
                // dashboard needs to pre-select one for Add.
                onAddNew = { navController.navigate("territory_assignment_wizard/new/new/new") },
                // Edit addresses a Group's whole territory within one
                // province (not a single assignment id — see
                // Destinations.TERRITORY_ASSIGNMENT_WIZARD's own comment);
                // the dashboard already knows this row's congregationId.
                onEdit = { congregationId, groupId, provinceId ->
                    navController.navigate("territory_assignment_wizard/$congregationId/$groupId/$provinceId")
                },
            )
        }
        composable(
            route = Destinations.TERRITORY_ASSIGNMENT_WIZARD,
            arguments = listOf(
                navArgument("congregationId") { type = NavType.StringType },
                navArgument("groupId") { type = NavType.StringType },
                navArgument("provinceId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val rawCongregationId = backStackEntry.arguments?.getString("congregationId")
            val rawGroupId = backStackEntry.arguments?.getString("groupId")
            val rawProvinceId = backStackEntry.arguments?.getString("provinceId")
            TerritoryAssignmentWizardScreen(
                congregationIdArg = rawCongregationId?.takeIf { it != "new" },
                groupIdArg = rawGroupId?.takeIf { it != "new" },
                provinceIdArg = rawProvinceId?.takeIf { it != "new" }?.toIntOrNull(),
                fixedCongregationId = ownCongregationId,
                currentPersonId = currentPersonId,
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.MANAGE_TERRITORIES,
            arguments = listOf(
                navArgument("focusLat") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("focusLng") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("focusName") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            // "Clicking coordinates should open the Territory Map centered
            // on the Publisher's latest location" — Share Location's own
            // "open in Territory Map" action is the only caller that ever
            // supplies these (see Destinations.territoryMapFocusedOn); every
            // plain Territory Map entry point (side panel, dashboard tiles)
            // navigates to MANAGE_TERRITORIES_BASE, which leaves all three
            // null here, and the screen behaves exactly as before.
            val focusLat = backStackEntry.arguments?.getString("focusLat")?.toDoubleOrNull()
            val focusLng = backStackEntry.arguments?.getString("focusLng")?.toDoubleOrNull()
            val focusName = backStackEntry.arguments?.getString("focusName")
            // "Territory Module will be a map of location of every Search,
            // Interested, Return Visit, Bible Study" — no CRUD anymore, see
            // TerritoryMapScreen's own doc comment (always view-only, so a
            // Publisher reaching this route needs no separate readOnly flag
            // — there's nothing to edit or delete in the first place).
            // Falls back through a Regular Elder's own group's congregation,
            // then a Publisher's own congregation (see ownGroupAssignment/
            // ownPublisherAssignment above) — `ownCongregationId` alone is
            // null for both of them, and null here means "every
            // congregation," same fix as MANAGE_ANNOUNCEMENTS/
            // GROUP_CHAT_SETTING already use. Explicit Super-Admin check
            // first (also null in ownCongregationId) so they still see every
            // congregation regardless of any other assignment they might
            // also hold.
            TerritoryMapScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) {
                    null
                } else {
                    ownCongregationId ?: ownGroupAssignment?.congregationId ?: ownPublisherAssignment?.congregationId
                },
                currentPersonId = currentPersonId,
                focusLat = focusLat,
                focusLng = focusLng,
                focusName = focusName,
                onBack = { navController.popBackStack() },
            )
        }
        // "Group Chat Setting" module (spec §2-6) — canManage/fixedCongregationId
        // follow the exact same convention as every other Manage screen here
        // (visibleCongregationId == null means Super-Admin's "all
        // congregations"); the real enforcement for who may create/manage a
        // group chat still lives server-side in firestore.rules'
        // canManageGroupChatsFor, since a UI value alone is never trusted for
        // that (spec §17).
        val canManageGroupChats = currentRole == AdminRole.SUPER_ADMIN || currentRole == AdminRole.ADMIN_PER_CONGREGATION || currentRole == AdminRole.COORDINATOR_ELDER
        val activeRoleLabel = currentRole?.displayLabel() ?: if (ownPublisherAssignment != null) "Publisher" else "Member"
        composable(Destinations.GROUP_CHAT_SETTING) {
            GroupChatListScreen(
                currentPersonId = currentPersonId,
                canManage = canManageGroupChats,
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else ownCongregationId,
                onBack = { navController.popBackStack() },
                onOpenGroupChat = { chatId -> navController.navigate(Destinations.groupChatDetail(chatId)) },
            )
        }
        composable(
            Destinations.GROUP_CHAT_DETAIL,
            arguments = listOf(navArgument("groupChatId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupChatId = backStackEntry.arguments?.getString("groupChatId").orEmpty()
            GroupChatScreen(
                groupChatId = groupChatId,
                currentPersonId = currentPersonId,
                currentPersonName = session.person?.fullName ?: "—",
                currentPersonRoleLabel = activeRoleLabel,
                canManageSettings = canManageGroupChats,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.DELETED_RECORDS) {
            com.emfitsolutions.gopreach.ui.screens.deletedrecords.DeletedRecordsScreen(
                access = com.emfitsolutions.gopreach.ui.screens.deletedrecords.DeletedRecordsAccess(
                    personId = currentPersonId,
                    isSuperAdmin = currentRole == AdminRole.SUPER_ADMIN,
                    manageableCongregationId = if (currentRole in setOf(
                            AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
                        )
                    ) ownCongregationId else null,
                ),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.FIELD_SERVICE_REPORT) {
            com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId),
                currentPersonId = currentPersonId,
                // Lock / Unlock a publisher's report: same role set the old Publisher Reports module allowed.
                canManageLocks = currentRole in setOf(
                    AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER,
                    AdminRole.REGULAR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
                ),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.DASHBOARD_REPORTS) {
            // Spec §6: this is the actual security boundary, not the screen —
            // Super-Admin sees every congregation (null); everyone else gets a
            // fixed set they cannot escape by any parameter this screen exposes.
            val visibleCongregationIds: Set<String>? = when {
                currentRole == AdminRole.SUPER_ADMIN -> null
                currentRole == AdminRole.CIRCUIT_OVERSEER ->
                    if (grant?.resolvedScopeType == ScopeType.ALL_CONGREGATIONS) null else grant?.scopeCongregationIds?.toSet().orEmpty()
                else -> setOfNotNull(ownCongregationId ?: ownGroupAssignment?.congregationId ?: ownPublisherAssignment?.congregationId)
            }
            DashboardReportsScreen(
                visibleCongregationIds = visibleCongregationIds,
                // "Allow the admin, super admin, coordinator elder, service
                // overseer to export the report to PDF or Excel" — exactly
                // these four roles, not the wider set that can merely view
                // this screen (Regular Elder/Ministerial Servant included).
                canExport = currentRole == AdminRole.SUPER_ADMIN || currentRole == AdminRole.ADMIN_PER_CONGREGATION ||
                    currentRole == AdminRole.COORDINATOR_ELDER || currentRole == AdminRole.SERVICE_OVERSEER || currentRole == AdminRole.SECRETARY,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MANAGE_ANNOUNCEMENTS) {
            // "Announcement Module" — Super-Admin (any congregation), Admin/
            // Coordinator Elder (own congregation only) can create/edit/
            // delete. The notification balloon's "New Announcement [All]"
            // item also deep-links Service Overseer/Regular Elder/
            // Ministerial Servant here now — same screen, but read-only for
            // them, since they were never in the managing set (matches
            // AnnouncementsScreen's Publisher `readOnly = true` usage below).
            val canManageAnnouncementsHere = currentRole in setOf(
                AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER,
            )
            AnnouncementsScreen(
                currentPersonId = currentPersonId,
                // Falls back to the Regular Elder's own group's congregation
                // (see `ownGroupAssignment` above) — `ownCongregationId`
                // alone is null for them, and null here means "every
                // congregation," which would leak every other congregation's
                // announcements to a Regular Elder now that this route is
                // reachable read-only from the notification balloon.
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId),
                readOnly = !canManageAnnouncementsHere,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.PUBLISHER_ANNOUNCEMENTS) {
            // A Publisher's own read-only notification list — scoped to
            // their own congregation, opening it marks the badge seen.
            AnnouncementsScreen(
                currentPersonId = currentPersonId,
                fixedCongregationId = ownPublisherAssignment?.congregationId,
                readOnly = true,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MEETING_ASSIGNMENTS) {
            // "Meeting Assignments" module — Coordinator Elder/Regular
            // Elder/Service Overseer/Admin (own congregation)/Super-Admin
            // (every congregation, picks one) enroll; every other role
            // reaching this route is read-only.
            val canEditMeetingAssignments = currentRole in setOf(
                AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER,
                AdminRole.REGULAR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
            )
            MeetingAssignmentsScreen(
                currentPersonId = currentPersonId,
                // Falls back through a Regular Elder's own group's
                // congregation (see MANAGE_ANNOUNCEMENTS above for the same
                // fix/reasoning).
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId),
                readOnly = !canEditMeetingAssignments,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MY_ASSIGNMENTS) {
            // "The publisher can see all the assignments under his name" —
            // own congregation only, matched by name across every Midweek/
            // Public Talk/Cart Assignment record on file (see
            // MeetingAssignmentsViewModel.myAssignmentsFor's doc comment).
            MyAssignmentsScreen(
                currentPersonName = session.person?.fullName ?: "—",
                congregationId = ownPublisherAssignment?.congregationId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.PUBLISHER_MEETING_ASSIGNMENTS) {
            // A Publisher's own read-only copy — scoped to their own
            // congregation only (spec: "the publisher will see only meeting
            // assignments under their congregation").
            MeetingAssignmentsScreen(
                currentPersonId = currentPersonId,
                fixedCongregationId = ownPublisherAssignment?.congregationId,
                readOnly = true,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.EDIT_MONTHLY_REPORT,
            arguments = listOf(navArgument("targetPersonId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val targetPersonId = backStackEntry.arguments?.getString("targetPersonId").orEmpty()
            MonthlyReportScreen(
                publisherPersonId = targetPersonId,
                allowEditWhenLocked = true,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.SEARCHING_ROUTE,
            arguments = listOf(navArgument("personId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            PipelineScreen(
                publisherPersonId = currentPersonId,
                currentPersonId = currentPersonId,
                congregationId = ownPublisherAssignment?.congregationId.orEmpty(),
                stage = PipelineStage.SEARCHING,
                initialPersonId = backStackEntry.arguments?.getString("personId"),
                // "Allow the publisher to permanently delete their own
                // Return Visit/Bible Study/Searching record" — these three
                // routes only ever show the signed-in session's own records
                // (publisherPersonId = currentPersonId above, no elder-
                // viewing-another-publisher path exists for them), so
                // `ownPublisherAssignment != null` here safely means "this
                // is my own record," same as Super-Admin's existing
                // unrestricted access.
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN || ownPublisherAssignment != null,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.RETURN_VISIT_ROUTE,
            arguments = listOf(navArgument("personId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            PipelineScreen(
                publisherPersonId = currentPersonId,
                currentPersonId = currentPersonId,
                congregationId = ownPublisherAssignment?.congregationId.orEmpty(),
                stage = PipelineStage.RETURN_VISIT,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN || ownPublisherAssignment != null,
                initialPersonId = backStackEntry.arguments?.getString("personId"),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.BIBLE_STUDY_ROUTE,
            arguments = listOf(navArgument("personId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            PipelineScreen(
                publisherPersonId = currentPersonId,
                currentPersonId = currentPersonId,
                congregationId = ownPublisherAssignment?.congregationId.orEmpty(),
                stage = PipelineStage.BIBLE_STUDY,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN || ownPublisherAssignment != null,
                initialPersonId = backStackEntry.arguments?.getString("personId"),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.FORWARD_REQUESTS) {
            // Super-Admin/Admin/Coordinator Elder/Service Overseer keep full
            // Accept/Decline/Assign access, own congregation only for anyone
            // but Super-Admin. Regular Elder/Ministerial Servant also reach
            // this now (the notification balloon's "Incoming approval
            // request for transfer [All]" item), but view-only — same
            // "widen visibility, never widen approval authority" call as
            // Manage Publisher Reports/Announcements above.
            val canActOnForwardRequests = currentRole in setOf(
                AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
            )
            ForwardRequestsScreen(
                congregationIds = if (currentRole == AdminRole.SUPER_ADMIN) null else setOfNotNull(ownCongregationId ?: ownGroupAssignment?.congregationId),
                currentPersonId = currentPersonId,
                readOnly = !canActOnForwardRequests,
                // "Consolidate 'Forward Request' Modules for Super Admin" —
                // the former separate "Forward Request Module" (all
                // statuses, all-congregations filter, edit/delete) is now
                // this same screen's Super-Admin-only view; see
                // ForwardRequestsScreen's own doc comment.
                isSuperAdmin = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.PUBLISHER_FORWARD_REQUESTS) {
            // A Publisher's own "FORWARD TO OTHER PUBLISHER" incoming queue —
            // scoped to themselves, not their congregation (see
            // PublisherForwardRequestsViewModel.incomingRequestsFor).
            PublisherForwardRequestsScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MANUAL_FIELD_SERVICE) {
            // Super-Admin (picks a congregation first), Admin, Service Overseer, Secretary and Coordinator Elder — own congregation only;
            // the screen and its ViewModel both re-check the role and the congregation.
            com.emfitsolutions.gopreach.ui.screens.manualreport.ManualFieldServiceScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId),
                currentPersonId = currentPersonId,
                currentRole = currentRole,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.COMPARATIVE_REPORT) {
            // Same access set and congregation scoping as PUBLISHER_ASSIGNMENT; Super-Admin picks a congregation first.
            com.emfitsolutions.gopreach.ui.screens.reports.ComparativeReportScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId ?: ownPublisherAssignment?.congregationId),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.PUBLISHER_ASSIGNMENT) {
            // "Publisher Assignment" — same congregation-scoping chain as every other Admin module;
            // Super-Admin (null) picks a congregation on the screen itself. Search Coordinates reuses Find Location.
            PublisherAssignmentScreen(
                fixedCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (ownCongregationId ?: ownGroupAssignment?.congregationId ?: ownPublisherAssignment?.congregationId),
                currentPersonId = currentPersonId,
                onSearchCoordinates = { navController.navigate(Destinations.FIND_LOCATION) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.INCOMING_HOUSEHOLDER_ASSIGNMENTS) {
            // A Publisher's own incoming House Holder Assignment queue —
            // scoped to themselves, same convention PUBLISHER_FORWARD_REQUESTS
            // already uses.
            IncomingHouseholderAssignmentsScreen(
                currentPersonId = currentPersonId,
                currentPersonName = session.person?.fullName ?: "—",
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.PREACHING_TIME_RECORD) {
            PreachingTimeRecordScreen(
                publisherPersonId = currentPersonId,
                congregationId = ownPublisherAssignment?.congregationId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MY_BIBLE_TEXT_RECORD) {
            BibleTextRecordScreen(
                publisherPersonId = currentPersonId,
                currentPerson = session.person,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.MONTHLY_REPORT_ROUTE,
            arguments = listOf(navArgument("periodMonth") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            MonthlyReportScreen(
                publisherPersonId = currentPersonId,
                initialPeriodMonth = backStackEntry.arguments?.getString("periodMonth")?.toLongOrNull(),
                onViewHistory = { navController.navigate(Destinations.MY_SUBMITTED_REPORTS) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MY_SUBMITTED_REPORTS) {
            // "Allow the publisher to see all his submitted Report record" —
            // own reports only, every period, read-only.
            MySubmittedReportsScreen(
                publisherPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.SHARE_LOCATION) {
            // "SHARE LOCATION SETTINGS" spec — Super-Admin sees/configures
            // every congregation; Admin/Service Overseer/Coordinator Elder/
            // Regular Elder their own congregation (widened from "own group"
            // for Regular Elder — the new spec's access list says "Own
            // Congregation" for elders); a Publisher sees every other
            // Publisher sharing within their own congregation too ("All
            // Other Publisher in their congregation can see" — widened from
            // "own group only").
            val visibleCongregationId = when (currentRole) {
                AdminRole.SUPER_ADMIN -> null
                AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY -> ownCongregationId
                AdminRole.REGULAR_ELDER -> ownGroupAssignment?.congregationId
                else -> ownPublisherAssignment?.congregationId
            }
            val canManageLocationSettings = currentRole == AdminRole.SUPER_ADMIN || currentRole in setOf(
                AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.REGULAR_ELDER,
            )
            ShareLocationScreen(
                currentPersonId = currentPersonId,
                currentPersonName = session.person?.fullName.orEmpty(),
                visibleCongregationId = visibleCongregationId,
                canShareOwnLocation = ownPublisherAssignment != null,
                canManageLocationSettings = canManageLocationSettings,
                ownCongregationId = if (currentRole == AdminRole.SUPER_ADMIN) null else (visibleCongregationId ?: ownCongregationId),
                onBack = { navController.popBackStack() },
                onOpenTerritoryMap = { lat, lng, name -> navController.navigate(Destinations.territoryMapFocusedOn(lat, lng, name)) },
            )
        }
        composable(Destinations.FIND_LOCATION) {
            // Publisher: enrolls to themselves. Admin-track roles (own congregation, or a
            // Regular Elder's own group's congregation): enroll then ASSIGN PUBLISHER.
            // Super-Admin: picks a congregation first. Look Around reuses the Territory
            // Map's existing focus-on-a-coordinate view and never creates a record.
            FindLocationScreen(
                currentPersonId = currentPersonId,
                enrollmentAccess = FindLocationEnrollmentAccess(
                    isPublisher = ownPublisherAssignment != null,
                    isSuperAdmin = currentRole == AdminRole.SUPER_ADMIN,
                    congregationId = ownPublisherAssignment?.congregationId ?: ownCongregationId ?: ownGroupAssignment?.congregationId,
                    groupId = ownGroupAssignment?.groupId,
                ),
                onLookAround = { lat, lng -> navController.navigate(Destinations.territoryMapFocusedOn(lat, lng, "Found location")) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.CALENDAR) {
            val scope = when (currentRole) {
                AdminRole.SUPER_ADMIN -> CalendarScope.AdminTrack(congregationId = null, canEditAll = true)
                AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.MINISTERIAL_SERVANT ->
                    CalendarScope.AdminTrack(congregationId = ownCongregationId, canEditAll = true)
                AdminRole.REGULAR_ELDER -> CalendarScope.AdminTrack(
                    congregationId = ownGroupAssignment?.congregationId,
                    canEditAll = false,
                    editableGroupId = ownGroupAssignment?.groupId,
                )
                // A Circuit Overseer/custom user is always read-only here — the
                // Calendar screen only supports one-congregation-at-a-time
                // filtering today, so a multi-congregation grant sees every
                // congregation's events rather than an unsupported partial
                // filter (still no edit access either way).
                AdminRole.CIRCUIT_OVERSEER -> CalendarScope.AdminTrack(
                    congregationId = grant?.scopeCongregationIds?.singleOrNull(),
                    canEditAll = false,
                )
                null -> CalendarScope.Publisher(
                    congregationId = ownPublisherAssignment?.congregationId,
                    groupId = ownPublisherAssignment?.groupId,
                )
            }
            CalendarScreen(
                currentPersonId = currentPersonId,
                scope = scope,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToThemeColorSettings = { navController.navigate(Destinations.THEME_COLOR_SETTINGS) },
                // Settings → Data Management → Deleted Records: the roles that manage deleted records.
                currentPersonId = currentPersonId,
                showDeletedRecordsSettings = currentRole in setOf(
                    AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
                ),
                onOpenDeletedRecords = { navController.navigate(Destinations.DELETED_RECORDS) },
            )
        }
        // "Theme Color Settings — Simplified User Experience" (spec §16) —
        // reachable both from Settings (above) and from the Control Panel
        // drawer section (see GoPreachSidePanelContent); same route either
        // way, no duplicate screen.
        composable(Destinations.THEME_COLOR_SETTINGS) {
            com.emfitsolutions.gopreach.ui.screens.settings.ThemeColorSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.SESSION_TIMEOUT_SETTING) {
            com.emfitsolutions.gopreach.ui.screens.settings.SessionTimeoutSettingScreen(
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.CONTROL_PANEL) {
            ControlPanelScreen(
                currentPersonId = currentPersonId,
                canManageLogo = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.ACCOUNT_SETTINGS) {
            AccountSettingsScreen(
                onBack = { navController.popBackStack() },
                // A changed password signs the session out (spec §1); routing
                // back to Login happens reactively above, same as everywhere else.
                onSignedOutForPasswordChange = { },
                // "Add a module to the Publisher Account/Profile" — only
                // shown for a plain Publisher's own active role, same
                // signal every other Publisher-only feature in this file
                // already checks.
                isPublisher = ownPublisherAssignment != null,
                onViewPublisherSchedules = if (ownPublisherAssignment != null) {
                    { navController.navigate(Destinations.PUBLISHER_SCHEDULES) }
                } else null,
            )
        }
        composable(Destinations.PUBLISHER_SCHEDULES) {
            // Same congregation-scoping source PREACHING_TIME_RECORD already
            // uses for a Publisher-scoped screen — this route is Publisher-
            // only (reached from Account Settings' own Publisher-gated link).
            PublisherSchedulesScreen(
                congregationId = ownPublisherAssignment?.congregationId,
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Destinations.MANAGE_USERS) {
            ManageUsersScreen(
                currentPersonId = currentPersonId,
                canPermanentlyDelete = currentRole == AdminRole.SUPER_ADMIN,
                onBack = { navController.popBackStack() },
                onAddNew = { navController.navigate(Destinations.ADD_USER) },
                onEdit = { personId -> navController.navigate(Destinations.editUser(personId)) },
            )
        }
        composable(Destinations.ADD_USER) {
            AddEditUserScreen(
                targetPersonId = null,
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            route = Destinations.EDIT_USER,
            arguments = listOf(navArgument("targetPersonId") { type = NavType.StringType }),
        ) { backStackEntry ->
            AddEditUserScreen(
                targetPersonId = backStackEntry.arguments?.getString("targetPersonId"),
                currentPersonId = currentPersonId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
    }
}
