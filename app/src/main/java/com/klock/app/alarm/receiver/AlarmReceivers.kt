package com.klock.app.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.klock.app.KlockApplication
import com.klock.app.alarm.scheduler.AndroidAlarmScheduler
import com.klock.app.alarm.service.AlarmRingingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives exact [android.app.AlarmManager.setAlarmClock] PendingIntent broadcasts and starts
 * [AlarmRingingService] immediately.
 */
class AlarmTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AndroidAlarmScheduler.ACTION_ALARM_TRIGGER) return
        val alarmId = intent.getLongExtra(AndroidAlarmScheduler.EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_ALARM_LABEL).orEmpty()
        val vibrate = intent.getBooleanExtra(AndroidAlarmScheduler.EXTRA_ALARM_VIBRATE, true)
        val ringtoneUri = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_ALARM_RINGTONE_URI)

        AlarmRingingService.startAlarmService(
            context = context,
            alarmId = alarmId,
            alarmLabel = label,
            vibrate = vibrate,
            ringtoneUri = ringtoneUri
        )
    }
}

/**
 * Restores all enabled alarms and any unfinished active ringing session after:
 * - Device reboot (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`)
 * - App update (`MY_PACKAGE_REPLACED`)
 * - System clock / timezone adjustment (`TIME_SET`, `TIMEZONE_CHANGED`)
 *
 * Note: Credential-encrypted Room storage is accessed only once UserManager reports user unlocked
 * (`isUserUnlocked`), avoiding crashes during `LOCKED_BOOT_COMPLETED` Direct Boot before PIN entry.
 */
class AlarmBootAndTimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um != null && !um.isUserUnlocked) {
                // Direct Boot pre-unlock: BOOT_COMPLETED will fire right after credential unlock.
                return
            }
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as? KlockApplication
        if (app == null) {
            pendingResult.finish()
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                container.alarmScheduler.rescheduleAllEnabled(container.alarmRepository)

                // If the device restarted while an alarm session was actively ringing and unsolved,
                // resume the ringing service immediately so rebooting cannot silently defeat Klock!
                val unfinished = container.sessionRepository.getActiveSession()
                if (unfinished != null && !unfinished.completed) {
                    AlarmRingingService.startAlarmService(
                        context = context.applicationContext,
                        alarmId = unfinished.alarmId,
                        alarmLabel = unfinished.alarmLabel
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
