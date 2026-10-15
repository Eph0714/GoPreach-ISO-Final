package com.emfitsolutions.gopreach.domain.weather

import kotlin.math.roundToInt

enum class AdvisoryLevel { INFO, CAUTION, WARNING }

data class AdvisoryItem(val level: AdvisoryLevel, val text: String)

/**
 * Field Ministry Weather Advisory: short, practical suggestions written from the forecast actually received. It is informational only:
 * it never claims conditions are safe, never invents an official alert, and never changes schedules, territories or reports.
 */
data class Advisory(val items: List<AdvisoryItem>, val complete: Boolean) {
    companion object {
        const val DISCLAIMER = "Forecast-based suggestions only. These are not official weather warnings."
    }
}

object WeatherAdvisory {
    private const val RAIN_LIKELY = 60
    private const val RAIN_POSSIBLE = 30
    private const val HOT_HIGH_C = 33.0
    private const val VERY_HOT_FEELS_C = 38.0
    private const val STRONG_WIND_KMH = 40.0
    private const val HEAVY_RAIN_MM_PER_HOUR = 7.5

    fun build(report: WeatherReport, now: Long): Advisory {
        val today = report.hoursOfDay(now)
        val remaining = today.filter { it.timeMillis + WeatherReport.HOUR_MS > now }
        val todayDaily = report.daily.firstOrNull { it.dateMillis == report.startOfLocalDay(now) }
        if (remaining.isEmpty() && todayDaily == null) {
            return Advisory(listOf(AdvisoryItem(AdvisoryLevel.INFO, "The forecast for this place is incomplete, so no advice can be given. Check again when the forecast refreshes.")), complete = false)
        }

        val items = mutableListOf<AdvisoryItem>()
        fun add(level: AdvisoryLevel, text: String) { items += AdvisoryItem(level, text) }
        fun at(h: HourlyForecast) = hourLabel(report.localHour(h.timeMillis))

        // Thunderstorms first: the most serious thing a forecast alone can suggest.
        val storm = remaining.filter { WeatherCodes.isThunder(it.weatherCode) }
        if (storm.isNotEmpty()) {
            add(AdvisoryLevel.WARNING, "Thunderstorms are forecast from about ${at(storm.first())}. Check official weather warnings before any outdoor activity, and consider moving ministry indoors or to another time.")
        }

        val probs = remaining.mapNotNull { it.precipitationProbability }
        val likely = remaining.filter { (it.precipitationProbability ?: 0) >= RAIN_LIKELY || (WeatherCodes.isWet(it.weatherCode) && it !in storm) }
        val peak = probs.maxOrNull()
        if (likely.isNotEmpty() && storm.isEmpty()) {
            val heavy = remaining.any { (it.precipitationMm ?: 0.0) >= HEAVY_RAIN_MM_PER_HOUR }
            val window = if (likely.first() == likely.last()) "around ${at(likely.first())}" else "between ${at(likely.first())} and ${at(likely.last())}"
            val extra = if (heavy) " Some heavy downpours are possible, so be cautious near flooded roads." else ""
            val earlier = remaining.firstOrNull()?.takeIf { it.timeMillis < likely.first().timeMillis }
            add(AdvisoryLevel.CAUTION, "Rain is likely $window.${if (earlier != null) " Consider scheduling outdoor ministry earlier, or bring rain protection." else " Bring rain protection or plan indoor alternatives."}$extra")
        } else if (storm.isEmpty() && peak != null && peak >= RAIN_POSSIBLE) {
            add(AdvisoryLevel.INFO, "There is some chance of rain today (up to $peak%). Carrying an umbrella or raincoat would be sensible.")
        }

        val feels = report.current?.feelsLikeC
        val high = todayDaily?.highC
        when {
            feels != null && feels >= VERY_HOT_FEELS_C ->
                add(AdvisoryLevel.WARNING, "It feels very hot right now (${feels.roundToInt()}°C). Take plenty of water, rest in the shade often, and avoid prolonged midday activity.")
            (high != null && high >= HOT_HIGH_C) || (feels != null && feels >= HOT_HIGH_C + 2) ->
                add(AdvisoryLevel.CAUTION, "Hot conditions are expected${if (high != null) " (high of ${high.roundToInt()}°C)" else ""}. Consider taking water, wearing light clothing and planning breaks, or going out earlier in the day.")
        }

        val wind = report.current?.windSpeedKmh
        if (wind != null && wind >= STRONG_WIND_KMH) add(AdvisoryLevel.CAUTION, "Strong wind is blowing (${wind.roundToInt()} km/h). Take care with umbrellas, literature and carts, and watch for falling branches.")

        // Tomorrow, so the publisher can plan ahead.
        val tomorrow = report.daily.firstOrNull { it.dateMillis == report.startOfLocalDay(now) + WeatherReport.DAY_MS }
        if (tomorrow != null) {
            val tp = tomorrow.precipitationProbability
            if (WeatherCodes.isThunder(tomorrow.weatherCode)) add(AdvisoryLevel.CAUTION, "Thunderstorms are forecast for tomorrow. Keep an eye on official warnings when planning outdoor ministry.")
            else if (tp != null && tp >= RAIN_LIKELY) add(AdvisoryLevel.INFO, "Tomorrow looks wet ($tp% chance of rain). You may want to plan indoor or flexible activities.")
        }

        if (items.isEmpty()) {
            val missing = remaining.isEmpty() || probs.isEmpty()
            add(
                AdvisoryLevel.INFO,
                if (missing) "The rain forecast for the rest of today is not available, so only limited advice can be given. Keep monitoring the forecast."
                else "The forecast shows no significant rain or heat for the rest of today. Conditions appear suitable for outdoor activities, but continue monitoring the forecast.",
            )
        }
        return Advisory(items.sortedByDescending { it.level.ordinal }, complete = remaining.isNotEmpty() && probs.isNotEmpty())
    }

    fun hourLabel(hour: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12 ${if (hour < 12) "AM" else "PM"}"
    }
}
