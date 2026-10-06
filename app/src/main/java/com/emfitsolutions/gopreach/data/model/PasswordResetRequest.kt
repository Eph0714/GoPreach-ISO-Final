package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

enum class PasswordResetRequestStatus { PENDING, RESOLVED }

/**
 * Spec §4.5: lost temp/regular credentials are recovered by asking the role that
 * enrolled the person, not via an emailed reset link (email is optional on
 * [Person]). This doc is the in-app notification that role sees and resolves by
 * issuing fresh temporary credentials.
 *
 * Firestore collection: `passwordResetRequests/{requestId}`
 */
data class PasswordResetRequest(
    @DocumentId val id: String = "",
    val requestedUsername: String = "",
    /** Resolved at request time when the username matches a known Person; null if
     * the username couldn't be found (the enrolling role still sees the raw text). */
    val personId: String? = null,
    /** Who should see/action this — the personId that enrolled the requester. */
    val targetPersonId: String? = null,
    val status: PasswordResetRequestStatus = PasswordResetRequestStatus.PENDING,
    val requestedAt: Long = 0L,
    val resolvedAt: Long? = null,
)
