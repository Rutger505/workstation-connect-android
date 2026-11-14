// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect

/**
 * Entry point for everything workstation-connect adds to KDE Connect. Upstream's KdeConnect
 * Application calls [isHelperProcess] + [init]; keep upstream hooks to those two calls.
 */
object WsConnect {
    private const val PREFS = "wsconnect"

    /** Shizuku's UserService process (":tether") also creates the Application: skip all app init there. */
    fun isHelperProcess(app: Application) = Application.getProcessName() != app.packageName

    fun init(app: Application) {
        Tether.init(app)
        if (linkEnabled(app)) LinkService.start(app)
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** BLE link (auto hotspot) on; survives reboots. */
    fun linkEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setLinkEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    /** The laptop: the chosen paired device, else the first paired one. */
    fun laptop(c: Context): Device? {
        val paired = KdeConnect.getInstance().devices.values.filter { it.isPaired }
        val chosen = prefs(c).getString("laptop", null)
        return paired.firstOrNull { it.deviceId == chosen } ?: paired.minByOrNull { it.name }
    }

    fun setLaptop(c: Context, deviceId: String) = prefs(c).edit().putString("laptop", deviceId).apply()
}

/** Restarts the BLE link after a reboot. Shizuku won't run until wireless debugging is started again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && WsConnect.linkEnabled(context)) LinkService.start(context)
    }
}
