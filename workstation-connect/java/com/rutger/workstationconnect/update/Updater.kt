// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Rutger505/fastify (MIT, same author).
package com.rutger.workstationconnect.update

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rutger.workstationconnect.AppLog
import com.rutger.workstationconnect.Tether
import com.rutger.workstationconnect.logs.LogUpload
import com.rutger.workstationconnect.logs.LogUploadLogic.Reason
import com.rutger.workstationconnect.ui.HomeActivity
import org.json.JSONObject
import org.kde.kdeconnect.helpers.LifecycleHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update from the CDN (`update.json` written by workstation-connect/publish; version.json is the retired channel of the old com.rutger.workstationconnect app):
 * - Check: app start, every Home resume (throttled, [UpdateLogic.resumeDecision]), every 10 min from LinkService, [UpdateWorker]
 *   every 15 min as backup (any network).
 * - Download + sha256 verify, then install right away via a PackageInstaller session (Rutger: no waiting,
 *   also not for tethering or the app being on screen). The install kills the process: the hotspot is
 *   saved first ([Tether.saveForUpdate]) and restored by the new process, logs are uploaded first.
 * - Silent (self-update, see [canInstallSilently]). Otherwise ("install unknown apps" not granted, Android
 *   asks anyway): Home card + notification, the user taps.
 */
object Updater {
    data class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val size: Long) {
        fun json() = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
            .put("apk", apk).put("sha256", sha256).put("size", size).toString()
        /** For logs: versionName (shown to the user) + versionCode (what the updater compares). */
        val label get() = "$versionName ($versionCode)"
        companion object {
            fun parse(s: String) = JSONObject(s).run {
                Release(getInt("versionCode"), getString("versionName"), getString("apk"), getString("sha256"), getLong("size"))
            }
        }
    }

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val latest: Release) : Status
        data class Downloading(val release: Release, val bytes: Long) : Status
        /** Not installable without a tap (install permission missing, or Android asked). */
        data class NeedsTap(val release: Release) : Status
        data class Installing(val release: Release) : Status
        data class Failed(val message: String, val release: Release?) : Status
    }

    private const val WORKER_INTERVAL_MIN = 15L // WorkManager minimum; LinkService checks every 10 min
    /** A silent install of a version that failed is retried by the periodic check after this. */
    private const val RETRY_FAILED_MS = 60 * 60_000L
    private const val NOTIFICATION_ID = 7703 // LinkService uses 7702, status notification 1
    private const val PREFS = "wsconnect-update"

    private val baseUrl = BuildConfig.UPDATE_URL.trimEnd('/')
    private val worker = Executors.newSingleThreadExecutor()
    private val log = AppLog.tag("update")
    private lateinit var app: Application
    @Volatile private var lastFinished = 0L // elapsedRealtime, 0 = never
    private val resumeQueued = AtomicBoolean(false)
    @Volatile private var failedInstall: Pair<Int, Long>? = null // versionCode, when

    @Volatile var status: Status = Status.Idle; private set

    /** Release shown on Home's update card, if any. */
    val available: Release?
        get() = when (val s = status) {
            is Status.Downloading -> s.release
            is Status.NeedsTap -> s.release
            is Status.Installing -> s.release
            is Status.Failed -> s.release
            else -> null
        }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    fun init(context: Application) {
        app = context
        logVersionChange()
        log.d("init: periodic worker every $WORKER_INTERVAL_MIN min, checking now")
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UpdateWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(WORKER_INTERVAL_MIN, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        worker.execute { check("start") }
    }

    /** LinkService's 10-min tick. */
    fun checkAsync() {
        if (::app.isInitialized) worker.execute { check("tick") } else log.d("tick check skipped: not initialised")
    }

    /** Home resumed: fresh check every time (Home shows the outcome), unless one runs or just finished. */
    fun onResume() {
        if (!::app.isInitialized) return log.d("resume check skipped: not initialised")
        val running = resumeQueued.get() || status is Status.Checking
        when (val d = UpdateLogic.resumeDecision(SystemClock.elapsedRealtime(), lastFinished, running)) {
            UpdateLogic.ResumeDecision.SkipRunning -> log.d("resume check skipped: a check is running")
            is UpdateLogic.ResumeDecision.SkipRecent -> log.d("resume check skipped: last check ${d.agoMs / 1000} s ago")
            UpdateLogic.ResumeDecision.Check -> {
                if (!resumeQueued.compareAndSet(false, true)) return log.d("resume check skipped: already queued")
                log.d("resume check queued")
                worker.execute { try { check("home resumed") } finally { resumeQueued.set(false) } }
            }
        }
    }

    /** Blocking (worker threads only). Fetch update.json; download + verify + install when newer. */
    @Synchronized
    fun check(trigger: String): String = try {
        checkLocked(trigger)
    } finally {
        lastFinished = SystemClock.elapsedRealtime()
    }

    private fun checkLocked(trigger: String): String {
        val busy = status
        if (busy is Status.Downloading || busy is Status.Installing) return describe().also { log.d("check ($trigger) skipped: busy, $it") }
        status = Status.Checking
        val release = try {
            fetchRelease()
        } catch (e: Exception) {
            val msg = "controle: ${e.javaClass.simpleName}: ${e.message}"
            // Every 10 min while offline: log only a new error (each I/W/E line makes the periodic log upload run).
            if ((busy as? Status.Failed)?.message != msg) log.w("check ($trigger) failed", e)
            else log.d("check ($trigger) failed again: ${e.javaClass.simpleName}")
            status = Status.Failed(msg, null)
            return describe()
        }
        if (release.versionCode <= BuildConfig.VERSION_CODE) {
            if ((busy as? Status.UpToDate)?.latest?.versionCode != release.versionCode) log.d("check ($trigger): up to date (latest ${release.label})")
            dir().deleteRecursively()
            prefs().edit().remove("pending").apply()
            abandonSessions()
            status = Status.UpToDate(release)
            return describe()
        }
        if (busy !is Status.NeedsTap && busy !is Status.Failed) log.i("check ($trigger): ${release.label} available")
        try {
            download(release)
        } catch (e: Exception) {
            val msg = "download: ${e.javaClass.simpleName}: ${e.message}"
            if ((busy as? Status.Failed)?.message != msg) log.e("download ${release.label} failed", e)
            else log.d("download ${release.label} failed again: ${e.javaClass.simpleName}")
            status = Status.Failed(msg, release)
            return describe()
        }
        prefs().edit().putString("pending", release.json()).apply()
        val silent = canInstallSilently()
        // Checks run every 10 min: repeat nothing for a release that already waits for a tap or just failed.
        if (!silent && busy is Status.NeedsTap && busy.release == release) {
            log.d("${release.label} still waits for a tap: nothing to do")
            status = busy
            return describe()
        }
        val failed = failedInstall
        if (silent && failed != null && failed.first == release.versionCode && System.currentTimeMillis() - failed.second < RETRY_FAILED_MS) {
            log.d("${release.label} install failed ${(System.currentTimeMillis() - failed.second) / 60_000} min ago: retry after ${RETRY_FAILED_MS / 60_000} min")
            status = busy
            return describe()
        }
        log.i("${release.label} downloaded, silent=$silent")
        if (silent) commit(release, interactive = false) else {
            status = Status.NeedsTap(release)
            notifyNeedsTap(release)
        }
        return describe()
    }

    /** User tapped "Nu installeren". Installs right away. */
    fun installNow() = worker.execute {
        val r = available ?: pending() ?: return@execute log.w("install now: no update available")
        log.i("install now tapped (${r.label})")
        if (!apkFile(r).exists()) { log.i("install now: apk missing, downloading first"); check("install now"); return@execute }
        commit(r, interactive = true)
    }

    @Synchronized
    private fun commit(r: Release, interactive: Boolean) {
        val file = apkFile(r)
        if (!file.exists()) {
            status = Status.Failed("installeren: ${file.name} ontbreekt", r)
            return log.e("commit ${r.label}: ${file.name} missing")
        }
        log.i("committing ${r.label} (${file.length() / 1024} KiB), interactive=$interactive")
        // Point of no return (the install kills the process): remember the hotspot, upload the log so far.
        Tether.saveForUpdate()
        runCatching { LogUpload.uploadNow(Reason.UPDATE, "installing ${r.label}, interactive=$interactive") }
            .onFailure { log.w("log upload before the install failed, installing anyway: ${it.javaClass.simpleName}") }
        try {
            abandonSessions()
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("wsconnect.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS)
                    .putExtra(UpdateReceiver.EXTRA_INTERACTIVE, interactive)
                    .putExtra(UpdateReceiver.EXTRA_VERSION, r.versionCode)
                status = Status.Installing(r)
                log.d("session $id written, committing")
                session.commit(PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            }
        } catch (e: Exception) {
            log.e("commit ${r.label} failed", e)
            status = Status.Failed("installeren: ${e.javaClass.simpleName}: ${e.message}", r)
            failedInstall = r.versionCode to System.currentTimeMillis()
            Tether.clearRestore()
            LogUpload.trigger(Reason.UPDATE)
        }
    }

    /** [UpdateReceiver]: PackageInstaller session result. */
    internal fun onInstallResult(code: Int, message: String?, confirm: Intent?, interactive: Boolean, versionCode: Int) {
        val r = pending()?.takeIf { it.versionCode == versionCode } ?: available
        val label = r?.takeIf { it.versionCode == versionCode }?.label ?: "versionCode $versionCode"
        when (code) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                log.w("$label needs confirmation (interactive=$interactive): $message")
                r?.let { status = Status.NeedsTap(it) } // stays on the card if the dialog is cancelled
                Tether.clearRestore()
                if (interactive && confirm != null && LifecycleHelper.isInForeground) {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    log.d("confirm dialog not opened (interactive=$interactive, foreground=${LifecycleHelper.isInForeground}): notification")
                    r?.let(::notifyNeedsTap)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> log.i("$label installed")
            else -> {
                log.e("$label install failed: status=$code $message")
                status = Status.Failed("installeren mislukt ($code): $message", r)
                failedInstall = versionCode to System.currentTimeMillis()
                Tether.clearRestore()
                LogUpload.trigger(Reason.UPDATE)
            }
        }
    }

    fun describe(): String {
        val info = runCatching { app.packageManager.getInstallSourceInfo(app.packageName) }.onFailure { log.d("install source: $it") }.getOrNull()
        val state = when (val s = status) {
            Status.Idle -> "not checked"
            Status.Checking -> "checking"
            is Status.UpToDate -> "up to date (latest ${s.latest.label})"
            is Status.Downloading -> "downloading ${s.release.label}: ${s.bytes / 1_048_576}/${s.release.size / 1_048_576} MB"
            is Status.NeedsTap -> "${s.release.label} needs a tap"
            is Status.Installing -> "installing ${s.release.label}"
            is Status.Failed -> "FAILED ${s.message}"
        }
        return "${BuildConfig.VERSION_NAME}: $state; installer=${info?.installingPackageName} updateOwner=${info?.updateOwnerPackageName}"
    }

    /**
     * Silent = a self-update (PackageInstallerSession.computeUserActionRequirement: isSelfUpdate +
     * UPDATE_PACKAGES_WITHOUT_USER_ACTION), so also right after an adb/browser install. Needs "install
     * unknown apps" granted and no other update owner (Android 14+, e.g. a store that claimed it).
     */
    private fun canInstallSilently(): Boolean {
        val pm = app.packageManager
        if (!pm.canRequestPackageInstalls()) return false.also { log.d("not silent: install unknown apps not granted") }
        val owner = runCatching { pm.getInstallSourceInfo(app.packageName).updateOwnerPackageName }
            .onFailure { log.w("update owner lookup failed", it) }.getOrNull()
        return (owner == null || owner == app.packageName).also { if (!it) log.d("not silent: update owner is $owner") }
    }

    private fun open(path: String) = (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Cache-Control", "no-cache")
    }

    private fun fetchRelease(): Release {
        val c = open("update.json")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return Release.parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun dir() = File(app.cacheDir, "updates")
    private fun apkFile(r: Release) = File(dir(), "wsconnect-${r.versionCode}-${r.sha256.take(12)}.apk")
    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun pending() = prefs().getString("pending", null)?.let {
        runCatching { Release.parse(it) }.onFailure { e -> log.w("pending release unreadable", e) }.getOrNull()
    }

    private fun abandonSessions() = app.packageManager.packageInstaller.let { i ->
        i.mySessions.forEach { s ->
            log.d("abandoning install session ${s.sessionId}")
            runCatching { i.abandonSession(s.sessionId) }.onFailure { log.w("abandon session ${s.sessionId}", it) }
        }
    }

    /** Downloads to .part, renamed only after the sha256 matches: an existing final file is verified. */
    private fun download(r: Release) {
        val target = apkFile(r)
        if (target.exists()) return log.d("${r.label} already downloaded")
        log.i("downloading ${r.label} (${r.size / 1024} KiB)")
        val t0 = System.currentTimeMillis()
        dir().deleteRecursively()
        dir().mkdirs()
        val part = File(dir(), target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        status = Status.Downloading(r, 0)
        val c = open(r.apk)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode} for ${r.apk}")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        if (total - reported > 512 * 1024) {
                            reported = total
                            status = Status.Downloading(r, total)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != r.sha256.lowercase()) {
            part.delete()
            error("sha256 mismatch: $sha, expected ${r.sha256}")
        }
        if (!part.renameTo(target)) error("rename ${part.name} failed")
        log.i("${r.label} downloaded + sha256 verified in ${System.currentTimeMillis() - t0} ms")
    }

    /** Opens Home (update card), not Android's confirm dialog directly. */
    private fun notifyNeedsTap(r: Release) {
        if (LifecycleHelper.isInForeground) return log.d("needs-tap notification skipped: home card is on screen")
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 2, Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Upstream channel: KdeConnect.onCreate deletes channels it doesn't know.
        runCatching {
            nm.notify(NOTIFICATION_ID, Notification.Builder(app, NotificationHelper.Channels.DEFAULT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update ${r.versionName} klaar, tik om te installeren")
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build())
        }.onSuccess { log.i("needs-tap notification shown (${r.label})") }
            .onFailure { log.e("needs-tap notification failed", it) }
    }

    private fun logVersionChange() {
        val p = prefs()
        val previous = p.getInt("versionCode", -1)
        if (previous == BuildConfig.VERSION_CODE) return
        val previousName = p.getString("versionName", null) // missing before the v<n> scheme
        log.i("now running ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), was ${previousName ?: "?"} ($previous)")
        p.edit().clear().putInt("versionCode", BuildConfig.VERSION_CODE).putString("versionName", BuildConfig.VERSION_NAME).apply()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
