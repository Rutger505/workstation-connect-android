// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.update

import com.rutger.workstationconnect.update.UpdateLogic.ResumeDecision
import com.rutger.workstationconnect.update.Updater.Release
import com.rutger.workstationconnect.update.Updater.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateLogicTest {
    private fun rel(code: Int) = Release(10000 + code, "v$code", "a.apk", "00", 1)
    private fun line(s: Status) = UpdateLogic.versionLine("v12", 10012, s)

    @Test
    fun resumeChecksWhenNeverChecked() = assertEquals(ResumeDecision.Check, UpdateLogic.resumeDecision(5_000, 0, false))

    @Test
    fun resumeSkipsWhileRunning() = assertEquals(ResumeDecision.SkipRunning, UpdateLogic.resumeDecision(100_000, 0, true))

    @Test
    fun resumeThrottlesRecentCheck() {
        assertEquals(ResumeDecision.SkipRecent(14_999), UpdateLogic.resumeDecision(115_000, 100_001, false))
        assertEquals(ResumeDecision.Check, UpdateLogic.resumeDecision(115_000, 100_000, false))
    }

    @Test
    fun manualBypassesThrottleButWaitsForRunning() {
        assertEquals(ResumeDecision.Check, UpdateLogic.resumeDecision(115_000, 114_000, false, force = true))
        assertEquals(ResumeDecision.SkipRunning, UpdateLogic.resumeDecision(115_000, 114_000, true, force = true))
    }

    @Test
    fun refreshSpinnerDoneWhenIdleOrCapped() {
        assertEquals(true, UpdateLogic.refreshDone(false, 0))
        assertEquals(false, UpdateLogic.refreshDone(true, 9_999))
        assertEquals(true, UpdateLogic.refreshDone(true, 10_000))
    }

    @Test
    fun versionLineOutcomes() {
        assertEquals("v12", line(Status.Idle))
        assertEquals("v12 · controleren…", line(Status.Checking))
        assertEquals("v12 · nieuwste versie", line(Status.UpToDate(rel(12))))
        assertEquals("v12 · v13 beschikbaar", line(Status.UpToDate(rel(13)))) // can't happen today, still honest
        assertEquals("v12 · controle mislukt", line(Status.Failed("controle: x", null)))
        assertEquals("v12 · v13 beschikbaar", line(Status.Failed("download: x", rel(13))))
        assertEquals("v12 · v13 beschikbaar", line(Status.Downloading(rel(13), 0)))
        assertEquals("v12 · v13 beschikbaar", line(Status.NeedsTap(rel(13))))
        assertEquals("v12 · v13 beschikbaar", line(Status.Installing(rel(13))))
    }
}
