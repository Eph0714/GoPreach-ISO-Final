package com.emfitsolutions.gopreach.data.sync

import android.net.Uri
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

class AndroidNetworkStatus(
    private val syncStatusCenter: SyncStatusCenter,
    private val connectivityObserver: ConnectivityObserver,
) : NetworkStatus {
    override val isSyncing: Flow<Boolean> get() = syncStatusCenter.isSyncing
    override fun isOnline(): Boolean = connectivityObserver.isOnline()
}

class FirebaseRemoteFiles(private val storage: FirebaseStorage) : RemoteFiles {
    override suspend fun upload(path: String, localUri: String): String {
        val ref = storage.reference.child(path)
        ref.putFile(Uri.parse(localUri)).await()
        return ref.downloadUrl.await().toString()
    }

    override suspend fun delete(path: String) {
        runCatching { storage.reference.child(path).delete().await() }
    }
}
