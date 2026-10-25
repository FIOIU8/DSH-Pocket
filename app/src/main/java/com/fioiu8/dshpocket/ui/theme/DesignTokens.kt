package com.fioiu8.dshpocket.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared layout values keep native pages visually related without duplicating magic numbers. */
object DshSpacing {
    val page = 16.dp
    val section = 12.dp
    val row = 16.dp
    val compact = 8.dp
    val rowVertical = 14.dp
    val touchTarget = 52.dp
    val controlHeight = 48.dp
    val icon = 24.dp
}

object DshShapes {
    val surface = 16.dp
    val control = 12.dp
}

object DshMotion {
    val emphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> spring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow,
    )

    const val pageDurationMillis = 220
    const val staggerStepMillis = 55
    const val maxStaggerItems = 5
}

@Composable
fun systemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) > 0f
}

@Composable
fun motionDuration(defaultMillis: Int): Int = if (systemAnimationsEnabled()) defaultMillis else 0

fun <T> dshSpring(enabled: Boolean): FiniteAnimationSpec<T> = if (enabled) {
    DshMotion.spring()
} else {
    tween(durationMillis = 0)
}

fun Dp.pageHorizontalPadding(): Dp = this.coerceAtLeast(DshSpacing.page)

fun Modifier.dshControl(): Modifier = heightIn(min = DshSpacing.controlHeight)
