package com.emfitsolutions.gopreach.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MidweekHeadingLanguageTest {
    @Test fun englishKeepsTheOriginalHeadings() {
        assertEquals(
            listOf("TREASURES FROM GOD'S WORD", "APPLY YOURSELF TO THE FIELD MINISTRY", "LIVING AS CHRISTIANS"),
            MidweekSection.entries.map { it.heading(MidweekHeadingLanguage.ENGLISH) },
        )
    }

    @Test fun tagalogAndIlokoHeadingsAreTheOnesTheCongregationsPrint() {
        assertEquals(
            listOf("KAYAMANAN MULA SA SALITA NG DIYOS", "MAGING MAHUSAY SA MINISTERYO", "PAMUMUHAY BILANG KRISTIYANO"),
            MidweekSection.entries.map { it.heading(MidweekHeadingLanguage.TAGALOG) },
        )
        assertEquals(
            listOf("GAMENG MANIPUD ITI SAO TI DIOS", "AGBALIN A MAS EPEKTIBO ITI MINISTERIO", "PANAGBIAG KAS KRISTIANO"),
            MidweekSection.entries.map { it.heading(MidweekHeadingLanguage.ILOKO) },
        )
    }

    @Test fun theStoredCodeIsReadBackAndAnythingElseMeansEnglish() {
        assertEquals(MidweekHeadingLanguage.TAGALOG, MidweekHeadingLanguage.of("TL"))
        assertEquals(MidweekHeadingLanguage.ILOKO, MidweekHeadingLanguage.of("ILO"))
        assertEquals(MidweekHeadingLanguage.ENGLISH, MidweekHeadingLanguage.of("EN"))
        assertEquals(MidweekHeadingLanguage.ENGLISH, MidweekHeadingLanguage.of(null))
        assertEquals(MidweekHeadingLanguage.ENGLISH, MidweekHeadingLanguage.of("FR"))
    }
}
