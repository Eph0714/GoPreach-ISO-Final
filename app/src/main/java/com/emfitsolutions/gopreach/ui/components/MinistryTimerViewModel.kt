package com.emfitsolutions.gopreach.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.model.MinistryTimerSession
import com.emfitsolutions.gopreach.data.model.PlannerDay
import com.emfitsolutions.gopreach.data.model.TimerSessionStatus
import com.emfitsolutions.gopreach.data.repository.MinistryTimerSessionRepository
import com.emfitsolutions.gopreach.data.repository.PlannerDayRepository
import com.emfitsolutions.gopreach.domain.DayBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of [MinistryTimerViewModel.stopAndFinalize] — what the Stop
 * confirmation dialogs need: the just-finished session's elapsed minutes,
 * and whatever the target day's [PlannerDay.totalMinutes] already was
 * *before* this session (0 means "no existing record yet," driving whether
 * the UI shows the plain save prompt or the add-to-existing prompt). */
data class MinistryTimerStopResult(val elapsedMinutes: Int, val existingMinutesForTargetDay: Int)

/**
 * Ministry Timer (spec §20) — reusable from both the Publisher Main
 * Interface and My Planner → Day (same instance either way, since a
 * Hilt-scoped `hiltViewModel()` call for the same nav-graph entry resolves
 * to the same ViewModel instance whenever both call sites share a scope —
 * and even where they don't, both just read/write the same Firestore-backed
 * [MinistryTimerSessionRepository.observeRunning] state, so they can never
 * disagree about whether a timer is running).
 */
@HiltViewModel
class MinistryTimerViewModel @Inject constructor(
    private val sessionRepository: MinistryTimerSessionRepository,
    private val plannerDayRepository: PlannerDayRepository,
) : ViewModel() {

    fun runningSessionFor(publisherPersonId: String): Flow<MinistryTimerSession?> =
        sessionRepository.observeRunning(publisherPersonId)

    /** Spec §20.8 — "prevent overlapping/duplicate sessions from being
     * counted twice": a no-op if one is already running, checked here rather
     * than trusted to the caller. */
    fun start(publisherPersonId: String) {
        viewModelScope.launch {
            if (sessionRepository.observeRunning(publisherPersonId).first() != null) return@launch
            val now = System.currentTimeMillis()
            sessionRepository.save(
                MinistryTimerSession(
                    publisherPersonId = publisherPersonId,
                    plannerDayId = PlannerDay.idFor(publisherPersonId, DayBounds.of(now).startInclusive),
                    startTime = now,
                    status = TimerSessionStatus.RUNNING,
                    createdAt = now,
                ),
            )
        }
    }

    /** PAUSE — banks the current RUNNING segment's elapsed time into
     * [MinistryTimerSession.accumulatedSeconds] and freezes the display; a
     * no-op unless a session is actually RUNNING (pausing an already-paused
     * or nonexistent session does nothing). */
    fun pause(publisherPersonId: String) {
        viewModelScope.launch {
            val session = sessionRepository.observeRunning(publisherPersonId).first() ?: return@launch
            if (session.status != TimerSessionStatus.RUNNING) return@launch
            val now = System.currentTimeMillis()
            val segmentSeconds = ((now - session.startTime) / 1000).coerceAtLeast(0)
            sessionRepository.save(
                session.copy(accumulatedSeconds = session.accumulatedSeconds + segmentSeconds, status = TimerSessionStatus.PAUSED),
            )
        }
    }

    /** RESUME — starts a fresh RUNNING segment from a new [startTime] without
     * touching the already-banked [MinistryTimerSession.accumulatedSeconds];
     * a no-op unless a session is actually PAUSED. */
    fun resume(publisherPersonId: String) {
        viewModelScope.launch {
            val session = sessionRepository.observeRunning(publisherPersonId).first() ?: return@launch
            if (session.status != TimerSessionStatus.PAUSED) return@launch
            sessionRepository.save(session.copy(startTime = System.currentTimeMillis(), status = TimerSessionStatus.RUNNING))
        }
    }

    /** STOP, phase 1 — "Stop the timer immediately" (spec): computes the
     * exact total duration in seconds (banked
     * [MinistryTimerSession.accumulatedSeconds] plus any still-running
     * segment) and marks the session COMPLETED, but does *not* yet touch the
     * day's [PlannerDay.totalMinutes] — that only happens once the Publisher
     * confirms via [confirmSaveElapsedMinutes], through the
     * save/add-to-existing dialogs the UI shows after this returns. A plain
     * suspend function (not launched internally) so the caller can await the
     * result and use it to drive those dialogs. Works from either RUNNING or
     * PAUSED; `null` if nothing was active. */
    suspend fun stopAndFinalize(publisherPersonId: String, targetDayMillis: Long): MinistryTimerStopResult? {
        val session = sessionRepository.observeRunning(publisherPersonId).first() ?: return null
        val now = System.currentTimeMillis()
        val runningSegmentSeconds = if (session.status == TimerSessionStatus.RUNNING) {
            ((now - session.startTime) / 1000).coerceAtLeast(0)
        } else {
            0L
        }
        val durationSeconds = session.accumulatedSeconds + runningSegmentSeconds
        sessionRepository.save(
            session.copy(stopTime = now, durationSeconds = durationSeconds, status = TimerSessionStatus.COMPLETED),
        )
        val elapsedMinutes = (durationSeconds / 60).toInt()
        val existingMinutes = plannerDayRepository.observeDay(publisherPersonId, targetDayMillis).first()?.totalMinutes ?: 0
        return MinistryTimerStopResult(elapsedMinutes = elapsedMinutes, existingMinutesForTargetDay = existingMinutes)
    }

    /** STOP, phase 2 — only called once the Publisher explicitly confirms
     * (spec: "the system must never automatically overwrite an existing
     * Ministry Time record" without asking). [PlannerDayRepository.addMinutes]
     * already adds rather than overwrites, so this same call correctly
     * covers both "no existing record" (0 + elapsed = elapsed, i.e. a plain
     * save) and "add to existing record" (existing + elapsed) — the UI only
     * decides *whether* to call this, never how. */
    suspend fun confirmSaveElapsedMinutes(publisherPersonId: String, targetDayMillis: Long, elapsedMinutes: Int) {
        if (elapsedMinutes > 0) {
            plannerDayRepository.addMinutes(publisherPersonId, targetDayMillis, elapsedMinutes)
        }
    }

    /** RESET — discards a RUNNING-or-PAUSED session with nothing saved (spec:
     * distinct from STOP, which always saves). A no-op if nothing is active. */
    fun reset(publisherPersonId: String) {
        viewModelScope.launch {
            val session = sessionRepository.observeRunning(publisherPersonId).first() ?: return@launch
            sessionRepository.delete(session.id)
        }
    }
}
