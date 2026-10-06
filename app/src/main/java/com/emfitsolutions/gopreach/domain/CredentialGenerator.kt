package com.emfitsolutions.gopreach.domain

import kotlin.random.Random

/**
 * One-time temp username/password generation for enrollment (spec §4.2-4.4).
 * Credentials are single-use: [AuthRepository.forcedPasswordChange] flips
 * `isTemporaryCredential` off the moment the enrollee sets their own.
 */
object CredentialGenerator {
    private const val PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /**
     * Generates a new Person id — lowercase-only, unlike Firestore's own
     * auto-id (which mixes case). This matters because [AuthEmail.kt]'s
     * `authEmailFor` embeds the id in a Firebase Auth email, and Firebase
     * Auth silently lowercases every email address; a mixed-case id would
     * come back lowercased from `personIdFromAuthEmail` after sign-in and
     * never match the original Firestore document, breaking login for that
     * account. 20 lowercase-alphanumeric characters keeps collision odds
     * effectively the same as Firestore's own auto-ids.
     */
    fun newPersonId(length: Int = 20): String =
        (1..length).map { ID_CHARS[Random.nextInt(ID_CHARS.length)] }.joinToString("")

    /** e.g. "juan.delacruz" from "Juan" / "Dela Cruz", with a numeric suffix appended
     * by the caller only if that base is already taken. */
    fun baseUsername(firstName: String, lastName: String): String {
        val normalized = { s: String -> s.trim().lowercase().replace(Regex("[^a-z]"), "") }
        val first = normalized(firstName).ifBlank { "user" }
        val last = normalized(lastName)
        return if (last.isBlank()) first else "$first.$last"
    }

    fun temporaryPassword(length: Int = 10): String =
        (1..length).map { PASSWORD_CHARS[Random.nextInt(PASSWORD_CHARS.length)] }.joinToString("")

    /**
     * A tap-to-open link the Super-Admin/Admin/Coordinator Elder can send the new
     * enrollee (spec §4.2-4.4). Embedding the temp password directly in the link is
     * a deliberate simplification for a first cut — it is single-use and short-lived
     * (invalidated the moment the enrollee sets their own credentials), but a
     * production hardening pass should swap this for a short-lived opaque token
     * that the app exchanges for credentials server-side instead.
     */
    fun shareableSetupLink(username: String, temporaryPassword: String): String =
        "gopreach://firstlogin?u=$username&p=$temporaryPassword"
}
