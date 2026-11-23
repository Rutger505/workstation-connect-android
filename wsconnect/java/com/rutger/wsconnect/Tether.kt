// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

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
import com.rutger.wsconnect.logs.LogUpload
import com.rutger.wsconnect.logs.LogUploadLogic.Reason
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

    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var control: ITetherControl? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    @Volatile private var restorePending = false

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
            if (binder == null || !binder.pingBinder()) return AppLog.i("shizuku helper: empty binder")
            val c = ITetherControl.Stub.asInterface(binder)
            control = c
            worker.execute { AppLog.i("shizuku helper: ${runCatching { c.whoami() }.getOrElse { it.toString() }}") }
            changed()
            if (restorePending) {
                restorePending = false
                AppLog.i("hotspot restore: shizuku helper ready, hotspot on")
                clearRestore()
            }
            if (wanted) on() // phone/Shizuku/app (update) restarted while the laptop wanted internet
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
            AppLog.i("shizuku helper: disconnected")
            changed()
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { AppLog.i("shizuku: running (v${Shizuku.getVersion()})"); bind() }
        Shizuku.addBinderDeadListener { control = null; AppLog.i("shizuku: stopped"); changed() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            AppLog.i("shizuku permission: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
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
        if (!wanted) return clearRestore()
        prefs().edit().putLong(KEY_RESTORE_AT, System.currentTimeMillis()).commit()
        AppLog.i("hotspot restore: saved (wanted=$wanted on=$hotspotOn), restored after the update")
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
            AppLog.i("hotspot restore: saved ${age / 1000} s ago, too old: ignored")
            return clearRestore()
        }
        if (wanted) return // already restoring/on
        AppLog.i("hotspot restore: wanted before the update (${age / 1000} s ago), helper ${if (control != null) "ready" else "not bound yet: waiting"}")
        if (control != null) {
            clearRestore()
            return on()
        }
        restorePending = true
        wanted = true // FLAG_WANTED to the laptop; on() follows in onServiceConnected
        changed()
        bind()
        main.postDelayed({
            if (!restorePending) return@postDelayed
            restorePending = false
            AppLog.i("hotspot restore: no shizuku helper within ${RESTORE_MAX_MS / 60_000} min, gave up")
            clearRestore()
            if (control == null) { wanted = false; changed() }
        }, RESTORE_MAX_MS)
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    fun bind() {
        if (control != null || !shizukuAllowed) return changed()
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { AppLog.i("bindUserService: $it") }
    }

    fun requestPermission() {
        when {
            !shizukuRunning -> AppLog.i("shizuku is not running")
            shizukuAllowed -> bind()
            else -> Shizuku.requestPermission(1)
        }
    }

    /** Runs [command] as the shell uid via the Shizuku helper; [done] gets the result (worker thread). */
    fun shell(command: String, done: (String) -> Unit) = worker.execute {
        val c = control ?: return@execute done("FAIL no shizuku helper")
        val r = runCatching { c.shell(command) }.getOrElse { "FAIL $it" }
        AppLog.i("shell: $command → $r")
        done(r)
    }

    fun on() {
        wanted = true
        changed()
        run("on") { c -> listOf(c.startHotspot()) }
    }

    fun off() {
        wanted = false
        if (restorePending) { restorePending = false; clearRestore(); AppLog.i("hotspot restore: cancelled (off)") }
        changed()
        run("off") { c -> listOf(c.stopHotspot()) }
    }

    private fun run(label: String, block: (ITetherControl) -> List<String>) = worker.execute {
        val c = control
        if (c == null) {
            lastError = true
            AppLog.i("$label: no shizuku helper (running=$shizukuRunning allowed=$shizukuAllowed)")
            if (label == "on") LogUpload.trigger(Reason.HOTSPOT_FAIL)
            return@execute changed()
        }
        val results = runCatching { block(c) }.getOrElse { listOf("FAIL $it") }
        // DUPLICATE_REQUEST = already on, which is what we wanted.
        lastError = results.any { !it.startsWith("OK") && !it.contains("DUPLICATE_REQUEST") }
        results.forEach { AppLog.i("$label: $it") }
        if (label == "on") LogUpload.trigger(if (lastError) Reason.HOTSPOT_FAIL else Reason.HOTSPOT_ON)
        changed()
    }

    private fun onTetherState(intent: Intent) {
        val active = intent.getStringArrayListExtra("tetherArray") ?: return
        hotspotOn = active.any { isWifi(it) }
        AppLog.i("tethered: ${active.ifEmpty { listOf("none") }.joinToString()}")
        changed()
    }

    // bt-pan: Bluetooth tethering the user turned on by hand, not ours.
    private fun isWifi(iface: String) = listOf("bt-pan", "rndis", "ncm", "usb", "eth").none { iface.startsWith(it) }

    private fun changed() = listeners.forEach { it() }
}
