// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import android.content.Context
import android.os.Build
import android.util.Log
import org.kde.kdeconnect_tp.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * App log: mirrored to logcat, kept in memory (last [MAX] lines, shown in LogActivity) and appended to
 * files/wsconnect.log (rotated to .1 at [ROTATE_BYTES], so ~2 × that on disk) by one background thread.
 * Survives process death; [init] reloads the tail and writes a "--- app start ---" separator.
 * Line format: "yyyy-MM-dd HH:mm:ss.SSS message". Before [init] (or in the Shizuku helper process) memory only.
 */
object AppLog {
    private const val MAX = 2000
    private const val ROTATE_BYTES = 256 * 1024L
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "wsconnect-log").apply { isDaemon = true } }
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT) }
    @Volatile private var file: File? = null
    private var out: FileOutputStream? = null // io thread only

    /** Main process, once (WsConnect.init). */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "wsconnect.log")
        val old = synchronized(lines) {
            val loaded = (readLines(rotated(f)) + readLines(f)).takeLast(MAX)
            val early = lines.toList() // logged before init: keep after the reloaded history
            lines.clear()
            lines.addAll(loaded)
            file = f
            early
        }
        i("--- app start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
            "Android ${Build.VERSION.RELEASE} ---")
        old.forEach { append(it) }
    }

    fun i(msg: String) {
        Log.i("wsconnect", msg)
        append("${fmt.get()!!.format(Date())} $msg")
    }

    private fun append(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        val f = file
        if (f != null) io.execute { write(f, line) }
        listeners.forEach { it() }
    }

    private fun write(f: File, line: String) = runCatching {
        if (f.length() > ROTATE_BYTES) {
            out?.close(); out = null
            f.renameTo(rotated(f))
        }
        val o = out ?: FileOutputStream(f, true).also { out = it }
        o.write((line + "\n").toByteArray())
    }.onFailure { Log.w("wsconnect", "log write failed: $it"); runCatching { out?.close() }; out = null }

    private fun rotated(f: File) = File(f.parentFile, f.name + ".1")
    private fun readLines(f: File) = runCatching { if (f.exists()) f.readLines() else emptyList() }.getOrDefault(emptyList())

    /** In-memory lines (the tail shown on screen). */
    fun lines(): List<String> = synchronized(lines) { lines.toList() }
    fun text(): String = lines().joinToString("\n")

    /** Everything on disk (both files) after pending writes; blocks, call off the main thread. */
    fun fullText(): String = fullLines().joinToString("\n")
    fun fullLines(): List<String> {
        val f = file ?: return lines()
        return io.submit<List<String>> { readLines(rotated(f)) + readLines(f) }.get()
    }

    /**
     * Uncaught exception (crashing thread): [msgs] must hit the disk before the process dies, so write
     * them through the io thread and wait (pending lines first), else append directly.
     */
    fun crash(msgs: List<String>) {
        val now = fmt.get()!!.format(Date())
        val stamped = msgs.map { "$now $it" }
        msgs.forEach { Log.e("wsconnect", it) }
        synchronized(lines) { lines.addAll(stamped) }
        val f = file ?: return
        val viaIo = runCatching {
            io.submit { stamped.forEach { write(f, it) }; out?.flush() }.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }.isSuccess
        if (!viaIo) runCatching { FileOutputStream(f, true).use { o -> stamped.forEach { o.write((it + "\n").toByteArray()) } } }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        file?.let { f -> io.execute { runCatching { out?.close() }; out = null; f.delete(); rotated(f).delete() } }
        listeners.forEach { it() }
    }

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
}
