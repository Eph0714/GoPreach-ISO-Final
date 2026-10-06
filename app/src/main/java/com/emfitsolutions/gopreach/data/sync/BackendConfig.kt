package com.emfitsolutions.gopreach.data.sync

import com.emfitsolutions.gopreach.BuildConfig

/**
 * Which server the app syncs with. Empty `backendUrl` (the default) = Firestore, exactly as before.
 * Set `gopreach.backendUrl=https://your-api-host` in local.properties to sync through the Hostinger API instead.
 */
object BackendConfig {
    val baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')
    val enabled: Boolean get() = baseUrl.isNotBlank()

    /** Same list as SyncWorker's Firestore path: models whose `@DocumentId` property is not named `id`. */
    val idFieldByCollection = mapOf(
        "sharedLocations" to "publisherPersonId",
        "locationSharingSettings" to "congregationId",
        "dashboardModuleLayouts" to "personId",
    )
}
