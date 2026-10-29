package com.emfitsolutions.gopreach.domain.map

import com.emfitsolutions.gopreach.data.model.DrawingStatus
import com.emfitsolutions.gopreach.platform.formatDate
import kotlinx.datetime.TimeZone

/**
 * The details written inside a territory drawing on the map: its name, its status with the date that goes with it (finished / to continue /
 * to do), and the first line of its remarks. Every part is optional; the status always shows.
 */
object DrawingLabel {
    private const val MAX_REMARKS = 40

    fun text(status: DrawingStatus, statusDate: Long, name: String, remarks: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        val lines = ArrayList<String>(3)
        name.trim().takeIf { it.isNotEmpty() }?.let { lines += it }
        lines += if (statusDate > 0L) "${status.label} · ${dateText(statusDate, timeZone)}" else status.label
        remarks.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            lines += if (it.length > MAX_REMARKS) it.take(MAX_REMARKS - 1).trimEnd() + "…" else it
        }
        return lines.joinToString("\n")
    }

    /** "Oct 29, 2026". */
    fun dateText(millis: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String = formatDate(millis, "MMM d, yyyy", timeZone)
}
