package com.emfitsolutions.gopreach.domain.weather

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Where a place in the Weather module came from. Only [kind] decides how it is grouped on screen. */
@Serializable
enum class PlaceKind { SEARCH, CURRENT_LOCATION, SAVED, TERRITORY }

/** A place weather can be requested for. [id] is stable for saved places and territories; [key] identifies cached data. */
@Serializable
data class WeatherPlace(
    val id: String,
    val name: String,
    /** Secondary line, e.g. "Pampanga, Philippines" - never contains publisher or congregation records. */
    val detail: String = "",
    val lat: Double,
    val lng: Double,
    val countryCode: String = "",
    val kind: PlaceKind = PlaceKind.SEARCH,
) {
    /** Cache key: coordinates rounded to ~1 km, so two lookups of the same spot share one cached copy. */
    val key: String get() = "${(lat * 100).roundToInt()}_${(lng * 100).roundToInt()}"
    val isPhilippines: Boolean get() = countryCode.equals("PH", ignoreCase = true)
    val title: String get() = if (detail.isBlank()) name else "$name, $detail"
}

/** The broad kind of weather a World Meteorological Organization code stands for. */
enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SHOWERS, SNOW, THUNDERSTORM, UNKNOWN }

object WeatherCodes {
    fun kind(code: Int?): WeatherKind = when (code) {
        0, 1 -> WeatherKind.CLEAR
        2 -> WeatherKind.PARTLY_CLOUDY
        3 -> WeatherKind.CLOUDY
        45, 48 -> WeatherKind.FOG
        51, 53, 55, 56, 57 -> WeatherKind.DRIZZLE
        61, 63, 65, 66, 67 -> WeatherKind.RAIN
        80, 81, 82 -> WeatherKind.SHOWERS
        71, 73, 75, 77, 85, 86 -> WeatherKind.SNOW
        95, 96, 99 -> WeatherKind.THUNDERSTORM
        else -> WeatherKind.UNKNOWN
    }

    fun label(code: Int?): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80 -> "Light rain showers"
        81 -> "Rain showers"
        82 -> "Violent rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Conditions unavailable"
    }

    fun isWet(code: Int?) = kind(code).let { it == WeatherKind.DRIZZLE || it == WeatherKind.RAIN || it == WeatherKind.SHOWERS || it == WeatherKind.THUNDERSTORM }
    fun isThunder(code: Int?) = kind(code) == WeatherKind.THUNDERSTORM
}

@Serializable
data class CurrentWeather(
    val timeMillis: Long,
    val temperatureC: Double?,
    val feelsLikeC: Double?,
    val humidityPercent: Int?,
    val windSpeedKmh: Double?,
    val windDirectionDeg: Int?,
    val precipitationMm: Double?,
    val weatherCode: Int?,
    val isDay: Boolean,
)

@Serializable
data class HourlyForecast(
    val timeMillis: Long,
    val temperatureC: Double?,
    /** Chance (0-100) that measurable precipitation happens in this hour. NOT an amount. */
    val precipitationProbability: Int?,
    /** Expected precipitation amount in millimetres for this hour. NOT a chance. */
    val precipitationMm: Double?,
    val weatherCode: Int?,
)

@Serializable
data class DailyForecast(
    /** Local midnight of the day, as epoch milliseconds. */
    val dateMillis: Long,
    val weatherCode: Int?,
    val highC: Double?,
    val lowC: Double?,
    val precipitationProbability: Int?,
    val precipitationMm: Double?,
    val sunriseMillis: Long?,
    val sunsetMillis: Long?,
)

@Serializable
data class WeatherReport(
    val place: WeatherPlace,
    /** The place's UTC offset when fetched; every time is shown in the place's own time, not the device's. */
    val utcOffsetSeconds: Int,
    val timeZoneName: String,
    val fetchedAtMillis: Long,
    val current: CurrentWeather?,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
) {
    /** Epoch millis of the place's local midnight that starts the day containing [millis]. */
    fun startOfLocalDay(millis: Long): Long {
        val local = millis + utcOffsetSeconds * 1000L
        return local.floorDiv(DAY_MS) * DAY_MS - utcOffsetSeconds * 1000L
    }

    /** Hour of day (0-23) in the place's own time. */
    fun localHour(millis: Long): Int = ((millis + utcOffsetSeconds * 1000L).mod(DAY_MS) / HOUR_MS).toInt()

    /** The hourly entries of the place's local day that contains [now]. */
    fun hoursOfDay(now: Long): List<HourlyForecast> {
        val start = startOfLocalDay(now)
        return hourly.filter { it.timeMillis >= start && it.timeMillis < start + DAY_MS }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}

/** Why a weather request failed - the screen words each one differently. */
enum class WeatherFailure { OFFLINE, TIMEOUT, RATE_LIMITED, NOT_FOUND, BAD_RESPONSE, SERVER }

class WeatherException(val failure: WeatherFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

fun compassPoint(degrees: Int?): String? {
    if (degrees == null) return null
    val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return names[((degrees % 360 + 360) % 360 / 22.5 + 0.5).toInt() % 16]
}
