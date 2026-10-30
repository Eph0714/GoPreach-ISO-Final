package com.emfitsolutions.gopreach.ui.screens

import com.emfitsolutions.gopreach.data.model.FsGroupReportSnapshot
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportRow
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportSheet
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.StatusColumn
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.summaryRows
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.toSheet
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.toSnapshotReport
import com.emfitsolutions.gopreach.ui.screens.groupmodules.groupMemberIds
import com.emfitsolutions.gopreach.ui.screens.groupmodules.officerSlotOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FS Group of an officer (for Publisher Assignment) and Monthly Report Snapshots (the saved values, kept exactly). */
class GroupModulesTest {
    private val g1 = Group(id = "g1", congregationId = "c1", name = "Group One", overseerPersonId = "ovr", servantPersonId = "srv", assistantPersonId = "ast", status = RecordStatus.ACTIVE)
    private val g2 = Group(id = "g2", congregationId = "c1", name = "Group Two", overseerPersonId = "ovr2", status = RecordStatus.ACTIVE)

    private fun publisherRole(person: String, group: String, category: PublisherCategory = PublisherCategory.REGULAR_PUBLISHER, status: RoleAssignmentStatus = RoleAssignmentStatus.ACTIVE) =
        RoleAssignment(id = "ra_$person", personId = person, roleType = RoleType.serialize(RoleType.Publisher(category)), congregationId = "c1", groupId = group, status = status)

    @Test
    fun `the group of an officer is read from the slots and an ordinary member has none`() {
        assertEquals("Group Overseer", officerSlotOf("ovr", "c1", listOf(g1, g2))?.label)
        assertEquals("Group Servant", officerSlotOf("srv", "c1", listOf(g1, g2))?.label)
        assertEquals("Group Assistant", officerSlotOf("ast", "c1", listOf(g1, g2))?.label)
        assertEquals("g2", officerSlotOf("ovr2", "c1", listOf(g1, g2))?.group?.id)
        assertNull(officerSlotOf("mine", "c1", listOf(g1, g2)))
        assertNull(officerSlotOf("", "c1", listOf(g1, g2)))
        assertNull("another congregation", officerSlotOf("ovr", "c2", listOf(g1, g2)))
        assertNull("an inactive group", officerSlotOf("ovr", "c1", listOf(g1.copy(status = RecordStatus.INACTIVE))))
    }

    @Test
    fun `the publishers of a group are its active publishers and its officers, never a removed one or another group's`() {
        val roles = listOf(
            publisherRole("mine", "g1"), publisherRole("other", "g2"), publisherRole("gone", "g1", PublisherCategory.REMOVED_PUBLISHER),
            publisherRole("old", "g1", status = RoleAssignmentStatus.INACTIVE),
        )
        assertEquals(setOf("ovr", "srv", "ast", "mine"), groupMemberIds(g1, roles))
        assertEquals(setOf("ovr2", "other"), groupMemberIds(g2, roles))
    }

    private fun sheet() = FieldServiceReportSheet(
        groupName = "Group One", monthLabel = "September 2026", isRange = false, overseer = "Gil Overseer", servant = "None", assistant = "Ada Assistant",
        reportColumns = listOf(StatusColumn.PUB, StatusColumn.RP), hourColumns = listOf(StatusColumn.RP),
        rows = listOf(
            FieldServiceReportRow(1, StatusColumn.PUB, "InGroup, Mia", 1, null, 2, "moved", personId = "mine"),
            FieldServiceReportRow(2, StatusColumn.RP, "Pioneer, Pat", 1, 40.5, 1, "", personId = "pat"),
            FieldServiceReportRow(3, StatusColumn.PUB, "Silent, Sy", 1, null, null, "", personId = "sy", hasReport = false),
        ),
    )

    private fun snapshotOf(s: FieldServiceReportSheet) = FsGroupReportSnapshot(
        id = "g1_202609", congregationId = "c1", congregationName = "Alpha", groupId = "g1", groupName = "Group One", year = 2026, month = 9, savedByPersonId = "ovr", savedByName = "Gil", savedAt = 1L,
        report = s.toSnapshotReport(),
    )

    @Test
    fun `a snapshot keeps the sheet exactly as it was and shows it again unchanged`() {
        val live = sheet()
        val snap = snapshotOf(live)
        // the original report changes afterwards: the snapshot does not
        val edited = live.copy(rows = live.rows.map { it.copy(bibleStudies = 99) })
        assertEquals(3, snap.report.totals.studies)
        assertEquals(297, edited.rows.sumOf { it.bibleStudies ?: 0 })
        val back = snap.toSheet()
        assertEquals(live.rows, back.rows)
        assertEquals(live.reportColumns, back.reportColumns)
        assertEquals(live.hourColumns, back.hourColumns)
        assertEquals("Group One", back.groupName)
        assertEquals("September 2026", back.monthLabel)
        assertEquals("Gil Overseer", back.overseer)
        assertEquals("Ada Assistant", back.assistant)
        assertEquals(live.countLine, back.countLine)
        assertEquals(live.reportCount(StatusColumn.PUB), back.reportCount(StatusColumn.PUB))
        assertEquals(live.totalHours(StatusColumn.RP), back.totalHours(StatusColumn.RP), 0.0)
    }

    @Test
    fun `the saved totals and summary are the report's own`() {
        val t = snapshotOf(sheet()).report.totals
        assertEquals(3, t.publishers)
        assertEquals(2, t.participated)
        assertEquals(1, t.pending)
        assertEquals(3, t.reports)
        assertEquals(40.5, t.hours, 0.0)
        assertEquals(3, t.studies)
        val rows = snapshotOf(sheet()).summaryRows()
        assertEquals(listOf("Metric", "Value"), rows[0])
        assertEquals("3", rows.first { it[0] == "Total Records" }[1])
        assertEquals("40.5", rows.first { it[0] == "Total Hours (pioneers)" }[1])
    }

    @Test
    fun `the snapshot id is the group and the month, so a second one for the period cannot exist`() {
        assertEquals("g1_202609", FsGroupReportSnapshot.idFor("g1", 2026, 9))
        assertEquals("g1_202612", FsGroupReportSnapshot.idFor("g1", 2026, 12))
        assertEquals(snapshotOf(sheet()).id, FsGroupReportSnapshot.idFor("g1", 2026, 9))
    }

    @Test
    fun `the website's snapshot document reads back and ours reads on the website`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        // what the website writes (summary values are strings, hours may be a whole number or null)
        val fromWeb = """{"id":"g1_202609","congregationId":"c1","congregationName":"Alpha","groupId":"g1","groupName":"Group One","year":2026,"month":9,"periodMonth":1,"savedByPersonId":"ovr","savedByName":"Gil",
            "savedByRole":"Group Overseer","savedAt":1790000000000,"sourceReports":2,"sourceVersion":5,"extra":"x",
            "report":{"sheet":{"name":"Group One","title":"Report for the Month of September 2026","officers":[["Group Overseer:","Gil"],["Group Servant:","None"]],"columns":["UP","PUB","AP","RP"],"hourColumns":["AP","RP"],
            "lines":[{"number":1,"status":"RP","personId":"p","name":"Pioneer, Pat","reports":1,"hasReport":true,"hours":40,"studies":2,"remarks":""},{"number":2,"status":"PUB","personId":"q","name":"Q","reports":1,"hasReport":false,"hours":null,"studies":null,"remarks":""}],"participated":1},
            "summary":[["Total Records","2"],["Total Hours (pioneers)","40"]],"totals":{"publishers":2,"participated":1,"pending":1,"reports":2,"hours":40,"studies":2}}}"""
        val snap = json.decodeFromString(FsGroupReportSnapshot.serializer(), fromWeb)
        assertEquals(40.0, snap.report.totals.hours, 0.0)
        val sheet = snap.toSheet()
        assertEquals(listOf(StatusColumn.UP, StatusColumn.PUB, StatusColumn.AP, StatusColumn.RP), sheet.reportColumns)
        assertEquals(2, sheet.rows.size)
        assertFalse(sheet.rows[1].hasReport)
        assertNull(sheet.rows[1].hours)
        assertEquals(40.0, sheet.rows[0].hours!!, 0.0)
        // and the other way round: the app's own document decodes again with the same values
        val mine = snapshotOf(sheet())
        val again = json.decodeFromString(FsGroupReportSnapshot.serializer(), json.encodeToString(FsGroupReportSnapshot.serializer(), mine))
        assertEquals(mine, again)
        assertNotNull(again.report.sheet.lines.firstOrNull { it.personId == "sy" })
    }
}
