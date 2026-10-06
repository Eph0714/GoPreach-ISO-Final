package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.domain.map.DrawingAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Resolves what the signed-in person may do with the map drawing tools, from the
 * same data every other FS Group permission in the app comes from: their active
 * role assignments and the Group records' Overseer/Servant/Assistant slots (see
 * [com.emfitsolutions.gopreach.domain.GroupAccessScope]). The geometric
 * boundary check lives in [com.emfitsolutions.gopreach.domain.map.DrawingValidator];
 * the matching server-side rule lives in firestore.rules.
 */
class DrawingPermissionService(
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val groupRepository: GroupRepository,
) {
    /** Live [DrawingAccess] for [personId] — re-resolved whenever their roles or any Group's slots change. */
    fun observeAccess(personId: String): Flow<DrawingAccess> =
        combine(
            personRepository.observeAll(),
            roleAssignmentRepository.observeForPerson(personId),
            groupRepository.observeAll(),
        ) { people, assignments, groups ->
            DrawingAccess.resolve(people.firstOrNull { it.id == personId }, assignments, groups)
        }
}
