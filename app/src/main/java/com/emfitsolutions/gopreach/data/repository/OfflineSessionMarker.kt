package com.emfitsolutions.gopreach.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "gopreach_offline_session"
private const val KEY_PERSON_ID = "personId"

/**
 * "Offline Login" spec §1 — Firebase Auth itself cannot sign a user in without a
 * network round-trip (there's no offline credential path in the SDK), so a
 * successful [AuthRepository.offlineSignIn] can't make
 * `FirebaseAuth.currentUser` non-null the way a normal online sign-in does.
 * This marker is the app's own record of "this personId is considered signed
 * in," read alongside Firebase's own persisted auth state by [UserSession] —
 * whichever is present wins, with Firebase's own state taking priority when
 * both exist (see [UserSession.state]). Plain (unencrypted) SharedPreferences
 * is fine here: this only ever holds a personId, never a credential.
 */
@Singleton
class OfflineSessionMarker @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _personId = MutableStateFlow(prefs.getString(KEY_PERSON_ID, null))
    val personId: StateFlow<String?> = _personId

    fun save(personId: String) {
        prefs.edit { putString(KEY_PERSON_ID, personId) }
        _personId.value = personId
    }

    fun clear() {
        prefs.edit { remove(KEY_PERSON_ID) }
        _personId.value = null
    }
}
