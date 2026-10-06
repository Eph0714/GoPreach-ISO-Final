package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test: Firestore only fills in a document's id when its `@DocumentId` is on the FIELD. In common code a plain
 * `@DocumentId val id` lands on the constructor parameter and the id comes back blank (login then used "@gopreach.internal").
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
