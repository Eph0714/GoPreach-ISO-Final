package com.emfitsolutions.gopreach.platform

import androidx.compose.runtime.Composable

/** Permissions a screen can ask for, independent of the platform's own permission names. */
enum class AppPermission {
    /** Precise (and, where the platform has it, approximate) location while the app is in use. */
    LOCATION,
    CAMERA,
    NOTIFICATIONS,
}

/** Asks the user for a permission; the result arrives in the callback given to [rememberPermissionRequester]. */
class PermissionRequester(private val request: (AppPermission) -> Unit) {
    fun launch(permission: AppPermission) = request(permission)
}

/** Opens the system "save as" picker; the chosen file's URI string (or null if cancelled) goes to the callback. */
class FileCreator(private val create: (suggestedName: String) -> Unit) {
    fun launch(suggestedName: String) = create(suggestedName)
}

/** Opens the system file/photo picker; the chosen file's URI string (or null if cancelled) goes to the callback. */
class FilePicker(private val pick: (mimeTypes: Array<String>) -> Unit) {
    fun launch(mimeType: String) = pick(arrayOf(mimeType))
    fun launch(mimeTypes: Array<String>) = pick(mimeTypes)
    fun launch(mimeTypes: List<String>) = pick(mimeTypes.toTypedArray())
}

/** [onResult] receives true when the permission was granted (for [AppPermission.LOCATION], when any location permission was). */
@Composable
expect fun rememberPermissionRequester(onResult: (granted: Boolean) -> Unit): PermissionRequester

@Composable
expect fun rememberFileCreator(mimeType: String, onResult: (uri: String?) -> Unit): FileCreator

@Composable
expect fun rememberFilePicker(onResult: (uri: String?) -> Unit): FilePicker

/** The platform's gallery-style "pick a photo/file by type" picker (Android GetContent), as opposed to the document browser above. */
@Composable
expect fun rememberContentPicker(onResult: (uri: String?) -> Unit): FilePicker
