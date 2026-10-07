package com.emfitsolutions.gopreach.platform

/** Cryptographically secure random bytes (Android SecureRandom, iOS SecRandomCopyBytes). */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * PBKDF2 key derivation. [algorithm] is `PBKDF2WithHmacSHA256` or `PBKDF2WithHmacSHA1`
 * (Android 7 has no SHA-256 variant; iOS supports both).
 */
expect fun pbkdf2(secret: String, salt: ByteArray, iterations: Int, keyBits: Int, algorithm: String): ByteArray

/** The strongest PBKDF2 variant this device offers. */
expect fun bestPbkdf2Algorithm(): String

/** Constant-time comparison, so timing never reveals how much of a guess was right. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
