package com.klock.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.klock.app.KlockAppContainer
import com.klock.app.alarm.scheduler.AlarmScheduler
import com.klock.app.alarm.scheduler.AlarmTimeCalculator
import com.klock.app.core.device.DeviceHardwareProfile
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.SubtopicConfig
import com.klock.app.domain.model.ThemePreference
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.UserProfile
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import com.klock.app.questions.adaptive.TopicRecommender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DrawerSection(val label: String) {
    ALARMS("Alarms"),
    TOPICS_AND_PROFILE("Topics & Profile"),
    HISTORY("History"),
    SETTINGS("Settings")
}

data class MainUiState(
    val isInitialized: Boolean = false,
    val selectedSection: DrawerSection = DrawerSection.ALARMS,
    val alarms: List<Alarm> = emptyList(),
    val activeSession: ActiveAlarmSession? = null,
    val lastCompletedSession: ActiveAlarmSession? = null,
    val showCompletionBanner: Boolean = false,
    val history: List<QuestionHistoryRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val editingAlarm: Alarm? = null,
    val isEditorOpen: Boolean = false
)

class MainViewModel(
    private val alarmRepository: AlarmRepository,
    private val sessionRepository: SessionRepository,
    private val historyRepository: QuestionHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    val hardwareProfile: DeviceHardwareProfile
) : ViewModel() {

    private val selectedSection = MutableStateFlow(DrawerSection.ALARMS)
    private val editorState = MutableStateFlow<Pair<Boolean, Alarm?>>(false to null)
    private val dismissedCompletionId = MutableStateFlow<String?>(null)

    private val loadedSettingsFlow = settingsRepository.settingsFlow.map { true to it }

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            selectedSection,
            alarmRepository.observeAlarms(),
            sessionRepository.observeActiveSession(),
            sessionRepository.observeCompletedSession("")
        ) { section, alarms, active, lastCompleted ->
            Quad(section, alarms, active, lastCompleted)
        },
        combine(
            historyRepository.observeRecentHistory(limit = hardwareProfile.tier.historyRetentionCount),
            loadedSettingsFlow,
            editorState,
            dismissedCompletionId
        ) { history, loadedSettings, editor, dismissedId ->
            Quad(history, loadedSettings, editor, dismissedId)
        }
    ) { q1, q2 ->
        val completed = q1.d
        val showCompleted = completed != null && completed.sessionId != q2.d
        MainUiState(
            isInitialized = q2.b.first,
            selectedSection = q1.a,
            alarms = q1.b,
            activeSession = q1.c,
            lastCompletedSession = completed,
            showCompletionBanner = showCompleted,
            history = q2.a,
            settings = q2.b.second,
            isEditorOpen = q2.c.first,
            editingAlarm = q2.c.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState(isInitialized = false)
    )

    fun selectSection(section: DrawerSection) {
        selectedSection.value = section
    }

    fun completeOnboarding(
        name: String,
        age: Int?,
        activityOrStudy: String,
        chosenTopics: List<TopicConfig>
    ) {
        val cleanedTopics = chosenTopics
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id }
            .ifEmpty { TopicRecommender.recommendTopics(activityOrStudy, age) }

        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    userProfile = UserProfile(
                        name = name.trim().take(50),
                        age = age?.coerceIn(5, 120),
                        currentActivityOrStudy = activityOrStudy.trim().take(80),
                        onboardingCompleted = true
                    ),
                    configuredTopics = cleanedTopics
                )
            }
        }
    }

    fun updateUserProfile(name: String, age: Int?, activityOrStudy: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    userProfile = current.userProfile.copy(
                        name = name.trim().take(50),
                        age = age?.coerceIn(5, 120),
                        currentActivityOrStudy = activityOrStudy.trim().take(80),
                        onboardingCompleted = true
                    )
                )
            }
        }
    }

    fun applySmartTopicSuggestions(activityOrStudy: String, age: Int?) {
        val suggested = TopicRecommender.recommendTopics(activityOrStudy, age)
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                val existingIds = current.configuredTopics.map { it.id }.toSet()
                val merged = current.configuredTopics + suggested.filterNot { it.id in existingIds }
                current.copy(configuredTopics = merged)
            }
        }
    }

    fun addCustomTopic(rawTopicName: String) {
        val trimmed = rawTopicName.trim().take(48)
        if (trimmed.isBlank()) return
        val id = TopicConfig.slugifyTopicId(trimmed)
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                if (current.configuredTopics.any { it.id == id }) {
                    current.copy(
                        configuredTopics = current.configuredTopics.map {
                            if (it.id == id) it.copy(enabled = true) else it
                        }
                    )
                } else {
                    current.copy(
                        configuredTopics = current.configuredTopics + TopicConfig(
                            id = id,
                            name = trimmed,
                            enabled = true,
                            subtopics = emptyList()
                        )
                    )
                }
            }
        }
    }

    fun toggleTopic(topicId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    configuredTopics = current.configuredTopics.map {
                        if (it.id == topicId) it.copy(enabled = enabled) else it
                    }
                )
            }
        }
    }

    fun removeTopic(topicId: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    configuredTopics = current.configuredTopics.filterNot { it.id == topicId }
                )
            }
        }
    }

    fun addSubtopic(topicId: String, rawSubtopicName: String) {
        val trimmed = rawSubtopicName.trim().take(48)
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    configuredTopics = current.configuredTopics.map { topic ->
                        if (topic.id != topicId) topic
                        else if (topic.subtopics.any { it.name.equals(trimmed, ignoreCase = true) }) topic
                        else topic.copy(
                            subtopics = topic.subtopics + SubtopicConfig(name = trimmed, enabled = true)
                        )
                    }
                )
            }
        }
    }

    fun toggleSubtopic(topicId: String, subtopicName: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    configuredTopics = current.configuredTopics.map { topic ->
                        if (topic.id != topicId) topic
                        else topic.copy(
                            subtopics = topic.subtopics.map { sub ->
                                if (sub.name == subtopicName) sub.copy(enabled = enabled) else sub
                            }
                        )
                    }
                )
            }
        }
    }

    fun removeSubtopic(topicId: String, subtopicName: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { current ->
                current.copy(
                    configuredTopics = current.configuredTopics.map { topic ->
                        if (topic.id != topicId) topic
                        else topic.copy(
                            subtopics = topic.subtopics.filterNot { it.name == subtopicName }
                        )
                    }
                )
            }
        }
    }

    fun openCreateAlarm() {
        val currentSettings = uiState.value.settings
        val template = Alarm(
            id = 0L,
            hour = 7,
            minute = 0,
            label = "",
            enabled = true,
            repeatDays = setOf(1, 2, 3, 4, 5),
            selectedTopicIds = emptySet(), // empty = use all active user topics
            difficultyPreset = currentSettings.defaultDifficulty,
            requiredQuestions = Alarm.DEFAULT_REQUIRED_QUESTIONS,
            vibrate = currentSettings.vibrateByDefault
        )
        editorState.value = true to template
    }

    fun openEditAlarm(alarm: Alarm) {
        editorState.value = true to alarm
    }

    fun closeEditor() {
        editorState.value = false to null
    }

    fun saveAlarm(draft: Alarm) {
        viewModelScope.launch {
            val cleaned = draft.copy(
                requiredQuestions = draft.requiredQuestions.coerceIn(
                    Alarm.MIN_REQUIRED_QUESTIONS,
                    Alarm.MAX_REQUIRED_QUESTIONS
                )
            )
            val persistedId = alarmRepository.upsertAlarm(cleaned)
            val withId = cleaned.copy(id = persistedId)
            if (withId.enabled) {
                val nextTrigger = alarmScheduler.schedule(withId)
                alarmRepository.updateNextTrigger(persistedId, nextTrigger)
            } else {
                alarmScheduler.cancel(persistedId)
                alarmRepository.updateNextTrigger(persistedId, null)
            }
            editorState.value = false to null
        }
    }

    fun toggleAlarm(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val nextMillis = alarmScheduler.schedule(alarm.copy(enabled = true))
                alarmRepository.setAlarmEnabled(alarm.id, true, nextMillis)
            } else {
                alarmScheduler.cancel(alarm.id)
                alarmRepository.setAlarmEnabled(alarm.id, false, null)
            }
        }
    }

    fun deleteAlarm(alarmId: Long) {
        viewModelScope.launch {
            alarmScheduler.cancel(alarmId)
            alarmRepository.deleteAlarm(alarmId)
            editorState.value = false to null
        }
    }

    fun dismissCompletionSummary(sessionId: String) {
        dismissedCompletionId.value = sessionId
    }

    fun updateTheme(theme: ThemePreference) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(themePreference = theme) }
        }
    }

    fun updateDefaultDifficulty(preset: DifficultyPreset) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(defaultDifficulty = preset) }
        }
    }

    fun nextScheduledSummary(alarms: List<Alarm>): String {
        val next = alarms.filter { it.enabled }
            .mapNotNull { alarm ->
                alarm.nextTriggerEpochMillis ?: AlarmTimeCalculator.computeNextTriggerMillis(alarm)
            }
            .minOrNull()
        return if (next == null) {
            "No active alarms"
        } else {
            AlarmTimeCalculator.formatCountdown(next)
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        fun factory(container: KlockAppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        alarmRepository = container.alarmRepository,
                        sessionRepository = container.sessionRepository,
                        historyRepository = container.historyRepository,
                        settingsRepository = container.settingsRepository,
                        alarmScheduler = container.alarmScheduler,
                        hardwareProfile = container.hardwareProfile
                    ) as T
                }
            }
    }
}
