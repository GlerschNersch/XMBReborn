package dev.xmbreborn

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Short messages ("Backup saved", "Can't open Maps") in the XMB's manner rather than Android's
 * toasts: a dark translucent box that eases in at the top right, as the PS3's notifications do,
 * with its OK or error sound, then fades after a moment. A new message replaces the old one.
 */
class Notice(private val root: FrameLayout, private val sfx: Sfx) {
    private val context = root.context
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
        maxWidth = (context.resources.displayMetrics.widthPixels * 0.7f).toInt()
        background = GradientDrawable().apply {
            setColor(Color.argb(190, 0, 0, 0))
            cornerRadius = dp(6f)
            setStroke(dp(1f).toInt(), Color.argb(60, 255, 255, 255))
        }
        alpha = 0f
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val hide = Runnable {
        view.animate().alpha(0f).translationX(dp(24f)).setDuration(FADE_MS)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    init {
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40f).toInt()
            marginEnd = dp(12f).toInt()
        })
        // Keep it clear of the status bar and cutout, wherever they are.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top
            else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            (view.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(12f).toInt()
            view.requestLayout()
            v.onApplyWindowInsets(insets)
        }
    }

    /** Shows [text]; [ok] picks the sound (true: worked, false: didn't, null: none). */
    fun show(text: String, ok: Boolean? = null) {
        view.removeCallbacks(hide)
        view.animate().cancel()
        view.text = text
        view.visibility = View.VISIBLE
        view.bringToFront()
        if (view.alpha < 0.5f) view.translationX = dp(24f)
        view.animate().alpha(1f).translationX(0f).setDuration(FADE_MS).start()
        view.postDelayed(hide, if (text.length > 40) LONG_MS else SHORT_MS)
        when (ok) {
            true -> sfx.play(Sfx.Kind.OK)
            false -> sfx.play(Sfx.Kind.ERROR)
            null -> Unit
        }
    }

    private companion object {
        const val FADE_MS = 220L
        const val SHORT_MS = 2200L
        const val LONG_MS = 3500L
    }
}
