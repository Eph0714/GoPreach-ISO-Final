package com.emfitsolutions.gopreach.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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

    override suspend fun <T : Any> pullOnce(collectionPath: String, kClass: KClass<T>, equalTo: Pair<String, String>?, idOf: (T) -> String) =
        pullFirestoreCollectionOnce(firestore, offline, collectionPath, kClass.java, query(collectionPath, equalTo), idOf)
}
