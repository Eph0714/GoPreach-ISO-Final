package com.emfitsolutions.gopreach.ui.screens.notifications

import com.emfitsolutions.gopreach.data.model.Announcement
import com.emfitsolutions.gopreach.data.model.ForwardRequest
import com.emfitsolutions.gopreach.data.model.ForwardRequestStatus
import com.emfitsolutions.gopreach.data.model.HouseholderAssignment
import com.emfitsolutions.gopreach.data.model.HouseholderAssignmentStatus
import com.emfitsolutions.gopreach.data.model.MonthlyReport
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherForwardRequest
import com.emfitsolutions.gopreach.data.model.ReportStatus
import com.emfitsolutions.gopreach.data.model.Schedule
import com.emfitsolutions.gopreach.data.model.ScheduleKind
import com.emfitsolutions.gopreach.data.repository.AnnouncementRepository
import com.emfitsolutions.gopreach.data.repository.ForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.HouseholderAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.NotificationCategory
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PublisherForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.ScheduleRepository
import com.emfitsolutions.gopreach.ui.components.formatRecordTimestamp
import com.emfitsolutions.gopreach.ui.screens.householderassignment.assignmentLabel
import com.emfitsolutions.gopreach.ui.navigation.Destinations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private data class AdminBundle(
    val forwards: List<ForwardRequest>,
    val publisherForwards: List<PublisherForwardRequest>,
    val reports: List<MonthlyReport>,
    val announcements: List<Announcement>,
    val schedules: List<Schedule>,
)

/**
 * "Fix the Notification Sound system" — the item-building half of
 * [NotificationCenterViewModel] (Transfer Request/Announcement/Calendar
 * Schedule/Monthly Report merging), pulled out into a plain injectable
 * singleton so [com.emfitsolutions.gopreach.notifications
 * .NotificationSoundCoordinator] can build the *exact same* list this app's
 * on-screen notification balloon shows, from Application scope — not tied to
 * any Composable's lifecycle, unlike the balloon itself. Having exactly one
 * copy of this filtering logic (rather than the coordinator re-deriving its
 * own idea of "what counts as a pending Transfer Request") is the point:
 * the sound-triggering path and the visible balloon can never silently
 * disagree about what's actually new. [NotificationCenterViewModel] now just
 * delegates to this class; its own public API (used by every screen already)
 * is unchanged.
 */
@Singleton
class NotificationItemsProvider @Inject constructor(
    private val forwardRequestRepository: ForwardRequestRepository,
    private val publisherForwardRequestRepository: PublisherForwardRequestRepository,
    private val monthlyReportRepository: MonthlyReportRepository,
    private val announcementRepository: AnnouncementRepository,
    private val scheduleRepository: ScheduleRepository,
    private val personRepository: PersonRepository,
    private val householderAssignmentRepository: HouseholderAssignmentRepository,
) {
    private val periodFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

    // "House Holder Assignment" — deliberately not part of [itemsForAdmin]'s
    // own bundle: a *sent* assignment has nothing for the sending Service
    // Overseer/Admin/Super-Admin to act on (only the receiving Publisher
    // does, via [itemsForPublisher] below) — the "assignment was accepted/
    // rejected" notification back to the sender is its own separate,
    // resolved-status-diff mechanism in
    // [com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator]
    // (same shape that class already uses for Forward Request outcomes),
    // not a [NotificationItem] in this pending-items list.
    fun itemsForAdmin(congregationIds: Set<String>?, includeMonthlyReports: Boolean): Flow<List<NotificationItem>> =
        combine(
            combine(
                forwardRequestRepository.observeAll(),
                publisherForwardRequestRepository.observeAll(),
                monthlyReportRepository.observeAll(),
                announcementRepository.observeAll(),
                scheduleRepository.observeAll(),
            ) { forwards, publisherForwards, reports, announcements, schedules ->
                AdminBundle(forwards, publisherForwards, reports, announcements, schedules)
            },
            personRepository.observeAll(),
        ) { bundle, people ->
            buildAdminItems(bundle, people, congregationIds, includeMonthlyReports)
        }

    private fun buildAdminItems(
        bundle: AdminBundle,
        people: List<Person>,
        congregationIds: Set<String>?,
        includeMonthlyReports: Boolean,
    ): List<NotificationItem> {
        val items = mutableListOf<NotificationItem>()

        bundle.forwards
            .filter { it.status == ForwardRequestStatus.PENDING && (congregationIds == null || it.toCongregationId in congregationIds) }
            .forEach { r ->
                items += NotificationItem(
                    id = r.id,
                    category = NotificationCategory.TRANSFER_REQUEST,
                    title = "Transfer Request: ${r.personNameSnapshot}",
                    subtitle = "From ${r.fromPublisherNameSnapshot} · ${r.fromCongregationNameSnapshot}",
                    timestamp = r.requestedAt,
                    route = Destinations.FORWARD_REQUESTS,
                )
            }

        bundle.publisherForwards
            .filter { it.status == ForwardRequestStatus.PENDING && (congregationIds == null || it.congregationId in congregationIds) }
            .forEach { r ->
                items += NotificationItem(
                    id = r.id,
                    category = NotificationCategory.TRANSFER_REQUEST,
                    title = "Transfer Request: ${r.personNameSnapshot}",
                    subtitle = "${r.fromPublisherNameSnapshot} → ${r.toPublisherNameSnapshot}",
                    timestamp = r.requestedAt,
                    route = Destinations.FORWARD_REQUESTS,
                )
            }

        if (includeMonthlyReports) {
            bundle.reports
                .filter { it.status == ReportStatus.SUBMITTED && (congregationIds == null || it.congregationId in congregationIds) }
                .forEach { report ->
                    val publisherName = people.firstOrNull { it.id == report.publisherPersonId }?.fullName ?: "A publisher"
                    items += NotificationItem(
                        id = report.id,
                        category = NotificationCategory.MONTHLY_REPORT,
                        title = "Monthly Report: $publisherName",
                        subtitle = periodFormat.format(Date(report.periodMonth)),
                        timestamp = report.submittedAt ?: report.periodMonth,
                        route = Destinations.manageReportsForMonth(report.periodMonth),
                    )
                }
        }

        bundle.announcements
            .filter { congregationIds == null || it.congregationId in congregationIds }
            .forEach { a ->
                items += NotificationItem(
                    id = a.id,
                    category = NotificationCategory.ANNOUNCEMENT,
                    title = "New Announcement: ${a.title}",
                    subtitle = a.details,
                    timestamp = a.createdAt,
                    route = Destinations.MANAGE_ANNOUNCEMENTS,
                )
            }

        bundle.schedules
            .filter { it.kind == ScheduleKind.CALENDAR_EVENT && (congregationIds == null || it.congregationId in congregationIds) }
            .forEach { s ->
                items += NotificationItem(
                    id = s.id,
                    category = NotificationCategory.CALENDAR_SCHEDULE,
                    title = "New Calendar Event: ${s.title}",
                    subtitle = formatRecordTimestamp(s.startTime),
                    timestamp = s.createdAt,
                    route = Destinations.CALENDAR,
                )
            }

        return items.sortedByDescending { it.timestamp }.take(50)
    }

    fun itemsForPublisher(currentPersonId: String, congregationId: String?): Flow<List<NotificationItem>> =
        combine(
            publisherForwardRequestRepository.observeAll(),
            householderAssignmentRepository.observeAll(),
            announcementRepository.observeAll(),
            scheduleRepository.observeAll(),
        ) { publisherForwards, assignments, announcements, schedules ->
            val items = mutableListOf<NotificationItem>()

            publisherForwards
                .filter { it.toPublisherPersonId == currentPersonId && it.status == ForwardRequestStatus.PENDING }
                .forEach { r ->
                    items += NotificationItem(
                        id = r.id,
                        category = NotificationCategory.TRANSFER_REQUEST,
                        title = "Transfer Request: ${r.personNameSnapshot}",
                        subtitle = "From ${r.fromPublisherNameSnapshot}",
                        timestamp = r.requestedAt,
                        route = Destinations.PUBLISHER_FORWARD_REQUESTS,
                    )
                }

            // "New House Holder Assignment... You have received a new
            // assignment from [Assigned By]." (spec's own exact worked
            // notification text).
            assignments
                .filter { it.toPublisherPersonId == currentPersonId && it.status == HouseholderAssignmentStatus.PENDING }
                .forEach { a ->
                    items += NotificationItem(
                        id = a.id,
                        category = NotificationCategory.TRANSFER_REQUEST,
                        title = "New House Holder Assignment: ${a.personNameSnapshot}",
                        subtitle = "From ${a.assignedByNameSnapshot} · ${a.recordType.assignmentLabel()}",
                        timestamp = a.assignedAt,
                        route = Destinations.INCOMING_HOUSEHOLDER_ASSIGNMENTS,
                    )
                }

            announcements
                .filter { it.congregationId == congregationId }
                .forEach { a ->
                    items += NotificationItem(
                        id = a.id,
                        category = NotificationCategory.ANNOUNCEMENT,
                        title = "New Announcement: ${a.title}",
                        subtitle = a.details,
                        timestamp = a.createdAt,
                        route = Destinations.PUBLISHER_ANNOUNCEMENTS,
                    )
                }

            schedules
                .filter { it.kind == ScheduleKind.CALENDAR_EVENT && it.congregationId == congregationId }
                .forEach { s ->
                    items += NotificationItem(
                        id = s.id,
                        category = NotificationCategory.CALENDAR_SCHEDULE,
                        title = "New Calendar Event: ${s.title}",
                        subtitle = formatRecordTimestamp(s.startTime),
                        timestamp = s.createdAt,
                        route = Destinations.CALENDAR,
                    )
                }

            items.sortedByDescending { it.timestamp }.take(50)
        }
}
