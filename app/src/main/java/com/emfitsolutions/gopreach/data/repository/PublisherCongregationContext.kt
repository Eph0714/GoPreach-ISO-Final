package com.emfitsolutions.gopreach.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The congregation a Super-Admin is currently working in inside the Publisher module. The Publisher list sets it
 * when they pick (or change) a congregation, and Add Publisher reads it so a new publisher starts in that same
 * congregation without choosing it again. Only a Super-Admin ever sets it; everyone else is fixed to their own
 * congregation by their role. Lives only in memory.
 */
class PublisherCongregationContext() {
    private val _selectedCongregationId = MutableStateFlow<String?>(null)
    val selectedCongregationId: StateFlow<String?> = _selectedCongregationId.asStateFlow()

    fun select(congregationId: String?) { _selectedCongregationId.value = congregationId }
}
