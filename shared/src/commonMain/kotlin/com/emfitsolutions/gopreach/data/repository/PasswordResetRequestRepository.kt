package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.PasswordResetRequest
import com.emfitsolutions.gopreach.data.model.PasswordResetRequestStatus
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.flow.Flow

private const val COLLECTION = "passwordResetRequests"

/**
 * "Forgot Password?" asks the person in charge for new sign-in details (the request is made without a login). The Super Admin and the person who enrolled the requester see the
 * pending requests in Account Management and mark each as done once they have set a new temporary password. Same collection and rules as the website.
 */
class PasswordResetRequestRepository(private val offline: OfflineFirestoreRepository) {
    fun observeAll(): Flow<List<PasswordResetRequest>> = offline.observeCollection(COLLECTION)

    /** Marks the request as done (kept, not deleted, so the history stays). */
    suspend fun resolve(request: PasswordResetRequest) {
        if (request.id.isBlank()) return
        offline.save(COLLECTION, request.id, request.copy(status = PasswordResetRequestStatus.RESOLVED, resolvedAt = nowMillis()))
    }
}
