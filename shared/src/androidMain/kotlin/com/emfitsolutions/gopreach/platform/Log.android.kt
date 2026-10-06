package com.emfitsolutions.gopreach.platform

actual object Log {
    actual fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        android.util.Log.w(tag, message, error)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        android.util.Log.e(tag, message, error)
    }
}
