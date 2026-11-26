package com.klock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.klock.app.domain.model.ThemePreference

@Immutable
data class KlockPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val inset: Color,
    val border: Color,
    val borderStrong: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val signal: Color,
    val signalOn: Color,
    val verified: Color,
    val danger: Color,
    val codeBackground: Color,
    val codeForeground: Color
)

val LightKlockPalette = KlockPalette(
    isDark = false,
    canvas = Color(0xFFF6F5F1),         // Warm archival stone
    surface = Color(0xFFFFFFFF),        // Clean paper sheet
    surfaceElevated = Color(0xFFFAF9F6),
    inset = Color(0xFFEFECE6),          // Inset control well
    border = Color(0xFFE2DFD7),         // 1dp hairline divider
    borderStrong = Color(0xFFC8C4BA),   // Active control border
    inkPrimary = Color(0xFF141413),     // Carbon ink
    inkSecondary = Color(0xFF52504B),   // Supporting copy
    inkMuted = Color(0xFF85827A),       // Metadata & inactive days
    signal = Color(0xFFD94E1F),         // Braun signal vermilion
    signalOn = Color(0xFFFFFFFF),
    verified = Color(0xFF1B6B3A),       // Editorial forest green
    danger = Color(0xFFB83227),         // Alert brick red
    codeBackground = Color(0xFF181816), // High-contrast workbench block
    codeForeground = Color(0xFFF2EFE9)
)

val DarkKlockPalette = KlockPalette(
    isDark = true,
    canvas = Color(0xFF0E0E0D),         // True warm obsidian (low-light 6 AM friendly)
    surface = Color(0xFF161614),        // Level-1 surface
    surfaceElevated = Color(0xFF1E1E1B),
    inset = Color(0xFF111110),          // Inset control well
    border = Color(0xFF282724),         // Whisper-quiet hairline
    borderStrong = Color(0xFF42403B),   // Focused border
    inkPrimary = Color(0xFFF2F0EA),     // Warm bone white
    inkSecondary = Color(0xFFA6A39B),   // Supporting copy
    inkMuted = Color(0xFF6E6B64),       // Metadata & inactive days
    signal = Color(0xFFEE6332),         // Warm Braun signal orange
    signalOn = Color(0xFF0E0E0D),
    verified = Color(0xFF3CA86B),       // Desaturated phosphor green
    danger = Color(0xFFE3564A),         // Desaturated alert red
    codeBackground = Color(0xFF131311),
    codeForeground = Color(0xFFEAE7E0)
)

val LocalKlockPalette = staticCompositionLocalOf { LightKlockPalette }

private val TabularSans = FontFamily.SansSerif
val KlockMono = FontFamily.Monospace

val KlockTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineMedium = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = TabularSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = KlockMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun KlockTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val palette = if (useDark) DarkKlockPalette else LightKlockPalette

    val m3Scheme = if (useDark) {
        darkColorScheme(
            primary = palette.signal,
            onPrimary = palette.signalOn,
            background = palette.canvas,
            onBackground = palette.inkPrimary,
            surface = palette.surface,
            onSurface = palette.inkPrimary,
            surfaceVariant = palette.inset,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.border,
            error = palette.danger
        )
    } else {
        lightColorScheme(
            primary = palette.signal,
            onPrimary = palette.signalOn,
            background = palette.canvas,
            onBackground = palette.inkPrimary,
            surface = palette.surface,
            onSurface = palette.inkPrimary,
            surfaceVariant = palette.inset,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.border,
            error = palette.danger
        )
    }

    CompositionLocalProvider(LocalKlockPalette provides palette) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = KlockTypography,
            content = content
        )
    }
}
