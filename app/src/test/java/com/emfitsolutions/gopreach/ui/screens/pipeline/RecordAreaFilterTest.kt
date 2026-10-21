package com.emfitsolutions.gopreach.ui.screens.pipeline

import com.emfitsolutions.gopreach.data.model.InterestedPerson
import com.emfitsolutions.gopreach.data.model.TerritoryAssignmentBarangay
import com.emfitsolutions.gopreach.domain.GroupAccessScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordAreaFilterTest {
    private fun claim(id: String, cong: String, group: String, muni: String, brgy: String) =
        TerritoryAssignmentBarangay(id = id, congregationId = cong, groupId = group, muncityName = muni, barangayName = brgy)

    private val claims = listOf(
        claim("1", "c1", "g1", "Solano", "Roxas"),
        claim("2", "c1", "g1", "Solano", "Quezon"),
        claim("3", "c1", "g2", "Bayombong", "Casat"),
        claim("4", "c2", "g9", "Kayapa", "Poblacion"),
    )

    private fun person(id: String, muni: String?, brgy: String?) = InterestedPerson(id = id, congregationId = "c1", name = "P$id", cityMunicipality = muni, barangay = brgy)

    @Test
    fun a_group_member_only_gets_the_areas_of_their_own_group() {
        val areas = authorizedAreas(GroupAccessScope.OwnGroup("c1", "me"), "c1", setOf("g1"), claims)
        assertEquals(listOf(AuthorizedArea("Solano", "Quezon"), AuthorizedArea("Solano", "Roxas")), areas)
        assertTrue("another group's territory never shows", areas.none { it.municipality == "Bayombong" })
    }

    @Test
    fun a_congregation_wide_role_gets_its_whole_congregation_but_never_another_congregation() {
        val areas = authorizedAreas(GroupAccessScope.Congregation("c1"), "c1", emptySet(), claims)
        assertEquals(setOf("Solano", "Bayombong"), areas.map { it.municipality }.toSet())
        assertTrue(areas.none { it.municipality == "Kayapa" })
        assertEquals("a congregation-wide role of another congregation sees nothing here", emptyList<AuthorizedArea>(), authorizedAreas(GroupAccessScope.Congregation("c2"), "c1", emptySet(), claims))
    }

    @Test
    fun someone_with_no_group_and_no_wide_role_gets_no_areas() {
        assertEquals(emptyList<AuthorizedArea>(), authorizedAreas(GroupAccessScope.None, "c1", emptySet(), claims))
        assertEquals(emptyList<AuthorizedArea>(), authorizedAreas(GroupAccessScope.OwnGroup("c1", "me"), "c1", setOf("g-not-assigned"), claims))
    }

    @Test
    fun the_super_admin_still_sees_only_the_congregation_being_viewed() {
        assertEquals(setOf("Solano", "Bayombong"), authorizedAreas(GroupAccessScope.AllCongregations, "c1", emptySet(), claims).map { it.municipality }.toSet())
    }

    @Test
    fun the_default_view_keeps_every_record() {
        val all = listOf(person("1", "Solano", "Roxas"), person("2", null, null))
        assertEquals(all, all.filterByArea(AreaFilterMode.ALL, "Solano", "Roxas"))
    }

    @Test
    fun the_municipality_and_barangay_filters_narrow_the_list() {
        val list = listOf(person("1", "Solano", "Roxas"), person("2", "Solano", "Quezon"), person("3", "Bayombong", "Casat"))
        assertEquals(listOf("1", "2"), list.filterByArea(AreaFilterMode.MUNICIPALITY, "Solano", null).map { it.id })
        assertEquals(listOf("2"), list.filterByArea(AreaFilterMode.BARANGAY, "Solano", "Quezon").map { it.id })
        assertEquals("a barangay of another municipality does not match", emptyList<String>(), list.filterByArea(AreaFilterMode.BARANGAY, "Bayombong", "Roxas").map { it.id })
        assertEquals("no municipality chosen yet: nothing is hidden", 3, list.filterByArea(AreaFilterMode.BARANGAY, null, null).size)
    }

    @Test
    fun a_record_with_no_saved_place_never_matches_a_filter() {
        val list = listOf(person("1", "Solano", "Roxas"), person("2", null, "Roxas"), person("3", "Solano", null), person("4", "", ""))
        assertEquals(listOf("1", "3"), list.filterByArea(AreaFilterMode.MUNICIPALITY, "Solano", null).map { it.id })
        assertEquals(listOf("1"), list.filterByArea(AreaFilterMode.BARANGAY, "Solano", "Roxas").map { it.id })
    }

    @Test
    fun the_place_names_match_however_they_were_spelled() {
        val list = listOf(person("1", "City of Solano", "ROXAS"))
        assertEquals(listOf("1"), list.filterByArea(AreaFilterMode.BARANGAY, "solano", "Roxas").map { it.id })
    }

    @Test
    fun the_name_search_ignores_case_matches_any_part_and_works_with_the_area_filter() {
        val list = listOf(person("1", "Solano", "Roxas").copy(name = "Maria Santos"), person("2", "Solano", "Quezon").copy(name = "Juan Cruz"), person("3", "Bayombong", "Casat").copy(name = "Maria Lopez"))
        assertEquals(listOf("1", "3"), list.filter { it.matchesSearch("MARIA") }.map { it.id })
        assertEquals(listOf("2"), list.filter { it.matchesSearch(" cruz ") }.map { it.id })
        assertEquals("a blank search keeps everything", 3, list.filter { it.matchesSearch("  ") }.size)
        // together: only the Marias inside Solano
        assertEquals(listOf("1"), list.filterByArea(AreaFilterMode.MUNICIPALITY, "Solano", null).filter { it.matchesSearch("maria") }.map { it.id })
        assertEquals(emptyList<String>(), list.filter { it.matchesSearch("nobody") }.map { it.id })
    }

    @Test
    fun the_dropdown_choices_depend_on_the_municipality_picked() {
        val areas = authorizedAreas(GroupAccessScope.Congregation("c1"), "c1", emptySet(), claims)
        assertEquals(listOf("Bayombong", "Solano"), areas.municipalities())
        assertEquals(listOf("Quezon", "Roxas"), areas.barangaysOf("Solano"))
        assertEquals(emptyList<String>(), areas.barangaysOf(null))
        assertEquals("a barangay of another municipality is never offered", listOf("Casat"), areas.barangaysOf("Bayombong"))
    }
}
