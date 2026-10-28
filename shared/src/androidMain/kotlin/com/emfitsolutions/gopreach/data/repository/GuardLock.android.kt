package com.emfitsolutions.gopreach.data.repository

internal actual class GuardLock actual constructor() {
    private val lock = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}
