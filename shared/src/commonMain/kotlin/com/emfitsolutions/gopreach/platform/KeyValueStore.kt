package com.emfitsolutions.gopreach.platform

/**
 * Small persistent settings storage (Android SharedPreferences, iOS NSUserDefaults). The API intentionally mirrors
 * SharedPreferences (`getX(key, default)`, `edit().putX().apply()`) so existing stores move with minimal changes.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun edit(): KeyValueEditor
}

interface KeyValueEditor {
    fun putString(key: String, value: String?): KeyValueEditor
    fun putInt(key: String, value: Int): KeyValueEditor
    fun putLong(key: String, value: Long): KeyValueEditor
    fun putBoolean(key: String, value: Boolean): KeyValueEditor
    fun putStringSet(key: String, value: Set<String>?): KeyValueEditor
    fun remove(key: String): KeyValueEditor
    fun apply()
}

inline fun KeyValueStore.edit(block: KeyValueEditor.() -> Unit) {
    val editor = edit()
    editor.block()
    editor.apply()
}

/** Opens named stores. [openSecure] is encrypted at rest (Android Keystore / iOS Keychain) — use it for credentials. */
interface KeyValueStores {
    fun open(name: String): KeyValueStore
    fun openSecure(name: String): KeyValueStore
}
