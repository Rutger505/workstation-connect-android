// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.logs

import android.content.Context
import android.os.Build
import com.rutger.workstationconnect.AppLog
import com.rutger.workstationconnect.logs.LogUploadLogic.Reason
import org.kde.kdeconnect_tp.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * AppLog upload to a private S3 bucket (SeaweedFS on cdn.rutgerpronk.com, path-style, SigV4), like the
 * laptop's logs.rs. Only lines since the last successful upload ([KEY_MARKER] = its last line), newest
 * [LogUploadLogic.MAX_BODY]. Triggers within [DEBOUNCE_MS] become one upload, one upload at a time on
 * [exec]. Failure: marker stays, reasons retried with the next trigger. Key: write-only, injected at
 * build time (wsconnect.gradle); empty = upload off.
 */
object LogUpload {
    private const val HOST = "cdn.rutgerpronk.com"
    private const val BUCKET = "wsconnect-logs"
    private const val REGION = "us-east-1"
    private const val DEBOUNCE_MS = 5_000L
    private const val TIMEOUT_MS = 30_000
    private const val PREFS = "wsconnect-logs"
    private const val KEY_MARKER = "marker"
    private const val KEY_CRASHED = "crashed"

    private val creds = SigV4.Credentials(BuildConfig.LOGS_ACCESS_KEY, BuildConfig.LOGS_SECRET_KEY)
    val enabled = creds.access.isNotEmpty() && creds.secret.isNotEmpty()

    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "wsconnect-logupload").apply { isDaemon = true } }
    private val pending = mutableListOf<Reason>() // guarded by itself
    private var scheduled: ScheduledFuture<*>? = null // guarded by pending
    private lateinit var app: Context

    /** Main process, once (WsConnect.init, after AppLog.init). */
    fun init(context: Context) {
        app = context.applicationContext
        installCrashHandler()
        if (!enabled) return AppLog.i("log upload: off (no key at build time)")
        if (prefs().getBoolean(KEY_CRASHED, false)) {
            prefs().edit().remove(KEY_CRASHED).apply()
            trigger(Reason.CRASH)
        }
        trigger(Reason.START)
    }

    /** Upload soon (debounced). Periodic only uploads when there are new lines. Any thread. */
    fun trigger(r: Reason) {
        if (!enabled || !::app.isInitialized) return
        synchronized(pending) {
            LogUploadLogic.merge(pending, r)
            if (scheduled == null) scheduled = exec.schedule(::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Upload right away, blocking (not on the main thread), queued behind a running upload. Returns the
     * object key, null when skipped; throws on failure. Used before an update install and by "Logs uploaden".
     */
    fun uploadNow(r: Reason, extra: String? = null): String? {
        if (!enabled || !::app.isInitialized) error("upload uit: geen sleutel in deze build")
        val reasons = synchronized(pending) { pending.toMutableList().also { LogUploadLogic.merge(it, r); it.remove(r); it.add(0, r) } }
        return try {
            exec.submit<String?> { attempt(reasons, extra, rethrow = true) }.get(2L * TIMEOUT_MS + 10_000, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun flush() {
        val reasons = synchronized(pending) {
            scheduled = null
            pending.toList().also { pending.clear() }
        }
        if (reasons.isNotEmpty()) attempt(reasons, null, rethrow = false)
    }

    /** exec thread only. */
    private fun attempt(reasons: List<Reason>, extra: String?, rethrow: Boolean): String? = try {
        upload(reasons, extra).also {
            // pending reasons that rode along are done
            synchronized(pending) { pending.removeAll(reasons) }
        }
    } catch (e: Exception) {
        AppLog.i("${LogUploadLogic.OWN_PREFIX}${reasons.joinToString { it.key }} failed: ${e.toString().replace(Regex("\\s+"), " ")}")
        synchronized(pending) { reasons.forEach { if (it != Reason.MANUAL) LogUploadLogic.merge(pending, it) } } // next trigger retries
        if (rethrow) throw e
        null
    }

    private fun upload(reasons: List<Reason>, extra: String?): String? {
        val all = AppLog.fullLines()
        val marker = prefs().getString(KEY_MARKER, null)
        val (lines, found) = LogUploadLogic.since(all, marker)
        if (reasons == listOf(Reason.PERIODIC) && !LogUploadLogic.hasNewLines(lines)) return null
        val now = System.currentTimeMillis()
        val head = buildString {
            append("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            append("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
            append("reason: ${reasons.joinToString { it.key }}\n")
            append("time: ${LogUploadLogic.isoUtc(now)}\n")
            append("log since: ${if (found) "last upload" else "start of log file"}\n")
            if (extra != null) append("\n").append(extra.trimEnd()).append("\n")
            append("\n===== app log (local time) =====\n")
        }
        val logs = lines.joinToString("") { it + "\n" }
        val room = (LogUploadLogic.MAX_BODY - head.toByteArray().size).coerceAtLeast(64 * 1024)
        val body = (head + LogUploadLogic.tail(logs, room)).toByteArray()
        val key = LogUploadLogic.objectKey(Build.MODEL, reasons.first(), now)
        put(key, body, now)
        all.lastOrNull()?.let { prefs().edit().putString(KEY_MARKER, it).apply() }
        AppLog.i("${LogUploadLogic.OWN_PREFIX}$key (${body.size} bytes)")
        return key
    }

    private fun put(key: String, body: ByteArray, now: Long) {
        val uri = SigV4.encodePath("/$BUCKET/$key")
        val date = LogUploadLogic.amzDate(now)
        val hash = SigV4.sha256Hex(body)
        val ctype = "text/plain; charset=utf-8"
        val auth = SigV4.authorization(
            creds, REGION, "s3", date, method = "PUT", uri = uri, query = "",
            headers = listOf("content-type" to ctype, "host" to HOST, "x-amz-content-sha256" to hash, "x-amz-date" to date),
            payloadSha256 = hash,
        )
        val c = URL("https://$HOST$uri").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", ctype)
            c.setRequestProperty("x-amz-content-sha256", hash)
            c.setRequestProperty("x-amz-date", date)
            c.setRequestProperty("Authorization", auth)
            c.setRequestProperty("User-Agent", "wsconnect-android/${BuildConfig.VERSION_CODE}")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val text = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                error("PUT $key: HTTP $code: ${text.take(300)}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Crash: stack trace into AppLog synchronously, flag for a "crash" upload on the next start. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                prefs().edit().putBoolean(KEY_CRASHED, true).commit()
                val trace = generateSequence(e) { it.cause }.take(5).flatMapIndexed { i, x ->
                    listOf((if (i == 0) "CRASH in thread ${t.name}: " else "  caused by: ") + x) +
                        x.stackTrace.take(25).map { "    at $it" }
                }.toList()
                AppLog.crash(trace)
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
