// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.update

import com.rutger.workstationconnect.update.Updater.Status

/** Pure parts of [Updater] (JVM tests in workstation-connect/test/). */
object UpdateLogic {
    /** Home resume re-checks unless a check finished this recently (rotation/resume spam). */
    const val RESUME_THROTTLE_MS = 15_000L

    sealed interface ResumeDecision {
        data object Check : ResumeDecision
        data object SkipRunning : ResumeDecision
        data class SkipRecent(val agoMs: Long) : ResumeDecision
    }

    /**
     * [nowMs]/[lastFinishedMs] on one monotonic clock (elapsedRealtime), [lastFinishedMs] 0 = never.
     * [running]: a check is queued or in flight.
     */
    fun resumeDecision(nowMs: Long, lastFinishedMs: Long, running: Boolean): ResumeDecision {
        if (running) return ResumeDecision.SkipRunning
        val ago = nowMs - lastFinishedMs
        return if (lastFinishedMs > 0 && ago in 0 until RESUME_THROTTLE_MS) ResumeDecision.SkipRecent(ago) else ResumeDecision.Check
    }

    /** Home header: installed version + outcome of the last check, e.g. `v12 · v13 beschikbaar`. */
    fun versionLine(installedName: String, installedCode: Int, status: Status): String {
        val newer = when (status) {
            is Status.Downloading -> status.release
            is Status.NeedsTap -> status.release
            is Status.Installing -> status.release
            is Status.Failed -> status.release
            is Status.UpToDate -> status.latest
            else -> null
        }?.takeIf { it.versionCode > installedCode }
        val outcome = when {
            newer != null -> "${newer.versionName} beschikbaar"
            status is Status.Checking -> "controleren…"
            status is Status.UpToDate -> "nieuwste versie"
            status is Status.Failed -> "controle mislukt"
            else -> null // Idle: not checked yet
        }
        return if (outcome == null) installedName else "$installedName · $outcome"
    }
}
