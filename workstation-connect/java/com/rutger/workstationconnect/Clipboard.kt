// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.rutger.workstationconnect.ui.HomeActivity
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect_tp.R
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * Clipboard extras on top of KDE Connect's clipboard plugin:
 * - history of both directions (phone copies via ClipboardListener observer, laptop copies via the
 *   hook in ClipboardListener.setText), passwords never stored;
 * - auto sync phone → laptop: upstream's READ_LOGS trick, granted through Shizuku instead of adb;
 * - "skip sensitive" on by default (upstream default is off).
 */
object Clipboard {
    data class Item(val text: String, val fromLaptop: Boolean, val at: Long)

    private const val PREFS = "wsconnect_clipboard"
    private const val MAX = 50
    private val log = AppLog.tag("clipboard")
    private lateinit var app: Context
    private val items = ArrayDeque<Item>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        app = context.applicationContext
        load()
        log.i("auto sync ${if (autoSync(app)) "on" else "off"} (READ_LOGS " +
            "${if (app.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) "granted" else "not granted"})")
        ClipboardListener.instance(app).registerObserver(object : ClipboardListener.ClipboardObserver {
            override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
                logPhoneCopy(content, contentType)
                if (contentType == ClipboardListener.ClipboardContentType.Text) add(content, fromLaptop = false)
            }
        })
    }

    /** What upstream's ClipboardPlugin observer does with a phone copy, mirrored. Lengths only, never content. */
    private fun logPhoneCopy(content: String, type: ClipboardListener.ClipboardContentType) = runCatching<Unit> {
        val laptop = WsConnect.laptop(app)
        val plugin = laptop?.getPlugin(ClipboardPlugin::class.java)
        val skip = plugin?.preferences?.getBoolean(app.getString(R.string.clipboard_preference_key_skip_sensitive), false) == true
        val what = when {
            laptop == null -> "no paired laptop"
            plugin == null -> "not sent: clipboard plugin ${Diag.plugin(laptop, ClipboardPlugin::class.java)}"
            type == ClipboardListener.ClipboardContentType.Password && skip -> "skipped-sensitive"
            !laptop.isReachable -> "not sent: laptop unreachable"
            else -> "sent"
        }
        log.i("phone copy (${type.name.lowercase()}, ${content.length} chars): $what")
    }.onFailure { log.w("phone copy: state lookup failed", it) }

    /** Hook from upstream ClipboardListener.setText (text that came from the laptop). */
    @JvmStatic
    fun fromLaptop(text: String?) {
        if (!::app.isInitialized) return AppLog.d("clipboard", "received from laptop before init: not in history")
        log.i("received from laptop (${text?.length ?: 0} chars)")
        if (!text.isNullOrEmpty()) add(text, fromLaptop = true) else log.d("empty clip from laptop: not in history")
    }

    fun history(): List<Item> = synchronized(items) { items.toList() }
    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    fun clear() {
        log.i("history cleared (${history().size} items)")
        synchronized(items) { items.clear() }
        save()
    }

    private fun add(text: String, fromLaptop: Boolean) {
        if (text.isBlank()) return log.d("blank clip: not in history")
        synchronized(items) {
            items.removeAll { it.text == text }
            items.addFirst(Item(text, fromLaptop, System.currentTimeMillis()))
            while (items.size > MAX) items.removeLast()
        }
        save()
    }

    private fun load() = runCatching {
        val arr = JSONArray(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", "[]"))
        synchronized(items) {
            items.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.addLast(Item(o.getString("t"), o.getBoolean("l"), o.getLong("a")))
            }
        }
        log.d("history loaded (${arr.length()} items)")
    }.onFailure { log.w("history load failed, starting empty", it) }

    private fun save() {
        val arr = JSONArray()
        history().forEach { arr.put(JSONObject().put("t", it.text).put("l", it.fromLaptop).put("a", it.at)) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
        listeners.forEach { it() }
    }

    /** Phone copies reach the laptop without opening the app. */
    fun autoSync(c: Context) = ClipboardPlugin.canSyncAutomatically(c)

    /**
     * Grants READ_LOGS (+ overlay, for upstream's floating read activity) via Shizuku, then restarts
     * the app: upstream checks READ_LOGS once, when ClipboardListener starts.
     */
    fun enableAutoSync(c: Context, done: (String) -> Unit) {
        val pkg = c.packageName
        log.i("enabling auto sync (READ_LOGS via shizuku)")
        Tether.shell("pm grant $pkg ${Manifest.permission.READ_LOGS} && appops set $pkg SYSTEM_ALERT_WINDOW allow") { r ->
            if (!r.startsWith("exit 0")) return@shell done(r).also { log.e("READ_LOGS grant failed (details in the shell line)") }
            if (c.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                log.w("READ_LOGS granted but not active")
                return@shell done("granted but not active: $r")
            }
            log.i("READ_LOGS granted, restarting the app")
            restart(c)
        }
    }

    private fun restart(c: Context) {
        val intent = PendingIntent.getActivity(c, 0, Intent(c, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 500, intent)
        exitProcess(0)
    }

    /** Upstream skips sensitive clips only if asked; we want it on unless Rutger turns it off. */
    fun defaultSkipSensitive(c: Context) {
        val key = c.getString(R.string.clipboard_preference_key_skip_sensitive)
        KdeConnect.getInstance().devices.values.filter { it.isPaired }.forEach { d ->
            val prefs = runCatching { d.getPlugin(ClipboardPlugin::class.java)?.preferences }
                .onFailure { log.w("skip-sensitive default: plugin prefs of ${d.name}", it) }.getOrNull()
                ?: return@forEach log.d("skip-sensitive default: ${d.name} has no clipboard plugin")
            if (!prefs.contains(key)) {
                log.i("skip-sensitive default: on for ${d.name}")
                prefs.edit().putBoolean(key, true).apply()
            }
        }
    }
}
