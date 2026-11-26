package com.klock.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.klock.app.KlockAppContainer
import com.klock.app.alarm.scheduler.AlarmScheduler
import com.klock.app.alarm.scheduler.AlarmTimeCalculator
import com.klock.app.core.device.DeviceHardwareProfile
import com.klock.app.core.device.OemGuidance
import com.klock.app.data.repository.ByokSecretStore
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.CategoryPerformanceStat
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.ThemePreference
import com.klock.app.domain.repository.AlarmRepository
import com.klock.app.domain.repository.QuestionHistoryRepository
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TopTab(val label: String) {
    ALARMS("Alarms"),
    HISTORY("History"),
    SETTINGS("Settings")
}

data class MainUiState(
    val selectedTab: TopTab = TopTab.ALARMS,
    val alarms: List<Alarm> = emptyList(),
    val activeSession: ActiveAlarmSession? = null,
    val lastCompletedSession: ActiveAlarmSession? = null,
    val showCompletionBanner: Boolean = false,
    val history: List<QuestionHistoryRecord> = emptyList(),
    val categoryStats: Map<QuestionCategory, CategoryPerformanceStat> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val hasByokApiKey: Boolean = false,
    val editingAlarm: Alarm? = null,
    val isEditorOpen: Boolean = false
)

class MainViewModel(
    private val alarmRepository: AlarmRepository,
    private val sessionRepository: SessionRepository,
    private val historyRepository: QuestionHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val secretStore: ByokSecretStore,
    private val alarmScheduler: AlarmScheduler,
    val hardwareProfile: DeviceHardwareProfile
) : ViewModel() {

    private val selectedTab = MutableStateFlow(TopTab.ALARMS)
    private val editorState = MutableStateFlow<Pair<Boolean, Alarm?>>(false to null)
    private val byokFlag = MutableStateFlow(secretStore.hasApiKey())
    private val statsState = MutableStateFlow<Map<QuestionCategory, CategoryPerformanceStat>>(emptyMap())
    private val dismissedCompletionId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            selectedTab,
            alarmRepository.observeAlarms(),
            sessionRepository.observeActiveSession(),
            sessionRepository.observeCompletedSession("")
        ) { tab, alarms, active, lastCompleted ->
            Quad(tab, alarms, active, lastCompleted)
        },
        combine(
            historyRepository.observeRecentHistory(limit = hardwareProfile.tier.historyRetentionCount),
            settingsRepository.settingsFlow,
            editorState,
            byokFlag,
            combine(statsState, dismissedCompletionId) { s, d -> s to d }
        ) { history, settings, editor, hasKey, statsAndDismissed ->
            Quint(history, settings, editor, hasKey, statsAndDismissed)
        }
    ) { q1, q2 ->
        val completed = q1.d
        val showCompleted = completed != null && completed.sessionId != q2.e.second
        MainUiState(
            selectedTab = q1.a,
            alarms = q1.b,
            activeSession = q1.c,
            lastCompletedSession = completed,
            showCompletionBanner = showCompleted,
            history = q2.a,
            categoryStats = q2.e.first,
            settings = q2.b,
            hasByokApiKey = q2.d,
            isEditorOpen = q2.c.first,
            editingAlarm = q2.c.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState(hasByokApiKey = secretStore.hasApiKey())
    )

    init {
        refreshCategoryStats()
    }

    fun selectTab(tab: TopTab) {
        selectedTab.value = tab
        if (tab == TopTab.HISTORY) {
            refreshCategoryStats()
        }
    }

    fun refreshCategoryStats() {
        viewModelScope.launch {
            statsState.value = historyRepository.getCategoryPerformanceStats()
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
            categories = currentSettings.defaultCategories,
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
            val safeCategories = draft.categories.ifEmpty { QuestionCategory.ALL }
            val cleaned = draft.copy(
                categories = safeCategories,
                requiredQuestions = draft.requiredQuestions.coerceIn(1, 15)
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

    fun updateUse24Hour(use24Hour: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(use24HourFormat = use24Hour) }
        }
    }

    fun updateDefaultDifficulty(preset: DifficultyPreset) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(defaultDifficulty = preset) }
        }
    }

    fun updateDefaultCategories(categories: Set<QuestionCategory>) {
        if (categories.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(defaultCategories = categories) }
        }
    }

    fun updateAiSynthesisEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(enableAiSynthesisWhenKeyConfigured = enabled) }
        }
    }

    fun saveByokApiKey(rawKey: String?) {
        secretStore.setApiKey(rawKey)
        byokFlag.value = secretStore.hasApiKey()
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
    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

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
                        secretStore = container.secretStore,
                        alarmScheduler = container.alarmScheduler,
                        hardwareProfile = container.hardwareProfile
                    ) as T
                }
            }
    }
}
