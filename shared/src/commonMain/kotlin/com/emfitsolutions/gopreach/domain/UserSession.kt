package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.platform.Log
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.UserAccessGrant
import com.emfitsolutions.gopreach.data.model.displayLabel
import com.emfitsolutions.gopreach.data.repository.AuthService
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.OfflineSessionMarker
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.emfitsolutions.gopreach.data.repository.personIdFromAuthEmail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Multiple Role Login Detection & Role Selection" spec §2 — one selectable
 * "account" a signed-in [Person] may operate the app as, backed by exactly
 * one of their own ACTIVE [RoleAssignment] rows. See [SessionState.roleOptions]
 * for how these are built. */
data class RoleOption(
    val assignment: RoleAssignment,
    val label: String,
)

data class SessionState(
    val isLoading: Boolean = true,
    val person: Person? = null,
    val roleAssignments: List<RoleAssignment> = emptyList(),
    /** Non-null only for a restricted (Circuit Overseer / custom) user — see
     * [UserAccessGrant] and [PermissionChecker.hasPermission]. */
    val grant: UserAccessGrant? = null,
    /** The congregation's FS Groups, only to name a group role ("Group Coordinator — FS Group 5"). */
    val groups: List<Group> = emptyList(),
    /** True when this session exists only because
     * [com.emfitsolutions.gopreach.data.repository.AuthRepository
     * .offlineSignIn] verified a cached password hash *locally*, with no
     * real `FirebaseAuth.currentUser` behind it — Firebase's SDK has no
     * offline path to populate that itself (see that function's own doc
     * comment). The app-level session looks and behaves like a normal
     * sign-in (Person/RoleAssignments load from the local cache exactly the
     * same way), but every Firestore security rule keyed on
     * `request.auth != null` denies this device outright, *forever*, even
     * once it reconnects — nothing here ever transparently "upgrades" to a
     * real Firebase session, since only [AuthRepository.signIn] (which
     * needs the actual plaintext password, deliberately never stored) can
     * do that. Root cause of "I cannot see the same data on other phone"
     * for a session that started this way: reads/writes needing
     * `isSignedIn()` (nearly everything, including every My Planner
     * collection) fail with PERMISSION_DENIED indefinitely, while
     * publicly-readable collections (`people`, congregations) keep working
     * fine, so the header still shows the right name/congregation and gives
     * no visual sign anything is wrong. See
     * [com.emfitsolutions.gopreach.ui.components.OfflineSessionBanner] for
     * the UI this drives — the only way out is signing out and back in
     * *with* an internet connection. */
    val isOfflineOnlySession: Boolean = false,
) {
    val isSignedIn: Boolean get() = person != null

    /** Spec §9: an account can be deactivated/suspended without deleting it or
     * touching its RoleAssignments — this is what actually revokes the ability
     * to keep using an already-open session once that happens (the sign-in gate
     * in [com.emfitsolutions.gopreach.data.repository.AuthRepository.signIn]
     * only stops a *new* sign-in attempt). [GoPreachNavGraph] signs the session
     * out the moment this goes true. */
    val isAccountBlocked: Boolean get() = person != null && !PermissionChecker.isAccountUsable(person, roleAssignments)

    /** Source of truth for whether the forced-password-change flow (spec §4.5)
     * still applies to this signed-in session — survives app restarts, unlike the
     * one-shot flag [com.emfitsolutions.gopreach.ui.screens.login.LoginUiState] uses
     * right after a fresh sign-in. */
    val requiresPasswordChange: Boolean get() = person?.isTemporaryCredential == true

    /** "Automatically detect all roles associated with the authenticated
     * account... Check for: Admin, Coordinator Elder, Ministerial Servant,
     * Regular Elder, Publisher" — one option per distinct [AdminRole] value
     * this Person actively holds, plus one for an active Publisher category
     * if any. Never hard-coded: every entry here is backed by a real,
     * currently-ACTIVE [RoleAssignment] row (spec §5/§12). A Person holding
     * two RoleAssignments of the very same AdminRole (e.g. Regular Elder in
     * two different Groups) collapses to the first one — the same "pick one"
     * convention this app already uses everywhere else it resolves "a
     * person's own X" (see GoPreachNavGraph's ownGroupAssignment /
     * ownPublisherAssignment, which used to do this same firstOrNull scan
     * directly). */
    val roleOptions: List<RoleOption>
        get() {
            val active = roleAssignments.filter { it.status == RoleAssignmentStatus.ACTIVE }
            val adminOptions = AdminRole.entries.mapNotNull { role ->
                active.firstOrNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == role }
                    ?.let { RoleOption(it, roleLabelFor(it, role, groups)) }
            }
            val publisherOption = active.firstOrNull { it.resolvedRoleTypeOrNull() is RoleType.Publisher }
                ?.let { RoleOption(it, "Publisher") }
            return adminOptions + listOfNotNull(publisherOption)
        }

    /** Unified multi-role account: every ACTIVE Admin-track [RoleAssignment] this person holds, most senior first (enum order of [AdminRole]). */
    val adminAssignments: List<RoleAssignment>
        get() = roleOptions.map { it.assignment }.filter { it.resolvedRoleTypeOrNull() is RoleType.Admin }

    /** The person's own active Publisher assignment, if any - what makes the Publisher Main Form the interface of a multi-role account. */
    val publisherAssignment: RoleAssignment?
        get() = roleOptions.map { it.assignment }.firstOrNull { it.resolvedRoleTypeOrNull() is RoleType.Publisher }

    /** The most senior Admin-track assignment: the one whose congregation / group scope the admin screens and the server-side rules
     * follow. Never chosen by the user - there is no role selection any more. */
    val primaryAdminAssignment: RoleAssignment? get() = adminAssignments.firstOrNull()

    /** The assignments whose permissions are consolidated into this one session: the primary one plus every other Admin-track role in the
     * same congregation (a congregation-wide role covers a group role there). A role held in a different congregation is never merged in,
     * so combining roles cannot widen a person's reach into another congregation's records. Super-Admin is not congregation-bound. */
    val consolidatedAdminAssignments: List<RoleAssignment>
        get() {
            val primary = primaryAdminAssignment ?: return emptyList()
            val role = (primary.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role
            return if (role == AdminRole.SUPER_ADMIN) adminAssignments
            else adminAssignments.filter { it === primary || (it.congregationId != null && it.congregationId == primary.congregationId) }
        }

    /** Every Admin-track role consolidated into this session, most senior first (distinct). */
    val consolidatedAdminRoles: List<AdminRole>
        get() = consolidatedAdminAssignments.mapNotNull { (it.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role }.distinct()

    /** One account, all its roles: the Admin-track role that scopes the session, else the Publisher assignment. Resolves by itself - once
     * the roles have loaded there is never a prompt to choose. */
    val activeRoleAssignment: RoleAssignment?
        get() = primaryAdminAssignment ?: publisherAssignment

    val activeAdminRole: AdminRole?
        get() = (activeRoleAssignment?.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role

    /** The person holds an active Publisher category: the Publisher Main Form is their interface, with every other role's modules in its side panel. */
    val isActivePublisherRole: Boolean
        get() = publisherAssignment != null

    /** Every role this account holds, in words ("Coordinator Elder + Publisher"), shown throughout the app. */
    val activeRoleLabel: String?
        get() = roleOptions.map { it.label }.takeIf { it.isNotEmpty() }?.joinToString(" + ")

    /** The FS Group of an active Group Coordinator / Servant / Assistant role; null for every other role (congregation-wide or none). */
    val activeGroupId: String?
        get() = activeRoleAssignment?.takeIf { groupRoleOf(it) != null }?.groupId

    /** Whether the active role is a group role — scoped to ONE FS Group, never the whole congregation. */
    val isGroupRole: Boolean get() = activeGroupId != null
}

/** The group slot an Elder / Ministerial-Servant assignment fills (Group Coordinator = overseer, Servant, Assistant), or null. */
fun groupRoleOf(assignment: RoleAssignment): RegularElderRole? =
    if ((assignment.resolvedRoleTypeOrNull() as? RoleType.Admin)?.role == AdminRole.REGULAR_ELDER && assignment.groupId != null) assignment.regularElderRole else null

/** "Group Coordinator — FS Group 5" for a group role; the plain role name for everything else. */
fun roleLabelFor(assignment: RoleAssignment, role: AdminRole, groups: List<Group>): String {
    val slot = groupRoleOf(assignment) ?: return role.displayLabel()
    val title = when (slot) {
        RegularElderRole.GROUP_OVERSEER -> "Group Coordinator"
        RegularElderRole.GROUP_SERVANT -> "Group Servant"
        RegularElderRole.GROUP_ASSISTANT -> "Group Assistant"
    }
    val name = groups.firstOrNull { it.id == assignment.groupId }?.name
    return if (name.isNullOrBlank()) title else "$title — $name"
}

/**
 * App-wide observable session, rebuilt from Firebase Auth's persisted sign-in state
 * (survives process death — no separate "remember me" needed) plus the signed-in
 * Person's [RoleAssignment]s, which drive every permission check via
 * [PermissionChecker].
 */
/** What the signed-in Person is acting as right now — mirrored onto their Person document so the security rules can see it. */
private data class ActiveContext(val person: Person, val congregationId: String?, val roleName: String?, val groupId: String?)

class UserSession(
    private val auth: AuthService,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val userAccessGrantRepository: UserAccessGrantRepository,
    private val groupRepository: GroupRepository,
    private val offlineSessionMarker: OfflineSessionMarker,
    private val syncEngine: com.emfitsolutions.gopreach.data.sync.SyncEngine,
    appScope: CoroutineScope,
) {
    private fun authStateFlow(): Flow<String?> = auth.authState().map { personIdFromAuthEmail(it?.email) }

    /** Firebase's own persisted sign-in wins when present (the normal case —
     * survives app restart with no network needed on its own); [OfflineSessionMarker]
     * only matters for [com.emfitsolutions.gopreach.data.repository.AuthRepository
     * .offlineSignIn], which Firebase's SDK has no way to reflect into
     * `currentUser` itself since it never makes a network call. */
    private fun signedInPersonIdFlow(): Flow<String?> =
        combine(authStateFlow(), offlineSessionMarker.personId) { firebaseId, offlineId -> firebaseId ?: offlineId }

    /** Group Chat Setting's security-rules trick (see [Person
     * .activeCongregationId]'s doc comment) — keeps that denormalized copy
     * current every time this session's own active role changes. Admin/
     * Coordinator Elder/Service Overseer/Ministerial Servant get their
     * congregation for [activeAdminRole]'s sake (that pair is what Group
     * Chat's own rules key off); a Regular Elder's own group/congregation
     * isn't a "manage every group chat in this congregation" scope the same
     * way, so that one resolves to null here (unaffected: they can still be
     * added as a participant and reach a chat through [GroupChat
     * .participantIds] alone, which needs no congregation match).
     *
     * A Publisher active role also resolves [congregationId] now — the
     * Territory Congregation restriction on Return Visits/Bible Studies/
     * Visit History needs a Publisher's own enrolled congregation available
     * to firestore.rules the same way Group Chat's admin-track congregation
     * already is; [role]/[activeAdminRole] deliberately stays null for a
     * Publisher (it never holds an [AdminRole]), so this is additive and
     * doesn't change anything [canManageGroupChatsFor] already checks (that
     * function still requires [activeAdminRole] to be one of the four admin
     * roles above, which a Publisher-only session never satisfies).
     *
     * Fires once per actual change, not on every recomposition-driven re-read
     * of [state] — [distinctUntilChanged] on the resolved pair below, not on
     * [SessionState] itself (which differs on every roleAssignments/grant
     * emission even when the *active* role hasn't moved).
     */
    private fun syncActiveRoleContext(appScope: CoroutineScope, personRepository: PersonRepository) {
        appScope.launch {
            state
                .map { s ->
                    val person = s.person ?: return@map null
                    val resolvedRole = s.activeRoleAssignment?.resolvedRoleTypeOrNull()
                    val role = (resolvedRole as? RoleType.Admin)?.role
                    // Bug fix ("Sync Error" — PERMISSION_DENIED stuck permanent
                    // for a Regular Elder): this role list used to omit
                    // AdminRole.REGULAR_ELDER, so a Regular Elder's
                    // `activeCongregationId` was always denormalized as
                    // `null` here even though their own RoleAssignment
                    // always carries a real one (see
                    // EldersEnrollmentViewModel — every role, Regular Elder
                    // included, is created with `congregationId` set; only
                    // `groupId` is the Regular-Elder-specific extra scope).
                    // firestore.rules' `canManagePublisherReportsFor` (Monthly
                    // Report writes, and the Territory Map/Return Visit
                    // ownership rule) explicitly checks
                    // `myPerson(db).activeCongregationId` for this exact
                    // role — against a value that could only ever be `null`,
                    // that check could never pass, so every one of those
                    // writes failed outright the instant the corresponding
                    // rule was enforced server-side.
                    val congregationId = when {
                        role in setOf(AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY, AdminRole.MINISTERIAL_SERVANT, AdminRole.REGULAR_ELDER) ->
                            s.activeRoleAssignment?.congregationId
                        resolvedRole is RoleType.Publisher -> s.activeRoleAssignment?.congregationId
                        else -> null
                    }
                    ActiveContext(person, congregationId, role?.name, s.activeGroupId)
                }
                .distinctUntilChanged { old, new ->
                    old?.congregationId == new?.congregationId && old?.roleName == new?.roleName && old?.groupId == new?.groupId && old?.person?.id == new?.person?.id
                }
                .collect { ctx ->
                    val (person, congregationId, roleName, groupId) = ctx ?: return@collect
                    var contextSaved = true
                    if (person.activeCongregationId != congregationId || person.activeAdminRole != roleName || person.activeGroupId != groupId) {
                        contextSaved = runCatching {
                            personRepository.saveNow(person.copy(activeCongregationId = congregationId, activeAdminRole = roleName, activeGroupId = groupId))
                        }.onSuccess { runCatching { syncEngine.resetCursor() } }
                            .onFailure { Log.w("UserSession", "Failed to sync active role context: ${it.message}") }
                            .isSuccess
                    }
                    // Only after the server knows the active congregation: pull everything once more so its FS Groups and territories (the map's boundaries) arrive.
                    if (contextSaved) runCatching { syncEngine.repullOnceFor(person.id, congregationId) }
                }
        }
    }

    val state: StateFlow<SessionState> = signedInPersonIdFlow()
        .let { personIdFlow ->
            personIdFlow.flatMapLatest { personId ->
                if (personId == null) {
                    flowOf(SessionState(isLoading = false, person = null, roleAssignments = emptyList()))
                } else {
                    combine(
                        personRepository.observeAll().map { people -> people.firstOrNull { it.id == personId } },
                        roleAssignmentRepository.observeForPerson(personId),
                        userAccessGrantRepository.observeForPerson(personId),
                        groupRepository.observeAll(),
                    ) { person, roles, grant, groups ->
                        SessionState(
                            isLoading = false,
                            person = person,
                            roleAssignments = roles,
                            grant = grant,
                            groups = groups,
                            // See this field's own doc comment — re-checked on
                            // every emission (not just once at sign-in) so a
                            // session that started offline-only is correctly
                            // still flagged even much later, and would clear
                            // automatically if something else in the app ever
                            // did complete a real Firebase sign-in in the
                            // background.
                            isOfflineOnlySession = auth.currentUser == null,
                        )
                    }
                }
            }
        }
        .stateIn(appScope, SharingStarted.Eagerly, SessionState(isLoading = true))

    init {
        syncActiveRoleContext(appScope, personRepository)
    }
}
