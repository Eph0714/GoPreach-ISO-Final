package com.emfitsolutions.gopreach.ui.screens.circuit

import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.domain.PersonDates

/** The congregation heading printed above its publishers: name, Coordinator Elder(s) and Congregation Code. */
data class CongregationHeading(
    val congregationId: String,
    val name: String,
    val code: String,
    /** Active Coordinator Elder name(s), "—" if the congregation has none. */
    val coordinator: String,
)

/** Counts for a set of publishers: the total, and one count per category (zeros included, Removed excluded). */
data class PublisherTotals(val total: Int, val byCategory: List<Pair<PublisherCategory, Int>>) {
    /** "Total Publishers: 91 · Regular Pioneer: 23 · …" */
    fun summaryLine(): String = (listOf("Total Publishers: $total") + byCategory.map { (c, n) -> "${c.displayName}: $n" }).joinToString("  ·  ")
}

private val TOTAL_CATEGORIES = PublisherCategory.entries.filter { it != PublisherCategory.REMOVED_PUBLISHER }

fun publisherTotals(rows: List<CircuitPersonRow>): PublisherTotals =
    PublisherTotals(rows.size, TOTAL_CATEGORIES.map { c -> c to rows.count { it.category == c } })

/** One heading + its publishers + its totals (a printed page / an Excel block). */
data class PublisherReportSection(val heading: CongregationHeading, val rows: List<CircuitPersonRow>, val totals: PublisherTotals)

/** One section per congregation present in [rows], ordered by congregation name. */
fun publisherSections(rows: List<CircuitPersonRow>, headings: List<CongregationHeading>): List<PublisherReportSection> {
    val byId = headings.associateBy { it.congregationId }
    return rows.groupBy { it.congregationId }
        .map { (id, group) ->
            val heading = byId[id] ?: CongregationHeading(id, group.first().congregationName, "", "—")
            PublisherReportSection(heading, group, publisherTotals(group))
        }
        .sortedBy { it.heading.name }
}

private fun dash(value: String?) = value?.takeIf { it.isNotBlank() } ?: "—"

fun addressOf(p: Person?): String? {
    if (p == null) return null
    val parts = listOf(p.address, p.barangay, p.cityMunicipality, p.province).filter { !it.isNullOrBlank() }
    return parts.joinToString(", ").ifBlank { null }
}

/** One column of the publisher table / printout / spreadsheet: title, on-screen width and what it shows. */
class PublisherColumn(val title: String, val width: Int, val value: (CircuitPersonRow, Int) -> String)

/** Every field of the publisher record a Circuit Overseer may see (never the sign-in details). */
val PUBLISHER_COLUMNS: List<PublisherColumn> = listOf(
    PublisherColumn("No.", 44) { _, i -> (i + 1).toString() },
    PublisherColumn("Name", 190) { r, _ -> r.name },
    PublisherColumn("Category", 140) { r, _ -> r.categoryLabel },
    PublisherColumn("Congregation", 190) { r, _ -> r.congregationName },
    PublisherColumn("FS Group", 110) { r, _ -> dash(r.groupName) },
    PublisherColumn("Status", 90) { r, _ -> r.status },
    PublisherColumn("Gender", 80) { r, _ -> dash(r.person?.gender?.name?.lowercase()?.replaceFirstChar { it.uppercase() }) },
    PublisherColumn("Age", 50) { r, _ -> r.person?.birthdate?.let { PersonDates.age(it)?.toString() } ?: "—" },
    PublisherColumn("Birthdate", 110) { r, _ -> r.person?.birthdate?.let { PersonDates.format(it) } ?: "—" },
    PublisherColumn("Baptized", 110) { r, _ -> r.person?.baptismalDate?.let { PersonDates.format(it) } ?: "—" },
    PublisherColumn("Contact", 120) { r, _ -> dash(r.contact) },
    PublisherColumn("Email", 190) { r, _ -> dash(r.person?.email) },
    PublisherColumn("Address", 260) { r, _ -> dash(addressOf(r.person)) },
    PublisherColumn("Contact Person", 150) { r, _ -> dash(r.person?.contactPerson) },
    PublisherColumn("Contact Person No.", 140) { r, _ -> dash(r.person?.contactPersonNumber) },
    PublisherColumn("Available Days", 170) { r, _ ->
        dash(r.person?.preachingAvailableDays?.joinToString(", ") { it.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3) })
    },
    PublisherColumn("Availability Notes", 200) { r, _ -> dash(r.person?.preachingAvailabilityRemarks) },
    PublisherColumn("Remarks", 220) { r, _ -> dash(r.person?.remarks) },
)

/** The columns for a printout: the Congregation column is dropped (every section is already headed by its congregation). */
val PUBLISHER_EXPORT_COLUMNS: List<PublisherColumn> = PUBLISHER_COLUMNS.filter { it.title != "Congregation" }
