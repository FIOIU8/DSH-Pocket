package com.fioiu8.dshpocket.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.fioiu8.dshpocket.R

/**
 * The "turn finished" card shown over other apps when the user is away: a caption, a
 * short hint and two actions — jump straight back to DSH Pocket, or dismiss. Unlike the
 * thought card this window is touchable (the buttons need clicks) but still not
 * focusable, and NOT_TOUCH_MODAL keeps everything outside the card usable.
 */
class TurnDoneView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    var onReturn: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val caption = TextView(context).apply {
        setTextColor(Color.parseColor("#8FA8FF"))
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private val hint = TextView(context).apply {
        setTextColor(Color.parseColor("#E8EBF4"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
    }

    private fun button(textRes: Int, primary: Boolean, onClick: () -> Unit): Button =
        Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
            text = context.getString(textRes)
            setTextColor(if (primary) Color.parseColor("#8FA8FF") else Color.parseColor("#9AA3B5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = false
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            setOnClickListener { onClick() }
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16f), dp(12f), dp(16f), dp(6f))
        background = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(Color.argb(228, 17, 19, 25))
        }
        addView(caption)
        addView(
            hint,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) },
        )
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(button(R.string.turn_done_return, primary = true) { onReturn?.invoke() })
                addView(button(R.string.turn_done_dismiss, primary = false) { onDismiss?.invoke() })
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun render() {
        caption.text = context.getString(R.string.turn_done_title)
        hint.text = context.getString(R.string.turn_done_hint)
    }

    private fun dp(value: Float): Int = (density * value).toInt()
}
