package com.klock.app.alarm.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.klock.app.MainActivity
import com.klock.app.alarm.receiver.AlarmTriggerReceiver
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.repository.AlarmRepository
import java.time.ZonedDateTime

interface AlarmScheduler {
    fun canScheduleExactAlarms(): Boolean
    fun schedule(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): Long
    fun cancel(alarmId: Long)
    suspend fun rescheduleAllEnabled(alarmRepository: AlarmRepository)
}

class AndroidAlarmScheduler(
    private val context: Context
) : AlarmScheduler {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager? =
        appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    override fun schedule(alarm: Alarm, now: ZonedDateTime): Long {
        require(alarm.id > 0L) { "Alarm must have a persisted positive ID before scheduling." }
        val triggerAtMillis = AlarmTimeCalculator.computeNextTriggerMillis(alarm, now)
        val manager = alarmManager ?: return triggerAtMillis

        val triggerPendingIntent = buildTriggerPendingIntent(
            alarmId = alarm.id,
            alarmLabel = alarm.label,
            vibrate = alarm.vibrate,
            ringtoneUri = alarm.ringtoneUri,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )!!

        val showIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
            appContext,
            requestCodeForShow(alarm.id),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (canScheduleExactAlarms()) {
                val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
                manager.setAlarmClock(clockInfo, triggerPendingIntent)
            } else {
                // Fallback when exact alarm permission was explicitly revoked on Android 12+
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    triggerPendingIntent
                )
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                triggerPendingIntent
            )
        }

        return triggerAtMillis
    }

    override fun cancel(alarmId: Long) {
        val manager = alarmManager ?: return
        val existing = buildTriggerPendingIntent(
            alarmId = alarmId,
            alarmLabel = "",
            vibrate = true,
            ringtoneUri = null,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            manager.cancel(existing)
            existing.cancel()
        }
    }

    override suspend fun rescheduleAllEnabled(alarmRepository: AlarmRepository) {
        val now = ZonedDateTime.now()
        val alarms = alarmRepository.getAllAlarms()
        for (alarm in alarms) {
            if (alarm.enabled) {
                val next = schedule(alarm, now)
                alarmRepository.updateNextTrigger(alarm.id, next)
            } else {
                cancel(alarm.id)
                if (alarm.nextTriggerEpochMillis != null) {
                    alarmRepository.updateNextTrigger(alarm.id, null)
                }
            }
        }
    }

    private fun buildTriggerPendingIntent(
        alarmId: Long,
        alarmLabel: String,
        vibrate: Boolean,
        ringtoneUri: String?,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(appContext, AlarmTriggerReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_ALARM_LABEL, alarmLabel)
            putExtra(EXTRA_ALARM_VIBRATE, vibrate)
            if (!ringtoneUri.isNullOrBlank()) {
                putExtra(EXTRA_ALARM_RINGTONE_URI, ringtoneUri)
            }
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCodeForAlarm(alarmId),
            intent,
            flags
        )
    }

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.klock.app.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"
        const val EXTRA_ALARM_VIBRATE = "extra_alarm_vibrate"
        const val EXTRA_ALARM_RINGTONE_URI = "extra_alarm_ringtone_uri"

        fun requestCodeForAlarm(alarmId: Long): Int =
            (alarmId xor (alarmId ushr 32)).toInt() and 0x3FFFFFFF

        private fun requestCodeForShow(alarmId: Long): Int =
            ((alarmId xor (alarmId ushr 32)).toInt() and 0x3FFFFFFF) + 0x40000000
    }
}
