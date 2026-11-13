package com.kavachcircle.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
    fun ring(c: Context, color: Int, size: Int = 68, res: Int = R.drawable.ic_stat): FrameLayout = FrameLayout(c).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(PANEL2)
            setStroke(dp(c, 3), color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size)).apply { marginEnd = dp(c, 14) }
        addView(ImageView(c).apply {
            setImageResource(res)
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

    fun oval(c: Context, fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(c, 1), stroke)
    }

    /** Round icon button for the top bar. */
    fun iconBtn(c: Context, res: Int, onClick: (View) -> Unit): ImageView = ImageView(c).apply {
        setImageResource(res)
        setColorFilter(INK)
        background = oval(c, PANEL, LINE)
        val pad = dp(c, 11)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)).apply { marginEnd = dp(c, 12) }
        setOnClickListener { onClick(it) }
    }

    /** Keypad key for the PIN screen. */
    fun key(c: Context, s: String, onClick: () -> Unit): TextView = TextView(c).apply {
        text = s
        textSize = 24f
        setTextColor(INK)
        gravity = Gravity.CENTER
        background = oval(c, PANEL, LINE)
        layoutParams = LinearLayout.LayoutParams(dp(c, 68), dp(c, 68)).apply {
            marginStart = dp(c, 10)
            marginEnd = dp(c, 10)
        }
        isClickable = true
        setOnClickListener {
            animate().scaleX(0.88f).scaleY(0.88f).setDuration(60).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()
            onClick()
        }
    }

    /** Dashboard tile: tilts in 3D under the finger and flips when tapped. */
    fun tile(c: Context, res: Int, title: String, sub: String, color: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(c, PANEL, LINE)
            setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14))
            minimumHeight = dp(c, 132)
            addView(ring(c, color, 44, res))
            addView(text(c, title, 15f, INK, true).apply { setPadding(0, dp(c, 8), 0, 0) })
            addView(text(c, sub, 12f, MUTED))
            tilt(this)
            isClickable = true
            setOnClickListener {
                animate().rotationY(90f).setDuration(140).withEndAction {
                    rotationY = 0f
                    onClick()
                }.start()
            }
        }

    private fun depth(v: View) {
        v.cameraDistance = 9000f * v.resources.displayMetrics.density
    }

    /** Views rise into place with a small 3D swing, one after another. */
    fun enter(v: View, index: Int) {
        depth(v)
        v.alpha = 0f
        v.translationY = dp(v.context, 30).toFloat()
        v.rotationX = -20f
        v.animate().alpha(1f).translationY(0f).rotationX(0f)
            .setStartDelay(45L * index.coerceAtMost(12)).setDuration(380)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Slow breathing pulse for status badges. Cancel the returned animator when the view goes away. */
    fun pulse(v: View): Animator = ObjectAnimator.ofPropertyValuesHolder(
        v,
        PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
        PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f)
    ).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        start()
    }

    /** Continuous 3D spin around the vertical axis. */
    fun spin(v: View): Animator {
        depth(v)
        return ObjectAnimator.ofFloat(v, "rotationY", 0f, 360f).apply {
            duration = 5200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun shake(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, 26f, -26f, 18f, -18f, 8f, 0f).setDuration(380).start()
    }

    /** The view leans toward the finger, like the cards on the website. */
    @SuppressLint("ClickableViewAccessibility")
    fun tilt(v: View) {
        depth(v)
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = view.width.coerceAtLeast(1).toFloat()
                    val h = view.height.coerceAtLeast(1).toFloat()
                    view.rotationY = (e.x / w - 0.5f) * 18f
                    view.rotationX = (0.5f - e.y / h) * 18f
                    view.scaleX = 0.97f
                    view.scaleY = 0.97f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).setDuration(220).start()
            }
            false
        }
    }

    val PIN_INPUT = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    val PHONE_INPUT = InputType.TYPE_CLASS_PHONE
}
