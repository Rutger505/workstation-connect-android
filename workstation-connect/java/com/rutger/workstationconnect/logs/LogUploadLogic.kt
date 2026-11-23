// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.logs

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Pure parts of [LogUpload] (JVM unit tests): reasons, object keys, marker, body cap. */
object LogUploadLogic {
    enum class Reason(val key: String) {
        START("start"), LINK_CONNECT("link-connect"), LINK_LOST("link-lost"),
        HOTSPOT_ON("hotspot-on"), HOTSPOT_FAIL("hotspot-fail"), UPDATE("update"),
        CRASH("crash"), PERIODIC("periodic"), MANUAL("manual"),
    }

    /** Body cap: the newest lines win. */
    const val MAX_BODY = 1_000_000

    /** Our own upload lines don't count as "new" (else every periodic upload causes the next one). */
    const val OWN_PREFIX = "log upload: "

    private val keyTime = DateTimeFormatter.ofPattern("yyyy-MM-dd/HHmmss").withZone(ZoneOffset.UTC)
    private val amz = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun sanitize(s: String): String =
        s.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_.") it else '_' }.joinToString("").ifEmpty { "unknown" }

    /** phone/<model>/<YYYY-MM-DD>/<HHMMSS>-<reason>.log (UTC) */
    fun objectKey(model: String, reason: Reason, epochMs: Long): String =
        "phone/${sanitize(model)}/${keyTime.format(Instant.ofEpochMilli(epochMs))}-${reason.key}.log"

    fun amzDate(epochMs: Long): String = amz.format(Instant.ofEpochMilli(epochMs))
    fun isoUtc(epochMs: Long): String = iso.format(Instant.ofEpochMilli(epochMs))

    /**
     * Lines after the marker (= the last line of the previous successful upload, timestamped so
     * effectively unique). Marker null or gone (log rotated/cleared): everything, found = false.
     */
    fun since(lines: List<String>, marker: String?): Pair<List<String>, Boolean> {
        if (marker == null) return lines to false
        val i = lines.lastIndexOf(marker)
        return if (i < 0) lines to false else lines.subList(i + 1, lines.size) to true
    }

    /** Any line that isn't our own upload bookkeeping. Line format: "yyyy-MM-dd HH:mm:ss.SSS message". */
    fun hasNewLines(lines: List<String>) = lines.any { it.length < 24 || !it.substring(24).startsWith(OWN_PREFIX) }

    /** Keeps the newest [max] bytes (UTF-8), cut at a line start. */
    fun tail(text: String, max: Int): String {
        val bytes = text.toByteArray()
        if (bytes.size <= max) return text
        var start = bytes.size - max
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        start = (start + 1).coerceAtMost(bytes.size)
        return "[… $start older bytes cut]\n" + String(bytes, start, bytes.size - start)
    }

    /** Merges a reason into the pending list: periodic only when alone, no duplicates. */
    fun merge(pending: MutableList<Reason>, r: Reason) {
        if (r == Reason.PERIODIC && pending.isNotEmpty()) return
        pending.remove(Reason.PERIODIC)
        if (r !in pending) pending.add(r)
    }
}
