package com.emfitsolutions.gopreach.data.repository

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app reads the very JSON the server's comparison engine writes (backend/src/sheetCompare.js): this payload was produced by that engine for the
 * same worksheet its own tests use, so the website, the backend and the app are shown to agree on the same numbers and the same spoken sentences.
 */
class ComparisonPayloadTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val payload = """{"available":true,"message":"","groups":[{"id":"g1","kind":"PERIODS","title":"Reporting periods: Jan-Jun 2026, Jul-Oct 2026","columns":[{"key":"1","label":"Jan-Jun 2026"},{"key":"2","label":"Jul-Oct 2026"}],"indicators":8,"recommended":{"previous":"1","current":"2"}}],"report":{"title":"Reporting periods: Jan-Jun 2026 compared with Jul-Oct 2026","kind":"PERIODS","scope":{"congregation":"Alpha","fileName":"visit.xlsx","sheet":"Sheet1","previous":"Jan-Jun 2026","current":"Jul-Oct 2026"},"counts":{"indicators":8,"compared":7,"increased":5,"decreased":1,"unchanged":1,"unavailable":1},"executive":["7 of 8 indicators could be compared between Jan-Jun 2026 and Jul-Oct 2026: 5 increased, 1 decreased and 1 did not change.","1 indicator has no value in one of the two columns and is not compared.","The largest increase is inactive publishers increased from 5 to 8, an increase of 3, or 60 percent.","The largest decrease is bible studies decreased from 40 to 31, a decrease of 9, or 22.5 percent."],"table":[{"label":"Publishers","total":false,"unit":"","polarity":1,"previous":80,"current":84,"previousCell":"B4","currentCell":"C4","difference":4,"percent":5,"direction":"increase","note":""},{"label":"Regular pioneers","total":false,"unit":"","polarity":1,"previous":12,"current":15,"previousCell":"B5","currentCell":"C5","difference":3,"percent":25,"direction":"increase","note":""},{"label":"Auxiliary pioneers","total":false,"unit":"","polarity":1,"previous":6,"current":6,"previousCell":"B6","currentCell":"C6","difference":0,"percent":0,"direction":"unchanged","note":""},{"label":"Bible studies","total":false,"unit":"","polarity":1,"previous":40,"current":31,"previousCell":"B7","currentCell":"C7","difference":-9,"percent":-22.5,"direction":"decrease","note":""},{"label":"Inactive publishers","total":false,"unit":"","polarity":-1,"previous":5,"current":8,"previousCell":"B8","currentCell":"C8","difference":3,"percent":60,"direction":"increase","note":""},{"label":"New publishers","total":false,"unit":"","polarity":1,"previous":0,"current":4,"previousCell":"B9","currentCell":"C9","difference":4,"percent":null,"direction":"increase","note":"No percentage: the previous value was 0"},{"label":"Meeting attendance average","total":false,"unit":"","polarity":1,"previous":95,"current":null,"previousCell":"B10","currentCell":null,"difference":null,"percent":null,"direction":"unavailable","note":"No value in the current column"},{"label":"Total","total":true,"unit":"","polarity":0,"previous":100,"current":110,"previousCell":"B11","currentCell":"C11","difference":10,"percent":10,"direction":"increase","note":""}],"improvements":["Publishers","Regular pioneers","New publishers"],"attention":["Bible studies","Inactive publishers"],"otherChanges":["Total"],"unchanged":["Auxiliary pioneers"],"limitations":["1 indicator missing a value in one of the two columns: Meeting attendance average.","Improvement and attention are only named for indicators whose meaning says so (for example more publishers or fewer inactive); every other change is shown neutrally.","This compares the numbers saved in an imported historical Excel file; they are not verified live GoPreach reports."],"method":["Indicators were matched by name between the reporting period \"Jan-Jun 2026\" (earlier) and the reporting period \"Jul-Oct 2026\" (later), using the numbers saved in the worksheet. Blank cells are treated as not available, never as zero.","8 indicators analysed.","The original spreadsheet is never changed."],"chart":{"type":"bar","previousLabel":"Jan-Jun 2026","currentLabel":"Jul-Oct 2026","series":[{"label":"Publishers","previous":80,"current":84},{"label":"Regular pioneers","previous":12,"current":15},{"label":"Auxiliary pioneers","previous":6,"current":6},{"label":"Bible studies","previous":40,"current":31},{"label":"Inactive publishers","previous":5,"current":8},{"label":"New publishers","previous":0,"current":4}]},"voice":["Reporting periods: Jan-Jun 2026 compared with Jul-Oct 2026.","Congregation: Alpha. File: visit.xlsx. Worksheet: Sheet1.","7 of 8 indicators could be compared between Jan-Jun 2026 and Jul-Oct 2026: 5 increased, 1 decreased and 1 did not change.","1 indicator has no value in one of the two columns and is not compared.","The largest increase is inactive publishers increased from 5 to 8, an increase of 3, or 60 percent.","The largest decrease is bible studies decreased from 40 to 31, a decrease of 9, or 22.5 percent.","Important changes.","Inactive publishers increased from 5 to 8, an increase of 3, or 60 percent.","Regular pioneers increased from 12 to 15, an increase of 3, or 25 percent.","Bible studies decreased from 40 to 31, a decrease of 9, or 22.5 percent.","Publishers increased from 80 to 84, an increase of 4, or 5 percent.","New publishers increased from 0 to 4, an increase of 4; a percentage cannot be given because the previous value was 0.","Key improvements: Publishers, Regular pioneers, New publishers.","Areas for attention: Bible studies, Inactive publishers.","1 indicator did not change: Auxiliary pioneers.","1 indicator missing a value in one of the two columns: Meeting attendance average.","This report compares the file visit.xlsx, matching indicators by name."]}}"""

    private fun comparison() = json.decodeFromString(Comparison.serializer(), payload)

    @Test fun theAppDecodesWhatTheServerSends() {
        val c = comparison()
        assertTrue(c.available)
        assertEquals("PERIODS", c.groups.single().kind)
        assertEquals(listOf("Jan-Jun 2026", "Jul-Oct 2026"), c.groups.single().columns.map { it.label })
        val r = assertNotNull(c.report)
        assertEquals("visit.xlsx", r.scope.fileName)
        assertEquals(8, r.counts.indicators)
        assertEquals(1, r.counts.unavailable)
    }

    @Test fun theNumbersAreTheServersNumbers() {
        val r = assertNotNull(comparison().report)
        val pioneers = r.table.first { it.label == "Regular pioneers" }
        assertEquals(3.0, pioneers.difference); assertEquals(25.0, pioneers.percent); assertEquals("increase", pioneers.direction)
        val studies = r.table.first { it.label == "Bible studies" }
        assertEquals(-9.0, studies.difference); assertEquals(-22.5, studies.percent)
        val fresh = r.table.first { it.label == "New publishers" }
        assertEquals(0.0, fresh.previous); assertNull(fresh.percent)
        val attendance = r.table.first { it.label == "Meeting attendance average" }
        assertEquals("unavailable", attendance.direction); assertNull(attendance.current); assertNull(attendance.difference)
        assertTrue("Regular pioneers" in r.improvements && "Bible studies" in r.attention && "Auxiliary pioneers" in r.unchanged)
    }

    @Test fun whatIsReadAloudIsTheServersSentences() {
        val voice = assertNotNull(comparison().report).voice
        assertTrue("Regular pioneers increased from 12 to 15, an increase of 3, or 25 percent." in voice)
        assertTrue(voice.first().startsWith("Reporting periods: Jan-Jun 2026 compared with Jul-Oct 2026"))
        assertTrue(voice.none { "undefined" in it || "NaN" in it || "null" in it })
    }

    @Test fun anAnswerWithNothingToCompareStaysEmpty() {
        val c = json.decodeFromString(Comparison.serializer(), """{"available":false,"message":"No comparable records were found in this Excel file.","groups":[],"report":null}""")
        assertTrue(!c.available && c.report == null && c.groups.isEmpty() && c.message.startsWith("No comparable"))
    }
}
