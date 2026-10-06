package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * A pin with a short text mark, dropped on the Territory Map by long-pressing
 * a spot (e.g. "Dog at gate", "Ask for Mang Tomas"). Saved online and shown to
 * everyone in the same congregation; only its creator — or an admin-track role
 * with territory access — can remove it.
 *
 * Firestore collection: `mapPins/{id}`
 */
@kotlinx.serialization.Serializable
data class MapPin(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val text: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val createdByPersonId: String = "",
    val createdByName: String = "",
    val createdAt: Long = 0L,
)
