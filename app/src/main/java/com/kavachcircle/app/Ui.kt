package com.kavachcircle.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

    fun pill(c: Context, fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, 999).toFloat()
        setStroke(dp(c, 1), stroke)
    }

    /** Horizontal row, vertically centred. */
    fun row(c: Context, top: Int = 8): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp(c, top)
    }

    /** Vertical column that takes the remaining width inside a row. */
    fun col(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** Signal chip: grey while waiting, green once the signal is seen. */
    fun chip(c: Context, s: String): TextView = TextView(c).apply {
        text = s
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(c, 4), dp(c, 7), dp(c, 4), dp(c, 7))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(c, 6)
        }
        setChip(this, false)
    }

    fun setChip(t: TextView, on: Boolean) {
        t.setTextColor(if (on) DARK else MUTED)
        t.background = pill(t.context, if (on) GREEN else PANEL2, if (on) GREEN else LINE)
    }

    fun avatar(c: Context, letter: String, color: Int): TextView = TextView(c).apply {
        text = letter
        textSize = 16f
        setTextColor(DARK)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)).apply { marginEnd = dp(c, 12) }
    }

    /** Round status badge with the shield inside. */
    fun ring(c: Context, color: Int, size: Int = 68): FrameLayout = FrameLayout(c).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(PANEL2)
            setStroke(dp(c, 3), color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size)).apply { marginEnd = dp(c, 14) }
        addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_stat)
            setColorFilter(color)
            layoutParams = FrameLayout.LayoutParams(dp(c, size / 2), dp(c, size / 2), Gravity.CENTER)
        })
    }

    /** Thin progress bar. */
    fun bar(c: Context, fraction: Float, color: Int): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        background = pill(c, PANEL2, LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8)).apply { topMargin = dp(c, 10) }
        val f = fraction.coerceIn(0f, 1f)
        addView(View(c).apply {
            background = pill(c, color, color)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f)
        })
        addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f)
        })
    }

    fun small(c: Context, s: String, fg: Int = MUTED, onClick: () -> Unit): TextView = TextView(c).apply {
        text = s
        textSize = 13f
        setTextColor(fg)
        background = pill(c, PANEL2, LINE)
        setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isClickable = true
        setOnClickListener { onClick() }
    }

    val PIN_INPUT = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    val PHONE_INPUT = InputType.TYPE_CLASS_PHONE
}
