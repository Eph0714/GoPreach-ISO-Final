package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.data.model.DeletedRecord
import com.emfitsolutions.gopreach.data.model.TrashItem
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.data.json.DocJson
import com.emfitsolutions.gopreach.data.json.encode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private const val COLLECTION = "deletedRecords"
private const val DAY_MS = 24L * 60 * 60 * 1000

sealed interface RestoreResult {
    data object Restored : RestoreResult
    /** Something active now clashes with the record (same id, same username, ...). Nothing was changed. */
    data class Conflict(val message: String) : RestoreResult
    data class Failed(val message: String) : RestoreResult
}

/**
 * Deleted Records: a central recycle bin shared by every module. Deleting a record anywhere calls
 * [moveToTrash] (and removes the live documents as before); the full original JSON lives on in a
 * [DeletedRecord] until it is [restore]d or permanently deleted. Because the live documents really are gone
 * from the normal collections, deleted records can't leak into lists, counts, reports, maps or dashboards.
 *
 * It rides on the same offline-first cache and sync queue as everything else, so a deletion, a restore and a
 * permanent delete all reach other devices through the normal sync.
 */
class RecycleBinRepository(
    private val offline: OfflineFirestoreRepository,
    private val remote: RemoteCollections,
    private val personRepository: PersonRepository,
    private val auditLogRepository: AuditLogRepository,
) {
    fun observeAll(): Flow<List<DeletedRecord>> = offline.observeCollection(COLLECTION)

    fun startRemoteSync(): Flow<Unit> =
        remote.mirror(COLLECTION, DeletedRecord::class) { it.id }

    /** A [TrashItem] for [data] as it is stored right now at [collectionPath]/[documentId]. */
    inline fun <reified T : Any> item(collectionPath: String, documentId: String, data: T) = TrashItem(collectionPath, documentId, DocJson.encode(data))

    /** A cleared relationship: put [field] back to the value it has in [data] on restore, only if it is still empty. */
    inline fun <reified T : Any> relationshipItem(collectionPath: String, documentId: String, data: T, field: String) =
        TrashItem(collectionPath, documentId, DocJson.encode(data), onlyIfFieldNull = field)

    /**
     * Records the deletion. The caller still removes the live documents through its own repositories, exactly
     * as before; this only keeps the snapshot. Returns the Deleted Records id.
     */
    suspend fun moveToTrash(
        recordType: String,
        module: String,
        label: String,
        congregationId: String?,
        groupId: String? = null,
        groupName: String? = null,
        originalCreatedAt: Long? = null,
        originalModifiedAt: Long? = null,
        deletedByPersonId: String,
        deletedByName: String = "",
        items: List<TrashItem>,
    ): String {
        val actorName = deletedByName.ifBlank { runCatching { personRepository.get(deletedByPersonId)?.fullName }.getOrNull().orEmpty() }
        val id = remote.newId(COLLECTION)
        offline.save(
            COLLECTION,
            id,
            DeletedRecord(
                id = id,
                recordType = recordType,
                module = module,
                label = label,
                congregationId = congregationId,
                groupId = groupId,
                groupName = groupName,
                originalCreatedAt = originalCreatedAt?.takeIf { it > 0 },
                originalModifiedAt = originalModifiedAt?.takeIf { it > 0 },
                deletedAt = nowMillis(),
                deletedByPersonId = deletedByPersonId,
                deletedByName = actorName,
                itemsJson = DocJson.encodeToString(ListSerializer(TrashItem.serializer()), items),
            ),
        )
        auditLogRepository.log(
            actorPersonId = deletedByPersonId,
            action = "MOVE_TO_DELETED_RECORDS",
            targetType = recordType,
            targetId = items.firstOrNull()?.documentId,
            congregationId = congregationId,
            details = "$module: $label",
        )
        return id
    }

    private fun itemsOf(record: DeletedRecord): List<TrashItem> =
        runCatching { DocJson.decodeFromString(ListSerializer(TrashItem.serializer()), record.itemsJson) }.getOrNull().orEmpty()

    /**
     * Puts the whole original record back, with its original ids. Checked first, and nothing is written if
     * anything clashes: an active document already using one of the ids, or an active person already using the
     * same username. A clash is reported, never overwritten.
     */
    suspend fun restore(record: DeletedRecord, actorPersonId: String): RestoreResult {
        // Re-read: it may have been restored or purged on another device since this list was drawn.
        offline.get<DeletedRecord>(COLLECTION, record.id)
            ?: return RestoreResult.Failed("This record is no longer in Deleted Records. It may have been restored or permanently deleted already.")
        val items = itemsOf(record)
        if (items.isEmpty()) return RestoreResult.Failed("This deleted record has no saved data to restore.")

        val people = personRepository.observeAll().first()
        for (item in items) {
            if (item.onlyIfFieldNull != null) continue
            val existing = offline.get<JsonObject>(item.collectionPath, item.documentId)
            if (existing != null) {
                return RestoreResult.Conflict("An active record with the same ID already exists, so \"${record.label}\" can't be restored over it. Review or remove that record first.")
            }
            if (item.collectionPath == "people") {
                val username = runCatching { DocJson.parseToJsonElement(item.json).jsonObject["username"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
                val clash = people.firstOrNull { it.id != item.documentId && username.isNotBlank() && it.username.equals(username, ignoreCase = true) }
                if (clash != null) {
                    return RestoreResult.Conflict("The username \"$username\" is now used by ${clash.fullName}, so \"${record.label}\" can't be restored. Change that account's username first, then restore.")
                }
            }
        }

        return runCatching {
            for (item in items) {
                val field = item.onlyIfFieldNull
                if (field == null) {
                    offline.saveRawJson(item.collectionPath, item.documentId, item.json)
                    continue
                }
                // A relationship cleared by the deletion: restore it only if it hasn't been set again since.
                val current = offline.get<JsonObject>(item.collectionPath, item.documentId) ?: continue
                val value = DocJson.parseToJsonElement(item.json).jsonObject[field]
                val currentValue = current[field]
                if ((currentValue == null || currentValue is JsonNull) && value != null && value !is JsonNull) {
                    val merged = JsonObject(current + (field to value))
                    offline.saveRawJson(item.collectionPath, item.documentId, DocJson.encodeToString(JsonObject.serializer(), merged))
                }
            }
            offline.delete(COLLECTION, record.id)
            auditLogRepository.log(
                actorPersonId = actorPersonId,
                action = "RESTORE_DELETED_RECORD",
                targetType = record.recordType,
                targetId = items.firstOrNull()?.documentId,
                congregationId = record.congregationId,
                details = "${record.module}: ${record.label}",
            )
            RestoreResult.Restored
        }.getOrElse { RestoreResult.Failed(it.localizedMessage ?: "Couldn't restore this record. Please try again.") }
    }

    /** Removes the record for good — only ever from Deleted Records, by an explicit action or the retention timer. */
    suspend fun permanentlyDelete(record: DeletedRecord, actorPersonId: String, automatic: Boolean) {
        offline.delete(COLLECTION, record.id)
        auditLogRepository.log(
            actorPersonId = actorPersonId,
            action = if (automatic) "AUTO_PERMANENT_DELETE_RECORD" else "PERMANENT_DELETE_RECORD",
            targetType = record.recordType,
            targetId = itemsOf(record).firstOrNull()?.documentId,
            congregationId = record.congregationId,
            details = "${record.module}: ${record.label} (deleted at ${record.deletedAt})",
        )
    }

    /**
     * Permanently deletes every entry in [candidates] whose retention has run out. Does nothing unless the
     * setting is on, and never touches an entry before its calculated date (deleted time + retention days).
     * Each entry is re-checked against the current data first, so one restored or purged elsewhere is skipped.
     */
    suspend fun purgeExpired(settings: AppSettings, candidates: List<DeletedRecord>, actorPersonId: String, now: Long = nowMillis()): Int {
        if (!settings.trashAutoDeleteEnabled) return 0
        var purged = 0
        for (record in candidates) {
            val due = permanentDeleteAt(record, settings) ?: continue
            if (due > now) continue
            if (offline.get<DeletedRecord>(COLLECTION, record.id) == null) continue
            permanentlyDelete(record, actorPersonId, automatic = true)
            purged++
        }
        return purged
    }

    companion object {
        /** When [record] will be permanently deleted automatically, or null when the setting is off. */
        fun permanentDeleteAt(record: DeletedRecord, settings: AppSettings): Long? =
            if (settings.trashAutoDeleteEnabled) record.deletedAt + settings.trashRetentionDays * DAY_MS else null
    }
}
