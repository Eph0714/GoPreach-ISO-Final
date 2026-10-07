package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.data.model.AccountStatus
import com.emfitsolutions.gopreach.data.model.CircuitCode
import com.emfitsolutions.gopreach.data.model.Congregation
import com.emfitsolutions.gopreach.data.model.CongregationCircuit
import com.emfitsolutions.gopreach.data.model.RecordStatus
import com.emfitsolutions.gopreach.data.model.isValidCircuitCode
import com.emfitsolutions.gopreach.data.model.normalizeCircuitCode
import com.emfitsolutions.gopreach.data.repository.validateNewCircuitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Circuit Overseer assignment rules walked through the same sequence as the feature's acceptance checklist. */
class CircuitRulesTest {
    private val solano = Congregation(id = "solano", name = "Solano")
    private val bayombong = Congregation(id = "bayombong", name = "Bayombong")
    private val bambang = Congregation(id = "bambang", name = "Bambang")
    private val bagabag = Congregation(id = "bagabag", name = "Bagabag", status = RecordStatus.INACTIVE)
    private val all = listOf(solano, bayombong, bambang, bagabag)

    private fun link(congregation: Congregation, overseer: String, code: String) =
        congregation.id to CongregationCircuit(id = congregation.id, congregationId = congregation.id, circuitOverseerPersonId = overseer, circuitCode = code)

    private fun names(list: List<Congregation>) = list.map { it.name }

    // ---- 1 / 15: codes are normalised, validated and unique by construction ----

    @Test
    fun `circuit codes are normalised so nt01 and NT01 are the same document`() {
        assertEquals("NT01", normalizeCircuitCode("  nt01 "))
        assertEquals("NT01", normalizeCircuitCode("n t 0 1"))
        assertTrue(isValidCircuitCode("NT01"))
        assertTrue(isValidCircuitCode("NT-02"))
        assertFalse(isValidCircuitCode("N"))
        assertFalse(isValidCircuitCode("NT 01"))
        assertFalse(isValidCircuitCode("NT/01"))
        assertFalse(isValidCircuitCode("-NT01"))
    }

    @Test
    fun `a blank or malformed code is rejected with a message`() {
        assertNotNull(validateNewCircuitCode("").second)
        assertNotNull(validateNewCircuitCode("a/b").second)
        assertEquals("NT01", validateNewCircuitCode(" nt01").first)
        assertNull(validateNewCircuitCode("nt01").second)
    }

    // ---- 2 / 3: a code held by one overseer is not offered to another ----

    @Test
    fun `a held code disappears for other overseers but stays visible for its own`() {
        val codes = listOf(
            CircuitCode(id = "NT01", code = "NT01", overseerPersonId = "A"),
            CircuitCode(id = "NT02", code = "NT02"),
            CircuitCode(id = "NT03", code = "NT03", status = RecordStatus.INACTIVE),
        )
        assertEquals(listOf("NT02"), CircuitRules.availableCodes(codes, "B").map { it.code })
        assertEquals(listOf("NT01", "NT02"), CircuitRules.availableCodes(codes, "A").map { it.code })
        assertEquals(listOf("NT02"), CircuitRules.availableCodes(codes, null).map { it.code }, "a new account sees only free active codes")
    }

    @Test
    fun `an inactive code is never offered, even to its own overseer`() {
        val codes = listOf(CircuitCode(id = "NT01", code = "NT01", status = RecordStatus.INACTIVE, overseerPersonId = "A"))
        assertTrue(CircuitRules.availableCodes(codes, "A").isEmpty())
    }

    // ---- 4 - 10: congregation availability across two overseers ----

    @Test
    fun `congregations held by A are unavailable to B and shown as taken`() {
        val links = mapOf(link(solano, "A", "NT01"), link(bayombong, "A", "NT01"))
        assertEquals(listOf("Bambang"), names(CircuitRules.availableCongregations(all, links, "B")), "B can only pick the free active one")
        assertEquals(listOf("Bayombong", "Solano"), names(CircuitRules.takenCongregations(all, links, "B")))
        assertEquals(listOf("Bambang", "Bayombong", "Solano"), names(CircuitRules.availableCongregations(all, links, "A")), "A still sees its own, plus free ones")
        assertTrue(CircuitRules.takenCongregations(all, links, "A").isEmpty())
    }

    @Test
    fun `removing a congregation from A makes it available to B, and assigning it to B hides it from A`() {
        val afterRemoval = mapOf(link(solano, "A", "NT01"))
        assertTrue("Bayombong" in names(CircuitRules.availableCongregations(all, afterRemoval, "B")))
        assertEquals(listOf("Solano"), names(CircuitRules.takenCongregations(all, afterRemoval, "B")))

        val afterAssignToB = mapOf(link(solano, "A", "NT01"), link(bayombong, "B", "NT02"))
        assertFalse("Bayombong" in names(CircuitRules.availableCongregations(all, afterAssignToB, "A")))
        assertTrue("Bayombong" in names(CircuitRules.takenCongregations(all, afterAssignToB, "A")))
        assertTrue("Bayombong" in names(CircuitRules.availableCongregations(all, afterAssignToB, "B")))
    }

    @Test
    fun `an inactive congregation is not offered unless the overseer already holds it`() {
        assertFalse("Bagabag" in names(CircuitRules.availableCongregations(all, emptyMap(), "A")))
        val held = mapOf(link(bagabag, "A", "NT01"))
        assertTrue("Bagabag" in names(CircuitRules.availableCongregations(all, held, "A")))
    }

    // ---- 5: validation messages ----

    @Test
    fun `assignment problems are reported for each rule`() {
        val active = CircuitCode(id = "NT01", code = "NT01", overseerPersonId = "A")
        val noLinks = emptyMap<String, CongregationCircuit>()
        assertNull(CircuitRules.congregationAssignmentProblem(solano, noLinks, "A", AccountStatus.ACTIVE, active))
        assertNotNull(CircuitRules.congregationAssignmentProblem(solano, noLinks, "A", AccountStatus.INACTIVE, active), "inactive overseer")
        assertNotNull(CircuitRules.congregationAssignmentProblem(solano, noLinks, "A", AccountStatus.SUSPENDED, active), "suspended overseer")
        assertNotNull(CircuitRules.congregationAssignmentProblem(solano, noLinks, "A", AccountStatus.ACTIVE, null), "no code")
        assertNotNull(CircuitRules.congregationAssignmentProblem(solano, noLinks, "A", AccountStatus.ACTIVE, active.copy(status = RecordStatus.INACTIVE)), "inactive code")
        assertNotNull(CircuitRules.congregationAssignmentProblem(solano, noLinks, "B", AccountStatus.ACTIVE, active), "code belongs to A")
        assertNotNull(CircuitRules.congregationAssignmentProblem(bagabag, noLinks, "A", AccountStatus.ACTIVE, active), "inactive congregation")
    }

    @Test
    fun `a congregation already held by someone else is refused with that overseer named`() {
        val links = mapOf(link(solano, "A", "NT01"))
        val code = CircuitCode(id = "NT02", code = "NT02", overseerPersonId = "B")
        val problem = CircuitRules.congregationAssignmentProblem(solano, links, "B", AccountStatus.ACTIVE, code) { if (it == "A") "Juan Dela Cruz" else it }
        assertNotNull(problem)
        assertTrue("Juan Dela Cruz" in problem)
        assertTrue("Remove it" in problem)
        assertNull(CircuitRules.congregationAssignmentProblem(solano, links, "A", AccountStatus.ACTIVE, CircuitCode(id = "NT01", code = "NT01", overseerPersonId = "A")), "its own owner may keep it")
    }

    // ---- 16: migration list ----

    @Test
    fun `the migration list holds exactly the congregations with no complete link`() {
        val links = mapOf(
            link(solano, "A", "NT01"),
            "bambang" to CongregationCircuit(id = "bambang", congregationId = "bambang", circuitOverseerPersonId = "B", circuitCode = ""),
        )
        assertEquals(listOf("Bagabag", "Bambang", "Bayombong"), names(CircuitRules.unassigned(all, links)))
        assertTrue(CircuitRules.unassigned(all, all.associate { link(it, "A", "NT01") }).isEmpty())
    }

    // ---- access: a Circuit Overseer's permissions are view-only ----

    @Test
    fun `an overseer's grant can view and report but never manage`() {
        val permissions = CircuitRules.OVERSEER_PERMISSIONS.map { it.name }
        assertTrue(permissions.none { it.startsWith("MANAGE_") || it.startsWith("ADD_") || it.startsWith("EDIT_") || it.startsWith("DELETE_") })
        // Reports reach the overseer only as submitted copies, never through the live Field Service Record.
        assertTrue(permissions.none { it.endsWith("_REPORTS") && it.startsWith("VIEW_") })
    }
}
