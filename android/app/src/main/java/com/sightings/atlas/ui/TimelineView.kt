package com.sightings.atlas.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.sightings.atlas.model.eraOf
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One slot per year with a report-density bar, a highlighted 3-year window and a
 * playhead the user can drag. Tapping or dragging reports the year through [onScrub].
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var counts = IntArray(0)
    private var minYear = 1910
    private var maxYear = 2026
    private var playYear = 1910
    private var fromYear = 1910
    private var toYear = 1910
    private var active = false

    var onScrub: ((Int) -> Unit)? = null
    var onScrubEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = 0xFF6EE7B7.toInt()
    }
    private val rect = RectF()

    fun setData(min: Int, max: Int, perYear: IntArray) {
        minYear = min
        maxYear = max
        counts = perYear
        playYear = min
        active = false
        invalidate()
    }

    fun setPlayhead(year: Int, from: Int, to: Int, active: Boolean) {
        playYear = year
        fromYear = from
        toYear = to
        this.active = active
        invalidate()
    }

    fun clear() {
        active = false
        playYear = minYear
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (counts.isEmpty() || width <= 0) return
        val span = (maxYear - minYear).coerceAtLeast(1)
        val slot = width.toFloat() / span
        val tallest = max(1, counts.maxOrNull() ?: 1)
        val baseY = height - 2f * density
        val maxH = height - 8f * density

        if (active) {
            paint.color = 0x1F6EE7B7
            val left = ((fromYear - minYear).toFloat()) * slot
            val right = ((toYear - minYear + 1).toFloat()) * slot
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
        }

        for (i in counts.indices) {
            val count = counts[i]
            if (count == 0) continue
            val barHeight = max(2f * density, maxH * sqrt(count.toFloat() / tallest))
            val left = i * slot
            val year = minYear + i
            val inWindow = active && year in fromYear..toYear
            paint.color = ColorUtils.setAlphaComponent(
                eraOf(year).color,
                if (inWindow) 200 else 55,
            )
            rect.set(left + 0.4f, baseY - barHeight, left + slot - 0.8f, baseY)
            canvas.drawRect(rect, paint)
        }

        paint.color = 0x3394C5D0
        canvas.drawRect(0f, baseY, width.toFloat(), baseY + 1f * density, paint)

        val x = ((playYear - minYear).toFloat() + 0.5f) * slot
        playheadPaint.color = if (active) 0xFF6EE7B7.toInt() else 0x6694C5D0.toInt()
        canvas.drawLine(x, 1.5f * density, x, height - 0.5f * density, playheadPaint)
        paint.color = if (active) 0xFFE9F6F4.toInt() else 0xCC94C5D0.toInt()
        canvas.drawCircle(x, 3.5f * density, 2.4f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val span = (maxYear - minYear).coerceAtLeast(1)
                val fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
                val year = minYear + (fraction * span).roundToInt()
                if (year != playYear || event.actionMasked == MotionEvent.ACTION_DOWN) {
                    playYear = year
                    invalidate()
                    onScrub?.invoke(year)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onScrubEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
