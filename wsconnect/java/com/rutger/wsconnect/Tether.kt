// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import org.kde.kdeconnect_tp.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Process-wide owner of the Shizuku helper and the phone's tethering state. */
object Tether {
    private const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"

    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var control: ITetherControl? = null

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
            if (wanted) on() // phone/Shizuku restarted while the laptop wanted internet
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
            AppLog.i("shizuku helper: disconnected")
            changed()
        }
    }

    fun init(context: Context) {
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
        changed()
        run("off") { c -> listOf(c.stopHotspot()) }
    }

    private fun run(label: String, block: (ITetherControl) -> List<String>) = worker.execute {
        val c = control
        if (c == null) {
            lastError = true
            AppLog.i("$label: no shizuku helper (running=$shizukuRunning allowed=$shizukuAllowed)")
            return@execute changed()
        }
        val results = runCatching { block(c) }.getOrElse { listOf("FAIL $it") }
        // DUPLICATE_REQUEST = already on, which is what we wanted.
        lastError = results.any { !it.startsWith("OK") && !it.contains("DUPLICATE_REQUEST") }
        results.forEach { AppLog.i("$label: $it") }
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
