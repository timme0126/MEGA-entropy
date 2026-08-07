package org.mega.entropy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MegaDarkColorScheme = darkColorScheme(
    primary = MegaOrange,
    onPrimary = MegaCharcoal,
    secondary = MegaOrangeDim,
    onSecondary = MegaTextPrimaryDark,
    background = MegaCharcoal,
    onBackground = MegaTextPrimaryDark,
    surface = MegaCharcoalElevated,
    onSurface = MegaTextPrimaryDark,
    surfaceVariant = MegaCharcoalElevated,
    onSurfaceVariant = MegaTextSecondaryDark,
    outline = MegaCharcoalOutline,
    error = MegaError,
)

private val MegaLightColorScheme = lightColorScheme(
    primary = MegaOrangeOnLight,
    onPrimary = MegaOffWhite,
    secondary = MegaOrangeDim,
    onSecondary = MegaTextPrimaryLight,
    background = MegaOffWhite,
    onBackground = MegaTextPrimaryLight,
    surface = MegaOffWhiteElevated,
    onSurface = MegaTextPrimaryLight,
    surfaceVariant = MegaOffWhiteElevated,
    onSurfaceVariant = MegaTextSecondaryLight,
    outline = MegaOffWhiteOutline,
    error = MegaError,
)

/** Monospace type is used throughout for anything numeric/calculated, so a
 * reviewer's eye can line up digits — see docs on "Show the math". */
val MegaMonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
)

private val MegaTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp),
)

@Composable
fun MegaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) MegaDarkColorScheme else MegaLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MegaTypography,
        content = content,
    )
}
