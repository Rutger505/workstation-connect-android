// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLogicTest {
    @Test
    fun addsHostToUpstreamsList() {
        assertEquals("100.64.0.2", PairingLogic.addHost("", "100.64.0.2", ","))
        assertEquals("10.0.0.1,100.64.0.2", PairingLogic.addHost("100.64.0.2", "10.0.0.1", ","))
        assertNull(PairingLogic.addHost("10.0.0.1,100.64.0.2", "100.64.0.2", ","))
    }

    @Test
    fun secondsLeftRoundsUpAndStopsAtZero() {
        assertEquals(30, PairingLogic.secondsLeft(30_000, 0))
        assertEquals(1, PairingLogic.secondsLeft(30_000, 29_500))
        assertEquals(0, PairingLogic.secondsLeft(30_000, 31_000))
    }
}
