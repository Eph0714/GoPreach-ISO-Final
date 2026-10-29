package com.emfitsolutions.gopreach.data.remote

/** Binary file storage (profile photos, logos, attachments). [localUri] is a platform URI string (content://, file://). */
interface RemoteFiles {
    /** Uploads the file at [localUri] to [path] (overwriting) and returns its public download URL. [onProgress] reports the bytes sent so far and the total. */
    suspend fun upload(path: String, localUri: String, onProgress: ((sent: Long, total: Long) -> Unit)? = null): String
    /** Best-effort delete; a missing file is not an error. */
    suspend fun delete(path: String)
}
