package com.zetaforge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Palette: "forge at night" - deep indigo surfaces, violet primary for actions,
// cyan for accents/metrics, amber for warnings, coral for failures. Chosen for
// a developer tool: high contrast, calm background, saturated colour reserved
// for state.
// ---------------------------------------------------------------------------

internal val Violet500 = Color(0xFF7C5CFF)
internal val Violet400 = Color(0xFF9B85FF)
internal val Violet700 = Color(0xFF5233D6)
internal val Cyan400 = Color(0xFF22D3EE)
internal val Cyan600 = Color(0xFF0891B2)
internal val Emerald400 = Color(0xFF34D399)
internal val Emerald600 = Color(0xFF059669)
internal val Amber400 = Color(0xFFFBBF24)
internal val Amber600 = Color(0xFFD97706)
internal val Coral400 = Color(0xFFFB7185)
internal val Coral600 = Color(0xFFE11D48)

internal val Ink900 = Color(0xFF0B1020)
internal val Ink800 = Color(0xFF121834)
internal val Ink700 = Color(0xFF1B2145)
internal val Ink600 = Color(0xFF272E5C)
internal val Mist50 = Color(0xFFF6F7FB)
internal val Mist100 = Color(0xFFECEEF7)
internal val Mist200 = Color(0xFFDCE0EE)
internal val Slate500 = Color(0xFF64748B)

private val DarkColors = darkColorScheme(
    primary = Violet400,
    onPrimary = Ink900,
    primaryContainer = Violet700,
    onPrimaryContainer = Color.White,
    secondary = Cyan400,
    onSecondary = Ink900,
    secondaryContainer = Cyan600,
    onSecondaryContainer = Color.White,
    tertiary = Emerald400,
    onTertiary = Ink900,
    background = Ink900,
    onBackground = Color(0xFFE6E8F5),
    surface = Ink800,
    onSurface = Color(0xFFE6E8F5),
    surfaceVariant = Ink700,
    onSurfaceVariant = Color(0xFFAFB6D8),
    outline = Ink600,
    outlineVariant = Ink700,
    error = Coral400,
    onError = Ink900,
    errorContainer = Color(0xFF4C1130),
    onErrorContainer = Coral400,
)

private val LightColors = lightColorScheme(
    primary = Violet500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Violet700,
    secondary = Cyan600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F5FB),
    onSecondaryContainer = Color(0xFF05505F),
    tertiary = Emerald600,
    onTertiary = Color.White,
    background = Mist50,
    onBackground = Ink900,
    surface = Color.White,
    onSurface = Ink900,
    surfaceVariant = Mist100,
    onSurfaceVariant = Color(0xFF4A5270),
    outline = Mist200,
    outlineVariant = Mist100,
    error = Coral600,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E9),
    onErrorContainer = Coral600,
)

/** Colours that carry meaning rather than brand, resolved per theme. */
data class ZetaAccentColors(
    val success: Color,
    val warning: Color,
    val info: Color,
    val danger: Color,
    val consoleBackground: Color,
    val consoleText: Color,
    val muted: Color,
)

val zetaAccentsDark = ZetaAccentColors(
    success = Emerald400,
    warning = Amber400,
    info = Cyan400,
    danger = Coral400,
    consoleBackground = Color(0xFF080C1A),
    consoleText = Color(0xFFC9D1E8),
    muted = Color(0xFF8B93BC),
)

val zetaAccentsLight = ZetaAccentColors(
    success = Emerald600,
    warning = Amber600,
    info = Cyan600,
    danger = Coral600,
    consoleBackground = Ink900,
    consoleText = Color(0xFFD5DBF0),
    muted = Slate500,
)

private val ZetaTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelSmall = labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Monospaced style used by the log console and by technical key/value rows. */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

@Composable
fun ZetaForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ZetaTypography,
        content = content,
    )
}

/** Accent colours for the current theme. */
@Composable
fun zetaAccents(darkTheme: Boolean = isSystemInDarkTheme()): ZetaAccentColors =
    if (darkTheme) zetaAccentsDark else zetaAccentsLight
