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
    paper = Color(0xFFF5F6F8), raised = Color(0xFFFFFFFF), ink = Color(0xFF192332), muted = Color(0xFF566274), faint = Color(0xFF637086),
    line = Color(0xFFE0E4EB), accent = Color(0xFF2855D9), accentSoft = Color(0xFFE8EEFF), onAccent = Color(0xFFFFFFFF), danger = Color(0xFFB63343),
    hero = Color(0xFF17243D), heroInk = Color(0xFFF6F8FF), spark = Color(0xFFA8C1FF),
)
val DarkPalette = Palette(
    paper = Color(0xFF10151F), raised = Color(0xFF1A2230), ink = Color(0xFFEFF3FA), muted = Color(0xFFB5C0D2), faint = Color(0xFF91A0B7),
    line = Color(0xFF303D51), accent = Color(0xFFA8C1FF), accentSoft = Color(0xFF23385E), onAccent = Color(0xFF14294E), danger = Color(0xFFFF8790),
    hero = Color(0xFF1C2D4A), heroInk = Color(0xFFF6F8FF), spark = Color(0xFFA8C1FF),
)
val LocalPalette = staticCompositionLocalOf { LightPalette }
val P: Palette @Composable get() = LocalPalette.current

object Type {
    val display = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp)
    val title = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp)
    val heading = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp)
    val item = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val reading = TextStyle(fontSize = 17.sp, lineHeight = 26.sp)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.7.sp)
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
