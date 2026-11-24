// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.rutger.workstationconnect.logs.LogUpload
import com.rutger.workstationconnect.update.Updater
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.ui.SettingsFragment

/**
 * Entry point for everything workstation-connect adds to KDE Connect. Upstream's KdeConnect
 * Application calls [isHelperProcess] + [init]; keep upstream hooks to those two calls.
 */
object WsConnect {
    private const val PREFS = "wsconnect"
    private val log = AppLog.tag("app")

    /** Shizuku's UserService process (":tether") also creates the Application: skip all app init there. */
    fun isHelperProcess(app: Application): Boolean {
        val name = Application.getProcessName()
        // helper: AppLog is memory-only there; the main process pulls these lines via ITetherControl.takeLog
        return (name != app.packageName).also { if (it) AppLog.i("helper", "process $name: app init skipped") }
    }

    fun init(app: Application) {
        val t0 = System.currentTimeMillis()
        AppLog.init(app)
        LogUpload.init(app) // crash handler early
        Tether.init(app)
        Clipboard.init(app)
        forceKdeBluetooth(app)
        if (linkEnabled(app)) LinkService.start(app) else log.i("bluetooth link off (setting): not started")
        Updater.init(app)
        Diag.init(app)
        Pairing.init(app)
        Tether.restoreAfterUpdate()
        log.i("init done in ${System.currentTimeMillis() - t0} ms")
    }

    /** KDE Connect's own Bluetooth backend (trackpad etc. without shared wifi) is always on; no toggle. Before BackgroundService starts. */
    private fun forceKdeBluetooth(c: Context) {
        val p = PreferenceManager.getDefaultSharedPreferences(c)
        if (p.getBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, false)) return
        p.edit().putBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, true).apply()
        log.i("KDE Connect over bluetooth: forced on")
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Bluetooth link (auto hotspot) on; survives reboots. */
    fun linkEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setLinkEnabled(c: Context, on: Boolean) {
        log.i("bluetooth link setting: ${if (on) "on" else "off"}")
        prefs(c).edit().putBoolean("enabled", on).apply()
    }

    /** The laptop: the chosen paired device, else the first paired one. */
    fun laptop(c: Context): Device? {
        val paired = KdeConnect.getInstance().devices.values.filter { it.isPaired }
        val chosen = prefs(c).getString("laptop", null)
        return paired.firstOrNull { it.deviceId == chosen } ?: paired.minByOrNull { it.name }
    }

    fun setLaptop(c: Context, deviceId: String) {
        log.i("laptop chosen: ${KdeConnect.getInstance().devices[deviceId]?.name ?: "?"} ($deviceId)")
        prefs(c).edit().putString("laptop", deviceId).apply()
    }
}

/**
 * Restarts the Bluetooth link after a reboot or an update (the process got killed; nobody opens the app).
 * Both broadcasts exempt us from the background FGS-start ban; Application.onCreate already tried
 * (LinkService.start is a no-op when running). Shizuku won't run after a reboot until wireless
 * debugging is started again; after an update it rebinds via Tether.init (new UserService version) and
 * Tether.restoreAfterUpdate turns the hotspot back on if the laptop wanted it before the install.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return AppLog.d("app", "boot receiver: $action ignored")
        AppLog.i("app", "${action.substringAfterLast('.')}: link ${if (WsConnect.linkEnabled(context)) "starting" else "off"}")
        if (WsConnect.linkEnabled(context)) LinkService.start(context)
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) Tether.restoreAfterUpdate() // no-op if WsConnect.init did it
    }
}
