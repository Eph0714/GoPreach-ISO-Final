package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * Territory Assignment → "Per Publisher Assignment": one barangay assigned to
 * one specific Publisher (as opposed to a whole FS Group, see
 * [TerritoryAssignment]). Province/municipality/barangay come from the PSGC
 * dataset, so ids are PSGC ids and names are denormalized for display.
 * [returnVisitIds] optionally links the Publisher's own enrolled Return Visit
 * records ([InterestedPerson] in the RETURN_VISIT stage) to this territory;
 * [returnVisitNames] is denormalized for the same reason as the names above.
 *
 * Firestore collection: `publisherTerritoryAssignments/{assignmentId}`
 */
data class PublisherTerritoryAssignment(
    @DocumentId val id: String = "",
    val congregationId: String = "",
    val publisherPersonId: String = "",
    val publisherName: String = "",
    val provinceId: Int = 0,
    val provinceName: String = "",
    val muncityId: Int = 0,
    val muncityName: String = "",
    val barangayId: Int = 0,
    val barangayName: String = "",
    val returnVisitIds: List<String> = emptyList(),
    val returnVisitNames: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val createdByPersonId: String = "",
    val updatedAt: Long = 0L,
)
