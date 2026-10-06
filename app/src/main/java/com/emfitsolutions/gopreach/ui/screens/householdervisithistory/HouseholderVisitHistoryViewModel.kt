package com.emfitsolutions.gopreach.ui.screens.householdervisithistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.PipelineStage
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.Visit
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Record Type" filter — spec's exact three categories plus "All", backed by
 * the existing [PipelineStage] a householder already sits at (no new field). */
enum class RecordTypeFilter(val label: String) {
    ALL("All"),
    FOUND_INTERESTED("Found Interested"),
    RETURN_VISITS("Return Visits"),
    BIBLE_STUDIES("Bible Studies"),
}

private fun RecordTypeFilter.matches(stage: PipelineStage): Boolean = when (this) {
    RecordTypeFilter.ALL -> true
    RecordTypeFilter.FOUND_INTERESTED -> stage == PipelineStage.SEARCHING
    RecordTypeFilter.RETURN_VISITS -> stage == PipelineStage.RETURN_VISIT
    RecordTypeFilter.BIBLE_STUDIES -> stage == PipelineStage.BIBLE_STUDY
}

/** "Simplify the Filter... ONE search/filter dropdown" — replaces the three
 * separate Province/Municipality/Barangay dropdowns this module used to have
 * with one field-picker plus one text box (see [HouseholderVisitHistoryScreen]).
 * Province/Municipality/Barangay data itself is untouched — still stored and
 * displayed on the record (spec §2/§3) — only how a person *searches* by it
 * is simplified. */
enum class SearchByField(val label: String) {
    ALL("All"),
    NAME("Name"),
    PROVINCE("Province"),
    MUNICIPALITY("Municipalities"),
    BARANGAY("Barangay"),
}

private fun HouseholderRow.matchesSearch(field: SearchByField, query: String): Boolean {
    if (query.isBlank()) return true
    fun String?.has() = this != null && contains(query, ignoreCase = true)
    return when (field) {
        // "search across the relevant... especially House Holder Name,
        // Province, Municipality, Barangay" — exactly those four fields,
        // not a blind whole-record text match (spec §18's own "do not
        // perform unreliable text matching" still applies to *this*, the
        // one remaining free-text box).
        SearchByField.ALL -> person.name.has() || person.province.has() || person.cityMunicipality.has() || person.barangay.has()
        SearchByField.NAME -> person.name.has()
        SearchByField.PROVINCE -> person.province.has()
        SearchByField.MUNICIPALITY -> person.cityMunicipality.has()
        SearchByField.BARANGAY -> person.barangay.has()
    }
}

/** One householder row this module shows — [visits] already sorted newest
 * first (spec §10/§17: "ALWAYS ... descending date order", visit date
 * primary, [Visit.createdAt] as the tie-break for a same-date pair).
 * [publisherName]/[congregationName] are resolved once here so the screen
 * never needs to re-look them up per recomposition. */
data class HouseholderRow(
    val person: InterestedPerson,
    val publisherName: String?,
    val congregationName: String,
    val visits: List<Visit>,
)

data class HouseholderVisitHistoryUiState(
    val isLoading: Boolean = true,
    val congregations: List<Congregation> = emptyList(),
    /** "Search By Congregation" (Super-Admin only) — `null` is "All
     * Congregations". Always `null` for a scoped (non-Super-Admin) session,
     * since [HouseholderVisitHistoryViewModel.setCongregationFilter] is a
     * no-op for one. */
    val congregationFilter: String? = null,
    val recordType: RecordTypeFilter = RecordTypeFilter.ALL,
    val searchByField: SearchByField = SearchByField.ALL,
    val searchQuery: String = "",
    val rows: List<HouseholderRow> = emptyList(),
)

/**
 * "House Holder Visit History" module — a consolidated view over the
 * *existing* Searching/Return Visit/Bible Study records
 * ([InterestedPersonRepository]) and their [Visit] history
 * ([VisitRepository]), for Super-Admin (every authorized congregation) and
 * Publisher (their own congregation) accounts. Builds nothing new: every
 * field this screen shows already exists on [InterestedPerson]/[Visit]; add/
 * edit/delete of an individual Visit (spec §8-§14) reuses
 * [com.emfitsolutions.gopreach.ui.screens.pipeline.PipelinePersonDetailScreen]
 * unchanged rather than a second, parallel implementation of the same
 * per-entry-ownership rules that screen (and firestore.rules' `visits` match
 * block) already enforce correctly — this ViewModel stays a pure
 * search/filter/list layer.
 *
 * [restrictTo] is the actual security boundary (spec §16/§24/§25), same
 * convention every other congregation-scoped screen in this app uses (see
 * [com.emfitsolutions.gopreach.ui.screens.pipeline.ElderInterestedRecordsScreen]) —
 * `null` means Super-Admin's unscoped "every congregation," a real id means
 * exactly that one congregation and nothing else; the UI never exposes a way
 * to escape whichever of the two the caller passed in.
 */
@HiltViewModel
class HouseholderVisitHistoryViewModel @Inject constructor(
    private val interestedPersonRepository: InterestedPersonRepository,
    private val visitRepository: VisitRepository,
    private val personRepository: PersonRepository,
    private val congregationRepository: CongregationRepository,
) : ViewModel() {

    private val congregationId = MutableStateFlow<String?>(null)
    // "Super Admin – Congregation Filter": "All Congregations" (null) or one
    // specific congregation, on top of [congregationId] — deliberately a
    // *separate* piece of state from the actual security scope, never merged
    // into it. Only ever has an effect when [congregationId] is itself null
    // (Super-Admin's unscoped session, see [restrictTo]'s own doc comment);
    // [setCongregationFilter] below is a no-op for anyone else, so even a
    // caller that bypassed the UI (which never shows this dropdown to a
    // scoped session at all) can't use it to reach another congregation —
    // the real boundary stays [congregationId] itself.
    private val congregationFilter = MutableStateFlow<String?>(null)
    private val recordType = MutableStateFlow(RecordTypeFilter.ALL)
    private val searchByField = MutableStateFlow(SearchByField.ALL)
    private val searchQuery = MutableStateFlow("")
    private var restricted = false

    /** Spec §14/§15/§16 — called once from the nav graph with the signed-in
     * session's actual authorized scope; a no-op on every later recomposition
     * (`restricted` latches) so a stray recomposition can never silently
     * re-widen an already-narrowed Publisher session back to `null`/unscoped. */
    fun restrictTo(scopedCongregationId: String?) {
        if (restricted) return
        restricted = true
        congregationId.value = scopedCongregationId
    }

    /** "Search By Congregation: All Congregations / Specific Congregation" —
     * Super-Admin only in effect (see [congregationFilter]'s own doc
     * comment); `null` means "All Congregations". */
    fun setCongregationFilter(value: String?) {
        if (congregationId.value != null) return
        congregationFilter.value = value
    }

    fun setRecordType(value: RecordTypeFilter) { recordType.value = value }
    fun setSearchByField(value: SearchByField) { searchByField.value = value }
    fun setSearchQuery(value: String) { searchQuery.value = value }

    init {
        // Same broad, screen-lifetime collection-group listener the
        // Consolidated Report already starts for "every Visit, every
        // Interested Person" — reused rather than duplicated (spec §26).
        viewModelScope.launch { visitRepository.startRemoteSyncAllForCongregationView().collect {} }
    }

    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** id -> full name for every Person — used by the PDF/Excel export's own
     * "Recorded By" column (spec §7/§21/§22), independent of the on-screen
     * detail view's own live per-visit name lookups. */
    val personNames: StateFlow<Map<String, String>> = personRepository.observeAll()
        .map { people -> people.associate { it.id to it.fullName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // The real security scope is [congregationId]; a Super-Admin's own
    // "Specific Congregation" choice ([congregationFilter]) can only ever
    // narrow *within* it, never widen past it — this `?:` order means a
    // non-null [congregationId] (any scoped role) always wins regardless of
    // [congregationFilter]'s value. Combined into one flow first so the
    // 5-flow [combine] below stays within kotlinx.coroutines' typed
    // (non-vararg) overloads.
    private val effectiveCongregationIdFlow = combine(congregationId, congregationFilter) { scoped, filter -> scoped ?: filter }

    private val scopedRowsFlow = combine(
        interestedPersonRepository.observeAll(),
        visitRepository.observeAllVisits(),
        personRepository.observeAll(),
        congregationRepository.observeAll(),
        effectiveCongregationIdFlow,
    ) { people, visits, persons, congregations, effectiveCongregationId ->
        val personNameById = persons.associate { it.id to it.fullName }
        val congregationNameById = congregations.associate { it.id to it.name }
        val visitsByPerson = visits.groupBy { it.interestedPersonId }
        people
            .filter { it.status == RecordStatus.ACTIVE }
            .filter { effectiveCongregationId != null && it.congregationId == effectiveCongregationId }
            .map { person ->
                HouseholderRow(
                    person = person,
                    publisherName = personNameById[person.publisherPersonId],
                    congregationName = congregationNameById[person.congregationId] ?: "—",
                    // "Get ALL visit history... do not show only the latest
                    // visit... unless there is a performance reason" (spec
                    // §6/§22) — every visit for this person, always, sorted
                    // newest-first.
                    visits = (visitsByPerson[person.id].orEmpty())
                        .sortedWith(compareByDescending<Visit> { it.visitDate }.thenByDescending { it.createdAt }),
                )
            }
    }

    private data class FilterState(val recordType: RecordTypeFilter, val searchByField: SearchByField, val searchQuery: String)

    private val filterState = combine(recordType, searchByField, searchQuery) { rt, field, q -> FilterState(rt, field, q) }

    val uiState: StateFlow<HouseholderVisitHistoryUiState> = combine(
        scopedRowsFlow, congregations, filterState, congregationFilter,
    ) { scopedRows, congregations, filter, congregationFilterValue ->
        val filteredRows = scopedRows
            .filter { filter.recordType.matches(it.person.pipelineStage) }
            .filter { it.matchesSearch(filter.searchByField, filter.searchQuery) }
            .sortedBy { it.person.name }
        HouseholderVisitHistoryUiState(
            isLoading = false,
            congregations = congregations,
            congregationFilter = congregationFilterValue,
            recordType = filter.recordType,
            searchByField = filter.searchByField,
            searchQuery = filter.searchQuery,
            rows = filteredRows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HouseholderVisitHistoryUiState())
}
