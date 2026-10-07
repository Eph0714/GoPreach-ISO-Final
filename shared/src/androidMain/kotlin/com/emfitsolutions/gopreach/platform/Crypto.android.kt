package com.emfitsolutions.gopreach.platform

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val random = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

actual fun pbkdf2(secret: String, salt: ByteArray, iterations: Int, keyBits: Int, algorithm: String): ByteArray =
    SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec(secret.toCharArray(), salt, iterations, keyBits)).encoded

actual fun bestPbkdf2Algorithm(): String = runCatching {
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    "PBKDF2WithHmacSHA256"
}.getOrDefault("PBKDF2WithHmacSHA1")
