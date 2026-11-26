package com.klock.app.alarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.klock.app.KlockApplication
import com.klock.app.R
import com.klock.app.alarm.scheduler.AndroidAlarmScheduler
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.ui.active.ActiveAlarmActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AlarmRingingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionObserverJob: Job? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureAlarmNotificationChannel(this)
        registerAutoRetryNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as KlockApplication).container

        if (intent?.action == ACTION_STOP_AFTER_VERIFIED_COMPLETION) {
            serviceScope.launch {
                val active = container.sessionRepository.getActiveSession()
                // Security invariant: never stop if an unfinished session still exists!
                if (active == null || active.completed) {
                    stopRingingAndService()
                }
            }
            return START_NOT_STICKY
        }

        val alarmId = intent?.getLongExtra(AndroidAlarmScheduler.EXTRA_ALARM_ID, -1L) ?: -1L
        val alarmLabel = intent?.getStringExtra(AndroidAlarmScheduler.EXTRA_ALARM_LABEL).orEmpty()
        val vibrate = intent?.getBooleanExtra(AndroidAlarmScheduler.EXTRA_ALARM_VIBRATE, true) ?: true
        val ringtoneUri = intent?.getStringExtra(AndroidAlarmScheduler.EXTRA_ALARM_RINGTONE_URI)

        // 1. Acquire CPU WakeLock immediately so screen-off / Doze cannot suspend alarm startup.
        acquireBoundedWakeLock()

        // 2. Promote to Foreground immediately with full-screen lock-screen intent and ZERO dismiss buttons.
        val notification = buildOngoingAlarmNotification(
            label = alarmLabel,
            progressText = "Alarm ringing · Tap to answer"
        )
        startForegroundCompat(notification)

        // 3. Start alarm audio IMMEDIATELY before touching database or network!
        container.audioController.startRinging(
            ringtoneUri = ringtoneUri,
            vibrate = vibrate,
            gradualVolumeRamp = false
        )

        // 4. Launch ActiveAlarmActivity directly when allowed by foreground exemptions.
        launchActiveAlarmActivity()

        // 5. Initialize/recover session and fetch 5 fresh internet questions asynchronously.
        serviceScope.launch {
            val session = container.sessionCoordinator.onAlarmTriggered(
                alarmId = alarmId,
                fallbackLabel = alarmLabel
            )
            observeSessionProgress()
            if (session.questions.size < session.requiredCount ||
                session.retrievalStatus != RetrievalStatus.READY
            ) {
                container.sessionCoordinator.fetchOrRetryQuestions()
            }
        }

        return START_STICKY
    }

    private fun observeSessionProgress() {
        if (sessionObserverJob?.isActive == true) return
        val container = (application as KlockApplication).container
        sessionObserverJob = serviceScope.launch {
            container.sessionRepository.observeActiveSession().collectLatest { session ->
                if (session == null || session.completed) {
                    stopRingingAndService()
                } else {
                    val statusText = when (session.retrievalStatus) {
                        RetrievalStatus.FETCHING ->
                            "${session.solvedCount} of ${session.requiredCount} answered · Loading questions…"
                        RetrievalStatus.READY ->
                            "${session.solvedCount} of ${session.requiredCount} answered · Tap to answer"
                        RetrievalStatus.NETWORK_ERROR ->
                            "${session.solvedCount} of ${session.requiredCount} answered · Connect to internet"
                    }
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(
                        NOTIFICATION_ID,
                        buildOngoingAlarmNotification(
                            label = session.alarmLabel,
                            progressText = statusText
                        )
                    )
                }
            }
        }
    }

    private fun registerAutoRetryNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val container = (application as KlockApplication).container
                serviceScope.launch {
                    val active = container.sessionRepository.getActiveSession()
                    if (active != null &&
                        !active.completed &&
                        active.retrievalStatus == RetrievalStatus.NETWORK_ERROR
                    ) {
                        container.sessionCoordinator.fetchOrRetryQuestions()
                    }
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            connectivityCallback = callback
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildOngoingAlarmNotification(
        label: String,
        progressText: String
    ): Notification {
        val fullScreenIntent = Intent(this, ActiveAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this,
            9001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (label.isBlank()) "Klock Alarm Active" else "Klock · $label"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_klock)
            .setContentTitle(title)
            .setContentText(progressText)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun launchActiveAlarmActivity() {
        runCatching {
            val activityIntent = Intent(this, ActiveAlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(activityIntent)
        }
    }

    private fun acquireBoundedWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Klock::AlarmRingingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun stopRingingAndService() {
        val container = (application as KlockApplication).container
        container.audioController.stopRinging()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        connectivityCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        connectivityCallback = null
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "klock_active_alarm_channel"
        const val NOTIFICATION_ID = 4201
        private const val MAX_WAKELOCK_MS = 30 * 60 * 1000L
        const val ACTION_STOP_AFTER_VERIFIED_COMPLETION =
            "com.klock.app.action.STOP_AFTER_VERIFIED_COMPLETION"

        fun startAlarmService(
            context: Context,
            alarmId: Long,
            alarmLabel: String = "",
            vibrate: Boolean = true,
            ringtoneUri: String? = null
        ) {
            val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                putExtra(AndroidAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AndroidAlarmScheduler.EXTRA_ALARM_LABEL, alarmLabel)
                putExtra(AndroidAlarmScheduler.EXTRA_ALARM_VIBRATE, vibrate)
                if (!ringtoneUri.isNullOrBlank()) {
                    putExtra(AndroidAlarmScheduler.EXTRA_ALARM_RINGTONE_URI, ringtoneUri)
                }
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun requestVerifiedStop(context: Context) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_STOP_AFTER_VERIFIED_COMPLETION
            }
            runCatching { context.startService(intent) }
        }

        fun ensureAlarmNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alarm_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                // Audio is played directly via AlarmAudioController on USAGE_ALARM so notification sound doesn't double-play
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
