package com.emfitsolutions.gopreach.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA1
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
actual fun pbkdf2(secret: String, salt: ByteArray, iterations: Int, keyBits: Int, algorithm: String): ByteArray {
    val password = secret.encodeToByteArray()
    val key = ByteArray(keyBits / 8)
    val prf = if (algorithm == "PBKDF2WithHmacSHA1") kCCPRFHmacAlgSHA1 else kCCPRFHmacAlgSHA256
    // addressOf(0) needs a non-empty array, so pin one spare byte for an empty salt. The password goes in as a String (UTF-8 C string).
    val sl = if (salt.isEmpty()) ByteArray(1) else salt
    sl.usePinned { s ->
            key.usePinned { k ->
                CCKeyDerivationPBKDF(
                    kCCPBKDF2,
                    secret,
                    password.size.convert(),
                    s.addressOf(0).reinterpret(),
                    salt.size.convert(),
                    prf,
                    iterations.convert(),
                    k.addressOf(0).reinterpret(),
                    key.size.convert(),
                )
            }
    }
    return key
}

actual fun bestPbkdf2Algorithm(): String = "PBKDF2WithHmacSHA256"
