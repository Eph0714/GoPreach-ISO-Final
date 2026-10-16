package com.emfitsolutions.gopreach.domain.weather

import com.emfitsolutions.gopreach.domain.NarrationLanguage
import kotlin.math.roundToInt

/**
 * The words the Weather screen reads aloud: the place, how it is right now, today's outlook, the field ministry advice and the official PAGASA warnings,
 * as short plain sentences (the player speaks one sentence at a time, so Pause / Resume continue where it stopped). Built only from data already on
 * screen. It never says conditions are safe: when no warning is known it says that warnings may still be issued.
 *
 * It can be read in English, Tagalog or Iloko ([lang]). The field ministry advice is generated in English only, so in Tagalog and Iloko the reader is
 * pointed to the screen for it instead of hearing English read with the wrong voice. PAGASA's own wording is read as published.
 */
object WeatherNarration {
    /** [officialWarnings]: null = they could not be checked (or do not apply to this country); empty = none active; otherwise the active warnings. */
    fun script(
        report: WeatherReport, now: Long, advisory: Advisory, officialWarnings: List<OfficialAlert>?, stale: Boolean,
        lang: NarrationLanguage = NarrationLanguage.ENGLISH,
    ): List<String> = buildList {
        val l = lang
        add(l.pick("Weather for ${report.place.name}.", "Panahon sa ${report.place.name}.", "Tiempo idiay ${report.place.name}."))
        if (stale) add(l.pick("This is a saved forecast and may be out of date.", "Ito ay naka-save na forecast at maaaring luma na.", "Daytoy ket naidulin a forecast ken mabalin a nabayagen."))

        report.current?.let { c ->
            val sky = sky(c.weatherCode, l)
            add(l.pick("Right now it is ${degrees(c.temperatureC, l)}, $sky.", "Sa ngayon, ${degrees(c.temperatureC, l)}, $sky.", "Ita, ${degrees(c.temperatureC, l)}, $sky."))
            if (c.feelsLikeC != null && c.temperatureC != null && kotlin.math.abs(c.feelsLikeC - c.temperatureC) >= 2.0) {
                add(l.pick("It feels like ${degrees(c.feelsLikeC, l)}.", "Parang ${degrees(c.feelsLikeC, l)} ang pakiramdam.", "Kasla ${degrees(c.feelsLikeC, l)} ti raman."))
            }
            c.humidityPercent?.let { add(l.pick("Humidity is $it percent.", "Ang halumigmig ay $it porsyento.", "Ti humedad ket $it porsiento.")) }
            c.windSpeedKmh?.let { w ->
                val dir = windName(c.windDirectionDeg, l)
                val n = w.roundToInt()
                add(
                    l.pick(
                        "Wind is $n kilometres per hour" + (if (dir != null) " from the $dir." else "."),
                        "Ang hangin ay $n kilometro bawat oras" + (if (dir != null) " mula sa $dir." else "."),
                        "Ti angin ket $n a kilometro iti kada oras" + (if (dir != null) " manipud iti $dir." else "."),
                    ),
                )
            }
        }

        report.daily.firstOrNull { it.dateMillis == report.startOfLocalDay(now) }?.let { d ->
            if (d.highC != null || d.lowC != null) {
                val en = buildList {
                    d.highC?.let { add("a high of ${degrees(it, l)}") }
                    d.lowC?.let { add("a low of ${degrees(it, l)}") }
                }
                val fil = buildList {
                    d.highC?.let { add("pinakamataas ${degrees(it, l)}") }
                    d.lowC?.let { add("pinakamababa ${degrees(it, l)}") }
                }
                val ilo = buildList {
                    d.highC?.let { add("kangatuan ${degrees(it, l)}") }
                    d.lowC?.let { add("kababaan ${degrees(it, l)}") }
                }
                add(l.pick("Today expect " + en.joinToString(" and ") + ".", "Ngayong araw: " + fil.joinToString(", ") + ".", "Ita nga aldaw: " + ilo.joinToString(", ") + "."))
            }
            d.precipitationProbability?.let { p ->
                val mm = d.precipitationMm?.takeIf { it >= 1.0 }?.roundToInt()
                add(
                    l.pick(
                        "The chance of rain today is $p percent" + (mm?.let { ", about $it millimetres." } ?: "."),
                        "Ang tsansa ng ulan ngayong araw ay $p porsyento" + (mm?.let { ", mga $it milimetro." } ?: "."),
                        "Ti tsansa ti tudo ita nga aldaw ket $p porsiento" + (mm?.let { ", agarup $it a milimetro." } ?: "."),
                    ),
                )
            }
        }

        if (advisory.items.isNotEmpty()) {
            if (l == NarrationLanguage.ENGLISH) {
                add("Field ministry advice.")
                advisory.items.take(5).forEach { add(it.text.trimEnd('.') + ".") }
            } else {
                add(l.pick("", "Tingnan sa screen ang mga payo para sa ministeryo sa field.", "Kitaen iti screen dagiti pammaregta para iti ministerio iti field."))
            }
        }

        when {
            officialWarnings == null -> add(
                l.pick(
                    "Official warnings could not be checked. Please check the PAGASA website.",
                    "Hindi nasuri ang mga opisyal na babala. Pakitingnan ang website ng PAGASA.",
                    "Saan a nasukimat dagiti opisial a pakdaar. Pangngaasi ta kitaenyo ti website ti PAGASA.",
                ),
            )
            officialWarnings.isEmpty() -> add(
                l.pick(
                    "PAGASA has no active warning for this place right now. A warning may still be issued at any time, so check PAGASA before outdoor activities.",
                    "Walang aktibong babala ang PAGASA para sa lugar na ito ngayon. Maaari pa ring maglabas ng babala anumang oras, kaya tingnan ang PAGASA bago ang mga gawain sa labas.",
                    "Awan ti aktibo a pakdaar ti PAGASA para iti daytoy a lugar ita. Mabalin pay laeng nga agipaay iti pakdaar iti aniaman nga oras, isu nga kitaenyo ti PAGASA sakbay dagiti aramid iti ruar.",
                ),
            )
            else -> {
                val n = officialWarnings.size
                add(
                    l.pick(
                        "PAGASA has $n active warning${if (n == 1) "" else "s"} for this place.",
                        "Ang PAGASA ay may $n aktibong babala para sa lugar na ito.",
                        "Ti PAGASA ket addaan iti $n nga aktibo a pakdaar para iti daytoy a lugar.",
                    ),
                )
                officialWarnings.take(4).forEach { a ->
                    val name = a.event.ifBlank { a.headline }
                    val sev = severity(a.severity, l)
                    add(l.pick("$name. Severity $sev.", "$name. Antas: $sev.", "$name. Kapintasan: $sev."))
                    firstSentence(a.instruction)?.let { add(it) }
                }
            }
        }
    }

    private fun degrees(c: Double?, l: NarrationLanguage): String =
        c?.let { l.pick("${it.roundToInt()} degrees", "${it.roundToInt()} digri", "${it.roundToInt()} a grado") }
            ?: l.pick("not available", "hindi available", "awan ti naammuan")

    private fun severity(raw: String, l: NarrationLanguage): String = when (raw.lowercase()) {
        "extreme" -> l.pick("extreme", "napakatindi", "nakaro unay")
        "severe" -> l.pick("severe", "matindi", "nakaro")
        "moderate" -> l.pick("moderate", "katamtaman", "katengnga")
        "minor" -> l.pick("minor", "bahagya", "bassit")
        else -> raw.lowercase()
    }

    private fun firstSentence(text: String): String? =
        text.replace("**", "").replace('\n', ' ').trim().takeIf { it.isNotEmpty() }?.let { t -> (t.substringBefore(". ").trimEnd('.') + ".").take(220) }

    /** The sky in words, for the WMO weather [code]. */
    fun sky(code: Int?, l: NarrationLanguage): String {
        if (l == NarrationLanguage.ENGLISH) return WeatherCodes.label(code).lowercase()
        val k = when (code) {
            0 -> 0
            1 -> 1
            2 -> 2
            3 -> 3
            45, 48 -> 4
            51, 53, 55, 56, 57 -> 5
            61, 80 -> 6
            63, 66, 67, 81 -> 7
            65, 82 -> 8
            71, 73, 75, 77, 85, 86 -> 9
            95 -> 10
            96, 99 -> 11
            else -> 12
        }
        val fil = listOf(
            "maaliwalas ang langit", "halos maaliwalas", "bahagyang maulap", "makulimlim", "mahamog", "ambon", "mahinang ulan", "ulan", "malakas na ulan", "niyebe",
            "pagkulog at pagkidlat", "pagkulog at pagkidlat na may yelo", "hindi alam ang kalagayan ng panahon",
        )
        val ilo = listOf(
            "nalawag ti langit", "asideg a nalawag", "bassit ti ulep", "nasipnget ti langit", "ayamuom", "gisgis", "bassit a tudo", "tudo", "napigsa a tudo", "niebe",
            "gurruod ken kimat", "gurruod ken kimat nga addaan iti yelo", "awan ti naammuan a kasasaad ti tiempo",
        )
        return if (l == NarrationLanguage.TAGALOG) fil[k] else ilo[k]
    }

    /** The compass point of a wind direction in words. English keeps the 16 points; Tagalog and Iloko use the 8 main ones. */
    fun windName(degrees: Int?, l: NarrationLanguage = NarrationLanguage.ENGLISH): String? {
        if (degrees == null) return null
        if (l != NarrationLanguage.ENGLISH) {
            val i = ((((degrees % 360) + 360) % 360 + 22) / 45) % 8
            val fil = listOf("hilaga", "hilagang-silangan", "silangan", "timog-silangan", "timog", "timog-kanluran", "kanluran", "hilagang-kanluran")
            val ilo = listOf("amianan", "amianan-daya", "daya", "abagatan-daya", "abagatan", "abagatan-laud", "laud", "amianan-laud")
            return if (l == NarrationLanguage.TAGALOG) fil[i] else ilo[i]
        }
        return compassPoint(degrees)?.let { p ->
            mapOf(
                "N" to "north", "NNE" to "north-north-east", "NE" to "north-east", "ENE" to "east-north-east", "E" to "east", "ESE" to "east-south-east",
                "SE" to "south-east", "SSE" to "south-south-east", "S" to "south", "SSW" to "south-south-west", "SW" to "south-west", "WSW" to "west-south-west",
                "W" to "west", "WNW" to "west-north-west", "NW" to "north-west", "NNW" to "north-north-west",
            )[p]
        }
    }

    fun windName(degrees: Int?): String? = windName(degrees, NarrationLanguage.ENGLISH)
}
