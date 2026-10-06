package com.emfitsolutions.gopreach.domain.map

import com.emfitsolutions.gopreach.data.model.AdminRole
import com.emfitsolutions.gopreach.data.model.Group
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PublisherCategory
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.data.model.TerritoryDrawing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The permission matrix from the spec, checked end to end against real boundary rings. */
class DrawingPermissionsTest {
    private fun p(lat: Double, lng: Double) = GeoPoint(lat, lng)

    // Territory 1 (Group 1) and Territory 2 (Group 2), side by side in congregation "c1";
    // territory 9 belongs to a group of another congregation.
    private val t1 = TerritoryBoundary("c1_t1", "Uno", "g1", "c1", listOf(listOf(p(16.00, 121.00), p(16.00, 121.01), p(16.01, 121.01), p(16.01, 121.00))))
    private val t2 = TerritoryBoundary("c1_t2", "Dos", "g2", "c1", listOf(listOf(p(16.00, 121.02), p(16.00, 121.03), p(16.01, 121.03), p(16.01, 121.02))))
    private val t9 = TerritoryBoundary("c2_t9", "Nueve", "g9", "c2", listOf(listOf(p(17.00, 121.00), p(17.00, 121.01), p(17.01, 121.01), p(17.01, 121.00))))
    private val territories = listOf(t1, t2, t9)

    private val groups = listOf(
        Group(id = "g1", congregationId = "c1", name = "1", overseerPersonId = "overseer", servantPersonId = "servant", assistantPersonId = "assistant"),
        Group(id = "g2", congregationId = "c1", name = "2", overseerPersonId = "other"),
        Group(id = "g9", congregationId = "c2", name = "9", overseerPersonId = "other2"),
    )

    private val inT1 = listOf(p(16.002, 121.002), p(16.002, 121.008), p(16.008, 121.008), p(16.008, 121.002))
    private val inT2 = listOf(p(16.002, 121.022), p(16.002, 121.028), p(16.008, 121.028), p(16.008, 121.022))
    private val inT9 = listOf(p(17.002, 121.002), p(17.002, 121.008), p(17.008, 121.008), p(17.008, 121.002))
    private val straddlingT1AndNowhere = listOf(p(16.002, 121.002), p(16.002, 121.015), p(16.008, 121.015), p(16.008, 121.002))
    private val nowhere = listOf(p(16.002, 121.012), p(16.002, 121.017), p(16.008, 121.017), p(16.008, 121.012))

    private fun admin(personId: String, role: AdminRole, cong: String?, group: String? = null) = RoleAssignment(
        id = "ra-$personId-$role", personId = personId, roleType = RoleType.serialize(RoleType.Admin(role)), congregationId = cong, groupId = group,
    )

    private fun access(personId: String, vararg assignments: RoleAssignment, superAdmin: Boolean = false) =
        DrawingAccess.resolve(Person(id = personId, firstName = personId, isSuperAdmin = superAdmin), assignments.toList(), groups)

    private fun groupMember(personId: String, group: String) = RoleAssignment(
        id = "pub-$personId", personId = personId, roleType = RoleType.serialize(RoleType.Publisher(PublisherCategory.REGULAR_PUBLISHER)),
        congregationId = "c1", groupId = group,
    )

    private val overseer = access("overseer", admin("overseer", AdminRole.REGULAR_ELDER, "c1", "g1"))
    private val servant = access("servant", admin("servant", AdminRole.MINISTERIAL_SERVANT, "c1", "g1"))
    private val assistant = access("assistant", groupMember("assistant", "g1"))
    private val serviceOverseer = access("so", admin("so", AdminRole.SERVICE_OVERSEER, "c1"))
    private val secretary = access("sec", admin("sec", AdminRole.SECRETARY, "c1"))
    private val coordinator = access("co", admin("co", AdminRole.COORDINATOR_ELDER, "c1"))
    private val superAdmin = access("sa", admin("sa", AdminRole.SUPER_ADMIN, null), superAdmin = true)
    private val plainPublisher = access("pub", groupMember("pub", "g1"))

    private fun polygon(a: DrawingAccess, ring: List<GeoPoint>, congregation: String = "c1") =
        DrawingValidator.validatePolygon(a, ring, territories, congregation)

    @Test
    fun groupLevelUsersCanDrawInsideTheirOwnTerritory() {
        listOf(overseer, servant, assistant).forEach { who ->
            val r = polygon(who, inT1)
            assertTrue("${who.personId}: $r", r is DrawingValidation.Valid && r.territory?.territoryId == "c1_t1")
        }
    }

    @Test
    fun groupLevelUsersCannotDrawInAnotherFsGroupsTerritory() {
        listOf(overseer, servant, assistant).forEach { who ->
            assertTrue(who.personId, polygon(who, inT2) is DrawingValidation.OutsideAssigned)
            assertTrue(who.personId, polygon(who, inT9) is DrawingValidation.OutsideAssigned)
        }
    }

    @Test
    fun groupLevelUsersCannotLeaveTheirTerritoryEvenPartially() {
        val r = polygon(overseer, straddlingT1AndNowhere)
        assertTrue(r is DrawingValidation.OutsideAssigned)
        assertTrue((r as DrawingValidation.OutsideAssigned).outsideRuns.isNotEmpty())
        assertTrue(polygon(overseer, nowhere) is DrawingValidation.OutsideAssigned)
    }

    @Test
    fun congregationWideRolesCanDrawInEveryFsGroupTerritoryOfTheirCongregation() {
        listOf(serviceOverseer, secretary, coordinator).forEach { who ->
            val a = polygon(who, inT1)
            val b = polygon(who, inT2)
            assertTrue(who.personId, a is DrawingValidation.Valid && a.territory?.groupId == "g1")
            assertTrue(who.personId, b is DrawingValidation.Valid && b.territory?.groupId == "g2")
        }
    }

    @Test
    fun congregationWideRolesCannotDrawInAnotherCongregation() {
        listOf(serviceOverseer, secretary, coordinator).forEach { who ->
            assertEquals(who.personId, DrawingValidation.OtherCongregation, polygon(who, inT9, "c2"))
            assertFalse(who.canDrawIn("g9"))
        }
    }

    @Test
    fun congregationWideRolesMayDrawOutsideAnyFsGroupTerritory() {
        listOf(coordinator, secretary, serviceOverseer).forEach { who ->
            val r = polygon(who, nowhere)
            assertTrue(who.personId, r is DrawingValidation.Valid && r.territory == null && r.congregationId == "c1")
        }
        // Group-level users still may not.
        assertTrue(polygon(overseer, nowhere) is DrawingValidation.OutsideAssigned)
    }

    @Test
    fun superAdminCanDrawInAnyCongregation() {
        assertTrue(polygon(superAdmin, inT1) is DrawingValidation.Valid)
        assertTrue(polygon(superAdmin, inT9, "c2") is DrawingValidation.Valid)
        assertTrue(superAdmin.canDrawIn("g9") && superAdmin.canDrawIn("g1"))
    }

    @Test
    fun aMemberWhoHoldsNoGroupSlotCannotDrawAtAll() {
        assertFalse(plainPublisher.canUseDrawingTools)
        assertEquals(DrawingValidation.NotAllowed, polygon(plainPublisher, inT1))
    }

    @Test
    fun noRolesMeansNoDrawing() {
        assertFalse(DrawingAccess.NONE.canUseDrawingTools)
        assertEquals(DrawingValidation.NotAllowed, polygon(DrawingAccess.NONE, inT1))
    }

    @Test
    fun selfCrossingPolygonsAreRejected() {
        val bowtie = listOf(p(16.002, 121.002), p(16.008, 121.008), p(16.002, 121.008), p(16.008, 121.002))
        assertEquals(DrawingValidation.SelfIntersecting, polygon(coordinator, bowtie))
    }

    @Test
    fun managingExistingDrawingsFollowsTheSameScope() {
        val inGroup1 = TerritoryDrawing(id = "d", groupId = "g1", congregationId = "c1")
        val inGroup2 = inGroup1.copy(groupId = "g2")
        val elsewhere = inGroup1.copy(groupId = "g9", congregationId = "c2")
        assertTrue(overseer.canManage(inGroup1))
        assertFalse(overseer.canManage(inGroup2))
        assertTrue(servant.canManage(inGroup1))
        assertFalse(servant.canManage(inGroup2))
        assertTrue(assistant.canManage(inGroup1))
        assertFalse(assistant.canManage(inGroup2))
        assertTrue(coordinator.canManage(inGroup1) && coordinator.canManage(inGroup2))
        assertFalse(coordinator.canManage(elsewhere))
        assertTrue(secretary.canManage(inGroup2))
        assertTrue(serviceOverseer.canManage(inGroup2))
        assertTrue(superAdmin.canManage(elsewhere))
        assertFalse(plainPublisher.canManage(inGroup1))
    }

    @Test
    fun roleLabelsNameTheSlotTheUserHolds() {
        assertEquals("Group Overseer", overseer.roleLabelFor("g1"))
        assertEquals("Group Servant", servant.roleLabelFor("g1"))
        assertEquals("Group Assistant", assistant.roleLabelFor("g1"))
        assertEquals("Coordinator Elder", coordinator.roleLabelFor("g2"))
        assertEquals("Secretary", secretary.roleLabelFor("g1"))
        assertEquals("Service Overseer", serviceOverseer.roleLabelFor("g1"))
        assertEquals("Super Admin", superAdmin.roleLabelFor("g9"))
    }

    @Test
    fun longPressDrawIsOfferedOnlyWhereTheRoleAllowsIt() {
        val inside = p(16.005, 121.005)
        val inOtherGroup = p(16.005, 121.025)
        val unassigned = p(16.005, 121.015)
        // Group-level: only inside their own FS Group territory.
        listOf(overseer, servant, assistant).forEach { who ->
            assertTrue(who.personId, who.canDrawAt(inside, territories, "c1"))
            assertFalse(who.personId, who.canDrawAt(inOtherGroup, territories, "c1"))
            assertFalse(who.personId, who.canDrawAt(unassigned, territories, "c1"))
        }
        // Congregation-wide: anywhere on their own congregation's map, never another's.
        listOf(coordinator, secretary, serviceOverseer).forEach { who ->
            assertTrue(who.canDrawAt(inside, territories, "c1"))
            assertTrue(who.canDrawAt(inOtherGroup, territories, "c1"))
            assertTrue(who.canDrawAt(unassigned, territories, "c1"))
            assertFalse(who.canDrawAt(inside, territories, "c2"))
        }
        assertTrue(superAdmin.canDrawAt(unassigned, territories, "c1") && superAdmin.canDrawAt(unassigned, territories, "c2"))
        assertFalse(plainPublisher.canDrawAt(inside, territories, "c1"))
    }

    @Test
    fun adminDrawsAnywhereInOwnCongregationButNotElsewhere() {
        val admin = access("adm", admin("adm", AdminRole.ADMIN_PER_CONGREGATION, "c1"))
        assertTrue(polygon(admin, nowhere) is DrawingValidation.Valid)
        assertTrue(polygon(admin, inT2) is DrawingValidation.Valid)
        assertEquals(DrawingValidation.OtherCongregation, polygon(admin, inT9, "c2"))
    }
}
