// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rutger.wsconnect.AppLog
import com.rutger.wsconnect.LinkService
import com.rutger.wsconnect.Tether
import com.rutger.wsconnect.WsConnect
import org.kde.kdeconnect_tp.BuildConfig

/** Debug screen: raw state, manual tether commands, copyable log. */
class LogActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private val refresh: () -> Unit = { main.post(::render) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 96, 24, 24) }
        fun row(vararg buttons: Pair<String, () -> Unit>) = root.addView(LinearLayout(this).apply {
            buttons.forEach { (label, action) ->
                addView(Button(context).apply { text = label; isAllCaps = false; setOnClickListener { action() } },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        root.addView(status)
        row("Test aan" to Tether::on, "Test BT uit" to Tether::btOff, "Test uit" to Tether::off)
        row("Log kopiëren" to ::copyLog, "Log wissen" to AppLog::clear)
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        AppLog.listen(refresh)
        Tether.listen(refresh)
        render()
    }

    override fun onPause() {
        AppLog.unlisten(refresh)
        Tether.unlisten(refresh)
        super.onPause()
    }

    private fun render() {
        fun yn(b: Boolean) = if (b) "ja" else "NEE"
        status.text = buildString {
            appendLine("${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}")
            appendLine("shizuku:   draait ${yn(Tether.shizukuRunning)}, toestemming ${yn(Tether.shizukuAllowed)}, helper ${yn(Tether.ready)}")
            appendLine("ble-link:  service ${yn(LinkService.running)}, laptop ${yn(LinkService.laptopConnected)}, autostart ${yn(WsConnect.linkEnabled(this@LogActivity))}")
            append("tethering: gewenst ${yn(Tether.wanted)}, bt ${yn(Tether.btTethered)}, hotspot ${yn(Tether.hotspotOn)}, fout ${yn(Tether.lastError)}")
        }
        log.text = AppLog.text()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("wsconnect log", status.text.toString() + "\n\n" + AppLog.text()))
        Toast.makeText(this, "Log gekopieerd", Toast.LENGTH_SHORT).show()
    }
}
