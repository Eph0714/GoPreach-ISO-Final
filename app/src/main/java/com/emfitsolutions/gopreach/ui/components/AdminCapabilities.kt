package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Permission
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import com.emfitsolutions.gopreach.domain.SessionState
import com.emfitsolutions.gopreach.domain.groupRoleOf
import com.emfitsolutions.gopreach.ui.screens.manualreport.ManualEntryRoles

/**
 * What the signed-in account may open from the Admin-track side panel - one flag per module family, computed ONCE per session from every
 * role the account holds (unified multi-role account: there is no role to choose).
 *
 * Each flag is the OR of what each consolidated role grants ([forRole]); a role that grants nothing contributes nothing, and a role held in
 * another congregation is never merged in (see [SessionState.consolidatedAdminAssignments]). This only decides what is OFFERED - every
 * screen and docs/legacy-firebase/firestore.rules still enforce the real scope and permission, so hiding a module is never the only protection.
 */
data class AdminCapabilities(
    val isSuperAdmin: Boolean = false,
    val isCircuitOverseer: Boolean = false,
    /** Every consolidated role is a Ministerial Servant: they get the simple menu. */
    val simpleMenu: Boolean = false,
    val canManageCongregationsAndAdmins: Boolean = false,
    val canEnrollCoordinatorElder: Boolean = false,
    val canEnrollServiceOverseer: Boolean = false,
    val canEnrollMinisterialServant: Boolean = false,
    val canManageAnnouncements: Boolean = false,
    val canViewConsolidatedReport: Boolean = false,
    val canViewFieldServiceGroupReport: Boolean = false,
    /** [canViewFieldServiceGroupReport] plus the Circuit Overseer, who reaches it from Received Reports. */
    val canViewFieldServiceReport: Boolean = false,
    val canViewDeletedRecords: Boolean = false,
    val canViewForwardRequests: Boolean = false,
    val canManageHouseholderAssignment: Boolean = false,
    val showRecentlyVisited: Boolean = false,
    val canManagePublisherReports: Boolean = false,
    val canAccessControlPanel: Boolean = false,
    val canViewUserLogs: Boolean = false,
    val canViewContactRecord: Boolean = false,
    val canManagePublishersAndGroups: Boolean = false,
    val canEnrollPublisher: Boolean = false,
    val canEnrollRegularElderOrPublisher: Boolean = false,
    val canViewTerritoryMap: Boolean = false,
    val canManageTerritories: Boolean = false,
    val canViewInterestedPeopleScope: Boolean = false,
    val canEditMeetingAssignments: Boolean = false,
    val canManageGroups: Boolean = false,
    val canManageTerritoryAssignments: Boolean = false,
    val canManageSessionTimeout: Boolean = false,
    val canManageCircuits: Boolean = false,
    val canManageUsers: Boolean = false,
    val canManageAccountCredentials: Boolean = false,
    val canManageCreditHourCategories: Boolean = false,
    val canEnterManualFieldService: Boolean = false,
    val canManageMeetingAttendance: Boolean = false,
    val canViewSentReports: Boolean = false,
    /** Device & Session Management: the Super Admin (everyone) and the Admin of a congregation (their own). The server enforces the same. */
    val canMonitorDevices: Boolean = false,
    /** Security Audit Logs: the Super Admin only. */
    val canViewSecurityLog: Boolean = false,
    /** Monthly Report Snapshots of the whole congregation: Admin, Coordinator Elder, Service Overseer, Secretary (and the Super Admin). */
    val canReadAllSnapshots: Boolean = false,
    /** The Overseer, Servant or Assistant of an FS Group (read from the group's own slots, not from a role): Publishers Assignment and saving snapshots of their own group. */
    val isGroupOfficer: Boolean = false,
) {
    /** Monthly Report Snapshots are offered to an FS Group officer (their own group) and to the congregation-wide roles (all of the congregation). */
    val canOpenSnapshots: Boolean get() = (canReadAllSnapshots || isGroupOfficer) && !isCircuitOverseer

    /** FS Group Report Edit Requests: an FS Group officer (own group) and the congregation-wide roles (the whole congregation, as they already keep). */
    val canOpenEditRequests: Boolean get() = (canReadAllSnapshots || isGroupOfficer) && !isCircuitOverseer
    /** Field Service / Attendance / Territory / Publishers: the modules of the Admin Main Form's tab strip. */
    val tabModules: Set<AdminTab>
        get() = buildSet {
            if (isCircuitOverseer && !isSuperAdmin) return@buildSet
            if (canEnrollPublisher) add(AdminTab.PUBLISHERS)
            if (canViewFieldServiceGroupReport) add(AdminTab.FIELD_SERVICE)
            if (canManageMeetingAttendance) add(AdminTab.ATTENDANCE)
            if (canViewTerritoryMap) add(AdminTab.TERRITORY)
        }

    private infix fun or(o: AdminCapabilities) = AdminCapabilities(
        isSuperAdmin = isSuperAdmin || o.isSuperAdmin,
        isCircuitOverseer = isCircuitOverseer || o.isCircuitOverseer,
        simpleMenu = simpleMenu && o.simpleMenu,
        canManageCongregationsAndAdmins = canManageCongregationsAndAdmins || o.canManageCongregationsAndAdmins,
        canEnrollCoordinatorElder = canEnrollCoordinatorElder || o.canEnrollCoordinatorElder,
        canEnrollServiceOverseer = canEnrollServiceOverseer || o.canEnrollServiceOverseer,
        canEnrollMinisterialServant = canEnrollMinisterialServant || o.canEnrollMinisterialServant,
        canManageAnnouncements = canManageAnnouncements || o.canManageAnnouncements,
        canViewConsolidatedReport = canViewConsolidatedReport || o.canViewConsolidatedReport,
        canViewFieldServiceGroupReport = canViewFieldServiceGroupReport || o.canViewFieldServiceGroupReport,
        canViewFieldServiceReport = canViewFieldServiceReport || o.canViewFieldServiceReport,
        canViewDeletedRecords = canViewDeletedRecords || o.canViewDeletedRecords,
        canViewForwardRequests = canViewForwardRequests || o.canViewForwardRequests,
        canManageHouseholderAssignment = canManageHouseholderAssignment || o.canManageHouseholderAssignment,
        showRecentlyVisited = showRecentlyVisited || o.showRecentlyVisited,
        canManagePublisherReports = canManagePublisherReports || o.canManagePublisherReports,
        canAccessControlPanel = canAccessControlPanel || o.canAccessControlPanel,
        canViewUserLogs = canViewUserLogs || o.canViewUserLogs,
        canViewContactRecord = canViewContactRecord || o.canViewContactRecord,
        canManagePublishersAndGroups = canManagePublishersAndGroups || o.canManagePublishersAndGroups,
        canEnrollPublisher = canEnrollPublisher || o.canEnrollPublisher,
        canEnrollRegularElderOrPublisher = canEnrollRegularElderOrPublisher || o.canEnrollRegularElderOrPublisher,
        canViewTerritoryMap = canViewTerritoryMap || o.canViewTerritoryMap,
        canManageTerritories = canManageTerritories || o.canManageTerritories,
        canViewInterestedPeopleScope = canViewInterestedPeopleScope || o.canViewInterestedPeopleScope,
        canEditMeetingAssignments = canEditMeetingAssignments || o.canEditMeetingAssignments,
        canManageGroups = canManageGroups || o.canManageGroups,
        canManageTerritoryAssignments = canManageTerritoryAssignments || o.canManageTerritoryAssignments,
        canManageSessionTimeout = canManageSessionTimeout || o.canManageSessionTimeout,
        canManageCircuits = canManageCircuits || o.canManageCircuits,
        canManageUsers = canManageUsers || o.canManageUsers,
        canManageAccountCredentials = canManageAccountCredentials || o.canManageAccountCredentials,
        canManageCreditHourCategories = canManageCreditHourCategories || o.canManageCreditHourCategories,
        canEnterManualFieldService = canEnterManualFieldService || o.canEnterManualFieldService,
        canManageMeetingAttendance = canManageMeetingAttendance || o.canManageMeetingAttendance,
        canViewSentReports = canViewSentReports || o.canViewSentReports,
        canMonitorDevices = canMonitorDevices || o.canMonitorDevices,
        canViewSecurityLog = canViewSecurityLog || o.canViewSecurityLog,
        canReadAllSnapshots = canReadAllSnapshots || o.canReadAllSnapshots,
        isGroupOfficer = isGroupOfficer || o.isGroupOfficer,
    )

    companion object {
        /**
         * The capabilities of every role [session] consolidates, merged. A role that is a group role (Group Coordinator / Servant /
         * Assistant) is evaluated as one, so it can never grant the congregation-wide reach a congregation-wide role would.
         */
        fun of(session: SessionState): AdminCapabilities {
            val grantPermissions = session.grant?.resolvedPermissions.orEmpty()
            val groupCanOpen = GroupAccessScope.resolve(session.person, session.roleAssignments).canOpen
            val perRole = session.consolidatedAdminAssignments.mapNotNull { assignment ->
                val role = (assignment.resolvedRoleTypeOrNull() as? com.emfitsolutions.gopreach.data.model.RoleType.Admin)?.role ?: return@mapNotNull null
                forRole(role, isGroupRole = groupRoleOf(assignment) != null, grantPermissions, groupCanOpen)
            }
            val officer = com.emfitsolutions.gopreach.ui.screens.groupmodules.officerSlotOf(session.person?.id.orEmpty(), session.publisherAssignment?.congregationId ?: session.primaryAdminAssignment?.congregationId, session.groups) != null
            val merged = if (perRole.isEmpty()) AdminCapabilities() else perRole.reduce { a, b -> a or b }
            return if (officer) merged.copy(isGroupOfficer = true) else merged
        }

        /** One role's capabilities - the permission matrix, in one place (it used to be re-derived on the Admin Main Form alone). */
        fun forRole(role: AdminRole, isGroupRole: Boolean, grantPermissions: Set<Permission>, groupCanOpen: Boolean): AdminCapabilities {
            val isSuperAdmin = role == AdminRole.SUPER_ADMIN
            val canEnrollCoordinatorElder = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION
            val canEnrollRegularElderOrPublisher = canEnrollCoordinatorElder || role == AdminRole.COORDINATOR_ELDER
            val canEnrollPublisher = canEnrollRegularElderOrPublisher || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY ||
                Permission.MANAGE_PUBLISHERS in grantPermissions
            val canEnrollMinisterialServant = canEnrollRegularElderOrPublisher || Permission.MANAGE_ELDERS in grantPermissions
            val canViewConsolidatedReport = canEnrollRegularElderOrPublisher || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY
            val canViewFieldServiceGroupReport = canViewConsolidatedReport || role == AdminRole.REGULAR_ELDER ||
                (role == AdminRole.MINISTERIAL_SERVANT && isGroupRole)
            val canManageHouseholderAssignment = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
                role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY
            val canViewUserLogs = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION || role == AdminRole.COORDINATOR_ELDER
            val canManagePublishersAndGroups = canViewUserLogs
            val isMinisterialServant = role == AdminRole.MINISTERIAL_SERVANT
            val canViewTerritoryMap = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
                role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY ||
                role == AdminRole.REGULAR_ELDER || isMinisterialServant
            val manualEntry = role in ManualEntryRoles
            return AdminCapabilities(
                isSuperAdmin = isSuperAdmin,
                isCircuitOverseer = role == AdminRole.CIRCUIT_OVERSEER,
                simpleMenu = isMinisterialServant,
                canManageCongregationsAndAdmins = isSuperAdmin,
                canEnrollCoordinatorElder = canEnrollCoordinatorElder,
                canEnrollServiceOverseer = canEnrollRegularElderOrPublisher,
                canEnrollMinisterialServant = canEnrollMinisterialServant,
                canManageAnnouncements = canEnrollRegularElderOrPublisher,
                canViewConsolidatedReport = canViewConsolidatedReport,
                canViewFieldServiceGroupReport = canViewFieldServiceGroupReport,
                canViewFieldServiceReport = canViewFieldServiceGroupReport || role == AdminRole.CIRCUIT_OVERSEER,
                canViewDeletedRecords = isSuperAdmin || canEnrollPublisher,
                canViewForwardRequests = canViewConsolidatedReport || role == AdminRole.REGULAR_ELDER || role == AdminRole.MINISTERIAL_SERVANT,
                canManageHouseholderAssignment = canManageHouseholderAssignment,
                showRecentlyVisited = canManageHouseholderAssignment || role == AdminRole.COORDINATOR_ELDER,
                canManagePublisherReports = canViewConsolidatedReport || role == AdminRole.REGULAR_ELDER || role == AdminRole.MINISTERIAL_SERVANT ||
                    grantPermissions.any {
                        it == Permission.VIEW_PUBLISHER_REPORTS || it == Permission.VIEW_GROUP_REPORTS || it == Permission.VIEW_CONGREGATION_REPORTS
                    },
                canAccessControlPanel = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION,
                canViewUserLogs = canViewUserLogs,
                canViewContactRecord = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
                    role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY || role == AdminRole.REGULAR_ELDER,
                canManagePublishersAndGroups = canManagePublishersAndGroups,
                canEnrollPublisher = canEnrollPublisher,
                canEnrollRegularElderOrPublisher = canEnrollRegularElderOrPublisher || Permission.MANAGE_ELDERS in grantPermissions,
                canViewTerritoryMap = canViewTerritoryMap,
                canManageTerritories = canViewTerritoryMap || role == AdminRole.CIRCUIT_OVERSEER,
                canViewInterestedPeopleScope = role == AdminRole.SUPER_ADMIN || role == AdminRole.CIRCUIT_OVERSEER ||
                    role == AdminRole.ADMIN_PER_CONGREGATION || role == AdminRole.COORDINATOR_ELDER ||
                    role == AdminRole.SERVICE_OVERSEER || role == AdminRole.REGULAR_ELDER,
                canEditMeetingAssignments = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
                    role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY || role == AdminRole.REGULAR_ELDER,
                canManageGroups = canManagePublishersAndGroups || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY ||
                    Permission.MANAGE_GROUPS in grantPermissions || groupCanOpen,
                canManageTerritoryAssignments = canManagePublishersAndGroups || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY,
                canManageSessionTimeout = isSuperAdmin,
                canManageCircuits = isSuperAdmin,
                canManageUsers = isSuperAdmin || Permission.MANAGE_USERS in grantPermissions,
                canManageAccountCredentials = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION ||
                    role == AdminRole.COORDINATOR_ELDER || role == AdminRole.SERVICE_OVERSEER || role == AdminRole.SECRETARY,
                canManageCreditHourCategories = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION,
                canEnterManualFieldService = manualEntry || isGroupRole,
                canManageMeetingAttendance = manualEntry && !isGroupRole,
                canMonitorDevices = role == AdminRole.SUPER_ADMIN || role == AdminRole.ADMIN_PER_CONGREGATION,
                canViewSecurityLog = isSuperAdmin,
                canReadAllSnapshots = role in setOf(AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY),
                canViewSentReports = role in setOf(
                    AdminRole.SUPER_ADMIN, AdminRole.ADMIN_PER_CONGREGATION, AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.SECRETARY,
                ),
            )
        }
    }
}
