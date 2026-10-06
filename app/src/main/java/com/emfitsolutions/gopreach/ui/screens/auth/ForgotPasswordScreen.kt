package com.emfitsolutions.gopreach.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forgot Password") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.submitted) {
                Text(
                    text = "Request sent. The person who enrolled you will be notified and can issue you new temporary credentials.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Login") }
            } else {
                Text(
                    text = "Enter your username. This is sent to the admin, coordinator elder, or elder who originally enrolled you — they'll issue you new temporary credentials.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text("Username") },
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    // Same fix as LoginScreen's Username field — KeyboardType
                    // .Password (not .Text), despite showing plain text: on
                    // its own, autoCorrectEnabled = false doesn't reliably
                    // stop every IME's "auto-space after a period" behavior
                    // (treating "." as end-of-sentence punctuation and
                    // silently turning "user.test" into "user. test");
                    // several keyboards (Samsung's default included) don't
                    // honor that flag alone. The Password keyboard type is
                    // Android's "visible password" field — it genuinely
                    // disables the whole prose/suggestion engine at the
                    // platform level regardless of IME.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = viewModel::submit, enabled = !uiState.isLoading, modifier = Modifier.fillMaxWidth()) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text("Send Request")
                }
            }
        }
    }
}
