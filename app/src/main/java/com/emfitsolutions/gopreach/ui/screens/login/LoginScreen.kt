package com.emfitsolutions.gopreach.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.BuildConfig
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.ui.components.GradientHero
import com.emfitsolutions.gopreach.ui.components.update.UpdateViewModel

private val FieldShape = RoundedCornerShape(16.dp)

/**
 * Login entry screen. Establishes the visual pattern used everywhere: plain-text
 * fields by default, password fields masked with a show/hide toggle (spec §1).
 */
@Composable
fun LoginScreen(
    onForgotPasswordClick: () -> Unit,
    onSignedIn: (requiresPasswordChange: Boolean) -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val activity = LocalContext.current as FragmentActivity

    // "Upon User Login... immediately after the user successfully logs in,
    // the system should check whether a newer version is available" — same
    // Activity-scoped UpdateViewModel instance MainActivity's own UpdateHost
    // renders the result of (see that screen's own comment on why), so this
    // doesn't spin up a second, unobserved one.
    val updateViewModel: UpdateViewModel = hiltViewModel(activity)

    LaunchedEffect(uiState.signedIn) {
        if (uiState.signedIn) {
            updateViewModel.checkOnLogin()
            onSignedIn(uiState.requiresPasswordChange == true)
            viewModel.consumeNavigationEvent()
        }
    }

    // A single scrollable Column, footer included as its last item — not a
    // Box-aligned overlay pinned to the bottom of the screen. A pinned overlay
    // looked right with no keyboard up, but once the IME shrinks the visible
    // height (see MainActivity's imePadding()), a fixed-position footer ends up
    // sitting on top of whatever content the shrunk viewport happens to place
    // there — in practice, directly on top of the Log In button. Keeping the
    // footer in-flow means it simply scrolls along like everything else instead.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        GradientHero(height = 220.dp) {
                // Bottom-aligned rather than pinned under the status bar — sitting
                // right at the top of the hero read as too high/cramped; anchoring
                // to the bottom instead gives the title/subtitle block room to
                // breathe and keeps it comfortably in view regardless of status bar
                // height across devices.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = Color.White)
                    Text(
                        stringResource(R.string.login_tagline),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .padding(PaddingValues(horizontal = 24.dp))
                    .padding(top = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.login_welcome_back),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text(stringResource(R.string.login_username)) },
                    singleLine = true,
                    shape = FieldShape,
                    // "Disable: Login Button, Username Field, Password Field"
                    // while authentication is running — this used to only
                    // disable the button below, leaving both fields editable
                    // (and, worse, submittable via the keyboard's own Done/
                    // Enter action) for the whole duration of a request.
                    enabled = !uiState.isLoading,
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    // Plain text, never masked — per spec, only password fields mask input.
                    visualTransformation = VisualTransformation.None,
                    // KeyboardType.Password (not .Text) despite showing plain
                    // text — this recurred even with autoCorrectEnabled =
                    // false, because that flag alone doesn't reliably stop
                    // every IME's "auto-space after a period" behavior; it's
                    // a separate suggestion-engine flag several keyboards
                    // (Samsung's default included) don't honor just from
                    // autoCorrect being off. KeyboardType.Password is
                    // Android's "visible password" field type — it genuinely
                    // disables the whole prose/suggestion engine (autocorrect,
                    // auto-caps, auto-space-after-punctuation) at the platform
                    // level, the same way the Password field below already
                    // relies on for its own input, so "user.test" can never
                    // silently become "user. test" again regardless of IME.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    shape = FieldShape,
                    enabled = !uiState.isLoading,
                    leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !uiState.isLoading) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (passwordVisible) stringResource(R.string.login_hide_password) else stringResource(R.string.login_show_password),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = uiState.rememberMe, onCheckedChange = viewModel::onRememberMeChange)
                        Text(stringResource(R.string.login_remember_me), style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = onForgotPasswordClick) {
                        Text(stringResource(R.string.login_forgot_password))
                    }
                }

                if (uiState.errorMessage != null) {
                    Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = viewModel::signIn,
                    enabled = !uiState.isLoading,
                    shape = FieldShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 6.dp, shape = FieldShape),
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(stringResource(R.string.login_log_in), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                // Always shown — even right after a logout or an expired session. Whether it signs
                // anyone in depends on biometric login having been explicitly set up for GoPreach
                // on this device (see LoginViewModel.onBiometricButtonClick), not on the button's visibility.
                val biometricTitle = stringResource(R.string.login_biometric_prompt_title)
                val biometricSubtitle = stringResource(R.string.login_biometric_prompt_subtitle)
                val biometricUsePassword = stringResource(R.string.login_biometric_prompt_use_password)
                OutlinedButton(
                    onClick = {
                        if (viewModel.onBiometricButtonClick(deviceHasBiometrics(activity))) {
                            launchBiometricPrompt(
                                activity = activity,
                                title = biometricTitle,
                                subtitle = biometricSubtitle,
                                negativeButtonText = biometricUsePassword,
                                onSuccess = viewModel::onBiometricAuthSucceeded,
                                onError = viewModel::onBiometricError,
                            )
                        }
                    },
                    enabled = !uiState.isLoading,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Login with Biometrics")
                }

                // Every method is always listed; one that was never set up (or isn't available) explains
                // itself when tapped instead of signing anyone in. Each one that is set up ends in the
                // same server-verified sign-in as the password form above.
                OutlinedButton(
                    onClick = viewModel::onPasskeyClick,
                    enabled = !uiState.isLoading,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Login with Passkey")
                }
                OutlinedButton(
                    onClick = { viewModel.onQuickLoginClick(QuickLoginMethod.PIN) },
                    enabled = !uiState.isLoading,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Pin, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Login with PIN")
                }
                OutlinedButton(
                    onClick = { viewModel.onQuickLoginClick(QuickLoginMethod.PATTERN) },
                    enabled = !uiState.isLoading,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Login with Pattern")
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.login_powered_by),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
    uiState.quickLoginMethod?.let { method ->
        QuickLoginDialog(
            method = method,
            username = remember(method) { viewModel.quickLoginUsername(method) },
            error = uiState.quickLoginError,
            lockedMs = uiState.quickLoginLockedMs,
            busy = uiState.isLoading,
            onSubmit = { viewModel.onQuickLoginSecret(method, it) },
            onDismiss = viewModel::dismissQuickLogin,
        )
    }
    uiState.notice?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissNotice,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissNotice) { Text("OK") } },
        )
    }
    if (uiState.showBiometricNotSetUp) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBiometricNotSetUp,
            title = { Text("Biometric Login Not Set Up") },
            text = { Text("Please log in using your username and password first, then enable biometric login in Account/Security Settings.") },
            confirmButton = { TextButton(onClick = viewModel::dismissBiometricNotSetUp) { Text("OK") } },
        )
    }
}