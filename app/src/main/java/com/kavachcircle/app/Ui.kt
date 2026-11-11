package com.kavachcircle.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small view helpers so the app needs no XML layouts. Colours match the Kavach Circle deck and website. */
object Ui {
    val BG = 0xFF070B14.toInt()
    val PANEL = 0xFF0F1A2B.toInt()
    val PANEL2 = 0xFF132238.toInt()
    val LINE = 0xFF22324A.toInt()
    val INK = 0xFFE6EDF7.toInt()
    val MUTED = 0xFF8FA3BF.toInt()
    val CYAN = 0xFF22D3EE.toInt()
    val AMBER = 0xFFF5A524.toInt()
    val RED = 0xFFFF4D6D.toInt()
    val GREEN = 0xFF34D399.toInt()
    val DARK = 0xFF04141A.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    private fun lp(c: Context, top: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(c, top) }

    fun round(c: Context, fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, 14).toFloat()
        setStroke(dp(c, 1), stroke)
    }

    fun text(c: Context, s: String, size: Float = 15f, color: Int = INK, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
            layoutParams = lp(c, 4)
        }

    fun title(c: Context, s: String, color: Int = INK): TextView = text(c, s, 24f, color, true)

    fun tag(c: Context, s: String, color: Int = AMBER): TextView = text(c, s, 12f, color, true).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    fun button(c: Context, s: String, fill: Int = PANEL2, fg: Int = INK, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s
            isAllCaps = false
            textSize = 15f
            setTextColor(fg)
            stateListAnimator = null
            background = round(c, fill, if (fill == PANEL2) LINE else fill)
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
            gravity = Gravity.CENTER
            layoutParams = lp(c, 8)
            setOnClickListener { onClick() }
        }

    fun card(c: Context, stroke: Int = LINE): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(c, PANEL, stroke)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16))
        layoutParams = lp(c, 12)
    }

    fun edit(c: Context, hint: String, kind: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(c).apply {
        this.hint = hint
        inputType = kind
        setTextColor(INK)
        setHintTextColor(MUTED)
        textSize = 16f
        background = round(c, PANEL2, LINE)
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        layoutParams = lp(c, 8)
    }

    val PIN_INPUT = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    val PHONE_INPUT = InputType.TYPE_CLASS_PHONE
}
