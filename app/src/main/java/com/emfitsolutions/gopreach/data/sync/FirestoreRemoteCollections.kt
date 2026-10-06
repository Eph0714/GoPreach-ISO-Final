package com.emfitsolutions.gopreach.data.sync

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlin.reflect.KClass

/** Android/Firestore implementation of [RemoteCollections]. */
class FirestoreRemoteCollections(
    private val firestore: FirebaseFirestore,
    private val offline: OfflineFirestoreRepository,
    private val appScope: CoroutineScope,
) : RemoteCollections {
    override fun newId(collectionPath: String): String = firestore.collection(collectionPath).document().id

    private fun query(path: String, equalTo: Pair<String, String>?): Query {
        val base = firestore.collection(path)
        return if (equalTo == null) base else base.whereEqualTo(equalTo.first, equalTo.second)
    }

    override fun <T : Any> mirror(collectionPath: String, kClass: KClass<T>, equalTo: Pair<String, String>?, idOf: (T) -> String): Flow<Unit> =
        mirrorFirestoreCollection(firestore, offline, appScope, collectionPath, kClass.java, query(collectionPath, equalTo), idOf)

    override suspend fun pushNow(collectionPath: String, documentId: String, data: Any) {
        firestore.collection(collectionPath).document(documentId).set(data).await()
    }

    override suspend fun deleteNow(collectionPath: String, documentId: String) {
        firestore.collection(collectionPath).document(documentId).delete().await()
    }

    override suspend fun hasAny(collectionPath: String): Boolean =
        !firestore.collection(collectionPath).limit(1).get(Source.SERVER).await().isEmpty

    override suspend fun countWhere(collectionPath: String, field: String, value: String, limit: Int): Int =
        firestore.collection(collectionPath).whereEqualTo(field, value).limit(limit.toLong()).get(Source.SERVER).await().size()

    override fun <T : Any> mirrorGroup(groupId: String, kClass: KClass<T>, equalTo: Pair<String, String>?, pathOf: (T) -> String, idOf: (T) -> String): Flow<Unit> = callbackFlow {
        val base = firestore.collectionGroup(groupId)
        val query = if (equalTo == null) base else base.whereEqualTo(equalTo.first, equalTo.second)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                if (error != null) Log.w("RemoteCollections", "Collection-group listener for '$groupId' failed: ${error.message}")
                return@addSnapshotListener
            }
            appScope.launch {
                for (change in snapshot.documentChanges) {
                    try {
                        val model = change.document.toObject(kClass.java)
                        val path = pathOf(model)
                        when (change.type) {
                            DocumentChange.Type.REMOVED -> offline.deleteFromServer(path, idOf(model))
                            else -> offline.cacheFromServer(kotlinx.serialization.serializer(kClass.java) as kotlinx.serialization.KSerializer<T>, path, idOf(model), model)
                        }
                    } catch (e: Exception) {
                        Log.e("RemoteCollections", "Skipping malformed $groupId document ${change.document.id}", e)
                    }
                }
            }
            trySend(Unit)
        }
        awaitClose { registration.remove() }
    }

    override suspend fun <T : Any> pullOnce(collectionPath: String, kClass: KClass<T>, equalTo: Pair<String, String>?, idOf: (T) -> String) =
        pullFirestoreCollectionOnce(firestore, offline, collectionPath, kClass.java, query(collectionPath, equalTo), idOf)
}
