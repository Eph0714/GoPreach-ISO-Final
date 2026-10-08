package com.emfitsolutions.gopreach.ui.components

import android.util.Log
import com.emfitsolutions.gopreach.data.model.displayName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.sync.PRESENCE_COLLECTION
import com.emfitsolutions.gopreach.data.sync.PRESENCE_ONLINE_TIMEOUT_MS
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.emfitsolutions.gopreach.domain.UserSession
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "OnlineUsersViewModel"

/** Same green already used for a "regular pioneer"-style positive/active state
 * elsewhere in the dashboard (see DashboardReportsScreen's own
 * COLOR_REGULAR_PIONEER) — kept as one literal constant here since a plain
 * red/green pair like this isn't meant to invert with the dark theme the way
 * a theme color would. */
private val OnlineUsersGreen = Color(0xFF2E7D32)

/** One row the "Online Users" list actually shows — resolved by joining a live
 * `presence` row against the same locally-cached Person/RoleAssignment/
 * Congregation data every other admin screen already reads from. */
data class OnlineUserRow(
    val personId: String,
    val fullName: String,
    val roleLabel: String,
    val congregationName: String,
)

private data class PresenceRow(val personId: String, val congregationId: String?, val lastSeen: Long)

private fun PublisherCategory.displayLabel(): String = displayName
    .lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

class OnlineUsersViewModel(
    private val firestore: FirebaseFirestore,
    private val syncApi: com.emfitsolutions.gopreach.data.remote.SyncApi,
    userSession: UserSession,
    personRepository: PersonRepository,
    roleAssignmentRepository: RoleAssignmentRepository,
    congregationRepository: CongregationRepository,
) : ViewModel() {

    /** "GoPreach App — Add Online Users Indicator" spec §9: the congregation
     * restriction is enforced by the query itself — a congregation-based
     * caller's query is server-side scoped to their own `congregationId`,
     * never "ask for everything, then hide the rest in the UI." firestore
     * .rules' `presence` match block then backs this with a real security
     * boundary of its own, not just "the app happens to ask nicely" — see
     * that rule for what stops a modified client from asking anyway. */
    private fun rawPresence(isSuperAdmin: Boolean, congregationId: String?): Flow<List<PresenceRow>> = callbackFlow {
        if (com.emfitsolutions.gopreach.data.sync.BackendConfig.enabled) {
            // Hostinger: no live listener — ask the server for the list every 15 seconds while someone is looking at it.
            val job = launch {
                while (true) {
                    runCatching {
                        val reply = syncApi.postJson("/v1/presence", kotlinx.serialization.json.JsonObject(emptyMap()))
                        val list = reply.body?.get("online") as? kotlinx.serialization.json.JsonArray
                        if (reply.status == 200 && list != null) {
                            // Stamped with this phone's clock so a server/phone clock difference can never make a live user look stale.
                            val received = System.currentTimeMillis()
                            trySend(
                                list.mapNotNull { e ->
                                    val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                    val id = (o["personId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                                    val cong = (o["congregationId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                                    PresenceRow(id, cong, received)
                                },
                            )
                        }
                    }.onFailure { Log.w(TAG, "presence poll failed: ${it.message}") }
                    delay(15_000)
                }
            }
            awaitClose { job.cancel() }
            return@callbackFlow
        }
        if (!isSuperAdmin && congregationId == null) {
            // No congregation to scope to (shouldn't normally happen for an
            // active session) — nothing is visible rather than everything.
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        var query: Query = firestore.collection(PRESENCE_COLLECTION)
        if (!isSuperAdmin) {
            query = query.whereEqualTo("congregationId", congregationId)
        }
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "presence listener failed: ${error.message}")
                return@addSnapshotListener
            }
            val rows = snapshot?.documents?.mapNotNull { d ->
                val lastSeen = d.getLong("lastSeen") ?: return@mapNotNull null
                PresenceRow(d.id, d.getString("congregationId"), lastSeen)
            } ?: emptyList()
            trySend(rows)
        }
        awaitClose { registration.remove() }
    }

    /** Re-evaluated every couple of seconds — not only when a presence
     * document itself changes — so a session that simply stopped
     * heartbeating (closed the app, lost connection, crashed) ages out of
     * the list close to real-time once [PRESENCE_ONLINE_TIMEOUT_MS] elapses,
     * per spec §4/§18 ("stale lastSeen must be treated as Offline") and the
     * follow-up "make the timeout real time" request, instead of only on
     * the next actual Firestore push. */
    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(2_000)
        }
    }

    val onlineUsers: StateFlow<List<OnlineUserRow>> = userSession.state
        .map { it.person }
        .distinctUntilChanged { old, new ->
            old?.id == new?.id && old?.isSuperAdmin == new?.isSuperAdmin && old?.activeCongregationId == new?.activeCongregationId
        }
        .flatMapLatest { person ->
            if (person == null) return@flatMapLatest flowOf(emptyList())
            combine(
                rawPresence(person.isSuperAdmin, person.activeCongregationId),
                ticker(),
                personRepository.observeAll(),
                roleAssignmentRepository.observeAll(),
                congregationRepository.observeAll(),
            ) { presenceRows, _, people, roleAssignments, congregations ->
                val now = System.currentTimeMillis()
                val peopleById = people.associateBy { it.id }
                val congregationNameById = congregations.associateBy({ it.id }, { it.name })
                presenceRows
                    // The one client-side check that matters even though the
                    // query is already server-scoped by congregation: a
                    // stale heartbeat must read as Offline regardless of
                    // what Firestore last pushed (spec §4/§18).
                    .filter { now - it.lastSeen <= PRESENCE_ONLINE_TIMEOUT_MS }
                    .mapNotNull { row ->
                        val p = peopleById[row.personId] ?: return@mapNotNull null
                        val assignments = roleAssignments.filter { it.personId == row.personId }
                        // "Removed/Inactive Users" (spec §10) — the exact same
                        // check PermissionChecker already uses everywhere else
                        // to decide whether an account may use the app at all.
                        if (!PermissionChecker.isAccountUsable(p, assignments)) return@mapNotNull null
                        val roleLabel = PermissionChecker.highestAdminRole(assignments)?.displayLabel()
                            ?: assignments.asSequence()
                                .mapNotNull { it.resolvedRoleTypeOrNull() as? RoleType.Publisher }
                                .firstOrNull()
                                ?.category?.displayLabel()
                            ?: "—"
                        val congregationName = row.congregationId?.let { congregationNameById[it] } ?: "—"
                        OnlineUserRow(row.personId, p.fullName, roleLabel, congregationName)
                    }
                    .sortedBy { it.fullName }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/**
 * "GoPreach App — Add Online Users Indicator" — a compact "🟢 Online Users: N"
 * badge for the Main Form/dashboard; tapping it opens the full, congregation-
 * restricted list (see [OnlineUsersViewModel] for exactly how that
 * restriction is enforced, both client- and rules-side).
 */
@Composable
fun OnlineUsersIndicator(
    modifier: Modifier = Modifier,
    // Callers on a light surface (the default) keep the original
    // onSurfaceVariant/error colors; a caller rendering on a solid dark
    // header background (e.g. the Publisher/Admin welcome header) passes its
    // own light colors so the label and glyph stay readable there too.
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onlineTint: Color = OnlineUsersGreen,
    offlineTint: Color = MaterialTheme.colorScheme.error,
    showIcon: Boolean = true,
    viewModel: OnlineUsersViewModel = koinViewModel(),
) {
    val onlineUsers by viewModel.onlineUsers.collectAsStateWithLifecycle()
    var showList by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.clickable { showList = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Change the online user icon to a man icon. Green if there is
        // online, red if there is 0 online" — a person glyph instead of the
        // plain colored dot, tinted by whether anyone is actually online.
        if (showIcon) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = if (onlineUsers.isNotEmpty()) onlineTint else offlineTint,
                modifier = Modifier.padding(end = 6.dp).size(18.dp),
            )
        }
        Text(
            "Online Users: ${onlineUsers.size}",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }

    if (showList) {
        OnlineUsersDialog(rows = onlineUsers, onDismiss = { showList = false })
    }
}

@Composable
private fun OnlineUsersDialog(rows: List<OnlineUserRow>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true),
        title = { Text("Online Users") },
        text = {
            if (rows.isEmpty()) {
                Text("No users online", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(rows, key = { it.personId }) { row ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(row.fullName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${row.roleLabel} — ${row.congregationName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
