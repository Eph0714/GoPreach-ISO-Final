package com.emfitsolutions.gopreach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RosterSelectionTest {
    private val roster = listOf("Eva Cruz", "Lita Santos", "Efren Martin", "John Martin")

    @Test fun theStoredTextIsReadAndWrittenBackAsTheSameCommaSeparatedValue() {
        assertEquals(listOf("Eva Cruz", "Lita Santos"), RosterSelection.parse(" Eva Cruz ,Lita Santos,, eva cruz"))
        assertEquals("Eva Cruz, Lita Santos", RosterSelection.format(RosterSelection.parse("Eva Cruz,Lita Santos")))
        assertEquals(listOf("Eva and Lita"), RosterSelection.parse("Eva and Lita"))   // an old free-typed value stays one name
        assertEquals(emptyList(), RosterSelection.parse("   "))
    }

    @Test fun theSearchFindsPublishersByAnyPartOfTheNameInAnyOrder() {
        assertEquals(listOf("Efren Martin", "John Martin"), RosterSelection.visible(roster, "martin"))
        assertEquals(listOf("Efren Martin"), RosterSelection.visible(roster, "Martin, Efren"))
        assertEquals(roster, RosterSelection.visible(roster, ""))
        assertEquals(emptyList(), RosterSelection.visible(roster, "zzz"))
    }

    @Test fun ticksAreToggledAndCountedWithoutDuplicates() {
        var sel = RosterSelection.toggle(emptyList(), "Eva Cruz")
        sel = RosterSelection.toggle(sel, "Lita Santos")
        assertEquals(2, sel.size)
        sel = RosterSelection.toggle(sel, "eva cruz")
        assertEquals(listOf("Lita Santos"), sel)
        assertTrue(RosterSelection.isSelected(sel, "LITA SANTOS"))
        assertFalse(RosterSelection.isSelected(sel, "Eva Cruz"))
    }

    @Test fun selectAllOnlyAddsWhatIsShownAndClearEmptiesIt() {
        val shown = RosterSelection.visible(roster, "martin")
        val sel = RosterSelection.selectAll(listOf("Eva Cruz"), shown)
        assertEquals(listOf("Eva Cruz", "Efren Martin", "John Martin"), sel)
        assertEquals(sel, RosterSelection.selectAll(sel, shown))   // again: no duplicates
        assertEquals(emptyList(), RosterSelection.clear())
    }

    @Test fun aNameTypedByHandThatIsNotOnTheRosterIsListedSoItCanBeUnticked() {
        val sel = listOf("Eva Cruz", "Visitor Vic")
        assertEquals(listOf("Visitor Vic"), RosterSelection.outsideRoster(sel, roster))
    }
}
