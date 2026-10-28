package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.RegularElderRole
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordScopeTest {
    private fun admin(role: AdminRole, id: String = "ra-$role", congregationId: String? = "solano", groupId: String? = null, slot: RegularElderRole? = null) =
        RoleAssignment(id = id, personId = "juan", roleType = RoleType.serialize(RoleType.Admin(role)), congregationId = congregationId, groupId = groupId, regularElderRole = slot)

    private fun session(vararg roles: RoleAssignment, groups: List<Group> = emptyList(), superAdmin: Boolean = false) =
        SessionState(
            isLoading = false,
            person = Person(id = "juan", isSuperAdmin = superAdmin),
            roleAssignments = roles.toList(),
            groups = groups,
        )

    @Test
    fun `congregation-wide roles manage their whole congregation`() {
        for (role in CongregationWideRecordRoles) {
            val scope = recordScopeOf(session(admin(role)))
            assertNotNull(scope, "$role has a scope")
            assertNull(scope.groupId)
            assertTrue(scope.allowsPublisher("solano", "any-group"))
            assertTrue(scope.allowsPublisher("solano", null))
            assertFalse(scope.allowsPublisher("bambang", "any-group"), "never another congregation")
        }
    }

    @Test
    fun `a group role manages only publishers of its own FS group`() {
        val group = admin(AdminRole.REGULAR_ELDER, "ra-group", groupId = "g5", slot = RegularElderRole.GROUP_SERVANT)
        val scope = recordScopeOf(session(group))
        assertNotNull(scope)
        assertEquals("g5", scope.groupId)
        assertTrue(scope.allowsPublisher("solano", "g5"))
        assertFalse(scope.allowsPublisher("solano", "g6"), "another FS group is out of scope")
        assertFalse(scope.allowsPublisher("solano", null), "a publisher with no group is out of scope")
        assertFalse(scope.allowsPublisher("bambang", "g5"))
    }

    @Test
    fun `a congregation-wide role covers the group role held with it - one session, no choice`() {
        val ce = admin(AdminRole.COORDINATOR_ELDER, "ra-ce")
        val group = admin(AdminRole.REGULAR_ELDER, "ra-group", groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER)
        val scope = recordScopeOf(session(ce, group))
        assertNotNull(scope)
        assertNull(scope.groupId, "Coordinator Elder: congregation-wide, whatever order the roles were assigned in")
        assertNull(recordScopeOf(session(group, ce))!!.groupId)
        assertTrue(scope.allowsPublisher("solano", "g6"))
        assertFalse(scope.allowsPublisher("bambang", "g5"), "never another congregation")
    }
    @Test
    fun `a Regular Elder without a group slot, a publisher and a circuit overseer have no scope`() {
        assertNull(recordScopeOf(session(admin(AdminRole.REGULAR_ELDER, "ra-re"))))
        assertNull(recordScopeOf(session(admin(AdminRole.MINISTERIAL_SERVANT, "ra-ms"))))
        assertNull(recordScopeOf(session(admin(AdminRole.CIRCUIT_OVERSEER, "ra-co", congregationId = null))))
    }

    @Test
    fun `the super admin manages everything`() {
        val scope = recordScopeOf(session(admin(AdminRole.SUPER_ADMIN, congregationId = null), superAdmin = true))
        assertNotNull(scope)
        assertTrue(scope.allowsPublisher("any", "any"))
        assertTrue(scope.allowsPublisher("other", null))
    }

    @Test
    fun `group roles are labelled with their FS group`() {
        val groups = listOf(Group(id = "g5", congregationId = "solano", name = "FS Group 5"))
        val coordinator = admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER)
        assertEquals("Group Coordinator — FS Group 5", roleLabelFor(coordinator, AdminRole.REGULAR_ELDER, groups))
        assertEquals("Group Servant — FS Group 5", roleLabelFor(admin(AdminRole.REGULAR_ELDER, groupId = "g5", slot = RegularElderRole.GROUP_SERVANT), AdminRole.REGULAR_ELDER, groups))
        assertEquals("Coordinator Elder", roleLabelFor(admin(AdminRole.COORDINATOR_ELDER), AdminRole.COORDINATOR_ELDER, groups))
        val onlyGroup = session(coordinator.copy(id = "ra-g"), groups = groups)
        assertEquals("Group Coordinator — FS Group 5", onlyGroup.activeRoleLabel)
        assertEquals("g5", onlyGroup.activeGroupId)
        assertEquals("Coordinator Elder + Group Coordinator — FS Group 5", session(admin(AdminRole.COORDINATOR_ELDER, "ra-ce"), coordinator.copy(id = "ra-g"), groups = groups).activeRoleLabel)
    }
}
