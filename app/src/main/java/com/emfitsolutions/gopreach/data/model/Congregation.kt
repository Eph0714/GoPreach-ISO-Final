package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/** Firestore collection: `congregations/{congregationId}` */
data class Congregation(
    @DocumentId val id: String = "",
    val name: String = "",
    /** "The Address must be replaced with (Province/City, Municipality,
     * Barangay)" — kept only as a derived, human-readable "Barangay,
     * Municipality, Province/City" string (composed by [com.emfitsolutions
     * .gopreach.ui.screens.enrollment.CongregationEnrollmentViewModel]/
     * [com.emfitsolutions.gopreach.ui.screens.congregations
     * .ManageCongregationsScreen]'s edit dialog whenever [province]/
     * [cityMunicipality]/[barangay] are saved) so every existing display
     * that reads this one field — the congregation list row, in particular —
     * keeps working unchanged; no screen lets anyone type it directly
     * anymore, and it is never the source of truth for location filtering.
     */
    val address: String = "",
    /** The three PSGC dropdowns that actually replaced free-text address
     * entry — see [com.emfitsolutions.gopreach.ui.components
     * .PhilippineAddressPicker]. All three are required going forward (every
     * enrollment/edit path validates this), but stay nullable here since a
     * congregation created before this field existed has none recorded. */
    val province: String? = null,
    val cityMunicipality: String? = null,
    val barangay: String? = null,
    /** Unique per spec §4.1. Uniqueness enforced app-side before write (Firestore has
     * no native unique-field constraint) — see CongregationRepository. */
    val code: String = "",
    /** Languages used by this congregation (e.g. "Iloko", "Ibanag") — free-form,
     * not a fixed enum, since congregations aren't limited to a predefined
     * language list. Optional; empty means none recorded yet. */
    val languages: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val createdByPersonId: String = "",
    /** "Admin Record Deletion and Inactive Status" spec — Move to Inactive
     * keeps the record and everything under it, just hides it from normal
     * active lists/reports; Delete Permanently is only offered when nothing
     * still references this congregation (see ManageCongregationsViewModel). */
    val status: RecordStatus = RecordStatus.ACTIVE,
)

/**
 * A group within a congregation, overseen by exactly one Regular Elder
 * (spec §3: "CRUD Groups + assign 1 Elder").
 *
 * Firestore collection: `groups/{groupId}`
 */
data class Group(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val name: String = "",
    /** Legacy single-elder field from before the three-role structure below —
     * kept, unmigrated, so existing data is never silently dropped or
     * overwritten. A Group written by the current app always leaves this null
     * and uses the three fields instead; a Group from before this change may
     * have only this set. See ManageGroupsScreen for how the admin carries an
     * existing assignment over into one of the three roles. */
    val regularElderPersonId: String? = null,
    /** A Group needs exactly one Elder in each of these three roles to be
     * considered fully assigned (spec: "Group Assignment Incomplete —
     * Missing: X"). Each personId here is also mirrored onto that Elder's own
     * RoleAssignment.groupId (see ManageGroupsViewModel.save) — that mirror is
     * what actually drives their Group/Congregation report access. */
    val overseerPersonId: String? = null,
    val servantPersonId: String? = null,
    val assistantPersonId: String? = null,
    val createdAt: Long = 0L,
    /** "Admin Record Deletion and Inactive Status" spec — see [Congregation.status]. */
    val status: RecordStatus = RecordStatus.ACTIVE,
    /** "Same Group = Same Color, Different Group = Different Color... the
     * color must always come from the Group Record assignment and must
     * never be determined randomly or individually for each member" — the
     * single source of truth for every member/record belonging to this
     * Group everywhere it's shown (Territory Map markers, Territory Scope
     * boundary, Group detail view, Group member display, Group Report). A
     * "#RRGGBB" string, assigned automatically from a curated palette when
     * the Group is first created (see ManageGroupsScreen's GroupDialog) and
     * editable by an admin afterward; null only for a Group saved before
     * this field existed, in which case
     * [com.emfitsolutions.gopreach.ui.screens.territories.TerritoryMapScreen]
     * falls back to its legacy generated color so old data keeps working
     * until that Group is next edited and picks up a real one. */
    val color: String? = null,
) {
    /** Which of the three required roles still need an Elder — empty means fully assigned. */
    fun missingRoles(): List<RegularElderRole> = buildList {
        if (overseerPersonId == null) add(RegularElderRole.GROUP_OVERSEER)
        if (servantPersonId == null) add(RegularElderRole.GROUP_SERVANT)
        if (assistantPersonId == null) add(RegularElderRole.GROUP_ASSISTANT)
    }

    val isComplete: Boolean get() = missingRoles().isEmpty()

    fun personIdFor(role: RegularElderRole): String? = when (role) {
        RegularElderRole.GROUP_OVERSEER -> overseerPersonId
        RegularElderRole.GROUP_SERVANT -> servantPersonId
        RegularElderRole.GROUP_ASSISTANT -> assistantPersonId
    }
}

/**
 * Lookup table for Regular Elder "specific title" (spec §3), fully CRUD-able by
 * admins so congregations can add titles without a code change.
 *
 * Firestore collection: `elderTitles/{elderTitleId}`
 */
data class ElderTitleEntity(
    @DocumentId val id: String = "",
    val titleName: String = "",
    val active: Boolean = true,
)

/** Firestore collection: `territories/{territoryId}` */
data class Territory(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val name: String = "",
    val description: String? = null,
    val boundaryNotes: String? = null,
    val assignedGroupId: String? = null,
    val createdAt: Long = 0L,
)

/**
 * Territory Assignment module — a Group's claim over every barangay of one
 * municipality/city currently checked in a matching [TerritoryAssignmentBarangay]
 * doc pointing at this id. The barangay list itself is NOT stored here (see
 * that model's own doc comment for why) — this header is just Group+
 * Municipality metadata. [provinceId]/[muncityId] are the stable PSGC row ids
 * from the bundled `psgc.db` ([com.emfitsolutions.gopreach.data.local.psgc
 * .PsgcEntities]), not the official PSGC code string; [provinceName]/
 * [muncityName] are denormalized at save time (same convention as
 * [Congregation.province]/[cityMunicipality]) so the dashboard never needs a
 * PSGC DB join to render a row.
 *
 * No [RecordStatus] field, unlike [Congregation]/[Group] — see
 * [com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository]
 * .removeAssignment's doc comment for why an "Inactive" state would be wrong
 * here (it would keep blocking every barangay it covers from being claimed
 * elsewhere). Remove is always a hard delete.
 *
 * Firestore collection: `territoryAssignments/{assignmentId}`
 */
data class TerritoryAssignment(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val groupId: String = "",
    val provinceId: Int = 0,
    val provinceName: String = "",
    val muncityId: Int = 0,
    val muncityName: String = "",
    val createdAt: Long = 0L,
    val createdByPersonId: String = "",
    val updatedAt: Long = 0L,
    val updatedByPersonId: String? = null,
)

/**
 * One claimed barangay for a [TerritoryAssignment]. [id] is always
 * `"${congregationId}_${barangayId}"` — that deterministic id IS the
 * uniqueness mechanism: "a barangay belongs to at most one FS Group per
 * congregation" becomes "this exact document id can be created at most
 * once," which [com.emfitsolutions.gopreach.data.repository
 * .TerritoryAssignmentRepository] enforces with a Firestore transaction
 * (get-then-set, never update the keyed fields) rather than a client-side
 * pre-check alone. Duplicates across *different* congregations are fine by
 * construction — a different congregationId is a different document id
 * entirely. [groupName] is denormalized so a duplicate-tap conflict ("X is
 * already assigned to Y") can be shown from this one document, no second read.
 *
 * Firestore collection: `territoryAssignmentBarangays/{congregationId}_{barangayId}`
 */
data class TerritoryAssignmentBarangay(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val assignmentId: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val provinceId: Int = 0,
    val muncityId: Int = 0,
    val muncityName: String = "",
    val barangayId: Int = 0,
    val barangayName: String = "",
    val createdAt: Long = 0L,
    val createdByPersonId: String = "",
)
