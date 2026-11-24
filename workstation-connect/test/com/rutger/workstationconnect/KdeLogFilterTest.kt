// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import com.rutger.workstationconnect.LogFormat.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KdeLogFilterTest {
    @Test
    fun mapsLevelsAndTags() {
        assertEquals(Level.D, KdeLogFilter.level(2))
        assertEquals(Level.D, KdeLogFilter.level(3))
        assertEquals(Level.I, KdeLogFilter.level(4))
        assertEquals(Level.W, KdeLogFilter.level(5))
        assertEquals(Level.E, KdeLogFilter.level(6))
        assertEquals(Level.E, KdeLogFilter.level(7))
        assertEquals("kde/KDE/Device", KdeLogFilter.tag("KDE/Device"))
        assertEquals("kde/?", KdeLogFilter.tag(null))
    }

    @Test
    fun dropsAndRedactsContent() {
        assertNull(KdeLogFilter.clean("STDOUT", "Line:secret"))
        assertNull(KdeLogFilter.clean("ConnectionMultiplexer", "Received invalid message 'x'"))
        assertNull(KdeLogFilter.clean("ConnectionMultiplexer", "as string: '[1, 2]'"))
        assertEquals("Device: AA Before unserialize (message: <redacted>)",
            KdeLogFilter.clean("BTLinkProvider/Client", "Device: AA Before unserialize (message: '{\"body\":1}')"))
        assertEquals("problem parsing notification extras for <redacted>",
            KdeLogFilter.clean("KDE/NotificationsPlugin", "problem parsing notification extras for Hi Bob, call me"))
        assertEquals("hasUrl: <redacted>", KdeLogFilter.clean("SharePlugin", "hasUrl: https://x/y?token=1"))
        assertEquals("Unable to determine correct outgoing address for sub ID 1. Using <redacted>",
            KdeLogFilter.clean("Sending message", "Unable to determine correct outgoing address for sub ID 1. Using +31612345678"))
        assertEquals("Pair packet", KdeLogFilter.clean("KDE/Device", "Pair packet"))
    }

    @Test
    fun rateLimitsPerTag() {
        val f = KdeLogFilter(maxPerWindow = 2, windowMs = 1000)
        assertEquals(1, f.accept(4, "a", "1", 0).size)
        assertEquals(1, f.accept(4, "a", "2", 10).size)
        assertTrue(f.accept(4, "a", "3", 20).isEmpty())
        assertTrue(f.accept(4, "a", "4", 30).isEmpty())
        assertEquals(1, f.accept(4, "b", "other tag", 40).size)
        val next = f.accept(5, "a", "5", 1000)
        assertEquals(2, next.size)
        assertTrue(next[0].msg.startsWith("… 2 lines of this tag suppressed"))
        assertEquals(KdeLogFilter.Out(Level.W, "kde/a", "5"), next[1])
    }
}
