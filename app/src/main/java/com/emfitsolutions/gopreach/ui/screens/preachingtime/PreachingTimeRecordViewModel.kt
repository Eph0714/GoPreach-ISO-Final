package com.emfitsolutions.gopreach.ui.screens.preachingtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.PreachingTimeRecord
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.repository.PreachingTimeRecordRepository
import com.emfitsolutions.gopreach.domain.DateRangeStore
import com.emfitsolutions.gopreach.ui.components.DateRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Preaching Time Record Module" spec §12-§15 — Pioneer-only CRUD. Territory
 * is deliberately not part of this screen's Add/Edit form or list display
 * (product decision: preaching time is reported without a territory) — the
 * model still carries `territoryId` for any pre-existing records, it's just
 * never shown or asked for here. */
@HiltViewModel
class PreachingTimeRecordViewModel @Inject constructor(
    private val preachingTimeRecordRepository: PreachingTimeRecordRepository,
    private val dateRangeStore: DateRangeStore,
) : ViewModel() {

    /** Spec §15 — shares the same [DateRangeStore] as the Dashboard, so
     * navigating here from a tapped "Preaching Hours" card keeps the range
     * the user already chose instead of resetting it. */
    val dateRange: StateFlow<DateRange> = dateRangeStore.range
    fun setDateRange(range: DateRange) = dateRangeStore.set(range)

    fun recordsFor(publisherPersonId: String): Flow<List<PreachingTimeRecord>> =
        preachingTimeRecordRepository.observeForPublisher(publisherPersonId)

    fun save(record: PreachingTimeRecord) {
        viewModelScope.launch { preachingTimeRecordRepository.save(record) }
    }

    fun setStatus(record: PreachingTimeRecord, status: RecordStatus) {
        viewModelScope.launch { preachingTimeRecordRepository.setStatus(record, status) }
    }

    fun permanentlyDelete(recordId: String) {
        viewModelScope.launch { preachingTimeRecordRepository.permanentlyDelete(recordId) }
    }
}
