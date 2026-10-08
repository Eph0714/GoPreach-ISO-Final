package com.emfitsolutions.gopreach.data.sync

/** A message a person can act on for a failed server write; the raw Firestore text is kept for anything unrecognised. */
fun friendlyServerError(e: Throwable): String {
    val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return when {
        "RESOURCE_EXHAUSTED" in text || "Quota exceeded" in text ->
            "The server's daily usage limit has been reached, so this could not be sent right now. Nothing was lost: try again later today " +
                "(the limit resets daily) or ask the administrator to raise the Firebase plan."
        "PERMISSION_DENIED" in text -> "You are not allowed to do that with your current role."
        "UNAVAILABLE" in text || "DEADLINE_EXCEEDED" in text -> "The server could not be reached. Check the connection and try again."
        else -> e.message ?: "Couldn't save. Please try again."
    }
}
