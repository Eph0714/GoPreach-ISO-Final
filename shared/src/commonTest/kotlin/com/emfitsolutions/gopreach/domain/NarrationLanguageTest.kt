package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.domain.NarrationLanguage.ENGLISH
import com.emfitsolutions.gopreach.domain.NarrationLanguage.ILOKO
import com.emfitsolutions.gopreach.domain.NarrationLanguage.TAGALOG
import com.emfitsolutions.gopreach.domain.weather.Advisory
import com.emfitsolutions.gopreach.domain.weather.CurrentWeather
import com.emfitsolutions.gopreach.domain.weather.DailyForecast
import com.emfitsolutions.gopreach.domain.weather.WeatherNarration
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import com.emfitsolutions.gopreach.domain.weather.WeatherReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NarrationLanguageTest {
    @Test
    fun voiceGenderIsReadFromTheVoiceName() {
        assertEquals(VoiceGender.FEMALE, VoiceNames.gender("en-us-x-sfg#female_1-local"))
        assertEquals(VoiceGender.MALE, VoiceNames.gender("en-us-x-iom#male_2-local"))
        assertEquals(VoiceGender.FEMALE, VoiceNames.gender("en-US-SMTf00"))
        assertEquals(VoiceGender.MALE, VoiceNames.gender("en-US-SMTm00"))
        assertEquals(VoiceGender.MALE, VoiceNames.gender("en-us-x-tpd-local"))
        assertEquals(VoiceGender.FEMALE, VoiceNames.gender("en-us-x-sfg-local"))
        assertNull(VoiceNames.gender("en-us-x-msm00013-local"), "no gender known: unknown, never guessed")
        assertNull(VoiceNames.gender("en-US-language"))
    }

    @Test
    fun theFixedPhrasesAreSpokenInTheChosenLanguage() {
        assertEquals("12 hours and 30 minutes", PlannerNarration.duration(750, ENGLISH))
        assertEquals("12 oras at 30 minuto", PlannerNarration.duration(750, TAGALOG))
        assertEquals("12 nga oras ken 30 a minuto", PlannerNarration.duration(750, ILOKO))
        assertEquals("walang oras", PlannerNarration.duration(0, TAGALOG))
        assertEquals("Revelation 21, verses 3 to 4", BibleTextNarration.spokenReference("Revelation", 21, "3-4", ENGLISH))
        assertEquals("Apocalipsis 21, talata 3 hanggang 4", BibleTextNarration.spokenReference("Apocalipsis", 21, "3-4", TAGALOG))
        assertEquals("Apocalipsis 21, bersikulo 3 agingga 4", BibleTextNarration.spokenReference("Apocalipsis", 21, "3-4", ILOKO))
        val period = PlannerNarration.Period("month", "Oktubre 2026", 600, 0, 3, 1, 20, 600, 0)
        assertTrue(PlannerNarration.periodScript(period, TAGALOG).contains("Oras sa ministeryo sa field para sa buwan: 10 oras."))
        assertTrue(PlannerNarration.periodScript(period, ILOKO).contains("Agsubli a panagbisita: 3 a tao."))
        assertTrue(PlannerNarration.periodScript(period, ENGLISH).contains("Return visits: 3 people."))
        assertTrue(BibleTextNarration.textScript(BibleTextNarration.Text("x", "", "", true), TAGALOG)[0] == "Tala.")
        assertEquals("Nalpas ti pasamak.", BibleTextNarration.eventScript("E", "", null, listOf(BibleTextNarration.Group(null, listOf(BibleTextNarration.Text("r", "v", "", false)))), ILOKO).last())
    }

    @Test
    fun theWeatherIsReadInTagalogAndIlokoWithoutEnglishAdvice() {
        val place = WeatherPlace("p", "Solano", "Nueva Vizcaya", 16.5, 121.2, "PH")
        val now = 1760500000_000L
        val base = WeatherReport(place, 28800, "Asia/Manila", now, CurrentWeather(now, 30.4, 36.2, 78, 12.4, 225, 0.2, 61, true), emptyList(), listOf(DailyForecast(0L, 63, 32.0, 24.0, 85, 12.5, null, null)))
        val report = base.copy(daily = base.daily.map { it.copy(dateMillis = base.startOfLocalDay(now)) })
        val advisory = Advisory(listOf(com.emfitsolutions.gopreach.domain.weather.AdvisoryItem(com.emfitsolutions.gopreach.domain.weather.AdvisoryLevel.CAUTION, "Rain is likely")), true)
        val fil = WeatherNarration.script(report, now, advisory, emptyList(), false, TAGALOG)
        assertEquals("Panahon sa Solano.", fil[0])
        assertTrue(fil.contains("Sa ngayon, 30 digri, mahinang ulan."))
        assertTrue(fil.contains("Ang hangin ay 12 kilometro bawat oras mula sa timog-kanluran."))
        assertTrue(fil.none { it.contains("Rain is likely") }, "English advice is not read with a Tagalog voice")
        val ilo = WeatherNarration.script(report, now, advisory, null, true, ILOKO)
        assertTrue(ilo.contains("Ita, 30 a grado, bassit a tudo."))
        assertTrue(ilo.any { it.startsWith("Saan a nasukimat") })
        assertTrue(ilo.any { it.contains("naidulin a forecast") })
    }
}
