package dev.dagent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val accent: Color,
    val accent2: Color,
    val bg: Color = Color(0xFF0B0B0D),
    val surface: Color = Color(0xFF15161A),
    val surface2: Color = Color(0xFF1E2026),
    val line: Color = Color(0xFF2A2D35),
    val text: Color = Color(0xFFF2F2F4),
    val muted: Color = Color(0xFF9BA0AA),
    val good: Color = Color(0xFF3DD9A4),
    val bad: Color = Color(0xFFFF6B6B),
)

val LocalPalette = staticCompositionLocalOf { Palette(Color(0xFFFF6B35), Color(0xFFFFC447)) }

@Composable
fun DAgentTheme(accent: Long, content: @Composable () -> Unit) {
    val a = Color(accent)
    val palette = Palette(accent = a, accent2 = lerp(a, Color(0xFFFFE08A), 0.45f))
    val scheme = darkColorScheme(
        primary = a,
        onPrimary = Color(0xFF140A05),
        secondary = palette.accent2,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.surface2,
        onSurfaceVariant = palette.muted,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface2,
        surfaceContainerHighest = palette.surface2,
        surfaceContainerLow = palette.surface,
        outline = palette.line,
        outlineVariant = palette.line,
        error = palette.bad,
    )
    val type = Typography(
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

val Mono = FontFamily.Monospace
