package com.fioiu8.dshpocket.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** M3 "rounded" shape family（设计稿 theme.shape = rounded）。 */
val DSHPocketShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightColors = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = TealOnPrimaryLight,
    primaryContainer = TealPrimaryContainerLight,
    onPrimaryContainer = TealOnPrimaryContainerLight,
    secondary = TealSecondaryLight,
    onSecondary = TealOnSecondaryLight,
    secondaryContainer = TealSecondaryContainerLight,
    onSecondaryContainer = TealOnSecondaryContainerLight,
    tertiary = TealTertiaryLight,
    onTertiary = TealOnTertiaryLight,
    tertiaryContainer = TealTertiaryContainerLight,
    onTertiaryContainer = TealOnTertiaryContainerLight,
    error = TealErrorLight,
    onError = TealOnErrorLight,
    errorContainer = TealErrorContainerLight,
    onErrorContainer = TealOnErrorContainerLight,
    background = TealSurfaceLight,
    onBackground = TealOnSurfaceLight,
    surface = TealSurfaceLight,
    onSurface = TealOnSurfaceLight,
    surfaceVariant = TealSurfaceVariantLight,
    onSurfaceVariant = TealOnSurfaceVariantLight,
    outline = TealOutlineLight,
    outlineVariant = TealOutlineVariantLight,
    inverseSurface = TealInverseSurfaceLight,
    inverseOnSurface = TealInverseOnSurfaceLight,
    inversePrimary = TealInversePrimaryLight,
    surfaceDim = TealSurfaceDimLight,
    surfaceBright = TealSurfaceBrightLight,
    surfaceContainerLowest = TealSurfaceContainerLowestLight,
    surfaceContainerLow = TealSurfaceContainerLowLight,
    surfaceContainer = TealSurfaceContainerLight,
    surfaceContainerHigh = TealSurfaceContainerHighLight,
    surfaceContainerHighest = TealSurfaceContainerHighestLight,
)

private val DarkColors = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = TealOnPrimaryDark,
    primaryContainer = TealPrimaryContainerDark,
    onPrimaryContainer = TealOnPrimaryContainerDark,
    secondary = TealSecondaryDark,
    onSecondary = TealOnSecondaryDark,
    secondaryContainer = TealSecondaryContainerDark,
    onSecondaryContainer = TealOnSecondaryContainerDark,
    tertiary = TealTertiaryDark,
    onTertiary = TealOnTertiaryDark,
    tertiaryContainer = TealTertiaryContainerDark,
    onTertiaryContainer = TealOnTertiaryContainerDark,
    error = TealErrorDark,
    onError = TealOnErrorDark,
    errorContainer = TealErrorContainerDark,
    onErrorContainer = TealOnErrorContainerDark,
    background = TealSurfaceDark,
    onBackground = TealOnSurfaceDark,
    surface = TealSurfaceDark,
    onSurface = TealOnSurfaceDark,
    surfaceVariant = TealSurfaceVariantDark,
    onSurfaceVariant = TealOnSurfaceVariantDark,
    outline = TealOutlineDark,
    outlineVariant = TealOutlineVariantDark,
    inverseSurface = TealInverseSurfaceDark,
    inverseOnSurface = TealInverseOnSurfaceDark,
    inversePrimary = TealInversePrimaryDark,
    surfaceDim = TealSurfaceDimDark,
    surfaceBright = TealSurfaceBrightDark,
    surfaceContainerLowest = TealSurfaceContainerLowestDark,
    surfaceContainerLow = TealSurfaceContainerLowDark,
    surfaceContainer = TealSurfaceContainerDark,
    surfaceContainerHigh = TealSurfaceContainerHighDark,
    surfaceContainerHighest = TealSurfaceContainerHighestDark,
)

/**
 * The design reference asks for the fixed cool-neutral palette in both modes,
 * so the scheme here is fixed. [dynamicColor] is kept as an escape hatch for users who
 * prefer their wallpaper palette.
 */
@Composable
fun DSHPocketTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val canUseDynamicColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val colorScheme = if (canUseDynamicColor) {
        if (darkTheme) {
            androidx.compose.material3.dynamicDarkColorScheme(
                context,
            )
        } else {
            androidx.compose.material3.dynamicLightColorScheme(
                context,
            )
        }
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = DSHPocketShapes,
        // 注：设计稿 theme.motion = expressive，但 material3 1.4.0 的 MotionScheme
        // 仍是 internal API，等上游公开后在此接入 expressive 弹簧动效。
        content = content,
    )
}
