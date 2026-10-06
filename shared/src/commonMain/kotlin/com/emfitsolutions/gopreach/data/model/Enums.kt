package com.emfitsolutions.gopreach.data.model

/** Administration track — hierarchical, congregation/group-scoped (spec §2.1). */
@kotlinx.serialization.Serializable
enum class AdminRole {
    SUPER_ADMIN,
    ADMIN_PER_CONGREGATION,
    COORDINATOR_ELDER,
    /** One per congregation (spec: "there must be 1 Service Overseer in
     * every congregation") — enforced as "at most one active" at enrollment
     * time (see ServiceOverseerEnrollmentViewModel), not a hard app-wide
     * invariant, since an admin may not have created one yet. Sees the
     * Consolidated Monthly Report for their own congregation; Coordinator
     * Elder/Admin see the same for their congregation, Super-Admin for every
     * congregation (see ConsolidatedReportViewModel). */
    SERVICE_OVERSEER,
    /** "Consolidate Elder, Coordinator Elder, Service Overseer and Secretary
     * Enrollment" — a brand-new role, enrolled the same way as
     * [SERVICE_OVERSEER] (through `Enrollment → Elders`, no separate module),
     * and deliberately given **the exact same access as [SERVICE_OVERSEER]**
     * everywhere in the app: every permission/UI-gating check that lists
     * [SERVICE_OVERSEER] also lists this role (grep `AdminRole.SECRETARY` to
     * find every one of those sites — there is no single shared "Service
     * Overseer permission set" constant this app's existing role-checking
     * style could hang off of, so each site was updated individually rather
     * than introduced as a new abstraction). Unlike [SERVICE_OVERSEER], not
     * capped at one active per congregation — nothing in the spec asked for
     * that, and inventing a restriction the source spec never stated would
     * violate "the system must not invent new restrictions." */
    SECRETARY,
    REGULAR_ELDER,
    /** Not an Elder — a distinct appointed position (spec: "MINISTERIAL
     * ACCOUNT"), enrollable by Super-Admin, Admin (own congregation), and
     * Coordinator Elder, unlike multiple per congregation are allowed (no
     * uniqueness constraint, unlike [SERVICE_OVERSEER]'s "at most one").
     * Its own "Select Role" checkboxes reuse [RegularElderRole.GROUP_SERVANT]/
     * [RegularElderRole.GROUP_ASSISTANT] via an additional, simultaneous
     * `Admin(REGULAR_ELDER)` RoleAssignment — the same "extra role on top of
     * the primary one" pattern already used for Coordinator Elder/Service
     * Overseer's own Group Overseer checkbox (see
     * MinisterialServantEnrollmentViewModel) — since a Group's Servant/
     * Assistant slot is filled the same way regardless of whether the person
     * is technically an Elder or a Ministerial Servant. */
    MINISTERIAL_SERVANT,

    /**
     * A restricted, externally-facing role (e.g. a real Circuit Overseer, or any
     * other one-off account a Super-Admin needs to create) that carries **no**
     * implicit permissions of its own — unlike the four roles above, whose access
     * is a fixed, built-in set. Every [AdminRole.CIRCUIT_OVERSEER] account's
     * actual capabilities live entirely in its [UserAccessGrant]: what it may do
     * ([UserAccessGrant.permissions]) and where ([UserAccessGrant.scopeType] +
     * scope lists). A RoleAssignment of this role with no matching grant grants
     * nothing at all — see [com.emfitsolutions.gopreach.domain.PermissionChecker.hasPermission].
     */
    CIRCUIT_OVERSEER,
}

/** Human-readable name for [AdminRole] — "Multiple Role Login Detection &
 * Role Selection" spec's own dropdown/example wording ("Admin", "Coordinator
 * Elder", ...), the single place every role-selection/display surface reads
 * this from rather than each re-deriving its own string. */
fun AdminRole.displayLabel(): String = when (this) {
    AdminRole.SUPER_ADMIN -> "Super Admin"
    AdminRole.ADMIN_PER_CONGREGATION -> "Admin"
    AdminRole.COORDINATOR_ELDER -> "Coordinator Elder"
    AdminRole.SERVICE_OVERSEER -> "Service Overseer"
    AdminRole.SECRETARY -> "Secretary"
    AdminRole.REGULAR_ELDER -> "Regular Elder"
    AdminRole.MINISTERIAL_SERVANT -> "Ministerial Servant"
    AdminRole.CIRCUIT_OVERSEER -> "Circuit Overseer"
}

/**
 * WHAT a restricted ([AdminRole.CIRCUIT_OVERSEER]) user may do — see
 * [UserAccessGrant]. Deliberately a flat enum rather than hard-coded booleans
 * scattered through the app, so a future permission is one new case plus
 * whatever screen checks it — not a schema change (spec §12/§15).
 */
@kotlinx.serialization.Serializable
enum class Permission {
    VIEW_CONGREGATIONS, ADD_CONGREGATIONS, EDIT_CONGREGATIONS, DELETE_CONGREGATIONS,
    VIEW_ELDERS, MANAGE_ELDERS,
    VIEW_GROUPS, MANAGE_GROUPS,
    VIEW_TERRITORY_ASSIGNMENTS, MANAGE_TERRITORY_ASSIGNMENTS,
    VIEW_PUBLISHERS, MANAGE_PUBLISHERS,
    VIEW_PUBLISHER_REPORTS, VIEW_GROUP_REPORTS, VIEW_CONGREGATION_REPORTS,
    PRINT_REPORTS, EXPORT_REPORTS,
    MANAGE_USERS,
}

/** WHERE a [Permission] applies for a restricted user (spec §6) — kept entirely
 * separate from [Permission] itself so "can view reports" and "for which
 * congregations" are independently configurable. */
@kotlinx.serialization.Serializable
enum class ScopeType {
    ALL_CONGREGATIONS,
    SELECTED_CONGREGATIONS,
    SELECTED_GROUPS,
}

/** Account-level (not role-level) lifecycle switch (spec §9) — distinct from
 * [RoleAssignmentStatus], which tracks one specific role/report-access grant.
 * A deactivated/suspended account can never sign in, full stop, regardless of
 * how many active RoleAssignments it still holds; nothing about its historical
 * records is touched. */
@kotlinx.serialization.Serializable
enum class AccountStatus { ACTIVE, INACTIVE, SUSPENDED }

/** Publisher track — categories, not a hierarchy (spec §2.2). */
@kotlinx.serialization.Serializable
enum class PublisherCategory {
    REGULAR_PIONEER,
    /** "Add Special Pioneer publisher status category" — functionally
     * equivalent to [REGULAR_PIONEER] everywhere that category drives
     * eligibility/permissions/calculations (report submission, Bible Study/
     * preaching-hour calculation, Preaching Time Records, notifications,
     * ...): every such call site was updated to check both together (see
     * each one's own doc comment/history for why it, specifically, treats
     * the two as one group). Kept as its own distinct enum constant, not an
     * alias, purely so the category still *displays* and *filters* as its
     * own value (spec: "keep their category values distinct so the system
     * can correctly display and report REGULAR PIONEER versus SPECIAL
     * PIONEER") — never silently merged into Regular Pioneer's own display/
     * filter identity, only its behavior. */
    SPECIAL_PIONEER,
    AUXILIARY_PIONEER,
    REGULAR_PUBLISHER,
    UNBAPTIZED_PUBLISHER,
    /** "CREATING PUBLISHER" spec's STATUS list — not regular about reporting
     * (some months with no report during a 6-month window); the spec's
     * auto-status note assigns this automatically once that check is wired
     * up, same as [INACTIVE_PUBLISHER]'s "6 months consecutive" trigger —
     * also manually selectable here at enrollment/edit in the meantime. */
    IRREGULAR_PUBLISHER,
    INACTIVE_PUBLISHER,
    /** "CREATING PUBLISHER" spec's STATUS list — a Publisher under reproof;
     * distinct from [REMOVED_PUBLISHER], which additionally blocks the
     * account from signing in at all (see ManagePublishersViewModel). */
    REPROOF_PUBLISHER,
    REMOVED_PUBLISHER,
}

/** The category as shown to people. A plain Publisher (stored as REGULAR_PUBLISHER) is just "PUBLISHER"; the
 * stored value is unchanged, so no data needs migrating. */
val PublisherCategory.displayName: String
    get() = if (this == PublisherCategory.REGULAR_PUBLISHER) "PUBLISHER" else name.replace('_', ' ')

/**
 * A [RoleAssignment.roleType] is either one of the [AdminRole]s or "the person is a
 * publisher in this [PublisherCategory]" — a Person can hold several RoleAssignments
 * at once (spec §3: a Coordinator Elder who is also a Regular Pioneer).
 */
@kotlinx.serialization.Serializable
sealed class RoleType {
    data class Admin(val role: AdminRole) : RoleType()
    data class Publisher(val category: PublisherCategory) : RoleType()

    companion object {
        private const val ADMIN_PREFIX = "ADMIN:"
        private const val PUBLISHER_PREFIX = "PUBLISHER:"

        /** Firestore/Room store role type as a flat string; this round-trips it. */
        fun serialize(type: RoleType): String = when (type) {
            is Admin -> "$ADMIN_PREFIX${type.role.name}"
            is Publisher -> "$PUBLISHER_PREFIX${type.category.name}"
        }

        fun deserialize(raw: String): RoleType = when {
            raw.startsWith(ADMIN_PREFIX) -> Admin(AdminRole.valueOf(raw.removePrefix(ADMIN_PREFIX)))
            raw.startsWith(PUBLISHER_PREFIX) -> Publisher(PublisherCategory.valueOf(raw.removePrefix(PUBLISHER_PREFIX)))
            else -> error("Unknown RoleType: $raw")
        }
    }
}

@kotlinx.serialization.Serializable
enum class RoleAssignmentStatus { ACTIVE, INACTIVE }

/**
 * Lifecycle status for a master record that isn't Person/RoleAssignment-based
 * (Congregation, Group, InterestedPerson) — the "Admin Record Deletion and
 * Inactive Status" spec's "Move to Inactive" outcome for those record types.
 * Person-linked records (Admins, Elders, Publisher categories, restricted
 * Users) already had their own equivalent before this spec
 * ([RoleAssignmentStatus], [PublisherCategory.REMOVED_PUBLISHER], and
 * [AccountStatus] respectively) and keep using those, rather than gaining a
 * second, redundant status field.
 */
@kotlinx.serialization.Serializable
enum class RecordStatus { ACTIVE, INACTIVE }

/** "Preaching Availability" module — a Publisher's own self-reported general
 * availability, in [Person.preachingAvailableDays] as plain [name] strings
 * (never this enum type directly — see that field's own doc comment on why),
 * so it can be shown/used when a Service Overseer/Admin/Super-Admin is
 * choosing who to assign a House Holder Assignment to, and on this
 * Publisher's own profile for any other signed-in account to see. */
@kotlinx.serialization.Serializable
enum class PreachingDay(val label: String, val shortLabel: String) {
    MONDAY("Monday", "Mon"),
    TUESDAY("Tuesday", "Tue"),
    WEDNESDAY("Wednesday", "Wed"),
    THURSDAY("Thursday", "Thu"),
    FRIDAY("Friday", "Fri"),
    SATURDAY("Saturday", "Sat"),
    SUNDAY("Sunday", "Sun"),
}

/** A Regular Elder's structural role within their assigned Group — distinct from
 * [ElderTitleEntity] (a free-form, admin-editable "specific title" label): this is
 * a fixed 3-way split that drives Group-completeness validation and which of a
 * Group's three Regular Elder slots this person fills. Every Group needs exactly
 * one of each to be considered fully assigned. */
@kotlinx.serialization.Serializable
enum class RegularElderRole { GROUP_OVERSEER, GROUP_SERVANT, GROUP_ASSISTANT }

@kotlinx.serialization.Serializable
enum class Gender { MALE, FEMALE }

/** Outcome of one preaching visit logged against a Return Visit or Bible
 * Study pipeline record ("Manage Returned Visit/Bible Study Module" spec's
 * Status dropdown: NH/B/CA/MO/NT). Replaces the old, differently-scoped
 * `HouseholderStatus` — this app's only user of that enum was [Visit.outcome]
 * itself, so this is a rename-in-place to the spec's exact vocabulary, not a
 * parallel field. */
@kotlinx.serialization.Serializable
enum class VisitOutcome {
    /** NH */ NOT_AT_HOME,
    /** B */ BUSY,
    /** CA */ CALL_AGAIN,
    /** MO */ MOVED_OUT,
    /** NT — ready for the next study topic. */ NEXT_TOPIC,
}

/** Where one [InterestedPerson] currently sits in the Searching → Return
 * Visit → Bible Study pipeline (spec's three-module redesign). Distinct from
 * [RecordStatus] (active/inactive soft-delete) — a record can be inactive at
 * any stage. Moves one stage at a time in either direction — forward
 * (Searching → Return Visit → Bible Study) or, per "Add Reverse Status
 * Movement," backward (Bible Study → Return Visit → Searching Interested
 * Person) — from the Searching/Return Visit/Bible Study module's own screen
 * (see PipelineViewModel.advanceStage, and that same screen's own
 * `nextStage()`/`previousStage()`); never a direct Searching↔Bible Study
 * jump in either direction. */
@kotlinx.serialization.Serializable
enum class PipelineStage { SEARCHING, RETURN_VISIT, BIBLE_STUDY }

/** Lifecycle of one cross-congregation [ForwardRequest] ("Forward to Other
 * Congregation" spec flow) — same enum also backs the same-congregation
 * [PublisherForwardRequest] ("Forward to Other Publisher") flow.
 * [InterestedPerson.pendingForwardRequestId]/[InterestedPerson
 * .pendingPublisherForwardRequestId] point at the most recent one for that
 * person (if any), regardless of which of these states it's currently in —
 * that's how the sending publisher's own screen shows a live "Forward
 * status: ..." without a separate lookup. [CANCELLED] — "add a cancel
 * request... applicable if the process is not yet accepted by the receiving
 * user" — is only ever set by the *sending* publisher themselves, and only
 * while still [PENDING]; once a request has moved to [ACCEPTED]/[DECLINED]
 * the receiving side has already acted and there's nothing left to cancel. */
@kotlinx.serialization.Serializable
enum class ForwardRequestStatus { PENDING, ACCEPTED, DECLINED, CANCELLED }

/** Local-only sync state for offline-first CRUD (spec §6.5), stored alongside cached rows. */
@kotlinx.serialization.Serializable
enum class SyncState { SYNCED, PENDING, FAILED }

@kotlinx.serialization.Serializable
enum class SyncOperationType { CREATE, UPDATE, DELETE }
