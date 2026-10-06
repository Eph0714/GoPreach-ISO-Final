package com.emfitsolutions.gopreach.ui.screens.dashboard

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.domain.duplicateNameKey

/** One named person behind a KPI card's headline number — spec: tapping "Total
 * Elders" shows e.g. "Henry Canales (Solano Tagalog Congregation)" for each
 * elder that number counts, not just the number itself. [statLabels] is every
 * [DashboardStatsUiState]-facing card label this one person counts toward
 * (e.g. a Regular Pioneer counts toward both "Total Publishers" and "Regular
 * Pioneers") — computed once from the same de-duplicated-by-person data
 * [CongregationStats.compute] uses, so the list a card's dialog shows always
 * matches the number on the card exactly (same source, same dedup, no risk of
 * the two silently drifting apart). */
data class StatMember(
    val fullName: String,
    val congregationId: String,
    val congregationName: String,
    val statLabels: Set<String>,
    /** "add also status, example 'EVAROSE FERNANDEZ (REGULAR PIONEER)'" —
     * this person's own specific role/category label(s) (e.g. "Regular
     * Pioneer", "Coordinator Elder"), as opposed to [statLabels], which are
     * the broader per-card *filter* keys ("Total Publishers", "Total
     * Elders", ...) a name is matched against to decide which dialogs it
     * belongs in. Usually one value; a person holding more than one
     * Elder-title role at once (e.g. Coordinator Elder AND, via a Group
     * Overseer slot, Regular Elder) keeps every one of them here, same
     * "union labels rather than pick one" rule [statLabels] itself follows
     * a few lines below. Empty only for a role this dashboard doesn't
     * resolve a specific status for. */
    val statuses: Set<String>,
)

/** Companion to [CongregationStats.compute] — same scoped/deduplicated
 * ACTIVE-assignment data, but resolved into named people instead of counts.
 *
 * Bug fix ("Total Elders shows 9 on the card but only 4 names in the
 * drill-down, same inconsistency on other cards"): this used to
 * `distinctBy { personId to congregationId }` *before* even looking at each
 * assignment's role — but a person routinely holds more than one ACTIVE
 * [RoleAssignment] document in the same congregation at once (e.g. a
 * separate Coordinator Elder doc and a separate Publisher doc, or a
 * Coordinator Elder doc plus a second Regular Elder doc from also being a
 * Group Overseer). `distinctBy` kept only whichever one of those documents
 * happened to come first and silently discarded the label(s) the others
 * would have contributed — so a real elder who also had, say, a Publisher
 * doc could vanish from "Total Elders"' list entirely (or vice versa for
 * "Total Publishers"/its category breakdowns) purely based on iteration
 * order, while [CongregationStats.compute]'s own count — built the correct
 * way, filtering to the relevant role *first* — never had that flaw. Fixed
 * by processing every assignment individually and merging labels per
 * (person, congregation) by union, so nobody's role ever gets dropped just
 * because they hold more than one assignment doc.
 */
fun computeStatMembers(
    congregations: List<Congregation>,
    assignments: List<RoleAssignment>,
    people: List<Person>,
): List<StatMember> {
    val peopleById = people.associateBy { it.id }
    val congregationsById = congregations.associateBy { it.id }

    data class PersonCongregationKey(val personId: String, val congregationId: String)
    val labelsByPersonCongregation = mutableMapOf<PersonCongregationKey, MutableSet<String>>()
    // "add also status, example 'EVAROSE FERNANDEZ (REGULAR PIONEER)'" —
    // this person's own specific role/category, collected alongside (but
    // kept separate from) the card-filter [labels] above; see [StatMember
    // .statuses]'s own doc comment for why the two aren't the same thing.
    val statusesByPersonCongregation = mutableMapOf<PersonCongregationKey, MutableSet<String>>()

    assignments
        .filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId in congregationsById }
        .forEach { assignment ->
            val congregationId = assignment.congregationId ?: return@forEach
            // Bug fix ("Super Admin login automatically closes app"):
            // [RoleAssignment.resolvedRoleType] THROWS for any roleType
            // string it can't parse (a legacy/corrupted document, or one
            // written under a since-renamed/removed AdminRole/
            // PublisherCategory enum constant) — see that function's own doc
            // comment. Called here over *every* congregation's assignments
            // (this dashboard is Super-Admin's unscoped, all-congregations
            // view), a single such row anywhere in the whole database used
            // to crash the dashboard the instant it loaded, immediately
            // after a correct login — exactly the "closes right after
            // Authenticate" symptom, and specifically a Super-Admin problem
            // since no other role's dashboard is ever exposed to every
            // congregation's data at once. [resolvedRoleTypeOrNull] never
            // throws; a row it can't parse is skipped (contributes no label)
            // instead of taking the whole dashboard down with it.
            val role = assignment.resolvedRoleTypeOrNull()
            val labels: Set<String> = when (role) {
                null -> emptySet()
                is RoleType.Admin -> when (role.role) {
                    // "Total Elders" counts Coordinator Elder, Regular Elder,
                    // and Service Overseer — every Elder-title role in the
                    // congregation. A person holding more than one of these
                    // (e.g. a Coordinator Elder who's also a Regular Elder
                    // via a Group Overseer assignment) still counts once;
                    // that dedup happens below (by personId, then by name).
                    AdminRole.COORDINATOR_ELDER, AdminRole.REGULAR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY -> setOf("Total Elders")
                    // "Total Ministerial" — same "use the same logic in
                    // counting elders" dedup, applied below alongside "Total
                    // Elders" rather than duplicating that block.
                    AdminRole.MINISTERIAL_SERVANT -> setOf("Total Ministerial")
                    else -> emptySet()
                }
                is RoleType.Publisher -> buildSet {
                    if (role.category != PublisherCategory.REMOVED_PUBLISHER) add("Total Publishers")
                    when (role.category) {
                        PublisherCategory.REGULAR_PIONEER -> add("Regular Pioneers")
                        // Its own breakdown line, same "keep the display/
                        // filter identity distinct" reasoning as every other
                        // Special Pioneer touch point in this app — not
                        // folded into "Regular Pioneers" here even though
                        // report eligibility treats the two the same
                        // (see PublisherCategory.SPECIAL_PIONEER's own doc
                        // comment).
                        PublisherCategory.SPECIAL_PIONEER -> add("Special Pioneers")
                        PublisherCategory.AUXILIARY_PIONEER -> add("Auxiliary Pioneers")
                        PublisherCategory.UNBAPTIZED_PUBLISHER -> add("Unbaptized Publishers")
                        PublisherCategory.IRREGULAR_PUBLISHER -> add("Irregular Publishers")
                        PublisherCategory.INACTIVE_PUBLISHER -> add("Inactive Publishers")
                        PublisherCategory.REPROOF_PUBLISHER -> add("Reproof Publishers")
                        PublisherCategory.REMOVED_PUBLISHER -> add("Removed Publishers")
                        PublisherCategory.REGULAR_PUBLISHER -> Unit
                    }
                }
            }
            if (labels.isEmpty()) return@forEach
            // The specific status shown next to the name — e.g. "Coordinator
            // Elder" rather than the umbrella "Total Elders" [labels] above
            // uses to decide *which* card this person belongs under.
            val status: String? = when (role) {
                is RoleType.Admin -> when (role.role) {
                    AdminRole.COORDINATOR_ELDER -> "Coordinator Elder"
                    AdminRole.REGULAR_ELDER -> "Regular Elder"
                    AdminRole.SERVICE_OVERSEER -> "Service Overseer"
                    AdminRole.SECRETARY -> "Secretary"
                    AdminRole.MINISTERIAL_SERVANT -> "Ministerial Servant"
                    else -> null
                }
                is RoleType.Publisher -> when (role.category) {
                    PublisherCategory.REGULAR_PIONEER -> "Regular Pioneer"
                    PublisherCategory.SPECIAL_PIONEER -> "Special Pioneer"
                    PublisherCategory.AUXILIARY_PIONEER -> "Auxiliary Pioneer"
                    PublisherCategory.UNBAPTIZED_PUBLISHER -> "Unbaptized Publisher"
                    PublisherCategory.IRREGULAR_PUBLISHER -> "Irregular Publisher"
                    PublisherCategory.INACTIVE_PUBLISHER -> "Inactive Publisher"
                    PublisherCategory.REPROOF_PUBLISHER -> "Reproof Publisher"
                    PublisherCategory.REMOVED_PUBLISHER -> "Removed Publisher"
                    PublisherCategory.REGULAR_PUBLISHER -> "Publisher"
                }
                null -> null
            }
            val key = PersonCongregationKey(assignment.personId, congregationId)
            labelsByPersonCongregation.getOrPut(key) { mutableSetOf() }.addAll(labels)
            if (status != null) statusesByPersonCongregation.getOrPut(key) { mutableSetOf() }.add(status)
        }

    val members = labelsByPersonCongregation.mapNotNull { (key, labels) ->
        val congregation = congregationsById[key.congregationId] ?: return@mapNotNull null
        // "Delete all unknown member in Total Elders, do not include them in
        // the count" — an ACTIVE RoleAssignment whose Person doc doesn't
        // exist (yet, or ever again — a deleted Person left an orphaned
        // assignment behind) is skipped here, not shown as a placeholder
        // "Unknown member" row. [countDistinctAdmins]/[CongregationStats]'s
        // publisher counting below now applies the exact same
        // person-must-exist requirement, so this list and the card's number
        // agree by construction — neither one counts what the other can't
        // show a real name for. See that function's doc comment for the
        // full history (this used to go the other way: counting orphans
        // in the number while dropping them from this list, which is where
        // the "9 on the card but 7 names" mismatch came from in the first
        // place — then a placeholder-name fix that made both agree but on
        // the *wrong* side, still counting entries nobody could identify;
        // this is the third and correct fix: neither side counts them).
        val person = peopleById[key.personId] ?: return@mapNotNull null
        StatMember(
            fullName = person.fullName,
            congregationId = congregation.id,
            congregationName = congregation.name,
            statLabels = labels,
            statuses = statusesByPersonCongregation[key].orEmpty(),
        )
    }

    // "Check the same name of the elder in a congregation and consider it
    // as one person" — keeps this drill-down list in lockstep with
    // [CongregationStats.compute]'s own name-deduped "Total Elders"/"Total
    // Ministerial" counts (same reasoning this function's own doc comment
    // already states: the dialog must always show exactly what the card's
    // number counts). Publisher-labeled entries are untouched — this app's
    // own person-level dedup already covers a genuine single Person doc
    // correctly; only these two Admin-role counts needed the *additional*
    // same-name-different-Person-doc rule spelled out here. Same label-union
    // fix as above applies here too: two duplicate-Person-doc entries for
    // the same name might hold different roles (e.g. one enrolled as
    // Coordinator Elder, a duplicate doc separately enrolled as Ministerial
    // Servant) — merge their labels rather than keeping only the first one's.
    val (adminMembers, otherMembers) = members.partition { "Total Elders" in it.statLabels || "Total Ministerial" in it.statLabels }
    val dedupedAdmins = adminMembers
        .groupBy { it.congregationId to it.fullName.trim().uppercase().replace(Regex("\\s+"), " ") }
        .map { (_, group) -> group.first().copy(statLabels = group.flatMap { it.statLabels }.toSet(), statuses = group.flatMap { it.statuses }.toSet()) }
    return otherMembers + dedupedAdmins
}

/**
 * One congregation's worth of the "Role-Based Dashboard... Graphical Reports"
 * spec's KPI list — computed live from current [RoleAssignment]/[MonthlyReport]
 * records (spec §15: "Calculate dashboard statistics directly from current
 * database records... update whenever records change"), never a cached/stale
 * summary field. Since every source Flow this is built from
 * (Congregation/RoleAssignment/MonthlyReport repositories) is already backed
 * by a live Firestore listener into the offline Room cache, recomputing this
 * on every emission is what "update whenever records change" means in
 * practice here — there's no separate write-time aggregation step to keep in
 * sync.
 */
data class CongregationStats(
    val congregationId: String,
    val congregationName: String,
    /** All active, non-removed publisher-track people (every [PublisherCategory]
     * except [PublisherCategory.REMOVED_PUBLISHER]) — the dashboard's own
     * "Total Publishers" KPI, distinct from [regularPublishers] below (spec §7
     * lists both "Total Publishers" and each category separately). */
    val totalPublishers: Int,
    /** Coordinator Elder + Regular Elder + Service Overseer (see
     * [ELDER_ROLES]) — every Elder-title role in the congregation, each
     * distinct person counted once even if they hold more than one of these
     * roles at once. Admin Per Congregation is still excluded (a purely
     * administrative role, not an Elder title). */
    val totalElders: Int,
    /** Ministerial Servant (see [MINISTERIAL_ROLES]) — same dedup rules as
     * [totalElders], kept as its own count since Ministerial Servant is a
     * distinct, non-Elder appointed position. */
    val totalMinisterial: Int,
    val regularPioneers: Int,
    /** "Add Special Pioneer publisher status category" — its own count, kept
     * distinct from [regularPioneers] for display/reporting even though
     * report eligibility treats the two identically (see
     * [com.emfitsolutions.gopreach.data.model.PublisherCategory
     * .SPECIAL_PIONEER]'s own doc comment). Already included in
     * [totalPublishers] above (that count only excludes REMOVED_PUBLISHER). */
    val specialPioneers: Int,
    val auxiliaryPioneers: Int,
    val regularPublishers: Int,
    val unbaptizedPublishers: Int,
    val inactivePublishers: Int,
    val removedPublishers: Int,
    val totalBibleStudies: Int,
    val regularPioneerHours: Double,
    val specialPioneerHours: Double,
    val auxiliaryPioneerHours: Double,
) {
    companion object {
        /** One [CongregationStats] per [congregations] entry, computed from the
         * full (unfiltered) [assignments]/[reports] lists — callers scope down
         * to "my congregation only" by filtering the *input* [congregations]
         * list first (see [DashboardStatsViewModel]), which is also exactly
         * where the role/scope security boundary belongs (spec §6: "prevent
         * cross-congregation access"). */
        fun compute(
            congregations: List<Congregation>,
            assignments: List<RoleAssignment>,
            reports: List<MonthlyReport>,
            people: List<Person> = emptyList(),
        ): List<CongregationStats> = congregations.map { congregation ->
            val peopleById = people.associateBy { it.id }
            val active = assignments.filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId == congregation.id }
            // distinctBy personId — a RoleAssignment is one document per role,
            // not one per person, so if the same person ever ends up with more
            // than one ACTIVE assignment matching the same bucket (a duplicate
            // enrollment, or a promotion flow that added a new assignment
            // instead of converting the old one), counting assignments directly
            // over-counts real, distinct people. This was a real, confirmed bug
            // (reported: "3 elders shown, only 2 actually enrolled"). Also
            // requires the Person doc to actually exist — same "don't count
            // an unknown member" rule [countDistinctAdmins] applies.
            // See computeStatMembers' matching comment — resolvedRoleTypeOrNull,
            // never the throwing resolvedRoleType, over data this broad.
            val publisherAssignments = active.filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher && it.personId in peopleById }
                .distinctBy { it.personId }
            fun countOf(category: PublisherCategory) = publisherAssignments.count {
                (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category == category
            }
            // Coordinator Elder + Regular Elder + Service Overseer (see
            // ELDER_ROLES/countDistinctAdmins) — a person holding more than
            // one of these at once still counts as one Elder. Deduped by
            // *name* too, not just personId — two separate Person docs for
            // the same real elder (a duplicate enrollment) still share one
            // full name within a congregation, and personId alone can't
            // catch that. "Total Ministerial" (MINISTERIAL_ROLES) uses the
            // exact same counting rules.
            val elderCount = countDistinctAdmins(active, people, ELDER_ROLES)
            val ministerialCount = countDistinctAdmins(active, people, MINISTERIAL_ROLES)
            val congregationReports = reports.filter { it.congregationId == congregation.id }
            CongregationStats(
                congregationId = congregation.id,
                congregationName = congregation.name,
                totalPublishers = publisherAssignments.count {
                    (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category != PublisherCategory.REMOVED_PUBLISHER
                },
                totalElders = elderCount,
                totalMinisterial = ministerialCount,
                regularPioneers = countOf(PublisherCategory.REGULAR_PIONEER),
                specialPioneers = countOf(PublisherCategory.SPECIAL_PIONEER),
                auxiliaryPioneers = countOf(PublisherCategory.AUXILIARY_PIONEER),
                regularPublishers = countOf(PublisherCategory.REGULAR_PUBLISHER),
                unbaptizedPublishers = countOf(PublisherCategory.UNBAPTIZED_PUBLISHER),
                inactivePublishers = countOf(PublisherCategory.INACTIVE_PUBLISHER),
                removedPublishers = countOf(PublisherCategory.REMOVED_PUBLISHER),
                totalBibleStudies = congregationReports.sumOf { it.bibleStudiesCount },
                regularPioneerHours = congregationReports.filter { it.category == PublisherCategory.REGULAR_PIONEER }.sumOf { it.hoursRendered ?: 0.0 },
                specialPioneerHours = congregationReports.filter { it.category == PublisherCategory.SPECIAL_PIONEER }.sumOf { it.hoursRendered ?: 0.0 },
                auxiliaryPioneerHours = congregationReports.filter { it.category == PublisherCategory.AUXILIARY_PIONEER }.sumOf { it.hoursRendered ?: 0.0 },
            )
        }

        /** The "All Congregations" KPI row (spec §3) for a Super-Admin viewing
         * everything at once — recomputed from the raw, global (already
         * congregation-scoped by the caller) [assignments]/[reports] lists, via
         * the exact same personId-dedup [compute] uses, rather than by summing
         * the already-computed per-congregation [CongregationStats.totalElders]
         * /publisher-category numbers. Summing was a real, confirmed bug: each
         * per-congregation count is already correctly deduped *within* that one
         * congregation, but a person holding an ACTIVE elder/publisher
         * assignment in **two different** congregations at once (still an edge
         * case worth fixing, not the common case) was then counted once per
         * congregation when those per-congregation totals were added together —
         * reported as "3 elders shown, only 2 enrolled" persisting even after
         * the per-congregation fix. Recomputing from scratch, globally, is the
         * only way to dedupe across congregation boundaries too. */
        fun total(
            congregations: List<Congregation>,
            assignments: List<RoleAssignment>,
            reports: List<MonthlyReport>,
            people: List<Person> = emptyList(),
        ): CongregationStats {
            val peopleById = people.associateBy { it.id }
            val congregationIds = congregations.map { it.id }.toSet()
            val active = assignments.filter { it.status == RoleAssignmentStatus.ACTIVE && it.congregationId in congregationIds }
            // See computeStatMembers' matching comment — resolvedRoleTypeOrNull,
            // never the throwing resolvedRoleType, over data this broad.
            val publisherAssignments = active.filter { it.resolvedRoleTypeOrNull() is RoleType.Publisher && it.personId in peopleById }
                .distinctBy { it.personId }
            fun countOf(category: PublisherCategory) = publisherAssignments.count {
                (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category == category
            }
            // Coordinator Elder + Regular Elder + Service Overseer (see
            // ELDER_ROLES) — a person holding more than one at once still
            // counts as one Elder. Deduped by name within each congregation
            // too — see [compute]'s matching comment for why personId alone
            // isn't enough. "Total Ministerial" (MINISTERIAL_ROLES) uses the
            // same per-congregation-then-summed approach.
            val elderCount = active
                .groupBy { it.congregationId }
                .entries.sumOf { (_, congregationAssignments) -> countDistinctAdmins(congregationAssignments, people, ELDER_ROLES) }
            val ministerialCount = active
                .groupBy { it.congregationId }
                .entries.sumOf { (_, congregationAssignments) -> countDistinctAdmins(congregationAssignments, people, MINISTERIAL_ROLES) }
            val scopedReports = reports.filter { it.congregationId in congregationIds }
            return CongregationStats(
                congregationId = "",
                congregationName = "All Congregations",
                totalPublishers = publisherAssignments.count {
                    (it.resolvedRoleTypeOrNull() as? RoleType.Publisher)?.category != PublisherCategory.REMOVED_PUBLISHER
                },
                totalElders = elderCount,
                totalMinisterial = ministerialCount,
                regularPioneers = countOf(PublisherCategory.REGULAR_PIONEER),
                specialPioneers = countOf(PublisherCategory.SPECIAL_PIONEER),
                auxiliaryPioneers = countOf(PublisherCategory.AUXILIARY_PIONEER),
                regularPublishers = countOf(PublisherCategory.REGULAR_PUBLISHER),
                unbaptizedPublishers = countOf(PublisherCategory.UNBAPTIZED_PUBLISHER),
                inactivePublishers = countOf(PublisherCategory.INACTIVE_PUBLISHER),
                removedPublishers = countOf(PublisherCategory.REMOVED_PUBLISHER),
                totalBibleStudies = scopedReports.sumOf { it.bibleStudiesCount },
                regularPioneerHours = scopedReports.filter { it.category == PublisherCategory.REGULAR_PIONEER }.sumOf { it.hoursRendered ?: 0.0 },
                specialPioneerHours = scopedReports.filter { it.category == PublisherCategory.SPECIAL_PIONEER }.sumOf { it.hoursRendered ?: 0.0 },
                auxiliaryPioneerHours = scopedReports.filter { it.category == PublisherCategory.AUXILIARY_PIONEER }.sumOf { it.hoursRendered ?: 0.0 },
            )
        }
    }
}

/** Every Elder-title [AdminRole] "Total Elders" counts: Coordinator Elder,
 * Regular Elder, Service Overseer, and Secretary (enrolled the same way,
 * Service-Overseer-equivalent access — see that role's own doc comment).
 * Ministerial Servant is deliberately not included — spec's own wording
 * calls it out as "Not an Elder — a distinct appointed position," even
 * though it's enrolled the same way (see [MINISTERIAL_ROLES]/"Total
 * Ministerial" — its own, separate count, same dedup logic). */
private val ELDER_ROLES = setOf(AdminRole.COORDINATOR_ELDER, AdminRole.REGULAR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY)

/** "Total Ministerial" — Ministerial Servant, counted with the exact same
 * dedup rules as [ELDER_ROLES]/"Total Elders" (per explicit request: "use
 * the same logi[c] in counting elders"). Kept as its own role set/card
 * rather than folded into "Total Elders" since Ministerial Servant is a
 * distinct, non-Elder appointed position (see [ELDER_ROLES]'s doc comment). */
private val MINISTERIAL_ROLES = setOf(AdminRole.MINISTERIAL_SERVANT)

/** Counts distinct people holding any of [roles] among [assignments]
 * (already filtered to one congregation/ACTIVE) — first collapsing multiple
 * RoleAssignment docs for the same personId (the same real person holding,
 * say, both a Coordinator Elder assignment and a separate Regular Elder
 * assignment from also being a Group Overseer counts once, not twice), then
 * collapsing distinct Person docs that share a name (see [duplicateNameKey]'s
 * doc comment). An assignment whose Person doc isn't in [people] — in-flight
 * sync, or an ACTIVE assignment orphaned by a since-deleted Person — is
 * excluded entirely rather than counted under a placeholder: "delete all
 * unknown member in Total Elders, do not include them in the count" (see
 * [computeStatMembers]'s matching doc comment, which applies the identical
 * rule to the drill-down list this number's dialog shows, so the two can
 * never drift apart again in either direction). Shared by both "Total
 * Elders" ([ELDER_ROLES]) and "Total Ministerial" ([MINISTERIAL_ROLES]) —
 * same counting rules, different role set. */
private fun countDistinctAdmins(assignments: List<RoleAssignment>, people: List<Person>, roles: Set<AdminRole>): Int {
    val peopleById = people.associateBy { it.id }
    return assignments
        .filter { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role in roles }
        .mapNotNull { peopleById[it.personId] }
        .map { it.duplicateNameKey() }
        .distinct()
        .size
}
