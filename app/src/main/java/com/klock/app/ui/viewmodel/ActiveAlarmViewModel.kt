package com.klock.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.klock.app.KlockAppContainer
import com.klock.app.alarm.audio.AlarmAudioController
import com.klock.app.alarm.session.AlarmSessionCoordinator
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.AnswerEvaluationResult
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.OverrideReason
import com.klock.app.domain.repository.SessionRepository
import com.klock.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveAlarmUiState(
    val session: ActiveAlarmSession? = null,
    val completedSession: ActiveAlarmSession? = null,
    val settings: AppSettings = AppSettings(),
    val isAudioRinging: Boolean = false,
    val draftAnswer: String = "",
    val feedbackBanner: FeedbackBanner? = null,
    val isSubmitting: Boolean = false,
    val isOverrideDialogOpen: Boolean = false,
    val overrideError: String? = null
)

data class FeedbackBanner(
    val isError: Boolean,
    val message: String,
    val attemptNumber: Int = 0
)

class ActiveAlarmViewModel(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val coordinator: AlarmSessionCoordinator,
    private val audioController: AlarmAudioController
) : ViewModel() {

    private val draftAnswer = MutableStateFlow("")
    private val feedbackBanner = MutableStateFlow<FeedbackBanner?>(null)
    private val isSubmitting = MutableStateFlow(false)
    private val overrideOpen = MutableStateFlow(false)
    private val overrideError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ActiveAlarmUiState> = combine(
        combine(
            sessionRepository.observeActiveSession(),
            sessionRepository.observeCompletedSession(""),
            settingsRepository.settingsFlow,
            audioController.isRinging
        ) { active, completed, settings, ringing ->
            Quad(active, completed, settings, ringing)
        },
        combine(
            draftAnswer,
            feedbackBanner,
            isSubmitting,
            overrideOpen,
            overrideError
        ) { draft, feedback, submitting, ovOpen, ovErr ->
            Quint(draft, feedback, submitting, ovOpen, ovErr)
        }
    ) { q1, q2 ->
        ActiveAlarmUiState(
            session = q1.a,
            completedSession = q1.b,
            settings = q1.c,
            isAudioRinging = q1.d,
            draftAnswer = q2.a,
            feedbackBanner = q2.b,
            isSubmitting = q2.c,
            isOverrideDialogOpen = q2.d,
            overrideError = q2.e
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ActiveAlarmUiState()
    )

    fun onAnswerInputChanged(value: String) {
        draftAnswer.value = value
    }

    fun appendSnippetToken(token: String) {
        val current = draftAnswer.value
        val needsSpace = current.isNotEmpty() && !current.endsWith(" ") && !current.endsWith("(") && !current.endsWith("[")
        draftAnswer.value = if (needsSpace) "$current $token" else "$current$token"
    }

    fun submitAnswer(onAllFiveSolved: () -> Unit = {}) {
        if (isSubmitting.value) return
        val answerText = draftAnswer.value
        isSubmitting.value = true
        viewModelScope.launch {
            try {
                val (result, updated) = coordinator.submitAnswer(
                    rawAnswer = answerText,
                    onSessionCompleted = onAllFiveSolved
                )
                when (result) {
                    is AnswerEvaluationResult.Correct -> {
                        draftAnswer.value = ""
                        feedbackBanner.value = if (updated?.completed == true) {
                            FeedbackBanner(
                                isError = false,
                                message = "All 5 answered — alarm stopped!"
                            )
                        } else {
                            FeedbackBanner(
                                isError = false,
                                message = "Correct! (${updated?.solvedCount ?: 1} of ${updated?.requiredCount ?: 5})"
                            )
                        }
                    }
                    is AnswerEvaluationResult.Incorrect -> {
                        feedbackBanner.value = FeedbackBanner(
                            isError = true,
                            message = result.hint,
                            attemptNumber = result.attemptNumber
                        )
                    }
                }
            } finally {
                isSubmitting.value = false
            }
        }
    }

    fun retryFetchQuestions() {
        feedbackBanner.value = null
        viewModelScope.launch {
            coordinator.fetchOrRetryQuestions()
        }
    }

    fun openEmergencyOverride() {
        overrideError.value = null
        overrideOpen.value = true
    }

    fun closeEmergencyOverride() {
        overrideError.value = null
        overrideOpen.value = false
    }

    fun confirmEmergencyOverride(
        reason: OverrideReason,
        holdDurationMillis: Long,
        typedConfirmationCode: String,
        onCompleted: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = coordinator.executeEmergencyOverride(
                reason = reason,
                holdDurationMillis = holdDurationMillis,
                typedConfirmationCode = typedConfirmationCode,
                onOverrideCompleted = onCompleted
            )
            res.fold(
                onSuccess = {
                    overrideOpen.value = false
                    overrideError.value = null
                },
                onFailure = { err ->
                    overrideError.value = err.message
                }
            )
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    companion object {
        fun factory(container: KlockAppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ActiveAlarmViewModel(
                        sessionRepository = container.sessionRepository,
                        settingsRepository = container.settingsRepository,
                        coordinator = container.sessionCoordinator,
                        audioController = container.audioController
                    ) as T
                }
            }
    }
}
