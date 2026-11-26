package com.klock.app.ui.active

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.klock.app.KlockApplication
import com.klock.app.alarm.service.AlarmRingingService
import com.klock.app.alarm.session.AlarmSessionCoordinator
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.OverrideReason
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.ui.components.CodeWorkbenchBlock
import com.klock.app.ui.components.HairlineCard
import com.klock.app.ui.components.MechanicalLockBar
import com.klock.app.ui.components.TactilePrimaryButton
import com.klock.app.ui.components.TactileSecondaryButton
import com.klock.app.ui.components.TechnicalTag
import com.klock.app.ui.theme.KlockTheme
import com.klock.app.ui.theme.LocalKlockPalette
import com.klock.app.ui.viewmodel.ActiveAlarmUiState
import com.klock.app.ui.viewmodel.ActiveAlarmViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActiveAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        configureLockScreenPresentation()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as KlockApplication).container

        setContent {
            val vm: ActiveAlarmViewModel = viewModel(
                factory = ActiveAlarmViewModel.factory(container)
            )
            val state by vm.uiState.collectAsStateWithLifecycle()

            KlockTheme(themePreference = state.settings.themePreference) {
                val isLocked = state.session != null && !state.session!!.completed
                BackHandler(enabled = isLocked) {
                    // Back navigation blocked until all 5 questions are answered.
                }

                ActiveAlarmRootScreen(
                    state = state,
                    onSelectOption = { option ->
                        vm.onAnswerInputChanged(option)
                        vm.submitAnswer(
                            onAllFiveSolved = {
                                AlarmRingingService.requestVerifiedStop(this@ActiveAlarmActivity)
                            }
                        )
                    },
                    onRetryFetch = vm::retryFetchQuestions,
                    onOpenOverride = vm::openEmergencyOverride,
                    onCloseOverride = vm::closeEmergencyOverride,
                    onConfirmOverride = { reason, holdMs, code ->
                        vm.confirmEmergencyOverride(
                            reason = reason,
                            holdDurationMillis = holdMs,
                            typedConfirmationCode = code,
                            onCompleted = {
                                AlarmRingingService.requestVerifiedStop(this@ActiveAlarmActivity)
                            }
                        )
                    },
                    onCloseFinished = { finish() }
                )
            }
        }
    }

    private fun configureLockScreenPresentation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isKeyguardSecure == false) {
                km.requestDismissKeyguard(this, null)
            }
        }
    }
}

@Composable
fun ActiveAlarmRootScreen(
    state: ActiveAlarmUiState,
    onSelectOption: (String) -> Unit,
    onRetryFetch: () -> Unit,
    onOpenOverride: () -> Unit,
    onCloseOverride: () -> Unit,
    onConfirmOverride: (OverrideReason, Long, String) -> Unit,
    onCloseFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val active = state.session

    Surface(
        modifier = modifier.fillMaxSize(),
        color = palette.canvas
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            when {
                active != null && !active.completed -> {
                    if (state.isOverrideDialogOpen) {
                        EmergencyOverrideView(
                            overrideCode = active.emergencyOverrideCode,
                            errorMessage = state.overrideError,
                            onCancel = onCloseOverride,
                            onConfirm = onConfirmOverride
                        )
                    } else {
                        ActiveQuestionDismissalView(
                            session = active,
                            feedbackMessage = state.feedbackBanner?.message,
                            feedbackIsError = state.feedbackBanner?.isError ?: false,
                            isSubmitting = state.isSubmitting,
                            onSelectOption = onSelectOption,
                            onRetryFetch = onRetryFetch,
                            onOpenOverride = onOpenOverride
                        )
                    }
                }

                state.completedSession != null -> {
                    ActiveCompletionScreen(
                        session = state.completedSession,
                        onDone = onCloseFinished
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No active alarm",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TactilePrimaryButton(text = "Back to Klock", onClick = onCloseFinished)
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveQuestionDismissalView(
    session: ActiveAlarmSession,
    feedbackMessage: String?,
    feedbackIsError: Boolean,
    isSubmitting: Boolean,
    onSelectOption: (String) -> Unit,
    onRetryFetch: () -> Unit,
    onOpenOverride: () -> Unit
) {
    val palette = LocalKlockPalette.current
    var liveTimeText by remember {
        mutableStateOf(SimpleDateFormat("h:mm a", Locale.US).format(Date()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            liveTimeText = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            delay(15_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header: 12-hour live clock + label + Emergency stop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = liveTimeText,
                    style = MaterialTheme.typography.displayMedium,
                    color = palette.inkPrimary
                )
                if (session.alarmLabel.isNotBlank()) {
                    Text(
                        text = session.alarmLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary
                    )
                }
            }

            Text(
                text = "Emergency stop",
                style = MaterialTheme.typography.labelLarge,
                color = palette.inkMuted,
                modifier = Modifier
                    .testTag("btn_emergency_override")
                    .clickable(role = Role.Button, onClick = onOpenOverride)
                    .padding(horizontal = 6.dp, vertical = 8.dp)
            )
        }

        // 5-Step Question Progress Bar
        MechanicalLockBar(
            requiredCount = session.requiredCount,
            solvedCount = session.solvedCount,
            currentIndex = session.currentQuestionIndex
        )

        val currentQuestion = session.currentQuestion

        when {
            currentQuestion != null -> {
                McqQuestionCardBody(
                    question = currentQuestion,
                    feedbackMessage = feedbackMessage,
                    feedbackIsError = feedbackIsError,
                    isSubmitting = isSubmitting,
                    onSelectOption = onSelectOption
                )
            }

            session.retrievalStatus == RetrievalStatus.FETCHING -> {
                HairlineCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Loading questions…",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = palette.signal,
                            trackColor = palette.inset
                        )
                        Text(
                            text = "Fetching 5 fresh multiple-choice questions to turn off your alarm.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary
                        )
                    }
                }
            }

            else -> {
                HairlineCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .testTag("network_error_panel"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "No internet connection",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.danger
                        )
                        Text(
                            text = "Turn on Wi-Fi or mobile data and tap Try Again to load your questions.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.inkPrimary
                        )
                        TactilePrimaryButton(
                            text = "Try Again",
                            onClick = onRetryFetch,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_retry_fetch")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McqQuestionCardBody(
    question: AlarmQuestion,
    feedbackMessage: String?,
    feedbackIsError: Boolean,
    isSubmitting: Boolean,
    onSelectOption: (String) -> Unit
) {
    val palette = LocalKlockPalette.current

    HairlineCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TechnicalTag(text = question.category.displayName, highlighted = true)

            Text(
                text = question.prompt,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = palette.inkPrimary,
                modifier = Modifier.testTag("active_question_prompt")
            )

            if (!question.codeSnippet.isNullOrBlank()) {
                CodeWorkbenchBlock(code = question.codeSnippet)
            }

            if (!feedbackMessage.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (feedbackIsError) palette.danger.copy(alpha = 0.12f)
                            else palette.verified.copy(alpha = 0.12f)
                        )
                        .border(
                            1.dp,
                            if (feedbackIsError) palette.danger else palette.verified,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(12.dp)
                        .testTag("answer_feedback_banner")
                ) {
                    Text(
                        text = feedbackMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (feedbackIsError) palette.danger else palette.verified
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEachIndexed { index, optionText ->
                    val letter = ('A' + index).toString()
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = palette.inset,
                        border = BorderStroke(1.dp, palette.borderStrong),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("option_$index")
                            .clickable(enabled = !isSubmitting, role = Role.Button) {
                                onSelectOption(optionText)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(1.dp, palette.borderStrong, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = letter,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.signal
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = optionText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.inkPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmergencyOverrideView(
    overrideCode: String,
    errorMessage: String?,
    onCancel: () -> Unit,
    onConfirm: (OverrideReason, Long, String) -> Unit
) {
    val palette = LocalKlockPalette.current
    var selectedReason by remember { mutableStateOf(OverrideReason.SAFETY_MEDICAL) }
    var typedCode by remember { mutableStateOf("") }
    var isHolding by remember { mutableStateOf(false) }
    var heldMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val start = System.currentTimeMillis()
            while (isHolding && heldMillis < AlarmSessionCoordinator.MINIMUM_OVERRIDE_HOLD_MS) {
                delay(80L)
                heldMillis = (System.currentTimeMillis() - start)
                    .coerceAtMost(AlarmSessionCoordinator.MINIMUM_OVERRIDE_HOLD_MS)
            }
            if (heldMillis >= AlarmSessionCoordinator.MINIMUM_OVERRIDE_HOLD_MS) {
                onConfirm(selectedReason, heldMillis, typedCode)
            }
        } else {
            heldMillis = 0L
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Emergency Stop",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.danger
            )
            Text(
                text = "Back",
                style = MaterialTheme.typography.labelLarge,
                color = palette.inkPrimary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(8.dp)
            )
        }

        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Use only in an emergency or when you have no internet access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary
                )

                OverrideReason.entries.forEach { reason ->
                    val active = reason == selectedReason
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) palette.inset else palette.surface)
                            .border(
                                1.dp,
                                if (active) palette.signal else palette.border,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedReason = reason }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reason.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                    }
                }

                HorizontalDivider(color = palette.border)

                val progress = (heldMillis.toFloat() / AlarmSessionCoordinator.MINIMUM_OVERRIDE_HOLD_MS.toFloat())
                    .coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.danger.copy(alpha = 0.14f))
                        .border(1.dp, palette.danger, RoundedCornerShape(6.dp))
                        .pointerInput(selectedReason) {
                            detectTapGestures(
                                onPress = {
                                    isHolding = true
                                    tryAwaitRelease()
                                    isHolding = false
                                }
                            )
                        }
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isHolding) {
                                "Keep holding… ${(heldMillis / 1000.0).let { String.format(Locale.US, "%.1f", it) }} / 8.0s"
                            } else {
                                "Hold for 8 seconds to stop alarm"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.danger
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = palette.danger,
                            trackColor = palette.inset
                        )
                    }
                }

                Text(
                    text = "Or type code $overrideCode:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary
                )
                OutlinedTextField(
                    value = typedCode,
                    onValueChange = { typedCode = it },
                    label = { Text(overrideCode) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_override_code"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.inset,
                        unfocusedContainerColor = palette.inset
                    )
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.danger
                    )
                }

                TactileSecondaryButton(
                    text = "Confirm Emergency Stop",
                    enabled = typedCode.isNotBlank(),
                    onClick = { onConfirm(selectedReason, heldMillis, typedCode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_confirm_override_code")
                )
            }
        }
    }
}

@Composable
fun ActiveCompletionScreen(
    session: ActiveAlarmSession,
    onDone: () -> Unit
) {
    val palette = LocalKlockPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("completion_screen"),
        verticalArrangement = Arrangement.Center
    ) {
        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (session.dismissedViaOverride) "Alarm stopped" else "Good morning!",
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.inkPrimary
                )
                Text(
                    text = if (session.dismissedViaOverride) {
                        "Your alarm was turned off using emergency stop."
                    } else {
                        "You answered all ${session.requiredCount} questions correctly. Your alarm is off."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.inkSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                TactilePrimaryButton(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_finish_completion")
                )
            }
        }
    }
}
