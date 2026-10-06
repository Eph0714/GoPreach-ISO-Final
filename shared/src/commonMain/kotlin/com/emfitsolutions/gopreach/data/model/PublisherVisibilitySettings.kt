package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/**
 * "Publisher Assignment" visibility switches — one document per congregation, edited by the Admin from the
 * Publisher Assignment module. They only decide whether a Publisher can SEE records assigned to *other*
 * Publishers; a Publisher always sees their own records, and an unassigned Searching/Return Visit record is
 * always visible to every Publisher of the congregation regardless of these flags.
 *
 * Firestore collection: `publisherVisibilitySettings/{congregationId}`
 */
@kotlinx.serialization.Serializable
data class PublisherVisibilitySettings(
    @DocumentId val id: String = "",
    /** Other Publishers can see (and add visits to) Searching records assigned to someone else. */
    val showOthersSearching: Boolean = false,
    /** Other Publishers can see (and add visits to) Return Visit records assigned to someone else. */
    val showOthersReturnVisit: Boolean = false,
    /** Other Publishers can VIEW Bible Studies assigned to someone else — never add a visit to them. */
    val showOthersBibleStudy: Boolean = false,
    /** "Follow-up Needed After [value] [unit]" — a record whose latest visit is older than this (or that has none) needs follow-up. */
    val followUpValue: Int = 7,
    val followUpUnit: String = FollowUpUnit.DAYS.name,
    val updatedByPersonId: String? = null,
    val updatedAt: Long = 0L,
) {
    /** Visits before this instant are overdue. Months and years are calendar-based, not 30/365-day approximations. */
    fun followUpCutoff(now: Long = com.emfitsolutions.gopreach.platform.nowMillis()): Long {
        val value = followUpValue.coerceAtLeast(1)
        val unit = when (runCatching { FollowUpUnit.valueOf(followUpUnit) }.getOrDefault(FollowUpUnit.DAYS)) {
            FollowUpUnit.DAYS -> com.emfitsolutions.gopreach.platform.CalendarUnit.DAYS
            FollowUpUnit.MONTHS -> com.emfitsolutions.gopreach.platform.CalendarUnit.MONTHS
            FollowUpUnit.YEARS -> com.emfitsolutions.gopreach.platform.CalendarUnit.YEARS
        }
        return com.emfitsolutions.gopreach.platform.minusCalendar(now, value, unit)
    }

    fun showsOthers(stage: PipelineStage): Boolean = when (stage) {
        PipelineStage.SEARCHING -> showOthersSearching
        PipelineStage.RETURN_VISIT -> showOthersReturnVisit
        PipelineStage.BIBLE_STUDY -> showOthersBibleStudy
    }

    companion object {
        fun defaultsFor(congregationId: String) = PublisherVisibilitySettings(id = congregationId)
    }
}

/**
 * The one rule for what a Publisher may SEE, shared by the Searching / Return Visit / Bible Study modules and the
 * dashboard's Recently Visited so they can never drift apart. A Publisher always sees their own records. Unassigned
 * Searching and Return Visit records of their congregation are always visible; unassigned Bible Studies are not.
 * Records assigned to someone else are visible only when the congregation's setting for that stage is on. Seeing a
 * Bible Study of another Publisher is view-only (see [canPublisherAddVisit]).
 */
fun InterestedPerson.isVisibleToPublisher(
    publisherPersonId: String,
    publisherCongregationId: String,
    settings: PublisherVisibilitySettings,
): Boolean {
    if (this.publisherPersonId == publisherPersonId) return true
    if (congregationId != publisherCongregationId) return false
    return if (this.publisherPersonId.isBlank()) pipelineStage != PipelineStage.BIBLE_STUDY
    else settings.showsOthers(pipelineStage)
}

/** Whether a Publisher who can see this record may also log a visit on it. Bible Study: only the assigned Publisher. */
fun InterestedPerson.canPublisherAddVisit(publisherPersonId: String): Boolean =
    pipelineStage != PipelineStage.BIBLE_STUDY || this.publisherPersonId == publisherPersonId

/** How long a record may go without a visit before it needs follow-up: [followUpValue] days, months or years. */
@kotlinx.serialization.Serializable
enum class FollowUpUnit(val label: String) { DAYS("Days"), MONTHS("Months"), YEARS("Years") }
