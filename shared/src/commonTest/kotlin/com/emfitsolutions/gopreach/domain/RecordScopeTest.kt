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

    private fun session(vararg roles: RoleAssignment, selected: String? = null, groups: List<Group> = emptyList(), superAdmin: Boolean = false) =
        SessionState(
            isLoading = false,
            person = Person(id = "juan", isSuperAdmin = superAdmin),
            roleAssignments = roles.toList(),
            groups = groups,
            selectedRoleAssignmentId = selected,
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
    fun `the selected role decides the scope - roles are never merged`() {
        val ce = admin(AdminRole.COORDINATOR_ELDER, "ra-ce")
        val group = admin(AdminRole.REGULAR_ELDER, "ra-group", groupId = "g5", slot = RegularElderRole.GROUP_OVERSEER)
        val asCe = recordScopeOf(session(ce, group, selected = "ra-ce"))
        val asGroup = recordScopeOf(session(ce, group, selected = "ra-group"))
        assertNotNull(asCe); assertNotNull(asGroup)
        assertNull(asCe.groupId, "Coordinator Elder: congregation-wide")
        assertEquals("g5", asGroup.groupId, "Group Coordinator: FS Group only — the Coordinator Elder role is not silently added")
        assertFalse(asGroup.allowsPublisher("solano", "g6"))
        assertNull(recordScopeOf(session(ce, group)), "until a role is chosen there is no scope")
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
        val state = session(admin(AdminRole.COORDINATOR_ELDER, "ra-ce"), coordinator.copy(id = "ra-g"), groups = groups, selected = "ra-g")
        assertEquals("Group Coordinator — FS Group 5", state.activeRoleLabel)
        assertEquals("g5", state.activeGroupId)
    }
}
