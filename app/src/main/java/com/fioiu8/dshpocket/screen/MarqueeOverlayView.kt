package com.fioiu8.dshpocket.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
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
        private const val COMET_FRACTION = 0.18f
        private const val BADGE_TEXT_SIZE_SP = 12f
    }

    private val density = resources.displayMetrics.density
    private val strokeWidth = 8f * density
    private val cornerRadius = 24f * density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@MarqueeOverlayView.strokeWidth
        color = Color.argb(70, 255, 255, 255)
    }
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
    private val hsv = FloatArray(3)
    private val badgeText = context.getString(R.string.screen_control_badge)

    override fun onDraw(canvas: Canvas) {
        val inset = strokeWidth / 2f + 2f * density
        frameRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, framePaint)

        framePath.reset()
        framePath.addRoundRect(frameRect, cornerRadius, cornerRadius, Path.Direction.CW)
        frameMeasure.setPath(framePath, false)
        val length = frameMeasure.length
        if (length <= 0f) return

        val progress = (SystemClock.uptimeMillis() % CYCLE_MS.toLong()) / CYCLE_MS
        val comet = length * COMET_FRACTION
        val gap = length - comet
        val baseHue = progress * 360f
        hsv[1] = 0.85f
        hsv[2] = 1f
        for (edge in 0 until EDGE_COUNT) {
            hsv[0] = (baseHue + edge * 360f / EDGE_COUNT) % 360f
            cometPaint.color = Color.HSVToColor(hsv)
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
}
