package com.klock.app.ui.screens

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klock.app.core.device.OemCompatibilityAdvisor
import com.klock.app.domain.model.AppSettings
import com.klock.app.domain.model.DifficultyPreset
import com.klock.app.domain.model.QuestionCategory
import com.klock.app.domain.model.QuestionHistoryRecord
import com.klock.app.domain.model.ThemePreference
import com.klock.app.ui.components.CodeWorkbenchBlock
import com.klock.app.ui.components.HairlineCard
import com.klock.app.ui.components.TactileSecondaryButton
import com.klock.app.ui.components.TechnicalTag
import com.klock.app.ui.theme.LocalKlockPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QuestionHistorySection(
    history: List<QuestionHistoryRecord>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val dateFormatter = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (history.isEmpty()) {
            item(key = "empty_history") {
                HairlineCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No answered questions yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "Questions you answer to turn off your alarms will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary
                        )
                    }
                }
            }
        } else {
            items(items = history, key = { it.id }) { item ->
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
                            TechnicalTag(text = item.category.displayName, highlighted = true)
                            Text(
                                text = dateFormatter.format(Date(item.answeredAtMillis)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkMuted
                            )
                        }

                        Text(
                            text = item.prompt,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.inkPrimary
                        )

                        if (!item.codeSnippet.isNullOrBlank()) {
                            CodeWorkbenchBlock(code = item.codeSnippet)
                        }

                        Text(
                            text = "Answer: ${item.canonicalAnswer}",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.verified
                        )

                        if (item.explanation.isNotBlank()) {
                            Text(
                                text = item.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSection(
    settings: AppSettings,
    onUpdateTheme: (ThemePreference) -> Unit,
    onUpdateDefaultDifficulty: (DifficultyPreset) -> Unit,
    onUpdateDefaultCategories: (Set<QuestionCategory>) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Theme
        item(key = "appearance") {
            HairlineCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemePreference.entries.forEach { pref ->
                            val selected = settings.themePreference == pref
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) palette.inkPrimary else palette.inset)
                                    .border(
                                        1.dp,
                                        if (selected) palette.inkPrimary else palette.border,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onUpdateTheme(pref) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = pref.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) palette.canvas else palette.inkPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Default Alarm Preferences
        item(key = "defaults") {
            HairlineCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Default Difficulty",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DifficultyPreset.entries.forEach { preset ->
                            val active = settings.defaultDifficulty == preset
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (active) palette.inkPrimary else palette.inset,
                                border = BorderStroke(1.dp, if (active) palette.inkPrimary else palette.border),
                                modifier = Modifier.clickable { onUpdateDefaultDifficulty(preset) }
                            ) {
                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) palette.canvas else palette.inkPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = palette.border)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Default Topics",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        if (settings.defaultCategories.size < QuestionCategory.ALL.size) {
                            Text(
                                text = "Select all",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.signal,
                                modifier = Modifier.clickable { onUpdateDefaultCategories(QuestionCategory.ALL) }
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuestionCategory.entries.forEach { cat ->
                            val active = cat in settings.defaultCategories
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (active) palette.surface else palette.inset,
                                border = BorderStroke(
                                    1.dp,
                                    if (active) palette.signal else palette.border
                                ),
                                modifier = Modifier.clickable {
                                    if (active && settings.defaultCategories.size > 1) {
                                        onUpdateDefaultCategories(settings.defaultCategories - cat)
                                    } else if (!active) {
                                        onUpdateDefaultCategories(settings.defaultCategories + cat)
                                    }
                                }
                            ) {
                                Text(
                                    text = cat.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (active) palette.inkPrimary else palette.inkMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Simple Alarm Reliability Card
        item(key = "alarm_reliability") {
            HairlineCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Keep Alarms Reliable",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "If battery saver delays alarms while your phone is asleep, allow Klock to run without battery restrictions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary
                    )
                    TactileSecondaryButton(
                        text = "Battery & Notification Settings",
                        onClick = {
                            runCatching {
                                context.startActivity(OemCompatibilityAdvisor.buildBatterySettingsIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
