// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import com.rutger.workstationconnect.LogFormat.Level

/**
 * Pure part of [KdeLog] (JVM unit tests): which upstream KDE Connect log lines go into AppLog, redacted how,
 * and a per-tag rate limit so a chatty upstream loop can't push our own lines out of the 2000-line buffer.
 *
 * Content rules (audited against upstream's android.util.Log calls; recheck on upstream merges):
 * run-command output, raw Bluetooth multiplexer data and identity packet dumps are dropped or redacted,
 * shared URLs, notification ticker text and SMS phone numbers are redacted.
 */
class KdeLogFilter(private val maxPerWindow: Int = MAX_PER_WINDOW, private val windowMs: Long = WINDOW_MS) {
    /** AppLog tag prefix of upstream lines. */
    companion object {
        const val PREFIX = "kde/"
        const val MAX_PER_WINDOW = 30
        const val WINDOW_MS = 60_000L

        /** Upstream tags whose every line is content (RunCommandPlugin's command stdout/stderr). */
        private val droppedTags = setOf("STDOUT", "STDERR")

        /** Lines that dump raw packet data (ConnectionMultiplexer). */
        private val droppedMessages = listOf(
            Regex("""^Received invalid message '"""),
            Regex("""^'data_buffer:"""),
            Regex("""^as string: '"""),
        )

        private val redactions = listOf(
            // BluetoothLinkProvider: whole identity packet
            Regex("""\(message: '.*'\)""", RegexOption.DOT_MATCHES_ALL) to "(message: <redacted>)",
            // NotificationsPlugin: ticker text = notification content
            Regex("""(notification extras for ).*""", RegexOption.DOT_MATCHES_ALL) to "$1<redacted>",
            // SharePlugin: shared URL
            Regex("""^(hasUrl: ).*""", RegexOption.DOT_MATCHES_ALL) to "$1<redacted>",
            // SmsMmsUtils: phone number
            Regex("""(outgoing address for sub ID \S+ Using ).*""", RegexOption.DOT_MATCHES_ALL) to "$1<redacted>",
        )

        /** logcat priority (android.util.Log.VERBOSE=2 … ASSERT=7) → AppLog level. */
        fun level(priority: Int): Level = when {
            priority <= 3 -> Level.D // VERBOSE, DEBUG
            priority == 4 -> Level.I
            priority == 5 -> Level.W
            else -> Level.E // ERROR, ASSERT (wtf)
        }

        /** "KDE/Device" → "kde/KDE/Device"; null/blank tag → "kde/?". */
        fun tag(tag: String?): String = PREFIX + (tag?.takeIf { it.isNotBlank() } ?: "?")

        /** Redacted message, or null = drop the line. */
        fun clean(tag: String?, msg: String): String? {
            if (tag in droppedTags) return null
            if (droppedMessages.any { it.containsMatchIn(msg) }) return null
            return redactions.fold(msg) { m, (re, repl) -> re.replace(m, repl) }
        }
    }

    private class Window(var start: Long, var count: Int = 0, var suppressed: Int = 0)
    private val windows = HashMap<String, Window>()

    /** A line that passes the rules, after the rate limit. */
    data class Out(val level: Level, val tag: String, val msg: String)

    /**
     * Lines to write for one upstream log call (empty = dropped): at most [maxPerWindow] per tag per
     * [windowMs]; the first line of the next window is preceded by a "… N lines suppressed" note.
     */
    @Synchronized
    fun accept(priority: Int, tag: String?, msg: String, nowMs: Long): List<Out> {
        val cleaned = clean(tag, msg) ?: return emptyList()
        val t = tag(tag)
        val w = windows.getOrPut(t) { Window(nowMs) }
        val out = ArrayList<Out>(2)
        if (nowMs - w.start >= windowMs) {
            if (w.suppressed > 0) out += Out(Level.I, t, "… ${w.suppressed} lines of this tag suppressed (rate limit $maxPerWindow/${windowMs / 1000}s)")
            w.start = nowMs; w.count = 0; w.suppressed = 0
        }
        if (w.count >= maxPerWindow) {
            w.suppressed++
            return out
        }
        w.count++
        out += Out(level(priority), t, cleaned)
        return out
    }
}
