// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Rutger505/fastify (MIT, same author).
package com.rutger.wsconnect.update

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rutger.wsconnect.AppLog
import com.rutger.wsconnect.Tether
import com.rutger.wsconnect.ui.HomeActivity
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

/**
 * Self-update from the CDN (`version.json` written by wsconnect/publish):
 * - Check: app start, Home resume (max every 30 min), [UpdateWorker] every 6 h on any network.
 * - Download + sha256 verify, then install via a PackageInstaller session.
 * - Silent (self-update, see [canInstallSilently]): only when [tetherBusy] is false (an update kills the process and with
 *   it the hotspot/BT tethering the laptop may be using), and on Android 14+ after
 *   [PackageInstaller.waitForInstallConstraints] (app not top-visible, not in a call). The session is
 *   committed only once both hold; a tether change retriggers the deferred install.
 * - Otherwise ("install unknown apps" not granted, Android asks anyway): Home card + notification,
 *   the user taps (also guarded, see HomeActivity).
 */
object Updater {
    data class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val size: Long) {
        fun json() = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
            .put("apk", apk).put("sha256", sha256).put("size", size).toString()
        companion object {
            fun parse(s: String) = JSONObject(s).run {
                Release(getInt("versionCode"), getString("versionName"), getString("apk"), getString("sha256"), getLong("size"))
            }
        }
    }

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val latest: Int) : Status
        data class Downloading(val release: Release, val bytes: Long) : Status
        /**
         * Silent install pending: waits for tethering to stop ([tether]), else until the app is not in use:
         * Android's install constraints ([byAndroid], it calls back) or our own check ([quietNow]).
         */
        data class Waiting(val release: Release, val tether: Boolean, val byAndroid: Boolean = false) : Status
        /** Not installable without a tap (install permission missing, or Android asked). */
        data class NeedsTap(val release: Release) : Status
        data class Installing(val release: Release) : Status
        data class Failed(val message: String, val release: Release?) : Status
    }

    private const val CHECK_INTERVAL_HOURS = 6L
    private const val RESUME_CHECK_MS = 30 * 60_000L
    private const val CONSTRAINT_TIMEOUT_MS = 24 * 60 * 60_000L
    private const val NOTIFICATION_ID = 7703 // LinkService uses 7701/7702
    private const val PREFS = "wsconnect-update"

    private val baseUrl = BuildConfig.UPDATE_URL.trimEnd('/')
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var app: Application
    @Volatile private var lastCheck = 0L

    @Volatile var status: Status = Status.Idle; private set

    /** Laptop may be using the phone's internet: never let an install kill the process now. */
    val tetherBusy: Boolean get() = Tether.wanted || Tether.hotspotOn

    /** Release shown on Home's update card, if any. */
    val available: Release?
        get() = when (val s = status) {
            is Status.Downloading -> s.release
            is Status.Waiting -> s.release
            is Status.NeedsTap -> s.release
            is Status.Installing -> s.release
            is Status.Failed -> s.release
            else -> null
        }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    fun init(context: Application) {
        app = context
        logVersionChange()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UpdateWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        // Re-read status on the worker: listeners fire in bursts, one reschedule is enough.
        Tether.listen {
            if ((status as? Status.Waiting)?.tether == true && !tetherBusy) worker.execute {
                val s = status
                if (s is Status.Waiting && s.tether && !tetherBusy) scheduleSilent(s.release)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { // app left the screen: retry our own constraint check
                worker.execute {
                    val s = status
                    if (s is Status.Waiting && !s.tether && !s.byAndroid) scheduleSilent(s.release)
                }
            }
        })
        worker.execute { check() }
    }

    /** Home resumed: re-check when the last check is stale. */
    fun onResume() {
        if (System.currentTimeMillis() - lastCheck > RESUME_CHECK_MS) worker.execute { check() }
    }

    /** Blocking (worker threads only). Fetch version.json; download + verify + install when newer. */
    @Synchronized
    fun check(): String {
        lastCheck = System.currentTimeMillis()
        val busy = status
        if (busy is Status.Downloading || busy is Status.Installing) return describe()
        status = Status.Checking
        val release = try {
            fetchRelease()
        } catch (e: Exception) {
            status = Status.Failed("controle: ${e.javaClass.simpleName}: ${e.message}", null)
            AppLog.i("update: check failed: $e")
            return describe()
        }
        if (release.versionCode <= BuildConfig.VERSION_CODE) {
            dir().deleteRecursively()
            prefs().edit().remove("pending").apply()
            abandonSessions()
            status = Status.UpToDate(release.versionCode)
            return describe()
        }
        // Android calls back for this one (re-arming waitForInstallConstraints would stack callbacks).
        if (busy is Status.Waiting && busy.byAndroid && busy.release == release) {
            status = busy
            return describe()
        }
        try {
            download(release)
        } catch (e: Exception) {
            AppLog.i("update: download v${release.versionCode} failed: $e")
            status = Status.Failed("download: ${e.javaClass.simpleName}: ${e.message}", release)
            return describe()
        }
        prefs().edit().putString("pending", release.json()).apply()
        val silent = canInstallSilently()
        AppLog.i("update: v${release.versionCode} downloaded, silent=$silent")
        if (silent) scheduleSilent(release) else {
            status = Status.NeedsTap(release)
            notifyNeedsTap(release)
        }
        return describe()
    }

    /** User tapped "Nu installeren" (Home already confirmed a tether drop). Installs right away. */
    fun installNow() = worker.execute {
        val r = available ?: pending() ?: return@execute
        if (!apkFile(r).exists()) { check(); return@execute }
        commit(r, interactive = true)
    }

    /** Silent path, step 1: tether guard, then Android's install constraints. */
    @Synchronized
    private fun scheduleSilent(r: Release) {
        val prev = status
        if (tetherBusy) {
            if ((prev as? Status.Waiting)?.tether != true) AppLog.i("update: v${r.versionCode} waits: tethering active")
            status = Status.Waiting(r, tether = true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && waitForConstraints(r)) return
        // Android 13, or not installer of record yet (first update after an adb/browser install):
        // waitForInstallConstraints throws SecurityException then. Same constraints, checked by us.
        if (quietNow()) return commit(r, interactive = false)
        if ((prev as? Status.Waiting)?.release != r) AppLog.i("update: v${r.versionCode} waits: app on screen or in a call")
        status = Status.Waiting(r, tether = false)
    }

    /** Not on screen, not in a call (no phone permission needed: audio mode). */
    private fun quietNow() = !LifecycleHelper.isInForeground &&
        app.getSystemService(AudioManager::class.java).mode.let { it != AudioManager.MODE_IN_CALL && it != AudioManager.MODE_IN_COMMUNICATION }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun waitForConstraints(r: Release): Boolean {
        // No setAppNotForegroundRequired: it counts foreground services (importance <= FOREGROUND_SERVICE),
        // and LinkService always runs. No setAppNotInteractingRequired: it counts any wifi/mobile traffic
        // of the app in the last 10 min (KDE Connect's LAN link, our own download).
        val constraints = PackageInstaller.InstallConstraints.Builder()
            .setAppNotTopVisibleRequired()
            .setNotInCallRequired()
            .build()
        val intent = Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_CONSTRAINTS)
            .putExtra(UpdateReceiver.EXTRA_VERSION, r.versionCode)
        val sender = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
        status = Status.Waiting(r, tether = false, byAndroid = true) // before: the callback may be quick
        return runCatching {
            app.packageManager.packageInstaller.waitForInstallConstraints(listOf(app.packageName), constraints, sender, CONSTRAINT_TIMEOUT_MS)
        }.onFailure { AppLog.i("update: waitForInstallConstraints: $it") }.isSuccess
    }

    /** Silent path, step 2 ([UpdateReceiver]): constraints met (or timed out). Our process may be fresh. */
    internal fun onConstraints(satisfied: Boolean, versionCode: Int) = worker.execute {
        val r = pending()?.takeIf { it.versionCode == versionCode && it.versionCode > BuildConfig.VERSION_CODE } ?: return@execute
        if (!satisfied) {
            AppLog.i("update: v$versionCode install constraints not met in time, next check retries")
            status = Status.Failed("wacht te lang op een rustig moment", r)
            lastCheck = 0
            return@execute
        }
        synchronized(this) {
            if (tetherBusy) scheduleSilent(r) else commit(r, interactive = false)
        }
    }

    @Synchronized
    private fun commit(r: Release, interactive: Boolean) {
        val file = apkFile(r)
        if (!file.exists()) {
            status = Status.Failed("installeren: ${file.name} ontbreekt", r)
            return
        }
        // Last guard before the point of no return (interactive: the user confirmed the tether drop).
        if (!interactive && tetherBusy) return scheduleSilent(r)
        AppLog.i("update: committing v${r.versionCode}, interactive=$interactive")
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
                session.commit(PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            }
        } catch (e: Exception) {
            AppLog.i("update: commit v${r.versionCode} failed: $e")
            status = Status.Failed("installeren: ${e.javaClass.simpleName}: ${e.message}", r)
        }
    }

    /** [UpdateReceiver]: PackageInstaller session result. */
    internal fun onInstallResult(code: Int, message: String?, confirm: Intent?, interactive: Boolean, versionCode: Int) {
        val r = pending()?.takeIf { it.versionCode == versionCode } ?: available
        when (code) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.i("update: v$versionCode needs confirmation (interactive=$interactive): $message")
                r?.let { status = Status.NeedsTap(it) } // stays on the card if the dialog is cancelled
                if (interactive && confirm != null && LifecycleHelper.isInForeground) {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else r?.let(::notifyNeedsTap)
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("update: v$versionCode installed")
            else -> {
                AppLog.i("update: v$versionCode install failed: status=$code $message")
                status = Status.Failed("installeren mislukt ($code): $message", r)
            }
        }
    }

    fun describe(): String {
        val info = runCatching { app.packageManager.getInstallSourceInfo(app.packageName) }.getOrNull()
        val state = when (val s = status) {
            Status.Idle -> "not checked"
            Status.Checking -> "checking"
            is Status.UpToDate -> "up to date (latest v${s.latest})"
            is Status.Downloading -> "downloading v${s.release.versionCode}: ${s.bytes / 1_048_576}/${s.release.size / 1_048_576} MB"
            is Status.Waiting -> "v${s.release.versionCode} waits for ${if (s.tether) "tethering off" else "app not in use"}"
            is Status.NeedsTap -> "v${s.release.versionCode} needs a tap"
            is Status.Installing -> "installing v${s.release.versionCode}"
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
        if (!pm.canRequestPackageInstalls()) return false
        val owner = runCatching { pm.getInstallSourceInfo(app.packageName).updateOwnerPackageName }.getOrNull()
        return owner == null || owner == app.packageName
    }

    private fun open(path: String) = (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("Cache-Control", "no-cache")
    }

    private fun fetchRelease(): Release {
        val c = open("version.json")
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
    private fun pending() = prefs().getString("pending", null)?.let { runCatching { Release.parse(it) }.getOrNull() }

    private fun abandonSessions() = app.packageManager.packageInstaller.let { i ->
        i.mySessions.forEach { runCatching { i.abandonSession(it.sessionId) } }
    }

    /** Downloads to .part, renamed only after the sha256 matches: an existing final file is verified. */
    private fun download(r: Release) {
        val target = apkFile(r)
        if (target.exists()) return
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
    }

    /** Opens Home (update card, tether guard), not Android's confirm dialog directly. */
    private fun notifyNeedsTap(r: Release) {
        if (LifecycleHelper.isInForeground) return // the card is on screen
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 2, Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Upstream channel: KdeConnect.onCreate deletes channels it doesn't know.
        runCatching {
            nm.notify(NOTIFICATION_ID, Notification.Builder(app, NotificationHelper.Channels.DEFAULT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update v${r.versionCode} klaar, tik om te installeren")
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build())
        }
    }

    private fun logVersionChange() {
        val p = prefs()
        val previous = p.getInt("versionCode", -1)
        if (previous == BuildConfig.VERSION_CODE) return
        AppLog.i("update: now running ${BuildConfig.VERSION_NAME}, was v$previous")
        p.edit().clear().putInt("versionCode", BuildConfig.VERSION_CODE).apply()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
