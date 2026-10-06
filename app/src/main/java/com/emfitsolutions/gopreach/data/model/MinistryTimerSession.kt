package com.emfitsolutions.gopreach.data.model

import com.google.firebase.firestore.DocumentId

enum class TimerSessionStatus { RUNNING, PAUSED, COMPLETED }

/**
 * Ministry Timer (spec §20, extended with real Play/Pause/Stop) — a live
 * session tied to one Publisher and one [PlannerDay]. Surviving navigation
 * needs no foreground service or process-death-surviving background work:
 * while [status] is [TimerSessionStatus.RUNNING], the UI simply renders
 * `accumulatedSeconds + (now - startTime)` on a ticking effect (see
 * `MinistryTimerCard`) — navigating away and back just re-reads this same
 * still-RUNNING document, nothing is lost. PAUSE banks the elapsed time so
 * far into [accumulatedSeconds] and flips to [TimerSessionStatus.PAUSED]
 * (frozen, no ticking); RESUME starts a fresh RUNNING segment from a new
 * [startTime] without touching the banked total. STOP computes the final
 * [durationSeconds] (`accumulatedSeconds` plus any still-running segment) and
 * flips to [TimerSessionStatus.COMPLETED]; that duration is added into the
 * owning [PlannerDay.totalMinutes] at that moment (see
 * [com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
 * .addMinutes]) — never re-derived later by summing every session, so a
 * completed session's contribution can't accidentally be double-counted.
 *
 * At most one RUNNING-or-PAUSED session per Publisher at a time (enforced by
 * the ViewModel checking before START) is what satisfies spec §20's "prevent
 * duplicate/overlapping saves."
 *
 * Firestore collection: `ministryTimerSessions/{sessionId}`
 */
data class MinistryTimerSession(
    @DocumentId val id: String = "",
    val publisherPersonId: String = "",
    val plannerDayId: String = "",
    val startTime: Long = 0L,
    val stopTime: Long? = null,
    /** Elapsed seconds banked from every RUNNING segment before the current
     * one — 0 until the first PAUSE. While [status] is RUNNING, the true
     * elapsed total is `accumulatedSeconds + (now - startTime)`; while
     * PAUSED, it's just `accumulatedSeconds` (frozen). */
    val accumulatedSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val status: TimerSessionStatus = TimerSessionStatus.RUNNING,
    val createdAt: Long = 0L,
)
