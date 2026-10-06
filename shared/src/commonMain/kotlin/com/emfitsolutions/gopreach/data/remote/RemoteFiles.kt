package com.emfitsolutions.gopreach.data.remote

/** Binary file storage (profile photos, logos, attachments). [localUri] is a platform URI string (content://, file://). */
interface RemoteFiles {
    /** Uploads the file at [localUri] to [path] (overwriting) and returns its public download URL. */
    suspend fun upload(path: String, localUri: String): String
    /** Best-effort delete; a missing file is not an error. */
    suspend fun delete(path: String)
}
