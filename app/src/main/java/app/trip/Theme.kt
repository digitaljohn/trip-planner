@file:OptIn(ExperimentalTextApi::class)

package app.trip

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AI Enablement type: Archivo for words, IBM Plex Mono for labels; Doto (dot matrix) for the glyph numerals.
private fun archivo(w: Int) = Font(R.font.archivo, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Archivo = FontFamily(archivo(400), archivo(500), archivo(600), archivo(700), archivo(800), archivo(900))
val PlexMono = FontFamily(Font(R.font.plex_mono, FontWeight.Normal), Font(R.font.plex_mono_medium, FontWeight.Medium))
val Doto = FontFamily(
    Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.doto, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

private val Light = lightColorScheme(
    primary = Color(0xFFC41F17), onPrimary = Color(0xFFF6F6F3),
    primaryContainer = Color(0xFFF3DCD8), onPrimaryContainer = Color(0xFF4E0B07),
    secondaryContainer = Color(0xFFDADAD4), onSecondaryContainer = Color(0xFF0B0B0C),
    background = Color(0xFFECECE8), onBackground = Color(0xFF0B0B0C),
    surface = Color(0xFFECECE8), onSurface = Color(0xFF0B0B0C),
    surfaceVariant = Color(0xFFE2E2DD), onSurfaceVariant = Color(0xFF6B6B72),
    surfaceContainerLowest = Color(0xFFF6F6F3), surfaceContainerLow = Color(0xFFF2F2EE),
    surfaceContainer = Color(0xFFF6F6F3), surfaceContainerHigh = Color(0xFFF6F6F3), surfaceContainerHighest = Color(0xFFF6F6F3),
    outline = Color(0xFFB4B4BA), outlineVariant = Color(0xFFD6D6D1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF453C), onPrimary = Color(0xFF0B0B0C),
    primaryContainer = Color(0xFF2B1210), onPrimaryContainer = Color(0xFFFFB8B2),
    secondaryContainer = Color(0xFF232326), onSecondaryContainer = Color(0xFFF6F6F3),
    background = Color(0xFF0B0B0C), onBackground = Color(0xFFF6F6F3),
    surface = Color(0xFF0B0B0C), onSurface = Color(0xFFF6F6F3),
    surfaceVariant = Color(0xFF18181A), onSurfaceVariant = Color(0xFFB4B4BA),
    surfaceContainerLowest = Color(0xFF0B0B0C), surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF131315), surfaceContainerHigh = Color(0xFF161618), surfaceContainerHighest = Color(0xFF161618),
    outline = Color(0xFF6B6B72), outlineVariant = Color(0xFF2A2A2E),
)

private fun body(size: Int, line: Int, w: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Archivo, fontWeight = w, fontSize = size.sp, lineHeight = line.sp)

// Doto's dots overshoot its line box; the extra line height keeps them inside the rolling-digit clip.
private fun dot(size: Int) = TextStyle(
    fontFamily = Doto, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size * 1.3f).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private val AppType = Typography(
    displayLarge = dot(64),
    displayMedium = dot(44),
    displaySmall = dot(32),
    headlineLarge = body(34, 36, FontWeight(850)).copy(letterSpacing = (-0.8).sp),
    headlineMedium = body(28, 30, FontWeight.ExtraBold).copy(letterSpacing = (-0.6).sp),
    headlineSmall = body(24, 28, FontWeight.ExtraBold).copy(letterSpacing = (-0.4).sp),
    titleLarge = body(20, 26, FontWeight.Bold).copy(letterSpacing = (-0.2).sp),
    titleMedium = body(16, 22, FontWeight.Bold),
    titleSmall = body(14, 20, FontWeight.Bold),
    bodyLarge = body(16, 23),
    bodyMedium = body(14, 20),
    bodySmall = body(12, 17),
    labelLarge = body(14, 20, FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.8.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun TripTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppType, shapes = AppShapes, content = content)
}
