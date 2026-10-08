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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.localnotes.R

/**
 * "Field notebook" palette: warm paper and ink, one signal red for everything that is live, and a
 * highlighter yellow that only ever marks something you found or flagged. The recording screen always
 * uses the dark "deck" colours, like the lit display of a recorder.
 */
@Immutable
data class Palette(
    val paper: Color, val raised: Color, val ink: Color, val muted: Color, val faint: Color, val line: Color,
    val rec: Color, val recSoft: Color, val onRec: Color, val mark: Color, val markSoft: Color, val danger: Color,
    val deck: Color, val deckInk: Color, val deckMuted: Color, val deckLine: Color,
)
val LightPalette = Palette(
    paper = Color(0xFFF4F0E8), raised = Color(0xFFEBE6DA), ink = Color(0xFF1B1915), muted = Color(0xFF666054), faint = Color(0xFF9A9486),
    line = Color(0xFFD8D2C4), rec = Color(0xFFD93A12), recSoft = Color(0xFFF6DDD2), onRec = Color(0xFFFFFFFF),
    mark = Color(0xFFF2D13B), markSoft = Color(0xFFFAEFB4), danger = Color(0xFFB3261E),
    deck = Color(0xFF100F0D), deckInk = Color(0xFFF4EFE6), deckMuted = Color(0xFFA29B8C), deckLine = Color(0xFF2E2B27),
)
val DarkPalette = Palette(
    paper = Color(0xFF151412), raised = Color(0xFF211F1B), ink = Color(0xFFF0EBE1), muted = Color(0xFFA39C8E), faint = Color(0xFF6E685D),
    line = Color(0xFF34312B), rec = Color(0xFFFF5A33), recSoft = Color(0xFF3A211A), onRec = Color(0xFF1A0C07),
    mark = Color(0xFFE9C93C), markSoft = Color(0xFF3B3414), danger = Color(0xFFF2B8B5),
    deck = Color(0xFF0D0C0B), deckInk = Color(0xFFF4EFE6), deckMuted = Color(0xFFA29B8C), deckLine = Color(0xFF2E2B27),
)
val LocalPalette = staticCompositionLocalOf { LightPalette }
val P: Palette @Composable get() = LocalPalette.current

@OptIn(ExperimentalTextApi::class)
private fun sans(weight: Int) = Font(R.font.instrument_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
/** Instrument Serif for titles, Instrument Sans for reading and controls, IBM Plex Mono for every number that ticks. */
val Serif = FontFamily(Font(R.font.instrument_serif), Font(R.font.instrument_serif_italic, style = FontStyle.Italic))
val Sans = FontFamily(sans(400), sans(500), sans(600), sans(700))
val Mono = FontFamily(Font(R.font.plex_mono, FontWeight.Normal), Font(R.font.plex_mono_medium, FontWeight.Medium))

object Type {
    val display = TextStyle(fontFamily = Serif, fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp)
    val title = TextStyle(fontFamily = Serif, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val heading = TextStyle(fontFamily = Serif, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp)
    val item = TextStyle(fontFamily = Serif, fontSize = 21.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyStrong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
    val reading = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 27.sp)
    val small = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp)
    val smallStrong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    /** Small mono caps, used above sections and for statuses. */
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.1.sp)
    val mono = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    val timer = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 62.sp, lineHeight = 68.sp, letterSpacing = (-3).sp, fontFeatureSettings = "tnum")
}
/** Digits that don't jiggle while a timer runs. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    // A few Material pieces remain (text selection, text); they take the notebook colours.
    val scheme = (if (palette == DarkPalette) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.rec, onPrimary = palette.onRec, background = palette.paper, surface = palette.paper,
        onSurface = palette.ink, onSurfaceVariant = palette.muted, outline = palette.line, outlineVariant = palette.line, error = palette.danger,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
