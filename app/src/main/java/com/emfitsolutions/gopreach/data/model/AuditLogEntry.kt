package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * "User logs" (spec §3 permission matrix: Super-Admin can view/export/delete;
 * Admin/Coordinator Elder can view/export their own congregation's; Regular
 * Elder has no access). [congregationId] is null for actions that aren't
 * congregation-scoped (e.g. a Super-Admin creating a new congregation) — those
 * only ever show up in the Super-Admin's view.
 *
 * Firestore collection: `auditLog/{entryId}`
 */
data class AuditLogEntry(
    @DocumentId val id: String = "",
    val actorPersonId: String = "",
    /** Short machine-readable action code, e.g. "SIGN_IN", "ENROLL_ADMIN",
     * "CREATE_CONGREGATION", "UPLOAD_LOGO", "RESTORE_BACKUP". */
    val action: String = "",
    val targetType: String? = null,
    val targetId: String? = null,
    val congregationId: String? = null,
    val timestamp: Long = 0L,

    /** Human-readable "what changed" for account/permission/scope actions (spec's
     * User Access Management §11) — e.g. "permissions: [VIEW_GROUPS] -> [VIEW_GROUPS,
     * MANAGE_GROUPS]". Optional and null for the simpler pre-existing action codes
     * that don't carry a before/after value. */
    val details: String? = null,
)
