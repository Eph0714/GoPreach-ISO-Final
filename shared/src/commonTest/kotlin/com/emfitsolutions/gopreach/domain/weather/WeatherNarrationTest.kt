package com.emfitsolutions.gopreach.domain.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherNarrationTest {
    private val place = WeatherPlace("p", "Solano", "Nueva Vizcaya", 16.5, 121.2, "PH")
    private val now = 1760500000_000L
    private val report = WeatherReport(
        place, 28800, "Asia/Manila", now,
        CurrentWeather(now, 30.4, 36.2, 78, 12.4, 225, 0.2, 61, true),
        emptyList(),
        listOf(DailyForecast(0L, 63, 32.0, 24.0, 85, 12.5, null, null)),
    ).let { r -> r.copy(daily = r.daily.map { it.copy(dateMillis = r.startOfLocalDay(now)) }) }
    private val advisory = Advisory(listOf(AdvisoryItem(AdvisoryLevel.CAUTION, "Rain is likely this afternoon")), true)

    @Test
    fun theForecastIsReadAsShortPlainSentences() {
        val s = WeatherNarration.script(report, now, advisory, emptyList(), stale = false)
        assertEquals("Weather for Solano.", s[0])
        assertTrue(s.contains("Right now it is 30 degrees, light rain."))
        assertTrue(s.contains("It feels like 36 degrees."))
        assertTrue(s.contains("Wind is 12 kilometres per hour from the south-west."))
        assertTrue(s.contains("Today expect a high of 32 degrees and a low of 24 degrees."))
        assertTrue(s.any { it.startsWith("The chance of rain today is 85 percent") && it.contains("13 millimetres") })
        assertTrue(s.contains("Rain is likely this afternoon."))
    }

    @Test
    fun officialWarningsAreReadHonestly() {
        val none = WeatherNarration.script(report, now, advisory, emptyList(), false)
        assertTrue(none.any { it.contains("no active warning") && it.contains("may still be issued") }, "no warning is never presented as no hazard")
        val unknown = WeatherNarration.script(report, now, advisory, null, false)
        assertTrue(unknown.any { it.contains("could not be checked") })
        val alert = OfficialAlert("a", "General Flood Advisory (Moderate)", "", "Moderate", "Future", "", "Take precautions. Stay away from rivers.", emptyList(), null, null, null, emptyList())
        val some = WeatherNarration.script(report, now, advisory, listOf(alert), false)
        assertTrue(some.contains("PAGASA has 1 active warning for this place."))
        assertTrue(some.contains("General Flood Advisory (Moderate). Severity moderate."))
        assertTrue(some.contains("Take precautions."))
        assertTrue(WeatherNarration.script(report, now, advisory, emptyList(), stale = true).contains("This is a saved forecast and may be out of date."))
    }
}
