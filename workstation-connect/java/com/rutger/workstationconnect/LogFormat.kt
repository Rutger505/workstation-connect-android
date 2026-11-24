// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

/**
 * Pure AppLog line format (JVM unit tests): "yyyy-MM-dd HH:mm:ss.SSS L tag: message".
 * Multi-line messages and stack traces become one line each, all with the same prefix (like logcat), so
 * every line stays greppable and the upload marker (last line) keeps its timestamp.
 */
object LogFormat {
    enum class Level(val letter: Char) { D('D'), I('I'), W('W'), E('E') }

    /** Stack trace lines per entry (causes included). */
    const val MAX_TRACE_LINES = 40

    private val re = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) (?:([DIWE]) ([^\s:]+): )?(.*)$""")

    /** [level]/[tag] null = old format ("ts message", before levels) or a line without timestamp. */
    data class Parsed(val time: String?, val level: Level?, val tag: String?, val msg: String)

    fun parse(line: String): Parsed {
        val m = re.matchEntire(line) ?: return Parsed(null, null, null, line)
        val (time, l, tag, msg) = m.destructured
        return Parsed(time, if (l.isEmpty()) null else Level.valueOf(l), tag.ifEmpty { null }, msg)
    }

    /** Tags: no whitespace or ':' (the parser splits on the first ": "). */
    fun cleanTag(tag: String): String =
        tag.map { if (it.isWhitespace() || it == ':') '_' else it }.joinToString("").ifEmpty { "app" }

    fun lines(time: String, level: Level, tag: String, msg: String, t: Throwable? = null): List<String> {
        val prefix = "$time ${level.letter} ${cleanTag(tag)}: "
        val body = msg.lines() + (t?.let { trace(it) } ?: emptyList())
        return body.map { prefix + it }
    }

    /** "  " + stackTraceToString lines, capped at [MAX_TRACE_LINES]. */
    fun trace(t: Throwable): List<String> {
        val all = t.stackTraceToString().trimEnd().lines()
        val kept = all.take(MAX_TRACE_LINES).map { "  " + it.replace("\t", "  ") }
        return if (all.size > kept.size) kept + "  … ${all.size - kept.size} more trace lines" else kept
    }
}
