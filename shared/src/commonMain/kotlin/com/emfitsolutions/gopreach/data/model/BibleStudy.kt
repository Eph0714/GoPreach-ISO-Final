package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId

/** What a [SupportingImage] shows — the UI only ever manages one image today
 * (the first entry of [InterestedPerson.supportingImages], defaulting to
 * [HOUSE]), but the type field is already there so a future multi-image
 * picker (spec's "Multiple Images – Future Ready" §8) is a UI change only,
 * not a data-model migration. */
@kotlinx.serialization.Serializable
enum class SupportingImageType { HOUSE, GATE, LANDMARK, MEETING_PLACE, OTHER }

/**
 * One supporting-place photo for an [InterestedPerson] (spec's "Interested
 * Person – Supporting Place Image Capture" feature). Stored as a downscaled,
 * compressed JPEG, Base64-encoded directly into the [InterestedPerson]
 * document — **not** Firebase Storage, since this project's Storage bucket
 * isn't provisioned yet (see SETUP.md: needs the paid Blaze plan). Embedding
 * it here instead means: (a) it rides the same offline-first Room cache +
 * Firestore sync path every other field already uses, no new plumbing; (b) it
 * inherits the InterestedPerson document's own `firestore.rules` access
 * control automatically, satisfying spec §9's "same security and access
 * rules as the Interested Person record" for free; and (c) there is no
 * separate download URL of any kind — public, predictable, or otherwise
 * (spec §9's other requirement) — because there's nothing to fetch
 * independently of the record itself. The capture flow (see
 * SupportingImageCapture.kt) keeps the encoded size to a few hundred KB at
 * most, comfortably inside Firestore's 1 MiB per-document limit.
 */
@kotlinx.serialization.Serializable
data class SupportingImage(
    val type: String = SupportingImageType.HOUSE.name,
    val base64Jpeg: String = "",
    val capturedAt: Long = 0L,
)

/**
 * Publisher-managed pipeline record — one person moving through Searching →
 * Return Visit → Bible Study (see [PipelineStage]). Before the "Redesign the
 * Publisher Dashboard" phase this was two separate, unrelated entities (an
 * `InterestedPerson` with no stage field, and a standalone `BibleStudyRecord`
 * with no visit history of its own); they're unified into this one record now
 * that the spec describes a single person carrying the same GPS/name/etc.
 * fields and one shared [Visit] history through every stage of their life in
 * this app. A "Bible Study" today is simply an [InterestedPerson] whose
 * [pipelineStage] is [PipelineStage.BIBLE_STUDY] — there is no separate
 * Bible Study collection or model anymore.
 *
 * Firestore collection: `interestedPeople/{interestedPersonId}`
 */
@kotlinx.serialization.Serializable
data class InterestedPerson(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    /** The congregation this record currently belongs to — set at creation
     * from the enrolling publisher's own assignment, and the one field a
     * successful [ForwardRequest] acceptance actually changes (along with
     * [publisherPersonId]). This is also the scope boundary a Service
     * Overseer's incoming-request screen filters on. */
    val congregationId: String = "",
    val name: String = "",
    val gender: Gender? = null,
    /** Shown to users as **Place of Origin** (renamed from "Address") — where
     * the person comes from, as distinct from the structured "Current
     * Address" ([province]/[cityMunicipality]/[barangay]) below. The stored
     * field name stays `address` so every existing Searching/Return Visit/
     * Bible Study record keeps its data with no migration; only the label
     * changed. The older, separate optional [placeOrigin] field predates this
     * rename and is no longer offered in the forms — it is kept (never
     * deleted) purely so records that already have a value still show it. */
    val address: String = "",
    /** "Add a dropdown for City, Municipalities, Town Barangay" — the
     * Philippine Standard Geographic Code's three levels (see
     * [com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository]),
     * stored as plain names (never a PSGC id) so they read the same way
     * [address] itself already does; `null` means never set — this is
     * additive on top of the free-text [address], not a replacement for it.
     * Filled either by browsing [com.emfitsolutions.gopreach.ui.components
     * .PhilippineAddressPicker]'s dropdowns, or automatically from
     * [gpsLat]/[gpsLng] once captured (best-effort — see [com
     * .emfitsolutions.gopreach.data.location.GeocodedAddress]'s own doc
     * comment on why barangay in particular isn't always resolved). */
    val province: String? = null,
    val cityMunicipality: String? = null,
    val barangay: String? = null,
    /** "Searching Module" spec — optional; free text since a household's
     * make-up isn't a fixed shape (multiple spouses/none/n-a are all valid
     * free-text answers in the source spec's own example). */
    val spouse: String? = null,
    val children: String? = null,
    val ageYears: Int? = null,
    val placeOrigin: String? = null,
    val language: String? = null,
    val literaturePlace: String? = null,
    val remarks: String? = null,
    /** "Interested Person GPS Capture" spec §12 — proper numeric fields, not
     * formatted text, so filtering/mapping by coordinate stays possible.
     * `null` means "not captured yet"; [gpsLat]/[gpsLng] are always set or
     * cleared together (see [InterestedPeopleViewModel.saveGpsLocation]/
     * [InterestedPeopleViewModel.clearGpsLocation]), never independently. */
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    /** Meters, as reported by [com.emfitsolutions.gopreach.data.location
     * .LocationTracker] at capture time — spec §12's recommended metadata. */
    val gpsAccuracy: Float? = null,
    val gpsCapturedAt: Long? = null,
    /** personId of whoever last captured/replaced this location — spec §12's
     * recommended metadata; distinct from the record's own [publisherPersonId]
     * since an Elder editing on a Publisher's behalf is still possible. */
    val gpsCapturedBy: String? = null,
    /** Set on every capture/replace, same value as [gpsCapturedAt] for a
     * fresh capture — kept as its own field (rather than reusing
     * [gpsCapturedAt]) so a future "originally captured vs. last updated"
     * distinction is a read-only addition, not a schema change. */
    val gpsUpdatedAt: Long? = null,
    val religion: String? = null,
    /** "Interested Person Fields" spec §2 — optional free text; distinct
     * from [religion] (a specific field with its own semantics) rather than
     * folding general notes into it. */
    val notes: String? = null,
    /** Optional contact info (mobile, telephone, or anything else) — Search Record, Return Visit and Bible Study alike. Absent on older records. */
    val contact: String? = null,
    val createdAt: Long = 0L,
    /** Stamped by [com.emfitsolutions.gopreach.data.repository
     * .InterestedPersonRepository.save] on every write; `0` only on a record
     * that predates this field and hasn't been saved since. */
    val updatedAt: Long = 0L,
    /** System-generated (spec §2/§12) — the signed-in session that enrolled
     * this person; set once at creation and never touched by an edit, same
     * way [createdAt] is already handled. Not shown as an editable field.
     * Distinct from [publisherPersonId] (the *assigned* publisher): a
     * Publisher enrolling their own record is both, but an Admin/Elder/
     * Service Overseer enrolling one from Find Location is only the creator
     * and picks (or leaves blank) who it's assigned to. */
    val createdByPersonId: String = "",
    /** Optional (spec §7) — empty until a supporting photo is captured. Only
     * the first entry is used by the current UI; see [SupportingImage]. */
    val supportingImages: List<SupportingImage> = emptyList(),
    /** "Admin Record Deletion and Inactive Status" spec — see [Congregation.status]. */
    val status: RecordStatus = RecordStatus.ACTIVE,
    val pipelineStage: PipelineStage = PipelineStage.SEARCHING,
    /** Bumped every time [pipelineStage] changes (and set to [createdAt] at
     * creation) — this, not [createdAt], is what "Bible Studies this month"
     * style date-range reports filter on (see ConsolidatedReportViewModel/
     * PublisherDashboardViewModel), since a record's creation date and the
     * date it actually became a Bible Study are two different things once a
     * person can spend weeks in Searching or Return Visit first. */
    val stageEnteredAt: Long = 0L,
    /** Points at the most recent [ForwardRequest] for this person, in
     * whichever of its three states it's currently in — `null` means this
     * person has never been forwarded (or was forwarded and the sender
     * cleared/acknowledged the outcome). The sending publisher's own screen
     * reads this id to show a live "Forward status: Pending/Accepted/Declined"
     * without a separate per-person lookup table. */
    val pendingForwardRequestId: String? = null,
    /** Same idea as [pendingForwardRequestId], for the same-congregation
     * "FORWARD TO OTHER PUBLISHER" flow (see [PublisherForwardRequest]) —
     * points at the most recent one for this person, `null` meaning never
     * forwarded to another publisher (or the sender cleared/acknowledged the
     * outcome). The two forward mechanisms are independent: a record can
     * have a pending request of each kind at once, though the UI only offers
     * one action at a time per kind (see PipelineScreen). */
    val pendingPublisherForwardRequestId: String? = null,
    /** "House Holder Assignment" module — points at the most recent
     * [HouseholderAssignment] for this person while one is outstanding
     * (`PENDING`), `null` once it's been accepted/rejected/cancelled and
     * acknowledged. Lets a Service Overseer/Admin/Super-Admin's own search
     * screen show "Assignment: Pending" against a record without a separate
     * per-person lookup, same convention as [pendingForwardRequestId]/
     * [pendingPublisherForwardRequestId] above. A record is only ever
     * "eligible" for a new assignment (see [HouseholderAssignment]'s own doc
     * comment on the ownership rule) when both this and [publisherPersonId]
     * are blank/null — never while an assignment is already outstanding. */
    val pendingHouseholderAssignmentId: String? = null,
) {
    val primarySupportingImage: SupportingImage? get() = supportingImages.firstOrNull()
    val hasGpsLocation: Boolean get() = gpsLat != null && gpsLng != null
}

/**
 * One preaching visit to an [InterestedPerson], logged at any pipeline stage
 * (spec §6.3; "Manage Returned Visit/Bible Study Module"'s own visit-history
 * mechanic reuses this same sub-collection rather than inventing a second
 * one per stage).
 *
 * Firestore collection: `interestedPeople/{interestedPersonId}/visits/{visitId}`
 */
@kotlinx.serialization.Serializable
data class Visit(
    @DocumentId val id: String = "",
    val interestedPersonId: String = "",
    val visitDate: Long = 0L,
    val visitTime: Long = 0L,
    /** "Notes / Visit Details" (spec §9) — what [topicDiscussed] already was;
     * kept as this field name rather than adding a redundant duplicate. Also
     * doubles as "Remarks/Topic Discussed" for a Return Visit/Bible Study
     * entry (spec's Visit History example). */
    val topicDiscussed: String? = null,
    val outcome: VisitOutcome = VisitOutcome.NOT_AT_HOME,
    /** Time consumed, in minutes (displayed as hh:mm). */
    val timeConsumedMinutes: Int = 0,
    /** "Visit Information" spec §9 — who conducted this visit ("Visited by"/
     * "Studied by" depending on [InterestedPerson.pipelineStage] at logging
     * time). Usually the owning [InterestedPerson.publisherPersonId], but
     * kept as its own field (not derived) since an Elder can log a visit on a
     * Publisher's behalf, same reasoning as [InterestedPerson.gpsCapturedBy]. */
    val publisherPersonId: String = "",
    /** Optional (spec §9: "if applicable") — when a follow-up is planned. */
    val followUpDate: Long? = null,
    val createdAt: Long = 0L,
    /** System-generated (spec §9) — the signed-in session that logged this
     * visit; may differ from [publisherPersonId] (see its doc comment). */
    val createdByPersonId: String = "",
    /** "House Holder Visit History" spec §6/§7 — this specific visit's own
     * coordinates, best-effort captured (same [com.emfitsolutions.gopreach
     * .data.location.LocationTracker]-backed, silently-optional pattern
     * [InterestedPerson.gpsLat]/[gpsLng] already use) at the moment a NEW
     * visit is logged — see [PipelineViewModel.saveVisit]. Deliberately its
     * own field, not a reference to the parent [InterestedPerson]'s current
     * GPS: a householder's own location can be re-captured/edited later, and
     * a visit's own coordinates must stay exactly what they were at that
     * visit regardless (spec: "must not be replaced by the House Holder's
     * current coordinates... do not replace a visit's coordinates with
     * another visit's coordinates"). `null` means never captured/unavailable
     * for this visit, not "same as the household's" — always shown as
     * "Not available", never silently falling back to another location. An
     * edit of an existing visit leaves both untouched (see `saveVisit`'s own
     * doc comment on preserving them). */
    val visitLat: Double? = null,
    val visitLng: Double? = null,
) {
    val hasVisitLocation: Boolean get() = visitLat != null && visitLng != null
}

/**
 * "Forward to Other Congregation" spec flow — a cross-congregation transfer
 * request for one [InterestedPerson], created from the Searching module.
 * Name/congregation snapshots are captured at request time so the receiving
 * Service Overseer's review screen and the sending publisher's status view
 * both render correctly even if the underlying Person/Congregation records
 * change later — the same "snapshot what mattered at the time" reasoning
 * this app already applies to [MonthlyReport.category].
 *
 * Firestore collection: `forwardRequests/{forwardRequestId}`
 */
@kotlinx.serialization.Serializable
data class ForwardRequest(
    @DocumentId val id: String = "",
    val interestedPersonId: String = "",
    val personNameSnapshot: String = "",
    val fromCongregationId: String = "",
    val fromCongregationNameSnapshot: String = "",
    val fromPublisherPersonId: String = "",
    val fromPublisherNameSnapshot: String = "",
    val toCongregationId: String = "",
    val toCongregationNameSnapshot: String = "",
    val status: ForwardRequestStatus = ForwardRequestStatus.PENDING,
    val requestedAt: Long = 0L,
    val respondedAt: Long? = null,
    val respondedByPersonId: String? = null,
    /** Set only on [ForwardRequestStatus.ACCEPTED] — who in the receiving
     * congregation the record was assigned to. */
    val assignedToPublisherPersonId: String? = null,
    val assignedToPublisherNameSnapshot: String? = null,
)

/**
 * "FORWARD TO OTHER PUBLISHER" spec flow — a same-congregation hand-off
 * request for one [InterestedPerson] (a Bible Study or Return Visit record),
 * directly between two publishers with no Service Overseer step: the sending
 * publisher already picked exactly who should receive it, so there's no
 * "assign to a publisher" step the way [ForwardRequest]'s cross-congregation
 * flow needs — only the receiving publisher themselves can [ACCEPT]/[DECLINE]
 * it. Name snapshots are captured at request time for the same reason
 * [ForwardRequest]'s are (see its doc comment).
 *
 * Admin/Elders/Super-Admin/Service Overseer can see these (read-only, scoped
 * to their own congregation via [congregationId]) alongside the cross-
 * congregation queue in [com.emfitsolutions.gopreach.ui.screens.pipeline
 * .ForwardRequestsScreen] — they never act on one; only the target publisher
 * does.
 *
 * Firestore collection: `publisherForwardRequests/{publisherForwardRequestId}`
 */
@kotlinx.serialization.Serializable
data class PublisherForwardRequest(
    @DocumentId val id: String = "",
    val interestedPersonId: String = "",
    val personNameSnapshot: String = "",
    /** Unchanged by this flow (both publishers are always in the same
     * congregation) — kept here anyway so Admin/Elder visibility can scope
     * the exact same way [ForwardRequest.toCongregationId] already does. */
    val congregationId: String = "",
    val fromPublisherPersonId: String = "",
    val fromPublisherNameSnapshot: String = "",
    val toPublisherPersonId: String = "",
    val toPublisherNameSnapshot: String = "",
    val status: ForwardRequestStatus = ForwardRequestStatus.PENDING,
    val requestedAt: Long = 0L,
    val respondedAt: Long? = null,
)

/** [HouseholderAssignment]'s own status track — deliberately not
 * [ForwardRequestStatus] (different name for the "declined" state, plus a
 * [COMPLETED] state neither forward flow has), matching the "House Holder
 * Assignment" spec's own exact five states verbatim. */
@kotlinx.serialization.Serializable
enum class HouseholderAssignmentStatus { PENDING, ACCEPTED, REJECTED, CANCELLED, COMPLETED }

/**
 * "House Holder Assignment" module — a Service Overseer/Admin/Super-Admin
 * directly assigning an *eligible* Interested Person/Return Visit/Bible
 * Study record (three labels for the one [InterestedPerson] entity at three
 * different [PipelineStage]s — see that enum) to a specific Publisher, who
 * must then Accept/Reject it before it actually becomes theirs. This is an
 * assignment/notification workflow, not a record-editing tool (spec's own
 * "Core Principle") — the underlying [InterestedPerson] stays the one
 * shared entity; every assignment fact (who assigned it, to whom, when,
 * accepted/rejected by whom and when, why rejected) lives here instead,
 * exactly the same "assignment info stored separately from the core record"
 * shape [ForwardRequest]/[PublisherForwardRequest] already use.
 *
 * "Important Record Ownership Rule" — the Service Overseer/Admin/Super-Admin
 * must never move/edit/delete/reassign a record a Publisher already owns.
 * Enforced structurally, not by a permission check on this model: a record
 * only ever becomes "eligible" for a new [HouseholderAssignment] once its
 * own [InterestedPerson.publisherPersonId] AND
 * [InterestedPerson.pendingHouseholderAssignmentId] are both blank/null (see
 * [com.emfitsolutions.gopreach.ui.screens.householderassignment
 * .HouseholderAssignmentViewModel.eligibleRecordsFor]) — a Publisher-owned
 * record never appears in that pool in the first place, so there is no
 * write path that could touch it.
 *
 * Name/congregation/address/barangay/notes are all snapshotted at
 * assignment time, same "the review screen must still render correctly even
 * if the underlying record changes later" reasoning [ForwardRequest]'s own
 * doc comment already gives; anything beyond the snapshot (current stage,
 * GPS, etc.) is read live off [interestedPersonId] instead, same as every
 * existing forward-request review screen already does.
 *
 * Firestore collection: `houseHolderAssignments/{assignmentId}`
 */
@kotlinx.serialization.Serializable
data class HouseholderAssignment(
    @DocumentId val id: String = "",
    val interestedPersonId: String = "",
    val personNameSnapshot: String = "",
    val recordType: PipelineStage = PipelineStage.SEARCHING,
    val congregationId: String = "",
    val congregationNameSnapshot: String = "",
    val barangaySnapshot: String? = null,
    val addressSnapshot: String? = null,
    val notesSnapshot: String? = null,
    /** The Service Overseer/Admin/Super-Admin who sent this assignment —
     * never a Publisher (spec: "Publisher... does not have access to create
     * assignments"). */
    val assignedByPersonId: String = "",
    val assignedByNameSnapshot: String = "",
    val toPublisherPersonId: String = "",
    val toPublisherNameSnapshot: String = "",
    val status: HouseholderAssignmentStatus = HouseholderAssignmentStatus.PENDING,
    val assignedAt: Long = 0L,
    val respondedAt: Long? = null,
    val respondedByPersonId: String? = null,
    val respondedByNameSnapshot: String? = null,
    /** Set only on [HouseholderAssignmentStatus.REJECTED] — spec's own
     * optional-reason list ("Already handling this house holder," "Not
     * available," ...) is presented by the UI as a picker; this stores
     * whichever text (a preset label, or "Other" free text) the Publisher
     * actually chose, `null` when they left it blank entirely (spec:
     * "Allow the Publisher to *optionally* provide a reason"). */
    val rejectionReason: String? = null,
    val cancelledAt: Long? = null,
    val cancelledByPersonId: String? = null,
)
