package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/**
 * One document inside a [DeletedRecord]: exactly what was stored at [collectionPath]/[documentId] when the
 * record was deleted, as JSON, so a restore puts it back unchanged (same id).
 *
 * [onlyIfFieldNull] is for a *relationship* that was cleared by the deletion rather than a document that was
 * removed — e.g. the members of a deleted Field Service Group lose their `groupId`. On restore the stored value
 * of that field is put back only if the document still exists and the field is still empty, so a newer change is
 * never overwritten.
 */
data class TrashItem(
    val collectionPath: String = "",
    val documentId: String = "",
    val json: String = "",
    val onlyIfFieldNull: String? = null,
)

/**
 * The Deleted Records ("recycle bin") entry for one deleted record and everything that went with it. Deleting
 * anything in GoPreach moves it here; it only disappears for good by an explicit "Delete Permanently" or when
 * the optional retention period expires. [itemsJson] is the JSON list of [TrashItem]s (a record plus related
 * documents such as a Publisher's role assignments and reports).
 */
data class DeletedRecord(
    @DocumentId val id: String = "",
    /** `deleted` while it sits here. A restored or permanently deleted entry is removed (the audit log keeps the history). */
    val status: String = STATUS_DELETED,
    /** What kind of record: "Publisher", "Return Visit", "Monthly Report", ... */
    val recordType: String = "",
    /** The module it came from: "Publishers", "Return Visit / Bible Study", ... */
    val module: String = "",
    /** Name / description shown in the list. */
    val label: String = "",
    val congregationId: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val originalCreatedAt: Long? = null,
    val originalModifiedAt: Long? = null,
    val deletedAt: Long = 0L,
    val deletedByPersonId: String = "",
    val deletedByName: String = "",
    val itemsJson: String = "[]",
) {
    companion object {
        const val STATUS_DELETED = "deleted"
    }
}
