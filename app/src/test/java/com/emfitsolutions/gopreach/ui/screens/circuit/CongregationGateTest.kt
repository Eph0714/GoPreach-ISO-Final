package com.emfitsolutions.gopreach.ui.screens.circuit

import com.emfitsolutions.gopreach.data.model.Congregation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CongregationGateTest {
    private val circuit = listOf(Congregation(id = "a", name = "A"), Congregation(id = "b", name = "B"))

    @Test fun `nothing chosen means nothing is shown`() = assertNull(validCongregation(circuit, null))

    @Test fun `a chosen congregation of the circuit is accepted`() = assertEquals("b", validCongregation(circuit, "b")?.id)

    @Test fun `a congregation outside the circuit is treated as not chosen`() = assertNull(validCongregation(circuit, "other-circuit"))

    @Test fun `switching circuit forgets the congregation`() {
        CircuitScopeStore.selectCircuit("coA")
        CircuitScopeStore.selectCongregation("a")
        CircuitScopeStore.selectCircuit("coA")
        assertEquals("a", CircuitScopeStore.congregation)
        CircuitScopeStore.selectCircuit("coB")
        assertNull(CircuitScopeStore.congregation)
    }
}
