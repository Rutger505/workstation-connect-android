// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.util.Log

/**
 * Upstream KDE Connect's logging, into AppLog. `workstation-connect.gradle` rewrites every
 * `android.util.Log.{v,d,i,w,e,wtf,println}` call in `org.kde.kdeconnect.*` classes at build time (AGP ASM
 * instrumentation) to the same-signature method here: upstream source stays untouched. Each call still goes
 * to logcat unchanged, then through [KdeLogFilter] (content redaction, rate limit) into AppLog as
 * "kde/<tag>" (V/D → d, I → i, W → w, E/wtf → e), without a second logcat line.
 *
 * No logcat reader on purpose: with READ_LOGS granted (clipboard trick) every logcat start asks the
 * system for consent; in the background that's auto-declined and cached 60 s, which would break
 * ClipboardListener's own logcat read.
 */
@Suppress("unused") // called from rewritten upstream bytecode
object KdeLog {
    private val filter = KdeLogFilter()

    private fun forward(priority: Int, tag: String?, msg: String?, t: Throwable?) {
        try {
            for (o in filter.accept(priority, tag, msg ?: "null", System.currentTimeMillis())) {
                AppLog.mirror(o.level, o.tag, o.msg, t)
            }
        } catch (e: Throwable) {
            // never break upstream's code path over our copy
            Log.w("wsc.kdelog", "forwarding an upstream log line failed", e)
        }
    }

    private inline fun call(priority: Int, tag: String?, msg: String?, t: Throwable?, logcat: () -> Int): Int =
        logcat().also { forward(priority, tag, msg, t) }

    @JvmStatic fun v(tag: String?, msg: String?) = call(Log.VERBOSE, tag, msg, null) { Log.v(tag, msg ?: "null") }
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?) = call(Log.VERBOSE, tag, msg, tr) { Log.v(tag, msg, tr) }
    @JvmStatic fun d(tag: String?, msg: String?) = call(Log.DEBUG, tag, msg, null) { Log.d(tag, msg ?: "null") }
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?) = call(Log.DEBUG, tag, msg, tr) { Log.d(tag, msg, tr) }
    @JvmStatic fun i(tag: String?, msg: String?) = call(Log.INFO, tag, msg, null) { Log.i(tag, msg ?: "null") }
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?) = call(Log.INFO, tag, msg, tr) { Log.i(tag, msg, tr) }
    @JvmStatic fun w(tag: String?, msg: String?) = call(Log.WARN, tag, msg, null) { Log.w(tag, msg ?: "null") }
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?) = call(Log.WARN, tag, msg, tr) { Log.w(tag, msg, tr) }
    @JvmStatic fun w(tag: String?, tr: Throwable?) = call(Log.WARN, tag, tr?.toString(), tr) { Log.w(tag, tr) }
    @JvmStatic fun e(tag: String?, msg: String?) = call(Log.ERROR, tag, msg, null) { Log.e(tag, msg ?: "null") }
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?) = call(Log.ERROR, tag, msg, tr) { Log.e(tag, msg, tr) }
    @JvmStatic fun wtf(tag: String?, msg: String?) = call(Log.ASSERT, tag, msg, null) { Log.wtf(tag, msg) }
    @JvmStatic fun wtf(tag: String?, tr: Throwable) = call(Log.ASSERT, tag, tr.toString(), tr) { Log.wtf(tag, tr) }
    @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?) = call(Log.ASSERT, tag, msg, tr) { Log.wtf(tag, msg, tr) }
    @JvmStatic fun println(priority: Int, tag: String?, msg: String?) = call(priority, tag, msg, null) { Log.println(priority, tag, msg ?: "null") }
}
