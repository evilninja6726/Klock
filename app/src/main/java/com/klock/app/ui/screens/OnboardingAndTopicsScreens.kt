package com.klock.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.klock.app.domain.model.SubtopicConfig
import com.klock.app.domain.model.TopicConfig
import com.klock.app.domain.model.UserProfile
import com.klock.app.questions.adaptive.TopicRecommender
import com.klock.app.ui.components.HairlineCard
import com.klock.app.ui.components.MechanicalToggle
import com.klock.app.ui.components.TactilePrimaryButton
import com.klock.app.ui.components.TactileSecondaryButton
import com.klock.app.ui.theme.LocalKlockPalette

/**
 * First-launch two-step onboarding:
 * Step 1: Collects basic user info (Name, Age, What they are doing or studying).
 * Step 2: Generates tailored Topic & Subtopic suggestions from Step 1 while letting the user
 *         add/remove/customize ANY topic or subtopic from any domain before starting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstLaunchOnboardingScreen(
    onComplete: (name: String, age: Int?, activityOrStudy: String, topics: List<TopicConfig>) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    var step by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    var activityText by remember { mutableStateOf("") }
    var draftTopics by remember { mutableStateOf<List<TopicConfig>>(emptyList()) }

    BackHandler(enabled = step == 2) {
        step = 1
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = palette.canvas
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .testTag("onboarding_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (step == 1) "Step 1 of 2" else "Step 2 of 2",
                style = MaterialTheme.typography.labelLarge,
                color = palette.signal
            )

            if (step == 1) {
                Text(
                    text = "Welcome to Klock",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary
                )
                Text(
                    text = "Tell us a little about yourself so we can personalize your morning greeting and suggest relevant question topics.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.inkSecondary
                )

                HairlineCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 50) name = it },
                            label = { Text("Your name") },
                            placeholder = { Text("e.g. Rajat") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.inset,
                                unfocusedContainerColor = palette.inset,
                                focusedBorderColor = palette.inkPrimary,
                                unfocusedBorderColor = palette.borderStrong
                            )
                        )

                        OutlinedTextField(
                            value = ageText,
                            onValueChange = { raw ->
                                if (raw.length <= 3 && raw.all { it.isDigit() }) ageText = raw
                            },
                            label = { Text("Age") },
                            placeholder = { Text("e.g. 22") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_age_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.inset,
                                unfocusedContainerColor = palette.inset,
                                focusedBorderColor = palette.inkPrimary,
                                unfocusedBorderColor = palette.borderStrong
                            )
                        )

                        Text(
                            text = "What are you currently doing or studying?",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TopicRecommender.ONBOARDING_FOCUS_PRESETS.forEach { preset ->
                                val isSelected = activityText.contains(preset, ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) palette.inkPrimary else palette.inset,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) palette.inkPrimary else palette.border
                                    ),
                                    modifier = Modifier.clickable {
                                        activityText = if (isSelected) {
                                            activityText.replace(preset, "", ignoreCase = true)
                                                .replace(Regex(",\\s*,"), ",")
                                                .trim(' ', ',')
                                        } else if (activityText.isBlank()) {
                                            preset
                                        } else {
                                            "$activityText, $preset"
                                        }
                                    }
                                ) {
                                    Text(
                                        text = preset,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) palette.canvas else palette.inkPrimary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = activityText,
                            onValueChange = { if (it.length <= 80) activityText = it },
                            label = { Text("Or type any field, course, or interest") },
                            placeholder = { Text("e.g. MBA Finance, B.Tech CS, Medicine, UPSC") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_activity_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.inset,
                                unfocusedContainerColor = palette.inset,
                                focusedBorderColor = palette.inkPrimary,
                                unfocusedBorderColor = palette.borderStrong
                            )
                        )
                    }
                }

                TactilePrimaryButton(
                    text = "Continue to Topics",
                    enabled = name.isNotBlank() || activityText.isNotBlank(),
                    onClick = {
                        val parsedAge = ageText.toIntOrNull()
                        if (draftTopics.isEmpty()) {
                            draftTopics = TopicRecommender.recommendTopics(activityText, parsedAge)
                        }
                        step = 2
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_onboarding_next")
                )
            } else {
                // Step 2: Review suggested topics & customize any topic/subtopic freely
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Your Topics",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.inkSecondary,
                        modifier = Modifier
                            .clickable(role = Role.Button) { step = 1 }
                            .padding(8.dp)
                    )
                }

                Text(
                    text = "We picked these based on what you do. You can add any topic or subtopic from any field — and change them anytime later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary
                )

                AddTopicInputCard(
                    onAddTopic = { newTopicName ->
                        val trimmed = newTopicName.trim().take(48)
                        if (trimmed.isNotBlank()) {
                            val id = TopicConfig.slugifyTopicId(trimmed)
                            if (draftTopics.none { it.id == id }) {
                                draftTopics = draftTopics + TopicConfig(id = id, name = trimmed, enabled = true)
                            }
                        }
                    }
                )

                draftTopics.forEach { topic ->
                    TopicConfigCard(
                        topic = topic,
                        onToggleTopic = { enabled ->
                            draftTopics = draftTopics.map {
                                if (it.id == topic.id) it.copy(enabled = enabled) else it
                            }
                        },
                        onRemoveTopic = {
                            draftTopics = draftTopics.filterNot { it.id == topic.id }
                        },
                        onAddSubtopic = { subName ->
                            val clean = subName.trim().take(48)
                            if (clean.isNotBlank()) {
                                draftTopics = draftTopics.map { t ->
                                    if (t.id != topic.id) t
                                    else if (t.subtopics.any { it.name.equals(clean, ignoreCase = true) }) t
                                    else t.copy(subtopics = t.subtopics + SubtopicConfig(clean, true))
                                }
                            }
                        },
                        onToggleSubtopic = { subName, enabled ->
                            draftTopics = draftTopics.map { t ->
                                if (t.id != topic.id) t
                                else t.copy(
                                    subtopics = t.subtopics.map { s ->
                                        if (s.name == subName) s.copy(enabled = enabled) else s
                                    }
                                )
                            }
                        },
                        onRemoveSubtopic = { subName ->
                            draftTopics = draftTopics.map { t ->
                                if (t.id != topic.id) t
                                else t.copy(subtopics = t.subtopics.filterNot { it.name == subName })
                            }
                        }
                    )
                }

                val hasEnabledTopic = draftTopics.any { it.enabled && it.name.isNotBlank() }
                TactilePrimaryButton(
                    text = "Start Using Klock",
                    enabled = hasEnabledTopic,
                    onClick = {
                        onComplete(
                            name.trim(),
                            ageText.toIntOrNull(),
                            activityText.trim(),
                            draftTopics
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_onboarding_finish")
                )
            }
        }
    }
}

/**
 * Dedicated drawer section where users can update their profile (Name, Age, Activity/Study)
 * and flexibly configure ANY custom Topics and Subtopics at any time.
 */
@Composable
fun TopicsAndProfileSection(
    userProfile: UserProfile,
    topics: List<TopicConfig>,
    onSaveProfile: (String, Int?, String) -> Unit,
    onSuggestFromProfile: (String, Int?) -> Unit,
    onAddTopic: (String) -> Unit,
    onToggleTopic: (String, Boolean) -> Unit,
    onRemoveTopic: (String) -> Unit,
    onAddSubtopic: (String, String) -> Unit,
    onToggleSubtopic: (String, String, Boolean) -> Unit,
    onRemoveSubtopic: (String, String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    var name by remember(userProfile.name) { mutableStateOf(userProfile.name) }
    var ageText by remember(userProfile.age) { mutableStateOf(userProfile.age?.toString().orEmpty()) }
    var activityText by remember(userProfile.currentActivityOrStudy) {
        mutableStateOf(userProfile.currentActivityOrStudy)
    }
    var profileSavedNotice by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Profile Card
        item(key = "profile_card") {
            HairlineCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Your Profile",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                if (it.length <= 50) {
                                    name = it
                                    profileSavedNotice = false
                                }
                            },
                            label = { Text("Name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.weight(0.68f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.inset,
                                unfocusedContainerColor = palette.inset
                            )
                        )
                        OutlinedTextField(
                            value = ageText,
                            onValueChange = { raw ->
                                if (raw.length <= 3 && raw.all { it.isDigit() }) {
                                    ageText = raw
                                    profileSavedNotice = false
                                }
                            },
                            label = { Text("Age") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.32f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.inset,
                                unfocusedContainerColor = palette.inset
                            )
                        )
                    }

                    OutlinedTextField(
                        value = activityText,
                        onValueChange = {
                            if (it.length <= 80) {
                                activityText = it
                                profileSavedNotice = false
                            }
                        },
                        label = { Text("What you do or study") },
                        placeholder = { Text("e.g. Data Analyst, Medicine, Law, Physics") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.inset,
                            unfocusedContainerColor = palette.inset
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TactilePrimaryButton(
                            text = if (profileSavedNotice) "Saved ✓" else "Save Profile",
                            onClick = {
                                onSaveProfile(name, ageText.toIntOrNull(), activityText)
                                profileSavedNotice = true
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TactileSecondaryButton(
                            text = "Suggest Topics",
                            onClick = {
                                onSaveProfile(name, ageText.toIntOrNull(), activityText)
                                onSuggestFromProfile(activityText, ageText.toIntOrNull())
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Add Any Topic From Any Domain
        item(key = "add_custom_topic") {
            AddTopicInputCard(onAddTopic = onAddTopic)
        }

        if (topics.isEmpty()) {
            item(key = "no_topics_notice") {
                HairlineCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No topics added yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "Add any topic above (from any subject or career) or tap 'Suggest Topics' to generate ideas from your profile.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary
                        )
                    }
                }
            }
        } else {
            items(items = topics, key = { it.id }) { topic ->
                TopicConfigCard(
                    topic = topic,
                    onToggleTopic = { enabled -> onToggleTopic(topic.id, enabled) },
                    onRemoveTopic = { onRemoveTopic(topic.id) },
                    onAddSubtopic = { sub -> onAddSubtopic(topic.id, sub) },
                    onToggleSubtopic = { sub, enabled -> onToggleSubtopic(topic.id, sub, enabled) },
                    onRemoveSubtopic = { sub -> onRemoveSubtopic(topic.id, sub) }
                )
            }
        }
    }
}

@Composable
fun AddTopicInputCard(
    onAddTopic: (String) -> Unit
) {
    val palette = LocalKlockPalette.current
    var newTopic by remember { mutableStateOf("") }

    HairlineCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Add Any Topic",
                style = MaterialTheme.typography.titleMedium,
                color = palette.inkPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newTopic,
                    onValueChange = { if (it.length <= 48) newTopic = it },
                    placeholder = { Text("e.g. SQL, Psychology, World History, Physics…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newTopic.isNotBlank()) {
                                onAddTopic(newTopic)
                                newTopic = ""
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_add_topic"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.inset,
                        unfocusedContainerColor = palette.inset,
                        focusedBorderColor = palette.signal,
                        unfocusedBorderColor = palette.borderStrong
                    )
                )
                TactilePrimaryButton(
                    text = "Add",
                    enabled = newTopic.isNotBlank(),
                    onClick = {
                        onAddTopic(newTopic)
                        newTopic = ""
                    },
                    modifier = Modifier.testTag("btn_add_topic")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicConfigCard(
    topic: TopicConfig,
    onToggleTopic: (Boolean) -> Unit,
    onRemoveTopic: () -> Unit,
    onAddSubtopic: (String) -> Unit,
    onToggleSubtopic: (String, Boolean) -> Unit,
    onRemoveSubtopic: (String) -> Unit
) {
    val palette = LocalKlockPalette.current
    var subtopicDraft by remember(topic.id) { mutableStateOf("") }

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
                        text = topic.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (topic.enabled) palette.inkPrimary else palette.inkMuted
                    )
                    Text(
                        text = if (topic.subtopics.isEmpty()) {
                            "All subtopics included"
                        } else {
                            "${topic.activeSubtopics.size} of ${topic.subtopics.size} subtopics active"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = onRemoveTopic)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    MechanicalToggle(
                        checked = topic.enabled,
                        onCheckedChange = onToggleTopic,
                        contentDescriptionText = "Toggle ${topic.name}"
                    )
                }
            }

            HorizontalDivider(color = palette.border)

            // Subtopic chips (tap to toggle active/inactive, tap × to delete)
            if (topic.subtopics.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    topic.subtopics.forEach { sub ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (sub.enabled && topic.enabled) palette.inset else palette.surface,
                            border = BorderStroke(
                                1.dp,
                                if (sub.enabled && topic.enabled) palette.signal else palette.border
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Text(
                                    text = sub.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (sub.enabled && topic.enabled) palette.inkPrimary else palette.inkMuted,
                                    modifier = Modifier.clickable {
                                        onToggleSubtopic(sub.name, !sub.enabled)
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "×",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.inkMuted,
                                    modifier = Modifier
                                        .clickable(role = Role.Button) { onRemoveSubtopic(sub.name) }
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Add subtopic input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = subtopicDraft,
                    onValueChange = { if (it.length <= 48) subtopicDraft = it },
                    placeholder = { Text("Add subtopic to ${topic.name}…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (subtopicDraft.isNotBlank()) {
                                onAddSubtopic(subtopicDraft)
                                subtopicDraft = ""
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.inset,
                        unfocusedContainerColor = palette.inset
                    )
                )
                TactileSecondaryButton(
                    text = "+ Subtopic",
                    enabled = subtopicDraft.isNotBlank(),
                    onClick = {
                        onAddSubtopic(subtopicDraft)
                        subtopicDraft = ""
                    }
                )
            }
        }
    }
}
