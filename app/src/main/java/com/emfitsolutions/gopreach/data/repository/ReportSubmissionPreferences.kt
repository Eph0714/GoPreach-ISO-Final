package com.emfitsolutions.gopreach.data.repository

import android.content.Context

/**
 * The Report Submission month range a user last chose, kept per user + Active Role + congregation scope (so switching role or congregation
 * brings back the range saved for that one). Stored on the device; first-of-month millis.
 */
class ReportSubmissionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("report_submission_preferences", Context.MODE_PRIVATE)

    private fun key(userId: String, role: String, congregationId: String?) = "$userId|$role|${congregationId.orEmpty()}"

    fun range(userId: String, role: String, congregationId: String?): Pair<Long, Long>? {
        val k = key(userId, role, congregationId)
        val from = prefs.getLong("$k.from", 0L)
        val to = prefs.getLong("$k.to", 0L)
        return if (from > 0L && to >= from) from to to else null
    }

    fun saveRange(userId: String, role: String, congregationId: String?, from: Long, to: Long) {
        val k = key(userId, role, congregationId)
        prefs.edit().putLong("$k.from", from).putLong("$k.to", to).putLong("$k.updatedAt", System.currentTimeMillis()).apply()
    }

    fun lastKind(userId: String, role: String): String? = prefs.getString("$userId|$role.kind", null)
    fun saveKind(userId: String, role: String, kind: String) { prefs.edit().putString("$userId|$role.kind", kind).apply() }
}
