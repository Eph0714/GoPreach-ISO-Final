package com.emfitsolutions.gopreach.domain

import com.emfitsolutions.gopreach.platform.bestPbkdf2Algorithm
import com.emfitsolutions.gopreach.platform.constantTimeEquals
import com.emfitsolutions.gopreach.platform.pbkdf2
import com.emfitsolutions.gopreach.platform.secureRandomBytes


/**
 * Rules for the on-device PIN and Pattern logins. They are convenience unlocks for the account's
 * own password — the server still verifies every sign-in — so the rules here only decide what is an
 * acceptable secret and how failed attempts are throttled.
 */
object QuickLoginPolicy {
    const val PIN_LENGTH = 6
    const val PATTERN_MIN_NODES = 6
    const val PATTERN_NODES = 9

    /** After this many wrong tries in a row the method locks for a while, the lock doubling each time. */
    const val FAILURES_BEFORE_LOCK = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    /** After this many wrong tries in a row the method is switched off: sign in with the password and set it up again. */
    const val FAILURES_BEFORE_DISABLE = 15

    private val commonPins = setOf(
        "123456", "654321", "123123", "112233", "121212", "696969", "159753", "147258", "258369",
        "123321", "111222", "101010", "202020", "246810", "135790", "142536", "159357", "789456",
    )

    /** Null if [pin] is acceptable, otherwise why it isn't. */
    fun pinProblem(pin: String): String? {
        if (pin.length != PIN_LENGTH || !pin.all { it.isDigit() }) return "Use exactly $PIN_LENGTH digits."
        if (pin.all { it == pin[0] }) return "That PIN is too easy to guess. Avoid repeating one digit."
        val steps = pin.zipWithNext { a, b -> b - a }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return "That PIN is too easy to guess. Avoid running digits like 123456."
        if (pin in commonPins) return "That PIN is too common. Choose a less predictable one."
        if (pin.substring(0, 3) == pin.substring(3)) return "That PIN is too easy to guess. Avoid repeating a short group."
        return null
    }

    /** Null if [nodes] (0..8, row by row, in the order drawn) is acceptable, otherwise why it isn't. */
    fun patternProblem(nodes: List<Int>): String? {
        if (nodes.size < PATTERN_MIN_NODES) return "Connect at least $PATTERN_MIN_NODES dots."
        if (nodes.distinct().size != nodes.size || nodes.any { it !in 0 until PATTERN_NODES }) return "That pattern isn't valid. Try again."
        val steps = nodes.zipWithNext { a, b -> b - a }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return "That pattern is too easy to guess. Avoid drawing the dots in order."
        return null
    }

    fun patternSecret(nodes: List<Int>): String = nodes.joinToString("-")

    /** How long to lock after [failures] consecutive wrong tries (0 = not locked). */
    fun lockDurationMs(failures: Int): Long {
        if (failures < FAILURES_BEFORE_LOCK || failures % FAILURES_BEFORE_LOCK != 0) return 0L
        val doublings = (failures / FAILURES_BEFORE_LOCK) - 1
        return (BASE_LOCK_MS shl doublings.coerceAtMost(10)).coerceAtMost(MAX_LOCK_MS)
    }
}

/**
 * Salted, slow (PBKDF2) hash of a PIN/pattern — what gets stored instead of the secret. The result is
 * one string, `algorithm$salt$hash` (hex), so it still verifies after the device's Android version
 * changes which PBKDF2 variant is the best one available (SHA-256 needs Android 8; Android 7 uses SHA-1).
 */
object SecretHasher {
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256

    fun hash(secret: String): String {
        val salt = secureRandomBytes(16)
        val algorithm = bestPbkdf2Algorithm()
        return listOf(algorithm, toHex(salt), toHex(derive(secret, salt, algorithm))).joinToString("$")
    }

    fun matches(secret: String, encoded: String): Boolean {
        val parts = encoded.split("$")
        if (parts.size != 3) return false
        val actual = runCatching { derive(secret, fromHex(parts[1]), parts[0]) }.getOrNull() ?: return false
        // Constant-time comparison, so the timing never reveals how much of a guess was right.
        return constantTimeEquals(actual, fromHex(parts[2]))
    }


    private fun derive(secret: String, salt: ByteArray, algorithm: String): ByteArray =
        pbkdf2(secret, salt, ITERATIONS, KEY_BITS, algorithm)

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    private fun fromHex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
