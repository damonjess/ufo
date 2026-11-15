package com.sightings.atlas.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.util.WeakHashMap

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

fun Context.colorOf(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

fun Context.rounded(
    fill: Int,
    stroke: Int = Color.TRANSPARENT,
    radiusDp: Float,
    strokeDp: Float = 1f,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dpf(radiusDp)
    setColor(fill)
    if (stroke != Color.TRANSPARENT) {
        setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), stroke)
    }
}

fun Context.pill(fill: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = rounded(fill, stroke, 2f)

fun Context.oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.monoText(sizeSp: Float, color: Int, spacing: Float = 0f): TextView = TextView(this).apply {
    typeface = Typeface.MONOSPACE
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    letterSpacing = spacing
    includeFontPadding = false
}

private const val CIPHER_GLYPHS = "0123456789ABCDEF#%&*+<>?@[]"
private val scrambleRunnables = WeakHashMap<TextView, Runnable>()

/** Fast left-to-right cipher reveal for HUD telemetry. */
fun TextView.scrambleText(target: String, durationMs: Long = 240L) {
    val view = this
    scrambleRunnables.remove(view)?.let { removeCallbacks(it) }
    if (target.isEmpty()) {
        text = ""
        return
    }
    val start = SystemClock.uptimeMillis()
    val runnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= durationMs) {
                text = target
                scrambleRunnables.remove(view)
                return
            }
            val resolved = ((elapsed.toFloat() / durationMs) * target.length).toInt()
            val sb = StringBuilder(target.length)
            for (i in target.indices) {
                val ch = target[i]
                if (i < resolved || ch == ' ' || ch == '[' || ch == ']' || ch == '/') {
                    sb.append(ch)
                } else {
                    val hash = ((i * 31 + elapsed.toInt() / 16) and 0x7FFF) % CIPHER_GLYPHS.length
                    sb.append(CIPHER_GLYPHS[hash])
                }
            }
            text = sb.toString()
            view.postDelayed(this, 24L)
        }
    }
    scrambleRunnables[view] = runnable
    post(runnable)
}
