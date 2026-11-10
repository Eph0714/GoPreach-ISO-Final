package com.emfitsolutions.gopreach.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountStatusRepositoryTest {
    @Test fun readsTheServersAnswerAndSkipsWhatItDoesNotKnow() {
        val m = AccountStatusRepository.parse(
            """{"statuses":{
                "a":{"status":"TEMP_UNUSED","firstLoginAt":null,"lastLoginAt":null,"passwordChangedAt":null},
                "b":{"status":"TEMP_USED","firstLoginAt":10,"lastLoginAt":20,"passwordChangedAt":null},
                "c":{"status":"PASSWORD_CHANGED","firstLoginAt":10,"lastLoginAt":30,"passwordChangedAt":25},
                "d":{"status":"DISABLED"},
                "e":{"status":"SOMETHING_NEW"}
            }}""",
        )
        assertEquals(setOf("a", "b", "c", "d"), m.keys, "a status this app does not know is left out, never shown as something else")
        assertEquals(PasswordStatus.TEMP_UNUSED, m["a"]!!.status)
        assertNull(m["a"]!!.firstLoginAt)
        assertEquals(PasswordStatus.TEMP_USED, m["b"]!!.status)
        assertEquals(10L, m["b"]!!.firstLoginAt)
        assertEquals(25L, m["c"]!!.passwordChangedAt)
        assertEquals(PasswordStatus.DISABLED, m["d"]!!.status)
    }

    @Test fun anythingElseIsAnEmptyMapNotACrash() {
        assertTrue(AccountStatusRepository.parse("""{"error":"Not allowed"}""").isEmpty())
        assertTrue(AccountStatusRepository.parse("""{"statuses":{}}""").isEmpty())
    }
}
