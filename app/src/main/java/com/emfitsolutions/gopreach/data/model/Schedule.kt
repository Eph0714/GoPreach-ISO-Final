package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

enum class ScheduleKind { CALENDAR_EVENT, CHAT_SCHEDULE, PERSONAL_NOTE }

/**
 * Backs both the Calendar module (spec §6.2) and the Manage Chat Schedule feature
 * (spec §5.1) — same shape, distinguished by [kind] and scoped the same way by role.
 * A publisher's private personal note ([ScheduleKind.PERSONAL_NOTE]) sets
 * [visibility] to just that publisher's personId.
 *
 * Firestore collection: `schedules/{scheduleId}`
 */
data class Schedule(
    @DocumentId val id: String = "",
    val kind: ScheduleKind = ScheduleKind.CALENDAR_EVENT,
    val title: String = "",
    val description: String? = null,
    val startTime: Long = 0L,
    val endTime: Long = 0L,

    val congregationId: String? = null,
    /** Null = congregation-wide (Admin/Coordinator Elder scope); set = one group's event. */
    val groupId: String? = null,
    /** Set only for PERSONAL_NOTE — visible to this personId alone. */
    val ownerPersonId: String? = null,

    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
)
