package com.sightings.atlas.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sightings.atlas.model.eraOf
import kotlin.math.abs
import kotlin.math.roundToInt

/** Two-thumb year span slider with an era-coloured track. */
class YearRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var minYear = 1910
        private set
    var maxYear = 2026
        private set
    var start = 1910
        private set
    var end = 2026
        private set

    var onChange: ((Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2994C5D0
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9F6F4.toInt() }
    private val thumbEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF05090B.toInt()
        strokeWidth = 1f * density
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x296EE7B7
        strokeWidth = 3f * density
    }

    private var activeThumb = -1

    init {
        isClickable = true
        minimumHeight = (26f * density).toInt()
    }

    fun setBounds(min: Int, max: Int) {
        minYear = min
        maxYear = max
        start = start.coerceIn(min, max)
        end = end.coerceIn(start, max)
        invalidate()
    }

    fun setRange(startYear: Int, endYear: Int) {
        start = startYear.coerceIn(minYear, maxYear)
        end = endYear.coerceIn(start, maxYear)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize((26f * density).toInt(), heightMeasureSpec))
    }

    private fun xFor(year: Int): Float {
        val pad = 8f * density
        val span = (maxYear - minYear).coerceAtLeast(1)
        return pad + (width - 2 * pad) * ((year - minYear).toFloat() / span)
    }

    private fun yearFor(x: Float): Int {
        val pad = 8f * density
        val span = (maxYear - minYear).coerceAtLeast(1)
        val fraction = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        return minYear + (fraction * span).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        val centerY = height / 2f
        val pad = 8f * density
        canvas.drawLine(pad, centerY, width - pad, centerY, railPaint)

        val sx = xFor(start)
        val ex = xFor(end)
        if (ex > sx) {
            fillPaint.shader = LinearGradient(
                sx, centerY, ex, centerY,
                eraOf(start).color, eraOf(end).color,
                Shader.TileMode.CLAMP,
            )
            canvas.drawLine(sx, centerY, ex, centerY, fillPaint)
        }
        drawThumb(canvas, sx, centerY)
        drawThumb(canvas, ex, centerY)
    }

    private fun drawThumb(canvas: Canvas, x: Float, centerY: Float) {
        val radius = 8f * density
        canvas.drawCircle(x, centerY, radius, thumbPaint)
        canvas.drawCircle(x, centerY, radius, thumbEdgePaint)
        canvas.drawCircle(x, centerY, radius + 1.5f * density, thumbGlowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sx = xFor(start)
                val ex = xFor(end)
                activeThumb = if (abs(event.x - sx) <= abs(event.x - ex)) 0 else 1
                update(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeThumb >= 0) update(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeThumb >= 0) update(event.x)
                activeThumb = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun update(x: Float) {
        val year = yearFor(x)
        when (activeThumb) {
            0 -> start = year.coerceIn(minYear, end - 1)
            1 -> end = year.coerceIn(start + 1, maxYear)
        }
        invalidate()
        onChange?.invoke(start, end)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
