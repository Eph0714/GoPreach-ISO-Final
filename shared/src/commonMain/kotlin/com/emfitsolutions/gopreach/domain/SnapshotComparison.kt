package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.CoReceivedReport
import com.emfitsolutions.gopreach.data.model.CoReceivedRow

/** The figures of one snapshot that the Circuit Overseer compares. */
data class SnapshotTotals(
    val publishers: Int,
    /** Lines per status label ("Pub", "AP", "RP", ...). */
    val byStatus: Map<String, Int>,
    val participated: Int,
    val hours: Double,
    val bibleStudies: Int,
)

/** A publisher whose status label changed between two sends of the same month. */
data class Reclassified(val name: String, val from: String, val to: String)

/** A publisher present in both sends whose hours or Bible Studies differ. */
data class FigureChange(val name: String, val hoursBefore: Double?, val hoursAfter: Double?, val studiesBefore: Int?, val studiesAfter: Int?)

/**
 * What changed between two sends of the SAME congregation and the SAME service month: the totals side by side, and which publishers
 * were added, removed, reclassified, or reported different figures. Different months are never compared as revisions of one report.
 */
data class SnapshotDiff(
    val previous: CoReceivedReport,
    val current: CoReceivedReport,
    val previousTotals: SnapshotTotals,
    val currentTotals: SnapshotTotals,
    val added: List<CoReceivedRow>,
    val removed: List<CoReceivedRow>,
    val reclassified: List<Reclassified>,
    val figureChanges: List<FigureChange>,
) {
    /** Status labels in either send, in a stable order. */
    val statusLabels: List<String> get() = (previousTotals.byStatus.keys + currentTotals.byStatus.keys).distinct().sorted()
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || reclassified.isNotEmpty() || figureChanges.isNotEmpty() || previousTotals != currentTotals
}

object SnapshotComparison {
    fun totalsOf(report: CoReceivedReport) = SnapshotTotals(
        publishers = report.publisherCount,
        byStatus = report.countByStatus,
        participated = report.participatedCount,
        hours = report.totalHours,
        bibleStudies = report.totalBibleStudies,
    )

    /** A line's identity: the publisher id when the send carries one, otherwise the name (older sends). */
    private fun keyOf(row: CoReceivedRow, byIdOnly: Boolean): String = if (byIdOnly && row.personId.isNotBlank()) "id:" + row.personId else "name:" + row.name.trim().lowercase()

    /**
     * Compares [current] against [previous], or returns null when they are not two sends of the same congregation's same month (a
     * snapshot is only ever compared with an earlier snapshot of that same reporting month).
     */
    fun compare(previous: CoReceivedReport, current: CoReceivedReport): SnapshotDiff? {
        if (previous.congregationId != current.congregationId || previous.periodMonth != current.periodMonth) return null
        val byId = previous.rows.all { it.personId.isNotBlank() } && current.rows.all { it.personId.isNotBlank() }
        val before = previous.rows.associateBy { keyOf(it, byId) }
        val after = current.rows.associateBy { keyOf(it, byId) }
        val added = after.filterKeys { it !in before }.values.sortedBy { it.number }
        val removed = before.filterKeys { it !in after }.values.sortedBy { it.number }
        val reclassified = mutableListOf<Reclassified>()
        val figures = mutableListOf<FigureChange>()
        after.forEach { (key, now) ->
            val was = before[key] ?: return@forEach
            if (was.status != now.status) reclassified += Reclassified(now.name, was.status, now.status)
            if (was.hours != now.hours || was.bibleStudies != now.bibleStudies) figures += FigureChange(now.name, was.hours, now.hours, was.bibleStudies, now.bibleStudies)
        }
        return SnapshotDiff(
            previous, current, totalsOf(previous), totalsOf(current), added, removed,
            reclassified.sortedBy { it.name.lowercase() }, figures.sortedBy { it.name.lowercase() },
        )
    }

    /** The send of the same month immediately before [current] (the highest earlier version), or null for the first send. */
    fun previousSend(current: CoReceivedReport, sendsOfThatMonth: List<CoReceivedReport>): CoReceivedReport? =
        sendsOfThatMonth.filter { it.congregationId == current.congregationId && it.periodMonth == current.periodMonth && it.version < current.version }
            .maxByOrNull { it.version }
}
