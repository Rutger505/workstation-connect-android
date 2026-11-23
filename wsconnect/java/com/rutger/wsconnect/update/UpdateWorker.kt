// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.update

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rutger.wsconnect.logs.LogUpload
import com.rutger.wsconnect.logs.LogUploadLogic.Reason

/**
 * Backup for LinkService's 10-min tick (15 min, WorkManager's minimum; any network incl. mobile data),
 * also while the link is off. Update check + periodic log upload. Not in AppLog: a line every 15 min
 * would make every periodic log upload run; Updater logs what matters itself.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Log.i("wsconnect", "update: worker: " + Updater.check())
        LogUpload.trigger(Reason.PERIODIC)
        return Result.success()
    }

    companion object {
        const val NAME = "wsconnect-update-check"
    }
}
