package com.emfitsolutions.gopreach.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Android-only: tells [SyncStatusCenter]/[SyncScheduler] about a queued write (the shared store can't know about them). */
class AndroidWriteQueuedListener(
    private val syncScheduler: SyncScheduler,
    private val syncStatusCenter: SyncStatusCenter,
    private val connectivityObserver: ConnectivityObserver,
) : WriteQueuedListener {
    override fun onWriteQueued() {
        syncStatusCenter.onWriteQueued(connectivityObserver.isOnline())
        syncScheduler.triggerSyncIfOnline()
    }
}

/**
 * Same local-cache write as `save`, but also pushes the document straight to Firestore immediately and marks it synced —
 * for the rare write that can't wait for the next sync (a freshly enrolled account must exist on the server before its
 * owner's first sign-in). Cache-first: if the immediate push throws, the document is still safely queued.
 * Only safe when the caller already knows the device is online.
 */
suspend inline fun <reified T : Any> OfflineFirestoreRepository.saveNow(firestore: FirebaseFirestore, collectionPath: String, documentId: String, data: T) {
    save(collectionPath, documentId, data)
    runCatching {
        firestore.collection(collectionPath).document(documentId).set(data).await()
        markSynced(collectionPath, documentId)
    }
}
