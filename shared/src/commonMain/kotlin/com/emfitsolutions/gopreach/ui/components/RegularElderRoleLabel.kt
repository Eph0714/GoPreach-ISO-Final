package com.emfitsolutions.gopreach.ui.components

import com.emfitsolutions.gopreach.data.model.RegularElderRole

/** Display label for a [RegularElderRole] — used everywhere the role is shown
 * (enrollment, Elder list, Group management, Elder dashboard). */
fun RegularElderRole.displayLabel(): String = when (this) {
    RegularElderRole.GROUP_OVERSEER -> "Group Overseer"
    RegularElderRole.GROUP_SERVANT -> "Group Servant"
    RegularElderRole.GROUP_ASSISTANT -> "Group Assistant"
}
