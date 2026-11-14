package com.sightings.atlas.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

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

fun Context.pill(fill: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = rounded(fill, stroke, 999f)

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
