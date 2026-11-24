// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import com.rutger.workstationconnect.LogFormat.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {
    private val ts = "2026-11-24 01:02:03.456"

    @Test
    fun formatsAndParses() {
        val l = LogFormat.lines(ts, Level.W, "link", "rfcomm read ended: x")
        assertEquals(listOf("$ts W link: rfcomm read ended: x"), l)
        assertEquals(LogFormat.Parsed(ts, Level.W, "link", "rfcomm read ended: x"), LogFormat.parse(l[0]))
    }

    @Test
    fun multiLineAndTraceKeepThePrefix() {
        val l = LogFormat.lines(ts, Level.E, "my tag", "a\nb", IllegalStateException("boom"))
        assertEquals("$ts E my_tag: a", l[0])
        assertEquals("$ts E my_tag: b", l[1])
        assertEquals("$ts E my_tag:   java.lang.IllegalStateException: boom", l[2])
        assertTrue(l.drop(3).all { it.startsWith("$ts E my_tag:     at ") || it.startsWith("$ts E my_tag:   …") })
        assertTrue(l.size <= 3 + LogFormat.MAX_TRACE_LINES + 1)
    }

    @Test
    fun parsesOldFormatAndSeparators() {
        val old = LogFormat.parse("$ts laptop: on (x)")
        assertEquals(LogFormat.Parsed(ts, null, null, "laptop: on (x)"), old)
        val raw = LogFormat.parse("[… 12 older bytes cut]")
        assertNull(raw.time)
        assertEquals("[… 12 older bytes cut]", raw.msg)
    }
}
