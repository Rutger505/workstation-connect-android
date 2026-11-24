// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.update

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rutger.workstationconnect.AppLog
import com.rutger.workstationconnect.logs.LogUpload
import com.rutger.workstationconnect.logs.LogUploadLogic.Reason

/**
 * Backup for LinkService's 10-min tick (15 min, WorkManager's minimum; any network incl. mobile data),
 * also while the link is off. Update check + periodic log upload. Its line is debug: debug lines alone
 * don't make the periodic log upload run; Updater logs what matters itself.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val r = runCatching { Updater.check("worker") }
            .onFailure { AppLog.e("update", "worker: check threw", it) }
        r.onSuccess { AppLog.d("update", "worker: $it") }
        LogUpload.trigger(Reason.PERIODIC)
        return Result.success()
    }

    companion object {
        const val NAME = "wsconnect-update-check"
    }
}
