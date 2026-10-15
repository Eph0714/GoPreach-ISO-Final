package com.emfitsolutions.gopreach.data.passkey

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.emfitsolutions.gopreach.data.repository.PasskeyCancelled

/**
 * Passkeys are built but switched off: while this is false the login button and the Account Settings row are hidden for everyone.
 * Turn it on once the backend has PASSKEY_RP_ID / ANDROID_CERT_SHA256 set and /.well-known/assetlinks.json is being served.
 */
const val PASSKEY_LOGIN_ENABLED = false

/**
 * The phone's passkey manager (Android Credential Manager). It takes the server's WebAuthn options as JSON and returns the signed answer as
 * JSON, so the sign-in logic itself stays in the shared module. [context] must be an Activity: the system passkey sheet is shown over it.
 */
object PasskeyClient {
    /** Signs a login challenge with a passkey the person picks. Throws [PasskeyCancelled] when they back out or have none. */
    suspend fun get(context: Context, requestJson: String): String {
        try {
            val result = CredentialManager.create(context).getCredential(
                context,
                GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))),
            )
            val credential = result.credential as? PublicKeyCredential
                ?: throw PasskeyCancelled("That wasn't a passkey. Please try again.")
            return credential.authenticationResponseJson
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyCancelled("Passkey sign-in was cancelled.")
        } catch (e: NoCredentialException) {
            throw PasskeyCancelled("No passkey was found on this device for GoPreach. Sign in with your password, then add one in Account Settings.")
        } catch (e: GetCredentialException) {
            throw PasskeyCancelled("Passkey sign-in isn't available right now (${e.type.substringAfterLast('.')}).")
        }
    }

    /** Creates a passkey on this phone for the signed-in account. Throws [PasskeyCancelled] when they back out or it can't be made. */
    suspend fun create(context: Context, requestJson: String): String {
        try {
            val result = CredentialManager.create(context).createCredential(context, CreatePublicKeyCredentialRequest(requestJson))
            return (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                ?: throw PasskeyCancelled("That wasn't a passkey. Please try again.")
        } catch (e: CreateCredentialCancellationException) {
            throw PasskeyCancelled("Passkey set-up was cancelled.")
        } catch (e: CreateCredentialException) {
            throw PasskeyCancelled("A passkey couldn't be created (${e.type.substringAfterLast('.')}).")
        }
    }
}
