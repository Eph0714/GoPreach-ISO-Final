package com.emfitsolutions.gopreach.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MyScopeSummaryViewModel @Inject constructor(
    congregationRepository: CongregationRepository,
    groupRepository: GroupRepository,
) : ViewModel() {
    val congregations: StateFlow<List<Congregation>> =
        congregationRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups: StateFlow<List<Group>> =
        groupRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/**
 * "My Group / My Congregation" summary card (backlog item from the Elder
 * Roles work) — an Elder's underlying access is already scoped to their own
 * congregation/group everywhere (see [AdminHomeScreen]'s `visibleCongregationIds`
 * and the Reports screens), this is purely a name label on the Main Form so
 * an Elder can see *which* congregation/group that scope actually refers to,
 * rather than only inferring it from unlabeled numbers.
 */
@Composable
fun MyScopeSummaryCard(
    congregationId: String?,
    groupId: String?,
    modifier: Modifier = Modifier,
    viewModel: MyScopeSummaryViewModel = hiltViewModel(),
) {
    if (congregationId == null && groupId == null) return
    val congregations by viewModel.congregations.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    val congregationName = congregations.firstOrNull { it.id == congregationId }?.name
    val groupName = groups.firstOrNull { it.id == groupId }?.name
    if (congregationName == null && groupName == null) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("My Scope", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (congregationName != null) {
                Text("Congregation: $congregationName", style = MaterialTheme.typography.bodyMedium)
            }
            if (groupName != null) {
                Text("Group: $groupName", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
