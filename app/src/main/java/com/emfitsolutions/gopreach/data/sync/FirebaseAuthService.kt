package com.emfitsolutions.gopreach.data.sync

import android.content.Context
import com.emfitsolutions.gopreach.data.repository.AuthFailure
import com.emfitsolutions.gopreach.data.repository.AuthService
import com.emfitsolutions.gopreach.data.repository.AuthUser
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Firebase Auth implementation of [AuthService]. */
class FirebaseAuthService(
    private val appContext: Context,
    private val firebaseAuth: FirebaseAuth,
) : AuthService {
    private fun FirebaseUser.toAuthUser() = AuthUser(uid, email, metadata?.lastSignInTimestamp)

    override val currentUser: AuthUser? get() = firebaseAuth.currentUser?.toAuthUser()

    override fun authState(): Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser?.toAuthUser()) }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    override suspend fun signIn(email: String, password: String) {
        guard { firebaseAuth.signInWithEmailAndPassword(email, password).await() }
    }

    override fun signOut() = firebaseAuth.signOut()

    override suspend fun idToken(force: Boolean): String? = guard { firebaseAuth.currentUser?.getIdToken(force)?.await()?.token }

    override suspend fun reauthenticate(password: String) {
        guard {
            val user = firebaseAuth.currentUser ?: throw AuthFailure.InvalidUser()
            val email = user.email ?: throw AuthFailure.InvalidUser()
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        }
    }

    override suspend fun updatePassword(newPassword: String) {
        guard {
            val user = firebaseAuth.currentUser ?: throw AuthFailure.InvalidUser()
            user.updatePassword(newPassword).await()
        }
    }

    override suspend fun createAccount(email: String, password: String) {
        // A throw-away second Firebase app, so creating the account never signs the current admin out.
        val secondaryApp = FirebaseApp.initializeApp(appContext, FirebaseApp.getInstance().options, "temp-account-" + email.substringBefore('@'))
        try {
            guard {
                val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)
                secondaryAuth.createUserWithEmailAndPassword(email, password).await()
                secondaryAuth.signOut()
            }
        } finally {
            secondaryApp.delete()
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthFailure) {
        throw e
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        throw AuthFailure.InvalidCredentials(e)
    } catch (e: FirebaseAuthInvalidUserException) {
        throw AuthFailure.InvalidUser(e)
    } catch (e: FirebaseTooManyRequestsException) {
        throw AuthFailure.TooManyRequests(e)
    } catch (e: FirebaseNetworkException) {
        throw AuthFailure.Network(e)
    } catch (e: Exception) {
        throw AuthFailure.Other(e.localizedMessage, e)
    }
}
