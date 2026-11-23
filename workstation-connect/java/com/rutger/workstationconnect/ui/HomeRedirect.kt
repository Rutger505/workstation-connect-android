package com.rutger.workstationconnect.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.kde.kdeconnect.ui.MainActivity

/**
 * Upstream opens MainActivity from everywhere (foreground/ping/share notifications, device shortcuts,
 * QS tile long-press). Send all of that to HomeActivity; MainActivity stays reachable as
 * "Geavanceerde instellingen" and for pairing notifications.
 */
object HomeRedirect {
    private const val EXTRA_ADVANCED = "wsconnect_advanced"

    fun advancedIntent(c: Context) = Intent(c, MainActivity::class.java).putExtra(EXTRA_ADVANCED, true)

    /** Hook in MainActivity.onCreate (after super). True = redirected and finished, caller must return. */
    fun toHome(a: Activity, savedInstanceState: Bundle?): Boolean {
        val i = a.intent
        val keep = savedInstanceState != null || // recreated (rotation, process death): user was already here
            i.getBooleanExtra(EXTRA_ADVANCED, false) ||
            i.hasExtra(MainActivity.PAIR_REQUEST_STATUS) ||
            i.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0 // "up" from an upstream settings screen
        if (keep) return false
        a.startActivity(Intent(a, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        a.finish()
        return true
    }
}
