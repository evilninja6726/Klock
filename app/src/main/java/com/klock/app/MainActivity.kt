package com.klock.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.klock.app.alarm.service.AlarmRingingService
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.ThemePreference
import com.klock.app.domain.model.TopicConfig
import com.klock.app.ui.active.ActiveAlarmActivity
import com.klock.app.ui.components.HamburgerMenuButton
import com.klock.app.ui.screens.AlarmEditorSheet
import com.klock.app.ui.screens.AlarmListSection
import com.klock.app.ui.screens.FirstLaunchOnboardingScreen
import com.klock.app.ui.screens.QuestionHistorySection
import com.klock.app.ui.screens.SettingsSection
import com.klock.app.ui.screens.TopicsAndProfileSection
import com.klock.app.ui.theme.KlockTheme
import com.klock.app.ui.theme.LocalKlockPalette
import com.klock.app.ui.viewmodel.DrawerSection
import com.klock.app.ui.viewmodel.MainUiState
import com.klock.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as KlockApplication).container

        setContent {
            val vm: MainViewModel = viewModel(
                factory = MainViewModel.factory(container)
            )
            val state by vm.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.activeSession?.sessionId, state.activeSession?.completed) {
                val active = state.activeSession
                if (active != null && !active.completed) {
                    startActivity(
                        Intent(this@MainActivity, ActiveAlarmActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                    )
                }
            }

            KlockTheme(themePreference = state.settings.themePreference) {
                if (!state.isInitialized) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = LocalKlockPalette.current.canvas
                    ) {}
                } else if (!state.settings.userProfile.onboardingCompleted) {
                    FirstLaunchOnboardingScreen(
                        onComplete = vm::completeOnboarding
                    )
                } else {
                    KlockMainAppScreen(
                        state = state,
                        nextScheduledSummary = vm.nextScheduledSummary(state.alarms),
                        canScheduleExactAlarms = container.alarmScheduler.canScheduleExactAlarms(),
                        onSelectSection = vm::selectSection,
                        onOpenCreateAlarm = vm::openCreateAlarm,
                        onOpenEditAlarm = vm::openEditAlarm,
                        onCloseEditor = vm::closeEditor,
                        onSaveAlarm = vm::saveAlarm,
                        onDeleteAlarm = vm::deleteAlarm,
                        onToggleAlarm = vm::toggleAlarm,
                        onDismissCompletionSummary = vm::dismissCompletionSummary,
                        onUpdateTheme = vm::updateTheme,
                        onUpdateDefaultDifficulty = vm::updateDefaultDifficulty,
                        onSaveProfile = vm::updateUserProfile,
                        onSuggestFromProfile = vm::applySmartTopicSuggestions,
                        onAddTopic = vm::addCustomTopic,
                        onToggleTopic = vm::toggleTopic,
                        onRemoveTopic = vm::removeTopic,
                        onAddSubtopic = vm::addSubtopic,
                        onToggleSubtopic = vm::toggleSubtopic,
                        onRemoveSubtopic = vm::removeSubtopic
                    )
                }
            }
        }
    }
}

@Composable
fun KlockMainAppScreen(
    state: MainUiState,
    nextScheduledSummary: String,
    canScheduleExactAlarms: Boolean,
    onSelectSection: (DrawerSection) -> Unit,
    onOpenCreateAlarm: () -> Unit,
    onOpenEditAlarm: (Alarm) -> Unit,
    onCloseEditor: () -> Unit,
    onSaveAlarm: (Alarm) -> Unit,
    onDeleteAlarm: (Long) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onDismissCompletionSummary: (String) -> Unit,
    onUpdateTheme: (ThemePreference) -> Unit,
    onUpdateDefaultDifficulty: (DifficultyPreset) -> Unit,
    onSaveProfile: (String, Int?, String) -> Unit,
    onSuggestFromProfile: (String, Int?) -> Unit,
    onAddTopic: (String) -> Unit,
    onToggleTopic: (String, Boolean) -> Unit,
    onRemoveTopic: (String) -> Unit,
    onAddSubtopic: (String, String) -> Unit,
    onToggleSubtopic: (String, String, Boolean) -> Unit,
    onRemoveSubtopic: (String, String) -> Unit
) {
    val palette = LocalKlockPalette.current
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    if (state.isEditorOpen && state.editingAlarm != null) {
        BackHandler(onBack = onCloseEditor)
        Scaffold(
            containerColor = palette.canvas
        ) { innerPadding ->
            AlarmEditorSheet(
                initialAlarm = state.editingAlarm,
                configuredTopics = state.settings.configuredTopics,
                onOpenTopicsManager = {
                    onCloseEditor()
                    onSelectSection(DrawerSection.TOPICS_AND_PROFILE)
                },
                onSave = onSaveAlarm,
                onDelete = onDeleteAlarm,
                onCancel = onCloseEditor,
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            )
        }
        return
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = palette.surface,
                drawerContentColor = palette.inkPrimary,
                drawerShape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp),
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .fillMaxHeight()
                    .testTag("navigation_drawer_sheet")
            ) {
                KlockDrawerContent(
                    userName = state.settings.userProfile.name,
                    userRole = state.settings.userProfile.currentActivityOrStudy,
                    selectedSection = state.selectedSection,
                    onSelectSection = { target ->
                        onSelectSection(target)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = palette.canvas,
            topBar = {
                KlockTopBar(
                    title = if (state.selectedSection == DrawerSection.ALARMS) "Klock"
                    else state.selectedSection.label,
                    subtitle = if (state.selectedSection == DrawerSection.ALARMS) nextScheduledSummary else null,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    }
                )
            }
        ) { innerPadding ->
            val bodyModifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)

            when (state.selectedSection) {
                DrawerSection.ALARMS -> {
                    AlarmListSection(
                        alarms = state.alarms,
                        configuredTopics = state.settings.configuredTopics,
                        userName = state.settings.userProfile.name,
                        canScheduleExact = canScheduleExactAlarms,
                        activeSession = state.activeSession,
                        completedSessionToReview = if (state.showCompletionBanner) {
                            state.lastCompletedSession
                        } else null,
                        onOpenActiveSession = {
                            context.startActivity(Intent(context, ActiveAlarmActivity::class.java))
                        },
                        onDismissCompletedReview = onDismissCompletionSummary,
                        onToggleAlarm = onToggleAlarm,
                        onEditAlarm = onOpenEditAlarm,
                        onCreateAlarm = onOpenCreateAlarm,
                        onOpenTopicsManager = { onSelectSection(DrawerSection.TOPICS_AND_PROFILE) },
                        onTriggerNowForTest = { alarm ->
                            AlarmRingingService.startAlarmService(
                                context = context,
                                alarmId = alarm.id,
                                alarmLabel = alarm.label,
                                vibrate = alarm.vibrate,
                                ringtoneUri = alarm.ringtoneUri
                            )
                        },
                        contentPadding = innerPadding,
                        modifier = bodyModifier
                    )
                }

                DrawerSection.TOPICS_AND_PROFILE -> {
                    TopicsAndProfileSection(
                        userProfile = state.settings.userProfile,
                        topics = state.settings.configuredTopics,
                        onSaveProfile = onSaveProfile,
                        onSuggestFromProfile = onSuggestFromProfile,
                        onAddTopic = onAddTopic,
                        onToggleTopic = onToggleTopic,
                        onRemoveTopic = onRemoveTopic,
                        onAddSubtopic = onAddSubtopic,
                        onToggleSubtopic = onToggleSubtopic,
                        onRemoveSubtopic = onRemoveSubtopic,
                        contentPadding = innerPadding,
                        modifier = bodyModifier
                    )
                }

                DrawerSection.HISTORY -> {
                    QuestionHistorySection(
                        history = state.history,
                        contentPadding = innerPadding,
                        modifier = bodyModifier
                    )
                }

                DrawerSection.SETTINGS -> {
                    SettingsSection(
                        settings = state.settings,
                        onUpdateTheme = onUpdateTheme,
                        onUpdateDefaultDifficulty = onUpdateDefaultDifficulty,
                        onOpenTopicsManager = { onSelectSection(DrawerSection.TOPICS_AND_PROFILE) },
                        contentPadding = innerPadding,
                        modifier = bodyModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun KlockTopBar(
    title: String,
    subtitle: String?,
    onOpenDrawer: () -> Unit
) {
    val palette = LocalKlockPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HamburgerMenuButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.testTag("btn_open_drawer")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(thickness = 1.dp, color = palette.border)
    }
}

@Composable
private fun KlockDrawerContent(
    userName: String,
    userRole: String,
    selectedSection: DrawerSection,
    onSelectSection: (DrawerSection) -> Unit
) {
    val palette = LocalKlockPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (userName.isNotBlank()) "Hi, $userName" else "Klock",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = palette.inkPrimary
        )
        if (userRole.isNotBlank()) {
            Text(
                text = userRole,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = palette.border)
        Spacer(modifier = Modifier.height(8.dp))

        DrawerSection.entries.forEach { section ->
            val active = section == selectedSection
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) palette.inset else palette.surface)
                    .border(
                        width = 1.dp,
                        color = if (active) palette.signal else palette.surface,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .testTag("drawer_item_${section.name.lowercase()}")
                    .clickable(role = Role.Tab) { onSelectSection(section) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) palette.signal else palette.inkPrimary
                )
            }
        }
    }
}
