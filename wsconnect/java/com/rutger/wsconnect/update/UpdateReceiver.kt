// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** PackageInstaller callbacks for [Updater]: install-constraints result and session status. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getIntExtra(EXTRA_VERSION, -1)
        when (intent.action) {
            ACTION_CONSTRAINTS -> Updater.onConstraints(
                satisfied = intent.getParcelableExtra(PackageInstaller.EXTRA_INSTALL_CONSTRAINTS_RESULT,
                    PackageInstaller.InstallConstraintsResult::class.java)?.areAllConstraintsSatisfied() == true,
                versionCode = version,
            )
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
        const val ACTION_CONSTRAINTS = "com.rutger.wsconnect.update.CONSTRAINTS"
        const val ACTION_STATUS = "com.rutger.wsconnect.update.STATUS"
        const val EXTRA_INTERACTIVE = "interactive"
        const val EXTRA_VERSION = "versionCode"
    }
}
