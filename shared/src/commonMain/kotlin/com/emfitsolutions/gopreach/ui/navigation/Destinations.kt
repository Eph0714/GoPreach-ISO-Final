package com.emfitsolutions.gopreach.ui.navigation

/**
 * All navigation routes in the app. Grouped by the phase that introduces them
 * (see BUILD_PLAN.md). Auth/role-based routing (Phase 2) will decide which of
 * the Admin-context or Publisher-context graphs a session lands on after login.
 */
object Destinations {
    // Phase 0
    const val LOGIN = "login"
    const val ABOUT = "about"

    // Phase 2
    const val FORCED_PASSWORD_CHANGE = "forced_password_change"
    const val FORGOT_PASSWORD = "forgot_password"

    // "Multiple Role Login Detection & Role Selection" — shown after
    // credentials are verified, only for an account with more than one
    // active role (see SessionState.needsRoleSelection).
    const val SELECT_ROLE = "select_role"

    // Role-based landing points (real dashboards land in Phase 3-5; placeholders for now)
    const val ADMIN_HOME = "admin_home"
    const val PUBLISHER_HOME = "publisher_home"

    // Phase 2 — enrollment (spec §4)
    const val ENROLL_CONGREGATION = "enroll_congregation"
    const val ENROLL_ADMIN = "enroll_admin"
    // "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
    // Enrollment" — one route (Regular Elder/Coordinator Elder/Service
    // Overseer/Secretary, picked via checkboxes) replaces the three separate
    // ENROLL_COORDINATOR_ELDER/ENROLL_SERVICE_OVERSEER/ENROLL_REGULAR_ELDER
    // routes and their matching MANAGE_* routes this app used to have.
    // Route strings themselves are never persisted/exposed outside this
    // running process, so renaming them (rather than keeping the old ones as
    // aliases) breaks nothing.
    const val ENROLL_ELDER = "enroll_elder"
    const val MANAGE_ELDERS = "manage_elders"
    const val ENROLL_PUBLISHER = "enroll_publisher"
    const val CONSOLIDATED_REPORT = "consolidated_report"
    // Admin "Comparative Report": Searching / Return Visit / Bible Study counts, one month against the month before.
    const val COMPARATIVE_REPORT = "comparative_report"
    // Manual Field Service Record: an authorized admin-track user enters a month of field service for a publisher or pioneer.
    const val MANUAL_FIELD_SERVICE = "manual_field_service"
    const val FIELD_SERVICE_REPORT = "field_service_report"
    const val DELETED_RECORDS = "deleted_records"
    const val FIELD_SERVICE_GROUP_REPORT = "field_service_group_report"
    // "MINISTERIAL ACCOUNT" — multiple per congregation allowed.
    const val ENROLL_MINISTERIAL_SERVANT = "enroll_ministerial_servant"
    const val MANAGE_MINISTERIAL_SERVANTS = "manage_ministerial_servants"
    // "Manage Publisher Report" module. [periodMonth] is an optional query
    // arg (epoch millis, first-of-month) — plain navigation (side panel,
    // dashboard tile) omits it and gets the module's own default "This
    // Month" filter; the notification balloon's own Monthly Report item
    // (see NotificationCenterViewModel) uses [manageReportsForMonth] so
    // tapping it opens the exact month that report belongs to, not whatever
    // month happens to be the module's default. `MANAGE_PUBLISHER_REPORTS`
    // itself stays a valid, argument-less route (Navigation Compose treats
    // a query parameter as optional), so every existing plain-navigate call
    // site is unaffected.
    const val MANAGE_PUBLISHER_REPORTS_ROUTE = "manage_publisher_reports?periodMonth={periodMonth}"
    const val MANAGE_PUBLISHER_REPORTS = "manage_publisher_reports"
    fun manageReportsForMonth(periodMonth: Long) = "manage_publisher_reports?periodMonth=$periodMonth"
    // "Announcement Module".
    const val MANAGE_ANNOUNCEMENTS = "manage_announcements"
    const val PUBLISHER_ANNOUNCEMENTS = "publisher_announcements"
    // "Meeting Assignments" module — Midweek Meeting Schedule + Public Talk
    // and Watchtower Study Schedule. One screen/route for both the admin-
    // track enrollment side and the Publisher's own read-only view (see
    // `readOnly` at the call site), same convention as Announcements above.
    const val MEETING_ASSIGNMENTS = "meeting_assignments"
    const val PUBLISHER_MEETING_ASSIGNMENTS = "publisher_meeting_assignments"
    // "Add a Button under Meeting [and Cart] Assignment[:] 'My
    // Assignments'... the publisher can see all the assignments under his
    // name" — a Publisher-only, own-congregation-only cross-cut of every
    // Midweek/Public Talk/Cart Assignment record that names them.
    const val MY_ASSIGNMENTS = "my_assignments"

    // Phase 3
    const val CONTROL_PANEL = "control_panel"
    const val MANAGE_CONGREGATIONS = "manage_congregations"
    const val MANAGE_ADMINS = "manage_admins"
    const val BACKUP_RESTORE = "backup_restore"
    const val USER_LOGS = "user_logs"
    // "Admin Dashboard Menu Reorganization" spec §16-17 — a dedicated,
    // simplified color-wheel + preview + Save screen, reachable from the
    // Control Panel drawer section (every role) and also linked from
    // [SETTINGS] below (same reach [SETTINGS] already had, just moved off
    // that screen instead of duplicated onto it). Reuses SettingsViewModel/
    // ThemePreferenceRepository unchanged — no new storage, no new
    // permission gate (spec §24: preserve exactly who already had access).
    const val THEME_COLOR_SETTINGS = "theme_color_settings"
    const val SESSION_TIMEOUT_SETTING = "session_timeout_setting"

    // Phase 4
    const val MANAGE_PUBLISHERS = "manage_publishers"
    const val MANAGE_GROUPS = "manage_groups"
    // Landing page offering the two Territory Assignment modules; every
    // existing entry point (side panel, etc.) still navigates here.
    const val MANAGE_TERRITORY_ASSIGNMENTS = "manage_territory_assignments"
    // "FS Group Assignment" — the original Territory Assignment dashboard.
    // "Per Publisher Assignment" — list, then the add form.
    // "new" sentinel in {congregationId}/{groupId}/{provinceId} means Add
    // rather than Edit (a brand-new session, nothing picked yet) — see
    // GoPreachNavGraph's own composable() block for this route. A Group's
    // whole multi-municipality territory within one province is edited as
    // one session, so Edit is addressed by (groupId, provinceId), not a
    // single assignment id.
    const val TERRITORY_ASSIGNMENT_WIZARD = "territory_assignment_wizard/{congregationId}/{groupId}/{provinceId}"
    // "Clicking coordinates should open the Territory Map centered on the
    // Publisher's latest location" — [MANAGE_TERRITORIES] (with the literal
    // `{focusLat}`-style placeholders) is the route *pattern*, used only to
    // register the composable; every plain "open Territory Map" entry point
    // (side panel, dashboard tiles) navigates to [MANAGE_TERRITORIES_BASE]
    // instead — the three query args are optional, so the base path alone
    // still matches the pattern and simply omits them. Only Share
    // Location's own "open in Territory Map" action supplies real values,
    // via [territoryMapFocusedOn].
    const val MANAGE_TERRITORIES_BASE = "manage_territories"
    const val MANAGE_TERRITORIES = "$MANAGE_TERRITORIES_BASE?focusLat={focusLat}&focusLng={focusLng}&focusName={focusName}"
    fun territoryMapFocusedOn(lat: Double, lng: Double, name: String): String {
        val encodedName = percentEncode(name)
        return "$MANAGE_TERRITORIES_BASE?focusLat=$lat&focusLng=$lng&focusName=$encodedName"
    }
    // "Group Chat Setting" module — replaces the old Chat Schedule (which was
    // never a real chat, just a calendar-style event CRUD screen reusing
    // Schedule/ScheduleKind.CHAT_SCHEDULE). GROUP_CHAT_SETTING is the list/
    // management entry point (own chats +, for Coordinator Elder/Admin/
    // Super-Admin, a "+ New Group Chat" affordance); GROUP_CHAT_DETAIL opens
    // one group's chat/participants/shared-documents, all in that one screen.
    const val GROUP_CHAT_SETTING = "group_chat_setting"
    const val GROUP_CHAT_DETAIL = "group_chat_detail/{groupChatId}"
    fun groupChatDetail(groupChatId: String) = "group_chat_detail/$groupChatId"
    const val REPORTS = "reports"

    // Phase 5 — Ministry Report App (Publisher context)
    // "Preaching Time Record Module" spec §12 — Pioneer-only.
    const val PREACHING_TIME_RECORD = "preaching_time_record"
    // "My Bible Text Record" module — every Publisher's personal Bible-
    // reference organizer.
    const val MY_BIBLE_TEXT_RECORD = "my_bible_text_record"
    const val MONTHLY_REPORT = "monthly_report"
    // My Planner → Send Report: "My Planner's selected Month/Year controls
    // the initial Monthly Report month" — the same optional-query-arg shape
    // MANAGE_PUBLISHER_REPORTS_ROUTE already uses for the same reason.
    // Every other caller still navigates to the plain MONTHLY_REPORT route
    // and keeps the screen's own default (current month).
    const val MONTHLY_REPORT_ROUTE = "monthly_report?periodMonth={periodMonth}"
    fun monthlyReportForMonth(periodMonth: Long) = "monthly_report?periodMonth=$periodMonth"
    const val EDIT_MONTHLY_REPORT = "edit_monthly_report/{targetPersonId}"
    fun editMonthlyReport(targetPersonId: String) = "edit_monthly_report/$targetPersonId"
    // "Allow the publisher to see all his submitted Report record" — a
    // read-only history, separate from MONTHLY_REPORT's current/previous-
    // month editing form.
    const val MY_SUBMITTED_REPORTS = "my_submitted_reports"

    // "Redesign the Publisher Dashboard" — Searching → Return Visit → Bible
    // Study pipeline (see PipelineStage). Replaces the old separate
    // INTERESTED_PEOPLE/BIBLE_STUDY_RECORD destinations: all three stages are
    // now one PipelineScreen parameterized by which stage it shows.
    const val SEARCHING = "searching"
    const val RETURN_VISIT = "return_visit"
    const val BIBLE_STUDY = "bible_study"
    // My Planner record rows open one specific person's existing detail
    // screen directly (visit history, edit, add visit) via this optional
    // query arg; every other caller keeps navigating to the plain routes
    // above and lands on the list, unchanged.
    const val SEARCHING_ROUTE = "$SEARCHING?personId={personId}"
    fun searchingPerson(personId: String) = "$SEARCHING?personId=$personId"
    const val RETURN_VISIT_ROUTE = "$RETURN_VISIT?personId={personId}"
    const val BIBLE_STUDY_ROUTE = "$BIBLE_STUDY?personId={personId}"
    fun returnVisitPerson(personId: String) = "$RETURN_VISIT?personId=$personId"
    fun bibleStudyPerson(personId: String) = "$BIBLE_STUDY?personId=$personId"
    // Service Overseer's incoming "Forward to Other Congregation" review queue.
    const val FORWARD_REQUESTS = "forward_requests"
    // A Publisher's own incoming "FORWARD TO OTHER PUBLISHER" review queue.
    const val PUBLISHER_FORWARD_REQUESTS = "publisher_forward_requests"
    // "House Holder Assignment" module — the Service Overseer/Admin/Super-
    // Admin's own search-and-assign screen.
    // "Publisher Assignment" — central Admin management of Searching / Return Visit / Bible Study records and their Publisher.
    const val PUBLISHER_ASSIGNMENT = "publisher_assignment"
    // A Publisher's own incoming House Holder Assignment review queue.
    const val INCOMING_HOUSEHOLDER_ASSIGNMENTS = "incoming_householder_assignments"

    // Phase 6
    const val SHARE_LOCATION = "share_location"
    const val CALENDAR = "calendar"
    // "Find Location" — manual-GPS-entry route finder (walking/driving/
    // bicycling/transit), Publisher-context.
    const val FIND_LOCATION = "find_location"

    const val SETTINGS = "settings"

    // User Access Management (Super-Admin account editing + Circuit Overseer/custom users)
    const val ACCOUNT_SETTINGS = "account_settings"
    // "Add Module: Preaching Availability" — spec's own "This will be
    // visible in other publisher account" — a Publisher browsing fellow
    // publishers' schedules within their own congregation, reached from
    // Account Settings.
    const val PUBLISHER_SCHEDULES = "publisher_schedules"
    const val MANAGE_USERS = "manage_users"
    const val ADD_USER = "add_user"
    const val EDIT_USER = "edit_user/{targetPersonId}"
    fun editUser(targetPersonId: String) = "edit_user/$targetPersonId"

    // Account / Credential Management — congregation- and role-scoped
    // username changes + account status for Publishers/Elders/Ministerial
    // Servants/Coordinator Elders/Service Overseers/Admins (spec §1-§5).
    const val ACCOUNT_MANAGEMENT = "account_management"

    // My Planner / Ministry Timer / Reporting upgrade (Phase B onward) —
    // Credit Hour Categories CRUD, same "authorized admin" gating as every
    // other lookup-table management screen.
    const val CREDIT_HOUR_CATEGORIES = "credit_hour_categories"
    // My Planner (spec §16-§27) is embedded directly on the Publisher Main
    // Form (PublisherHomeScreen), synced to the Dashboard's date range —
    // never a separate destination to navigate to.

    // Role-Based Dashboard, Side Panel & Graphical Reports
    const val DASHBOARD_REPORTS = "dashboard_reports"

    // "Contact Record" module — consolidated Publisher/Interested People
    // (Searching/Return Visit/Bible Study)/Coordinator Elder/Service
    // Overseer/Ministerial Servant directory. Super-Admin/Coordinator
    // Elder/Regular Elder only.
    const val CONTACT_RECORD = "contact_record"

    // Super-Admin's own "All Congregations" Interested People view — every
    // Searching/Return Visit/Bible Study record in every congregation, with
    // full Add/Edit/permanent-Delete access (unlike SEARCHING/RETURN_VISIT/
    // BIBLE_STUDY above, which are Publisher-only, own-records routes).

    // Spec §15 — "Elders should be able to see Interested Person information
    // according to their existing Congregation/Group access scope": a
    // read-only browse (see ElderInterestedRecordsScreen) for Admin/
    // Coordinator Elder/Service Overseer/Regular Elder, scoped to their own
    // congregation (or own Group for a Regular Elder) — distinct from
    // [ALL_INTERESTED_RECORDS], which is Super-Admin-only and full read/write
    // across every congregation.
    const val SCOPED_INTERESTED_RECORDS = "scoped_interested_records"

    // "House Holder Visit History" — a read-only, consolidated view over the
    // existing Searching/Return Visit/Bible Study records and their Visit
    // history, for Super-Admin (every authorized congregation) and Publisher
    // (their own congregation) accounts. See HouseholderVisitHistoryScreen's
    // own doc comment.
    const val HOUSEHOLDER_VISIT_HISTORY = "householder_visit_history"
}

/** URL-encodes [s] like `java.net.URLEncoder` (UTF-8; space becomes `+`; letters, digits and `.-*_` are kept). */
private fun percentEncode(s: String): String {
    val out = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        when {
            b >= 0 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_") -> out.append(c)
            b.toInt() == ' '.code -> out.append('+')
            else -> out.append('%').append(b.toUByte().toString(16).uppercase().padStart(2, '0'))
        }
    }
    return out.toString()
}
