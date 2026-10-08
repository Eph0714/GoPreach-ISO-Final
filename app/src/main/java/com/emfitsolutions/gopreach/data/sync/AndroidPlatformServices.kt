package com.emfitsolutions.gopreach.data.sync

import android.net.Uri
import com.emfitsolutions.gopreach.data.remote.RemoteFiles
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await


/** [RemoteFiles] for the Hostinger backend: the file's bytes are read from the phone and sent to the server's file store. */
class BackendRemoteFiles(
    private val context: android.content.Context,
    private val api: com.emfitsolutions.gopreach.data.remote.SyncApi,
) : RemoteFiles {
    override suspend fun upload(path: String, localUri: String): String {
        val uri = Uri.parse(localUri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read the selected file.")
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return api.uploadFile(path, bytes, mime)
    }

    override suspend fun delete(path: String) {
        runCatching { api.deleteFile(path) }
    }
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
