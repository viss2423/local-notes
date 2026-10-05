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

/** A compact audio-console palette with one consistent signal colour in both themes. */
@Immutable
data class Palette(
    val paper: Color, val raised: Color, val ink: Color, val muted: Color, val faint: Color,
    val line: Color, val accent: Color, val accentSoft: Color, val onAccent: Color, val danger: Color,
    val hero: Color, val heroInk: Color, val spark: Color,
)
val LightPalette = Palette(
    paper = Color(0xFFF2F8F7), raised = Color(0xFFFFFFFF), ink = Color(0xFF102B31), muted = Color(0xFF526B70), faint = Color(0xFF71888B),
    line = Color(0xFFD7E7E4), accent = Color(0xFF006F73), accentSoft = Color(0xFFD7F3EC), onAccent = Color(0xFFFFFFFF), danger = Color(0xFFB63343),
    hero = Color(0xFF092E34), heroInk = Color(0xFFEFFFF9), spark = Color(0xFFFF7C65),
)
val DarkPalette = Palette(
    paper = Color(0xFF07191D), raised = Color(0xFF11282C), ink = Color(0xFFE9FAF5), muted = Color(0xFFA8C6BF), faint = Color(0xFF789A94),
    line = Color(0xFF29444A), accent = Color(0xFF75EBD3), accentSoft = Color(0xFF16453F), onAccent = Color(0xFF062924), danger = Color(0xFFFF8790),
    hero = Color(0xFF123940), heroInk = Color(0xFFEFFFF9), spark = Color(0xFFFF9278),
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
