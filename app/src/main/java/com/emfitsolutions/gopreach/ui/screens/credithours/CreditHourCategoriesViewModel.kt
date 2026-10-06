package com.emfitsolutions.gopreach.ui.screens.credithours

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.CreditHourCategory
import com.emfitsolutions.gopreach.data.repository.CreditHourCategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What happened when an admin asked to delete a category. */
sealed interface CategoryDeleteCheck {
    data object Checking : CategoryDeleteCheck
    /** Not referenced by any Credit Hour entry — safe to remove for good. */
    data class Unused(val category: CreditHourCategory) : CategoryDeleteCheck
    /** Referenced by existing entries (or usage couldn't be verified) —
     * only deactivation is offered, so history keeps its category. */
    data class InUse(val category: CreditHourCategory, val couldNotVerify: Boolean) : CategoryDeleteCheck
}

/** Credit Hour Categories CRUD for Super-Admin / Admin (the route itself is
 * role-gated in the nav graph; Publishers only ever *read* this list from
 * the Credit Hours form). */
@HiltViewModel
class CreditHourCategoriesViewModel @Inject constructor(
    private val repository: CreditHourCategoryRepository,
) : ViewModel() {

    /** `null` until the local cache has answered once. */
    val categories: StateFlow<List<CreditHourCategory>?> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _deleteCheck = MutableStateFlow<CategoryDeleteCheck?>(null)
    val deleteCheck: StateFlow<CategoryDeleteCheck?> = _deleteCheck.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { repository.ensureDefaultCategories() }
    }

    fun save(category: CreditHourCategory) {
        val name = category.name.trim()
        val duplicate = categories.value.orEmpty().any { it.id != category.id && it.name.trim().equals(name, ignoreCase = true) }
        if (duplicate) {
            _messages.trySend("A category named \"$name\" already exists.")
            return
        }
        viewModelScope.launch {
            runCatching { repository.save(category.copy(name = name)) }
                .onSuccess { _messages.trySend(if (category.id.isBlank()) "Category added." else "Category updated.") }
                .onFailure { _messages.trySend("Could not save category: ${it.message ?: "unknown error"}") }
        }
    }

    fun setActive(category: CreditHourCategory, active: Boolean) {
        viewModelScope.launch {
            runCatching { repository.save(category.copy(active = active)) }
                .onSuccess { _messages.trySend(if (active) "\"${category.name}\" activated." else "\"${category.name}\" deactivated.") }
                .onFailure { _messages.trySend("Could not update category: ${it.message ?: "unknown error"}") }
        }
    }

    /** Step 1 of Delete: ask the server whether any entry still uses it. */
    fun requestDelete(category: CreditHourCategory) {
        _deleteCheck.value = CategoryDeleteCheck.Checking
        viewModelScope.launch {
            _deleteCheck.value = runCatching { repository.countRecordsUsing(category.id) }.fold(
                onSuccess = { count -> if (count == 0) CategoryDeleteCheck.Unused(category) else CategoryDeleteCheck.InUse(category, couldNotVerify = false) },
                onFailure = { CategoryDeleteCheck.InUse(category, couldNotVerify = true) },
            )
        }
    }

    fun dismissDelete() {
        _deleteCheck.value = null
    }

    /** Step 2: only ever reached from [CategoryDeleteCheck.Unused]. */
    fun confirmDelete(category: CreditHourCategory) {
        _deleteCheck.value = null
        viewModelScope.launch {
            runCatching { repository.delete(category.id) }
                .onSuccess { _messages.trySend("\"${category.name}\" deleted.") }
                .onFailure { _messages.trySend("Could not delete category: ${it.message ?: "unknown error"}") }
        }
    }

    fun deactivateInstead(category: CreditHourCategory) {
        _deleteCheck.value = null
        setActive(category, false)
    }
}
