package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * "Allow the publisher to save their 'Find Location'" — a coordinate the
 * publisher looked up in [com.emfitsolutions.gopreach.ui.screens.findlocation
 * .FindLocationScreen] and chose to keep for next time, with a free-text
 * remark identifying what/where it is (spec example: "House of Emilio
 * Aguinaldo"). Own-publisher-only, same as a Personal Note on the Calendar —
 * never shared with anyone else.
 *
 * Firestore collection: `savedLocations/{id}`
 */
data class SavedLocation(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val remarks: String = "",
    val createdAt: Long = 0L,
)
