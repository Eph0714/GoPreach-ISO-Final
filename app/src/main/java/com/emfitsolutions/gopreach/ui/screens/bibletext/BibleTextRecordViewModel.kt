package com.emfitsolutions.gopreach.ui.screens.bibletext

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.export.BibleTextExportFile
import com.emfitsolutions.gopreach.data.model.BibleTextCategory
import com.emfitsolutions.gopreach.data.model.BibleTextRecord
import com.emfitsolutions.gopreach.data.repository.BibleTextCategoryRepository
import com.emfitsolutions.gopreach.data.repository.BibleTextRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick-pick suggestions offered on the Add/Edit Event dialog's Theme/Topic
 * field (spec §1's own examples list, plus this module's pre-upgrade default
 * category set — carried over so a Publisher who relied on those still finds
 * them here). Plain strings the Publisher can also ignore and type over —
 * nothing else in this module treats a suggested value specially once it's
 * been typed into an Event's [BibleTextCategory.name]. */
val SUGGESTED_THEME_TOPICS: List<String> = listOf(
    "Love for Jehovah",
    "Strengthening Our Faith",
    "Endurance in Difficult Times",
    "Kingdom Preaching",
    "Family Worship",
    "God and His Attributes",
    "Jesus Christ",
    "The Bible and Its Teachings",
    "God's Kingdom",
    "Faith and Spirituality",
    "Prayer and Worship",
    "Christian Living",
    "Christian Qualities",
    "Family and Personal Matters",
    "Marriage and Relationships",
    "Parenting and Children",
    "Youth and Young People",
    "Daily Life and Practical Decisions",
    "Work, Money, and Material Things",
    "Health and Well-Being",
    "Peace, Happiness, and Encouragement",
    "Trials, Suffering, and Challenges",
    "Life and Death",
    "Sin, Forgiveness, and Salvation",
    "Conduct and Moral Issues",
    "Friendship and Relationships",
    "Congregation and Christian Unity",
    "Ministry and Evangelism",
    "Bible Prophecy and the Future",
    "Bible History",
    "Bible Characters and Examples",
    "Science and the Bible",
    "Bible Study and Understanding",
)

/** Quick-pick suggestions for the Event field itself (spec §1's own examples). */
val SUGGESTED_EVENTS: List<String> = listOf(
    "Public Talk",
    "Congregation Bible Study",
    "Regional Convention",
    "Circuit Assembly",
    "Memorial",
    "Special Meeting",
    "Personal Bible Study",
    "Ministry Meeting",
)

/**
 * "My Bible Text Record" module — a Publisher's personal Event → Bible Text
 * organizer. Every read/write here is scoped to whichever [publisherPersonId]
 * the caller passes, resolved by the screen from the signed-in session
 * (never a value the UI lets the Publisher type/pick themselves) — the same
 * "ownership from the session, not the frontend" rule
 * [BibleTextRecordRepository]'s own doc comment describes, backed
 * server-side by firestore.rules' matching `bibleTextRecords`/
 * `bibleTextCategories` blocks.
 */
@HiltViewModel
class BibleTextRecordViewModel @Inject constructor(
    private val recordRepository: BibleTextRecordRepository,
    private val eventRepository: BibleTextCategoryRepository,
    private val recycleBinRepository: com.emfitsolutions.gopreach.data.repository.RecycleBinRepository,
) : ViewModel() {

    fun recordsFor(publisherPersonId: String): Flow<List<BibleTextRecord>> =
        recordRepository.observeForPublisher(publisherPersonId)

    fun eventsFor(publisherPersonId: String): Flow<List<BibleTextCategory>> =
        eventRepository.observeForPublisher(publisherPersonId)

    fun saveRecord(record: BibleTextRecord) {
        viewModelScope.launch { recordRepository.save(record) }
    }

    fun deleteRecord(record: BibleTextRecord, label: String, actorPersonId: String) {
        viewModelScope.launch {
            recycleBinRepository.moveToTrash(
                recordType = "Bible Text",
                module = "My Bible Text Record",
                label = label,
                congregationId = null,
                originalCreatedAt = record.createdAt,
                deletedByPersonId = actorPersonId,
                items = listOf(recycleBinRepository.item("bibleTextRecords", record.id, record)),
            )
            recordRepository.delete(record.id)
        }
    }

    fun saveEvent(event: BibleTextCategory) {
        viewModelScope.launch { eventRepository.save(event) }
    }

    /** Hands the saved (id-assigned) Event straight back so the caller can
     * navigate into its (still-empty) Bible Text list immediately, without
     * waiting for the next [eventsFor] emission to catch up. */
    suspend fun saveEventAndReturn(event: BibleTextCategory): BibleTextCategory = eventRepository.save(event)

    /** "If the Publisher deletes an Event... Deleting the Event will also
     * remove its associated Bible Text records" (spec §16) — unlike the old
     * flat Category (a reusable tag other records could be reassigned away
     * from before deleting it), an Event is one specific occasion: nothing
     * else should end up "under" a different occasion just because this one
     * was deleted, so this always cascades rather than offering
     * reassignment. The screen shows spec §16's warning *before* calling
     * this, not after. */
    fun deleteEventCascade(publisherPersonId: String, eventId: String) {
        viewModelScope.launch {
            val toDelete = recordRepository.observeForPublisher(publisherPersonId).first()
                .filter { it.categoryId == eventId }
            val event = eventRepository.observeForPublisher(publisherPersonId).first().firstOrNull { it.id == eventId }
            if (event != null) {
                recycleBinRepository.moveToTrash(
                    recordType = "Bible Text Event",
                    module = "My Bible Text Record",
                    label = event.event,
                    congregationId = null,
                    originalCreatedAt = event.createdAt,
                    deletedByPersonId = publisherPersonId,
                    items = buildList {
                        add(recycleBinRepository.item("bibleTextCategories", event.id, event))
                        toDelete.forEach { add(recycleBinRepository.item("bibleTextRecords", it.id, it)) }
                    },
                )
            }
            toDelete.forEach { recordRepository.delete(it.id) }
            eventRepository.delete(eventId)
        }
    }

    /** "The receiving Publisher can import the data[;] the imported data
     * must not override the existing data of the receiving Publisher" —
     * every imported record becomes a brand-new [BibleTextRecord] with a
     * fresh Firestore-assigned id, owned by [publisherPersonId]; nothing the
     * receiving Publisher already has is ever matched, overwritten, or
     * deleted. An Event is only created when no existing Event with the
     * exact same Event+Theme/Topic+Speaker already exists for this Publisher
     * — reusing an existing one instead of creating a duplicate occasion is
     * the one bit of "merging" this does, and it never renames/deletes/
     * touches an existing Event to do it. */
    suspend fun importRecords(
        publisherPersonId: String,
        file: BibleTextExportFile,
        existingEvents: List<BibleTextCategory>,
    ): ImportResult {
        fun key(event: String, theme: String, speaker: String?) =
            Triple(event.trim().lowercase(), theme.trim().lowercase(), speaker?.trim()?.lowercase().orEmpty())

        val eventIdByKey = existingEvents.associateTo(mutableMapOf()) { key(it.event, it.name, it.speaker) to it.id }
        var newEventCount = 0
        file.records.map { Triple(it.eventName, it.themeTopic, it.speaker) }.distinct().forEach { (event, theme, speaker) ->
            val k = key(event, theme, speaker)
            if (!eventIdByKey.containsKey(k)) {
                val now = System.currentTimeMillis()
                val saved = eventRepository.save(
                    BibleTextCategory(publisherPersonId = publisherPersonId, event = event.trim(), name = theme.trim(), speaker = speaker?.trim()?.ifBlank { null }, createdAt = now, updatedAt = now),
                )
                eventIdByKey[k] = saved.id
                newEventCount++
            }
        }
        var newRecordCount = 0
        file.records.forEach { exported ->
            val eventId = eventIdByKey[key(exported.eventName, exported.themeTopic, exported.speaker)] ?: return@forEach
            val now = System.currentTimeMillis()
            recordRepository.save(
                BibleTextRecord(
                    publisherPersonId = publisherPersonId,
                    bibleVersionId = exported.bibleVersionId,
                    languageId = exported.languageId,
                    bibleBookId = exported.bibleBookId,
                    chapter = exported.chapter,
                    verses = exported.verses,
                    categoryId = eventId,
                    remarks = exported.remarks,
                    videos = exported.videos,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            newRecordCount++
        }
        return ImportResult(newEventCount, newRecordCount)
    }
}

/** [newEvents]/[newRecords] — how many of each [importRecords] actually
 * added, for the "Imported X records and Y new events" confirmation. */
data class ImportResult(val newEvents: Int, val newRecords: Int)
