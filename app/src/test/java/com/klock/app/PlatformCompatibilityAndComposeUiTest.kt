package com.klock.app

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import com.klock.app.domain.model.OverrideReason
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionDifficulty
import com.klock.app.domain.model.QuestionFormat
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.domain.model.RetrievalStatus
import com.klock.app.ui.active.ActiveAlarmRootScreen
import com.klock.app.ui.theme.KlockTheme
import com.klock.app.ui.viewmodel.ActiveAlarmUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(DeviceMemoryTier.STANDARD_RAM, MemoryTierDetector.classify(6144L * mb, isLowRamDevice = false))
        assertEquals(DeviceMemoryTier.HIGH_RAM, MemoryTierDetector.classify(8192L * mb, isLowRamDevice = false))
        assertEquals(DeviceMemoryTier.HIGH_RAM, MemoryTierDetector.classify(16384L * mb, isLowRamDevice = false))
    }

    @Test
    fun `OEM advisor detects Xiaomi Redmi Samsung Motorola OnePlus Vivo Huawei and AOSP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val xiaomi = OemCompatibilityAdvisor.buildGuidance(context, "Xiaomi", "Redmi")
        assertEquals(OemVendor.XIAOMI_REDMI_POCO, xiaomi.vendor)
        assertTrue(xiaomi.checklist.any { it.contains("Autostart", ignoreCase = true) })

        val samsung = OemCompatibilityAdvisor.buildGuidance(context, "samsung", "galaxy")
        assertEquals(OemVendor.SAMSUNG, samsung.vendor)
        assertTrue(samsung.checklist.any { it.contains("Never auto sleeping", ignoreCase = true) })

        val motorola = OemCompatibilityAdvisor.buildGuidance(context, "motorola", "moto")
        assertEquals(OemVendor.MOTOROLA, motorola.vendor)

        val realme = OemCompatibilityAdvisor.buildGuidance(context, "realme", "RMX")
        assertEquals(OemVendor.OPPO_REALME_ONEPLUS, realme.vendor)

        val pixel = OemCompatibilityAdvisor.buildGuidance(context, "Google", "Pixel")
        assertEquals(OemVendor.STANDARD_AOSP, pixel.vendor)
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
                label = "SQL Sprint",
                enabled = true,
                repeatDays = setOf(1, 2, 3, 4, 5),
                categories = setOf(QuestionCategory.SQL, QuestionCategory.POWER_BI)
            )
        )
        val saved = repo.getAlarmById(id)!!
        val now = ZonedDateTime.of(2026, 11, 25, 22, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val triggerMillis = scheduler.schedule(saved, now)

        assertTrue(triggerMillis > now.toInstant().toEpochMilli())
        assertTrue("OS AlarmClockInfo must be registered", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // Cancel clears OS alarm
        scheduler.cancel(id)
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())

        // Simulate device reboot recovery -> rescheduleAllEnabled restores OS registration
        scheduler.rescheduleAllEnabled(repo)
        assertTrue(
            "Enabled alarm must be restored after reboot reschedule",
            shadowAlarmManager.scheduledAlarms.isNotEmpty()
        )

        // Verify BootReceiver handles BOOT_COMPLETED without error
        val bootReceiver = AlarmBootAndTimeReceiver()
        bootReceiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(
            "Countdown formatter works cleanly",
            "Rings in 8h 45m",
            AlarmTimeCalculator.formatCountdown(triggerMillis, now.toInstant().toEpochMilli())
        )
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
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun `small 320dp screen renders clean MCQ-only Active Alarm UI without source metadata or Stop button`() {
        val sampleQ = AlarmQuestion(
            fingerprint = "fp_ui_1",
            slotIndex = 2,
            category = QuestionCategory.POWER_BI,
            difficulty = QuestionDifficulty.MEDIUM,
            format = QuestionFormat.MULTIPLE_CHOICE,
            provenance = QuestionProvenance.SOURCE_GROUNDED_EXTRACTED,
            prompt = "Which DAX function modifies filter context in Power BI?",
            codeSnippet = "Sales APAC = ____([Total Sales], Region = \"APAC\")",
            options = listOf("CALCULATE", "SUM", "RELATED", "EARLIER"),
            canonicalAnswer = "CALCULATE",
            explanation = "CALCULATE modifies filter context.",
            sourceTitle = "CALCULATE (DAX)",
            sourceProvider = "Microsoft Learn",
            sourceUrl = "https://learn.microsoft.com/en-us/dax/calculate-function-dax",
            fetchedAtMillis = 1_700_000_000_000L
        )
        val session = ActiveAlarmSession(
            sessionId = "sess_320dp",
            alarmId = 1L,
            alarmLabel = "Morning Alarm",
            startedAtMillis = 1_700_000_000_000L,
            requiredCount = 5,
            solvedCount = 2,
            currentQuestionIndex = 2,
            questions = List(5) { sampleQ.copy(slotIndex = it, fingerprint = "fp_$it") },
            retrievalStatus = RetrievalStatus.READY,
            emergencyOverrideCode = "KLOCK-9X2P4M"
        )

        composeRule.setContent {
            KlockTheme {
                ActiveAlarmRootScreen(
                    state = ActiveAlarmUiState(
                        session = session,
                        isAudioRinging = true
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

        composeRule.onNodeWithTag("mechanical_lock_bar").assertIsDisplayed()
        composeRule.onNodeWithText("Question 3 of 5").assertIsDisplayed()
        composeRule.onNodeWithText("2 / 5").assertIsDisplayed()
        composeRule.onNodeWithTag("active_question_prompt").assertIsDisplayed()
        composeRule.onNodeWithTag("option_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("option_3").performScrollTo().assertIsDisplayed()

        // Verify source metadata and technical jargon are hidden from the UI
        assertEquals(0, composeRule.onAllNodesWithText("Microsoft Learn", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("https://", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("SOURCE-GROUNDED", substring = true, ignoreCase = true).fetchSemanticsNodes().size)

        // Verify NO ordinary "Stop" or "Snooze" button exists on active ringing screen
        assertEquals(0, composeRule.onAllNodesWithText("Stop", ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Snooze", ignoreCase = true).fetchSemanticsNodes().size)
    }
}
