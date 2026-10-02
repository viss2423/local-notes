package dev.localnotes.ui

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

/** Electric studio palette: cool surfaces, clear type and a high-visibility recording signal. */
@Immutable
data class Palette(
    val paper: Color, val raised: Color, val ink: Color, val muted: Color, val faint: Color,
    val line: Color, val accent: Color, val accentSoft: Color, val onAccent: Color, val danger: Color,
)
val LightPalette = Palette(
    paper = Color(0xFFF3F6FC), raised = Color(0xFFFFFFFF), ink = Color(0xFF10182E), muted = Color(0xFF5E6D89), faint = Color(0xFF8C9AB0),
    line = Color(0xFFDCE4F2), accent = Color(0xFF3151D8), accentSoft = Color(0xFFE1E8FF), onAccent = Color(0xFFFFFFFF), danger = Color(0xFFD43C62),
)
val DarkPalette = Palette(
    paper = Color(0xFF090E1D), raised = Color(0xFF151E35), ink = Color(0xFFF5F8FF), muted = Color(0xFFA5B2CB), faint = Color(0xFF74819D),
    line = Color(0xFF293552), accent = Color(0xFFC5F47B), accentSoft = Color(0xFF293B33), onAccent = Color(0xFF111B21), danger = Color(0xFFFF779B),
)
val LocalPalette = staticCompositionLocalOf { LightPalette }
val P: Palette @Composable get() = LocalPalette.current

object Type {
    val display = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.5).sp)
    val title = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 29.sp, lineHeight = 35.sp, letterSpacing = (-0.8).sp)
    val heading = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp)
    val item = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val reading = TextStyle(fontSize = 17.sp, lineHeight = 26.sp)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
    val timer = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 54.sp, lineHeight = 62.sp, letterSpacing = (-2).sp, fontFeatureSettings = "tnum, lnum")
}
/** Digits that don't jiggle while a timer runs. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    // Material dialogs and text fields use the same surfaces as the custom studio controls.
    val scheme = (if (palette == DarkPalette) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.accent, onPrimary = palette.onAccent, background = palette.paper, surface = palette.paper,
        surfaceContainerHigh = palette.raised, surfaceContainer = palette.raised, surfaceContainerHighest = palette.raised,
        onSurface = palette.ink, onSurfaceVariant = palette.muted, outline = palette.line, outlineVariant = palette.line, error = palette.danger,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
