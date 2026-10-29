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

    @Test fun signInSendsTheDeviceAndTheApprovedLoginRequestNeverAnEndOtherSessionFlag() = runTest {
        var body = ""
        val engine = MockEngine { req -> body = req.body.toByteArray().decodeToString(); respond(tokens, HttpStatusCode.OK, json) }
        val auth = service(MemoryStores(), engine)
        auth.signIn("p1@gopreach.internal", "pw", approval = LoginRequestTicket("req1", "poll1", 0L, null))
        assertFalse(body.contains("endOtherSession"), body)
        assertTrue(body.contains("\"requestId\":\"req1\"") && body.contains("\"pollToken\":\"poll1\""), body)
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

    @Test fun aSignOutByThePersonKeepsTheFingerprintLoginButOneEndedByTheServerForgetsEverything() = runTest {
        val ended = mutableListOf<Boolean>()
        // by the person (or the idle timeout): the fingerprint / PIN sign-ins stay
        val own = HttpAuthService(HttpClient(MockEngine { respond(tokens, HttpStatusCode.OK, json) }), "https://api.test", MemoryStores(), CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), onSignedOut = { ended += it })
        own.signIn("p1@gopreach.internal", "pw")
        own.signOut()
        assertEquals(listOf(false), ended, "signing out is reported as the person's own")
        // by the server (another device, a deleted trusted device, a password change): everything saved is forgotten
        var call = 0
        val engine = MockEngine { call++; if (call == 1) respond(tokens, HttpStatusCode.OK, json) else respond("""{"error":"session-ended"}""", HttpStatusCode.Unauthorized, json) }
        val serverEnded = HttpAuthService(HttpClient(engine), "https://api.test", MemoryStores(), CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), onSignedOut = { ended += it })
        serverEnded.signIn("p1@gopreach.internal", "pw")
        assertNull(serverEnded.idToken(force = true))
        assertEquals(listOf(false, true), ended, "a session the server ended is reported as such")
    }
    @Test fun anOrdinaryExpiredLoginIsNotReportedAsSignedInElsewhere() = runTest {
        var call = 0
        val engine = MockEngine { call++; if (call == 1) respond(tokens, HttpStatusCode.OK, json) else respond("""{"error":"invalid-token"}""", HttpStatusCode.Unauthorized, json) }
        val auth = service(MemoryStores(), engine)
        auth.signIn("p1@gopreach.internal", "pw")
        assertNull(auth.idToken(force = true))
        assertFalse(auth.takeSessionEndedNotice())
    }

    // ---- two-factor sign-in ----
    private val challenge = """{"twoFactorRequired":true,"challengeId":"c1","challengeToken":"secret1","expiresAt":9999999999999,"methods":{"totp":true,"recovery":true,"email":{"masked":"a**@example.com"}}}"""

    @Test fun aPasswordRightButTwoFactorAccountSignsNobodyInAndReturnsTheChallenge() = runTest {
        val auth = service(MemoryStores(), MockEngine { respond(challenge, HttpStatusCode.Accepted, json) })
        val e = assertFailsWith<AuthFailure.TwoFactorRequired> { auth.signIn("p1@gopreach.internal", "pw") }
        assertEquals("c1", e.challenge.challengeId)
        assertEquals("secret1", e.challenge.challengeToken)
        assertTrue(e.challenge.totp && e.challenge.recovery)
        assertEquals("a**@example.com", e.challenge.emailMasked)
        assertNull(auth.currentUser, "nothing is signed in before the second factor")
    }

    @Test fun aRightCodeCompletesTheSignInAndTheTrustedCredentialIsKeptForTheNextOne() = runTest {
        val stores = MemoryStores()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            bodies += req.body.toByteArray().decodeToString()
            if (req.url.encodedPath.endsWith("/2fa/verify")) respond("""{"accessToken":"a1","refreshToken":"r1","expiresAt":1,"personId":"p1","trustedToken":"trusted-credential"}""", HttpStatusCode.OK, json)
            else respond(challenge, HttpStatusCode.Accepted, json)
        }
        val auth = service(stores, engine)
        val c = assertFailsWith<AuthFailure.TwoFactorRequired> { auth.signIn("p1@gopreach.internal", "pw") }.challenge
        auth.verifyTwoFactor(c, SecondFactor.AUTHENTICATOR_APP, " 123456 ", trustDevice = true)
        val sent = bodies.last()
        assertTrue(sent.contains("\"challengeId\":\"c1\"") && sent.contains("\"code\":\"123456\"") && sent.contains("\"trustDevice\":true"), sent)
        assertEquals("p1", auth.currentUser?.uid)
        // the next password sign-in presents the trusted credential
        auth.signOut()
        runCatching { auth.signIn("p1@gopreach.internal", "pw") }
        assertTrue(bodies.last().contains("\"trustedToken\":\"trusted-credential\""), bodies.last())
    }

    @Test fun recoveryAndEmailCodesUseTheirOwnFieldsAndAWrongCodeIsReportedWithTheTriesLeft() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req -> bodies += req.body.toByteArray().decodeToString(); respond("""{"error":"invalid-code","triesLeft":3}""", HttpStatusCode.Unauthorized, json) }
        val auth = service(MemoryStores(), engine)
        val c = TwoFactorChallenge("c1", "s1", 0L, totp = true, recovery = true, emailMasked = null)
        val e = assertFailsWith<AuthFailure.InvalidCode> { auth.verifyTwoFactor(c, SecondFactor.RECOVERY_CODE, "ABCDE-FGHJK", false) }
        assertEquals(3, e.triesLeft)
        assertTrue(bodies.last().contains("\"recoveryCode\":\"ABCDE-FGHJK\""), bodies.last())
        assertFailsWith<AuthFailure.InvalidCode> { auth.verifyTwoFactor(c, SecondFactor.EMAIL_CODE, "111111", false) }
        assertTrue(bodies.last().contains("\"emailCode\":\"111111\""), bodies.last())
        assertNull(auth.currentUser)
    }

    @Test fun anExpiredOrUsedUpChallengeIsReportedAsExpired() = runTest {
        val c = TwoFactorChallenge("c1", "s1", 0L, true, true, null)
        val gone = service(MemoryStores(), MockEngine { respond("""{"error":"invalid-challenge"}""", HttpStatusCode.Unauthorized, json) })
        assertFailsWith<AuthFailure.ChallengeExpired> { gone.verifyTwoFactor(c, SecondFactor.AUTHENTICATOR_APP, "123456", false) }
        val tooMany = service(MemoryStores(), MockEngine { respond("""{"error":"too-many-tries"}""", HttpStatusCode.TooManyRequests, json) })
        assertFailsWith<AuthFailure.ChallengeExpired> { tooMany.verifyTwoFactor(c, SecondFactor.AUTHENTICATOR_APP, "123456", false) }
    }

    @Test fun signInSendsTheFullDeviceDescriptionWithAStableDeviceId() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req -> bodies += req.body.toByteArray().decodeToString(); respond(tokens, HttpStatusCode.OK, json) }
        val stores = MemoryStores()
        val auth = HttpAuthService(HttpClient(engine), "https://api.test", stores, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), deviceLabel = "GoPreach Android (Test Phone)",
            deviceInfo = DeviceInfo("Test Phone", "TP-1", "phone", "Android", "14", "1.165.0"))
        auth.signIn("p1@gopreach.internal", "pw")
        val first = bodies.last()
        assertTrue(first.contains("\"type\":\"phone\"") && first.contains("\"osVersion\":\"14\"") && first.contains("\"appVersion\":\"1.165.0\"") && first.contains("\"model\":\"TP-1\""), first)
        val id = Regex("\"id\":\"([0-9a-f]{36})\"").find(first)?.groupValues?.get(1)
        assertNotNull(id, first)
        auth.signOut()
        auth.signIn("p1@gopreach.internal", "pw")
        assertTrue(bodies.last().contains("\"id\":\"$id\""), "the same installation keeps the same device id")
    }
}