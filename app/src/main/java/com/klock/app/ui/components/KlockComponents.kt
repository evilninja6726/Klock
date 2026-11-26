package com.klock.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klock.app.domain.model.AlarmQuestion
import com.klock.app.domain.model.QuestionProvenance
import com.klock.app.ui.theme.KlockMono
import com.klock.app.ui.theme.LocalKlockPalette

/**
 * Signature element: 5-Segment Mechanical Lock Bar.
 * Displays exact dismissal progress (e.g., 2 / 5 UNLOCKED) and per-slot difficulty tier markers.
 */
@Composable
fun MechanicalLockBar(
    requiredCount: Int,
    solvedCount: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val safeTotal = requiredCount.coerceIn(1, 100)
    val displayQuestionNumber = (solvedCount + 1).coerceAtMost(safeTotal)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mechanical_lock_bar")
            .semantics {
                contentDescription = "Progress: $solvedCount of $safeTotal questions answered"
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (solvedCount >= safeTotal) "All questions answered"
                else "Question $displayQuestionNumber of $safeTotal",
                style = MaterialTheme.typography.labelLarge,
                color = palette.inkSecondary
            )
            Text(
                text = "$solvedCount / $safeTotal",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (solvedCount >= safeTotal) palette.verified else palette.inkPrimary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (safeTotal <= 10) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in 0 until safeTotal) {
                    val isSolved = i < solvedCount
                    val isCurrent = i == currentIndex && solvedCount < safeTotal

                    val fill = when {
                        isSolved -> palette.verified
                        isCurrent -> palette.signal
                        else -> palette.inset
                    }
                    val borderColor = when {
                        isSolved -> palette.verified
                        isCurrent -> palette.signal
                        else -> palette.border
                    }
                    val labelColor = when {
                        isSolved -> Color.White
                        isCurrent -> palette.signalOn
                        else -> palette.inkMuted
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(fill)
                                .border(1.dp, borderColor, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSolved) "✓" else "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = labelColor
                            )
                        }
                    }
                }
            }
        } else {
            val fraction = (solvedCount.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.inset)
                    .border(1.dp, palette.border, RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(10.dp)
                        .background(if (solvedCount >= safeTotal) palette.verified else palette.signal)
                )
            }
        }
    }
}

/**
 * Minimal 3-line hamburger menu button (48x48dp accessible target, zero icon-font bloat).
 */
@Composable
fun HamburgerMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open navigation menu" },
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .background(palette.inkPrimary)
                )
            }
        }
    }
}

/**
 * High-contrast monospace code workbench block that scrolls horizontally on compact 320dp screens
 * so SQL queries and Python indentation never wrap destructively.
 */
@Composable
fun CodeWorkbenchBlock(
    code: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    val horizontalScroll = rememberScrollState()
    val lines = code.lines()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(palette.codeBackground)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(6.dp))
            .horizontalScroll(horizontalScroll)
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEachIndexed { idx, line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = String.format("%2d", idx + 1),
                        fontFamily = KlockMono,
                        fontSize = 12.sp,
                        color = Color(0xFF6E6B64)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = line,
                        fontFamily = KlockMono,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = palette.codeForeground
                    )
                }
            }
        }
    }
}

@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val palette = LocalKlockPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        ) {
            content()
        }
    }
}

@Composable
fun TactilePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalKlockPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.signal,
            contentColor = palette.signalOn,
            disabledContainerColor = palette.inset,
            disabledContentColor = palette.inkMuted
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun TactileSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalKlockPalette.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, palette.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = palette.inkPrimary,
            disabledContentColor = palette.inkMuted
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * Tactile mechanical toggle switch (zero round Material pill default) with 48dp accessible hit target.
 */
@Composable
fun MechanicalToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescriptionText: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .semantics { contentDescription = contentDescriptionText },
        contentAlignment = Alignment.CenterEnd
    ) {
        Row(
            modifier = Modifier
                .width(48.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) palette.inkPrimary else palette.inset)
                .border(
                    width = 1.dp,
                    color = if (checked) palette.inkPrimary else palette.borderStrong,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(3.dp),
            horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (checked) palette.signal else palette.inkMuted)
            )
        }
    }
}

@Composable
fun TechnicalTag(
    text: String,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val palette = LocalKlockPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (highlighted) palette.signal.copy(alpha = 0.14f) else palette.inset)
            .border(
                width = 1.dp,
                color = if (highlighted) palette.signal else palette.border,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) palette.signal else palette.inkSecondary
        )
    }
}
