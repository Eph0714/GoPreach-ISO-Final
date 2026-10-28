package com.emfitsolutions.gopreach.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceGroupTest {
    private fun session(id: String, device: String?, name: String, current: Boolean, status: String, last: Long, trusted: Boolean = false) = DeviceSession(
        sessionId = id, current = current, deviceId = device, name = name, model = null, type = "phone", os = "Android", osVersion = "14", appVersion = "1.166.0", ip = null, location = null,
        authMethod = "password", loginAt = 1_000L, lastActiveAt = last, status = status, trusted = trusted, revokedBy = null,
    )

    @Test fun eachDeviceIsListedOnceWithItsSessionsMergedAndSignedOutDevicesAreHistory() {
        val groups = groupDevices(
            listOf(
                session("a1", "phone1", "Ana phone", current = true, status = "active", last = 900, trusted = true),
                session("a2", "phone1", "Ana phone", current = false, status = "active", last = 500, trusted = true),
                session("a3", "phone1", "Ana phone", current = false, status = "revoked", last = 100, trusted = true),
                session("b1", "tab1", "Kitchen tablet", current = false, status = "active", last = 800),
                session("c1", "gone1", "Signed-out phone", current = false, status = "revoked", last = 50),
            ),
            listOf(TrustedDevice("abc", "phone1", "Ana phone", 1_000L, 5_000L), TrustedDevice("xyz", "laptop1", "Home laptop", 1_000L, 6_000L)),
        )
        assertEquals(listOf("phone1", "tab1", "laptop1"), groups.map { it.deviceId }, "the device in use first, then by last activity; a trusted device without a session is kept; a signed-out one is not")
        assertEquals(2, groups[0].sessions)
        assertTrue(groups[0].current && groups[0].trusted && groups[0].active)
        assertEquals(5_000L, groups[0].trustedUntil)
        assertFalse(groups[1].trusted)
        assertFalse(groups[2].active)
        assertTrue(groups[2].trusted)
    }

    @Test fun aSessionWithoutADeviceIdStandsOnItsOwn() {
        val groups = groupDevices(listOf(session("s1", null, "Old app", current = false, status = "active", last = 10)), emptyList())
        assertEquals(listOf("s1"), groups.map { it.key })
        assertEquals("s1", groups[0].sessionId)
    }

    @Test fun theNewestSessionsFactsWin() {
        val groups = groupDevices(listOf(session("o", "d", "Old name", false, "active", 10), session("n", "d", "New name", false, "active", 20)), emptyList())
        assertEquals("New name", groups.single().name)
    }
}
