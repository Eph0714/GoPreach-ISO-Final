package com.emfitsolutions.gopreach.data.repository

import kotlinx.serialization.Serializable

// ---- The Previous Manual Record analysis: built by the server (backend/src/manualAnalysis.js, wording by the free self-hosted AI in aiSummary.js).
// The app shows exactly what the server returned and reads aloud its narration; it calculates nothing of its own.

@Serializable data class AnalysisSource(val fileName: String = "", val sheet: String = "", val congregation: String = "", val savedAt: Long? = null)
@Serializable data class AnalysisGroupRef(val id: String = "", val kind: String = "", val title: String = "")
@Serializable data class AnalysisSelection(val previous: String = "", val current: String = "")
@Serializable data class AnalysisValue(val period: String = "", val value: Double? = null)
@Serializable
data class AnalysisCard(
    val label: String = "", val unit: String = "", val polarity: Int = 0, val direction: String = "unavailable", val previous: Double? = null, val current: Double? = null,
    val difference: Double? = null, val percent: Double? = null, val note: String = "", val previousCell: String? = null, val currentCell: String? = null,
    val values: List<AnalysisValue> = emptyList(),
)
@Serializable data class AnalysisSeries(val label: String = "", val values: List<Double?> = emptyList())
@Serializable
data class AnalysisChart(
    val id: String = "", val kind: String = "count", val title: String = "", val unit: String = "", val periods: List<String> = emptyList(),
    val series: List<AnalysisSeries> = emptyList(), val omitted: Int = 0, val note: String = "",
)
@Serializable data class AnalysisEvidence(val label: String = "", val period: String = "", val value: Double? = null, val cell: String? = null)
@Serializable
data class AnalysisFinding(
    val id: String = "", val kind: String = "fact", val priority: Int = 0, val text: String = "", val explanation: String? = null, val evidence: List<AnalysisEvidence> = emptyList(),
)
@Serializable data class AnalysisQuality(val notes: List<String> = emptyList(), val rows: Int = 0)

/** What the AI did: OK, DISABLED, UNAVAILABLE, TIMEOUT, INVALID, REJECTED, TOO_LARGE, NOT_REQUESTED or SKIPPED. Only OK means its wording is on the screen. */
@Serializable data class AnalysisAi(val status: String = "NOT_REQUESTED", val provider: String? = null, val model: String? = null, val message: String = "", val usedFor: List<String> = emptyList())

@Serializable
data class ManualAnalysis(
    val available: Boolean = false, val message: String = "", val generatedAt: String = "", val source: AnalysisSource = AnalysisSource(),
    val group: AnalysisGroupRef = AnalysisGroupRef(), val groups: List<CompareGroup> = emptyList(), val selection: AnalysisSelection = AnalysisSelection(),
    val periods: List<CompareColumn> = emptyList(), val report: ComparisonReport? = null, val cards: List<AnalysisCard> = emptyList(), val charts: List<AnalysisChart> = emptyList(),
    val findings: List<AnalysisFinding> = emptyList(), val quality: AnalysisQuality = AnalysisQuality(), val executive: List<String> = emptyList(),
    val narration: List<String> = emptyList(), val ai: AnalysisAi = AnalysisAi(),
) {
    val aiOk: Boolean get() = ai.status == "OK"
}
