package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

/** "My Bible Text Record: Event/Topic Record Structure Upgrade" — every
 * [BibleTextCategory] saved before this pass has no occasion info at all
 * (see [BibleTextCategory.event]'s own doc comment); this is what a
 * pre-existing one defaults to instead of a blank required field, so it
 * shows up as something the Publisher can immediately correct rather than
 * losing data or being silently blocked (spec §25: "create an appropriate
 * migration/default strategy rather than deleting the records"). */
const val LEGACY_EVENT_PLACEHOLDER = "Personal Bible Study"

/**
 * "My Bible Text Record: Event/Topic Record Structure Upgrade" — the parent
 * record for one Event/occasion (spec: "Public Talk", "Congregation Bible
 * Study", "Memorial", ...), which one or more [BibleTextRecord]s are saved
 * under. Strictly per-Publisher: [publisherPersonId] is the ownership
 * boundary this app's client-side scoping *and* firestore.rules both enforce
 * (see that file's own comment on this collection) — spec §18/§20's "Do not
 * trust a client-submitted PublisherID."
 *
 * Pre-upgrade this was a flat, reusable "Category" ("God's Promise",
 * "Ministry", "Family", ...) with no occasion/speaker info and no concept of
 * a Bible Text "belonging" to one specific event — every [BibleTextRecord]
 * just picked one from a dropdown, and the same category was reused across
 * unrelated records. [event]/[speaker] are new fields, additive on top of
 * that same collection/document shape (no data migration needed): existing
 * [name] values become this Event's Theme/Topic — precisely what those free-
 * text values already were — and existing records keep working with
 * [event] defaulting to [LEGACY_EVENT_PLACEHOLDER] until the Publisher edits
 * them.
 *
 * Firestore collection: `bibleTextCategories/{eventId}`
 */
data class BibleTextCategory(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    /** The Event/occasion type — required for a new record (spec §1/§20);
     * blank for anything saved before this upgrade (see this class's own doc
     * comment and [LEGACY_EVENT_PLACEHOLDER]). */
    val event: String = "",
    /** Kept under its pre-upgrade Firestore field name (`name`) rather than
     * renamed to `themeTopic` on the wire — this is the exact same free-text
     * value the old flat "Category" already stored ("Christian Living",
     * "Ministry and Evangelism", ...), which is precisely what this
     * upgrade's required "Theme/Topic" field means; renaming the stored
     * field would need a data migration for zero actual behavior change. */
    val name: String = "",
    /** Optional (spec §1: "Speaker must NOT be required"). */
    val speaker: String? = null,
    /** The event's own video gallery — jw.org videos kept with the Event as a
     * group separate from its Bible Texts (see [SavedVideo]). Videos that were
     * attached to an individual [BibleTextRecord.videos] before this existed are
     * still shown in the same gallery. */
    val videos: List<SavedVideo> = emptyList(),
    /** "Allow the publisher to add a sub topic and add a bible text inside
     * it" — an Event's own Bible Texts can optionally be grouped under one
     * of these (e.g. "Introduction", "Main Point 1", "Conclusion") instead
     * of sitting directly under the Event. Kept as a plain embedded list on
     * the Event itself rather than its own synced Firestore collection —
     * one Event's subtopics are never read/written independently of that
     * Event, so this needs no new collection, security rule, or sync
     * plumbing of its own; it rides along with the Event's existing one.
     * A subtopic can itself contain subtopics (see [BibleTextSubtopic.parentId]),
     * to any depth. A Bible Text's own [BibleTextRecord.subtopicId] is what
     * actually places it under one specific subtopic, or leaves it directly
     * under the Event when null. */
    val subtopics: List<BibleTextSubtopic> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** One sub-grouping within a single [BibleTextCategory] (Event) — see that
 * class's own [BibleTextCategory.subtopics] doc comment. [id] only needs to
 * be unique within its own Event (a client-generated random string is
 * enough; it's never looked up across Events). [parentId] is another
 * subtopic's [id] within the same Event, or null for a top-level subtopic —
 * a subtopic can itself contain subtopics, to any depth ("theme inside a
 * sub theme, and so on"). */
data class BibleTextSubtopic(
    val id: String = "",
    val name: String = "",
    val parentId: String? = null,
    /** Manual display order among siblings (same [parentId]) — "allow the
     * user to rearrange the subtopic manually" via Move Up/Down, which swaps
     * this value between two adjacent siblings rather than reordering by
     * [createdAt]. Defaults to [createdAt] at creation time so a subtopic
     * saved before this field existed sorts exactly where it already did. */
    val order: Long = 0L,
    val createdAt: Long = 0L,
)

/**
 * "My Bible Text Record" module (spec §1-§34) — one saved Bible reference: a
 * personal study/ministry/reminder note tied to Version + Language + Book +
 * Chapter + Verses + Category + Remarks. [bibleVersionId]/[languageId]/
 * [bibleBookId] key into the static reference data in
 * [com.emfitsolutions.gopreach.domain.NwtBibleReferenceData] (see that
 * file's own doc comment for why this app's Bible reference metadata is
 * bundled reference data rather than its own synced Firestore collection —
 * this record just stores the ids so a future move to a server-hosted
 * lookup table is a data-source swap under these same fields, not a schema
 * change here).
 *
 * Spec §32 — this stores a *reference* (book/chapter/verses) plus the
 * Publisher's own [remarks], never the underlying NWT verse text itself: no
 * licensed/authorized full-text source is wired into this app, so nothing
 * here reproduces copyrighted Bible text. [remarks] is the Publisher's own
 * original content and is stored like any other personal note.
 *
 * Firestore collection: `bibleTextRecords/{recordId}`
 */
data class BibleTextRecord(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val bibleVersionId: String = "",
    val languageId: String = "",
    val bibleBookId: String = "",
    val chapter: Int = 0,
    /** Spec §9 — a single verse ("3") or a verse range ("3-4", "10-12"),
     * stored as entered (validated at the UI layer against the selected
     * Book's chapter/verse-count metadata when available — see
     * [com.emfitsolutions.gopreach.domain.NwtBibleReferenceData]). Free text
     * rather than two int columns so "3-4" round-trips exactly as the
     * Publisher typed it, matching every worked example in the spec. */
    val verses: String = "",
    /** The parent [BibleTextCategory] (Event/occasion) this Bible Text
     * belongs to — kept under its pre-upgrade field name (`categoryId`) for
     * Firestore backward compatibility; every existing record's value here
     * is already exactly this relationship (spec §17's `MyBibleEventID`). */
    val categoryId: String = "",
    /** One of the parent Event's own [BibleTextCategory.subtopics] ids, or
     * null when this Bible Text sits directly under the Event instead of
     * any subtopic — see that field's own doc comment. Never validated
     * against the parent's current subtopic list at read time: a subtopic
     * deleted out from under this record (see the delete-subtopic flow,
     * which moves its texts back to null rather than deleting them) simply
     * leaves this pointing at nothing, which reads the exact same as null. */
    val subtopicId: String? = null,
    /** Optional (spec §3/§20) — the Publisher's own personal note; multi-line
     * free text. */
    val remarks: String = "",
    /** JW Library / jw.org videos the Publisher attached to this Bible Text —
     * links only (see [SavedVideo]); the video files themselves are never
     * stored in the record. */
    val videos: List<SavedVideo> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * A jw.org video attached to a [BibleTextRecord], saved as a reference only —
 * its [lank] (jw.org's key for the video, the same one JW Library puts in a
 * shared link, e.g. "pub-jwbvod26_34_VIDEO") and the language it was found in.
 * The streaming/download addresses are looked up from jw.org each time they're
 * needed rather than saved here, so a link that later changes still works. A
 * downloaded copy lives only on the device it was downloaded to (see
 * [com.emfitsolutions.gopreach.data.repository.JwVideoRepository]) and is
 * found again from [lank] + [jwLocale], so nothing device-specific is stored
 * in the synced record.
 */
data class SavedVideo(
    val title: String = "",
    val lank: String = "",
    /** jw.org locale code of the language this video was found in ("E", "TG"...). */
    val jwLocale: String = "E",
    val durationSeconds: Int = 0,
    val thumbnailUrl: String = "",
    val addedAt: Long = 0L,
)
