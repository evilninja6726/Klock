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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.klock.app.ui.active.ActiveAlarmActivity
import com.klock.app.ui.screens.AlarmEditorSheet
import com.klock.app.ui.screens.AlarmListSection
import com.klock.app.ui.screens.QuestionHistorySection
import com.klock.app.ui.screens.SettingsSection
import com.klock.app.ui.theme.KlockTheme
import com.klock.app.ui.theme.LocalKlockPalette
import com.klock.app.ui.viewmodel.MainUiState
import com.klock.app.ui.viewmodel.MainViewModel
import com.klock.app.ui.viewmodel.TopTab

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
                KlockMainAppScreen(
                    state = state,
                    nextScheduledSummary = vm.nextScheduledSummary(state.alarms),
                    canScheduleExactAlarms = container.alarmScheduler.canScheduleExactAlarms(),
                    onSelectTab = vm::selectTab,
                    onOpenCreateAlarm = vm::openCreateAlarm,
                    onOpenEditAlarm = vm::openEditAlarm,
                    onCloseEditor = vm::closeEditor,
                    onSaveAlarm = vm::saveAlarm,
                    onDeleteAlarm = vm::deleteAlarm,
                    onToggleAlarm = vm::toggleAlarm,
                    onDismissCompletionSummary = vm::dismissCompletionSummary,
                    onUpdateTheme = vm::updateTheme,
                    onUpdateDefaultDifficulty = vm::updateDefaultDifficulty,
                    onUpdateDefaultCategories = vm::updateDefaultCategories
                )
            }
        }
    }
}

@Composable
fun KlockMainAppScreen(
    state: MainUiState,
    nextScheduledSummary: String,
    canScheduleExactAlarms: Boolean,
    onSelectTab: (TopTab) -> Unit,
    onOpenCreateAlarm: () -> Unit,
    onOpenEditAlarm: (com.klock.app.domain.model.Alarm) -> Unit,
    onCloseEditor: () -> Unit,
    onSaveAlarm: (com.klock.app.domain.model.Alarm) -> Unit,
    onDeleteAlarm: (Long) -> Unit,
    onToggleAlarm: (com.klock.app.domain.model.Alarm, Boolean) -> Unit,
    onDismissCompletionSummary: (String) -> Unit,
    onUpdateTheme: (com.klock.app.domain.model.ThemePreference) -> Unit,
    onUpdateDefaultDifficulty: (com.klock.app.domain.model.DifficultyPreset) -> Unit,
    onUpdateDefaultCategories: (Set<com.klock.app.domain.model.QuestionCategory>) -> Unit
) {
    val palette = LocalKlockPalette.current
    val context = LocalContext.current

    if (state.isEditorOpen && state.editingAlarm != null) {
        BackHandler(onBack = onCloseEditor)
        Scaffold(
            containerColor = palette.canvas
        ) { innerPadding ->
            AlarmEditorSheet(
                initialAlarm = state.editingAlarm,
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

    Scaffold(
        containerColor = palette.canvas,
        topBar = {
            KlockTopHeader(
                selectedTab = state.selectedTab,
                nextSummary = nextScheduledSummary,
                onSelectTab = onSelectTab
            )
        }
    ) { innerPadding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)

        when (state.selectedTab) {
            TopTab.ALARMS -> {
                AlarmListSection(
                    alarms = state.alarms,
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

            TopTab.HISTORY -> {
                QuestionHistorySection(
                    history = state.history,
                    contentPadding = innerPadding,
                    modifier = bodyModifier
                )
            }

            TopTab.SETTINGS -> {
                SettingsSection(
                    settings = state.settings,
                    onUpdateTheme = onUpdateTheme,
                    onUpdateDefaultDifficulty = onUpdateDefaultDifficulty,
                    onUpdateDefaultCategories = onUpdateDefaultCategories,
                    contentPadding = innerPadding,
                    modifier = bodyModifier
                )
            }
        }
    }
}

@Composable
private fun KlockTopHeader(
    selectedTab: TopTab,
    nextSummary: String,
    onSelectTab: (TopTab) -> Unit
) {
    val palette = LocalKlockPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "Klock",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = palette.inkPrimary
            )
            Text(
                text = nextSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopTab.entries.forEach { tab ->
                val active = tab == selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) palette.inkPrimary else palette.inset)
                        .border(
                            1.dp,
                            if (active) palette.inkPrimary else palette.border,
                            RoundedCornerShape(6.dp)
                        )
                        .testTag("tab_${tab.name.lowercase()}")
                        .clickable(role = Role.Tab) { onSelectTab(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) palette.canvas else palette.inkSecondary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = palette.border)
    }
}
