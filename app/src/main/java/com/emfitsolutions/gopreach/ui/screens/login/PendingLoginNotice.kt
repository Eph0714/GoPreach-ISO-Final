package com.emfitsolutions.gopreach.ui.screens.login

import javax.inject.Inject
import javax.inject.Singleton

/** A message the next Login screen shows once (e.g. "Account Setup Complete" after the first-time setup signs the user out). */
@Singleton
class PendingLoginNotice @Inject constructor() {
    @Volatile private var pending: Pair<String, String>? = null

    fun post(title: String, message: String) { pending = title to message }

    /** Returns the waiting message, if any, and forgets it. */
    fun take(): Pair<String, String>? = pending.also { pending = null }
}
