package com.emfitsolutions.gopreach.platform

/** Minimal logging for shared code (Android logcat; iOS console). Never log passwords, tokens or personal data. */
expect object Log {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
