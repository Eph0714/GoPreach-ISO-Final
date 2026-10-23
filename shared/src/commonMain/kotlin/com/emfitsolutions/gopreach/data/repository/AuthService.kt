package com.emfitsolutions.gopreach.data.repository

import kotlinx.coroutines.flow.Flow

/** The signed-in account as the identity provider reports it (today Firebase Auth; the person id is the part of [email] before `@`). */
data class AuthUser(val uid: String, val email: String?, val lastSignInAtMillis: Long?)

/** Why a sign-in or credential operation failed, independent of the identity provider's own exception types. */
sealed class AuthFailure(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidCredentials(cause: Throwable? = null) : AuthFailure("Invalid credentials", cause)
    class InvalidUser(cause: Throwable? = null) : AuthFailure("Unknown or disabled account", cause)
    class TooManyRequests(cause: Throwable? = null) : AuthFailure("Too many attempts", cause)
    class Network(cause: Throwable? = null) : AuthFailure("No network", cause)
    /** The password was right but the server says this account already has a live session on another device. */
    class SessionActive(val session: ActiveSessionInfo, cause: Throwable? = null) : AuthFailure("Account already active", cause)
    /** The server revoked this device's session because the account signed in somewhere else. */
    class SessionEnded(cause: Throwable? = null) : AuthFailure("Session ended", cause)
    class Other(message: String?, cause: Throwable? = null) : AuthFailure(message, cause)
}

/** The other session of an account, as the server reported it: [device] and [lastActiveAtMillis] are null unless the server knows them. */
data class ActiveSessionInfo(val device: String?, val lastActiveAtMillis: Long?)

/** The server's one-time passkey challenge: [optionsJson] goes to the phone's passkey manager as-is; [sessionId] comes back with the answer. */
data class PasskeyChallenge(val sessionId: String?, val optionsJson: String)

/** A passkey saved for the signed-in account (the server holds only its public key). */
data class PasskeyInfo(val id: String, val name: String, val createdAt: Long, val lastUsedAt: Long?)

/**
 * Identity provider facade used by the shared code. Android backs it with Firebase Auth; the same interface lets the Hostinger
 * backend or another provider replace it later without touching screens or repositories. Every failing call throws [AuthFailure].
 */
interface AuthService {
    /** The signed-in account right now, or null. */
    val currentUser: AuthUser?

    /** Emits the signed-in account (or null) now and on every change. */
    fun authState(): Flow<AuthUser?>

    /** [endOtherSession] true takes over an account that is signed in elsewhere (the server revokes the other session first); otherwise [AuthFailure.SessionActive] is thrown. */
    suspend fun signIn(email: String, password: String, endOtherSession: Boolean = false)
    fun signOut()

    /** True once if this device was signed out because the account signed in on another device (the sign-in screen tells the person). */
    fun takeSessionEndedNotice(): Boolean = false

    /** A fresh ID token for the backend (forces a refresh when [force]); null when nobody is signed in. */
    suspend fun idToken(force: Boolean): String?

    /** Re-checks the current account's password (required before sensitive changes). */
    suspend fun reauthenticate(password: String)
    suspend fun updatePassword(newPassword: String)

    /** Creates an account for someone else without signing the current admin out. */
    suspend fun createAccount(email: String, password: String)

    /** Sets a temporary password for another person's account. The server allows it only to whoever may manage that person's credentials. */
    suspend fun adminResetPassword(personId: String, newPassword: String): Unit = throw AuthFailure.Other("Resetting another person's password is not available on this device.")

    // ---- Passkeys (WebAuthn). Only the HTTP service implements these; the phone's passkey manager does the signing. ----

    /** Asks the server for a sign-in challenge (no login needed). */
    suspend fun passkeyLoginOptions(): PasskeyChallenge = throw AuthFailure.Other("Passkeys aren't available on this device.")

    /** Sends the phone's signed answer. On success the person is signed in (tokens stored); returns their username. */
    suspend fun passkeyLogin(sessionId: String, responseJson: String, endOtherSession: Boolean = false): String? = throw AuthFailure.Other("Passkeys aren't available on this device.")

    /** The options for creating a passkey for the signed-in account. */
    suspend fun passkeyRegisterOptions(): String = throw AuthFailure.Other("Passkeys aren't available on this device.")
    suspend fun passkeyRegister(responseJson: String, name: String) { throw AuthFailure.Other("Passkeys aren't available on this device.") }
    suspend fun passkeyList(): List<PasskeyInfo> = throw AuthFailure.Other("Passkeys aren't available on this device.")
    suspend fun passkeyDelete(id: String): Boolean = throw AuthFailure.Other("Passkeys aren't available on this device.")
}
