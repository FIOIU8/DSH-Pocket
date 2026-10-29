package com.fioiu8.dshpocket.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import com.fioiu8.dshpocket.R

/**
 * The full-screen marquee the user explicitly asked for: a colored border chasing around
 * the display while the AI operates (plan §2.5). Pure visual — the window is
 * NOT_TOUCHABLE, so it is a hint, never a security boundary.
 *
 * Drawn as a rounded-rect stroked four times per frame, each layer showing one colored
 * "comet" whose dash phase is offset by a quarter perimeter — the marquee chase with
 * phase-shifted edges — plus a hue rotation completing one turn per [CYCLE_MS] and a
 * small badge so the state survives even where the border is covered.
 */
class MarqueeOverlayView(context: Context) : View(context) {

    companion object {
        const val CYCLE_MS = 1_200f
        private const val EDGE_COUNT = 4
        private const val COMET_FRACTION = 1f / EDGE_COUNT
        private const val BADGE_TEXT_SIZE_SP = 12f
        private const val FALLBACK_CORNER_RADIUS_DP = 16f
    }

    private val density = resources.displayMetrics.density
    private val strokeWidth = 2f * density
    private val cornerRadii = FloatArray(8) { FALLBACK_CORNER_RADIUS_DP * density }

    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@MarqueeOverlayView.strokeWidth
    }
    private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BADGE_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }

    private val framePath = Path()
    private val frameMeasure = PathMeasure()
    private val frameRect = RectF()
    // A negative value means the platform did not provide a radius. Zero is valid:
    // it describes a square display corner and must not trigger the fallback radius.
    private val systemCornerRadii = FloatArray(4) { -1f }
    private val hsv = FloatArray(3)
    private val badgeText = context.getString(R.string.screen_control_badge)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshSystemCornerRadii(rootWindowInsets)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        refreshSystemCornerRadii(insets)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCornerRadii()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokeWidth / 2f + 2f * density
        frameRect.set(inset, inset, width - inset, height - inset)
        framePath.reset()
        framePath.addRoundRect(frameRect, cornerRadii, Path.Direction.CW)
        frameMeasure.setPath(framePath, false)
        val length = frameMeasure.length
        if (length <= 0f) return

        val progress = (SystemClock.uptimeMillis() % CYCLE_MS.toLong()) / CYCLE_MS
        // The four segments tile the full perimeter. A small overlap hides antialiasing
        // seams so screen content never shows through as a black or white divider.
        val comet = (length * COMET_FRACTION + strokeWidth).coerceAtMost(length)
        val gap = length - comet
        val baseHue = progress * 360f
        hsv[1] = 0.85f
        hsv[2] = 1f
        for (edge in 0 until EDGE_COUNT) {
            hsv[0] = (baseHue + edge * 360f / EDGE_COUNT) % 360f
            cometPaint.color = Color.HSVToColor(hsv)
            cometPaint.alpha = 255
            cometPaint.pathEffect = DashPathEffect(
                floatArrayOf(comet, gap),
                length * (1f - ((progress + edge.toFloat() / EDGE_COUNT) % 1f)),
            )
            canvas.drawPath(framePath, cometPaint)
        }

        drawBadge(canvas, inset)

        // Self-scheduling: this view redraws every frame while attached, which is exactly
        // its lifetime (attached for the duration of an operation, debounce included).
        postInvalidateOnAnimation()
    }

    private fun drawBadge(canvas: Canvas, inset: Float) {
        val textWidth = badgeTextPaint.measureText(badgeText)
        val padding = 8f * density
        val left = inset + 16f * density
        val top = inset + 16f * density
        val bottom = top + badgeTextPaint.textSize + padding * 2
        val right = left + textWidth + padding * 2
        canvas.drawRoundRect(left, top, right, bottom, bottom - top, bottom - top, badgeBackgroundPaint)
        canvas.drawText(badgeText, left + padding, top + padding - badgeTextPaint.fontMetrics.ascent, badgeTextPaint)
    }

    private fun refreshSystemCornerRadii(insets: WindowInsets?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val positions = intArrayOf(
            android.view.RoundedCorner.POSITION_TOP_LEFT,
            android.view.RoundedCorner.POSITION_TOP_RIGHT,
            android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
            android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
        )
        val display = display
        positions.forEachIndexed { index, position ->
            val insetRadius = insets?.getRoundedCorner(position)?.radius
            val displayRadius = display?.getRoundedCorner(position)?.radius
            val radius = insetRadius ?: displayRadius
            systemCornerRadii[index] = radius?.toFloat() ?: -1f
        }
        updateCornerRadii()
        invalidate()
    }

    private fun updateCornerRadii() {
        val inset = strokeWidth / 2f + 2f * density
        val maxRadius = (minOf(width, height).toFloat() - inset * 2f).coerceAtLeast(0f) / 2f
        for (corner in 0 until 4) {
            val systemRadius = systemCornerRadii[corner]
            // Insets describe the physical screen edge; the path follows its inward offset.
            val pathRadius = if (systemRadius >= 0f) {
                (systemRadius - inset).coerceAtLeast(0f)
            } else {
                FALLBACK_CORNER_RADIUS_DP * density
            }
            val radius = pathRadius.coerceAtMost(maxRadius)
            cornerRadii[corner * 2] = radius
            cornerRadii[corner * 2 + 1] = radius
        }
    }
}
