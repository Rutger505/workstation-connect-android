// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rutger.workstationconnect.ui.HomeActivity
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R

/**
 * The one ongoing notification, shared by upstream's BackgroundService and [LinkService]: both
 * startForeground with [ID] on channel PERSISTENT. Title = KDE Connect status, text = laptop link status.
 *
 * Single source: upstream's `createForegroundNotification` ends in [decorate] (keeps its action buttons),
 * [build] goes through it while BackgroundService runs, so a post from either service is identical.
 * Android keeps a shared-id FGS notification while another service of the app still has it in the
 * foreground (ActiveServices.cancelForegroundNotificationLocked), so one service stopping leaves it up.
 * Never post while neither service runs: the system already cancelled it, a post leaves an ongoing orphan.
 */
object StatusNotification {
    /** = upstream's BackgroundService.FOREGROUND_NOTIFICATION_ID (private there). */
    const val ID = 1
    private val log = AppLog.tag("status")
    @Volatile private var lastShown: String? = null

    /** Full content: upstream's (with actions) while BackgroundService runs, else fallback without actions. */
    fun build(c: Context): Notification =
        BackgroundService.instance?.createForegroundNotification() ?: decorate(c, fallback(c))

    /** Hook target: upstream's createForegroundNotification returns through here. */
    fun decorate(c: Context, b: NotificationCompat.Builder): Notification {
        val title = kdeLine()
        val text = linkLine()
        // called on every upstream refresh: log changes only
        val shown = "$title | $text"
        if (shown != lastShown) {
            lastShown = shown
            log.d("notification: $shown (upstream actions: ${BackgroundService.instance != null})")
        }
        return b
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Any thread. No-op while neither service runs (see class doc). */
    fun refresh(c: Context) {
        if (!LinkService.running && BackgroundService.instance == null) {
            if (lastShown != null) log.d("refresh skipped: no service holds the notification")
            lastShown = null
            return
        }
        runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, build(c)) }
            .onFailure { log.e("status notification post failed", it) }
    }

    private fun kdeLine(): String {
        val names = KdeConnect.getInstance().devices.values.filter { it.isReachable && it.isPaired }.map { it.name }
        return if (names.isEmpty()) "KDE Connect: niet verbonden" else "KDE Connect: verbonden met ${names.joinToString()}"
    }

    private fun linkLine(): String {
        val names = LinkService.clientLabels.map { it.substringAfter('/') }
        return "Workstation Connect: " + when {
            !LinkService.running -> "uit"
            names.isEmpty() -> "wacht op laptop"
            else -> "verbonden met ${names.joinToString()}"
        }
    }

    // Same base as upstream's builder. Upstream channels only: KdeConnect.onCreate deletes every unlisted
    // channel, which throws (crash loop) while a foreground service still uses it.
    private fun fallback(c: Context) = NotificationCompat.Builder(c, NotificationHelper.Channels.PERSISTENT)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setShowWhen(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setAutoCancel(false)
        .setGroup("BackgroundService")
}
