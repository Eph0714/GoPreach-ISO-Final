package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * Single global settings doc. [logoUrl] is the Super-Admin-customizable app logo
 * (spec §1: "Application logo must be customizable by the Super Admin
 * (upload/replace via Control Panel)") — null until one's been uploaded, in which
 * case the UI falls back to the built-in wordmark.
 *
 * Firestore collection: `appSettings/{id}` — exactly one document, id [GLOBAL_ID].
 */
data class AppSettings(
    @DocumentId val id: String = GLOBAL_ID,
    val logoUrl: String? = null,
    /** "Session Timeout Setting" — log everyone out after [sessionTimeoutMinutes]
     * of no interaction while [sessionTimeoutEnabled]. App-wide (one value for
     * every user), editable by Super-Admin/Admins/Elders. */
    val sessionTimeoutEnabled: Boolean = true,
    val sessionTimeoutMinutes: Int = DEFAULT_SESSION_TIMEOUT_MINUTES,
    /** "Automatically Permanently Delete Deleted Records" — off by default: deleted records then stay in Deleted
     * Records until someone restores or permanently deletes them. */
    val trashAutoDeleteEnabled: Boolean = false,
    val trashRetentionDays: Int = DEFAULT_TRASH_RETENTION_DAYS,
    val updatedAt: Long = 0L,
    val updatedByPersonId: String? = null,
) {
    companion object {
        const val GLOBAL_ID = "global"
        const val DEFAULT_SESSION_TIMEOUT_MINUTES = 5
        const val MIN_SESSION_TIMEOUT_MINUTES = 1
        const val MAX_SESSION_TIMEOUT_MINUTES = 120
        const val DEFAULT_TRASH_RETENTION_DAYS = 30
        val TRASH_RETENTION_OPTIONS = listOf(7, 14, 30, 60, 90, 180, 365)
    }
}
