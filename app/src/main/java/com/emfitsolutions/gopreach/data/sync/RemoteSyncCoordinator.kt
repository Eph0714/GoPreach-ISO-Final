package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.data.repository.AnnouncementRepository
import com.emfitsolutions.gopreach.data.repository.AppSettingsRepository
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.BibleTextCategoryRepository
import com.emfitsolutions.gopreach.data.repository.BibleTextRecordRepository
import com.emfitsolutions.gopreach.data.repository.CartAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.CircuitCodeRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourCategoryRepository
import com.emfitsolutions.gopreach.data.repository.DashboardModuleLayoutRepository
import com.emfitsolutions.gopreach.data.repository.ElderTitleRepository
import com.emfitsolutions.gopreach.data.repository.ForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.HouseholderAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.LocationSharingSettingsRepository
import com.emfitsolutions.gopreach.data.repository.MidweekMeetingScheduleRepository
import com.emfitsolutions.gopreach.data.repository.MinistryTimerSessionRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.RecycleBinRepository
import com.emfitsolutions.gopreach.data.repository.PublicTalkScheduleRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.PreachingTimeRecordRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.PublisherForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.personIdFromAuthEmail
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.WeeklyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.YearlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.SavedLocationRepository
import com.emfitsolutions.gopreach.data.repository.ScheduleRepository
import com.emfitsolutions.gopreach.data.repository.SharedLocationRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Starts every collection-wide `startRemoteSync()` Firestore listener, and
 * re-starts them on every sign-in/sign-out. Without this, the offline cache
 * only ever contains documents *this device* wrote itself — anything created
 * elsewhere (another admin's device, a directly-provisioned account, a
 * teammate's enrollment) never reaches this device's Room cache or its
 * permission checks, since [com.emfitsolutions.gopreach.domain.UserSession]
 * and every repository read from that cache, not Firestore directly.
 *
 * Why re-start on every auth change (not just once at boot, as a first pass
 * of this used to do): a Firestore snapshot listener that hits a permission
 * error (e.g. registered while signed out, against a collection the security
 * rules gate on `isSignedIn()`) *terminates* — [FirestoreMirror]'s error
 * branch swallows the error rather than crashing, but the underlying
 * registration is still dead and will never emit again, even after the user
 * later signs in. Collections readable while signed out (like `people`,
 * per `firestore.rules`) never hit that error so looked fine; anything else —
 * `roleAssignments` in particular — would register at process start (before
 * any sign-in), die on the first `PERMISSION_DENIED`, and silently never
 * sync again, which surfaced as a signed-in user's dashboard showing "Unknown
 * role" despite their RoleAssignment existing server-side. Re-subscribing
 * fresh on every auth-state change (via `flatMapLatest`, which cancels the
 * old listener and attaches a brand new one) means every collection gets a
 * listener registered *while already authenticated* right after login.
 * (Per-parent-document listeners, like
 * [com.emfitsolutions.gopreach.data.repository.VisitRepository], are started
 * on demand by their own screens instead, since there's no fixed set of them
 * to start up front.)
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteSyncCoordinator(
    private val firebaseAuth: FirebaseAuth,
    private val connectivityObserver: ConnectivityObserver,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val congregationRepository: CongregationRepository,
    private val groupRepository: GroupRepository,
    private val elderTitleRepository: ElderTitleRepository,
    private val territoryRepository: TerritoryRepository,
    private val territoryAssignmentRepository: TerritoryAssignmentRepository,
    private val publisherTerritoryAssignmentRepository: com.emfitsolutions.gopreach.data.repository.PublisherTerritoryAssignmentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val interestedPersonRepository: InterestedPersonRepository,
    private val forwardRequestRepository: ForwardRequestRepository,
    private val publisherForwardRequestRepository: PublisherForwardRequestRepository,
    private val householderAssignmentRepository: HouseholderAssignmentRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val recycleBinRepository: RecycleBinRepository,
    private val auditLogRepository: AuditLogRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val sharedLocationRepository: SharedLocationRepository,
    private val userAccessGrantRepository: UserAccessGrantRepository,
    private val circuitCodeRepository: CircuitCodeRepository,
    private val coFieldServiceReportRepository: com.emfitsolutions.gopreach.data.repository.CoFieldServiceReportRepository,
    private val meetingAttendanceRepository: com.emfitsolutions.gopreach.data.repository.MeetingAttendanceRepository,
    private val comparativeReportRepository: com.emfitsolutions.gopreach.data.repository.ComparativeReportRepository,
    private val restrictedSessionSync: RestrictedSessionSync,
    private val preachingTimeRecordRepository: PreachingTimeRecordRepository,
    private val announcementRepository: AnnouncementRepository,
    private val locationSharingSettingsRepository: LocationSharingSettingsRepository,
    private val publisherVisibilitySettingsRepository: com.emfitsolutions.gopreach.data.repository.PublisherVisibilitySettingsRepository,
    private val savedLocationRepository: SavedLocationRepository,
    private val mapPinRepository: com.emfitsolutions.gopreach.data.repository.MapPinRepository,
    private val territoryDrawingRepository: com.emfitsolutions.gopreach.data.repository.TerritoryDrawingRepository,
    private val bibleTextCategoryRepository: BibleTextCategoryRepository,
    private val bibleTextRecordRepository: BibleTextRecordRepository,
    private val midweekMeetingScheduleRepository: MidweekMeetingScheduleRepository,
    private val publicTalkScheduleRepository: PublicTalkScheduleRepository,
    private val cartAssignmentRepository: CartAssignmentRepository,
    private val dashboardModuleLayoutRepository: DashboardModuleLayoutRepository,
    private val creditHourCategoryRepository: CreditHourCategoryRepository,
    // "I cannot see the same data to other phone" — these six repositories
    // each define their own startRemoteSync(), but nothing ever called it:
    // this class is the *only* place that happens, and none of them were
    // wired in here. Every document any of them ever wrote reached
    // Firestore fine (writes never depend on this), but no *other* device
    // ever pulled those documents back down — each device's Room cache only
    // ever contained what it had written itself. My Planner in particular
    // is built entirely from these six collections, so this is the reason
    // "everything" in My Planner looked empty on a second device even
    // though the first device showed "Online, all synced".
    private val plannerDayRepository: PlannerDayRepository,
    private val monthlyPlannerGoalRepository: MonthlyPlannerGoalRepository,
    private val weeklyPlannerGoalRepository: WeeklyPlannerGoalRepository,
    private val yearlyPlannerGoalRepository: YearlyPlannerGoalRepository,
    private val creditHourRecordRepository: CreditHourRecordRepository,
    private val ministryTimerSessionRepository: MinistryTimerSessionRepository,
    private val appScope: CoroutineScope,
) {
    private var started = false

    /** Emits the signed-in uid (or null) whenever Firebase Auth's state
     * changes. `callbackFlow` is *cold* — every independent collector re-runs
     * the block and registers its own [FirebaseAuth.AuthStateListener]. This
     * used to be called once in [startAll] and the resulting `Flow` object
     * handed to all 17 `startTracked` calls below, but a `Flow` is just a
     * recipe, not a running stream: each of those 17 `flatMapLatest`
     * collectors re-ran this block independently, registering 17 separate
     * Firebase Auth listeners instead of one. Signing out and back in as a
     * different account meant 17 near-simultaneous auth-state callbacks each
     * tearing down and re-subscribing their own repo's Firestore listener at
     * once — reproduced as the app freezing on the second sign-in until
     * force-closed. `stateIn(..., Eagerly)` makes this one real, shared,
     * already-running stream (one listener, started the moment this
     * singleton is created) that every `startTracked` call below now just
     * *observes*, instead of each spinning up its own. */
    // Bug fix (2026-09-12, "PERMISSION_DENIED keeps firing over and over,
    // escalating — dozens of duplicate 'attempt 1' failures for the same
    // collection, never settling"): reproduced live on a real device with
    // shaky connectivity — `FirebaseAuth.AuthStateListener` doesn't only
    // fire on a genuine sign-in/sign-out; a token refresh that has to retry
    // over a flaky connection can bounce `currentUser` through a transient
    // null in between, and each bounce is a real, *distinct* uid transition
    // that `distinctUntilChanged()` alone can't collapse. Every such bounce
    // makes every `startTracked` call below cancel its collection's old
    // Firestore listener and open a brand new one via `flatMapLatest` — and
    // since that cancellation's own cleanup isn't guaranteed to finish
    // before the replacement listener registers, listeners piled up faster
    // than they tore down, so *every* new registration kept racing the same
    // "token not attached yet" startup window this file's own retry logic
    // (see [FirestoreMirror]) was built to recover from once, not
    // indefinitely. `debounce` waits for the uid stream to actually settle
    // before propagating a change, so a flapping connection collapses into
    // one clean re-subscription instead of a runaway pile of them.
    private val uidChanged: StateFlow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser?.uid) }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }.debounce(1_500).distinctUntilChanged().stateIn(appScope, SharingStarted.Eagerly, firebaseAuth.currentUser?.uid)

    /** Same idea as [uidChanged], but resolved to the app's own personId
     * (the email-local-part identifier every document's `publisherPersonId`
     * field and firestore.rules' own `personIdFromToken()` actually use) —
     * `uidChanged`'s Firebase Auth UID is a different, unrelated string. Used
     * by [startTrackedForPublisher] to build the per-publisher-scoped
     * `.whereEqualTo("publisherPersonId", personId)` query each of those
     * collections' security rules require (see [PlannerDayRepository
     * .startRemoteSync]'s doc comment for the bug this fixes). */
    private val personIdChanged: StateFlow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(personIdFromAuthEmail(auth.currentUser?.email)) }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }.debounce(1_500).distinctUntilChanged()
        .stateIn(appScope, SharingStarted.Eagerly, personIdFromAuthEmail(firebaseAuth.currentUser?.email))

    /** Bug fix ("I cannot see the same data to other phone" — reproduced live
     * on a real device): [FirestoreMirror]'s retry budget is built to recover
     * from a *momentary* "token not attached yet" window at the exact instant
     * a listener first registers, not from an ID token that stays genuinely
     * stale for the device's entire session — which does happen in practice,
     * since Firebase Auth's own *automatic* background token refresh is a
     * scheduled task some OEMs' aggressive battery/task management (Huawei in
     * particular) are well known for silently killing. When that happens,
     * `firebaseAuth.currentUser` still exists locally (the app looks and acts
     * signed in — Person/RoleAssignments load fine from the Room cache) but
     * every request Firestore's *server* sees genuinely has `request.auth ==
     * null`, since the token attached to it has expired: every collection
     * gated on `isSignedIn()` (nearly all of them, including every My Planner
     * one) permanently fails PERMISSION_DENIED for the rest of the process,
     * confirmed by a live device stuck exactly this way for 6 straight
     * attempts across multiple full app relaunches.
     *
     * Bumped by [retryIfNeeded] alongside a forced token refresh — combined
     * into the same key every `startTracked` flow already re-subscribes on,
     * so a bump has the exact same effect a real uid change already does
     * (cancel the dead listener, attach a fresh one), just without requiring
     * an actual sign-out/sign-in to trigger it. */
    private val retryGeneration = MutableStateFlow(0)

    /**
     * Whether the signed-in account holds a `userAccessGrants` document (a Circuit Overseer): null until the server
     * answers, then true/false (false also if the read fails, e.g. offline, so an ordinary account is never held back).
     * A grant account's rules refuse list queries over whole collections — it only ever downloads the collections
     * [startGrantAwareSync] scopes for it — so [startTracked] must not even open the whole-collection listeners for it
     * (each would just fail with PERMISSION_DENIED and retry forever).
     */
    private val isGrantAccount: StateFlow<Boolean?> = combine(uidChanged, retryGeneration) { uid, _ -> uid }
        .mapLatest { uid ->
            val personId = personIdFromAuthEmail(firebaseAuth.currentUser?.email)
            if (uid == null || personId == null) null
            else if (BackendConfig.enabled) false // the Hostinger server scopes a Circuit Overseer's downloads itself; no Firestore read
            else runCatching { restrictedSessionSync.resolve(personId) != null }.getOrDefault(false)
        }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** Like [startTracked] but also for a grant account (collections every signed-in account may read whole: people, circuit codes/links). */
    private fun Flow<Unit>.startTrackedForEveryone(uidChanged: Flow<String?>): Unit {
        combine(uidChanged, retryGeneration) { uid, _ -> uid }.flatMapLatest { this }.launchIn(appScope)
    }

    /** Wires one collection's listener into [appScope], re-subscribing fresh
     * on every auth-state change (see the class doc for why that matters) or
     * [retryIfNeeded] call. */
    private fun Flow<Unit>.startTracked(uidChanged: Flow<String?>): Unit {
        combine(uidChanged, retryGeneration, isGrantAccount) { uid, _, grantAccount -> uid to grantAccount }
            .flatMapLatest { (uid, grantAccount) -> if (uid == null || grantAccount != false) kotlinx.coroutines.flow.emptyFlow() else this }
            .launchIn(appScope)
    }

    /** [startTracked]'s counterpart for a collection whose `startRemoteSync`
     * needs the current Publisher's own personId to build its
     * ownership-scoped query (see [personIdChanged]'s doc comment) — [flowFor]
     * is called fresh with whatever personId is currently signed in every
     * time it changes or [retryIfNeeded] bumps [retryGeneration], exactly
     * like [startTracked] does for the plain uid-keyed case. No personId
     * (signed out) means nothing to scope to, so nothing is subscribed. */
    private fun startTrackedForPublisher(flowFor: (String) -> Flow<Unit>) {
        combine(personIdChanged, retryGeneration) { personId, _ -> personId }
            .flatMapLatest { personId -> if (personId != null) flowFor(personId) else kotlinx.coroutines.flow.emptyFlow() }
            .launchIn(appScope)
    }

    /**
     * Mirrors the five collections a grant-based account's security rules are *scoped* on —
     * `roleAssignments`, `congregations`, `groups`, `monthlyReports` and `userAccessGrants`.
     *
     * An ordinary account listens to each whole collection, exactly as before. A Circuit Overseer (any
     * account that holds a `userAccessGrants/{me}` document) cannot: Firestore refuses a list query its
     * rules can't prove for every possible result, so each collection is asked for only its own document /
     * its assigned congregations instead (see [RestrictedSessionSync]). The assigned list is re-read from
     * the locally mirrored grant, so when a Super-Admin adds or removes a congregation the listeners follow.
     * Whether the account has a grant is decided by one server read per (re)subscription; if that read
     * fails (offline) the ordinary listeners are used, and [retryIfNeeded] re-decides on the next attempt.
     */
    private fun startGrantAwareSync() {
        combine(uidChanged, retryGeneration) { uid, _ -> uid }
            .flatMapLatest { uid ->
                val personId = personIdFromAuthEmail(firebaseAuth.currentUser?.email)
                if (uid == null || personId == null) {
                    kotlinx.coroutines.flow.emptyFlow()
                } else {
                    kotlinx.coroutines.flow.flow { emit(runCatching { restrictedSessionSync.resolve(personId) }.getOrNull()) }
                        .flatMapLatest { scope ->
                            if (scope == null) {
                                kotlinx.coroutines.flow.merge(
                                    roleAssignmentRepository.startRemoteSync(),
                                    congregationRepository.startRemoteSync(),
                                    groupRepository.startRemoteSync(),
                                    monthlyReportRepository.startRemoteSync(),
                                    userAccessGrantRepository.startRemoteSync(),
                                )
                            } else {
                                kotlinx.coroutines.flow.merge(
                                    restrictedSessionSync.ownGrant(personId),
                                    restrictedSessionSync.ownRoleAssignments(personId),
                                    if (scope.allCongregations) {
                                        kotlinx.coroutines.flow.merge(
                                            congregationRepository.startRemoteSync(),
                                            groupRepository.startRemoteSync(),
                                            roleAssignmentRepository.startRemoteSync(),
                                            monthlyReportRepository.startRemoteSync(),
                                            if (scope.circuit) coFieldServiceReportRepository.startRemoteSync() else kotlinx.coroutines.flow.emptyFlow(),
                                        )
                                    } else {
                                        userAccessGrantRepository.observeForPerson(personId)
                                            .map { it?.scopeCongregationIds.orEmpty().sorted() }
                                            .onStart { emit(scope.congregationIds.sorted()) }
                                            .distinctUntilChanged()
                                            .flatMapLatest { ids -> restrictedSessionSync.forCongregations(ids, scope.circuit) }
                                    },
                                )
                            }
                        }
                }
            }
            .launchIn(appScope)
    }

    /** Called from every point the app already suspects a session might need
     * a fresh look — cold start, foreground resume, right after a fresh
     * login (see call sites) — cheap and safe to call unconditionally even
     * when nothing was actually wrong: [FirebaseAuth.getIdToken] is a no-op
     * network call when the cached token is still valid, and re-attaching an
     * already-healthy listener is harmless. */
    fun retryIfNeeded() {
        if (BackendConfig.enabled) return
        val user = firebaseAuth.currentUser
        appScope.launch {
            // `true` forces a real refresh rather than trusting whatever's
            // cached — trusting the cache is exactly what leaves this stuck
            // in the first place. Failure (e.g. genuinely offline right now)
            // is fine to swallow: the listeners below will just see the same
            // token they already had and behave exactly as before this call.
            val result = runCatching { user?.getIdToken(true)?.await() }
            if (result.isFailure) {
                android.util.Log.w("RemoteSyncCoordinator", "retryIfNeeded: token refresh failed", result.exceptionOrNull())
            }
            retryGeneration.value++
        }
    }

    fun startAll() {
        if (BackendConfig.enabled) return // the backend path syncs through SyncEngine, not Firestore listeners
        if (started) return
        started = true
        // Force a fresh token before every collection's very first attempt —
        // closes the gap for a cold start whose cached token was already
        // stale the moment the process launched (see [retryIfNeeded]'s doc
        // comment), rather than only recovering on the next explicit trigger.
        retryIfNeeded()
        // Same idea on every offline→online transition — a device that was
        // offline for a while (its own OS having killed Firebase Auth's
        // background refresh in the meantime, on the OEMs this affects) gets
        // a real chance to self-heal the moment it reconnects, not just on
        // the next cold start/login/foreground.
        var wasOnline: Boolean? = null
        connectivityObserver.observe()
            .onEach { online ->
                val previouslyOffline = wasOnline == false
                wasOnline = online
                if (online && previouslyOffline) retryIfNeeded()
            }
            .launchIn(appScope)
        personRepository.startRemoteSync().startTrackedForEveryone(uidChanged)
        startGrantAwareSync()
        elderTitleRepository.startRemoteSync().startTracked(uidChanged)
        territoryRepository.startRemoteSync().startTracked(uidChanged)
        territoryAssignmentRepository.startRemoteSync().startTracked(uidChanged)
        publisherTerritoryAssignmentRepository.startRemoteSync().startTracked(uidChanged)
        scheduleRepository.startRemoteSync().startTracked(uidChanged)
        interestedPersonRepository.startRemoteSync().startTracked(uidChanged)
        forwardRequestRepository.startRemoteSync().startTracked(uidChanged)
        publisherForwardRequestRepository.startRemoteSync().startTracked(uidChanged)
        householderAssignmentRepository.startRemoteSync().startTracked(uidChanged)
        recycleBinRepository.startRemoteSync().startTracked(uidChanged)
        auditLogRepository.startRemoteSync().startTracked(uidChanged)
        appSettingsRepository.startRemoteSync().startTracked(uidChanged)
        sharedLocationRepository.startRemoteSync().startTracked(uidChanged)
        circuitCodeRepository.startRemoteSync().startTrackedForEveryone(uidChanged)
        coFieldServiceReportRepository.startStatusSync().startTrackedForEveryone(uidChanged)
        // Attendance settings and the historical monthly statistics are open to every signed-in account (no personal data).
        meetingAttendanceRepository.startSharedSync().startTrackedForEveryone(uidChanged)
        // Weekly attendance: the Super-Admin gets every congregation; everyone else (not a grant account — a Circuit Overseer's are mirrored
        // per assigned congregation) gets their own active congregation's, and the audit trail too when their active role manages attendance.
        combine(personIdChanged, retryGeneration, isGrantAccount) { pid, _, grantAccount -> pid to grantAccount }
            .flatMapLatest { (pid, grantAccount) ->
                if (pid == null || grantAccount != false) kotlinx.coroutines.flow.emptyFlow()
                else personRepository.observeAll().map { list -> list.firstOrNull { it.id == pid } }
                    .map { p -> Triple(p?.isSuperAdmin == true, p?.activeCongregationId, p?.activeAdminRole) }
                    .distinctUntilChanged()
                    .flatMapLatest { (superAdmin, cong, role) ->
                        when {
                            superAdmin -> kotlinx.coroutines.flow.merge(meetingAttendanceRepository.startRemoteSyncAll(), comparativeReportRepository.startSyncAll())
                            cong == null -> kotlinx.coroutines.flow.emptyFlow()
                            role in setOf("ADMIN_PER_CONGREGATION", "COORDINATOR_ELDER", "SERVICE_OVERSEER", "SECRETARY") ->
                                kotlinx.coroutines.flow.merge(
                                    meetingAttendanceRepository.startAttendanceSync(cong), meetingAttendanceRepository.startEventsSync(cong),
                                    comparativeReportRepository.startSyncFor(cong),
                                )
                            else -> meetingAttendanceRepository.startAttendanceSync(cong)
                        }
                    }
            }
            .launchIn(appScope)
        // The full report copies are for the Super-Admin only (a Circuit Overseer gets theirs per congregation, see [startGrantAwareSync]).
        combine(personIdChanged, retryGeneration) { pid, _ -> pid }
            .flatMapLatest { pid ->
                if (pid == null) kotlinx.coroutines.flow.flowOf(false)
                else personRepository.observeAll().map { list -> list.firstOrNull { it.id == pid }?.isSuperAdmin == true }.distinctUntilChanged()
            }
            .flatMapLatest { superAdmin -> if (superAdmin) coFieldServiceReportRepository.startRemoteSync() else kotlinx.coroutines.flow.emptyFlow() }
            .launchIn(appScope)
        preachingTimeRecordRepository.startRemoteSync().startTracked(uidChanged)
        announcementRepository.startRemoteSync().startTracked(uidChanged)
        locationSharingSettingsRepository.startRemoteSync().startTracked(uidChanged)
        publisherVisibilitySettingsRepository.startRemoteSync().startTracked(uidChanged)
        savedLocationRepository.startRemoteSync().startTracked(uidChanged)
        mapPinRepository.startRemoteSync().startTracked(uidChanged)
        territoryDrawingRepository.startRemoteSync().startTracked(uidChanged)
        territoryDrawingRepository.startBoundsRemoteSync().startTracked(uidChanged)
        startTrackedForPublisher { pid -> bibleTextCategoryRepository.startRemoteSync(pid) }
        startTrackedForPublisher { pid -> bibleTextRecordRepository.startRemoteSync(pid) }
        midweekMeetingScheduleRepository.startRemoteSync().startTracked(uidChanged)
        publicTalkScheduleRepository.startRemoteSync().startTracked(uidChanged)
        cartAssignmentRepository.startRemoteSync().startTracked(uidChanged)
        dashboardModuleLayoutRepository.startRemoteSync().startTracked(uidChanged)
        // Bug fix ("Credit Hours categories cannot be found in the
        // dropdown"): categories are managed by an admin on *their* device,
        // but nothing ever mirrored this collection down to anyone else's —
        // so every Publisher's My Planner read an empty local cache.
        creditHourCategoryRepository.startRemoteSync().startTracked(uidChanged)
        // Bug fix ("I cannot see the same data to other phone" — My Planner
        // in particular): see this class's own constructor doc comment
        // above these six repositories for why they were silently never
        // syncing down to any device but the one that wrote them.
        startTrackedForPublisher { pid -> plannerDayRepository.startRemoteSync(pid) }
        startTrackedForPublisher { pid -> monthlyPlannerGoalRepository.startRemoteSync(pid) }
        startTrackedForPublisher { pid -> weeklyPlannerGoalRepository.startRemoteSync(pid) }
        startTrackedForPublisher { pid -> yearlyPlannerGoalRepository.startRemoteSync(pid) }
        startTrackedForPublisher { pid -> creditHourRecordRepository.startRemoteSync(pid) }
        startTrackedForPublisher { pid -> ministryTimerSessionRepository.startRemoteSync(pid) }
    }
}
