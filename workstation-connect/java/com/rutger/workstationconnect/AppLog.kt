// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

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
import java.util.concurrent.TimeUnit
import com.rutger.workstationconnect.LogFormat.Level

/**
 * App log: mirrored to logcat (tag "wsc.<tag>", matching priority), kept in memory (last [MAX] lines, shown in
 * LogActivity) and appended to files/wsconnect.log (rotated to .1 at [ROTATE_BYTES], so ~2 × that on disk) by one
 * background thread. Survives process death; [init] reloads the tail and writes a "--- app start ---" separator.
 * Line format: [LogFormat] ("yyyy-MM-dd HH:mm:ss.SSS L tag: message"); all levels go to the file and the upload.
 * Before [init] (and in the Shizuku helper process) memory only: the helper hands its lines over via [take]/[forward].
 *
 * Use: `private val log = AppLog.tag("link")`, then `log.d/i/w/e(msg[, throwable])`. Never log secrets or
 * private content (clipboard/notification text, keys, signed URLs).
 */
object AppLog {
    /** Per-component logger. */
    class Logger internal constructor(val tag: String) {
        fun d(msg: String, t: Throwable? = null) = log(Level.D, tag, msg, t)
        fun i(msg: String, t: Throwable? = null) = log(Level.I, tag, msg, t)
        fun w(msg: String, t: Throwable? = null) = log(Level.W, tag, msg, t)
        fun e(msg: String, t: Throwable? = null) = log(Level.E, tag, msg, t)
    }

    fun tag(tag: String) = Logger(LogFormat.cleanTag(tag))

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
        log(Level.I, "app", "--- app start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
            "Android ${Build.VERSION.RELEASE} ---")
        append(old)
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = log(Level.D, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = log(Level.I, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.W, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.E, tag, msg, t)

    fun log(level: Level, tag: String, msg: String, t: Throwable? = null) {
        val clean = LogFormat.cleanTag(tag)
        runCatching { Log.println(priority(level), "wsc.$clean", if (t == null) msg else msg + "\n" + t.stackTraceToString()) }
        append(LogFormat.lines(now(), level, clean, msg, t))
    }

    private fun priority(level: Level) = when (level) {
        Level.D -> Log.DEBUG
        Level.I -> Log.INFO
        Level.W -> Log.WARN
        Level.E -> Log.ERROR
    }

    private fun now() = fmt.get()!!.format(Date())

    private fun append(new: List<String>) {
        if (new.isEmpty()) return
        synchronized(lines) {
            lines.addAll(new)
            while (lines.size > MAX) lines.removeFirst()
        }
        val f = file
        if (f != null) io.execute { new.forEach { write(f, it) } }
        listeners.forEach { it() }
    }

    /** Helper process: hands over (and drops) the buffered lines, so the main process can [forward] them. */
    fun take(): List<String> = synchronized(lines) { lines.toList().also { lines.clear() } }

    /** Main process: lines from the helper process (already formatted + on its logcat), appended as-is. */
    fun forward(helperLines: List<String>) = append(helperLines)

    private fun write(f: File, line: String) = runCatching {
        if (f.length() > ROTATE_BYTES) {
            out?.close(); out = null
            f.renameTo(rotated(f))
        }
        val o = out ?: FileOutputStream(f, true).also { out = it }
        o.write((line + "\n").toByteArray())
    }.onFailure { Log.w("wsc.applog", "log write failed", it); runCatching { out?.close() }; out = null }

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
     * Uncaught exception (crashing thread): the trace must hit the disk before the process dies, so write
     * it through the io thread and wait (pending lines first), else append directly.
     */
    fun crash(thread: String, e: Throwable) {
        val msg = "CRASH in thread $thread"
        runCatching { Log.e("wsc.crash", msg, e) }
        val stamped = LogFormat.lines(now(), Level.E, "crash", msg, e)
        synchronized(lines) { lines.addAll(stamped) }
        val f = file ?: return
        val viaIo = runCatching {
            io.submit { stamped.forEach { write(f, it) }; out?.flush() }.get(2, TimeUnit.SECONDS)
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
