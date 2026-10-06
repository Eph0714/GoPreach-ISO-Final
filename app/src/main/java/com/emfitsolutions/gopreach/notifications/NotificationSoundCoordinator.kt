package com.emfitsolutions.gopreach.notifications

import android.content.Context
import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.HouseholderAssignment
import com.emfitsolutions.gopreach.data.model.HouseholderAssignmentStatus
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.ForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.GroupChatRepository
import com.emfitsolutions.gopreach.data.repository.HouseholderAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.NotificationCategory
import com.emfitsolutions.gopreach.data.repository.PublisherForwardRequestRepository
import com.emfitsolutions.gopreach.di.ApplicationScope
import com.emfitsolutions.gopreach.domain.UserSession
import com.emfitsolutions.gopreach.ui.components.ChatBoxEntry
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItem
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItemsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** Same access set [com.emfitsolutions.gopreach.ui.navigation.GoPreachNavGraph
 * .canEditPublisherReports] already grants Monthly Report visibility to —
 * reused here so [NotificationCategory.MONTHLY_REPORT]-worthy scoping (via
 * [NotificationItemsProvider.itemsForAdmin]'s `includeMonthlyReports`) never
 * has to be re-derived a second, possibly-drifting way. Monthly Report
 * itself is deliberately not one of the sound-worthy categories below (see
 * [SOUND_WORTHY_CATEGORIES]) — this only affects whether one even ends up in
 * the [NotificationItem] list this class filters. */
private val ADMIN_ROLES_WITH_REPORT_ACCESS = setOf(
    AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER,
    AdminRole.REGULAR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
)

/** The [NotificationItem] categories worth an audible alert — Monthly Report
 * submissions were never one of these even before this class existed (see
 * the old `NewItemNotifier`'s own `onlyCategories`), so that stays unchanged. */
private val SOUND_WORTHY_CATEGORIES = setOf(
    NotificationCategory.ANNOUNCEMENT,
    NotificationCategory.CALENDAR_SCHEDULE,
    NotificationCategory.TRANSFER_REQUEST,
)

/**
 * "Fix the Notification Sound system" — root cause: every notification
 * trigger in this app (`NewItemNotifier`, `ForwardRequestNotifier`,
 * `PublisherForwardNotifier`, `ForwardToCongregationSenderNotifier`,
 * `GroupChatMessageNotifier`) used to be a `@Composable` nested *inside* the
 * Home/Dashboard screen, watching its Flow via `collectAsStateWithLifecycle`.
 * Two consequences, both silent: (1) navigating to any *other* screen
 * unmounted Home entirely, cancelling every one of those watchers — no sound
 * for anything that arrived while the Publisher was, say, on the Territory
 * Map; (2) `collectAsStateWithLifecycle`'s default `minActiveState` is
 * `STARTED`, which a backgrounded/minimized Activity is not — so even
 * *sitting on Home* stopped producing sound the moment the app left the
 * foreground. Every one of this app's notifications is local-only (no FCM/
 * push backend — see [NotificationHelper]'s own doc comment), triggered by
 * noticing new data stream in through an already-running Firestore listener
 * (see [com.emfitsolutions.gopreach.data.sync.RemoteSyncCoordinator]) — so
 * "sound only while a specific screen happens to be on top, in the
 * foreground" was never actually necessary; it just happened to be where the
 * trigger code was written.
 *
 * This class moves every one of those triggers to [ApplicationScope] —
 * started once from `GoPreachApp.onCreate()`, alongside
 * [com.emfitsolutions.gopreach.data.sync.RemoteSyncCoordinator] itself, and
 * running for as long as the app *process* is alive regardless of which
 * screen (if any) is on top. That's a real, honest limit worth stating
 * plainly: with no push backend, a fully killed process (swiped from
 * Recents, or not launched since reboot) cannot receive anything until
 * relaunched — the same limit every local-only notification in this app
 * already had, not something this fix could remove without introducing FCM
 * and a server component this app has deliberately never had.
 *
 * Re-subscribes fresh whenever the signed-in identity or active role/
 * congregation changes ([UserSession.state], same "flatMapLatest cancels the
 * old listener" pattern [RemoteSyncCoordinator] already uses for its own
 * auth-change handling), so switching accounts or roles never leaves a
 * stale, wrongly-scoped watcher running — spec's "no cross-congregation
 * notification data is exposed" holds exactly as well here as it already
 * does for every other congregation-scoped read in this app.
 *
 * A Circuit Overseer (grant-based scoping, no plain congregationId of their
 * own) is deliberately out of scope here, same as the old `AdminHomeScreen`
 * balloon already excluded them (`showNotificationBell = role !=
 * CIRCUIT_OVERSEER`) — narrower functionality for one rare role, not a
 * regression, and safer than guessing at their grant scope from outside the
 * screen that already resolves it correctly.
 */
@Singleton
class NotificationSoundCoordinator @Inject constructor(
    private val userSession: UserSession,
    private val itemsProvider: NotificationItemsProvider,
    private val forwardRequestRepository: ForwardRequestRepository,
    private val publisherForwardRequestRepository: PublisherForwardRequestRepository,
    private val householderAssignmentRepository: HouseholderAssignmentRepository,
    private val groupChatRepository: GroupChatRepository,
    private val congregationRepository: CongregationRepository,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var started = false

    /** Everything this class needs to know about "who's signed in and what
     * can they see right now" — one value class so [distinctUntilChanged]
     * can tell "nothing that matters changed" from "re-subscribe everything,"
     * without re-triggering on every unrelated [UserSession.state] emission
     * (e.g. a Person field edit that isn't the role/congregation). */
    private data class Scope(
        val personId: String?,
        val isPublisher: Boolean,
        val adminRole: AdminRole?,
        val congregationId: String?,
    )

    fun start() {
        if (started) return
        started = true

        val scope: Flow<Scope> = userSession.state
            .map { s ->
                Scope(
                    personId = s.person?.id,
                    isPublisher = s.isActivePublisherRole,
                    adminRole = s.activeAdminRole,
                    congregationId = s.activeRoleAssignment?.congregationId,
                )
            }
            .distinctUntilChanged()

        // Announcements / Calendar Schedule / Transfer Request (incoming,
        // pending) — the exact same list the on-screen balloon shows (see
        // NotificationItemsProvider's own doc comment), filtered to the
        // categories actually worth a sound. [notifyOnNewArrivals] is called
        // *inside* this lambda (not chained onto the already-flattened
        // stream below), so `flatMapLatest` starting a fresh inner flow on
        // every scope change also gets fresh "nothing seen yet" dedup state
        // — otherwise a switch to a different signed-in identity could
        // either flood every one of their pre-existing items as "new," or
        // wrongly suppress genuinely new ones, depending on how the two
        // accounts' timestamps happened to compare.
        scope.flatMapLatest { s ->
            unifiedItemsFor(s).notifyOnNewArrivals { item ->
                NotificationHelper.notify(
                    context,
                    id = (item.category.name + item.id).hashCode(),
                    title = item.title,
                    text = item.subtitle,
                    category = item.category,
                )
            }
        }.launchIn(appScope)

        // "Your forwarded record was accepted/declined" — the sender's own
        // outgoing half, which the unified balloon above never includes
        // (it only ever lists *pending* requests, not resolved ones). Only
        // meaningful for an active Publisher role, same scope the original
        // per-screen notifiers had.
        scope.flatMapLatest { s -> if (s.isPublisher && s.personId != null) outgoingForwardStatusFor(s.personId) else flowOf(emptyMap()) }
            .launchIn(appScope)

        // "Notify the assigning Service Overseer/Admin/Super-Admin that the
        // assignment was accepted/rejected" — same "status flip away from
        // PENDING" diff [outgoingForwardStatusFor] already uses, but not
        // Publisher-only: the assigner here is a Service Overseer/Admin/
        // Super-Admin, never a Publisher (spec: "Publisher... does not have
        // access to create assignments"), so this runs for *any* signed-in
        // person, unlike the Publisher-only forward-request watcher above.
        scope.map { it.personId }
            .distinctUntilChanged()
            .flatMapLatest { personId -> if (personId != null) outgoingHouseholderAssignmentStatusFor(personId) else flowOf(Unit) }
            .launchIn(appScope)

        // Group Chat messages — every signed-in person regardless of role
        // context, same as the old per-screen `GroupChatMessageNotifier`
        // call sites (unconditional in both Home screens). Same reasoning as
        // above for calling [notifyOnChatArrivals] inside the lambda, not
        // chained after it.
        scope.map { it.personId }
            .distinctUntilChanged()
            .flatMapLatest { personId ->
                if (personId != null) chatBoxEntriesFor(personId).notifyOnChatArrivals() else flowOf(Unit)
            }.launchIn(appScope)
    }

    private fun unifiedItemsFor(s: Scope): Flow<List<NotificationItem>> = when {
        s.isPublisher && s.personId != null -> itemsProvider.itemsForPublisher(s.personId, s.congregationId)
        s.adminRole != null && s.adminRole != AdminRole.CIRCUIT_OVERSEER -> {
            val congregationIds = if (s.adminRole == AdminRole.SUPER_ADMIN) null else setOfNotNull(s.congregationId)
            itemsProvider.itemsForAdmin(congregationIds, s.adminRole in ADMIN_ROLES_WITH_REPORT_ACCESS)
        }
        else -> flowOf(emptyList())
    }.map { list -> list.filter { it.category in SOUND_WORTHY_CATEGORIES } }

    /** Fires [onNewItem] once per item whose [NotificationItem.timestamp] is
     * newer than the newest one seen on the *previous* emission — same "skip
     * the very first load" anti-flood rule the old `NewItemNotifier`
     * Composable already used (a fresh subscribe, e.g. right after sign-in,
     * must never replay a sound for every pre-existing item at once). */
    private fun Flow<List<NotificationItem>>.notifyOnNewArrivals(onNewItem: (NotificationItem) -> Unit): Flow<Unit> {
        var lastMaxTimestamp: Long? = null
        return onEach { current ->
            val previousMax = lastMaxTimestamp
            if (previousMax != null) {
                current.filter { it.timestamp > previousMax }.forEach(onNewItem)
            }
            val currentMax = current.maxOfOrNull { it.timestamp }
            if (currentMax != null && (previousMax == null || currentMax > previousMax)) {
                lastMaxTimestamp = currentMax
            }
        }.map { }
    }

    /** Ported from the old `ForwardToCongregationSenderNotifier` +
     * `PublisherForwardNotifier`'s outgoing half, unchanged: a status flip
     * away from PENDING (Cancelled excluded — the sender already knows, they
     * did it) rings once. Returns the latest id->status snapshot purely so
     * the caller's [Flow] has a stable, comparable element type; nothing
     * downstream actually reads it. */
    private fun outgoingForwardStatusFor(publisherPersonId: String): Flow<Map<String, ForwardRequestStatus>> {
        var lastCongregationStatuses: Map<String, ForwardRequestStatus>? = null
        var lastPublisherStatuses: Map<String, ForwardRequestStatus>? = null
        return combine(
            forwardRequestRepository.observeAll().map { list -> list.filter { it.fromPublisherPersonId == publisherPersonId } },
            publisherForwardRequestRepository.observeAll().map { list -> list.filter { it.fromPublisherPersonId == publisherPersonId } },
        ) { congregationForwards, publisherForwards ->
            notifyResolvedForwards(
                current = congregationForwards,
                previous = lastCongregationStatuses,
                idOf = ForwardRequest::id,
                statusOf = ForwardRequest::status,
                titleFor = { "Forward Request Update" },
                textFor = { r -> "${r.personNameSnapshot} was ${r.status.name.lowercase()} by ${r.toCongregationNameSnapshot}." },
                notificationId = { r -> 9300 + r.id.hashCode() },
            )
            lastCongregationStatuses = congregationForwards.associate { it.id to it.status }

            notifyResolvedForwards(
                current = publisherForwards,
                previous = lastPublisherStatuses,
                idOf = PublisherForwardRequest::id,
                statusOf = PublisherForwardRequest::status,
                titleFor = { "Forward Request Update" },
                textFor = { r -> "${r.personNameSnapshot} was ${r.status.name.lowercase()} by ${r.toPublisherNameSnapshot}." },
                notificationId = { r -> 9310 + r.id.hashCode() },
            )
            lastPublisherStatuses = publisherForwards.associate { it.id to it.status }

            emptyMap()
        }
    }

    /** [outgoingForwardStatusFor]'s exact same shape, for
     * [HouseholderAssignment] — its own [HouseholderAssignmentStatus] isn't
     * [ForwardRequestStatus] (spec's own distinct state names, see that
     * enum's own doc comment), so this can't reuse [notifyResolvedForwards]
     * directly. Cancelled excluded here too — the assigner already knows,
     * they did it themselves (see [HouseholderAssignmentViewModel.cancel]). */
    private fun outgoingHouseholderAssignmentStatusFor(assignedByPersonId: String): Flow<Unit> {
        var lastStatuses: Map<String, HouseholderAssignmentStatus>? = null
        return householderAssignmentRepository.observeAll()
            .map { list -> list.filter { it.assignedByPersonId == assignedByPersonId } }
            .map { assignments ->
                val previous = lastStatuses
                if (previous != null) {
                    assignments.forEach { a ->
                        val wasPending = previous[a.id] == HouseholderAssignmentStatus.PENDING
                        if (wasPending && a.status != HouseholderAssignmentStatus.PENDING && a.status != HouseholderAssignmentStatus.CANCELLED) {
                            NotificationHelper.notify(
                                context,
                                id = 9320 + a.id.hashCode(),
                                title = "House Holder Assignment Update",
                                text = "${a.personNameSnapshot} was ${a.status.name.lowercase()} by ${a.toPublisherNameSnapshot}.",
                                category = NotificationCategory.TRANSFER_REQUEST,
                            )
                        }
                    }
                }
                lastStatuses = assignments.associate { it.id to it.status }
            }
    }

    private fun <T> notifyResolvedForwards(
        current: List<T>,
        previous: Map<String, ForwardRequestStatus>?,
        idOf: (T) -> String,
        statusOf: (T) -> ForwardRequestStatus,
        titleFor: (T) -> String,
        textFor: (T) -> String,
        notificationId: (T) -> Int,
    ) {
        if (previous == null) return
        current.forEach { request ->
            val id = idOf(request)
            val status = statusOf(request)
            val wasPending = previous[id] == ForwardRequestStatus.PENDING
            if (wasPending && status != ForwardRequestStatus.PENDING && status != ForwardRequestStatus.CANCELLED) {
                NotificationHelper.notify(
                    context,
                    id = notificationId(request),
                    title = titleFor(request),
                    text = textFor(request),
                    category = NotificationCategory.TRANSFER_REQUEST,
                )
            }
        }
    }

    /** Ported from [com.emfitsolutions.gopreach.ui.screens.groupchat
     * .GroupChatViewModel.chatBoxEntriesFor], unchanged — this class can't
     * depend on that Hilt ViewModel directly (it isn't obtainable outside a
     * ViewModelStoreOwner), only the plain repositories it itself wraps. */
    private fun chatBoxEntriesFor(personId: String): Flow<List<ChatBoxEntry>> = combine(
        groupChatRepository.observeGroupChatsForParticipant(personId),
        congregationRepository.observeAll(),
    ) { chats, congregationList ->
        val congregationNameById: Map<String, String> = congregationList.associateBy({ it.id }, { it.name })
        chats
            .map { chat ->
                ChatBoxEntry(
                    chat = chat,
                    congregationName = congregationNameById[chat.congregationId],
                    unreadCount = (chat.messageCount - (chat.readCounts[personId] ?: 0L)).coerceAtLeast(0L),
                )
            }
            .sortedByDescending { it.chat.lastMessageAt ?: it.chat.createdAt }
    }

    /** Ported from the old `GroupChatMessageNotifier` Composable, unchanged:
     * fires once per chat whose unread count went *up* since the previous
     * emission (never on the very first one). */
    private fun Flow<List<ChatBoxEntry>>.notifyOnChatArrivals(): Flow<Unit> {
        var lastUnreadCounts: Map<String, Long>? = null
        return onEach { entries ->
            val previous = lastUnreadCounts
            if (previous != null) {
                entries.forEach { entry ->
                    val before = previous[entry.chat.id] ?: 0L
                    if (entry.unreadCount > before) {
                        val preview = when {
                            entry.chat.lastMessageIsAttachment -> "${entry.chat.lastMessageSenderName ?: "Someone"} sent an attachment."
                            entry.chat.lastMessageText != null -> "${entry.chat.lastMessageSenderName ?: "Someone"}: ${entry.chat.lastMessageText}"
                            else -> "New message"
                        }
                        NotificationHelper.notify(
                            context,
                            id = 9400 + entry.chat.id.hashCode(),
                            title = entry.chat.groupName,
                            text = preview,
                            category = NotificationCategory.MESSAGE,
                        )
                    }
                }
            }
            lastUnreadCounts = entries.associate { it.chat.id to it.unreadCount }
        }.map { }
    }
}
