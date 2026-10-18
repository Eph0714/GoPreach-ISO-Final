package com.emfitsolutions.gopreach.ui.screens.publishers

import com.emfitsolutions.gopreach.data.model.displayName
import com.emfitsolutions.gopreach.domain.PersonDates

/** One column of the Publisher record: its heading, its width on screen (dp) and how a publisher's value is written. */
class PublisherColumn(val title: String, val width: Int, val value: (PublisherRow) -> String)

/**
 * The ONE list of everything the Publisher Module shows about a publisher. The Table View, the List View card and the Print / PDF / Excel output
 * all read it, so what is printed is exactly what the table shows, and a field added here appears in all of them. A blank value stays blank.
 */
object PublisherColumns {
    private fun statusLabel(r: PublisherRow) = r.person.accountStatus.name.lowercase().replaceFirstChar { it.uppercase() }

    /** House / street, barangay, city or municipality and province, written in one line, skipping what is blank. */
    fun addressOf(r: PublisherRow): String {
        val p = r.person
        return listOf(p.address, p.barangay.orEmpty(), p.cityMunicipality.orEmpty(), p.province.orEmpty())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }

    val all: List<PublisherColumn> = listOf(
        PublisherColumn("Name", 200) { it.person.fullName },
        PublisherColumn("Gender", 80) { it.person.gender?.name?.lowercase()?.replaceFirstChar { c -> c.uppercase() }.orEmpty() },
        PublisherColumn("Birthdate", 130) { PersonDates.format(it.person.birthdate) },
        PublisherColumn("Age", 60) { PersonDates.yearsText(PersonDates.age(it.person.birthdate)) },
        PublisherColumn("Baptismal Date", 130) { PersonDates.format(it.person.baptismalDate) },
        PublisherColumn("Baptismal Age", 100) { PersonDates.yearsText(PersonDates.baptismalAge(it.person.birthdate, it.person.baptismalDate)) },
        PublisherColumn("FS Group", 120) { it.groupName },
        PublisherColumn("Category", 160) { it.category.displayName },
        PublisherColumn("Account Status", 120) { statusLabel(it) },
        PublisherColumn("Contact", 140) { it.person.contact },
        PublisherColumn("Email", 200) { it.person.email.orEmpty() },
        PublisherColumn("Address", 300) { addressOf(it) },
        PublisherColumn("Contact Person", 170) { it.person.contactPerson.orEmpty() },
        PublisherColumn("Contact Person No.", 150) { it.person.contactPersonNumber.orEmpty() },
        PublisherColumn("Username", 140) { it.person.username },
        PublisherColumn("Remarks", 260) { it.person.remarks?.trim().orEmpty() },
    )

    /** Every column except the name, as "Heading: value" lines for the List View card (blank values are left out). */
    fun detailLines(r: PublisherRow): List<String> =
        all.drop(1).mapNotNull { c -> c.value(r).takeIf { it.isNotBlank() }?.let { c.title + ": " + it } }

    /** The grouped view orders groups by name; a publisher without a group goes under this heading. */
    fun groupHeading(r: PublisherRow): String = r.groupName.ifBlank { "No FS Group" }
}
