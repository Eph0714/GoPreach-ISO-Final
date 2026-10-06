package com.emfitsolutions.gopreach.di

import com.emfitsolutions.gopreach.data.location.LocationTracker
import com.emfitsolutions.gopreach.data.repository.AnnouncementRepository
import com.emfitsolutions.gopreach.data.repository.AnnouncementSeenStore
import com.emfitsolutions.gopreach.data.repository.AppSettingsRepository
import com.emfitsolutions.gopreach.data.repository.AuditLogRepository
import com.emfitsolutions.gopreach.data.repository.AuthRepository
import com.emfitsolutions.gopreach.data.repository.BackupRepository
import com.emfitsolutions.gopreach.data.repository.BibleTextCategoryRepository
import com.emfitsolutions.gopreach.data.repository.BibleTextRecordRepository
import com.emfitsolutions.gopreach.data.repository.BibleVerseTextRepository
import com.emfitsolutions.gopreach.data.repository.CartAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.CongregationRepository
import com.emfitsolutions.gopreach.data.repository.CredentialStore
import com.emfitsolutions.gopreach.data.repository.CreditHourCategoryRepository
import com.emfitsolutions.gopreach.data.repository.CreditHourRecordRepository
import com.emfitsolutions.gopreach.data.repository.DashboardModuleLayoutRepository
import com.emfitsolutions.gopreach.data.repository.DrawingPermissionService
import com.emfitsolutions.gopreach.data.repository.ElderTitleRepository
import com.emfitsolutions.gopreach.data.repository.ForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.GroupChatRepository
import com.emfitsolutions.gopreach.data.repository.GroupRepository
import com.emfitsolutions.gopreach.data.repository.HouseholderAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.InterestedPersonRepository
import com.emfitsolutions.gopreach.data.repository.JwVideoRepository
import com.emfitsolutions.gopreach.data.repository.LocationSharingSettingsRepository
import com.emfitsolutions.gopreach.data.repository.MapPinRepository
import com.emfitsolutions.gopreach.data.repository.MidweekMeetingScheduleRepository
import com.emfitsolutions.gopreach.data.repository.MinistryTimerSessionRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.MonthlyReportRepository
import com.emfitsolutions.gopreach.data.repository.NameOrderPreference
import com.emfitsolutions.gopreach.data.repository.NotificationDismissedStore
import com.emfitsolutions.gopreach.data.repository.NotificationSeenStore
import com.emfitsolutions.gopreach.data.repository.NotificationSoundRepository
import com.emfitsolutions.gopreach.data.repository.OfflineAuthStore
import com.emfitsolutions.gopreach.data.repository.OfflineSessionMarker
import com.emfitsolutions.gopreach.data.repository.OverpassLandmarkRepository
import com.emfitsolutions.gopreach.data.repository.OverpassStreetRepository
import com.emfitsolutions.gopreach.data.repository.PersonRepository
import com.emfitsolutions.gopreach.data.repository.PhilippineLocationRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.data.repository.PreachingTimeRecordRepository
import com.emfitsolutions.gopreach.data.repository.PublicTalkScheduleRepository
import com.emfitsolutions.gopreach.data.repository.PublisherCongregationContext
import com.emfitsolutions.gopreach.data.repository.PublisherDashboardVisibilityRepository
import com.emfitsolutions.gopreach.data.repository.PublisherForwardRequestRepository
import com.emfitsolutions.gopreach.data.repository.PublisherTerritoryAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.PublisherVisibilitySettingsRepository
import com.emfitsolutions.gopreach.data.repository.QuickLoginStore
import com.emfitsolutions.gopreach.data.repository.RecycleBinRepository
import com.emfitsolutions.gopreach.data.repository.RemoteBarangayBoundaryRepository
import com.emfitsolutions.gopreach.data.repository.RoleAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.SavedLocationRepository
import com.emfitsolutions.gopreach.data.repository.ScheduleRepository
import com.emfitsolutions.gopreach.data.repository.SharedLocationRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryAssignmentRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryBoundaryRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryDrawingRepository
import com.emfitsolutions.gopreach.data.repository.TerritoryRepository
import com.emfitsolutions.gopreach.data.repository.ThemePreferenceRepository
import com.emfitsolutions.gopreach.data.repository.UserAccessGrantRepository
import com.emfitsolutions.gopreach.data.repository.VisitRepository
import com.emfitsolutions.gopreach.data.repository.WeeklyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.repository.YearlyPlannerGoalRepository
import com.emfitsolutions.gopreach.data.sync.ConnectivityObserver
import com.emfitsolutions.gopreach.data.sync.DataRefresher
import com.emfitsolutions.gopreach.data.sync.OfflineFirestoreRepository
import com.emfitsolutions.gopreach.data.sync.PresenceHeartbeat
import com.emfitsolutions.gopreach.data.sync.ReminderScheduler
import com.emfitsolutions.gopreach.data.sync.ReminderWorker
import com.emfitsolutions.gopreach.data.sync.RemoteSyncCoordinator
import com.emfitsolutions.gopreach.data.sync.SyncScheduler
import com.emfitsolutions.gopreach.data.sync.SyncStatusCenter
import com.emfitsolutions.gopreach.data.sync.SyncWorker
import com.emfitsolutions.gopreach.data.update.ApkDownloader
import com.emfitsolutions.gopreach.data.update.InstalledUpdateInfoStore
import com.emfitsolutions.gopreach.data.update.UpdateInstaller
import com.emfitsolutions.gopreach.data.update.UpdateManifestCache
import com.emfitsolutions.gopreach.data.update.UpdateManifestRepository
import com.emfitsolutions.gopreach.data.update.UpdateReminderStore
import com.emfitsolutions.gopreach.domain.DateRangeStore
import com.emfitsolutions.gopreach.domain.PublisherReportService
import com.emfitsolutions.gopreach.domain.UserSession
import com.emfitsolutions.gopreach.notifications.CalendarAlarmRescheduler
import com.emfitsolutions.gopreach.notifications.NotificationSoundCoordinator
import com.emfitsolutions.gopreach.ui.components.LogoViewModel
import com.emfitsolutions.gopreach.ui.components.ManualSyncViewModel
import com.emfitsolutions.gopreach.ui.components.MinistryTimerViewModel
import com.emfitsolutions.gopreach.ui.components.NameOrderViewModel
import com.emfitsolutions.gopreach.ui.components.OfflineSessionBannerViewModel
import com.emfitsolutions.gopreach.ui.components.OnlineUsersViewModel
import com.emfitsolutions.gopreach.ui.components.PhilippineAddressPickerViewModel
import com.emfitsolutions.gopreach.ui.components.RefreshButtonViewModel
import com.emfitsolutions.gopreach.ui.components.SyncMessageHostViewModel
import com.emfitsolutions.gopreach.ui.components.SyncStatusIndicatorViewModel
import com.emfitsolutions.gopreach.ui.components.map.CurrentLocationViewModel
import com.emfitsolutions.gopreach.ui.components.map.MapDrawingViewModel
import com.emfitsolutions.gopreach.ui.components.update.UpdateViewModel
import com.emfitsolutions.gopreach.ui.navigation.SessionViewModel
import com.emfitsolutions.gopreach.ui.screens.account.AccountSettingsViewModel
import com.emfitsolutions.gopreach.ui.screens.accountmanagement.AccountManagementViewModel
import com.emfitsolutions.gopreach.ui.screens.admins.ManageAdminsViewModel
import com.emfitsolutions.gopreach.ui.screens.announcements.ManageAnnouncementsViewModel
import com.emfitsolutions.gopreach.ui.screens.auth.ForcedPasswordChangeViewModel
import com.emfitsolutions.gopreach.ui.screens.auth.ForgotPasswordViewModel
import com.emfitsolutions.gopreach.ui.screens.backup.BackupRestoreViewModel
import com.emfitsolutions.gopreach.ui.screens.bibletext.BibleTextRecordViewModel
import com.emfitsolutions.gopreach.ui.screens.bibletext.BibleVerseTextViewModel
import com.emfitsolutions.gopreach.ui.screens.bibletext.VideoViewModel
import com.emfitsolutions.gopreach.ui.screens.calendar.CalendarViewModel
import com.emfitsolutions.gopreach.ui.screens.congregations.ManageCongregationsViewModel
import com.emfitsolutions.gopreach.ui.screens.contactrecord.ContactRecordViewModel
import com.emfitsolutions.gopreach.ui.screens.controlpanel.ControlPanelViewModel
import com.emfitsolutions.gopreach.ui.screens.credithours.CreditHourCategoriesViewModel
import com.emfitsolutions.gopreach.ui.screens.dashboard.DashboardStatsViewModel
import com.emfitsolutions.gopreach.ui.screens.deletedrecords.DeletedRecordsViewModel
import com.emfitsolutions.gopreach.ui.screens.elders.ManageEldersViewModel
import com.emfitsolutions.gopreach.ui.screens.elders.ManageMinisterialServantsViewModel
import com.emfitsolutions.gopreach.ui.screens.enrollment.AdminEnrollmentViewModel
import com.emfitsolutions.gopreach.ui.screens.enrollment.CongregationEnrollmentViewModel
import com.emfitsolutions.gopreach.ui.screens.enrollment.EldersEnrollmentViewModel
import com.emfitsolutions.gopreach.ui.screens.enrollment.MinisterialServantEnrollmentViewModel
import com.emfitsolutions.gopreach.ui.screens.enrollment.PublisherEnrollmentViewModel
import com.emfitsolutions.gopreach.ui.screens.fieldservicereport.FieldServiceReportViewModel
import com.emfitsolutions.gopreach.ui.screens.findlocation.FindLocationViewModel
import com.emfitsolutions.gopreach.ui.screens.groupchat.GroupChatViewModel
import com.emfitsolutions.gopreach.ui.screens.groups.ManageGroupsViewModel
import com.emfitsolutions.gopreach.ui.screens.home.HomeViewModel
import com.emfitsolutions.gopreach.ui.screens.home.MyScopeSummaryViewModel
import com.emfitsolutions.gopreach.ui.screens.home.PublisherDashboardLayoutViewModel
import com.emfitsolutions.gopreach.ui.screens.home.PublisherDashboardViewModel
import com.emfitsolutions.gopreach.ui.screens.home.QuickAccessViewModel
import com.emfitsolutions.gopreach.ui.screens.home.RecentlyVisitedViewModel
import com.emfitsolutions.gopreach.ui.screens.householderassignment.HouseholderAssignmentViewModel
import com.emfitsolutions.gopreach.ui.screens.householdervisithistory.HouseholderVisitHistoryViewModel
import com.emfitsolutions.gopreach.ui.screens.login.BiometricEnrollmentOffer
import com.emfitsolutions.gopreach.ui.screens.login.BiometricOfferViewModel
import com.emfitsolutions.gopreach.ui.screens.login.BiometricSetupViewModel
import com.emfitsolutions.gopreach.ui.screens.login.LoginMethodsViewModel
import com.emfitsolutions.gopreach.ui.screens.login.LoginViewModel
import com.emfitsolutions.gopreach.ui.screens.login.PendingLoginNotice
import com.emfitsolutions.gopreach.ui.screens.manualreport.ManualFieldServiceViewModel
import com.emfitsolutions.gopreach.ui.screens.meetingassignments.MeetingAssignmentsViewModel
import com.emfitsolutions.gopreach.ui.screens.monthlyreport.MonthlyReportViewModel
import com.emfitsolutions.gopreach.ui.screens.monthlyreport.MySubmittedReportsViewModel
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationCenterViewModel
import com.emfitsolutions.gopreach.ui.screens.notifications.NotificationItemsProvider
import com.emfitsolutions.gopreach.ui.screens.pipeline.ForwardRequestsViewModel
import com.emfitsolutions.gopreach.ui.screens.pipeline.PipelineViewModel
import com.emfitsolutions.gopreach.ui.screens.pipeline.PublisherForwardRequestsViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.CreditHourEntryViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerComparativeViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerDayViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerMonthViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerReportViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerWeekViewModel
import com.emfitsolutions.gopreach.ui.screens.planner.PlannerYearViewModel
import com.emfitsolutions.gopreach.ui.screens.preachingtime.PreachingTimeRecordViewModel
import com.emfitsolutions.gopreach.ui.screens.publisherreports.ManagePublisherReportsViewModel
import com.emfitsolutions.gopreach.ui.screens.publishers.ManagePublishersViewModel
import com.emfitsolutions.gopreach.ui.screens.reports.ComparativeReportViewModel
import com.emfitsolutions.gopreach.ui.screens.reports.ConsolidatedReportViewModel
import com.emfitsolutions.gopreach.ui.screens.reports.FieldServiceGroupReportViewModel
import com.emfitsolutions.gopreach.ui.screens.reports.ReportsViewModel
import com.emfitsolutions.gopreach.ui.screens.settings.InactivityTracker
import com.emfitsolutions.gopreach.ui.screens.settings.SessionTimeoutViewModel
import com.emfitsolutions.gopreach.ui.screens.settings.SettingsViewModel
import com.emfitsolutions.gopreach.ui.screens.sharelocation.ShareLocationViewModel
import com.emfitsolutions.gopreach.ui.screens.territories.TerritoryMapViewModel
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.PublisherTerritoryAssignmentViewModel
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.TerritoryAssignmentWizardViewModel
import com.emfitsolutions.gopreach.ui.screens.territoryassignments.TerritoryAssignmentsViewModel
import com.emfitsolutions.gopreach.ui.screens.userlogs.UserLogsViewModel
import com.emfitsolutions.gopreach.ui.screens.users.AddEditUserViewModel
import com.emfitsolutions.gopreach.ui.screens.users.ManageUsersViewModel

import android.content.Context
import com.emfitsolutions.gopreach.data.local.AppDatabase
import com.emfitsolutions.gopreach.data.local.psgc.PsgcDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.androidx.workmanager.dsl.workerOf
import com.emfitsolutions.gopreach.data.remote.HttpSyncApi
import com.emfitsolutions.gopreach.data.remote.SyncApi
import com.emfitsolutions.gopreach.data.remote.SyncTransportException
import com.emfitsolutions.gopreach.data.sync.BackendConfig
import com.emfitsolutions.gopreach.data.sync.SyncEngine
import com.emfitsolutions.gopreach.data.sync.AndroidWriteQueuedListener
import com.emfitsolutions.gopreach.data.sync.WriteQueuedListener
import com.emfitsolutions.gopreach.data.sync.RemoteCollections
import com.emfitsolutions.gopreach.data.sync.NetworkStatus
import com.emfitsolutions.gopreach.data.sync.AndroidNetworkStatus
import com.emfitsolutions.gopreach.data.sync.FirebaseRemoteFiles
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.emfitsolutions.gopreach.data.sync.FirestoreRemoteCollections
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.tasks.await
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Koin graph (replaces Hilt). Repositories are singletons, ViewModels are created per screen. */
val infraModule = module {
    // Platform / infrastructure
    single { Gson() }
    // Long-lived scope for work that must outlive a single screen/ViewModel (e.g. mirroring a listener into the cache).
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single { FirebaseAuth.getInstance() }
    single { FirebaseFirestore.getInstance() }
    single { FirebaseStorage.getInstance() }
    single { buildAppDatabase(get()) }
    single { get<AppDatabase>().cacheDao() }
    single { get<AppDatabase>().syncQueueDao() }
    single { buildPsgcDatabase(get()) }
    single { get<PsgcDatabase>().psgcDao() }

    single<RemoteCollections> { FirestoreRemoteCollections(get(), get(), get()) }
    single<NetworkStatus> { AndroidNetworkStatus(get(), get()) }
    single<RemoteFiles> { FirebaseRemoteFiles(get()) }
    single<WriteQueuedListener> { AndroidWriteQueuedListener(get(), get(), get()) }

    // Hostinger backend sync (only used when BackendConfig.enabled)
    single { HttpClient(OkHttp) }
    single<SyncApi> {
        val auth = get<FirebaseAuth>()
        HttpSyncApi(get(), BackendConfig.baseUrl) {
            auth.currentUser?.getIdToken(false)?.await()?.token ?: throw SyncTransportException("Not signed in")
        }
    }
    single { SyncEngine(get(), get(), get(), idFieldByCollection = BackendConfig.idFieldByCollection) }
}

val appModule = module {

    // Repositories, services, stores
    singleOf(::LocationTracker)
    singleOf(::AnnouncementRepository)
    singleOf(::AnnouncementSeenStore)
    singleOf(::AppSettingsRepository)
    singleOf(::AuditLogRepository)
    singleOf(::AuthRepository)
    singleOf(::BackupRepository)
    singleOf(::BibleTextCategoryRepository)
    singleOf(::BibleTextRecordRepository)
    singleOf(::BibleVerseTextRepository)
    singleOf(::CartAssignmentRepository)
    singleOf(::CongregationRepository)
    singleOf(::CredentialStore)
    singleOf(::CreditHourCategoryRepository)
    singleOf(::CreditHourRecordRepository)
    singleOf(::DashboardModuleLayoutRepository)
    singleOf(::DrawingPermissionService)
    singleOf(::ElderTitleRepository)
    singleOf(::ForwardRequestRepository)
    singleOf(::GroupChatRepository)
    singleOf(::GroupRepository)
    singleOf(::HouseholderAssignmentRepository)
    singleOf(::InterestedPersonRepository)
    singleOf(::JwVideoRepository)
    singleOf(::LocationSharingSettingsRepository)
    singleOf(::MapPinRepository)
    singleOf(::MidweekMeetingScheduleRepository)
    singleOf(::MinistryTimerSessionRepository)
    singleOf(::MonthlyPlannerGoalRepository)
    singleOf(::MonthlyReportRepository)
    singleOf(::NameOrderPreference)
    singleOf(::NotificationDismissedStore)
    singleOf(::NotificationSeenStore)
    singleOf(::NotificationSoundRepository)
    singleOf(::OfflineAuthStore)
    singleOf(::OfflineSessionMarker)
    singleOf(::OverpassLandmarkRepository)
    singleOf(::PersonRepository)
    singleOf(::PhilippineLocationRepository)
    singleOf(::PlannerDayRepository)
    singleOf(::PreachingTimeRecordRepository)
    singleOf(::PublicTalkScheduleRepository)
    singleOf(::PublisherCongregationContext)
    singleOf(::PublisherDashboardVisibilityRepository)
    singleOf(::PublisherForwardRequestRepository)
    singleOf(::PublisherTerritoryAssignmentRepository)
    singleOf(::PublisherVisibilitySettingsRepository)
    singleOf(::QuickLoginStore)
    singleOf(::RecycleBinRepository)
    singleOf(::RemoteBarangayBoundaryRepository)
    singleOf(::RoleAssignmentRepository)
    singleOf(::SavedLocationRepository)
    singleOf(::ScheduleRepository)
    singleOf(::SharedLocationRepository)
    singleOf(::TerritoryAssignmentRepository)
    singleOf(::TerritoryBoundaryRepository)
    singleOf(::TerritoryDrawingRepository)
    singleOf(::TerritoryRepository)
    singleOf(::ThemePreferenceRepository)
    singleOf(::UserAccessGrantRepository)
    singleOf(::VisitRepository)
    singleOf(::WeeklyPlannerGoalRepository)
    singleOf(::YearlyPlannerGoalRepository)
    singleOf(::ConnectivityObserver)
    singleOf(::DataRefresher)
    singleOf(::OfflineFirestoreRepository)
    singleOf(::PresenceHeartbeat)
    singleOf(::ReminderScheduler)
    single { RemoteSyncCoordinator(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    singleOf(::SyncScheduler)
    singleOf(::SyncStatusCenter)
    singleOf(::ApkDownloader)
    singleOf(::InstalledUpdateInfoStore)
    singleOf(::UpdateInstaller)
    singleOf(::UpdateManifestCache)
    singleOf(::UpdateManifestRepository)
    singleOf(::UpdateReminderStore)
    singleOf(::DateRangeStore)
    singleOf(::PublisherReportService)
    singleOf(::UserSession)
    singleOf(::CalendarAlarmRescheduler)
    singleOf(::NotificationSoundCoordinator)
    singleOf(::BiometricEnrollmentOffer)
    singleOf(::PendingLoginNotice)
    singleOf(::NotificationItemsProvider)
    singleOf(::InactivityTracker)

    // WorkManager workers
    workerOf(::SyncWorker)
    workerOf(::ReminderWorker)

    singleOf(::OverpassStreetRepository)

    // ViewModels
    viewModelOf(::LogoViewModel)
    viewModelOf(::ManualSyncViewModel)
    viewModelOf(::MinistryTimerViewModel)
    viewModelOf(::NameOrderViewModel)
    viewModelOf(::OfflineSessionBannerViewModel)
    viewModelOf(::OnlineUsersViewModel)
    viewModelOf(::PhilippineAddressPickerViewModel)
    viewModelOf(::RefreshButtonViewModel)
    viewModelOf(::SyncMessageHostViewModel)
    viewModelOf(::SyncStatusIndicatorViewModel)
    viewModelOf(::CurrentLocationViewModel)
    viewModelOf(::MapDrawingViewModel)
    viewModelOf(::UpdateViewModel)
    viewModelOf(::SessionViewModel)
    viewModelOf(::AccountSettingsViewModel)
    viewModelOf(::AccountManagementViewModel)
    viewModelOf(::ManageAdminsViewModel)
    viewModelOf(::ManageAnnouncementsViewModel)
    viewModelOf(::ForcedPasswordChangeViewModel)
    viewModelOf(::ForgotPasswordViewModel)
    viewModelOf(::BackupRestoreViewModel)
    viewModelOf(::BibleTextRecordViewModel)
    viewModelOf(::BibleVerseTextViewModel)
    viewModelOf(::VideoViewModel)
    viewModelOf(::CalendarViewModel)
    viewModelOf(::ManageCongregationsViewModel)
    viewModelOf(::ContactRecordViewModel)
    viewModelOf(::ControlPanelViewModel)
    viewModelOf(::CreditHourCategoriesViewModel)
    viewModelOf(::DashboardStatsViewModel)
    viewModelOf(::DeletedRecordsViewModel)
    viewModelOf(::ManageEldersViewModel)
    viewModelOf(::ManageMinisterialServantsViewModel)
    viewModelOf(::AdminEnrollmentViewModel)
    viewModelOf(::CongregationEnrollmentViewModel)
    viewModelOf(::EldersEnrollmentViewModel)
    viewModelOf(::MinisterialServantEnrollmentViewModel)
    viewModelOf(::PublisherEnrollmentViewModel)
    viewModelOf(::FieldServiceReportViewModel)
    viewModelOf(::FindLocationViewModel)
    viewModelOf(::GroupChatViewModel)
    viewModelOf(::ManageGroupsViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::MyScopeSummaryViewModel)
    viewModelOf(::PublisherDashboardLayoutViewModel)
    viewModelOf(::PublisherDashboardViewModel)
    viewModelOf(::QuickAccessViewModel)
    viewModelOf(::RecentlyVisitedViewModel)
    viewModelOf(::HouseholderAssignmentViewModel)
    viewModelOf(::HouseholderVisitHistoryViewModel)
    viewModelOf(::BiometricOfferViewModel)
    viewModelOf(::BiometricSetupViewModel)
    viewModelOf(::LoginMethodsViewModel)
    viewModelOf(::LoginViewModel)
    viewModelOf(::ManualFieldServiceViewModel)
    viewModelOf(::MeetingAssignmentsViewModel)
    viewModelOf(::MonthlyReportViewModel)
    viewModelOf(::MySubmittedReportsViewModel)
    viewModelOf(::NotificationCenterViewModel)
    viewModelOf(::ForwardRequestsViewModel)
    viewModelOf(::PipelineViewModel)
    viewModelOf(::PublisherForwardRequestsViewModel)
    viewModelOf(::CreditHourEntryViewModel)
    viewModelOf(::PlannerComparativeViewModel)
    viewModelOf(::PlannerDayViewModel)
    viewModelOf(::PlannerMonthViewModel)
    viewModelOf(::PlannerReportViewModel)
    viewModelOf(::PlannerWeekViewModel)
    viewModelOf(::PlannerYearViewModel)
    viewModelOf(::PreachingTimeRecordViewModel)
    viewModelOf(::ManagePublisherReportsViewModel)
    viewModelOf(::ManagePublishersViewModel)
    viewModelOf(::ComparativeReportViewModel)
    viewModelOf(::ConsolidatedReportViewModel)
    viewModelOf(::FieldServiceGroupReportViewModel)
    viewModelOf(::ReportsViewModel)
    viewModelOf(::SessionTimeoutViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ShareLocationViewModel)
    viewModelOf(::TerritoryMapViewModel)
    viewModelOf(::PublisherTerritoryAssignmentViewModel)
    viewModelOf(::TerritoryAssignmentWizardViewModel)
    viewModelOf(::TerritoryAssignmentsViewModel)
    viewModelOf(::UserLogsViewModel)
    viewModelOf(::AddEditUserViewModel)
    viewModelOf(::ManageUsersViewModel)
}
