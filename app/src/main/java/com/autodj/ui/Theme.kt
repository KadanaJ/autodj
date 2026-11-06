package com.autodj.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.autodj.data.Pace

object Palette {
    val BgTop = Color(0xFF0C1336)
    val BgBottom = Color(0xFF030510)
    val Surface = Color(0xFF121834)
    val SurfaceHigh = Color(0xFF1B2347)
    val Outline = Color(0xFF2A3363)
    val Accent = Color(0xFF2F7BFF)
    val AccentGlow = Color(0xFF00C6FF)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFF9AA3C7)
    val TextFaint = Color(0xFF5D6690)
    val Warning = Color(0xFFFFB648)

    val background = Brush.verticalGradient(listOf(BgTop, BgBottom))
    val button = Brush.linearGradient(listOf(AccentGlow, Accent, Color(0xFF3B4BFF)))

    fun pace(p: Pace?): Color = when (p) {
        Pace.CHILL -> Color(0xFF8FA8FF)
        Pace.CRUISE -> Color(0xFF3DDC97)
        Pace.WORKOUT -> Color(0xFFFF9F43)
        Pace.PEAK -> Color(0xFFFF4D6D)
        null -> TextFaint
    }
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.AccentGlow,
    background = Palette.BgBottom,
    surface = Palette.Surface,
    surfaceVariant = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    onSurface = Palette.Text,
    onSurfaceVariant = Palette.TextDim,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
)

private val type = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun AutoDjTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
