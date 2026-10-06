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
    class Other(message: String?, cause: Throwable? = null) : AuthFailure(message, cause)
}

/**
 * Identity provider facade used by the shared code. Android backs it with Firebase Auth; the same interface lets the Hostinger
 * backend or another provider replace it later without touching screens or repositories. Every failing call throws [AuthFailure].
 */
interface AuthService {
    /** The signed-in account right now, or null. */
    val currentUser: AuthUser?

    /** Emits the signed-in account (or null) now and on every change. */
    fun authState(): Flow<AuthUser?>

    suspend fun signIn(email: String, password: String)
    fun signOut()

    /** A fresh ID token for the backend (forces a refresh when [force]); null when nobody is signed in. */
    suspend fun idToken(force: Boolean): String?

    /** Re-checks the current account's password (required before sensitive changes). */
    suspend fun reauthenticate(password: String)
    suspend fun updatePassword(newPassword: String)

    /** Creates an account for someone else without signing the current admin out. */
    suspend fun createAccount(email: String, password: String)
}
