package com.emfitsolutions.gopreach.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.emfitsolutions.gopreach.R
import com.emfitsolutions.gopreach.data.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LogoViewModel @Inject constructor(
    appSettingsRepository: AppSettingsRepository,
) : ViewModel() {
    val logoUrl: StateFlow<String?> = appSettingsRepository.observe()
        .map { it.logoUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/**
 * The logo shown in [AppBanner] — the Super-Admin-uploaded image (spec §1, Control
 * Panel) when one exists, falling back to the bundled GoPreach mark
 * ([R.drawable.app_logo]) otherwise. Since this project's Firebase Storage bucket
 * isn't provisioned yet (see SETUP.md — needs the paid Blaze plan), the Control
 * Panel's upload feature is effectively unavailable today, so in practice this
 * bundled mark **is** the app's logo everywhere until Storage is set up. Used as
 * the `logoContent` slot on every entry/dashboard banner so a logo change anywhere
 * in the Control Panel would show up everywhere without each screen re-implementing this.
 */
@Composable
fun DynamicAppLogo(viewModel: LogoViewModel = hiltViewModel()) {
    val logoUrl by viewModel.logoUrl.collectAsStateWithLifecycle()
    if (logoUrl != null) {
        AsyncImage(
            model = logoUrl,
            contentDescription = "GoPreach logo",
            modifier = Modifier.size(64.dp).clip(CircleShape),
        )
    } else {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "GoPreach logo",
            modifier = Modifier.size(56.dp).clip(CircleShape),
        )
    }
}
