package com.fioiu8.dshpocket.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.fioiu8.dshpocket.R

/**
 * The passive card the thought overlay shows: a caption plus the tail of the model's
 * reasoning stream. Pure display — no touch handling of any kind (the window itself is
 * FLAG_NOT_TOUCHABLE, so touches fall through to whatever is underneath).
 */
class ThoughtOverlayView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    private val caption = TextView(context).apply {
        setTextColor(Color.parseColor("#8FA8FF"))
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.04f
    }

    private val body = TextView(context).apply {
        setTextColor(Color.parseColor("#E8EBF4"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.MONOSPACE
        maxLines = 8
        // The newest reasoning is at the tail; ellipsize the head instead of hiding it.
        ellipsize = android.text.TextUtils.TruncateAt.START
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        val pad = dp(12f)
        setPadding(pad, dp(8f), pad, dp(9f))
        background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(Color.argb(212, 17, 19, 25))
        }
        addView(caption)
        addView(
            body,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3f) },
        )
    }

    fun render(state: ThoughtState) {
        caption.text = context.getString(
            if (state.thinking) R.string.thought_overlay_thinking else R.string.thought_overlay_answering,
        )
        if (state.text.isBlank()) {
            body.visibility = GONE
        } else {
            body.visibility = VISIBLE
            body.text = state.text
        }
    }

    private fun dp(value: Float): Int = (density * value).toInt()
}
