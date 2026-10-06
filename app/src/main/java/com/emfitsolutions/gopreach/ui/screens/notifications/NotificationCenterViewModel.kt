package com.emfitsolutions.gopreach.ui.screens.notifications

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.NotificationCategory
import com.emfitsolutions.gopreach.data.repository.AnnouncementSeenStore
import com.emfitsolutions.gopreach.data.repository.NotificationDismissedStore
import com.emfitsolutions.gopreach.data.repository.NotificationSeenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** One row in the unified notification balloon. [route] is where tapping it
 * navigates — always an existing, already-scoped screen (Forward Requests,
 * Manage Publisher Reports, Manage/Publisher Announcements, Calendar) rather
 * than a new dedicated notification-detail screen. */
data class NotificationItem(
    /** The underlying record's own id (ForwardRequest/PublisherForwardRequest/
     * MonthlyReport/Announcement/Schedule) — stable across recompositions and
     * app restarts, unlike a derived hash, so [NotificationDismissedStore] can
     * track "the user dismissed this one specific notification" per item
     * (spec: "Delete Notification"/"Clear Old Notifications") without ever
     * touching the underlying record itself. */
    val id: String,
    val category: NotificationCategory,
    val title: String,
    val subtitle: String,
    val timestamp: Long,
    val route: String,
)

/**
 * Backs the unified notification balloon shown to every role (spec: "Add a
 * notification balloon for Service Overseer, Elders, Admin, publisher and
 * super admin"), merging four independent event sources into one
 * chronological list:
 *  1. Incoming approval requests for a transfer (cross-congregation
 *     [ForwardRequest], same-congregation [PublisherForwardRequest]) — every
 *     role sees whichever of the two it can actually act on/view (see
 *     [com.emfitsolutions.gopreach.ui.screens.pipeline.ForwardRequestsScreen]/
 *     [com.emfitsolutions.gopreach.ui.screens.pipeline
 *     .PublisherForwardRequestsScreen]).
 *  2. Incoming Publisher Monthly Reports — not shown to a Publisher (spec).
 *  3. New Announcements.
 *  4. New Calendar Schedule entries (excludes Chat Schedule/personal notes —
 *     the spec names "Calendar Schedule" specifically).
 *
 * Congregation scoping (spec's closing note) is the caller's job, exactly
 * like every other congregation-scoped screen in this app: pass `null` for
 * [itemsForAdmin]'s `congregationIds` only for Super-Admin ("can see all
 * congregation notification"), and the exact single congregationId for
 * [itemsForPublisher] — never resolved in here.
 *
 * "Seen" tracking reuses the pre-existing per-category-cheap local approach
 * (see [NotificationSeenStore]/[AnnouncementSeenStore]) — the bell's badge is
 * an unseen *count*, not a persisted read/unread flag per item, so opening
 * the balloon (see [markAllSeen]) is what resets it, the same way opening the
 * Announcements screen already does for that one category alone.
 *
 * The actual item-building logic lives in [NotificationItemsProvider] now
 * (see its own doc comment) — this class just delegates to it, so
 * [com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator]
 * (Application-scoped, not tied to any screen) can build the exact same list
 * to decide what's newly arrived and worth a sound, without a second,
 * possibly-drifting copy of "what counts as a pending Transfer Request."
 */
@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val itemsProvider: NotificationItemsProvider,
    private val notificationSeenStore: NotificationSeenStore,
    private val announcementSeenStore: AnnouncementSeenStore,
    private val notificationDismissedStore: NotificationDismissedStore,
) : ViewModel() {

    /** Admin-track roles (Super-Admin/Admin/Coordinator Elder/Regular
     * Elder/Service Overseer/Ministerial Servant). [congregationIds] is
     * `null` only for Super-Admin; [includeMonthlyReports] is `false` only
     * when the caller has no Monthly Report visibility of its own (this
     * balloon never grants access beyond what the role already has). */
    fun itemsForAdmin(congregationIds: Set<String>?, includeMonthlyReports: Boolean): Flow<List<NotificationItem>> =
        itemsProvider.itemsForAdmin(congregationIds, includeMonthlyReports)

    /** A Publisher's own balloon — no Monthly Report category (spec: "Not
     * for Publisher"), and "transfer" here means only the same-congregation
     * hand-offs targeted *at them* ([com.emfitsolutions.gopreach.data.model
     * .PublisherForwardRequest]) — a Publisher never sees the cross-
     * congregation Service Overseer queue. */
    fun itemsForPublisher(currentPersonId: String, congregationId: String?): Flow<List<NotificationItem>> =
        itemsProvider.itemsForPublisher(currentPersonId, congregationId)

    /** [items] with every notification the user already dismissed (spec:
     * "Delete Notification"/"Clear Old Notifications") filtered out — the
     * bell's own display list and [unseenCountFor] should both read this,
     * not the raw builder output, so a dismissed item neither shows nor
     * keeps counting toward the badge. [com.emfitsolutions.gopreach
     * .notifications.NotificationSoundCoordinator]'s own "new arrival"
     * detection stays on the *raw* [NotificationItemsProvider] flow instead —
     * dismissing a past notification shouldn't suppress the sound for a
     * genuinely new one that happens to share nothing but a category with it. */
    fun visibleItemsFor(items: Flow<List<NotificationItem>>, currentPersonId: String): Flow<List<NotificationItem>> =
        combine(items, notificationDismissedStore.dismissedByPerson) { list, _ ->
            val dismissed = notificationDismissedStore.dismissedFor(currentPersonId)
            list.filter { it.id !in dismissed }
        }

    /** Dismisses one notification — disappears from the balloon on this
     * device only; never touches the underlying record. */
    fun dismiss(item: NotificationItem, currentPersonId: String) = notificationDismissedStore.dismiss(currentPersonId, item.id)

    /** "Clear Old Notifications" / a "Clear All" action — dismisses every
     * notification currently shown in one tap. */
    fun dismissAll(items: List<NotificationItem>, currentPersonId: String) =
        notificationDismissedStore.dismissAll(currentPersonId, items.map { it.id })

    private fun lastSeenAt(category: NotificationCategory, personId: String): Long =
        if (category == NotificationCategory.ANNOUNCEMENT) announcementSeenStore.lastSeenAt(personId)
        else notificationSeenStore.lastSeenAt(category, personId)

    /** Live unseen count for the bell's badge — recomputes whenever [items]
     * or either seen-store changes, same "combine against the seen-store's
     * own StateFlow" trick [com.emfitsolutions.gopreach.ui.screens
     * .announcements.ManageAnnouncementsViewModel.unseenCountFor] already
     * uses for the single-category case. */
    fun unseenCountFor(items: Flow<List<NotificationItem>>, currentPersonId: String): Flow<Int> =
        combine(items, notificationSeenStore.lastSeenAt, announcementSeenStore.lastSeenAtByPerson) { list, _, _ ->
            list.count { it.timestamp > lastSeenAt(it.category, currentPersonId) }
        }

    /** Opening the balloon marks every category seen "now" — the row list
     * itself is unaffected (it's not filtered by seen status, same as the
     * Announcements screen), only the badge count resets. */
    fun markAllSeen(currentPersonId: String) {
        NotificationCategory.entries.forEach { category ->
            if (category == NotificationCategory.ANNOUNCEMENT) announcementSeenStore.markSeenNow(currentPersonId)
            else notificationSeenStore.markSeenNow(category, currentPersonId)
        }
    }
}
