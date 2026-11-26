package com.klock.app

import android.app.Application
import com.klock.app.alarm.audio.AlarmAudioController
import com.klock.app.alarm.audio.AndroidAlarmAudioController
import com.klock.app.alarm.scheduler.AlarmScheduler
import com.klock.app.alarm.scheduler.AndroidAlarmScheduler
import com.klock.app.alarm.service.AlarmRingingService
import com.klock.app.alarm.session.AlarmSessionCoordinator
import com.klock.app.alarm.worker.AlarmReconcileWorker
import com.klock.app.core.device.DeviceHardwareProfile
import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.device.MemoryTierDetector
import com.klock.app.data.local.KlockDatabase
import com.klock.app.data.repository.AlarmRepositoryImpl
import com.klock.app.data.repository.ByokSecretStore
import com.klock.app.data.repository.DataStoreSettingsRepository
import com.klock.app.data.repository.EncryptedByokSecretStore
import com.klock.app.data.repository.QuestionHistoryRepositoryImpl
import com.klock.app.data.repository.SessionRepositoryImpl
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionEngine
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import com.klock.app.questions.adaptive.AdaptivePlanner
import com.klock.app.questions.engine.LiveQuestionEngine
import com.klock.app.questions.network.LiveEducationalSourcesClient
import com.klock.app.questions.validation.AnswerValidator

/**
 * Lightweight, zero-reflection manual Dependency Injection container.
 * Avoids annotation-processor reflection/startup overhead on 2 GB RAM devices.
 */
class KlockAppContainer(application: Application) {

    val hardwareProfile: DeviceHardwareProfile by lazy {
        MemoryTierDetector.detect(application)
    }

    val database: KlockDatabase by lazy {
        KlockDatabase.build(
            context = application,
            lowRamMode = hardwareProfile.tier == DeviceMemoryTier.LOW_RAM
        )
    }

    val alarmRepository: AlarmRepository by lazy {
        AlarmRepositoryImpl(database.alarmDao())
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(database.sessionDao())
    }

    val historyRepository: QuestionHistoryRepository by lazy {
        QuestionHistoryRepositoryImpl(database.questionHistoryDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(application)
    }

    val secretStore: ByokSecretStore by lazy {
        EncryptedByokSecretStore(application)
    }

    val alarmScheduler: AlarmScheduler by lazy {
        AndroidAlarmScheduler(application)
    }

    val audioController: AlarmAudioController by lazy {
        AndroidAlarmAudioController(application)
    }

    val answerValidator: AnswerValidator by lazy {
        AnswerValidator()
    }

    val adaptivePlanner: AdaptivePlanner by lazy {
        AdaptivePlanner()
    }

    val sourcesClient: LiveEducationalSourcesClient by lazy {
        LiveEducationalSourcesClient(memoryTier = hardwareProfile.tier)
    }

    val questionEngine: QuestionEngine by lazy {
        LiveQuestionEngine(
            sourcesClient = sourcesClient,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            secretStore = secretStore,
            adaptivePlanner = adaptivePlanner,
            answerValidator = answerValidator,
            memoryTier = hardwareProfile.tier
        )
    }

    val sessionCoordinator: AlarmSessionCoordinator by lazy {
        AlarmSessionCoordinator(
            alarmRepository = alarmRepository,
            sessionRepository = sessionRepository,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            questionEngine = questionEngine,
            answerValidator = answerValidator,
            alarmScheduler = alarmScheduler,
            audioController = audioController,
            memoryTier = hardwareProfile.tier
        )
    }
}

class KlockApplication : Application() {

    lateinit var container: KlockAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = KlockAppContainer(this)
        AlarmRingingService.ensureAlarmNotificationChannel(this)
        AlarmReconcileWorker.enqueuePeriodic(this)
    }
}
