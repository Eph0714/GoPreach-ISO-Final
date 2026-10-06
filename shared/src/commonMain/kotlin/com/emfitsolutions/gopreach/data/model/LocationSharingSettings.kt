package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * "SHARE LOCATION SETTINGS" — one per congregation, configurable by
 * Super-Admin (any), Admin/Service Overseer/Coordinator Elder/Regular Elder
 * (own congregation only). Governs how long a Publisher's "Share My
 * Location" toggle stays on before automatically stopping, and how accurate
 * a GPS fix must be before it's actually published (spec's example: "30
 * mins" / "5 mtrs").
 *
 * Firestore collection: `locationSharingSettings/{congregationId}`
 */
data class LocationSharingSettings(
    @DocumentId val congregationId: String = "",
    val sharingDurationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val accuracyRadiusMeters: Int = DEFAULT_ACCURACY_METERS,
    val updatedByPersonId: String? = null,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_DURATION_MINUTES = 30
        // Was 5 (the spec's own illustrative example), then 20 — still too
        // tight: confirmed on a real device (dumpsys location) that normal
        // phone GPS routinely reports 21-33m even with a real satellite fix,
        // not just indoors, so 20m left Share Location silently publishing
        // nothing and stuck on "Acquiring..." forever for perfectly working
        // GPS. 50m still rejects a pure WiFi/cell-only fix (commonly 100m+)
        // but reliably admits a genuine GPS fix; a congregation that wants
        // tighter precision can still set it explicitly.
        const val DEFAULT_ACCURACY_METERS = 50

        fun defaultsFor(congregationId: String) = LocationSharingSettings(congregationId = congregationId)
    }
}
