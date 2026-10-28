package com.emfitsolutions.gopreach.data.repository

import platform.Foundation.NSLock

internal actual class GuardLock actual constructor() {
    private val lock = NSLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
