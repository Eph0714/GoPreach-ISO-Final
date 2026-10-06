package com.emfitsolutions.gopreach.platform

actual object Log {
    actual fun d(tag: String, message: String) {
        println("D/$tag: $message")
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        println("W/$tag: $message ${error?.message.orEmpty()}")
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        println("E/$tag: $message ${error?.message.orEmpty()}")
    }
}
