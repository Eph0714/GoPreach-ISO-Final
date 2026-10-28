package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleAssignmentStatus
import com.emfitsolutions.gopreach.data.model.RoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unified multi-role account: one login, every role consolidated, no role selection. */
class MultiRoleSessionTest {
    private fun admin(role: AdminRole, congregationId: String? = "solano", groupId: String? = null, slot: RegularElderRole? = null, status: RoleAssignmentStatus = RoleAssignmentStatus.ACTIVE) =
        RoleAssignment(id = "ra-$role-$congregationId-$groupId", personId = "juan", roleType = RoleType.serialize(RoleType.Admin(role)), congregationId = congregationId, groupId = groupId, regularElderRole = slot, status = status)

    private fun publisher(congregationId: String? = "solano", category: PublisherCategory = PublisherCategory.REGULAR_PIONEER) =
        RoleAssignment(id = "ra-pub-$congregationId", personId = "juan", roleType = RoleType.serialize(RoleType.Publisher(category)), congregationId = congregationId)

    private fun session(vararg roles: RoleAssignment) =
        SessionState(isLoading = false, person = Person(id = "juan"), roleAssignments = roles.toList())

    @Test
    fun `a session always resolves by itself - however many roles`() {
        val combos = listOf(
            listOf(admin(AdminRole.COORDINATOR_ELDER), publisher()),
            listOf(admin(AdminRole.SERVICE_OVERSEER), publisher()),
            listOf(admin(AdminRole.SECRETARY), publisher()),
            listOf(admin(AdminRole.MINISTERIAL_SERVANT), publisher()),
            listOf(admin(AdminRole.REGULAR_ELDER), publisher()),
            listOf(admin(AdminRole.COORDINATOR_ELDER), admin(AdminRole.SERVICE_OVERSEER), admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER)),
            listOf(admin(AdminRole.COORDINATOR_ELDER), admin(AdminRole.SERVICE_OVERSEER), admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER), publisher()),
        )
        for (roles in combos) {
            val s = session(*roles.toTypedArray())
            assertNotNull(s.activeRoleAssignment, "resolved without any choice: $roles")
            assertEquals(roles.size, s.roleOptions.size, "every role is detected, none hard-coded: $roles")
        }
    }

    @Test
    fun `the Publisher Main Form is the interface whenever a Publisher category is held`() {
        assertTrue(session(admin(AdminRole.COORDINATOR_ELDER), publisher()).isActivePublisherRole)
        assertFalse(session(admin(AdminRole.COORDINATOR_ELDER)).isActivePublisherRole)
        assertTrue(session(publisher()).isActivePublisherRole)
    }

    @Test
    fun `the most senior Admin-track role scopes the session and same-congregation roles are consolidated`() {
        val s = session(publisher(), admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER), admin(AdminRole.COORDINATOR_ELDER), admin(AdminRole.SERVICE_OVERSEER))
        assertEquals(AdminRole.COORDINATOR_ELDER, s.activeAdminRole)
        assertEquals(listOf(AdminRole.COORDINATOR_ELDER, AdminRole.SERVICE_OVERSEER, AdminRole.REGULAR_ELDER), s.consolidatedAdminRoles)
        assertNull(s.activeGroupId, "the congregation-wide role is not narrowed to the group")
        assertNotNull(s.publisherAssignment)
    }

    @Test
    fun `a role in another congregation is never merged into the session`() {
        val s = session(admin(AdminRole.SERVICE_OVERSEER, "solano"), admin(AdminRole.COORDINATOR_ELDER, "bambang"), publisher("solano"))
        val primaryCongregation = s.primaryAdminAssignment!!.congregationId
        assertTrue(s.consolidatedAdminAssignments.all { it.congregationId == primaryCongregation }, "only one congregation's roles are combined")
        assertEquals(1, s.consolidatedAdminAssignments.size)
    }

    @Test
    fun `the super admin is not congregation-bound`() {
        val s = session(admin(AdminRole.SUPER_ADMIN, congregationId = null), admin(AdminRole.COORDINATOR_ELDER, "bambang"))
        assertEquals(AdminRole.SUPER_ADMIN, s.activeAdminRole)
        assertEquals(2, s.consolidatedAdminAssignments.size)
    }

    @Test
    fun `a removed or inactive role stops counting immediately`() {
        assertEquals(AdminRole.COORDINATOR_ELDER, session(admin(AdminRole.COORDINATOR_ELDER), publisher()).activeAdminRole)
        val revoked = session(admin(AdminRole.COORDINATOR_ELDER, status = RoleAssignmentStatus.INACTIVE), publisher())
        assertNull(revoked.activeAdminRole, "an inactive Admin-track role grants nothing")
        assertTrue(revoked.consolidatedAdminRoles.isEmpty())
        assertTrue(revoked.isActivePublisherRole)
    }

    @Test
    fun `no role at all resolves to nothing, not to a prompt`() {
        val s = session()
        assertNull(s.activeRoleAssignment)
        assertTrue(s.consolidatedAdminRoles.isEmpty())
    }

    @Test
    fun `duplicate roles collapse to one entry`() {
        val s = session(admin(AdminRole.COORDINATOR_ELDER), admin(AdminRole.COORDINATOR_ELDER), publisher())
        assertEquals(1, s.consolidatedAdminRoles.size)
        assertEquals("Coordinator Elder + Publisher", s.activeRoleLabel)
    }
}
