package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.platform.KeyValueEditor
import com.emfitsolutions.gopreach.platform.KeyValueStore
import com.emfitsolutions.gopreach.platform.KeyValueStores
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, Any?>()
    override fun getString(key: String, default: String?) = values[key] as? String ?: default
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?) = values[key] as? Set<String> ?: default
    override fun edit(): KeyValueEditor = object : KeyValueEditor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun putInt(key: String, value: Int) = apply { values[key] = value }
        override fun putLong(key: String, value: Long) = apply { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun apply() = Unit
    }
}

private class MemoryStores : KeyValueStores {
    val store = MemoryStore()
    override fun open(name: String): KeyValueStore = store
    override fun openSecure(name: String): KeyValueStore = store
}

class HttpAuthServiceTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val tokens = """{"accessToken":"a1","refreshToken":"r1","expiresAt":1,"personId":"p1"}"""

    private fun service(stores: MemoryStores, engine: MockEngine) =
        HttpAuthService(HttpClient(engine), "https://api.test", stores, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), deviceLabel = "GoPreach Android (Test Phone)")

    @Test fun serverConfirmedActiveSessionIsReportedWithTheOtherDevice() = runTest {
        val engine = MockEngine { respond("""{"error":"session-active","device":"GoPreach Web (Chrome on Windows)","lastActiveAt":1700000000000}""", HttpStatusCode.Conflict, json) }
        val e = assertFailsWith<AuthFailure.SessionActive> { service(MemoryStores(), engine).signIn("p1@gopreach.internal", "pw") }
        assertEquals("GoPreach Web (Chrome on Windows)", e.session.device)
        assertEquals(1700000000000L, e.session.lastActiveAtMillis)
    }

    @Test fun aPlain429IsNeverTreatedAsAnActiveSession() = runTest {
        val engine = MockEngine { respond("""{"error":"too-many-requests"}""", HttpStatusCode.TooManyRequests, json) }
        assertFailsWith<AuthFailure.TooManyRequests> { service(MemoryStores(), engine).signIn("p1@gopreach.internal", "pw") }
    }

    @Test fun signInSendsTheDeviceAndTheChoiceToEndTheOtherSession() = runTest {
        var body = ""
        val engine = MockEngine { req -> body = req.body.toByteArray().decodeToString(); respond(tokens, HttpStatusCode.OK, json) }
        val auth = service(MemoryStores(), engine)
        auth.signIn("p1@gopreach.internal", "pw", endOtherSession = true)
        assertTrue(body.contains("\"endOtherSession\":true"), body)
        assertTrue(body.contains("GoPreach Android (Test Phone)"), body)
        assertNotNull(auth.currentUser)
    }

    @Test fun aSessionTheServerEndedSignsThePhoneOutAndExplainsWhyOnce() = runTest {
        val stores = MemoryStores()
        var call = 0
        val engine = MockEngine { call++; if (call == 1) respond(tokens, HttpStatusCode.OK, json) else respond("""{"error":"session-ended"}""", HttpStatusCode.Unauthorized, json) }
        val auth = service(stores, engine)
        auth.signIn("p1@gopreach.internal", "pw")
        assertNull(auth.idToken(force = true), "the old device cannot renew")
        assertNull(auth.currentUser, "its tokens are cleared")
        assertNull(stores.store.getString("refresh", null))
        assertTrue(auth.takeSessionEndedNotice())
        assertFalse(auth.takeSessionEndedNotice(), "the message is shown once")
    }

    @Test fun anOrdinaryExpiredLoginIsNotReportedAsSignedInElsewhere() = runTest {
        var call = 0
        val engine = MockEngine { call++; if (call == 1) respond(tokens, HttpStatusCode.OK, json) else respond("""{"error":"invalid-token"}""", HttpStatusCode.Unauthorized, json) }
        val auth = service(MemoryStores(), engine)
        auth.signIn("p1@gopreach.internal", "pw")
        assertNull(auth.idToken(force = true))
        assertFalse(auth.takeSessionEndedNotice())
    }
}
