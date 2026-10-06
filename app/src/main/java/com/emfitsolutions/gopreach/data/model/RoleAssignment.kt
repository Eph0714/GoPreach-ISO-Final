package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * One active (or historical) role held by a [Person]. A person can carry several of
 * these at once — e.g. a Coordinator Elder RoleAssignment plus a separate Regular
 * Pioneer RoleAssignment for the same personId (spec §3).
 *
 * Permission checks are always "does this Person have an ACTIVE RoleAssignment
 * matching X in scope Y" — never a lookup on the Person record itself.
 *
 * Firestore collection: `roleAssignments/{roleAssignmentId}`
 */
data class RoleAssignment(
    @DocumentId val id: String = "",
    val personId: String = "",

    /** Serialized [RoleType] — an AdminRole or a PublisherCategory. */
    val roleType: String = "",

    val congregationId: String? = null,
    val groupId: String? = null,

    /** Only meaningful for AdminRole.REGULAR_ELDER — id into [ElderTitleEntity]. */
    val elderTitleId: String? = null,
    /** Only meaningful for AdminRole.REGULAR_ELDER — which of a Group's three
     * required Elder slots this person fills. Kept alongside [groupId], which is
     * what actually drives this Elder's own Group/Congregation report access
     * (see [com.emfitsolutions.gopreach.domain.PermissionChecker]) — [Group]'s
     * overseer/servant/assistant fields are the source of truth for *who* fills a
     * slot, and assigning them there is what sets this RoleAssignment's groupId. */
    val regularElderRole: RegularElderRole? = null,

    val status: RoleAssignmentStatus = RoleAssignmentStatus.ACTIVE,
    val dateAssigned: Long = 0L,

    // Audit trail (spec §3: "who assigned it, when, by whom it can be edited").
    val assignedByPersonId: String = "",
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
) {
    fun resolvedRoleType(): RoleType = RoleType.deserialize(roleType)

    /** Same as [resolvedRoleType] but never throws — returns null for any
     * [roleType] string [RoleType.deserialize] can't parse (a blank/default
     * value, an old enum name from before a rename, or any other corrupt
     * data). Use this instead of [resolvedRoleType] anywhere that runs
     * unconditionally on *every* signed-in session's own assignments —
     * e.g. [com.emfitsolutions.gopreach.domain.PermissionChecker]'s
     * role-resolution functions, which fire immediately after every login,
     * before the Main Form even renders. One malformed RoleAssignment used
     * to crash the whole app the instant those functions ran; this lets it
     * be silently skipped instead, exactly like it holds no such role. */
    fun resolvedRoleTypeOrNull(): RoleType? = runCatching { RoleType.deserialize(roleType) }.getOrNull()
}
