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
    /** The password was right but the account has two-factor sign-in: nothing is signed in until [challenge] is answered (see [AuthService.verifyTwoFactor]). */
    class TwoFactorRequired(val challenge: TwoFactorChallenge) : AuthFailure("Two-factor verification required")
    /** A verification code was wrong. [triesLeft] is how many more the server allows for this sign-in. */
    class InvalidCode(val triesLeft: Int?, cause: Throwable? = null) : AuthFailure("That code is not right", cause)
    /** The sign-in challenge expired or was used up: the person signs in again. */
    class ChallengeExpired(cause: Throwable? = null) : AuthFailure("Sign-in expired", cause)
    class Other(message: String?, cause: Throwable? = null) : AuthFailure(message, cause)
}

/** What the server hands a device that proved the password but still owes the second factor. [emailMasked] is set only when a code can be e-mailed. */
data class TwoFactorChallenge(val challengeId: String, val challengeToken: String, val expiresAtMillis: Long, val totp: Boolean, val recovery: Boolean, val emailMasked: String?)

/** Which kind of code answers a [TwoFactorChallenge]. */
enum class SecondFactor { AUTHENTICATOR_APP, RECOVERY_CODE, EMAIL_CODE }

/** What this device says about itself to the server (the device list shows it). [id] only tells devices apart and is never trusted by itself. */
data class DeviceInfo(val name: String, val model: String?, val type: String, val os: String?, val osVersion: String?, val appVersion: String?)

/** The other session of an account, as the server reported it: [device] and [lastActiveAtMillis] are null unless the server knows them. */
data class ActiveSessionInfo(val device: String?, val lastActiveAtMillis: Long?, val request: LoginRequestTicket? = null)

/**
 * What the server hands the NEW device when the account is signed in elsewhere: the login request it must get approved. [pollToken] is a secret only this
 * device holds. [emailMasked] is set only when a verification code can be e-mailed (the address is never shown in full).
 */
data class LoginRequestTicket(val requestId: String, val pollToken: String, val expiresAtMillis: Long, val emailMasked: String?)

enum class LoginRequestStatus { PENDING, APPROVED, DENIED, EXPIRED }

/** A sign-in attempt from another device, as the signed-in device is shown it. The IP is already shortened by the server. */
data class PendingLoginRequest(val id: String, val device: String?, val ip: String?, val createdAtMillis: Long, val expiresAtMillis: Long)

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
    suspend fun signIn(email: String, password: String, endOtherSession: Boolean = false, approval: LoginRequestTicket? = null)
    fun signOut()

    // ---- Two-factor sign-in ----
    /** Answers the challenge a password sign-in left ([AuthFailure.TwoFactorRequired]); on success the person is signed in. [trustDevice] asks the server to trust this device afterwards. */
    suspend fun verifyTwoFactor(challenge: TwoFactorChallenge, kind: SecondFactor, code: String, trustDevice: Boolean): Unit = throw AuthFailure.Other("Not available on this device.")
    /** E-mails a verification code to the confirmed recovery address; returns the masked address. */
    suspend fun sendTwoFactorEmailCode(challenge: TwoFactorChallenge): String = throw AuthFailure.Other("Not available on this device.")
    /** Keeps the credential the server issued when this device was marked trusted: it is sent with the next password sign-in so the code can be skipped. */
    fun saveTrustedDeviceToken(token: String) {}
    /** Forgets the trusted-device credential (the server already ended the trust). */
    fun clearTrustedDeviceToken() {}

    // ---- Protecting the signed-in device: the new device waits for approval, the signed-in one answers "Is this you?" ----
    suspend fun loginRequestStatus(ticket: LoginRequestTicket): LoginRequestStatus = throw AuthFailure.Other("Not available on this device.")
    /** E-mails a verification code to the registered address; returns the masked address. */
    suspend fun sendLoginEmailCode(ticket: LoginRequestTicket): String = throw AuthFailure.Other("Not available on this device.")
    suspend fun verifyLoginEmailCode(ticket: LoginRequestTicket, code: String): LoginRequestStatus = throw AuthFailure.Other("Not available on this device.")
    suspend fun pendingLoginRequests(): List<PendingLoginRequest> = emptyList()
    suspend fun decideLoginRequest(id: String, approve: Boolean): Boolean = false

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
