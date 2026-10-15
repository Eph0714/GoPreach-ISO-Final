package com.emfitsolutions.gopreach.data.weather

import com.emfitsolutions.gopreach.domain.weather.CurrentWeather
import com.emfitsolutions.gopreach.domain.weather.DailyForecast
import com.emfitsolutions.gopreach.domain.weather.HourlyForecast
import com.emfitsolutions.gopreach.domain.weather.PlaceKind
import com.emfitsolutions.gopreach.domain.weather.WeatherException
import com.emfitsolutions.gopreach.domain.weather.WeatherFailure
import com.emfitsolutions.gopreach.domain.weather.WeatherPlace
import com.emfitsolutions.gopreach.domain.weather.WeatherReport
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** The weather source behind the module. Kept as an interface so screens and tests never depend on the network. */
interface WeatherService {
    suspend fun forecast(place: WeatherPlace, nowMillis: Long): WeatherReport
    suspend fun search(query: String): List<WeatherPlace>
}

/**
 * Open-Meteo (https://open-meteo.com): no API key. The free tier is for non-commercial use (600 calls/minute, 5,000/hour, 10,000/day)
 * and its data is CC BY 4.0, so the screen shows the attribution. A commercial deployment would need a paid Open-Meteo plan.
 */
class OpenMeteoWeatherService(
    private val client: HttpClient,
    private val forecastUrl: String = "https://api.open-meteo.com/v1/forecast",
    private val geocodingUrl: String = "https://geocoding-api.open-meteo.com/v1/search",
    private val timeoutMillis: Long = 15_000L,
) : WeatherService {
    override suspend fun forecast(place: WeatherPlace, nowMillis: Long): WeatherReport {
        val text = fetch(forecastUrl) {
            parameter("latitude", place.lat)
            parameter("longitude", place.lng)
            parameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m")
            parameter("hourly", "temperature_2m,precipitation_probability,precipitation,weather_code")
            parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,sunrise,sunset")
            parameter("timezone", "auto")
            parameter("timeformat", "unixtime")
            parameter("forecast_days", 7)
        }
        return parseForecast(text, place, nowMillis)
    }

    override suspend fun search(query: String): List<WeatherPlace> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val text = fetch(geocodingUrl) {
            parameter("name", q)
            parameter("count", 10)
            parameter("language", "en")
            parameter("format", "json")
        }
        return parseSearch(text)
    }

    private suspend fun fetch(url: String, params: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String {
        try {
            return withTimeout(timeoutMillis) {
                val response = client.get(url) { params() }
                when {
                    response.status == HttpStatusCode.TooManyRequests ->
                        throw WeatherException(WeatherFailure.RATE_LIMITED, "The weather service is receiving too many requests. Please try again in a few minutes.")
                    response.status.value in 500..599 -> throw WeatherException(WeatherFailure.SERVER, "The weather service is temporarily unavailable.")
                    response.status.value == 400 -> throw WeatherException(WeatherFailure.NOT_FOUND, "Weather is not available for this location.")
                    response.status.value !in 200..299 -> throw WeatherException(WeatherFailure.SERVER, "The weather service answered with an error (${response.status.value}).")
                }
                response.bodyAsText()
            }
        } catch (e: WeatherException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw WeatherException(WeatherFailure.TIMEOUT, "The weather service took too long to answer.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WeatherException(WeatherFailure.OFFLINE, "Could not reach the weather service. Check your internet connection.", e)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun parseForecast(text: String, place: WeatherPlace, nowMillis: Long): WeatherReport {
            val dto = try { json.decodeFromString(ForecastDto.serializer(), text) } catch (e: Exception) {
                throw WeatherException(WeatherFailure.BAD_RESPONSE, "The weather service sent data this app could not read.", e)
            }
            if (dto.error == true) throw WeatherException(WeatherFailure.NOT_FOUND, dto.reason ?: "Weather is not available for this location.")
            val offset = dto.utcOffsetSeconds ?: throw WeatherException(WeatherFailure.BAD_RESPONSE, "The weather service did not say which time zone applies.")

            val current = dto.current?.takeIf { it.time != null }?.let {
                CurrentWeather(
                    timeMillis = it.time!! * 1000L,
                    temperatureC = it.temperature,
                    feelsLikeC = it.apparent,
                    humidityPercent = it.humidity?.toInt(),
                    windSpeedKmh = it.windSpeed,
                    windDirectionDeg = it.windDirection?.toInt(),
                    precipitationMm = it.precipitation,
                    weatherCode = it.weatherCode,
                    isDay = it.isDay != 0,
                )
            }
            val h = dto.hourly
            val hourly = h?.time.orEmpty().mapIndexed { i, t ->
                HourlyForecast(
                    timeMillis = t * 1000L,
                    temperatureC = h?.temperature.at(i),
                    precipitationProbability = h?.probability.at(i)?.toInt()?.coerceIn(0, 100),
                    precipitationMm = h?.precipitation.at(i)?.coerceAtLeast(0.0),
                    weatherCode = h?.weatherCode.at(i)?.toInt(),
                )
            }
            val d = dto.daily
            val daily = d?.time.orEmpty().mapIndexed { i, t ->
                DailyForecast(
                    dateMillis = t * 1000L,
                    weatherCode = d?.weatherCode.at(i)?.toInt(),
                    highC = d?.high.at(i),
                    lowC = d?.low.at(i),
                    precipitationProbability = d?.probability.at(i)?.toInt()?.coerceIn(0, 100),
                    precipitationMm = d?.precipitation.at(i)?.coerceAtLeast(0.0),
                    sunriseMillis = d?.sunrise.at(i)?.let { it * 1000L },
                    sunsetMillis = d?.sunset.at(i)?.let { it * 1000L },
                )
            }
            if (current == null && hourly.isEmpty() && daily.isEmpty()) {
                throw WeatherException(WeatherFailure.BAD_RESPONSE, "The weather service returned no forecast for this location.")
            }
            return WeatherReport(place, offset, dto.timezone.orEmpty(), nowMillis, current, hourly, daily)
        }

        internal fun parseSearch(text: String): List<WeatherPlace> {
            val dto = try { json.decodeFromString(GeocodingDto.serializer(), text) } catch (e: Exception) {
                throw WeatherException(WeatherFailure.BAD_RESPONSE, "The location search returned data this app could not read.", e)
            }
            return dto.results.orEmpty().mapNotNull { r ->
                val lat = r.latitude ?: return@mapNotNull null
                val lng = r.longitude ?: return@mapNotNull null
                if (r.name.isNullOrBlank() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
                WeatherPlace(
                    id = "geo_${r.id ?: "${lat}_$lng"}",
                    name = r.name,
                    detail = listOfNotNull(r.admin2, r.admin1, r.country).filter { it.isNotBlank() && it != r.name }.distinct().joinToString(", "),
                    lat = lat,
                    lng = lng,
                    countryCode = r.countryCode.orEmpty(),
                    kind = PlaceKind.SEARCH,
                )
            }
        }

        private fun <T> List<T?>?.at(i: Int): T? = this?.getOrNull(i)
    }
}

@Serializable
private data class ForecastDto(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
    val timezone: String? = null,
    val error: Boolean? = null,
    val reason: String? = null,
    val current: CurrentDto? = null,
    val hourly: HourlyDto? = null,
    val daily: DailyDto? = null,
)

@Serializable
private data class CurrentDto(
    val time: Long? = null,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparent: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("wind_direction_10m") val windDirection: Double? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("is_day") val isDay: Int? = 1,
)

@Serializable
private data class HourlyDto(
    val time: List<Long> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?>? = null,
    @SerialName("precipitation_probability") val probability: List<Double?>? = null,
    val precipitation: List<Double?>? = null,
    @SerialName("weather_code") val weatherCode: List<Double?>? = null,
)

@Serializable
private data class DailyDto(
    val time: List<Long> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Double?>? = null,
    @SerialName("temperature_2m_max") val high: List<Double?>? = null,
    @SerialName("temperature_2m_min") val low: List<Double?>? = null,
    @SerialName("precipitation_probability_max") val probability: List<Double?>? = null,
    @SerialName("precipitation_sum") val precipitation: List<Double?>? = null,
    val sunrise: List<Long?>? = null,
    val sunset: List<Long?>? = null,
)

@Serializable
private data class GeocodingDto(val results: List<GeoResultDto>? = null)

@Serializable
private data class GeoResultDto(
    val id: Long? = null,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val admin1: String? = null,
    val admin2: String? = null,
)
