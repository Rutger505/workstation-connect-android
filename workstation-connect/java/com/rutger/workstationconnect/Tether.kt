// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.rutger.workstationconnect.logs.LogUpload
import com.rutger.workstationconnect.logs.LogUploadLogic.Reason
import org.kde.kdeconnect_tp.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Process-wide owner of the Shizuku helper and the phone's tethering state. */
object Tether {
    private const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val PREFS = "wsconnect-tether"
    private const val KEY_RESTORE_AT = "restore_after_update_at"
    /** A saved "hotspot wanted" older than this is ignored, and a restore waiting for Shizuku gives up. */
    private const val RESTORE_MAX_MS = 5 * 60_000L

    private val log = AppLog.tag("tether")
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var control: ITetherControl? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    @Volatile private var restorePending = false
    /** elapsedRealtime of the last on/off request (0 = none pending): time until the tether broadcast confirms it. */
    @Volatile private var requestedAt = 0L

    @Volatile var wanted = false; private set
    @Volatile var hotspotOn = false; private set
    @Volatile var lastError = false; private set

    val shizukuRunning: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    val shizukuAllowed: Boolean
        get() = shizukuRunning && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    val ready: Boolean get() = control != null

    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, TetherControlService::class.java.name))
        .daemon(false)
        .processNameSuffix("tether")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return log.w("shizuku helper: empty binder")
            val c = ITetherControl.Stub.asInterface(binder)
            control = c
            worker.execute {
                val who = runCatching { c.whoami() }.getOrElse { log.w("shizuku helper: whoami failed", it); "?" }
                log.i("shizuku helper ready: $who")
                pullHelperLog(c)
            }
            changed()
            if (restorePending) {
                restorePending = false
                log.i("hotspot restore: shizuku helper ready, hotspot on")
                clearRestore()
            }
            if (wanted) on("still wanted after helper (re)bind") // phone/Shizuku/app (update) restarted while the laptop wanted internet
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
            log.w("shizuku helper: disconnected")
            changed()
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky {
            log.i("shizuku: running (v${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}, permission ${if (shizukuAllowed) "granted" else "not granted"})")
            bind()
        }
        Shizuku.addBinderDeadListener { control = null; log.w("shizuku: stopped (helper lost)"); changed() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            val granted = result == PackageManager.PERMISSION_GRANTED
            if (granted) log.i("shizuku permission: granted") else log.w("shizuku permission: denied")
            bind()
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onTetherState(intent)
        }, IntentFilter(TETHER_STATE_CHANGED), Context.RECEIVER_EXPORTED)?.let(::onTetherState)
    }

    /**
     * Before an update install (it kills the process, and with it our idea of "wanted"): remember that
     * the laptop wanted the hotspot, so [restoreAfterUpdate] turns it back on in the new process.
     */
    fun saveForUpdate() {
        if (!wanted) return clearRestore().also { log.d("hotspot restore: not wanted, nothing saved") }
        val saved = prefs().edit().putLong(KEY_RESTORE_AT, System.currentTimeMillis()).commit()
        if (saved) log.i("hotspot restore: saved (wanted=$wanted on=$hotspotOn), restored after the update")
        else log.w("hotspot restore: saving failed, hotspot stays off after the update")
    }

    /** Install didn't happen (failed/needs a tap): nothing to restore. */
    fun clearRestore() {
        if (::app.isInitialized) prefs().edit().remove(KEY_RESTORE_AT).apply()
    }

    /**
     * New process after an update (WsConnect.init / BootReceiver MY_PACKAGE_REPLACED, idempotent): hotspot
     * back on if it was wanted, as soon as the Shizuku helper is bound ([connection]).
     */
    fun restoreAfterUpdate() {
        if (!::app.isInitialized) return
        val at = prefs().getLong(KEY_RESTORE_AT, 0L).takeIf { it > 0 } ?: return
        val age = System.currentTimeMillis() - at
        if (age !in 0..RESTORE_MAX_MS) {
            log.i("hotspot restore: saved ${age / 1000} s ago, too old: ignored")
            return clearRestore()
        }
        if (wanted) return log.d("hotspot restore: already wanted/restoring") // already restoring/on
        log.i("hotspot restore: wanted before the update (${age / 1000} s ago), helper ${if (control != null) "ready" else "not bound yet: waiting"}")
        if (control != null) {
            clearRestore()
            return on("restore after update")
        }
        restorePending = true
        wanted = true // FLAG_WANTED to the laptop; on() follows in onServiceConnected
        changed()
        bind()
        main.postDelayed({
            if (!restorePending) return@postDelayed
            restorePending = false
            log.w("hotspot restore: no shizuku helper within ${RESTORE_MAX_MS / 60_000} min, gave up")
            clearRestore()
            if (control == null) { wanted = false; changed() }
        }, RESTORE_MAX_MS)
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    fun bind() {
        if (control != null || !shizukuAllowed) {
            log.d("bind: skipped (${if (control != null) "helper already bound" else "shizuku running=$shizukuRunning, no permission"})")
            return changed()
        }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onSuccess { log.d("bindUserService requested (v${BuildConfig.VERSION_CODE})") }
            .onFailure { log.e("bindUserService failed", it) }
    }

    fun requestPermission() {
        when {
            !shizukuRunning -> log.w("permission request: shizuku is not running")
            shizukuAllowed -> bind()
            else -> { log.i("shizuku permission requested"); Shizuku.requestPermission(1) }
        }
    }

    /** Worker thread: the helper process' own AppLog lines (memory only there) into ours. */
    private fun pullHelperLog(c: ITetherControl) {
        val text = runCatching { c.takeLog() }.getOrElse { return log.d("helper log not fetched: ${it.javaClass.simpleName}") }
        AppLog.forward(text.split("\n").filter { it.isNotEmpty() })
    }

    /** Runs [command] as the shell uid via the Shizuku helper; [done] gets the result (worker thread). */
    fun shell(command: String, done: (String) -> Unit) = worker.execute {
        val c = control ?: return@execute done("FAIL no shizuku helper").also { log.w("shell: $command: no shizuku helper") }
        val r = runCatching { c.shell(command) }.getOrElse { log.e("shell: $command failed", it); "FAIL $it" }
        // output shortened: only our own fixed commands run here, but keep lines small
        val short = r.replace(Regex("\\s+"), " ").let { if (it.length > 200) it.take(200) + "…" else it }
        if (r.startsWith("exit 0")) log.i("shell: $command → $short") else log.w("shell: $command → $short")
        pullHelperLog(c)
        done(r)
    }

    /** [why]: who asked (logged). */
    fun on(why: String) {
        log.i("hotspot on requested ($why, was wanted=$wanted on=$hotspotOn)")
        wanted = true
        requestedAt = SystemClock.elapsedRealtime()
        changed()
        run("on") { c -> listOf(c.startHotspot()) }
    }

    fun off(why: String) {
        log.i("hotspot off requested ($why, was wanted=$wanted on=$hotspotOn)")
        wanted = false
        requestedAt = SystemClock.elapsedRealtime()
        if (restorePending) { restorePending = false; clearRestore(); log.i("hotspot restore: cancelled (off)") }
        changed()
        run("off") { c -> listOf(c.stopHotspot()) }
    }

    private fun run(label: String, block: (ITetherControl) -> List<String>) = worker.execute {
        val c = control
        if (c == null) {
            lastError = true
            log.e("$label: no shizuku helper (running=$shizukuRunning allowed=$shizukuAllowed)")
            if (label == "on") LogUpload.trigger(Reason.HOTSPOT_FAIL)
            return@execute changed()
        }
        val t0 = SystemClock.elapsedRealtime()
        val results = runCatching { block(c) }.getOrElse { log.e("$label: helper call failed", it); listOf("FAIL $it") }
        val ms = SystemClock.elapsedRealtime() - t0
        // DUPLICATE_REQUEST = already on, which is what we wanted.
        lastError = results.any { !it.startsWith("OK") && !it.contains("DUPLICATE_REQUEST") }
        results.forEach { if (lastError) log.e("$label: $it (call $ms ms)") else log.i("$label: $it (call $ms ms)") }
        pullHelperLog(c)
        if (label == "on") LogUpload.trigger(if (lastError) Reason.HOTSPOT_FAIL else Reason.HOTSPOT_ON)
        changed()
    }

    private fun onTetherState(intent: Intent) {
        val active = intent.getStringArrayListExtra("tetherArray") ?: return log.d("tether broadcast without tetherArray: ignored")
        val was = hotspotOn
        hotspotOn = active.any { isWifi(it) }
        val since = requestedAt.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it }
        if (hotspotOn != was) {
            requestedAt = 0
            log.i("hotspot ${if (hotspotOn) "on" else "off"} (tethered: ${active.ifEmpty { listOf("none") }.joinToString()}" +
                "${since?.let { ", $it ms after the request" } ?: ", not requested by us"})")
        } else log.d("tethered: ${active.ifEmpty { listOf("none") }.joinToString()} (hotspot unchanged)")
        changed()
    }

    // bt-pan: Bluetooth tethering the user turned on by hand, not ours.
    private fun isWifi(iface: String) = listOf("bt-pan", "rndis", "ncm", "usb", "eth").none { iface.startsWith(it) }

    private fun changed() = listeners.forEach { it() }
}
