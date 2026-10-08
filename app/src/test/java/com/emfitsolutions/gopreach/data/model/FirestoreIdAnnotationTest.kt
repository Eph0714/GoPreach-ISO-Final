package com.emfitsolutions.gopreach.data.model

import com.emfitsolutions.gopreach.platform.DocumentId
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every synchronized model marks its id property with `@field:DocumentId` (on the FIELD, not the constructor parameter), which is how the
 * sync code and the screens find a record's id. Guards against a model being added or edited without it.
 */
class FirestoreIdAnnotationTest {
    @Test
    fun everyModelWithADocumentIdKeepsItOnTheField() {
        val models = listOf(
            Person::class.java, Congregation::class.java, Announcement::class.java, RoleAssignment::class.java,
            Schedule::class.java, SharedLocation::class.java, PlannerDay::class.java, MapPin::class.java, TerritoryDrawing::class.java,
        )
        for (cls in models) {
            val annotated = cls.declaredFields.filter { it.isAnnotationPresent(DocumentId::class.java) }
            assertTrue("${cls.simpleName} has no @DocumentId on a field", annotated.isNotEmpty())
        }
    }
}
