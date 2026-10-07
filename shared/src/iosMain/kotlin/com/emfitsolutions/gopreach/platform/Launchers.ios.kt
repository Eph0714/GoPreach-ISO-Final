package com.emfitsolutions.gopreach.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

internal fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

/** Keeps the CLLocationManager and its delegate alive for the length of one permission request. */
private class LocationAuthDelegate(private val onDone: (Boolean) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    val manager = CLLocationManager()
    private var finished = false

    fun start() {
        manager.delegate = this
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) finish(manager.authorizationStatus)
        else manager.requestWhenInUseAuthorization()
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) finish(manager.authorizationStatus)
    }

    private fun finish(status: CLAuthorizationStatus) {
        if (finished) return
        finished = true
        val granted = status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
        onMain { onDone(granted) }
    }
}

private var activeLocationRequest: LocationAuthDelegate? = null

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPermissionRequester(onResult: (granted: Boolean) -> Unit): PermissionRequester {
    val latest = rememberUpdatedState(onResult)
    return remember {
        PermissionRequester { permission ->
            when (permission) {
                AppPermission.LOCATION -> {
                    val request = LocationAuthDelegate { granted ->
                        activeLocationRequest = null
                        latest.value(granted)
                    }
                    activeLocationRequest = request
                    request.start()
                }
                // requestAccess answers immediately with the stored decision when the user has already chosen.
                AppPermission.CAMERA ->
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted -> onMain { latest.value(granted) } }
                AppPermission.NOTIFICATIONS ->
                    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
                    ) { granted, _ -> onMain { latest.value(granted) } }
            }
        }
    }
}

private class PickerDelegate(private val onResult: (String?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        onResult(url?.absoluteString)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onResult(null)
}

private var activePickerDelegate: PickerDelegate? = null

private fun presentOpenPicker(mimeTypes: Array<String>, onResult: (String?) -> Unit) {
    val types = mimeTypes.mapNotNull { if (it == "*/*") UTTypeItem else UTType.typeWithMIMEType(it) }.ifEmpty { listOf(UTTypeItem) }
    val delegate = PickerDelegate { uri ->
        activePickerDelegate = null
        onResult(uri)
    }
    activePickerDelegate = delegate
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
    picker.delegate = delegate
    val presenter = topViewController()
    if (presenter == null) onResult(null) else presenter.presentViewController(picker, animated = true, completion = null)
}

/**
 * iOS has no "save as" dialog that runs before the file exists. The file is created in the app's temporary folder and its
 * URI is returned, so the caller writes into it exactly as on Android. Handing the finished file to the user
 * (share sheet / Files) is the job of the iOS `PlatformActions`.
 */
@Composable
actual fun rememberFileCreator(mimeType: String, onResult: (uri: String?) -> Unit): FileCreator {
    val latest = rememberUpdatedState(onResult)
    return remember {
        FileCreator { name ->
            val path = NSTemporaryDirectory() + name
            NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
            latest.value(NSURL.fileURLWithPath(path).absoluteString)
        }
    }
}

@Composable
actual fun rememberFilePicker(onResult: (uri: String?) -> Unit): FilePicker {
    val latest = rememberUpdatedState(onResult)
    return remember { FilePicker { types -> presentOpenPicker(types) { latest.value(it) } } }
}

/** Same document browser as [rememberFilePicker]; a photo-library picker (PHPicker) can replace it later. */
@Composable
actual fun rememberContentPicker(onResult: (uri: String?) -> Unit): FilePicker {
    val latest = rememberUpdatedState(onResult)
    return remember { FilePicker { types -> presentOpenPicker(types) { latest.value(it) } } }
}
