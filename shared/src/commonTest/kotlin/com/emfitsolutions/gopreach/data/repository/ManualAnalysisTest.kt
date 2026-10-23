package com.emfitsolutions.gopreach.data.repository

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app reads the server's analysis as it is sent (same settings as the repository): missing values stay missing, unknown fields are ignored. */
class ManualAnalysisTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

    private val sample = """
    {"available":true,"generatedAt":"2026-10-23T08:14:00.000Z","futureField":1,
     "source":{"fileName":"visit.xlsx","sheet":"Visit","congregation":"Alpha Congregation","savedAt":1792754192965},
     "group":{"id":"g1","kind":"PERIODS","title":"Reporting periods: March, June"},
     "groups":[{"id":"g1","kind":"PERIODS","title":"t","columns":[{"key":"1","label":"March 2025"},{"key":"2","label":"June 2025"}],"indicators":3,"recommended":{"previous":"1","current":"2"}}],
     "selection":{"previous":"1","current":"2"},"periods":[{"key":"1","label":"March 2025"},{"key":"2","label":"June 2025"}],
     "report":{"title":"Reporting periods: March 2025 compared with June 2025","scope":{"congregation":"Alpha","fileName":"visit.xlsx","sheet":"Visit","previous":"March 2025","current":"June 2025"},
       "counts":{"indicators":2,"compared":1,"increased":1,"decreased":0,"unchanged":0,"unavailable":1},"executive":["One indicator increased."],
       "table":[{"label":"Publishers","total":false,"unit":"","polarity":1,"previous":80,"current":84,"previousCell":"B2","currentCell":"C2","difference":4,"percent":5,"direction":"increase","note":""},
                {"label":"Auxiliary Pioneers","total":false,"unit":"","polarity":1,"previous":6,"current":null,"previousCell":"B3","currentCell":null,"difference":null,"percent":null,"direction":"unavailable","note":"No value in the current column"}],
       "voice":["Spoken sentence."],"method":["Matched by name."],"limitations":[]},
     "cards":[{"label":"Publishers","unit":"","polarity":1,"direction":"increase","previous":80,"current":84,"difference":4,"percent":5,"values":[{"period":"March 2025","value":80},{"period":"June 2025","value":84}]}],
     "charts":[{"id":"chart-count","kind":"count","title":"Counts by reporting period","unit":"count","periods":["March 2025","June 2025"],"series":[{"label":"Auxiliary Pioneers","values":[6,null]},{"label":"Publishers","values":[80,84]}],"omitted":0,"note":"n"}],
     "findings":[{"id":"f1","kind":"fact","priority":1,"text":"Publishers increased from 80 to 84.","explanation":"Plain words.","evidence":[{"label":"Publishers","period":"March 2025","value":80,"cell":"B2"}]}],
     "quality":{"notes":["1 cell holds text."],"rows":2},"executive":["Publishers rose from 80 to 84."],"narration":["Summary."],
     "ai":{"status":"OK","provider":"ollama","model":"qwen2.5:7b-instruct","message":"","usedFor":["executive","findings","narration"]}}
    """.trimIndent()

    @Test fun theServersAnalysisIsReadAsSent() {
        val a = json.decodeFromString(ManualAnalysis.serializer(), sample)
        assertTrue(a.available); assertTrue(a.aiOk)
        assertEquals(listOf("March 2025", "June 2025"), a.periods.map { it.label })
        assertEquals(84.0, a.cards.single().current); assertEquals(5.0, a.cards.single().percent)
        assertEquals(listOf(6.0, null), a.charts.single().series.first().values, "a missing value stays missing, never zero")
        val missing = a.report!!.table.last()
        assertNull(missing.current); assertNull(missing.difference); assertEquals("unavailable", missing.direction)
        assertEquals("B2", a.findings.single().evidence.single().cell)
        assertEquals("Plain words.", a.findings.single().explanation)
        assertEquals("Spoken sentence.", a.report!!.voice.single())
    }

    @Test fun anAnalysisWithoutComparableDataOrWithAFailedAiIsNeverReadAsSuccess() {
        val none = json.decodeFromString(ManualAnalysis.serializer(), """{"available":false,"message":"no valid comparison","ai":{"status":"SKIPPED","message":""}}""")
        assertFalse(none.available); assertFalse(none.aiOk); assertTrue(none.cards.isEmpty())
        val failed = json.decodeFromString(ManualAnalysis.serializer(), """{"available":true,"ai":{"status":"REJECTED","message":"The AI stated 40, which GoPreach did not calculate.","usedFor":[]}}""")
        assertFalse(failed.aiOk); assertTrue(failed.ai.message.contains("did not calculate"))
        val absent = json.decodeFromString(ManualAnalysis.serializer(), """{"available":true}""")
        assertEquals("NOT_REQUESTED", absent.ai.status)
    }
}
