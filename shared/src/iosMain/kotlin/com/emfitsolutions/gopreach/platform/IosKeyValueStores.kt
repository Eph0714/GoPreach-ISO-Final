package com.emfitsolutions.gopreach.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.set
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS storage: plain settings in NSUserDefaults, credentials in the Keychain (encrypted at rest by the OS,
 * available after the first unlock, never included in unencrypted backups).
 */
class IosKeyValueStores : KeyValueStores {
    override fun open(name: String): KeyValueStore = UserDefaultsStore(NSUserDefaults(suiteName = name))
    override fun openSecure(name: String): KeyValueStore = KeychainStore("com.emfitsolutions.gopreach.$name")
}

private const val SET_SEPARATOR = "\u001F"

/** Edits are collected and written together on [apply], like SharedPreferences. */
private abstract class BufferedEditor : KeyValueEditor {
    private val actions = mutableListOf<() -> Unit>()
    protected fun queue(action: () -> Unit): KeyValueEditor = also { actions += action }
    override fun apply() = actions.forEach { it() }
}

private class UserDefaultsStore(private val defaults: NSUserDefaults) : KeyValueStore {
    private fun has(key: String) = defaults.objectForKey(key) != null

    override fun getString(key: String, default: String?): String? = defaults.stringForKey(key) ?: default
    override fun getInt(key: String, default: Int): Int = if (has(key)) defaults.integerForKey(key).toInt() else default
    override fun getLong(key: String, default: Long): Long = if (has(key)) defaults.integerForKey(key) else default
    override fun getBoolean(key: String, default: Boolean): Boolean = if (has(key)) defaults.boolForKey(key) else default
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        defaults.arrayForKey(key)?.map { it.toString() }?.toSet() ?: default

    override fun edit(): KeyValueEditor = object : BufferedEditor() {
        override fun putString(key: String, value: String?) = queue { if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key) }
        override fun putInt(key: String, value: Int) = queue { defaults.setInteger(value.toLong(), key) }
        override fun putLong(key: String, value: Long) = queue { defaults.setInteger(value, key) }
        override fun putBoolean(key: String, value: Boolean) = queue { defaults.setBool(value, key) }
        override fun putStringSet(key: String, value: Set<String>?) = queue { if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value.toList(), key) }
        override fun remove(key: String) = queue { defaults.removeObjectForKey(key) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class KeychainStore(private val service: String) : KeyValueStore {

    private fun dictionary(entries: List<Pair<CFStringRef?, CFTypeRef?>>): CFDictionaryRef? {
        val keys = nativeHeap.allocArray<COpaquePointerVar>(entries.size)
        val values = nativeHeap.allocArray<COpaquePointerVar>(entries.size)
        entries.forEachIndexed { i, (k, v) ->
            keys[i] = k
            values[i] = v
        }
        return CFDictionaryCreate(kCFAllocatorDefault, keys.reinterpret(), values.reinterpret(), entries.size.convert(), null, null)
            .also {
                nativeHeap.free(keys.rawValue)
                nativeHeap.free(values.rawValue)
            }
    }

    /** Runs [block] with the retained CF value for [account]/[extra], releasing everything afterwards. */
    private fun <T> withQuery(account: String, extra: List<Pair<CFStringRef?, CFTypeRef?>> = emptyList(), block: (CFDictionaryRef?) -> T): T {
        val retainedService = CFBridgingRetain(service)
        val retainedAccount = CFBridgingRetain(account)
        val entries = listOf<Pair<CFStringRef?, CFTypeRef?>>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to retainedService,
            kSecAttrAccount to retainedAccount,
        ) + extra
        val query = dictionary(entries)
        try {
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(retainedService)
            CFRelease(retainedAccount)
        }
    }

    private fun read(key: String): String? = withQuery(
        key,
        listOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne),
    ) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
        }
    }

    private fun delete(key: String) {
        withQuery(key) { query -> SecItemDelete(query) }
    }

    private fun write(key: String, value: String) {
        delete(key)
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        val retainedData = CFBridgingRetain(data)
        try {
            withQuery(
                key,
                listOf(kSecValueData to retainedData, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock),
            ) { query -> SecItemAdd(query, null) }
        } finally {
            CFRelease(retainedData)
        }
    }

    override fun getString(key: String, default: String?): String? = read(key) ?: default
    override fun getInt(key: String, default: Int): Int = read(key)?.toIntOrNull() ?: default
    override fun getLong(key: String, default: Long): Long = read(key)?.toLongOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = read(key)?.toBooleanStrictOrNull() ?: default
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        read(key)?.let { if (it.isEmpty()) emptySet() else it.split(SET_SEPARATOR).toSet() } ?: default

    override fun edit(): KeyValueEditor = object : BufferedEditor() {
        override fun putString(key: String, value: String?) = queue { if (value == null) delete(key) else write(key, value) }
        override fun putInt(key: String, value: Int) = queue { write(key, value.toString()) }
        override fun putLong(key: String, value: Long) = queue { write(key, value.toString()) }
        override fun putBoolean(key: String, value: Boolean) = queue { write(key, value.toString()) }
        override fun putStringSet(key: String, value: Set<String>?) = queue { if (value == null) delete(key) else write(key, value.joinToString(SET_SEPARATOR)) }
        override fun remove(key: String) = queue { delete(key) }
    }
}
