package com.emfitsolutions.gopreach.domain.weather

import com.emfitsolutions.gopreach.data.weather.OpenMeteoWeatherService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private val place = WeatherPlace("p", "Solano", "Nueva Vizcaya, Philippines", 16.5, 121.2, "PH")
    private val offset = 8 * 3600 // Manila, UTC+8

    private fun report(hours: List<HourlyForecast>, daily: List<DailyForecast> = emptyList(), current: CurrentWeather? = null, off: Int = offset) =
        WeatherReport(place, off, "Asia/Manila", 0, current, hours, daily)

    // local midnight 0:00 on a day in Manila, expressed as epoch millis
    private val start = 1_760_457_600_000L // 2025-10-14T16:00:00Z == 2025-10-15 00:00 in UTC+8
    private fun hour(h: Int, pop: Int? = 0, code: Int = 1, mm: Double? = 0.0) = HourlyForecast(start + h * 3_600_000L, 30.0, pop, mm, code)
    private fun day(offsetDays: Int, pop: Int? = 10, code: Int = 1, high: Double? = 31.0) = DailyForecast(start + offsetDays * 86_400_000L, code, high, 24.0, pop, 0.0, null, null)
    private fun now(h: Int) = start + h * 3_600_000L + 600_000L

    @Test
    fun theWmoCodesMapToTheRightKindAndWording() {
        assertEquals(WeatherKind.THUNDERSTORM, WeatherCodes.kind(95))
        assertEquals(WeatherKind.SHOWERS, WeatherCodes.kind(81))
        assertEquals("Heavy rain", WeatherCodes.label(65))
        assertEquals("Conditions unavailable", WeatherCodes.label(null))
        assertTrue(WeatherCodes.isWet(61)); assertFalse(WeatherCodes.isWet(3))
    }

    @Test
    fun localTimeUsesThePlacesOffsetNotTheDevices() {
        val r = report(emptyList())
        assertEquals(start, r.startOfLocalDay(start + 5 * 3_600_000L))
        assertEquals(5, r.localHour(start + 5 * 3_600_000L))
        assertEquals(start + 86_400_000L, r.startOfLocalDay(start + 86_400_000L + 1))
        assertEquals(15, report(emptyList(), off = -5 * 3600).localHour(start + 4 * 3_600_000L))
        assertEquals("N", compassPoint(0)); assertEquals("E", compassPoint(90)); assertEquals("NNW", compassPoint(340)); assertNull(compassPoint(null))
    }

    @Test
    fun rainIsAdvisedFromTheForecastAndNeverCalledSafeOrOfficial() {
        val hours = (0..23).map { hour(it, pop = if (it in 14..17) 80 else 5, code = if (it in 14..17) 63 else 1) }
        val adv = WeatherAdvisory.build(report(hours, listOf(day(0))), now(9))
        val text = adv.items.joinToString(" ") { it.text }
        assertTrue(text.contains("Rain is likely between 2 PM and 5 PM"), text)
        assertTrue(text.contains("earlier"), text)
        assertFalse(text.contains("safe", ignoreCase = true) && !text.contains("not"), text)
        assertTrue(adv.complete)
    }

    @Test
    fun thunderstormsGetConservativeWordingAndComeFirst() {
        val hours = (0..23).map { hour(it, pop = 70, code = if (it == 15) 95 else 61) }
        val adv = WeatherAdvisory.build(report(hours, listOf(day(0, pop = 70, code = 95))), now(9))
        assertEquals(AdvisoryLevel.WARNING, adv.items.first().level)
        assertTrue(adv.items.first().text.contains("Check official weather warnings"))
        assertFalse(adv.items.any { it.text.contains("PAGASA has issued", ignoreCase = true) })
    }

    @Test
    fun heatIsReportedFromTheDailyHigh() {
        val adv = WeatherAdvisory.build(report((0..23).map { hour(it) }, listOf(day(0, high = 36.0))), now(9))
        assertTrue(adv.items.any { it.text.contains("Hot conditions") && it.text.contains("36") })
    }

    @Test
    fun aQuietForecastStillSaysToKeepMonitoring() {
        val adv = WeatherAdvisory.build(report((0..23).map { hour(it) }, listOf(day(0))), now(9))
        assertEquals(1, adv.items.size)
        assertTrue(adv.items[0].text.contains("continue monitoring"))
    }

    @Test
    fun anIncompleteForecastGivesNoAdviceInsteadOfGuessing() {
        val none = WeatherAdvisory.build(report(emptyList(), emptyList()), now(9))
        assertFalse(none.complete)
        assertTrue(none.items.single().text.contains("incomplete"))
        val noProbability = WeatherAdvisory.build(report((0..23).map { hour(it, pop = null) }, listOf(day(0))), now(9))
        assertFalse(noProbability.complete)
        assertFalse(noProbability.items.any { it.text.contains("no significant rain") })
    }

    private val forecastJson = """{"utc_offset_seconds":28800,"timezone":"Asia/Manila",
      "current":{"time":1760500000,"temperature_2m":30.5,"apparent_temperature":36.2,"relative_humidity_2m":78,"is_day":1,"precipitation":0.2,"weather_code":61,"wind_speed_10m":12.4,"wind_direction_10m":225},
      "hourly":{"time":[1760457600,1760461200],"temperature_2m":[27.0,null],"precipitation_probability":[40,null],"precipitation":[0.4,0.0],"weather_code":[3,61]},
      "daily":{"time":[1760457600],"weather_code":[63],"temperature_2m_max":[32.0],"temperature_2m_min":[24.0],"precipitation_probability_max":[85],"precipitation_sum":[12.5],"sunrise":[1760479000],"sunset":[1760521000]}}"""

    @Test
    fun aForecastResponseIsReadWithTheRealFigures() {
        val r = OpenMeteoWeatherService.parseForecast(forecastJson, place, 99)
        assertEquals(30.5, r.current!!.temperatureC); assertEquals(36.2, r.current!!.feelsLikeC); assertEquals(78, r.current!!.humidityPercent)
        assertEquals(225, r.current!!.windDirectionDeg); assertEquals(61, r.current!!.weatherCode)
        assertEquals(28800, r.utcOffsetSeconds); assertEquals(99, r.fetchedAtMillis)
        assertEquals(2, r.hourly.size); assertEquals(40, r.hourly[0].precipitationProbability); assertNull(r.hourly[1].precipitationProbability); assertNull(r.hourly[1].temperatureC)
        assertEquals(85, r.daily.single().precipitationProbability); assertEquals(12.5, r.daily.single().precipitationMm)
        assertEquals(1760479000_000L, r.daily.single().sunriseMillis)
    }

    @Test
    fun malformedOrEmptyResponsesAreReportedNotCrashed() {
        assertEquals(WeatherFailure.BAD_RESPONSE, assertFailsWith<WeatherException> { OpenMeteoWeatherService.parseForecast("<html>", place, 0) }.failure)
        assertEquals(WeatherFailure.BAD_RESPONSE, assertFailsWith<WeatherException> { OpenMeteoWeatherService.parseForecast("""{"utc_offset_seconds":0}""", place, 0) }.failure)
        assertEquals(WeatherFailure.NOT_FOUND, assertFailsWith<WeatherException> { OpenMeteoWeatherService.parseForecast("""{"error":true,"reason":"bad"}""", place, 0) }.failure)
    }

    @Test
    fun searchResultsKeepOnlyUsablePlaces() {
        val found = OpenMeteoWeatherService.parseSearch("""{"results":[{"id":1,"name":"Solano","latitude":16.52,"longitude":121.18,"country":"Philippines","country_code":"PH","admin1":"Nueva Vizcaya","admin2":"Solano"},{"name":"","latitude":1,"longitude":1},{"name":"X","latitude":999,"longitude":1}]}""")
        assertEquals(1, found.size)
        assertEquals("Solano, Nueva Vizcaya, Philippines", found[0].title)
        assertTrue(found[0].isPhilippines)
        assertTrue(OpenMeteoWeatherService.parseSearch("{}").isEmpty())
    }

    @Test
    fun httpFailuresBecomeSpecificErrorsAndRequestsCarryTheCoordinates() = runTest { withContext(Dispatchers.Default) {
        fun service(status: HttpStatusCode, body: String = "{}", capture: (String) -> Unit = {}) =
            OpenMeteoWeatherService(HttpClient(MockEngine { req -> capture(req.url.toString()); respond(body, status) }))
        assertEquals(WeatherFailure.RATE_LIMITED, assertFailsWith<WeatherException> { service(HttpStatusCode.TooManyRequests).forecast(place, 0) }.failure)
        assertEquals(WeatherFailure.SERVER, assertFailsWith<WeatherException> { service(HttpStatusCode.InternalServerError).forecast(place, 0) }.failure)
        var url = ""
        service(HttpStatusCode.OK, forecastJson) { url = it }.forecast(place, 0)
        assertTrue(url.contains("latitude=16.5") && url.contains("longitude=121.2") && url.contains("timezone=auto") && url.contains("timeformat=unixtime"), url)
        assertTrue(service(HttpStatusCode.OK).search("a").isEmpty(), "a one-letter search makes no request")
    }
    }
}
