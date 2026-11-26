package com.klock.app.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.klock.app.alarm.scheduler.AlarmTimeCalculator
import com.klock.app.core.device.OemCompatibilityAdvisor
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.TopicConfig
import com.klock.app.ui.components.HairlineCard
import com.klock.app.ui.components.MechanicalToggle
import com.klock.app.ui.components.TactilePrimaryButton
import com.klock.app.ui.components.TactileSecondaryButton
import com.klock.app.ui.components.TechnicalTag
import com.klock.app.ui.theme.KlockMono
import com.klock.app.ui.theme.LocalKlockPalette

private val DAY_LABELS = listOf(
    1 to "M",
    2 to "T",
    3 to "W",
    4 to "T",
    5 to "F",
    6 to "S",
    7 to "S"
)

@Composable
fun AlarmListSection(
    alarms: List<Alarm>,
    configuredTopics: List<TopicConfig>,
    userName: String,
    canScheduleExact: Boolean,
    activeSession: ActiveAlarmSession?,
    completedSessionToReview: ActiveAlarmSession?,
    onOpenActiveSession: () -> Unit,
    onDismissCompletedReview: (String) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onEditAlarm: (Alarm) -> Unit,
    onCreateAlarm: () -> Unit,
    onOpenTopicsManager: () -> Unit,
    onTriggerNowForTest: (Alarm) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val context = LocalContext.current

    var notifPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermissionGranted = granted
    }

    val fullScreenAllowed = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.canUseFullScreenIntent() ?: true
        } else true
    }

    val oemGuidance = remember(context) { OemCompatibilityAdvisor.buildGuidance(context) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("alarm_list"),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 88.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (activeSession != null && !activeSession.completed) {
                item(key = "active_banner") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = palette.signal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = onOpenActiveSession)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Alarm ringing · ${activeSession.solvedCount} of ${activeSession.requiredCount} answered",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.signalOn
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap to return to questions",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.signalOn
                            )
                        }
                    }
                }
            }

            if (completedSessionToReview != null && activeSession == null) {
                item(key = "completion_summary_${completedSessionToReview.sessionId}") {
                    CompletionSummaryCard(
                        session = completedSessionToReview,
                        userName = userName,
                        onDismiss = { onDismissCompletedReview(completedSessionToReview.sessionId) }
                    )
                }
            }

            if (!canScheduleExact || !notifPermissionGranted || !fullScreenAllowed || !oemGuidance.isBatteryUnrestricted) {
                item(key = "readiness_strip") {
                    ReadinessDiagnosticsCard(
                        canScheduleExact = canScheduleExact,
                        notifPermissionGranted = notifPermissionGranted,
                        fullScreenAllowed = fullScreenAllowed,
                        isBatteryUnrestricted = oemGuidance.isBatteryUnrestricted,
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
            }

            if (configuredTopics.none { it.enabled }) {
                item(key = "no_topics_warning") {
                    HairlineCard(onClick = onOpenTopicsManager) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Choose your question topics",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "Configure ->",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.signal
                            )
                        }
                    }
                }
            }

            if (alarms.isEmpty()) {
                item(key = "empty_state") {
                    HairlineCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (userName.isNotBlank()) "Hi, $userName" else "No alarms yet",
                                style = MaterialTheme.typography.headlineMedium,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "Set an alarm and wake up by answering fresh multiple-choice questions on your chosen topics.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TactilePrimaryButton(
                                text = "+ Add Alarm",
                                onClick = onCreateAlarm
                            )
                        }
                    }
                }
            } else {
                items(items = alarms, key = { it.id }) { alarm ->
                    AlarmRowCard(
                        alarm = alarm,
                        configuredTopics = configuredTopics,
                        onToggle = { enabled -> onToggleAlarm(alarm, enabled) },
                        onClick = { onEditAlarm(alarm) },
                        onTestRing = { onTriggerNowForTest(alarm) }
                    )
                }
            }
        }

        if (alarms.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(palette.canvas.copy(alpha = 0.96f))
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom = contentPadding.calculateBottomPadding() + 12.dp
                    )
            ) {
                TactilePrimaryButton(
                    text = "+ Add Alarm",
                    onClick = onCreateAlarm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_new_alarm")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmRowCard(
    alarm: Alarm,
    configuredTopics: List<TopicConfig>,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onTestRing: () -> Unit
) {
    val palette = LocalKlockPalette.current
    val (clockDigits, period) = AlarmTimeCalculator.formatTriggerClock(
        hour = alarm.hour,
        minute = alarm.minute,
        use24Hour = false
    )

    val activeTopicNames = if (alarm.selectedTopicIds.isEmpty()) {
        val enabled = configuredTopics.filter { it.enabled }.map { it.name }
        if (enabled.isEmpty()) "Your topics"
        else if (enabled.size <= 2) enabled.joinToString(", ")
        else "${enabled.take(2).joinToString(", ")} +${enabled.size - 2}"
    } else {
        val matched = configuredTopics.filter { it.id in alarm.selectedTopicIds }.map { it.name }
        if (matched.isEmpty()) "Your topics"
        else if (matched.size <= 2) matched.joinToString(", ")
        else "${matched.take(2).joinToString(", ")} +${matched.size - 2}"
    }

    HairlineCard(onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = clockDigits,
                        style = MaterialTheme.typography.displayLarge,
                        color = if (alarm.enabled) palette.inkPrimary else palette.inkMuted
                    )
                    if (period != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = period,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (alarm.enabled) palette.inkSecondary else palette.inkMuted,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                MechanicalToggle(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    contentDescriptionText = "Toggle alarm at $clockDigits ${period.orEmpty()}"
                )
            }

            if (alarm.label.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = alarm.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (alarm.enabled) palette.inkPrimary else palette.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (alarm.repeatDays.isEmpty()) {
                        Text(
                            text = "Once",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkMuted
                        )
                    } else if (alarm.repeatDays.size == 7) {
                        Text(
                            text = "Every day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (alarm.enabled) palette.inkSecondary else palette.inkMuted
                        )
                    } else if (alarm.repeatDays == setOf(1, 2, 3, 4, 5)) {
                        Text(
                            text = "Weekdays",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (alarm.enabled) palette.inkSecondary else palette.inkMuted
                        )
                    } else {
                        DAY_LABELS.forEach { (dayNum, letter) ->
                            val active = dayNum in alarm.repeatDays
                            Text(
                                text = letter,
                                fontFamily = KlockMono,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active && alarm.enabled) palette.signal
                                else if (active) palette.inkSecondary
                                else palette.inkMuted.copy(alpha = 0.45f)
                            )
                        }
                    }
                }

                if (alarm.enabled && alarm.nextTriggerEpochMillis != null) {
                    Text(
                        text = AlarmTimeCalculator.formatCountdown(alarm.nextTriggerEpochMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = palette.border)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    TechnicalTag(text = "${alarm.requiredQuestions} Qs")
                    TechnicalTag(text = activeTopicNames)
                    TechnicalTag(text = alarm.difficultyPreset.displayName)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Test",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.signal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onTestRing)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadinessDiagnosticsCard(
    canScheduleExact: Boolean,
    notifPermissionGranted: Boolean,
    fullScreenAllowed: Boolean,
    isBatteryUnrestricted: Boolean,
    onRequestNotifications: () -> Unit
) {
    val palette = LocalKlockPalette.current
    val context = LocalContext.current

    HairlineCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Setup needed for reliable alarms",
                style = MaterialTheme.typography.titleMedium,
                color = palette.signal
            )
            if (!canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ReadinessActionRow(
                    label = "Allow exact alarm timing",
                    actionLabel = "Allow",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            if (!notifPermissionGranted) {
                ReadinessActionRow(
                    label = "Allow alarm notifications",
                    actionLabel = "Allow",
                    onClick = onRequestNotifications
                )
            }
            if (!fullScreenAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ReadinessActionRow(
                    label = "Allow lock-screen alarm display",
                    actionLabel = "Enable",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            if (!isBatteryUnrestricted) {
                ReadinessActionRow(
                    label = "Turn off battery saver restrictions for Klock",
                    actionLabel = "Fix",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(OemCompatibilityAdvisor.buildBatterySettingsIntent(context))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ReadinessActionRow(
    label: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    val palette = LocalKlockPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = palette.signal,
            modifier = Modifier
                .border(1.dp, palette.signal, RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun CompletionSummaryCard(
    session: ActiveAlarmSession,
    userName: String,
    onDismiss: () -> Unit
) {
    val palette = LocalKlockPalette.current
    val greeting = if (userName.isBlank()) "Good morning!" else "Good morning, $userName!"

    HairlineCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (session.dismissedViaOverride) "Alarm stopped" else greeting,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (session.dismissedViaOverride) palette.signal else palette.verified
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (session.dismissedViaOverride) {
                        "Stopped using emergency stop."
                    } else {
                        "All ${session.requiredCount} questions answered."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary
                )
            }
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelLarge,
                color = palette.inkMuted,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(6.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditorSheet(
    initialAlarm: Alarm,
    configuredTopics: List<TopicConfig>,
    onOpenTopicsManager: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: ((Long) -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val (initialHour12, initialIsPm) = remember(initialAlarm) {
        AlarmTimeCalculator.to12Hour(initialAlarm.hour)
    }
    var hourText by remember(initialAlarm) { mutableStateOf(initialHour12.toString()) }
    var minuteText by remember(initialAlarm) { mutableStateOf(String.format("%02d", initialAlarm.minute)) }
    var isPm by remember(initialAlarm) { mutableStateOf(initialIsPm) }
    var label by remember(initialAlarm) { mutableStateOf(initialAlarm.label) }
    var repeatDays by remember(initialAlarm) { mutableStateOf(initialAlarm.repeatDays) }
    var selectedTopicIds by remember(initialAlarm) { mutableStateOf(initialAlarm.selectedTopicIds) }
    var difficultyPreset by remember(initialAlarm) { mutableStateOf(initialAlarm.difficultyPreset) }
    var questionCountText by remember(initialAlarm) {
        mutableStateOf(initialAlarm.requiredQuestions.toString())
    }
    var vibrate by remember(initialAlarm) { mutableStateOf(initialAlarm.vibrate) }

    val parsedHour12 = (hourText.toIntOrNull() ?: initialHour12).coerceIn(1, 12)
    val parsedMinute = (minuteText.toIntOrNull() ?: initialAlarm.minute).coerceIn(0, 59)
    val computedHour24 = AlarmTimeCalculator.to24Hour(parsedHour12, isPm)
    val parsedQuestionCount = (questionCountText.toIntOrNull() ?: Alarm.DEFAULT_REQUIRED_QUESTIONS)
        .coerceIn(Alarm.MIN_REQUIRED_QUESTIONS, Alarm.MAX_REQUIRED_QUESTIONS)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (initialAlarm.id == 0L) "New Alarm" else "Edit Alarm",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.inkPrimary
            )
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = palette.inkSecondary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(8.dp)
            )
        }

        // 12-Hour Time Picker
        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Time",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.inkPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeUnitStepper(
                        label = "Hour",
                        valueText = hourText,
                        onValueTextChange = { raw ->
                            if (raw.length <= 2 && raw.all { it.isDigit() }) hourText = raw
                        },
                        onStep = { delta ->
                            val zeroBased = (parsedHour12 - 1 + delta + 12) % 12
                            hourText = (zeroBased + 1).toString()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.displayMedium,
                        color = palette.inkPrimary,
                        modifier = Modifier.padding(top = 18.dp)
                    )
                    TimeUnitStepper(
                        label = "Minute",
                        valueText = minuteText,
                        onValueTextChange = { raw ->
                            if (raw.length <= 2 && raw.all { it.isDigit() }) minuteText = raw
                        },
                        onStep = { delta ->
                            val next = (parsedMinute + delta + 60) % 60
                            minuteText = String.format("%02d", next)
                        },
                        modifier = Modifier.weight(1f)
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        AmPmOption(text = "AM", selected = !isPm, onClick = { isPm = false })
                        AmPmOption(text = "PM", selected = isPm, onClick = { isPm = true })
                    }
                }
            }
        }

        // Label
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.length <= 60) label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.surface,
                unfocusedContainerColor = palette.surface,
                focusedBorderColor = palette.inkPrimary,
                unfocusedBorderColor = palette.borderStrong
            )
        )

        // Repeat Days
        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Repeat",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Weekdays",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.signal,
                            modifier = Modifier.clickable { repeatDays = setOf(1, 2, 3, 4, 5) }
                        )
                        Text(
                            text = "Daily",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.signal,
                            modifier = Modifier.clickable { repeatDays = (1..7).toSet() }
                        )
                        Text(
                            text = "Once",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.inkSecondary,
                            modifier = Modifier.clickable { repeatDays = emptySet() }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DAY_LABELS.forEach { (dayNum, dayChar) ->
                        val selected = dayNum in repeatDays
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selected) palette.inkPrimary else palette.inset)
                                .border(
                                    1.dp,
                                    if (selected) palette.inkPrimary else palette.border,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable {
                                    repeatDays = if (selected) repeatDays - dayNum else repeatDays + dayNum
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayChar,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) palette.canvas else palette.inkSecondary
                            )
                        }
                    }
                }
            }
        }

        // Number of Questions (Default 5, Max 100)
        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Number of Questions",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "Default 5 · Up to 100 questions",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TactileSecondaryButton(
                            text = "−",
                            enabled = parsedQuestionCount > Alarm.MIN_REQUIRED_QUESTIONS,
                            onClick = {
                                questionCountText = (parsedQuestionCount - 1)
                                    .coerceAtLeast(Alarm.MIN_REQUIRED_QUESTIONS)
                                    .toString()
                            }
                        )
                        OutlinedTextField(
                            value = questionCountText,
                            onValueChange = { raw ->
                                if (raw.length <= 3 && raw.all { it.isDigit() }) {
                                    questionCountText = raw
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = palette.inkPrimary),
                            modifier = Modifier
                                .width(72.dp)
                                .testTag("input_question_count"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.inset,
                                unfocusedContainerColor = palette.inset
                            )
                        )
                        TactileSecondaryButton(
                            text = "+",
                            enabled = parsedQuestionCount < Alarm.MAX_REQUIRED_QUESTIONS,
                            onClick = {
                                questionCountText = (parsedQuestionCount + 1)
                                    .coerceAtMost(Alarm.MAX_REQUIRED_QUESTIONS)
                                    .toString()
                            }
                        )
                    }
                }

                // Quick-pick pills up to 100
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 25, 50, 100).forEach { presetCount ->
                        val active = parsedQuestionCount == presetCount
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) palette.inkPrimary else palette.inset,
                            border = BorderStroke(1.dp, if (active) palette.inkPrimary else palette.border),
                            modifier = Modifier.clickable {
                                questionCountText = presetCount.toString()
                            }
                        ) {
                            Text(
                                text = "$presetCount",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) palette.canvas else palette.inkPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Topics for This Alarm (Flexible user-configured topics)
        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Topics",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "Edit topics & subtopics",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.signal,
                        modifier = Modifier.clickable(onClick = onOpenTopicsManager)
                    )
                }

                if (configuredTopics.isEmpty()) {
                    Text(
                        text = "No topics configured yet. Tap 'Edit topics & subtopics' to add any topic.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary
                    )
                } else {
                    Text(
                        text = if (selectedTopicIds.isEmpty()) {
                            "Using all your active topics (tap below to pick specific ones for this alarm)"
                        } else {
                            "${selectedTopicIds.size} specific topic(s) selected for this alarm"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val useAllActive = selectedTopicIds.isEmpty()
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (useAllActive) palette.inkPrimary else palette.inset,
                            border = BorderStroke(1.dp, if (useAllActive) palette.inkPrimary else palette.border),
                            modifier = Modifier.clickable { selectedTopicIds = emptySet() }
                        ) {
                            Text(
                                text = "All Active Topics",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (useAllActive) palette.canvas else palette.inkPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        configuredTopics.forEach { topic ->
                            val isSelected = topic.id in selectedTopicIds
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) palette.inkPrimary else palette.inset,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) palette.inkPrimary else palette.border
                                ),
                                modifier = Modifier.clickable {
                                    selectedTopicIds = if (isSelected) {
                                        selectedTopicIds - topic.id
                                    } else {
                                        selectedTopicIds + topic.id
                                    }
                                }
                            ) {
                                Text(
                                    text = topic.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isSelected) palette.canvas else palette.inkPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Difficulty & Vibrate
        HairlineCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Difficulty",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.inkPrimary
                )
                DifficultyPreset.entries.forEach { preset ->
                    val selected = preset == difficultyPreset
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) palette.inset else palette.surface)
                            .border(
                                1.dp,
                                if (selected) palette.signal else palette.border,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { difficultyPreset = preset }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary
                        )
                    }
                }

                HorizontalDivider(color = palette.border)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vibrate",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    MechanicalToggle(
                        checked = vibrate,
                        onCheckedChange = { vibrate = it },
                        contentDescriptionText = "Toggle alarm vibration"
                    )
                }
            }
        }

        // Save / Delete Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (initialAlarm.id != 0L && onDelete != null) {
                TactileSecondaryButton(
                    text = "Delete",
                    onClick = { onDelete(initialAlarm.id) },
                    modifier = Modifier.weight(0.38f)
                )
            }
            TactilePrimaryButton(
                text = "Save Alarm",
                onClick = {
                    onSave(
                        initialAlarm.copy(
                            hour = computedHour24,
                            minute = parsedMinute,
                            label = label.trim(),
                            enabled = true,
                            repeatDays = repeatDays,
                            selectedTopicIds = selectedTopicIds,
                            difficultyPreset = difficultyPreset,
                            requiredQuestions = parsedQuestionCount,
                            vibrate = vibrate
                        )
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_save_alarm")
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AmPmOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalKlockPalette.current
    Box(
        modifier = Modifier
            .width(52.dp)
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) palette.inkPrimary else palette.inset)
            .border(
                1.dp,
                if (selected) palette.inkPrimary else palette.borderStrong,
                RoundedCornerShape(4.dp)
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) palette.canvas else palette.inkSecondary
        )
    }
}

@Composable
private fun TimeUnitStepper(
    label: String,
    valueText: String,
    onValueTextChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkSecondary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TactileSecondaryButton(text = "−", onClick = { onStep(-1) })
            OutlinedTextField(
                value = valueText,
                onValueChange = onValueTextChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = palette.inkPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(68.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.inset,
                    unfocusedContainerColor = palette.inset,
                    focusedBorderColor = palette.signal,
                    unfocusedBorderColor = palette.borderStrong
                )
            )
            TactileSecondaryButton(text = "+", onClick = { onStep(+1) })
        }
    }
}
