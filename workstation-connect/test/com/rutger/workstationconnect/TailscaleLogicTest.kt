// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleLogicTest {
    private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun tailscaleRangeIs100_64Slash10() {
        assertTrue(TailscaleLogic.isTailscale(ip(100, 64, 0, 1)))
        assertTrue(TailscaleLogic.isTailscale(ip(100, 127, 255, 254)))
        assertFalse(TailscaleLogic.isTailscale(ip(100, 63, 0, 1)))
        assertFalse(TailscaleLogic.isTailscale(ip(100, 128, 0, 1)))
        assertFalse(TailscaleLogic.isTailscale(ip(192, 168, 1, 2)))
        assertFalse(TailscaleLogic.isTailscale(ip(100, 64, 0)))
    }

    @Test
    fun hostIsDottedQuadAndZeroIsNone() {
        assertEquals("100.101.200.3", TailscaleLogic.host(ip(100, 101, 200, 3)))
        assertNull(TailscaleLogic.host(ip(0, 0, 0, 0)))
        assertNull(TailscaleLogic.host(ip(1, 2, 3)))
    }

    @Test
    fun updateSetsReplacesAndRemovesPerLaptop() {
        val a = TailscaleLogic.update("", "AA:01", "100.64.0.1")!!
        assertEquals("AA:01=100.64.0.1", a)
        assertNull(TailscaleLogic.update(a, "AA:01", "100.64.0.1"))
        val b = TailscaleLogic.update(a, "AA:00", "100.64.0.9")!!
        assertEquals(mapOf("AA:00" to "100.64.0.9", "AA:01" to "100.64.0.1"), TailscaleLogic.parse(b))
        assertEquals("AA:00=100.64.0.9;AA:01=100.64.0.2", TailscaleLogic.update(b, "AA:01", "100.64.0.2"))
        assertEquals("AA:00=100.64.0.9", TailscaleLogic.update(b, "AA:01", null))
        assertNull(TailscaleLogic.update(b, "AA:02", null))
    }

    @Test
    fun parseDropsMalformed() {
        assertEquals(mapOf("AA:01" to "100.64.0.1"), TailscaleLogic.parse("junk;=1.2.3.4;AA:02=;AA:01=100.64.0.1"))
    }

    @Test
    fun stateStringShowsTailscaleIp() {
        assertEquals("flags=none battery=80 signal=3 v6 ts=100.64.0.2",
            Proto.stateString(ip(0, 80, 3, 6, 100, 64, 0, 2)))
        assertEquals("flags=none battery=80 signal=3 v6 ts=none", Proto.stateString(ip(0, 80, 3, 6, 0, 0, 0, 0)))
    }
}
