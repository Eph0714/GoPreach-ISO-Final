package com.emfitsolutions.gopreach.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AndroidKeyValueStores(private val context: Context) : KeyValueStores {
    override fun open(name: String): KeyValueStore =
        SharedPrefsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    // Same file names and key schemes the app has always used, so already-stored credentials stay readable.
    override fun openSecure(name: String): KeyValueStore {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return SharedPrefsStore(
            EncryptedSharedPreferences.create(
                context, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ),
        )
    }
}

private class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?) = prefs.getString(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = prefs.getStringSet(key, default)
    override fun edit(): KeyValueEditor = Editor(prefs.edit())

    private class Editor(private val e: SharedPreferences.Editor) : KeyValueEditor {
        override fun putString(key: String, value: String?) = also { e.putString(key, value) }
        override fun putInt(key: String, value: Int) = also { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) = also { e.putLong(key, value) }
        override fun putBoolean(key: String, value: Boolean) = also { e.putBoolean(key, value) }
        override fun putStringSet(key: String, value: Set<String>?) = also { e.putStringSet(key, value) }
        override fun remove(key: String) = also { e.remove(key) }
        override fun apply() = e.apply()
    }
}
