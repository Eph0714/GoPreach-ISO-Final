package com.emfitsolutions.gopreach.domain.weather

import kotlinx.datetime.Instant

/** An official PAGASA warning or advisory (Common Alerting Protocol) that covers a place. */
data class OfficialAlert(
    val id: String,
    val event: String,
    val headline: String,
    /** CAP severity: Extreme, Severe, Moderate, Minor or Unknown. */
    val severity: String,
    val urgency: String,
    val description: String,
    val instruction: String,
    val areaNames: List<String>,
    val sentMillis: Long?,
    val expiresMillis: Long?,
    val link: String?,
    /** Area outlines as (lat, lng) rings. Used only to decide whether a place is inside the warned area. */
    val polygons: List<List<Pair<Double, Double>>>,
) {
    val severityRank: Int get() = when (severity.lowercase()) { "extreme" -> 4; "severe" -> 3; "moderate" -> 2; "minor" -> 1; else -> 0 }
    fun isActive(now: Long) = expiresMillis == null || expiresMillis > now
    fun covers(lat: Double, lng: Double) = polygons.any { CapParser.inside(it, lat, lng) }
}

/** Reads one CAP 1.2 alert document. Deliberately small: CAP is flat enough that no XML library is needed. */
object CapParser {
    fun parse(xml: String): OfficialAlert? {
        if (!xml.contains("<alert")) return null
        if (tag(xml, "status")?.equals("Actual", ignoreCase = true) != true) return null
        if (tag(xml, "msgType").equals("Cancel", ignoreCase = true)) return null
        val id = tag(xml, "identifier") ?: return null
        val info = blocks(xml, "info").firstOrNull() ?: return null
        val areas = blocks(info, "area")
        return OfficialAlert(
            id = id,
            event = tag(info, "event").orEmpty(),
            headline = tag(info, "headline").orEmpty(),
            severity = tag(info, "severity") ?: "Unknown",
            urgency = tag(info, "urgency") ?: "Unknown",
            description = tag(info, "description").orEmpty().trim(),
            instruction = tag(info, "instruction").orEmpty().trim(),
            areaNames = areas.mapNotNull { tag(it, "areaDesc") }.distinct(),
            sentMillis = tag(xml, "sent")?.let(::time),
            expiresMillis = tag(info, "expires")?.let(::time),
            link = tag(info, "web")?.takeIf { it.startsWith("http") },
            polygons = areas.flatMap { blocks(it, "polygon") }.mapNotNull { ring(unescape(it)) },
        )
    }

    fun time(text: String): Long? = runCatching { Instant.parse(text.trim()).toEpochMilliseconds() }.getOrNull()

    /** "lat,lng lat,lng ..." -> points. A ring with fewer than 3 distinct points is not an area. */
    internal fun ring(text: String): List<Pair<Double, Double>>? {
        val pts = text.trim().split(Regex("\\s+")).mapNotNull { p ->
            val parts = p.split(',')
            val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else lat to lng
        }
        return pts.takeIf { it.distinct().size >= 3 }
    }

    /** Ray casting: is the point inside the ring? */
    fun inside(ring: List<Pair<Double, Double>>, lat: Double, lng: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (latI, lngI) = ring[i]
            val (latJ, lngJ) = ring[j]
            if ((lngI > lng) != (lngJ > lng) && lat < (latJ - latI) * (lng - lngI) / (lngJ - lngI) + latI) inside = !inside
            j = i
        }
        return inside
    }

    private fun blocks(text: String, name: String): List<String> =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toList()

    private fun tag(text: String, name: String): String? = blocks(text, name).firstOrNull()?.let { unescape(it) }?.takeIf { it.isNotBlank() }

    internal fun unescape(raw: String): String {
        val s = raw.trim().removePrefix("<![CDATA[").removeSuffix("]]>")
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> (if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())?.toChar()?.toString() ?: ""
            }
        }.replace("\r", "")
    }
}
