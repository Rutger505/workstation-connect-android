// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory log shown in the app (copyable), mirrored to logcat. */
object AppLog {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun i(msg: String) {
        Log.i("wsconnect", msg)
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        synchronized(lines) {
            lines.addLast("$ts $msg")
            while (lines.size > MAX) lines.removeFirst()
        }
        listeners.forEach { it() }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
    fun clear() { synchronized(lines) { lines.clear() }; listeners.forEach { it() } }
    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
}
