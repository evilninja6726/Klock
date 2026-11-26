package com.klock.app.alarm.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.klock.app.KlockApplication
import java.util.concurrent.TimeUnit

/**
 * Lightweight non-polling WorkManager job (runs at most once every 12 hours when battery is not low)
 * to reconcile enabled Room alarms with OS AlarmManager registrations and prune stale history rows.
 */
class AlarmReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KlockApplication ?: return Result.success()
        return runCatching {
            val container = app.container
            container.alarmScheduler.rescheduleAllEnabled(container.alarmRepository)
            container.historyRepository.pruneOldFingerprints(
                keepLatest = container.hardwareProfile.tier.historyRetentionCount
            )
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "klock_alarm_reconciler"

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AlarmReconcileWorker>(
                12,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                    )
            }
        }
    }
}
