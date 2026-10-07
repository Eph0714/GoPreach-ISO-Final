package com.emfitsolutions.gopreach.domain

/** How a person's name is written and asked for: "Last, First" or "First Last". */
enum class NameOrder(val label: String) {
    LAST_FIRST("Last name first"),
    FIRST_LAST("First name first"),
}

/**
 * The user's current choice, readable from plain model code ([com.emfitsolutions.gopreach.data.model.Person.fullName]).
 * [com.emfitsolutions.gopreach.data.repository.NameOrderPreference] owns the stored value and keeps this in step.
 */
object NameOrderState {
    @kotlin.concurrent.Volatile var current: NameOrder = NameOrder.LAST_FIRST
}

/** "FERNANDEZ, EPHRAIM M. JR." (last first) or "EPHRAIM M. FERNANDEZ JR." (first first). Blank parts are skipped. */
fun formatPersonName(first: String?, middleInitial: String?, last: String?, extension: String?, order: NameOrder = NameOrderState.current): String {
    val given = listOfNotNull(first?.takeIf { it.isNotBlank() }, middleInitial?.takeIf { it.isNotBlank() }?.let { "$it." }).joinToString(" ")
    val family = last?.takeIf { it.isNotBlank() }.orEmpty()
    val ext = extension?.takeIf { it.isNotBlank() }
    return when (order) {
        NameOrder.LAST_FIRST -> listOf(
            listOf(family, given).filter { it.isNotEmpty() }.joinToString(", "),
            ext.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString(" ")
        NameOrder.FIRST_LAST -> listOf(given, family, ext.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
