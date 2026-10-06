package com.emfitsolutions.gopreach.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.domain.RoleOption

/**
 * "Multiple Role Login Detection & Role Selection" spec §10 — shown only for
 * an account with more than one active role (see
 * [com.emfitsolutions.gopreach.domain.SessionState.needsRoleSelection]),
 * right after credentials are verified and before any interface opens.
 * [onSignOut] is this screen's own escape hatch — not named in the spec, but
 * without it a person who lands here for the wrong account (or simply
 * changes their mind) would otherwise be stuck: this screen has no Back
 * destination, since Login intentionally never re-shows a signed-in
 * session's credentials.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectRoleScreen(
    personName: String,
    roleOptions: List<RoleOption>,
    onContinue: (RoleOption) -> Unit,
    onSignOut: () -> Unit,
) {
    var selected by remember(roleOptions) { mutableStateOf<RoleOption?>(null) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.select_role_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.select_role_welcome, personName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                // Spec §2/§10 — the exact required wording.
                stringResource(R.string.select_role_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.select_role_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.label ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text(stringResource(R.string.select_role_placeholder)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    visualTransformation = VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // "The dropdown must automatically contain only the roles
                    // detected from the database for the authenticated
                    // username and password... Do not display roles that are
                    // not assigned to the authenticated user." — [roleOptions]
                    // is already exactly that list; nothing else is offered.
                    roleOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                selected = option
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { selected?.let(onContinue) },
            // "The Continue button should only be enabled after the user
            // selects a valid detected role."
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.select_role_continue), fontWeight = FontWeight.Bold)
        }

        TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.select_role_sign_out))
        }
    }
}
