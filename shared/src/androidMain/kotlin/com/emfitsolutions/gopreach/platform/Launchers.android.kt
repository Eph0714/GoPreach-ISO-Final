package com.emfitsolutions.gopreach.platform

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberPermissionRequester(onResult: (granted: Boolean) -> Unit): PermissionRequester {
    val latest = rememberUpdatedState(onResult)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest.value(it) }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        latest.value(results.values.any { it })
    }
    return remember(single, multiple) {
        PermissionRequester { permission ->
            when (permission) {
                AppPermission.LOCATION -> multiple.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                AppPermission.CAMERA -> single.launch(Manifest.permission.CAMERA)
                AppPermission.NOTIFICATIONS -> single.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
actual fun rememberFileCreator(mimeType: String, onResult: (uri: String?) -> Unit): FileCreator {
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { latest.value(it?.toString()) }
    return remember(launcher) { FileCreator { name -> launcher.launch(name) } }
}

@Composable
actual fun rememberFilePicker(onResult: (uri: String?) -> Unit): FilePicker {
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { latest.value(it?.toString()) }
    return remember(launcher) { FilePicker { types -> launcher.launch(types) } }
}

@Composable
actual fun rememberContentPicker(onResult: (uri: String?) -> Unit): FilePicker {
    val latest = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { latest.value(it?.toString()) }
    return remember(launcher) { FilePicker { types -> launcher.launch(types.firstOrNull() ?: "*/*") } }
}
