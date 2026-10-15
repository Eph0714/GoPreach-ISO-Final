package com.emfitsolutions.gopreach.ui.screens.announcements

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.AnnouncementAttachment
import com.emfitsolutions.gopreach.data.model.AnnouncementRead
import com.emfitsolutions.gopreach.data.repository.AnnouncementRepository
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.platform.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File

/** An elder the Circuit Overseer may address: only elders of the chosen congregation are ever listed. */
data class ElderOption(val personId: String, val name: String, val roleLabel: String)

private val ELDER_ROLES = mapOf(
    "COORDINATOR_ELDER" to "Coordinator Elder",
    "SERVICE_OVERSEER" to "Service Overseer",
    "SECRETARY" to "Secretary",
    "REGULAR_ELDER" to "Elder",
)

const val MAX_ANNOUNCEMENT_FILE_BYTES = 10L * 1024 * 1024

/**
 * The Circuit Overseer's Announcements module, and the file handling shared with the recipients' list. Who sees what is decided by the server:
 * this phone only ever receives the announcements it may read, and a file is only handed over after the server has checked the same.
 */
class CoAnnouncementsViewModel(
    private val repository: AnnouncementRepository,
    private val persons: PersonRepository,
    private val audit: AuditLogRepository,
    private val context: Context,
) : ViewModel() {

    val peopleNames: StateFlow<Map<String, String>> =
        persons.observeAll().map { list -> list.associate { it.id to it.fullName } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Circuit Overseer announcements of one congregation, newest first (null = every congregation the phone knows). */
    fun announcementsFor(congregationId: String?): Flow<List<Announcement>> =
        repository.observeAll().map { list ->
            list.filter { it.isCircuitOverseerStyle && (congregationId == null || it.congregationId == congregationId) }.sortedByDescending { it.displayDate }
        }

    /** The elders of [congregationId] who can receive an announcement (they have signed in, so the server knows their congregation and role). */
    fun eldersOf(congregationId: String?): Flow<List<ElderOption>> =
        persons.observeAll().map { people ->
            if (congregationId == null) emptyList()
            else people.filter { it.activeCongregationId == congregationId && it.activeAdminRole in ELDER_ROLES }
                .map { ElderOption(it.id, it.fullName, ELDER_ROLES.getValue(it.activeAdminRole!!)) }
                .sortedBy { it.name.lowercase() }
        }

    fun readsOf(announcementId: String): Flow<List<AnnouncementRead>> = repository.observeReads(announcementId)

    fun readIds(personId: String): Flow<Set<String>> = repository.observeReadIds(personId)

    suspend fun markRead(personId: String, announcementId: String) = repository.markRead(personId, announcementId)

    fun newId(): String = repository.newId()

    // ---------------------------------------------------------------- saving

    /** Saves a draft or a published announcement, then records what happened (published / edited / recipients changed) in the audit history. */
    suspend fun save(announcement: Announcement, actorPersonId: String, previous: Announcement?): Announcement {
        val now = nowMillis()
        val actorName = persons.get(actorPersonId)?.fullName.orEmpty()
        val publishing = announcement.status == Announcement.STATUS_PUBLISHED
        val firstPublish = publishing && previous?.status != Announcement.STATUS_PUBLISHED
        val toSave = announcement.copy(
            createdByPersonId = previous?.createdByPersonId ?: actorPersonId,
            createdByName = previous?.createdByName?.ifBlank { actorName } ?: actorName,
            createdAt = previous?.createdAt?.takeIf { it > 0L } ?: now,
            publishedAt = if (publishing) (previous?.publishedAt ?: now) else previous?.publishedAt,
            lastEditedByPersonId = if (previous == null) null else actorPersonId,
            lastEditedAt = if (previous == null) null else now,
        )
        val saved = repository.save(toSave)
        val action = when {
            firstPublish -> "ANNOUNCEMENT_PUBLISHED"
            previous == null -> "ANNOUNCEMENT_DRAFT_SAVED"
            else -> "ANNOUNCEMENT_EDITED"
        }
        val recipientsNow = recipientSummary(saved)
        val recipientsChanged = previous != null && (previous.recipientScope != saved.recipientScope || previous.recipientIds.toSet() != saved.recipientIds.toSet())
        audit.log(actorPersonId, action, "Announcement", saved.id, saved.congregationId, "\"${saved.title}\" - $recipientsNow")
        if (recipientsChanged && !firstPublish) audit.log(actorPersonId, "ANNOUNCEMENT_RECIPIENTS_CHANGED", "Announcement", saved.id, saved.congregationId, recipientsNow)
        return saved
    }

    suspend fun archive(announcement: Announcement, actorPersonId: String) {
        repository.save(announcement.copy(status = Announcement.STATUS_ARCHIVED, lastEditedByPersonId = actorPersonId, lastEditedAt = nowMillis()))
        audit.log(actorPersonId, "ANNOUNCEMENT_ARCHIVED", "Announcement", announcement.id, announcement.congregationId, "\"${announcement.title}\"")
    }

    /** Deletes the announcement and the files on the server that belonged to it. */
    suspend fun delete(announcement: Announcement, actorPersonId: String) {
        announcement.attachments.forEach { repository.deleteSecureAttachment(announcement.id, it.id) }
        repository.delete(announcement.id)
        audit.log(actorPersonId, "ANNOUNCEMENT_DELETED", "Announcement", announcement.id, announcement.congregationId, "\"${announcement.title}\"")
    }

    private fun recipientSummary(a: Announcement) =
        if (a.recipientScope == Announcement.SCOPE_SELECTED_ELDERS) "Selected elders (${a.recipientIds.size})" else "Entire congregation"

    // ---------------------------------------------------------------- files

    /** Reads the picked file, checks its size, and sends it to the server, which checks its type and content. Throws a readable message when refused. */
    suspend fun attach(announcementId: String, uri: Uri): AnnouncementAttachment {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        if (size > MAX_ANNOUNCEMENT_FILE_BYTES) throw IllegalArgumentException("\"$name\" is larger than 10 MB.")
        val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IllegalStateException("Couldn't read \"$name\".")
        if (bytes.size > MAX_ANNOUNCEMENT_FILE_BYTES) throw IllegalArgumentException("\"$name\" is larger than 10 MB.")
        val mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        return repository.uploadSecureAttachment(announcementId, name, bytes, mime)
    }

    suspend fun removeAttachment(announcementId: String, attachmentId: String) = repository.deleteSecureAttachment(announcementId, attachmentId)

    suspend fun download(announcementId: String, attachment: AnnouncementAttachment): ByteArray = repository.downloadSecureAttachment(announcementId, attachment.id)

    /** Copies the file into the phone's Downloads/GoPreach folder; returns what to tell the person. */
    suspend fun saveToDownloads(attachment: AnnouncementAttachment, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, attachment.name)
                put(MediaStore.Downloads.MIME_TYPE, attachment.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GoPreach")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Couldn't create the file in Downloads.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't write the file.")
            "Saved to Downloads/GoPreach: ${attachment.name}"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val file = File(dir, attachment.name).also { it.writeBytes(bytes) }
            "Saved to ${file.path}"
        }
    }

    /** Opens a downloaded file in the phone's own viewer (PDF reader, image viewer, Office app). The copy lives in the app cache only. */
    suspend fun openExternally(activityContext: Context, attachment: AnnouncementAttachment, bytes: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }.let { dir -> File(dir, attachment.id + "_" + attachment.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.writeBytes(bytes) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activityContext.startActivity(Intent.createChooser(intent, attachment.name))
    }
}
