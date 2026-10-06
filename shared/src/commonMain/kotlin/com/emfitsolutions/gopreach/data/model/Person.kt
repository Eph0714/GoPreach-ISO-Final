package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId
import com.emfitsolutions.gopreach.platform.PropertyName

/**
 * Identity + login record — created once per human being, independent of whatever
 * role(s) they hold. See BUILD_PLAN Phase 1 / spec §3 recommendation: role, scope,
 * and permissions live on [RoleAssignment], never duplicated here.
 *
 * Firestore collection: `people/{personId}`
 */
data class Person(
    @DocumentId val id: String = "",
    val lastName: String = "",
    val firstName: String = "",
    val middleInitial: String? = null,
    val extensionName: String? = null,
    val address: String = "",
    /** "Add a dropdown for City, Municipalities, Town Barangay" (Enrolling)
     * — same PSGC fields, same "additive on top of [address], name-only,
     * possibly auto-filled from [gpsLat]/[gpsLng]" reasoning as
     * [InterestedPerson.province]'s own doc comment. */
    val province: String? = null,
    val cityMunicipality: String? = null,
    val barangay: String? = null,
    val gender: Gender? = null,
    val contact: String = "",
    val contactPerson: String? = null,
    val contactPersonNumber: String? = null,
    /** First-time Publisher setup: when the Publisher confirmed their details (after choosing their own
     * username and password). Until they then sign in again with those credentials, [isTemporaryCredential]
     * stays true, so setup is not finished by confirming alone. */
    val setupConfirmedAt: Long? = null,
    /** When the Publisher proved the new credentials by signing in again — first-time setup finished. */
    val setupCompletedAt: Long? = null,
    /** Free-text note about this person, entered when enrolling a Publisher (and editable afterwards). */
    val remarks: String? = null,
    /** Optional. Epoch millis at UTC midnight of the chosen date (what the date picker returns). The age is never stored — see [com.emfitsolutions.gopreach.domain.PersonDates]. */
    val birthdate: Long? = null,
    /** Optional, same encoding as [birthdate]. */
    val baptismalDate: Long? = null,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val email: String? = null,

    /** The profile-menu avatar shown at the top-right of the Admin/Publisher
     * Main Form for every role (Super-Admin, Admin, Coordinator/Regular
     * Elder, Publisher, ...) — uploaded to Firebase Storage at
     * `people/{personId}/profile`, same "no new dependency" pattern
     * [AnnouncementRepository]'s own image upload already uses. `null`
     * means never uploaded — the avatar falls back to a blank icon. */
    val profileImageUrl: String? = null,

    // Login — attaches to the Person, not to any one role (spec §3).
    val username: String = "",
    // Explicit @PropertyName: Firestore's Kotlin-bean reflection mishandles
    // boolean properties already prefixed with "is" (it derives the wrong
    // getter/field name and silently drops the value on toObject()), so this
    // pins the actual Firestore field name rather than relying on inference.
    @get:PropertyName("isTemporaryCredential")
    val isTemporaryCredential: Boolean = false,
    /** The plaintext temp password, kept only while [isTemporaryCredential] is
     * true so an admin can look it up again (e.g. the enrollment share link
     * got lost) — cleared the moment the person completes their forced
     * password change. Firebase Auth itself never stores this; it only ever
     * lives here, transiently, for that lookup window. */
    val temporaryPassword: String? = null,

    val createdAt: Long = 0L,
    val createdByPersonId: String? = null,

    /** Account-level lifecycle switch (spec §9) — checked at sign-in time
     * ([com.emfitsolutions.gopreach.data.repository.AuthRepository.signIn]);
     * an INACTIVE/SUSPENDED account can never sign in, independent of whatever
     * [RoleAssignment]s it still holds. */
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,

    /**
     * Denormalized copy of "does this person hold an active
     * [AdminRole.SUPER_ADMIN] RoleAssignment" — kept only so Firestore security
     * rules can check it with a single `get()` on this known-path document
     * instead of an unsupported cross-collection query (see firestore.rules'
     * file-level note). The [RoleAssignment] row is still the real source of
     * truth for every in-app check ([com.emfitsolutions.gopreach.domain.PermissionChecker]
     * never reads this field) — this flag only has to stay correct for rules
     * enforcement to work, and today it's set exactly once, by hand, per
     * SETUP.md's Super-Admin bootstrap steps (there is no in-app "create another
     * Super-Admin" flow to keep in sync).
     */
    @get:PropertyName("isSuperAdmin")
    val isSuperAdmin: Boolean = false,

    /**
     * Denormalized copy of this session's currently-active
     * [RoleAssignment.congregationId]/[AdminRole] (as its `name`), kept in
     * sync by [com.emfitsolutions.gopreach.domain.UserSession] every time
     * the signed-in account's active role changes (login, or picking a role
     * on [com.emfitsolutions.gopreach.ui.screens.login.SelectRoleScreen]).
     * Same trade-off as [isSuperAdmin] above: exists only so Group Chat
     * Firestore/Storage rules can check "which congregation/role is this
     * caller currently acting as" with a single `get()` on this known-path
     * document — [com.emfitsolutions.gopreach.domain.PermissionChecker]
     * never reads these, [com.emfitsolutions.gopreach.domain.SessionState
     * .activeRoleAssignment] is still the real in-app source of truth.
     * [activeAdminRole] is null for a Publisher-only active role (Publisher
     * access to Group Chats is participant-list based, not congregation-role
     * based — see firestore.rules), but [activeCongregationId] IS still set
     * for one — the Territory Congregation restriction on Return Visits/
     * Bible Studies/Visit History (spec "Territory Map: Congregation-
     * Restricted Return Visit Access") needs exactly this, a Publisher's own
     * enrolled congregation, to enforce server-side.
     */
    val activeCongregationId: String? = null,
    val activeAdminRole: String? = null,

    /** "Preaching Availability" module — which days of the week this
     * Publisher has said they're generally available to preach (spec's own
     * checkbox list, Monday-Sunday), stored as plain [PreachingDay.name]
     * strings rather than the enum itself — every enum field elsewhere in
     * this codebase is a single value, never inside a `List`, so this
     * deliberately doesn't become the first `List<Enum>` Firestore mapping
     * this app relies on. Empty means never set (not "available every
     * day") — a Service Overseer/Admin/Super-Admin choosing who to assign
     * a House Holder Assignment to sees this as-is, blank included, never
     * a guessed default. Lives directly on the shared [Person] record (not
     * a private per-viewer setting), so it can be surfaced to another
     * account that already reads this Person document (spec's own "This
     * will be visible in other publisher account") — but only ever *within
     * this Publisher's own congregation* (spec: "The publisher schedule
     * will be visible only within their congregation"), never a cross-
     * congregation directory. Every screen that actually shows this field
     * today (see [com.emfitsolutions.gopreach.ui.screens.householderassignment
     * .HouseholderAssignmentViewModel.assignablePublishers]) already narrows
     * to one specific congregation first, so that scoping holds by
     * construction — this field itself carries no congregation restriction
     * of its own, so any *new* screen surfacing it must apply the same
     * same-congregation filter rather than reading it off an unscoped
     * Person list. */
    val preachingAvailableDays: List<String> = emptyList(),
    /** Free text — "preferred schedule, limitations, or other relevant
     * notes" (spec's own wording). `null` means never set. */
    val preachingAvailabilityRemarks: String? = null,
) {
    /** The name in the order the user picked in Settings ("Last name first" / "First name first"). */
    val fullName: String
        get() = com.emfitsolutions.gopreach.domain.formatPersonName(firstName, middleInitial, lastName, extensionName)
}
