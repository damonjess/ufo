package com.sightings.atlas.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.sightings.atlas.model.eraOf
import kotlin.math.max

/** Five-year bins of reports, tinted by era while inside the selected span. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bins = IntArray(0)
    private var binStart = 1910
    private var binSize = 5
    private var rangeStart = 1910
    private var rangeEnd = 2026

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun update(bins: IntArray, binStart: Int, binSize: Int, rangeStart: Int, rangeEnd: Int) {
        this.bins = bins
        this.binStart = binStart
        this.binSize = binSize
        this.rangeStart = rangeStart
        this.rangeEnd = rangeEnd
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (bins.isEmpty()) return
        val tallest = max(1, bins.max())
        val gap = 2f * density
        val barWidth = (width - gap * (bins.size - 1)) / bins.size
        for (i in bins.indices) {
            val from = binStart + i * binSize
            val to = from + binSize - 1
            val inside = to >= rangeStart && from <= rangeEnd
            val barHeight = max(3f * density, height * (bins[i].toFloat() / tallest))
            val left = i * (barWidth + gap)
            rect.set(left, height - barHeight, left + barWidth, height.toFloat())
            paint.color = if (inside) {
                ColorUtils.setAlphaComponent(eraOf(max(from, binStart)).color, 140)
            } else {
                0x2190BEC8
            }
            canvas.drawRoundRect(rect, 1f * density, 1f * density, paint)
        }
    }
}
