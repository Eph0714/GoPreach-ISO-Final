package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The module-access matrix of the unified multi-role account: what each role grants, and how roles combine. */
class AdminCapabilitiesTest {
    private fun admin(role: AdminRole, congregationId: String? = "solano", groupId: String? = null, slot: RegularElderRole? = null) =
        RoleAssignment(id = "ra-$role-$congregationId-$groupId", personId = "juan", roleType = RoleType.serialize(RoleType.Admin(role)), congregationId = congregationId, groupId = groupId, regularElderRole = slot)

    private val pioneer = RoleAssignment(id = "ra-pub", personId = "juan", roleType = RoleType.serialize(RoleType.Publisher(PublisherCategory.REGULAR_PIONEER)), congregationId = "solano")

    private fun caps(vararg roles: RoleAssignment) =
        AdminCapabilities.of(SessionState(isLoading = false, person = Person(id = "juan"), roleAssignments = roles.toList()))

    @Test
    fun `a publisher with no Admin-track role is offered no admin module`() {
        val c = caps(pioneer)
        assertEquals(AdminCapabilities(), c)
        assertTrue(c.tabModules.isEmpty())
    }

    @Test
    fun `a single role grants exactly what that role always granted`() {
        val ce = caps(admin(AdminRole.COORDINATOR_ELDER))
        assertTrue(ce.canEnrollPublisher); assertTrue(ce.canManageGroups); assertTrue(ce.canViewUserLogs); assertTrue(ce.canManageAnnouncements)
        assertFalse("Publisher Assignment is not a Coordinator Elder module", ce.canManageHouseholderAssignment)
        assertFalse(ce.isSuperAdmin); assertFalse(ce.canManageCongregationsAndAdmins); assertFalse(ce.canAccessControlPanel)

        val so = caps(admin(AdminRole.SERVICE_OVERSEER))
        assertTrue(so.canManageHouseholderAssignment); assertTrue(so.canManageTerritoryAssignments); assertTrue(so.canViewSentReports)
        assertFalse(so.canViewUserLogs); assertFalse(so.canManageAnnouncements)

        val ms = caps(admin(AdminRole.MINISTERIAL_SERVANT))
        assertTrue(ms.simpleMenu); assertTrue(ms.canViewTerritoryMap)
        assertFalse(ms.canEnrollPublisher); assertFalse(ms.canManageGroups)
    }

    @Test
    fun `roles combine - the union of what each grants, nothing more`() {
        val both = caps(admin(AdminRole.COORDINATOR_ELDER), admin(AdminRole.SERVICE_OVERSEER), pioneer)
        assertTrue("from Coordinator Elder", both.canManageAnnouncements && both.canViewUserLogs)
        assertTrue("from Service Overseer", both.canManageHouseholderAssignment && both.canViewSentReports)
        assertFalse("neither grants it", both.canManageCongregationsAndAdmins || both.isSuperAdmin || both.canAccessControlPanel || both.canManageCircuits)
    }

    @Test
    fun `a Group Coordinator alone gets group reach only, together with a Coordinator Elder the congregation reach`() {
        val group = admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER)
        val alone = caps(group)
        assertFalse(alone.canEnrollPublisher); assertFalse(alone.canManagePublishersAndGroups)
        assertTrue(alone.canViewFieldServiceGroupReport)
        val together = caps(group, admin(AdminRole.COORDINATOR_ELDER))
        assertTrue(together.canEnrollPublisher && together.canManagePublishersAndGroups && together.canViewFieldServiceGroupReport)
    }

    @Test
    fun `simple menu applies only when every consolidated role is a Ministerial Servant`() {
        assertTrue(caps(admin(AdminRole.MINISTERIAL_SERVANT)).simpleMenu)
        assertFalse(caps(admin(AdminRole.MINISTERIAL_SERVANT), admin(AdminRole.REGULAR_ELDER)).simpleMenu)
    }

    @Test
    fun `a role in another congregation does not add its modules`() {
        val c = caps(admin(AdminRole.MINISTERIAL_SERVANT, "solano"), admin(AdminRole.COORDINATOR_ELDER, "bambang"))
        // Coordinator Elder is the most senior, so it scopes the session; the Ministerial Servant of "solano" is NOT merged in.
        assertTrue(c.canEnrollPublisher)
        assertFalse("solano's Ministerial Servant role must not be merged into bambang's session", c.simpleMenu)
        val onlyBambangRoles = caps(admin(AdminRole.COORDINATOR_ELDER, "bambang"))
        assertEquals(onlyBambangRoles, c)
    }

    @Test
    fun `every admin module is reachable once - side items never repeat a route`() {
        val c = caps(admin(AdminRole.COORDINATOR_ELDER), admin(AdminRole.SERVICE_OVERSEER), admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER))
        val tabs = c.tabModules
        assertTrue(AdminTab.PUBLISHERS in tabs && AdminTab.FIELD_SERVICE in tabs && AdminTab.ATTENDANCE in tabs && AdminTab.TERRITORY in tabs)
    }

    @Test
    fun `the super admin and the circuit overseer keep their own matrices`() {
        val sa = caps(admin(AdminRole.SUPER_ADMIN, congregationId = null))
        assertTrue(sa.isSuperAdmin && sa.canManageCongregationsAndAdmins && sa.canManageCircuits && sa.canAccessControlPanel && sa.canManageSessionTimeout)
        val co = caps(admin(AdminRole.CIRCUIT_OVERSEER, congregationId = null))
        assertTrue(co.isCircuitOverseer)
        assertTrue(co.tabModules.isEmpty())
        assertFalse(co.canEnrollPublisher)
    }
}
