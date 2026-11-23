// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** PackageInstaller session status for [Updater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getIntExtra(EXTRA_VERSION, -1)
        when (intent.action) {
            ACTION_STATUS -> Updater.onInstallResult(
                code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java),
                interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false),
                versionCode = version,
            )
        }
    }

    companion object {
        const val ACTION_STATUS = "com.rutger.workstationconnect.update.STATUS"
        const val EXTRA_INTERACTIVE = "interactive"
        const val EXTRA_VERSION = "versionCode"
    }
}
