package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import android.util.Log
import com.emfitsolutions.gopreach.data.model.Person
import com.emfitsolutions.gopreach.data.model.PasswordResetRequest
import com.emfitsolutions.gopreach.data.model.RoleAssignment
import com.emfitsolutions.gopreach.data.model.RoleType
import com.emfitsolutions.gopreach.domain.CredentialGenerator
import com.emfitsolutions.gopreach.domain.PermissionChecker
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** "Fix the Login API and Database Errors... Display user-friendly messages...
 * Do not expose database errors directly to users" — every real failure mode
 * [AuthRepository.signIn] can hit, mapped to the exact wording the audit
 * spec's own examples use, instead of surfacing a raw Firebase exception
 * message (which can be a technical string, or occasionally null) straight to
 * the Login screen. Centralized here so both [AuthRepository.signIn] and
 * [AuthRepository.createSecondaryAuthAccount]'s callers could reuse the same
 * mapping if a future auth entry point needs it. */
private const val SIGN_IN_TIMEOUT_MS = 15_000L
private fun friendlyAuthErrorMessage(e: Throwable): String = when (e) {
    is TimeoutCancellationException -> "Connection timeout. Please check your internet connection and try again."
    is FirebaseNetworkException -> "No internet connection. Please check your connection and try again."
    is FirebaseTooManyRequestsException -> "Too many attempts. Please wait a moment and try again."
    is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> "Invalid username or password."
    else -> "Server temporarily unavailable. Please try again later."
}

sealed class AuthResult {
    data class Success(val person: Person, val requiresPasswordChange: Boolean) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

data class TempCredentials(
    val username: String,
    val temporaryPassword: String,
    val shareableLink: String,
    val personId: String = "",
)

/**
 * Firebase Auth wiring for spec §4.5 (first-login/temp-credential flow) and §5.1/5.2
 * (username+password login, forgot password). See [authEmailFor] for how a
 * username-based login maps onto Firebase's email-based accounts.
 */
class AuthRepository(
    private val appContext: Context,
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val personRepository: PersonRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val offlineAuthStore: OfflineAuthStore,
    private val offlineSessionMarker: OfflineSessionMarker,
) {
    private companion object {
        /** "ADD LOGIN DEBUGGING... DO NOT LOG: Passwords, Tokens, Password
         * Hashes, Secrets" — every log line below reports only
         * booleans/counts, never a username, password, Firebase ID token,
         * or [Person.username]. */
        const val TAG = "AuthDebug"
    }

    val currentPersonId: String?
        get() = personIdFromAuthEmail(firebaseAuth.currentUser?.email) ?: offlineSessionMarker.personId.value

    suspend fun findPersonByUsername(username: String): Person? =
        firestore.collection("people")
            .whereEqualTo("username", username)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.toObject(Person::class.java)

    suspend fun signIn(username: String, password: String): AuthResult {
        Log.d(TAG, "Login request started")
        return try {
            withTimeout(SIGN_IN_TIMEOUT_MS) { signInInternal(username, password) }
        } catch (e: Exception) {
            // Bug fix — "the application must never remain stuck": this used to
            // only wrap the Firebase Auth call itself in try/catch. A timeout,
            // or any exception from findPersonByUsername/roleAssignmentRepository
            // (both plain Firestore/Room reads with their own failure modes —
            // a dropped connection mid-query, a security-rules rejection, ...)
            // used to propagate straight out of this function uncaught, which
            // left [com.emfitsolutions.gopreach.ui.screens.login.LoginViewModel]
            // with an exception thrown out of its own coroutine — the "infinite
            // loading" bug the login audit spec calls out by name, since nothing
            // downstream of a truly *uncaught* exception ever gets to flip
            // isLoading back to false. Every path through this function now
            // always returns a normal [AuthResult] instead.
            Log.e(TAG, "Login request failed: ${e::class.simpleName}")
            AuthResult.Error(friendlyAuthErrorMessage(e))
        }
    }

    private suspend fun signInInternal(username: String, password: String): AuthResult {
        val person = findPersonByUsername(username)
        Log.d(TAG, "User found: ${person != null}")
        if (person == null) return AuthResult.Error("Invalid username or password.")
        // Spec §9: a deactivated/suspended account must never sign in again,
        // regardless of what its RoleAssignments say — checked *before* touching
        // Firebase Auth so a disabled account doesn't even get to try.
        // "CREATING PUBLISHER" spec: a Removed Publisher (with no other Admin
        // role) is blocked the same way — see PermissionChecker's two-arg
        // isAccountUsable overload.
        val roleAssignments = roleAssignmentRepository.observeForPerson(person.id).first()
        Log.d(TAG, "Roles found: ${roleAssignments.size}")
        if (!PermissionChecker.isAccountUsable(person, roleAssignments)) {
            Log.d(TAG, "Account usable: FALSE")
            return AuthResult.Error("This account has been deactivated. Contact your administrator.")
        }
        firebaseAuth.signInWithEmailAndPassword(authEmailFor(person.id), password).await()
        Log.d(TAG, "Password verified: TRUE")
        // Cache-only: a stale pre-sign-in snapshot must never get queued as a
        // pending upload here (see PersonRepository.cacheFromServer's doc
        // comment) — it would race, and could clobber, UserSession
        // .syncActiveRoleContext's own write of activeCongregationId/
        // activeAdminRole for this same session.
        personRepository.cacheFromServer(person)
        // "Offline Login" spec §1-§2: securely cache a hashed verifier for this
        // exact username/password (never the password itself) so a later
        // sign-in attempt with no network can still be verified — see
        // [offlineSignIn]. Independent of the "Remember me" checkbox, which is
        // a separate, opt-in biometric-unlock convenience.
        //
        // Deliberately non-fatal: this is a side effect of an already-
        // successful Firebase sign-in, backed by the same
        // EncryptedSharedPreferences mechanism that can throw on a Keystore
        // failure (see LoginViewModel's own doc comment on this) — a glitch
        // here must never turn a real, successful login into a reported
        // failure, it just means offline sign-in won't be available later.
        runCatching { offlineAuthStore.saveVerifier(username, password, person.id) }
            .onFailure { Log.e(TAG, "Failed to save offline verifier: ${it::class.simpleName}") }
        offlineSessionMarker.save(person.id)
        auditLogRepository.log(actorPersonId = person.id, action = "SIGN_IN")
        Log.d(TAG, "Navigation result: SUCCESS")
        return AuthResult.Success(person, requiresPasswordChange = person.isTemporaryCredential)
    }

    /** "Offline Login" spec §1 — used instead of [signIn] whenever the device has
     * no network connection. Verifies the entered password against the hashed
     * verifier saved by the most recent successful *online* [signIn] on this
     * device (never touches the network, never compares a stored plaintext
     * password), then grants access using whatever Person/RoleAssignment data is
     * already cached locally from that prior session (spec §1: "the user's last
     * synchronized permissions and scope") — see [OfflineSessionMarker] for how
     * this makes [UserSession] treat the app as signed in without
     * `FirebaseAuth.currentUser`, which Firebase's SDK has no offline path to
     * populate. */
    suspend fun offlineSignIn(username: String, password: String): AuthResult {
        return try {
            Log.d(TAG, "Login request started (offline)")
            val personId = offlineAuthStore.verify(username, password)
            Log.d(TAG, "Password verified: ${personId != null}")
            if (personId == null) {
                // Bug fix — distinguish "you've never signed in successfully
                // on this exact device before" (there's nothing to verify
                // against at all) from "wrong password for a device that
                // does have a cached sign-in" — these used to both show the
                // same "Invalid username or password.", which reads as a
                // typo when the real problem is "you need one online
                // sign-in first."
                return if (offlineAuthStore.hasSavedVerifierFor(username)) {
                    AuthResult.Error("Invalid username or password.")
                } else {
                    AuthResult.Error("No internet connection, and no offline sign-in is available yet for this account on this device. Connect to the internet at least once, then try again.")
                }
            }
            val person = personRepository.get(personId)
                ?: return AuthResult.Error("No local data available for this account yet. Connect to the internet at least once, then try again.")
            val roleAssignments = roleAssignmentRepository.observeForPerson(personId).first()
            Log.d(TAG, "Roles found: ${roleAssignments.size}")
            if (!PermissionChecker.isAccountUsable(person, roleAssignments)) {
                return AuthResult.Error("This account has been deactivated. Contact your administrator.")
            }
            offlineSessionMarker.save(personId)
            Log.d(TAG, "Navigation result: SUCCESS (offline)")
            AuthResult.Success(person, requiresPasswordChange = person.isTemporaryCredential)
        } catch (e: Exception) {
            // Same "must never remain stuck" guarantee as signIn() —
            // offlineSignIn touches no network, but Room/
            // EncryptedSharedPreferences reads can still fail (e.g. a
            // Keystore error on a very old/misbehaving device).
            Log.e(TAG, "Offline login request failed: ${e::class.simpleName}")
            AuthResult.Error("Couldn't sign in. Please try again.")
        }
    }

    fun signOut() {
        firebaseAuth.signOut()
        offlineSessionMarker.clear()
    }

    /**
     * Creates a Person + RoleAssignment with single-use temp credentials (spec
     * §4.2-4.4). Uses a throwaway secondary [FirebaseApp] instance to create the new
     * Auth account so it never disturbs [enrollingPersonId]'s active session — the
     * client SDK otherwise signs you in as whichever user you just created.
     */
    suspend fun createAccountWithTempCredentials(
        person: Person,
        roleAssignment: (personId: String) -> RoleAssignment,
        enrollingPersonId: String,
    ): TempCredentials {
        val personId = CredentialGenerator.newPersonId()
        val username = uniqueUsername(CredentialGenerator.baseUsername(person.firstName, person.lastName))
        val tempPassword = CredentialGenerator.temporaryPassword()

        createSecondaryAuthAccount(personId, tempPassword)

        val finalPerson = person.copy(
            id = personId,
            username = username,
            isTemporaryCredential = true,
            temporaryPassword = tempPassword,
            createdAt = System.currentTimeMillis(),
            createdByPersonId = enrollingPersonId,
        )
        // saveNow, not save: this document must exist on the server the
        // instant this function returns, not whenever someone next taps
        // "Sync to Server" — the very first thing a freshly enrolled user
        // does is sign in, almost always on a different device than the one
        // that enrolled them, and sign-in looks this up straight from
        // Firestore (see findPersonByUsername). Safe to do synchronously
        // here specifically because createSecondaryAuthAccount above already
        // proved this device is online right now. See
        // OfflineFirestoreRepository.saveNow's doc comment for the full story
        // of the login failure this fixes.
        personRepository.saveNow(finalPerson)
        val assignment = roleAssignment(personId)
        roleAssignmentRepository.saveNow(assignment)
        val actionRole = when (val type = assignment.resolvedRoleType()) {
            is RoleType.Admin -> type.role.name
            is RoleType.Publisher -> "PUBLISHER"
        }
        auditLogRepository.log(
            actorPersonId = enrollingPersonId,
            action = "ENROLL_$actionRole",
            targetType = "Person",
            targetId = personId,
            congregationId = assignment.congregationId,
        )

        return TempCredentials(
            username = username,
            temporaryPassword = tempPassword,
            shareableLink = CredentialGenerator.shareableSetupLink(username, tempPassword),
            personId = personId,
        )
    }

    private suspend fun createSecondaryAuthAccount(personId: String, tempPassword: String) {
        val appName = "temp-account-$personId"
        val secondaryApp = FirebaseApp.initializeApp(appContext, FirebaseApp.getInstance().options, appName)
        try {
            withTimeout(SIGN_IN_TIMEOUT_MS) {
                val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)
                secondaryAuth.createUserWithEmailAndPassword(authEmailFor(personId), tempPassword).await()
                secondaryAuth.signOut()
            }
        } finally {
            secondaryApp.delete()
        }
    }

    private suspend fun uniqueUsername(base: String): String {
        if (findPersonByUsername(base) == null) return base
        var suffix = 1
        while (findPersonByUsername("$base$suffix") != null) suffix++
        return "$base$suffix"
    }

    /** Spec §4.5 step 2-3: mandatory username+password change on first login, then
     * re-login with the new credentials — this only updates Auth + the Person
     * record and signs the user out; the caller navigates back to Login. */
    suspend fun forcedPasswordChange(newUsername: String, newPassword: String): AuthResult {
        val user = firebaseAuth.currentUser
            ?: return AuthResult.Error("Session expired — please log in again.")
        val personId = personIdFromAuthEmail(user.email)
            ?: return AuthResult.Error("Session expired — please log in again.")
        val existing = findPersonByUsername(newUsername)
        if (existing != null && existing.id != personId) {
            return AuthResult.Error("That username is already taken.")
        }
        val person = personRepository.get(personId)
            ?: return AuthResult.Error("Account record not found.")
        return try {
            user.updatePassword(newPassword).await()
            val updated = person.copy(username = newUsername, isTemporaryCredential = false, temporaryPassword = null)
            personRepository.save(updated) // local cache + offline queue, for consistency with the rest of the app
            // The queued write above happens asynchronously in the background — if it
            // hasn't landed by the time we sign out below, the write loses its
            // authenticated session and gets permanently stuck retrying against
            // security rules that require auth (confirmed by hand: race reproduces
            // every time otherwise). So also write synchronously, here, before
            // signing out — Firestore's native .set(pojo) skips the @DocumentId
            // field automatically, unlike the offline queue's Gson-based path.
            firestore.collection("people").document(personId).set(updated).await()
            firebaseAuth.signOut()
            AuthResult.Success(updated, requiresPasswordChange = false)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't update your credentials.")
        }
    }

    // --- First-time Publisher setup (change credentials -> verify details -> optional login methods -> re-login) ---

    /** Step 1 of the Publisher wizard: the Publisher picks their own username and password. Unlike
     * [forcedPasswordChange] this keeps the session open for the next steps and does NOT mark the account as
     * finished: the temporary password stops working now (the Auth password changes and the stored copy is
     * erased), but [Person.isTemporaryCredential] stays true until the re-login proves the new credentials. */
    suspend fun changeCredentialsKeepingSession(newUsername: String, newPassword: String): AuthResult {
        val user = firebaseAuth.currentUser ?: return AuthResult.Error("Session expired — please log in again.")
        val personId = personIdFromAuthEmail(user.email) ?: return AuthResult.Error("Session expired — please log in again.")
        val existing = findPersonByUsername(newUsername)
        if (existing != null && existing.id != personId) return AuthResult.Error("That username is already taken.")
        val person = personRepository.get(personId) ?: return AuthResult.Error("Account record not found.")
        return try {
            user.updatePassword(newPassword).await()
            val updated = person.copy(username = newUsername, temporaryPassword = null)
            // Synchronous as well as queued: the new username must exist on the server before anyone signs in with it.
            firestore.collection("people").document(personId).set(updated).await()
            personRepository.cacheFromServer(updated)
            AuthResult.Success(updated, requiresPasswordChange = true)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't update your credentials.")
        }
    }

    /** Step 3: the Publisher explicitly confirmed their (possibly corrected) details. Saves them and stamps
     * [Person.setupConfirmedAt]; the very next step is the mandatory sign-out and re-login. */
    suspend fun confirmFirstLoginProfile(person: Person): AuthResult {
        val personId = currentPersonId ?: return AuthResult.Error("Session expired — please log in again.")
        if (person.id != personId) return AuthResult.Error("Account mismatch — please log in again.")
        return try {
            val confirmed = person.copy(setupConfirmedAt = System.currentTimeMillis())
            firestore.collection("people").document(personId).set(confirmed).await()
            personRepository.cacheFromServer(confirmed)
            AuthResult.Success(confirmed, requiresPasswordChange = true)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't save your information. Check your connection and try again.")
        }
    }

    /** When this device last signed in to the server with a password (not a token refresh), or null if there is no
     * server session (offline). Compared with [Person.setupConfirmedAt] to tell the required re-login apart from the
     * wizard's own session. */
    fun lastSignInAtMillis(): Long? = firebaseAuth.currentUser?.metadata?.lastSignInTimestamp

    /** The Publisher signed in again with their new credentials: setup is finished. */
    suspend fun completeFirstLogin(): AuthResult {
        val personId = currentPersonId ?: return AuthResult.Error("Session expired — please log in again.")
        val person = personRepository.get(personId) ?: return AuthResult.Error("Account record not found.")
        return try {
            val done = person.copy(isTemporaryCredential = false, temporaryPassword = null, setupCompletedAt = System.currentTimeMillis())
            firestore.collection("people").document(personId).set(done).await()
            personRepository.cacheFromServer(done)
            auditLogRepository.log(actorPersonId = personId, action = "COMPLETE_FIRST_LOGIN_SETUP")
            AuthResult.Success(done, requiresPasswordChange = false)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't finish setup. Check your connection and try again.")
        }
    }

    /** Re-proves the signed-in user's identity with their *current* password —
     * required before either self-service credential change below, per spec §1
     * ("Require the current password before changing the username" / entering
     * current password to change it). Firebase's own reauthenticate() is what
     * actually verifies it against the securely-hashed credential; nothing here
     * ever sees or compares a stored password itself.
     *
     * Bug fix: this used to blanket-catch every exception here as "Current
     * password is incorrect" — including a dropped network connection or
     * Firebase's own too-many-attempts throttling, neither of which means the
     * password was wrong. That produced exactly the confusing "incorrect
     * password" report even when the user typed it correctly. Only an actual
     * [FirebaseAuthInvalidCredentialsException] means the credential itself was
     * rejected; every other failure now surfaces its real cause instead. */
    /** Confirms the signed-in user's password — used before enabling biometric login in Settings. */
    suspend fun verifyCurrentPassword(password: String): Result<Unit> = reauthenticate(password)

    private suspend fun reauthenticate(currentPassword: String): Result<Unit> {
        val user = firebaseAuth.currentUser ?: return Result.failure(IllegalStateException("Session expired — please log in again."))
        val email = user.email ?: return Result.failure(IllegalStateException("Session expired — please log in again."))
        return try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, currentPassword)).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(IllegalStateException("Current password is incorrect."))
        } catch (e: FirebaseNetworkException) {
            Result.failure(IllegalStateException("No internet connection. Check your network and try again."))
        } catch (e: FirebaseTooManyRequestsException) {
            Result.failure(IllegalStateException("Too many attempts. Please wait a moment and try again."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.localizedMessage ?: "Couldn't verify your current password. Please try again."))
        }
    }

    /** Super-Admin (or any signed-in user) changing their own username (spec §1).
     * Firebase Auth's own address is keyed by personId, not username (see
     * [authEmailFor]), so this never touches Auth itself — only [Person.username]
     * and, unlike a password change, doesn't require a fresh login afterward. */
    suspend fun changeUsername(newUsername: String, currentPassword: String): AuthResult {
        val personId = currentPersonId ?: return AuthResult.Error("Session expired — please log in again.")
        reauthenticate(currentPassword).onFailure { return AuthResult.Error(it.message ?: "Current password is incorrect.") }
        val trimmed = newUsername.trim()
        if (trimmed.isBlank()) return AuthResult.Error("Username cannot be blank.")
        val existing = findPersonByUsername(trimmed)
        if (existing != null && existing.id != personId) return AuthResult.Error("That username is already taken.")
        val person = personRepository.get(personId) ?: return AuthResult.Error("Account record not found.")
        return try {
            val previousUsername = person.username
            val updated = person.copy(username = trimmed)
            personRepository.save(updated)
            firestore.collection("people").document(personId).set(updated).await()
            auditLogRepository.log(
                actorPersonId = personId,
                action = "CHANGE_OWN_USERNAME",
                targetType = "Person",
                targetId = personId,
                details = "username: \"$previousUsername\" -> \"$trimmed\"",
            )
            AuthResult.Success(updated, requiresPasswordChange = false)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't update your username.")
        }
    }

    /** Super-Admin (or any signed-in user) changing their own password (spec §1).
     * `FirebaseAuth.updatePassword` is Firebase's own hashed-credential update —
     * this app never stores or hashes a login password itself. Signs the user
     * out afterward so they must log back in with the new password, matching
     * spec §1's "require the Super-Admin to log in again". */
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult {
        val personId = currentPersonId ?: return AuthResult.Error("Session expired — please log in again.")
        if (newPassword.length < 6) return AuthResult.Error("New password must be at least 6 characters.")
        reauthenticate(currentPassword).onFailure { return AuthResult.Error(it.message ?: "Current password is incorrect.") }
        val user = firebaseAuth.currentUser ?: return AuthResult.Error("Session expired — please log in again.")
        return try {
            user.updatePassword(newPassword).await()
            auditLogRepository.log(actorPersonId = personId, action = "CHANGE_OWN_PASSWORD", targetType = "Person", targetId = personId)
            firebaseAuth.signOut()
            AuthResult.Success(personRepository.get(personId) ?: Person(id = personId), requiresPasswordChange = false)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't update your password.")
        }
    }

    /** Account Management spec §7 — an authorized admin changing *someone
     * else's* username. No [reauthenticate] (the acting admin's own signed-in
     * session already proves who they are; only a person changing their own
     * credentials needs to re-prove their password, same distinction
     * [changeUsername] above draws for self-service). The caller (the
     * ViewModel behind Account Management) is responsible for having already
     * checked [com.emfitsolutions.gopreach.domain.PermissionChecker.canManageCredentialsFor]
     * — this method itself never signs anyone out, matching spec §7's steps
     * (validate, check uniqueness, save, audit-log) with no forced re-login. */
    suspend fun adminChangeUsername(
        targetPersonId: String,
        newUsername: String,
        actingPersonId: String,
    ): AuthResult {
        val trimmed = newUsername.trim()
        if (trimmed.isBlank()) return AuthResult.Error("Username cannot be blank.")
        val target = personRepository.get(targetPersonId) ?: return AuthResult.Error("Account not found.")
        val existing = findPersonByUsername(trimmed)
        if (existing != null && existing.id != targetPersonId) return AuthResult.Error("That username is already taken.")
        return try {
            val previousUsername = target.username
            val updated = target.copy(username = trimmed)
            personRepository.save(updated)
            firestore.collection("people").document(targetPersonId).set(updated).await()
            auditLogRepository.log(
                actorPersonId = actingPersonId,
                action = "ACCOUNT_MGMT_USERNAME_CHANGE",
                targetType = "Person",
                targetId = targetPersonId,
                congregationId = target.activeCongregationId,
                details = "username: \"$previousUsername\" -> \"$trimmed\"",
            )
            AuthResult.Success(updated, requiresPasswordChange = false)
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "Couldn't update that account's username.")
        }
    }

    /** Spec §4.5 step 4: lost credentials are recovered from whoever enrolled the
     * person, not a self-service email link (email is optional on [Person]). */
    suspend fun requestPasswordReset(username: String) {
        val person = runCatching { findPersonByUsername(username) }.getOrNull()
        val request = PasswordResetRequest(
            requestedUsername = username,
            personId = person?.id,
            targetPersonId = person?.createdByPersonId,
            requestedAt = System.currentTimeMillis(),
        )
        val id = firestore.collection("passwordResetRequests").document().id
        firestore.collection("passwordResetRequests").document(id).set(request.copy(id = id)).await()
    }
}
