package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * Publishers Assignment: one assignment the Overseer, Servant or Assistant of an FS Group gave to a publisher of THEIR group. The group is read from the officer's own slot, never
 * chosen; the server refuses a save for another group's publisher and refuses to change the group, the publisher or who assigned it afterwards. [history] only grows.
 *
 * Firestore collection: `publisherAssignments/{id}`. The website writes and reads the same documents.
 */
@kotlinx.serialization.Serializable
data class PublisherAssignment(
    @field:DocumentId val id: String = "",
    val congregationId: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val publisherPersonId: String = "",
    val publisherName: String = "",
    /** One of [AssignmentTypes]. */
    val type: String = "OTHER",
    val title: String = "",
    val description: String = "",
    val remarks: String = "",
    /** When the assignment is for (midnight, millis); null when it has no date. */
    val date: Long? = null,
    /** One of [AssignmentStatus]. */
    val status: String = AssignmentStatus.ASSIGNED.name,
    val assignedByPersonId: String = "",
    val assignedByName: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val history: List<AssignmentHistoryEntry> = emptyList(),
)

@kotlinx.serialization.Serializable
data class AssignmentHistoryEntry(
    val at: Long = 0L,
    val byPersonId: String = "",
    val byName: String = "",
    val status: String = "",
    val note: String = "",
)

/** The five statuses, with the label and colours both apps use (same words and colours on the website). */
enum class AssignmentStatus(val label: String, val textColor: Long, val backColor: Long) {
    PENDING("Pending", 0xFF8A5A00, 0xFFFFF1C2),
    ASSIGNED("Assigned", 0xFF1A4F9C, 0xFFD8E8FF),
    IN_PROGRESS("In Progress", 0xFF5B2EA6, 0xFFE9DEFA),
    COMPLETED("Completed", 0xFF1F6B2C, 0xFFD6EFD8),
    CANCELLED("Cancelled", 0xFF8A1F3A, 0xFFF8D7E0),
    ;

    companion object {
        fun of(name: String?): AssignmentStatus = entries.firstOrNull { it.name == name } ?: ASSIGNED
    }
}

/** The kinds of assignment (named after the modules and duties GoPreach already has): key -> label. */
object AssignmentTypes {
    val all: Map<String, String> = linkedMapOf(
        "FIELD_SERVICE" to "Field Service",
        "CART" to "Cart Witnessing",
        "TERRITORY" to "Territory",
        "MEETING" to "Meeting Part",
        "FOLLOW_UP" to "Return Visit / Bible Study Follow-up",
        "GROUP_DUTY" to "Group Responsibility",
        "OTHER" to "Other",
    )

    fun label(key: String?): String = all[key] ?: key.orEmpty()
}
