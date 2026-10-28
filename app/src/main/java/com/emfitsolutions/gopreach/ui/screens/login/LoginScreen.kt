package com.emfitsolutions.gopreach.ui.screens.login

import com.emfitsolutions.gopreach.ui.components.ActionKind
import com.emfitsolutions.gopreach.ui.components.ActionButton
import com.emfitsolutions.gopreach.shared.resources.Res
import com.emfitsolutions.gopreach.shared.resources.app_name
import com.emfitsolutions.gopreach.shared.resources.login_biometric_prompt_subtitle
import com.emfitsolutions.gopreach.shared.resources.login_biometric_prompt_title
import com.emfitsolutions.gopreach.shared.resources.login_biometric_prompt_use_password
import com.emfitsolutions.gopreach.shared.resources.login_forgot_password
import com.emfitsolutions.gopreach.shared.resources.login_hide_password
import com.emfitsolutions.gopreach.shared.resources.login_log_in
import com.emfitsolutions.gopreach.shared.resources.login_password
import com.emfitsolutions.gopreach.shared.resources.login_powered_by
import com.emfitsolutions.gopreach.shared.resources.login_remember_me
import com.emfitsolutions.gopreach.shared.resources.login_show_password
import com.emfitsolutions.gopreach.shared.resources.login_tagline
import com.emfitsolutions.gopreach.shared.resources.login_username
import com.emfitsolutions.gopreach.shared.resources.login_welcome_back
import androidx.compose.foundation.layout.Arrangement
import com.emfitsolutions.gopreach.R
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Surface
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import com.emfitsolutions.gopreach.ui.components.AlertDialog
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emfitsolutions.gopreach.BuildConfig
import com.emfitsolutions.gopreach.data.repository.QuickLoginMethod
import com.emfitsolutions.gopreach.ui.components.update.UpdateViewModel

private val FieldShape = RoundedCornerShape(14.dp)

/**
 * Login entry screen. Establishes the visual pattern used everywhere: plain-text
 * fields by default, password fields masked with a show/hide toggle (spec §1).
 */
@Composable
fun LoginScreen(
    onForgotPasswordClick: () -> Unit,
    onSignedIn: (requiresPasswordChange: Boolean) -> Unit,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val activity = LocalContext.current as FragmentActivity

    // "Upon User Login... immediately after the user successfully logs in,
    // the system should check whether a newer version is available" — same
    // Activity-scoped UpdateViewModel instance MainActivity's own UpdateHost
    // renders the result of (see that screen's own comment on why), so this
    // doesn't spin up a second, unobserved one.
    val updateViewModel: UpdateViewModel = koinViewModel(viewModelStoreOwner = activity)

    LaunchedEffect(uiState.signedIn) {
        if (uiState.signedIn) {
            updateViewModel.checkOnLogin()
            onSignedIn(uiState.requiresPasswordChange == true)
            viewModel.consumeNavigationEvent()
        }
    }

    // The website's sign-in look (LoginDesign.kt): lavender sky, drifting light and layered mountains behind one rounded card. Everything the old screen did is kept: the
    // password form, Remember me, Forgot password, Biometrics, Passkey, PIN and Pattern, the busy lock on every control, and the one-session prompt below.
    // One scrollable column with the footer last (not pinned) so the keyboard can never cover the Sign In button.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pal = remember(dark) { LoginPalette(dark) }
    val fieldColors = loginFieldColors(pal)
    val busy = uiState.isLoading
    val outlineColors = ButtonDefaults.outlinedButtonColors(contentColor = pal.primary, disabledContentColor = pal.muted)
    val outlineBorder = BorderStroke(1.dp, pal.primary.copy(alpha = 0.55f))
    Box(Modifier.fillMaxSize()) {
        LoginBackdrop(pal)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp), color = pal.card, border = BorderStroke(1.dp, pal.line), shadowElevation = 14.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(painter = painterResource(R.drawable.app_logo), contentDescription = "GoPreach", modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                        Text(
                            buildAnnotatedString { withStyle(SpanStyle(color = pal.text)) { append("Go") }; withStyle(SpanStyle(color = pal.primary)) { append("Preach") } },
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.login_welcome_back), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = pal.text)
                    Text("Sign in to your GoPreach App account", style = MaterialTheme.typography.bodyLarge, color = pal.muted)
                    Spacer(Modifier.height(4.dp))

                    uiState.errorMessage?.let { message ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))) {
                            Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = viewModel::onUsernameChange,
                        placeholder = { Text(stringResource(Res.string.login_username)) },
                        singleLine = true,
                        shape = FieldShape,
                        colors = fieldColors,
                        // Every control is disabled while a sign-in runs (fields included, so the keyboard's Done cannot submit a second request).
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                        // Plain text, never masked; KeyboardType.Password only so no keyboard auto-spaces after a period ("user.test" must stay "user.test").
                        visualTransformation = VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = viewModel::onPasswordChange,
                        placeholder = { Text(stringResource(Res.string.login_password)) },
                        singleLine = true,
                        shape = FieldShape,
                        colors = fieldColors,
                        enabled = !busy,
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible) stringResource(Res.string.login_hide_password) else stringResource(Res.string.login_show_password),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = uiState.rememberMe, onCheckedChange = viewModel::onRememberMeChange, colors = CheckboxDefaults.colors(checkedColor = pal.primary), enabled = !busy)
                            Text(stringResource(Res.string.login_remember_me), style = MaterialTheme.typography.bodyMedium, color = pal.text, fontWeight = FontWeight.Medium)
                        }
                        TextButton(onClick = onForgotPasswordClick, enabled = !busy) { Text(stringResource(Res.string.login_forgot_password), color = pal.primary) }
                    }

                    Button(
                        onClick = viewModel::signIn,
                        enabled = !busy,
                        shape = FieldShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = pal.yellow, contentColor = pal.onYellow,
                            disabledContainerColor = pal.yellow.copy(alpha = 0.55f), disabledContentColor = pal.onYellow.copy(alpha = 0.7f),
                        ),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        modifier = Modifier.fillMaxWidth().shadow(elevation = 6.dp, shape = FieldShape),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = pal.onYellow)
                        Text(if (busy) "Signing in..." else stringResource(Res.string.login_log_in), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (!busy) Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    }

                    // Every other way in is listed here too; one that was never set up (or is not available) explains itself when tapped instead of signing anyone in.
                    val biometricTitle = stringResource(Res.string.login_biometric_prompt_title)
                    val biometricSubtitle = stringResource(Res.string.login_biometric_prompt_subtitle)
                    val biometricUsePassword = stringResource(Res.string.login_biometric_prompt_use_password)
                    OutlinedButton(
                        onClick = {
                            if (viewModel.onBiometricButtonClick(deviceHasBiometrics(activity))) {
                                launchBiometricPrompt(
                                    activity = activity, title = biometricTitle, subtitle = biometricSubtitle, negativeButtonText = biometricUsePassword,
                                    onSuccess = viewModel::onBiometricAuthSucceeded, onError = viewModel::onBiometricError,
                                )
                            }
                        },
                        enabled = !busy, shape = FieldShape, colors = outlineColors, border = outlineBorder, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Login with Biometrics")
                    }
                    if (com.emfitsolutions.gopreach.data.passkey.PASSKEY_LOGIN_ENABLED) OutlinedButton(
                        onClick = { viewModel.onPasskeyClick(activity) },
                        enabled = !busy, shape = FieldShape, colors = outlineColors, border = outlineBorder, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Login with Passkey")
                    }
                    OutlinedButton(
                        onClick = { viewModel.onQuickLoginClick(QuickLoginMethod.PIN) },
                        enabled = !busy, shape = FieldShape, colors = outlineColors, border = outlineBorder, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Pin, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Login with PIN")
                    }
                    OutlinedButton(
                        onClick = { viewModel.onQuickLoginClick(QuickLoginMethod.PATTERN) },
                        enabled = !busy, shape = FieldShape, colors = outlineColors, border = outlineBorder, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Login with Pattern")
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = pal.primary, modifier = Modifier.size(20.dp))
                        Text("  Your data is secure and encrypted", style = MaterialTheme.typography.bodySmall, color = pal.muted)
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.login_powered_by), style = MaterialTheme.typography.labelSmall, color = pal.muted)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = pal.muted)
                    }
                }
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
    uiState.twoFactor?.let { tf ->
        var kind by remember(tf.totp) { mutableStateOf(if (tf.totp) com.emfitsolutions.gopreach.data.repository.SecondFactor.AUTHENTICATOR_APP else com.emfitsolutions.gopreach.data.repository.SecondFactor.RECOVERY_CODE) }
        var code by remember { mutableStateOf("") }
        var trust by remember { mutableStateOf(false) }
        val methods = buildList {
            if (tf.totp) add(com.emfitsolutions.gopreach.data.repository.SecondFactor.AUTHENTICATOR_APP to "Authenticator app")
            if (tf.recovery) add(com.emfitsolutions.gopreach.data.repository.SecondFactor.RECOVERY_CODE to "Recovery code")
            if (tf.emailMasked != null) add(com.emfitsolutions.gopreach.data.repository.SecondFactor.EMAIL_CODE to "E-mail code")
        }
        AlertDialog(
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            onDismissRequest = viewModel::cancelTwoFactor,
            title = { Text("Two-step verification") },
            text = {
                Column {
                    Text("Your password is right. Enter a code to finish signing in.")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                        methods.forEach { (k, label) ->
                            androidx.compose.material3.FilterChip(selected = kind == k, onClick = { kind = k; code = "" }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !tf.busy)
                        }
                    }
                    Text(
                        when (kind) {
                            com.emfitsolutions.gopreach.data.repository.SecondFactor.AUTHENTICATOR_APP -> "Open your authenticator app and type the 6-digit code for GoPreach."
                            com.emfitsolutions.gopreach.data.repository.SecondFactor.RECOVERY_CODE -> "Type one of the recovery codes you saved (like ABCDE-FGHJK). Each works only once."
                            com.emfitsolutions.gopreach.data.repository.SecondFactor.EMAIL_CODE -> if (tf.emailSent) "Type the 6-digit code we e-mailed to ${tf.emailMasked}." else "We can e-mail a code to ${tf.emailMasked}."
                        },
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = if (kind == com.emfitsolutions.gopreach.data.repository.SecondFactor.RECOVERY_CODE) v.uppercase().take(12) else v.filter(Char::isDigit).take(6) },
                        singleLine = true, label = { Text("Code") }, enabled = !tf.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = if (kind == com.emfitsolutions.gopreach.data.repository.SecondFactor.RECOVERY_CODE) androidx.compose.ui.text.input.KeyboardType.Ascii else androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Checkbox(checked = trust, onCheckedChange = { trust = it }, enabled = !tf.busy)
                        Text("Trust this device (skips the code for a while)", style = MaterialTheme.typography.bodySmall)
                    }
                    tf.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    if (tf.busy) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                if (kind == com.emfitsolutions.gopreach.data.repository.SecondFactor.EMAIL_CODE && !tf.emailSent) {
                    TextButton(enabled = !tf.busy, onClick = viewModel::sendTwoFactorEmail) { Text("E-mail me a code") }
                } else {
                    TextButton(enabled = !tf.busy && code.isNotBlank(), onClick = { viewModel.verifyTwoFactor(kind, code, trust) }) { Text("Verify") }
                }
            },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = viewModel::cancelTwoFactor, label = "Cancel") },
        )
    }
    uiState.approval?.let { ap ->
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Waiting for approval") },
            text = {
                Column {
                    Text("This account is already signed in on another device, so GoPreach asked that device to confirm it is you.")
                    ap.device?.let { Text("Signed in on: $it", modifier = Modifier.padding(top = 8.dp)) }
                    Text(ap.message, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    if (ap.emailMasked != null) {
                        if (ap.codeSent) {
                            OutlinedTextField(
                                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                                label = { Text("6-digit code") }, enabled = !ap.busy,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                        Text(
                            "Can't reach that device? Get a code by e-mail at ${ap.emailMasked}.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    ap.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    if (ap.busy) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                if (ap.emailMasked != null) {
                    if (ap.codeSent) TextButton(enabled = !ap.busy && code.length == 6, onClick = { viewModel.verifyApprovalEmailCode(code) }) { Text("Verify Code") }
                    else TextButton(enabled = !ap.busy, onClick = viewModel::sendApprovalEmailCode) { Text("E-mail Me a Code") }
                }
            },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = viewModel::cancelApproval, label = "Cancel") },
        )
    }
    uiState.activeSession?.let { other ->
        AlertDialog(
            onDismissRequest = viewModel::cancelEndOtherSession,
            title = { Text("Account Already Active") },
            text = {
                Column {
                    Text("This account is already signed in on another device.")
                    other.device?.let { Text("Device: $it", modifier = Modifier.padding(top = 8.dp)) }
                    other.lastActiveAtMillis?.let {
                        Text("Last active: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))}")
                    }
                    Text("Do you want to end the other session and continue here? The other device will be signed out.", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmEndOtherSession(activity) }) { Text("End Other Session and Continue") } },
            dismissButton = { ActionButton(ActionKind.Cancel, onClick = viewModel::cancelEndOtherSession, label = "Cancel") },
        )
    }
    uiState.notice?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissNotice,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { ActionButton(ActionKind.Save, onClick = viewModel::dismissNotice, label = "OK") },
        )
    }
    if (uiState.showBiometricNotSetUp) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBiometricNotSetUp,
            title = { Text("Biometric Login Not Set Up") },
            text = { Text("Please log in using your username and password first, then enable biometric login in Account/Security Settings.") },
            confirmButton = { ActionButton(ActionKind.Save, onClick = viewModel::dismissBiometricNotSetUp, label = "OK") },
        )
    }
}