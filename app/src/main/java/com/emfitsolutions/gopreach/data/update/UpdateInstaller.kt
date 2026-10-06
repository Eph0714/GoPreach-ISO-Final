package com.emfitsolutions.gopreach.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the downloaded APK to Android's own Package Installer — this app
 * never installs anything itself. The system installer is what actually
 * enforces the security guarantee that matters most here: an update APK
 * signed with a *different* certificate than the currently-installed app is
 * rejected automatically, before the user even sees an "install" prompt.
 * Nothing in this class can override that; it isn't trying to.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Android 8+ gates "install from this app" per-app rather than with a
     * single global "unknown sources" toggle. Below API 26 there's no such
     * per-app check — the OS's own install confirmation is all that's needed. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the OS setting that grants "install unknown apps" for
     * this app specifically — not a workaround, this *is* the official flow
     * Android provides for exactly this situation. */
    fun requestInstallPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Launches the system Package Installer UI for [apkFile]. Returns the
     * Intent so the caller decides how to start it (an Activity Context is
     * required for ACTION_VIEW from outside an Activity). */
    fun installIntent(apkFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
