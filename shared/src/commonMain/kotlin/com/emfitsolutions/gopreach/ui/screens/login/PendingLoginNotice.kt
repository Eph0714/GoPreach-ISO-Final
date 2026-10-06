package com.emfitsolutions.gopreach.ui.screens.login


/** A message the next Login screen shows once (e.g. "Account Setup Complete" after the first-time setup signs the user out). */
class PendingLoginNotice() {
    @Volatile private var pending: Pair<String, String>? = null

    fun post(title: String, message: String) { pending = title to message }

    /** Returns the waiting message, if any, and forgets it. */
    fun take(): Pair<String, String>? = pending.also { pending = null }
}
