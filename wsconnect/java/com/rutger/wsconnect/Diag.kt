// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.backends.bluetooth.BluetoothLink
import org.kde.kdeconnect.backends.lan.LanLink
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.notifications.AppDatabase
import org.kde.kdeconnect.plugins.notifications.NotificationReceiver
import org.kde.kdeconnect.plugins.notifications.NotificationsPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug lines in AppLog (end up in the log upload) for KDE Connect parts we don't own:
 * - per paired device: reachable, link types, notifications/mousepad/clipboard plugin state; logged on change
 *   (device list callback fires on every link add/loss, plugin listener on enable/disable/permissions);
 * - notification forwarding: upstream NotificationsPlugin's filter, mirrored (we see the same posts via our
 *   own listener on NotificationReceiver) — package + lengths only, never text.
 */
object Diag {
    private const val CALLBACK = "wsconnect-diag"
    private lateinit var app: Context
    private val deviceState = ConcurrentHashMap<String, String>()
    private val listening = ConcurrentHashMap<String, Device>()
    @Volatile private var notifService: NotificationReceiver? = null
    private val lastNotifLog = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        app = context.applicationContext
        KdeConnect.getInstance().addDeviceListChangedCallback(CALLBACK) { devicesChanged() }
        devicesChanged()
    }

    private fun devicesChanged() = runCatching {
        val devices = KdeConnect.getInstance().devices.values.filter { it.isPaired }
        devices.forEach { d ->
            if (listening[d.deviceId] !== d) {
                listening[d.deviceId] = d
                d.addPluginsChangedListener { deviceChanged(it) }
            }
            deviceChanged(d)
        }
    }.onFailure { AppLog.i("diag: devices: $it") }

    private fun deviceChanged(d: Device) {
        val links = Links.of(d).joinToString("+") {
            when (it) { is LanLink -> "lan"; is BluetoothLink -> "bt"; else -> it.javaClass.simpleName }
        }.ifEmpty { "none" }
        val state = "reachable=${d.isReachable} links=$links plugins: notifications=${plugin(d, NotificationsPlugin::class.java)} " +
            "mousepad=${plugin(d, MousePadPlugin::class.java)} clipboard=${plugin(d, ClipboardPlugin::class.java)}"
        if (deviceState.put(d.deviceId, state) != state) AppLog.i("kde ${d.name}: $state")
        if (d.loadedPlugins.containsKey(Plugin.getPluginKey(NotificationsPlugin::class.java))) attachNotifications()
    }

    /** on / no-permission / off (disabled in settings) / not-loaded (enabled, device unreachable). */
    fun plugin(d: Device, cls: Class<out Plugin>): String {
        val key = Plugin.getPluginKey(cls)
        return when {
            d.pluginsWithoutPermissions.containsKey(key) -> "no-permission"
            d.loadedPlugins.containsKey(key) -> "on"
            !runCatching { d.isPluginEnabled(key) }.getOrDefault(false) -> "off"
            else -> "not-loaded"
        }
    }

    // ---- notifications ----

    /** Our listener on upstream's receiver; once per receiver instance (a rebind creates a new one). */
    private fun attachNotifications() {
        if (notifService != null || !NotificationReceiver.hasReadNotificationsPermission(app)) return
        runCatching {
            NotificationReceiver.RunCommand(app) { service ->
                if (notifService === service) return@RunCommand
                notifService = service
                service.addListener(notifListener)
                AppLog.i("notifications: diag listener attached")
            }
        }.onFailure { AppLog.i("notifications: diag listener: $it") }
    }

    private val notifListener = object : NotificationReceiver.NotificationListener {
        override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
            runCatching { posted(statusBarNotification) }
        }
        override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {}
        override fun onListenerConnected(service: NotificationReceiver) {}
    }

    /** Mirrors NotificationsPlugin.sendNotification's filter (keep in sync on upstream merges). */
    private fun posted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val pkg = sbn.packageName
        val f = n.flags
        val skip = when {
            f and Notification.FLAG_FOREGROUND_SERVICE != 0 -> "foreground service"
            f and Notification.FLAG_ONGOING_EVENT != 0 -> "ongoing"
            f and Notification.FLAG_LOCAL_ONLY != 0 -> "local only"
            f and NotificationCompat.FLAG_GROUP_SUMMARY != 0 -> "group summary"
            Notification.CATEGORY_TRANSPORT == n.category -> "media controls"
            !AppDatabase.getInstance(app).isEnabled(pkg) -> "blocked by filter"
            pkg == "com.android.systemui" && (sbn.tag == "low_battery" || n.channelId == "MediaOngoingActivity") -> "systemui hack"
            runCatching { app.packageManager.isPackageSuspended(pkg) }.getOrDefault(false) -> "app suspended"
            else -> null
        }
        val laptop = WsConnect.laptop(app)
        val line = if (skip != null) "not sent: $skip" else {
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.length ?: 0
            val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.length ?: 0
            val hidden = AppDatabase.getInstance(app).getPrivacy(pkg, AppDatabase.PrivacyOptions.BLOCK_CONTENTS)
            "forward (title $title, text $text chars${if (hidden) ", contents hidden" else ""}; " +
                "laptop ${laptop?.let { "reachable=${it.isReachable} plugin=${plugin(it, NotificationsPlugin::class.java)}" } ?: "none"})"
        }
        // Updates of one notification come in bursts; skips repeat forever (progress, chat bubbles…).
        val window = if (skip == null) 5_000L else 30 * 60_000L
        val key = "$pkg|${skip ?: "forward"}"
        val now = SystemClock.elapsedRealtime()
        val last = lastNotifLog[key]
        if (last != null && now - last < window) return
        lastNotifLog[key] = now
        AppLog.i("notif $pkg: $line")
    }
}
