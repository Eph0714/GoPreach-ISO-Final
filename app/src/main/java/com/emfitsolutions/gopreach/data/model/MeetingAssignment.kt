package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * "Meeting Assignments" module.
 *
 * One assignment particular under a Midweek Meeting Schedule sub-category —
 * "there can be more assignment particulars for each sub category, the
 * particulars will be inputted manually and will not be hard coded," so this
 * is a free-form repeatable row, not a fixed shape or count. [durationMinutes]
 * and [assignedTo] are both optional free text: a duration isn't always given
 * (the spec's own example — "Our History in Motion," no minutes listed) and
 * an assignment can name more than one publisher ("Evarose and Jovy").
 */
data class MidweekAssignmentItem(
    val particular: String = "",
    val durationMinutes: String = "",
    val assignedTo: String = "",
)

/** The Midweek Meeting Schedule's three fixed sub-categories — spec's own
 * names and background-fill colors; what's *inside* each ([MidweekAssignmentItem]
 * list on [MidweekMeetingSchedule]) is fully user-entered, never hard-coded. */
enum class MidweekSection(val displayLabel: String) {
    TREASURES("TREASURES FROM GOD'S WORD"),
    FIELD_MINISTRY("APPLY YOURSELF TO THE FIELD MINISTRY"),
    LIVING_AS_CHRISTIANS("LIVING AS CHRISTIANS"),
}

/**
 * One congregation's Midweek Meeting Schedule for one week. [weekStartDate]
 * is that week's Monday at midnight — the natural key alongside
 * [congregationId] (spec's own example: "Week for August 31-September 6" —
 * a Monday-Sunday span); picking an already-scheduled week in the
 * enrollment UI edits this same document instead of creating a duplicate
 * for it (see MeetingAssignmentsViewModel.scheduleFor).
 *
 * Firestore collection: `midweekMeetingSchedules/{scheduleId}`
 */
data class MidweekMeetingSchedule(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val weekStartDate: Long = 0L,
    val treasuresItems: List<MidweekAssignmentItem> = emptyList(),
    val fieldMinistryItems: List<MidweekAssignmentItem> = emptyList(),
    val livingAsChristiansItems: List<MidweekAssignmentItem> = emptyList(),
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
) {
    fun itemsFor(section: MidweekSection): List<MidweekAssignmentItem> = when (section) {
        MidweekSection.TREASURES -> treasuresItems
        MidweekSection.FIELD_MINISTRY -> fieldMinistryItems
        MidweekSection.LIVING_AS_CHRISTIANS -> livingAsChristiansItems
    }

    fun withItems(section: MidweekSection, items: List<MidweekAssignmentItem>): MidweekMeetingSchedule = when (section) {
        MidweekSection.TREASURES -> copy(treasuresItems = items)
        MidweekSection.FIELD_MINISTRY -> copy(fieldMinistryItems = items)
        MidweekSection.LIVING_AS_CHRISTIANS -> copy(livingAsChristiansItems = items)
    }
}

/**
 * One row of the "Public Talk and Watchtower Study Schedule" — one per
 * [date], never duplicated within a congregation (spec: "do not duplicate
 * date" — enforced in MeetingAssignmentsViewModel, not just the UI). Every
 * field besides [date] is free text, entered manually (spec: "will not be
 * hard coded").
 *
 * Firestore collection: `publicTalkSchedules/{rowId}`
 */
data class PublicTalkScheduleRow(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val date: Long = 0L,
    val theme: String = "",
    val speaker: String = "",
    val chairman: String = "",
    val watchtowerConductor: String = "",
    val watchtowerReader: String = "",
    val micServers: String = "",
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
)

/**
 * One row of the "Cart Assignment" schedule (module renamed "Meeting and
 * Cart Assignment" to cover this) — Date/Location/Publishers, entered
 * manually like every other field in this module. Unlike [PublicTalkScheduleRow],
 * more than one row may share the same [date]: "there can be multiple cart
 * assignment[s]" for one day (spec's own example — two different locations,
 * same date), so there is no "do not duplicate date" rule here.
 *
 * Firestore collection: `cartAssignments/{rowId}`
 */
data class CartAssignmentRow(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val date: Long = 0L,
    val location: String = "",
    val publishers: String = "",
    val createdByPersonId: String = "",
    val createdAt: Long = 0L,
    val lastEditedByPersonId: String? = null,
    val lastEditedAt: Long? = null,
)
