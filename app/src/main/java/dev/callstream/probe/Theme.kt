package dev.callstream.probe

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Call Stream's look: calm ink surfaces, with one "signal" gradient (mint to violet) that means the agent is live.
 * Amber means a call is in progress, coral means something needs attention. Follows the phone's light/dark setting.
 */
@Immutable
data class BrandColors(
    val card: Color,
    val cardBorder: Color,
    val signalStart: Color,
    val signalEnd: Color,
    val live: Color,
    val liveContainer: Color,
    val busy: Color,
    val busyContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val idle: Color,
    val idleContainer: Color,
) {
    val signal: Brush get() = Brush.linearGradient(listOf(signalStart, signalEnd))
}

private val DarkBrand = BrandColors(
    card = Color(0xFF14181F), cardBorder = Color(0xFF222832),
    signalStart = Color(0xFF5BE7C4), signalEnd = Color(0xFF8B7CFF),
    live = Color(0xFF5BE7C4), liveContainer = Color(0xFF0F3A31),
    busy = Color(0xFFFFC870), busyContainer = Color(0xFF3B2D12),
    danger = Color(0xFFFF8A80), dangerContainer = Color(0xFF45201D),
    idle = Color(0xFF9AA4B2), idleContainer = Color(0xFF222832),
)

private val LightBrand = BrandColors(
    card = Color(0xFFFFFFFF), cardBorder = Color(0xFFE3E7EC),
    signalStart = Color(0xFF14C79B), signalEnd = Color(0xFF6E5BF0),
    live = Color(0xFF008A6C), liveContainer = Color(0xFFD3F5EB),
    busy = Color(0xFF9A6200), busyContainer = Color(0xFFFFEBC7),
    danger = Color(0xFFC4302B), dangerContainer = Color(0xFFFDE2DF),
    idle = Color(0xFF5E6875), idleContainer = Color(0xFFEDF0F3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5BE7C4), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0F3A31), onPrimaryContainer = Color(0xFF9CF5DD),
    secondary = Color(0xFFB4A8FF), onSecondary = Color(0xFF24186B),
    secondaryContainer = Color(0xFF2C2552), onSecondaryContainer = Color(0xFFDDD6FF),
    tertiary = Color(0xFFFFC870), onTertiary = Color(0xFF3F2D00),
    tertiaryContainer = Color(0xFF3B2D12), onTertiaryContainer = Color(0xFFFFE2B0),
    error = Color(0xFFFF8A80), onError = Color(0xFF5C0D08),
    errorContainer = Color(0xFF45201D), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF0A0C10), onBackground = Color(0xFFE9ECF1),
    surface = Color(0xFF0A0C10), onSurface = Color(0xFFE9ECF1),
    surfaceVariant = Color(0xFF1C2129), onSurfaceVariant = Color(0xFF9AA4B2),
    surfaceContainerLowest = Color(0xFF07080B), surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = Color(0xFF14181F), surfaceContainerHigh = Color(0xFF1B2028), surfaceContainerHighest = Color(0xFF232933),
    outline = Color(0xFF3A424E), outlineVariant = Color(0xFF242A33),
    inverseSurface = Color(0xFFE9ECF1), inverseOnSurface = Color(0xFF14181F), inversePrimary = Color(0xFF008A6C),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF008A6C), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3F5EB), onPrimaryContainer = Color(0xFF00382C),
    secondary = Color(0xFF5F4DD6), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E1FF), onSecondaryContainer = Color(0xFF1D1361),
    tertiary = Color(0xFF9A6200), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEBC7), onTertiaryContainer = Color(0xFF301F00),
    error = Color(0xFFC4302B), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE2DF), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F7F9), onBackground = Color(0xFF12161C),
    surface = Color(0xFFF5F7F9), onSurface = Color(0xFF12161C),
    surfaceVariant = Color(0xFFE6EAEE), onSurfaceVariant = Color(0xFF5E6875),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEEF2), surfaceContainerHigh = Color(0xFFE5E9EE), surfaceContainerHighest = Color(0xFFDFE3E9),
    outline = Color(0xFFC3CAD3), outlineVariant = Color(0xFFE3E7EC),
    inverseSurface = Color(0xFF232933), inverseOnSurface = Color(0xFFEFF1F4), inversePrimary = Color(0xFF5BE7C4),
)

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

/** Small caps-style label above a group, e.g. "PERSONALITY". */
val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

val LocalBrand = staticCompositionLocalOf { DarkBrand }

object Brand {
    val colors: BrandColors @Composable get() = LocalBrand.current
}

@Composable
fun CallStreamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBrand provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
