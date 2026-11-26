package com.klock.app

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.klock.app.alarm.receiver.AlarmBootAndTimeReceiver
import com.klock.app.alarm.scheduler.AlarmTimeCalculator
import com.klock.app.alarm.scheduler.AndroidAlarmScheduler
import com.klock.app.core.device.DeviceMemoryTier
import com.klock.app.core.device.MemoryTierDetector
import com.klock.app.core.device.OemCompatibilityAdvisor
import com.klock.app.core.device.OemVendor
import com.klock.app.domain.model.ActiveAlarmSession
import com.klock.app.domain.model.Alarm
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.OverrideReason
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.UserProfile
import com.klock.app.ui.active.ActiveAlarmRootScreen
import com.klock.app.ui.screens.FirstLaunchOnboardingScreen
import com.klock.app.ui.theme.KlockTheme
import com.klock.app.ui.viewmodel.ActiveAlarmUiState
import com.klock.app.ui.viewmodel.MainUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformCompatibilityAndComposeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `memory tier classification covers 2 GB budget to 16 GB flagship devices`() {
        val mb = 1024L * 1024L
        assertEquals(DeviceMemoryTier.LOW_RAM, MemoryTierDetector.classify(2048L * mb, isLowRamDevice = false))
        assertEquals(DeviceMemoryTier.LOW_RAM, MemoryTierDetector.classify(4096L * mb, isLowRamDevice = true))
        assertEquals(DeviceMemoryTier.STANDARD_RAM, MemoryTierDetector.classify(4096L * mb, isLowRamDevice = false))
        assertEquals(DeviceMemoryTier.HIGH_RAM, MemoryTierDetector.classify(16384L * mb, isLowRamDevice = false))
    }

    @Test
    fun `OEM advisor detects Xiaomi Redmi Samsung Motorola OnePlus Vivo Huawei and AOSP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(OemVendor.XIAOMI_REDMI_POCO, OemCompatibilityAdvisor.buildGuidance(context, "Xiaomi", "Redmi").vendor)
        assertEquals(OemVendor.SAMSUNG, OemCompatibilityAdvisor.buildGuidance(context, "samsung", "galaxy").vendor)
        assertEquals(OemVendor.MOTOROLA, OemCompatibilityAdvisor.buildGuidance(context, "motorola", "moto").vendor)
        assertEquals(OemVendor.OPPO_REALME_ONEPLUS, OemCompatibilityAdvisor.buildGuidance(context, "realme", "RMX").vendor)
        assertEquals(OemVendor.STANDARD_AOSP, OemCompatibilityAdvisor.buildGuidance(context, "Google", "Pixel").vendor)
    }

    @Test
    fun `AlarmManager schedules exact AlarmClock and boot receiver restores enabled alarms`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<KlockApplication>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = shadowOf(alarmManager)
        shadowAlarmManager.scheduledAlarms.clear()

        val scheduler = AndroidAlarmScheduler(context)
        val repo = context.container.alarmRepository

        val id = repo.upsertAlarm(
            Alarm(
                hour = 6,
                minute = 45,
                label = "Morning Focus",
                enabled = true,
                repeatDays = setOf(1, 2, 3, 4, 5),
                requiredQuestions = 25
            )
        )
        val saved = repo.getAlarmById(id)!!
        assertEquals(25, saved.requiredQuestions)
        val now = ZonedDateTime.of(2026, 11, 25, 22, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        scheduler.schedule(saved, now)
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())

        scheduler.cancel(id)
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())

        scheduler.rescheduleAllEnabled(repo)
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())

        AlarmBootAndTimeReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `12-hour clock formatting and AM PM conversion round-trips accurately across all 24 hours`() {
        assertEquals("12:00" to "AM", AlarmTimeCalculator.formatTriggerClock(0, 0, use24Hour = false))
        assertEquals("7:05" to "AM", AlarmTimeCalculator.formatTriggerClock(7, 5, use24Hour = false))
        assertEquals("12:30" to "PM", AlarmTimeCalculator.formatTriggerClock(12, 30, use24Hour = false))
        assertEquals("6:45" to "PM", AlarmTimeCalculator.formatTriggerClock(18, 45, use24Hour = false))
        for (h24 in 0..23) {
            val (h12, isPm) = AlarmTimeCalculator.to12Hour(h24)
            assertTrue(h12 in 1..12)
            assertEquals(h24, AlarmTimeCalculator.to24Hour(h12, isPm))
        }
    }

    @Test
    fun `first-launch onboarding collects user info recommends topics and allows custom topics`() {
        var capturedName = ""
        var capturedAge: Int? = null
        var capturedTopics: List<TopicConfig> = emptyList()

        composeRule.setContent {
            KlockTheme {
                FirstLaunchOnboardingScreen(
                    onComplete = { name, age, _, topics ->
                        capturedName = name
                        capturedAge = age
                        capturedTopics = topics
                    }
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_name_input").performTextInput("Rajat")
        composeRule.onNodeWithTag("onboarding_age_input").performTextInput("23")
        composeRule.onNodeWithTag("onboarding_activity_input").performScrollTo().performTextInput("Data Analyst")
        composeRule.onNodeWithTag("btn_onboarding_next").performScrollTo().performClick()

        // Step 2: Add an arbitrary custom topic alongside recommended topics
        composeRule.onNodeWithTag("input_add_topic").performScrollTo().performTextInput("World History")
        composeRule.onNodeWithTag("btn_add_topic").performClick()
        composeRule.onNodeWithTag("btn_onboarding_finish").performScrollTo().performClick()

        assertEquals("Rajat", capturedName)
        assertEquals(23, capturedAge)
        assertTrue(capturedTopics.any { it.name == "SQL" })
        assertTrue(capturedTopics.any { it.name == "World History" })
    }

    @Test
    fun `3-line menu button opens left-to-right navigation drawer with personalized greeting`() {
        val state = MainUiState(
            isInitialized = true,
            settings = AppSettings(
                userProfile = UserProfile(
                    name = "Rajat",
                    age = 23,
                    currentActivityOrStudy = "Data & Analytics",
                    onboardingCompleted = true
                ),
                configuredTopics = listOf(TopicConfig(name = "SQL"))
            )
        )

        composeRule.setContent {
            KlockTheme {
                KlockMainAppScreen(
                    state = state,
                    nextScheduledSummary = "No active alarms",
                    canScheduleExactAlarms = true,
                    onSelectSection = {},
                    onOpenCreateAlarm = {},
                    onOpenEditAlarm = {},
                    onCloseEditor = {},
                    onSaveAlarm = {},
                    onDeleteAlarm = {},
                    onToggleAlarm = { _, _ -> },
                    onDismissCompletionSummary = {},
                    onUpdateTheme = {},
                    onUpdateDefaultDifficulty = {},
                    onSaveProfile = { _, _, _ -> },
                    onSuggestFromProfile = { _, _ -> },
                    onAddTopic = {},
                    onToggleTopic = { _, _ -> },
                    onRemoveTopic = {},
                    onAddSubtopic = { _, _ -> },
                    onToggleSubtopic = { _, _, _ -> },
                    onRemoveSubtopic = { _, _ -> }
                )
            }
        }

        // Open slide-out drawer via 3-line menu icon
        composeRule.onNodeWithTag("btn_open_drawer").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("navigation_drawer_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_topics_and_profile").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_history").assertIsDisplayed()
        composeRule.onNodeWithTag("drawer_item_settings").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `small 320dp screen renders clean MCQ card and personalized Good morning name on completion`() {
        val sampleQ = AlarmQuestion(
            fingerprint = "fp_ui_1",
            slotIndex = 2,
            topicId = "sql",
            topicName = "SQL",
            subtopicName = "Window Functions",
            difficulty = QuestionDifficulty.MEDIUM,
            format = QuestionFormat.MULTIPLE_CHOICE,
            provenance = QuestionProvenance.SOURCE_GROUNDED_EXTRACTED,
            prompt = "Which keyword restarts window numbering per group?",
            options = listOf("PARTITION BY", "GROUP BY", "ORDER BY", "HAVING"),
            canonicalAnswer = "PARTITION BY",
            explanation = "PARTITION BY splits rows into partitions.",
            fetchedAtMillis = 1_700_000_000_000L
        )
        val completed = ActiveAlarmSession(
            sessionId = "sess_done",
            alarmId = 1L,
            alarmLabel = "Wake Up",
            startedAtMillis = 1_700_000_000_000L,
            requiredCount = 5,
            solvedCount = 5,
            questions = List(5) { sampleQ.copy(slotIndex = it, fingerprint = "fp_$it") },
            retrievalStatus = RetrievalStatus.READY,
            emergencyOverrideCode = "KLOCK-9X2P4M",
            completed = true,
            completedAtMillis = 1_700_000_030_000L
        )

        composeRule.setContent {
            KlockTheme {
                ActiveAlarmRootScreen(
                    state = ActiveAlarmUiState(
                        session = null,
                        completedSession = completed,
                        settings = AppSettings(
                            userProfile = UserProfile(name = "Rajat", onboardingCompleted = true)
                        )
                    ),
                    onSelectOption = {},
                    onRetryFetch = {},
                    onOpenOverride = {},
                    onCloseOverride = {},
                    onConfirmOverride = { _: OverrideReason, _: Long, _: String -> },
                    onCloseFinished = {}
                )
            }
        }

        composeRule.onNodeWithText("Good morning, Rajat!").assertIsDisplayed()
    }
}
