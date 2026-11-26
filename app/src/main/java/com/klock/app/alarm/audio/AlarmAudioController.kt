package com.klock.app.alarm.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

interface AlarmAudioController {
    val isRinging: StateFlow<Boolean>
    fun startRinging(
        ringtoneUri: String? = null,
        vibrate: Boolean = true,
        gradualVolumeRamp: Boolean = false
    )
    fun stopRinging()
}

/**
 * Reliable, zero-network-dependency alarm audio engine:
 * - Plays immediately on STREAM_ALARM / USAGE_ALARM so Do Not Disturb / Ringer Silent does not mute it.
 * - Ensures STREAM_ALARM volume is at least 35% of max during an active alarm and restores user volume on stop.
 * - Falls back to a synthesized PCM dual-frequency pulse via [AudioTrack] if OEM MediaPlayer or ringtone URI fails.
 */
class AndroidAlarmAudioController(
    context: Context
) : AlarmAudioController {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isRinging = MutableStateFlow(false)
    override val isRinging: StateFlow<Boolean> = _isRinging.asStateFlow()

    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var fallbackToneJob: Job? = null
    private var volumeRampJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAlarmVolume: Int? = null

    override fun startRinging(
        ringtoneUri: String?,
        vibrate: Boolean,
        gradualVolumeRamp: Boolean
    ) {
        synchronized(lock) {
            if (_isRinging.value) return
            _isRinging.value = true

            ensureMinimumAlarmStreamVolume()
            requestAlarmAudioFocus()

            val startedMediaPlayer = tryStartMediaPlayer(ringtoneUri, gradualVolumeRamp)
            if (!startedMediaPlayer) {
                startSynthesizedFallbackTone()
            }

            if (vibrate) {
                startVibration()
            }
        }
    }

    override fun stopRinging() {
        synchronized(lock) {
            _isRinging.value = false

            volumeRampJob?.cancel()
            volumeRampJob = null

            fallbackToneJob?.cancel()
            fallbackToneJob = null

            runCatching {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) player.stop()
                    player.reset()
                    player.release()
                }
            }
            mediaPlayer = null

            stopVibration()
            abandonAlarmAudioFocus()
            restoreAlarmStreamVolume()
        }
    }

    private fun tryStartMediaPlayer(ringtoneUri: String?, gradualVolumeRamp: Boolean): Boolean {
        val candidateUris = buildList {
            if (!ringtoneUri.isNullOrBlank()) {
                runCatching { Uri.parse(ringtoneUri) }.getOrNull()?.let { add(it) }
            }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { add(it) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { add(it) }
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        for (uri in candidateUris) {
            try {
                val player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(appContext, uri)
                    isLooping = true
                    prepare()
                    val initialVol = if (gradualVolumeRamp) 0.2f else 1.0f
                    setVolume(initialVol, initialVol)
                    start()
                }
                mediaPlayer = player
                if (gradualVolumeRamp) {
                    volumeRampJob = scope.launch {
                        for (step in 1..8) {
                            delay(2_500L)
                            val fraction = (0.2f + step * 0.1f).coerceAtMost(1.0f)
                            synchronized(lock) {
                                runCatching { mediaPlayer?.setVolume(fraction, fraction) }
                            }
                        }
                    }
                }
                return true
            } catch (_: Exception) {
                // Try next URI or synthesized fallback
            }
        }
        return false
    }

    private fun startSynthesizedFallbackTone() {
        fallbackToneJob = scope.launch {
            val sampleRate = 16_000
            val pulseSamples = sampleRate / 4 // 250ms crisp Braun-style beep
            val pcm = ShortArray(pulseSamples)
            for (i in 0 until pulseSamples) {
                val t = i.toDouble() / sampleRate
                val wave = 0.6 * sin(2.0 * PI * 880.0 * t) + 0.4 * sin(2.0 * PI * 1320.0 * t)
                pcm[i] = (wave * Short.MAX_VALUE * 0.75).toInt().toShort()
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(pulseSamples * 2)

            val track = runCatching {
                AudioTrack(
                    attributes,
                    format,
                    minBuf,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }.getOrNull() ?: return@launch

            try {
                track.play()
                while (isActive && _isRinging.value) {
                    // Braun ET66 double-pip cadence
                    track.write(pcm, 0, pcm.size)
                    delay(120L)
                    track.write(pcm, 0, pcm.size)
                    delay(650L)
                }
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    private fun ensureMinimumAlarmStreamVolume() {
        val am = audioManager ?: return
        runCatching {
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val minTarget = ceil(maxVol * 0.35).toInt().coerceAtLeast(1)
            if (maxVol > 0 && currentVol < minTarget) {
                previousAlarmVolume = currentVol
                am.setStreamVolume(AudioManager.STREAM_ALARM, minTarget, 0)
            }
        }
    }

    private fun restoreAlarmStreamVolume() {
        val prev = previousAlarmVolume ?: return
        previousAlarmVolume = null
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
        }
    }

    private fun requestAlarmAudioFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            audioFocusRequest = req
            runCatching { am.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonAlarmAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }

    private fun startVibration() {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 450L, 350L, 450L, 900L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        }
    }

    private fun stopVibration() {
        runCatching { resolveVibrator()?.cancel() }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
