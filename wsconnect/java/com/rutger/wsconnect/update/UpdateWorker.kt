// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.update

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rutger.wsconnect.AppLog

/** Periodic update check (6 h, any network incl. mobile data), also while the app is closed. */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AppLog.i("update: worker: " + Updater.check())
        return Result.success()
    }

    companion object {
        const val NAME = "wsconnect-update-check"
    }
}
