package com.sightings.atlas.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.sightings.atlas.data.GeoLabel
import com.sightings.atlas.data.WorldData
import com.sightings.atlas.model.Era
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.eraOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orthographic globe drawn natively: pre-rotated unit vectors, bounding-cap culling,
 * batched glow markers, country/city labels and Cesium-style zoom easing, fling and
 * double-tap.
 */
class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Target(
        val startLam: Double,
        val startPhi: Double,
        val targetLam: Double,
        val targetPhi: Double,
        val start: Long,
    )

    /** Land/border/graticule ring stored as unit vectors, with a bounding cap for culling. */
    private class Ring(
        val pts: FloatArray,
        val closed: Boolean,
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val cosR: Float,
        val sinR: Float,
    )

    private class Label(
        val text: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val weight: Int,
        val countryWidth: Float,
        val cityWidth: Float,
    )

    private class Pin(val sighting: Sighting, val x: Float, val y: Float, val z: Float, val era: Int) {
        val year = sighting.year
        val lat = sighting.lat
        val lon = sighting.lon
        val hasVideo = sighting.video != null
    }

    /** Screen-space group of pins shown as one bubble while zoomed out. */
    private class Cluster(
        var sx: Double = 0.0,
        var sy: Double = 0.0,
        var count: Int = 0,
        var lat: Double = 0.0,
        var lon: Double = 0.0,
    ) {
        var x = 0f
        var y = 0f
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val eraValues = Era.entries.toTypedArray()

    private var landLow: List<Ring> = emptyList()
    private var landHigh: List<Ring> = emptyList()
    private var bordersLow: List<Ring> = emptyList()
    private var bordersHigh: List<Ring> = emptyList()
    private var graticule: List<Ring> = emptyList()
    private var countryLabels: List<Label> = emptyList()
    private var cityLabels: List<Label> = emptyList()

    private var pins: List<Pin> = emptyList()
    private var pinCoords: Array<FloatArray> = Array(4) { FloatArray(0) }
    private var pinCounts = IntArray(4)
    private var videoCoords = FloatArray(0)
    private var videoCount = 0

    private val pinGen = java.util.concurrent.atomic.AtomicInteger(0)

    private val clusterPool = Array(1000) { Cluster() }
    private var clusterCount = 0
    private var clusterGrid = IntArray(1024)
    private var heatCounts = IntArray(1024)
    private var clusterDotCoords = FloatArray(2048)

    private val labelRectPool = Array(64) { RectF() }
    private var occupiedCount = 0

    private var selectedSighting: Sighting? = null
    private var selectionLockStart = 0L

    // Radar lock state, synced from MainActivity's area search controls.
    private var radarActive = false
    private var radarLat = Double.NaN
    private var radarLon = Double.NaN
    private var radarRadiusKm = 200.0
    private val radarArcRect = RectF()

    /** Inclusive year window used by timeline playback; full range by default. */
    private var yearFrom = 0
    private var yearTo = 9999

    /** Draw a density grid instead of pins. */
    var heatMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            dirty = true
            ensureFrames()
            invalidate()
        }

    var selected: Sighting?
        get() = selectedSighting
        set(value) {
            if (selectedSighting?.id != value?.id && value != null) {
                selectionLockStart = SystemClock.uptimeMillis()
                CyberSfx.targetLock()
            }
            selectedSighting = value
            if (value != null && value.hasPosition) {
                target = Target(lam, phi, -value.lon, (-value.lat).coerceIn(-72.0, 72.0), SystemClock.uptimeMillis())
                if (zoomTarget < 1.8) zoomTarget = 1.8
            }
            dirty = true
            ensureFrames()
            invalidate()
        }

    var autoRotate: Boolean = true
        set(value) {
            field = value
            if (value) ensureFrames()
            dirty = true
            invalidate()
        }

    var onSelect: ((Sighting?) -> Unit)? = null
    var onUserRotate: (() -> Unit)? = null
    /** Called with centre latitude, centre longitude and zoom. */
    var onViewChanged: ((Double, Double, Double) -> Unit)? = null
    /** Called when dragging/flinging stops. */
    var onInteractionEnd: (() -> Unit)? = null
    /** True while the street-tile layer is showing. */
    var onStreetModeChanged: ((Boolean) -> Unit)? = null

    val isInteracting: Boolean get() = fastMoving

    private val fastMoving: Boolean
        get() = dragging || flinging || target != null || abs(zoomTarget - zoom) > 1e-4

    /** Only animate the HUD while the lock sequence is playing or radar is sweeping. */
    private val hudAnimating: Boolean
        get() = isShown && (
            radarActive || (
                selectedSighting != null &&
                    SystemClock.uptimeMillis() - selectionLockStart < LOCK_ANIMATION_MS
                )
            )

    private var lam = DEFAULT_LAM
    private var phi = DEFAULT_PHI
    private var zoom = 1.0
    private var zoomTarget = 1.0
    private var target: Target? = null
    private var lamVelocity = 0.0
    private var phiVelocity = 0.0
    private var flinging = false
    private var lastTickStepLon = Int.MIN_VALUE
    private var lastTickStepLat = Int.MIN_VALUE

    private var dragging = false
    private var moved = false
    private var pinchUsed = false
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var pinchStartDist = 1f
    private var pinchStartZoom = 1.0
    private var pinchMidX = 0f
    private var pinchMidY = 0f

    private var anchorGeo: DoubleArray? = null
    private var anchorX = 0f
    private var anchorY = 0f

    private var velocityTracker: VelocityTracker? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingTap: Sighting? = null
    private val tapRunnable = Runnable {
        onSelect?.invoke(pendingTap)
        pendingTap = null
    }

    private var dirty = true
    private var framePosted = false
    private var lastFrame = 0.0
    private var lastFrameNanos = 0L
    private var lastReadout = 0L
    private var background: Bitmap? = null

    // projection state
    private var cx = 0f
    private var cy = 0f
    private var radius = 0.0
    private var centerLon = 0.0
    private var centerLat = 0.0
    private var cosLam = 1.0
    private var sinLam = 0.0
    private var cosPhi = 1.0
    private var sinPhi = 0.0

    private val curVec = DoubleArray(3)

    private val landPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val borderPath = Path()
    private val graticulePath = Path()

    private var stars = FloatArray(0)
    private var glowShader: Shader? = null
    private var oceanShader: Shader? = null
    private var atmoShader: Shader? = null
    private var shaderRadius = -1.0
    private var shaderWidth = 0
    private var shaderHeight = 0
    private val shaderMatrix = Matrix()

    private val bgPaint = Paint().apply { color = 0xFF05090B.toInt() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint().apply { color = 0xFFCFE8EF.toInt() }
    private val atmoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val landFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF133038.toInt()
    }
    private val landStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x7A92D8E0
        strokeWidth = 0.7f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x4D8FDDEA
        strokeWidth = 0.55f * density
    }
    private val graticulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x1FA0D7E6
        strokeWidth = 0.5f * density
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x6696E2F0
        strokeWidth = 1f * density
    }

    // Cyber HUD / radar paints.
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x4800F0FF
    }
    private val hudFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1600FF9C
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 7.5f * scaledDensity
        color = 0x8800F0FF.toInt()
    }

    private val pinHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pinBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pinCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val videoRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFF8E7.toInt()
        strokeWidth = 1.2f * density
    }
    private val videoOuterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xCCFFF8E7.toInt()
    }
    private val videoInnerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val selectionDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cityDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6EE7B7.toInt() }
    private val clusterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x406EE7B7 }
    private val clusterRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = 0x4D9DF3D3
    }
    private val clusterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9DF3D3.toInt() }
    private val clusterBatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.4f * density
        color = 0xFF9DF3D3.toInt()
    }
    private val clusterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 7.5f * scaledDensity
        color = 0xFFD8FFF2.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val heatPaint = Paint().apply { isAntiAlias = false }

    private val countryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 9.5f * scaledDensity
        color = 0xFF9FD7E0.toInt()
    }
    private val countryHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 9.5f * scaledDensity
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        color = 0xD905090B.toInt()
    }
    private val cityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f * scaledDensity
        color = 0xFFE6F1F2.toInt()
    }
    private val cityHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f * scaledDensity
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        color = 0xD905090B.toInt()
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f * scaledDensity
        color = 0xFFE9F6F4.toInt()
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9050D11.toInt() }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x4796E2F0
        strokeWidth = 1f * density
    }

    private val labelBounds = RectF()

    // Street tiles: raster map tiles drawn once the globe is deep enough to read roads.
    private val tileCache = object : LruCache<String, Bitmap>(48) {}
    private val tileLoading = ConcurrentHashMap<String, Boolean>()
    private val tileFailed = ConcurrentHashMap<String, Boolean>()
    private val tileExecutor = Executors.newFixedThreadPool(3)
    private val tileRect = RectF()
    private var streetMode = false
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        contentDescription = context.getString(com.sightings.atlas.R.string.globe_desc)
        graticule = buildGraticule()
    }

    /* ---------------- public API ---------------- */

    fun setWorld(data: WorldData) {
        landLow = buildRings(data.land110)
        landHigh = buildRings(data.land50)
        bordersLow = buildRings(data.borders110)
        bordersHigh = buildRings(data.borders50)
        countryLabels = buildLabels(data.countries, upper = true)
        cityLabels = buildLabels(data.cities, upper = false)
        dirty = true
        invalidate()
    }

    /** Projects the active area search onto the globe as a sweeping radar dome. */
    fun setRadarLock(active: Boolean, lat: Double, lon: Double, radiusKm: Double) {
        if (active && (!radarActive || radarRadiusKm != radiusKm)) {
            CyberSfx.radarPing()
        }
        radarActive = active
        radarLat = lat
        radarLon = lon
        radarRadiusKm = radiusKm
        dirty = true
        if (active) ensureFrames()
        invalidate()
    }

    /** Restricts drawn pins to an inclusive year window (timeline playback). */
    fun zoomTo(value: Double) {
        zoomTarget = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        dirty = true
        ensureFrames()
    }

    fun setYearWindow(from: Int, to: Int) {
        yearFrom = from
        yearTo = to
        dirty = true
        ensureFrames()
        invalidate()
    }

    fun clearYearWindow() {
        yearFrom = 0
        yearTo = 9999
        dirty = true
        ensureFrames()
        invalidate()
    }

    /** Eases the camera to a place, optionally making sure it is at least this deep. */
    fun flyTo(lat: Double, lon: Double, minZoom: Double = 1.8) {
        target = Target(lam, phi, -lon, (-lat).coerceIn(-72.0, 72.0), SystemClock.uptimeMillis())
        if (zoomTarget < minZoom) zoomTarget = minZoom
        dirty = true
        ensureFrames()
    }

    fun setSightings(list: List<Sighting>) {
        val generation = pinGen.incrementAndGet()
        val source = ArrayList(list)
        pinExecutor.execute {
            if (generation != pinGen.get()) return@execute
            val built = ArrayList<Pin>(source.size)
            val counts = IntArray(4)
            var videos = 0
            for (sighting in source) {
                if (!sighting.hasPosition) continue
                val lat = Math.toRadians(sighting.lat)
                val lon = Math.toRadians(sighting.lon)
                val cosLat = cos(lat)
                val x = (cosLat * sin(lon)).toFloat()
                val y = (cosLat * cos(lon)).toFloat()
                val z = sin(lat).toFloat()
                val era = eraOf(sighting.year).ordinal
                built.add(Pin(sighting, x, y, z, era))
                if (sighting.video != null) videos++ else counts[era]++
            }
            if (generation != pinGen.get()) return@execute
            post {
                if (generation != pinGen.get()) return@post
                pins = built
                pinCoords = Array(4) { FloatArray(counts[it] * 2) }
                pinCounts = IntArray(4)
                videoCoords = FloatArray(videos * 2)
                videoCount = 0
                dirty = true
                invalidate()
            }
        }
    }

    fun resetView() {
        target = Target(lam, phi, DEFAULT_LAM, DEFAULT_PHI, SystemClock.uptimeMillis())
        zoomTarget = 1.0
        flinging = false
        lamVelocity = 0.0
        phiVelocity = 0.0
        anchorGeo = null
        dirty = true
        ensureFrames()
    }

    fun zoomBy(factor: Double) {
        CyberSfx.zoomPulse(factor >= 1.0)
        val extra = when {
            zoomTarget > 60.0 -> 1.7
            zoomTarget > 12.0 -> 1.25
            else -> 1.0
        }
        val effective = if (factor >= 1.0) factor * extra else factor / extra
        zoomTarget = (zoomTarget * effective).coerceIn(MIN_ZOOM, MAX_ZOOM)
        anchorGeo = null
        if (zoomTarget > 3.0) onUserRotate?.invoke()
        ensureFrames()
    }

    /* ---------------- geometry preparation ---------------- */

    private fun buildRings(rings: List<FloatArray>, closed: Boolean = true): List<Ring> {
        val out = ArrayList<Ring>(rings.size)
        for (flat in rings) {
            val count = flat.size / 2
            if (count < 3) continue
            val pts = FloatArray(count * 3)
            var sx = 0f
            var sy = 0f
            var sz = 0f
            for (i in 0 until count) {
                val lon = Math.toRadians(flat[i * 2].toDouble())
                val lat = Math.toRadians(flat[i * 2 + 1].toDouble())
                val cosLat = cos(lat)
                val x = (cosLat * sin(lon)).toFloat()
                val y = (cosLat * cos(lon)).toFloat()
                val z = sin(lat).toFloat()
                pts[i * 3] = x
                pts[i * 3 + 1] = y
                pts[i * 3 + 2] = z
                sx += x
                sy += y
                sz += z
            }
            var ux = sx
            var uy = sy
            var uz = sz
            val len = sqrt(ux * ux + uy * uy + uz * uz)
            if (len < 1e-4f) {
                ux = 0f
                uy = 0f
                uz = 1f
            } else {
                ux /= len
                uy /= len
                uz /= len
            }
            var minDot = 1f
            for (i in 0 until count) {
                val dot = pts[i * 3] * ux + pts[i * 3 + 1] * uy + pts[i * 3 + 2] * uz
                if (dot < minDot) minDot = dot
            }
            val cosR = minDot.coerceIn(-1f, 1f)
            val sinR = sqrt(max(0f, 1f - cosR * cosR))
            out.add(Ring(pts, closed, ux, uy, uz, cosR, sinR))
        }
        return out
    }

    private fun buildLabels(labels: List<GeoLabel>, upper: Boolean): List<Label> {
        val out = ArrayList<Label>(labels.size)
        for (label in labels) {
            if (!label.lon.isFinite() || !label.lat.isFinite()) continue
            val lon = Math.toRadians(label.lon)
            val lat = Math.toRadians(label.lat)
            val cosLat = cos(lat)
            val text = if (upper) label.name.uppercase(Locale.US) else label.name
            val countryWidth = countryLabelPaint.measureText(text)
            val cityWidth = cityLabelPaint.measureText(text)
            out.add(
                Label(
                    text = text,
                    x = cosLat * sin(lon),
                    y = cosLat * cos(lon),
                    z = sin(lat),
                    weight = label.weight,
                    countryWidth = countryWidth,
                    cityWidth = cityWidth,
                ),
            )
        }
        out.sortByDescending { it.weight }
        return out
    }

    private fun buildGraticule(): List<Ring> {
        val rings = ArrayList<FloatArray>()
        var lon = -180
        while (lon < 180) {
            val points = ArrayList<Float>(122)
            var lat = -90.0
            while (lat <= 90.0) {
                points.add(lon.toFloat())
                points.add(lat.toFloat())
                lat += 6.0
            }
            rings.add(points.toFloatArray())
            lon += 20
        }
        var lat = -75
        while (lat <= 75) {
            val points = ArrayList<Float>(242)
            var meridian = -180.0
            while (meridian <= 180.0) {
                points.add(meridian.toFloat())
                points.add(lat.toFloat())
                meridian += 6.0
            }
            rings.add(points.toFloatArray())
            lat += 20
        }
        return buildRings(rings, closed = false)
    }

    /* ---------------- drawing ---------------- */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildStars(w, h)
        glowShader = RadialGradient(
            w * 0.5f,
            h * 0.46f,
            max(w, h) * 0.72f,
            intArrayOf(0x80143E48.toInt(), 0x00143E48),
            null,
            Shader.TileMode.CLAMP,
        )
        buildBackgroundBitmap(w, h)
        shaderRadius = -1.0
        dirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, w, h)
        updateProjection()

        val globeRadius = radius.toFloat()
        val centerX = cx
        val centerY = cy
        if (globeRadius <= 0f) return

        updateShaders(centerX, centerY, globeRadius)
        val maxDim = max(w, h)
        if (globeRadius * 1.24f < maxDim * 1.5f) {
            atmoShader?.let {
                atmoPaint.shader = it
                canvas.drawCircle(centerX, centerY, globeRadius * 1.24f, atmoPaint)
            }
        }
        oceanShader?.let {
            oceanPaint.shader = it
            canvas.drawCircle(centerX, centerY, globeRadius, oceanPaint)
        }

        val street = zoom >= TILE_MIN_ZOOM
        if (street != streetMode) {
            streetMode = street
            onStreetModeChanged?.invoke(street)
        }

        if (street) {
            drawTiles(canvas)
        } else {
            val detailed = zoom >= 2.2 && !autoRotate && !fastMoving
            val fastDraw = fastMoving
            landFillPaint.isAntiAlias = !fastDraw
            landStrokePaint.isAntiAlias = !fastDraw
            landPath.rewind()
            buildPath(landPath, if (detailed) landHigh else landLow)
            canvas.drawPath(landPath, landFillPaint)
            if (!fastDraw) {
                landStrokePaint.strokeWidth = (if (detailed) 0.75f else 0.7f) * density
                canvas.drawPath(landPath, landStrokePaint)
            }

            if (zoom < 5.0 && !fastDraw) {
                graticulePath.rewind()
                buildPath(graticulePath, graticule)
                canvas.drawPath(graticulePath, graticulePaint)
            }

            if (!autoRotate && !fastMoving) {
                borderPath.rewind()
                buildPath(borderPath, if (detailed) bordersHigh else bordersLow)
                borderPaint.color = if (detailed) 0x668FDDEA else 0x4D8FDDEA
                borderPaint.strokeWidth = (if (detailed) 0.7f else 0.55f) * density
                canvas.drawPath(borderPath, borderPaint)
            }
        }

        canvas.drawCircle(centerX, centerY, globeRadius, rimPaint)

        drawRadarZone(canvas)
        drawMarkers(canvas, w, h)
        if (!fastMoving) drawLabels(canvas, w, h)
        drawSelection(canvas, h)
        drawCyberHud(canvas, w, h, globeRadius)
        dirty = false
        publishReadout()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val cached = background
        if (cached != null) {
            canvas.drawBitmap(cached, 0f, 0f, null)
            return
        }
        canvas.drawColor(bgPaint.color)
        glowShader?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, 0f, w, h, glowPaint)
        }
        starPaint.color = 0xFFCFE8EF.toInt()
        var i = 0
        while (i < stars.size) {
            starPaint.alpha = (stars[i + 3] * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(stars[i], stars[i + 1], stars[i] + stars[i + 2], stars[i + 1] + stars[i + 2], starPaint)
            i += 4
        }
        starPaint.alpha = 255
    }

    private fun buildBackgroundBitmap(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        background?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgPaint.color)
        glowShader?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)
        }
        starPaint.color = 0xFFCFE8EF.toInt()
        var i = 0
        while (i < stars.size) {
            starPaint.alpha = (stars[i + 3] * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(stars[i], stars[i + 1], stars[i] + stars[i + 2], stars[i + 1] + stars[i + 2], starPaint)
            i += 4
        }

        // Subtle CRT horizontal scanlines.
        starPaint.color = 0x0A96E2F0
        starPaint.alpha = 255
        val scanStep = (3f * density).coerceAtLeast(2f)
        var scanY = 0f
        while (scanY < h) {
            canvas.drawLine(0f, scanY, w.toFloat(), scanY, starPaint)
            scanY += scanStep
        }

        // Faint tactical cross-tick grid; baked once, not per frame.
        starPaint.color = 0x1496E2F0
        val gridStep = 56f * density
        val tick = 2.5f * density
        var gridX = gridStep
        while (gridX < w) {
            var gridY = gridStep
            while (gridY < h) {
                canvas.drawLine(gridX - tick, gridY, gridX + tick, gridY, starPaint)
                canvas.drawLine(gridX, gridY - tick, gridX, gridY + tick, starPaint)
                gridY += gridStep
            }
            gridX += gridStep
        }
        starPaint.color = 0xFFCFE8EF.toInt()
        starPaint.alpha = 255
        background = bitmap
    }

    private fun updateShaders(centerX: Float, centerY: Float, globeRadius: Float) {
        if (shaderWidth != width || shaderHeight != height || atmoShader == null) {
            shaderWidth = width
            shaderHeight = height
            shaderRadius = globeRadius.toDouble()
            atmoShader = RadialGradient(
                centerX,
                centerY,
                globeRadius * 1.24f,
                intArrayOf(0x296EE7B7, 0x1288AFCD, 0x0088AFCD),
                floatArrayOf(0.758f, 0.891f, 1f),
                Shader.TileMode.CLAMP,
            )
            oceanShader = RadialGradient(
                centerX - globeRadius * 0.35f,
                centerY - globeRadius * 0.4f,
                globeRadius * 1.04f,
                intArrayOf(0xFF123039.toInt(), 0xFF0B1F27.toInt(), 0xFF05121A.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else if (abs(globeRadius - shaderRadius) > 0.5) {
            val scale = (globeRadius / shaderRadius).toFloat()
            shaderMatrix.setScale(scale, scale, centerX, centerY)
            atmoShader?.setLocalMatrix(shaderMatrix)
            oceanShader?.setLocalMatrix(shaderMatrix)
        }
    }

    private fun buildPath(path: Path, rings: List<Ring>) {
        if (rings.isEmpty()) return
        val cl = cosLam.toFloat()
        val sl = sinLam.toFloat()
        val cp = cosPhi.toFloat()
        val sp = sinPhi.toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        val halfDiagonal = hypot(w / 2.0, h / 2.0)
        val viewAngle = if (radius > 0.0) asin(min(1.0, halfDiagonal / radius)) else PI / 2
        val cosV = cos(viewAngle).toFloat()
        val sinV = sin(viewAngle).toFloat()

        for (ring in rings) {
            if (!ringPasses(ring, cl, sl, cp, sp, cosV, sinV, w, h)) continue
            appendRing(path, ring, cl, sl, cp, sp)
        }
    }

    private fun ringPasses(
        ring: Ring,
        cl: Float,
        sl: Float,
        cp: Float,
        sp: Float,
        cosV: Float,
        sinV: Float,
        w: Float,
        h: Float,
    ): Boolean {
        val bc = ring.cy * cl + ring.cx * sl
        val z = sp * ring.cz + cp * bc
        if (z < ring.cosR * cosV - ring.sinR * sinV) return false

        val capX = ring.cx * cl - ring.cy * sl
        val capY = cp * ring.cz - sp * bc
        val capPx = cx + radius * capX
        val capPy = cy - radius * capY
        val margin = (radius * ring.sinR * 1.08 + 24.0)
        if (capPx + margin < 0 || capPx - margin > w || capPy + margin < 0 || capPy - margin > h) {
            return false
        }
        return true
    }

    private fun appendRing(path: Path, ring: Ring, cl: Float, sl: Float, cp: Float, sp: Float) {
        val pts = ring.pts
        val count = pts.size / 3
        if (count < 2) return

        val radF = radius.toFloat()
        var a = pts[(count - 1) * 3]
        var b = pts[(count - 1) * 3 + 1]
        var c = pts[(count - 1) * 3 + 2]
        var prevX = a * cl - b * sl
        var bc = b * cl + a * sl
        var prevY = cp * c - sp * bc
        var prevZ = sp * c + cp * bc
        var prevVisible = prevZ >= 0f
        var penDown = false

        for (i in 0 until count) {
            a = pts[i * 3]
            b = pts[i * 3 + 1]
            c = pts[i * 3 + 2]
            val curX = a * cl - b * sl
            bc = b * cl + a * sl
            val curY = cp * c - sp * bc
            val curZ = sp * c + cp * bc
            val visible = curZ >= 0f

            if (visible) {
                if (!prevVisible) {
                    val denom = prevZ - curZ
                    val t = if (denom != 0f) prevZ / denom else 0.5f
                    var hzX = prevX + (curX - prevX) * t
                    var hzY = prevY + (curY - prevY) * t
                    val n = sqrt(hzX * hzX + hzY * hzY)
                    if (n > 1e-6f) {
                        hzX /= n
                        hzY /= n
                    }
                    path.moveTo(cx + radF * hzX, cy - radF * hzY)
                    penDown = true
                }
                val sx = cx + radF * curX
                val sy = cy - radF * curY
                if (penDown) {
                    path.lineTo(sx, sy)
                } else {
                    path.moveTo(sx, sy)
                    penDown = true
                }
            } else if (prevVisible && penDown) {
                val denom = prevZ - curZ
                val t = if (denom != 0f) prevZ / denom else 0.5f
                var hzX = prevX + (curX - prevX) * t
                var hzY = prevY + (curY - prevY) * t
                val n = sqrt(hzX * hzX + hzY * hzY)
                if (n > 1e-6f) {
                    hzX /= n
                    hzY /= n
                }
                path.lineTo(cx + radF * hzX, cy - radF * hzY)
                penDown = false
            }

            prevX = curX
            prevY = curY
            prevZ = curZ
            prevVisible = visible
        }
        if (penDown && ring.closed) path.close()
    }

    private fun drawMarkers(canvas: Canvas, w: Float, h: Float) {
        pinCounts.fill(0)
        videoCount = 0
        val cl = cosLam.toFloat()
        val sl = sinLam.toFloat()
        val cp = cosPhi.toFloat()
        val sp = sinPhi.toFloat()

        val clusterMode = !heatMode && zoom >= CLUSTER_MIN_ZOOM && zoom < CLUSTER_ZOOM
        val cell = CLUSTER_CELL_DP * density
        val cols = if (clusterMode) max(1, ceil((w + 2 * cell) / cell).toInt()) else 0
        val rows = if (clusterMode) max(1, ceil((h + 2 * cell) / cell).toInt()) else 0
        val totalCells = cols * rows
        if (clusterMode) {
            clusterCount = 0
            if (clusterGrid.size < totalCells) {
                clusterGrid = IntArray(max(totalCells, clusterGrid.size * 2))
            }
            java.util.Arrays.fill(clusterGrid, 0, totalCells, -1)
        }

        val heatCell = HEAT_CELL_DP * density
        val heatCols = if (heatMode) max(1, ceil(w / heatCell).toInt()) else 0
        val heatRows = if (heatMode) max(1, ceil(h / heatCell).toInt()) else 0
        val totalHeatCells = heatCols * heatRows
        if (heatMode) {
            if (heatCounts.size < totalHeatCells) {
                heatCounts = IntArray(max(totalHeatCells, heatCounts.size * 2))
            }
            java.util.Arrays.fill(heatCounts, 0, totalHeatCells, 0)
        }
        var heatMax = 0

        for (pin in pins) {
            if (pin.year < yearFrom || pin.year > yearTo) continue
            val a = pin.x
            val b = pin.y
            val c = pin.z
            val x = a * cl - b * sl
            val bc = b * cl + a * sl
            val z = sp * c + cp * bc
            if (z < 0.015f) continue
            val y = cp * c - sp * bc
            val px = cx + radius * x
            val py = cy - radius * y
            if (px < -cell || px > w + cell || py < -cell || py > h + cell) continue

            if (heatMode) {
                val gx = (px / heatCell).toInt().coerceIn(0, heatCols - 1)
                val gy = (py / heatCell).toInt().coerceIn(0, heatRows - 1)
                val index = gy * heatCols + gx
                val n = heatCounts[index] + 1
                heatCounts[index] = n
                if (n > heatMax) heatMax = n
            }

            if (pin.hasVideo) {
                if (videoCount * 2 + 1 < videoCoords.size) {
                    videoCoords[videoCount * 2] = px.toFloat()
                    videoCoords[videoCount * 2 + 1] = py.toFloat()
                    videoCount++
                }
                continue
            }

            if (clusterMode) {
                val gx = ((px + cell) / cell).toInt().coerceIn(0, cols - 1)
                val gy = ((py + cell) / cell).toInt().coerceIn(0, rows - 1)
                val gridIndex = gy * cols + gx
                val known = clusterGrid[gridIndex]
                if (known == -1) {
                    if (clusterCount < clusterPool.size) {
                        val cluster = clusterPool[clusterCount]
                        cluster.sx = px.toDouble()
                        cluster.sy = py.toDouble()
                        cluster.count = 1
                        cluster.lat = pin.lat
                        cluster.lon = pin.lon
                        cluster.x = px.toFloat()
                        cluster.y = py.toFloat()
                        clusterGrid[gridIndex] = clusterCount
                        clusterCount++
                    }
                } else {
                    val cluster = clusterPool[known]
                    cluster.count++
                    cluster.sx += px.toDouble()
                    cluster.sy += py.toDouble()
                }
                continue
            }

            val index = pinCounts[pin.era]
            val coords = pinCoords[pin.era]
            if (index * 2 + 1 < coords.size) {
                coords[index * 2] = px.toFloat()
                coords[index * 2 + 1] = py.toFloat()
                pinCounts[pin.era] = index + 1
            }
        }

        if (heatMode) {
            drawHeat(canvas, heatCounts, heatCols, heatRows, heatCell, heatMax)
            return
        }
        if (clusterMode) {
            drawClusters(canvas)
        }

        val zoomFactor = (1.0 + log2(zoom.coerceAtLeast(1.0)) * 0.08).coerceIn(1.0, 1.5).toFloat()
        val lowZoom = zoom < 2.2
        val fastDraw = fastMoving
        val core = 2.1f * density * zoomFactor
        val body = 6.5f * density * zoomFactor
        val halo = (if (lowZoom) 3f else 5f) * density * zoomFactor
        pinHaloPaint.strokeWidth = halo
        pinCorePaint.strokeWidth = core

        if (!clusterMode) {
            for (era in 0 until 4) {
                val count = pinCounts[era]
                if (count == 0) continue
                val color = eraValues[era].color
                pinHaloPaint.color = ColorUtils.setAlphaComponent(color, if (lowZoom) 26 else 40)
                pinCorePaint.color = color
                val coords = pinCoords[era]
                if (!fastDraw) canvas.drawPoints(coords, 0, count * 2, pinHaloPaint)
                canvas.drawPoints(coords, 0, count * 2, pinCorePaint)
            }
        }

        if (videoCount > VIDEO_BATCH_THRESHOLD) {
            videoOuterDotPaint.strokeWidth = body * 1.36f
            videoInnerDotPaint.strokeWidth = core * 1.7f
            canvas.drawPoints(videoCoords, 0, videoCount * 2, videoOuterDotPaint)
            canvas.drawPoints(videoCoords, 0, videoCount * 2, videoInnerDotPaint)
        } else {
            for (i in 0 until videoCount) {
                val vx = videoCoords[i * 2]
                val vy = videoCoords[i * 2 + 1]
                canvas.drawCircle(vx, vy, body * 0.68f, videoRingPaint)
                canvas.drawCircle(vx, vy, core * 0.85f, selectionDotPaint)
            }
        }
    }

    private fun drawClusters(canvas: Canvas) {
        clusterFillPaint.color = 0x406EE7B7
        clusterRingPaint.color = 0x4D9DF3D3
        for (i in 0 until clusterCount) {
            val cluster = clusterPool[i]
            val count = cluster.count
            if (count > 0) {
                cluster.x = (cluster.sx / count).toFloat()
                cluster.y = (cluster.sy / count).toFloat()
            }
        }
        if (fastMoving) {
            if (clusterCount * 2 > clusterDotCoords.size) {
                clusterDotCoords = FloatArray(max(clusterCount * 2, clusterDotCoords.size * 2))
            }
            for (i in 0 until clusterCount) {
                val cluster = clusterPool[i]
                clusterDotCoords[i * 2] = cluster.x
                clusterDotCoords[i * 2 + 1] = cluster.y
            }
            if (clusterCount > 0) {
                canvas.drawPoints(clusterDotCoords, 0, clusterCount * 2, clusterBatchPaint)
            }
            return
        }
        val dotsOnly = zoom < 2.0
        for (i in 0 until clusterCount) {
            val cluster = clusterPool[i]
            if (dotsOnly) {
                val dot = (1.8f + min(2.6f, sqrt(cluster.count.toFloat()) * 0.18f)) * density
                canvas.drawCircle(cluster.x, cluster.y, dot, clusterDotPaint)
                continue
            }
            if (cluster.count < 6) {
                val dot = if (cluster.count == 1) 2.1f else 2.8f
                canvas.drawCircle(cluster.x, cluster.y, dot * density, clusterDotPaint)
                continue
            }
            val radius = (4.4f + min(4.2f, sqrt(cluster.count.toFloat()) * 0.8f)) * density
            canvas.drawCircle(cluster.x, cluster.y, radius, clusterFillPaint)
            canvas.drawCircle(cluster.x, cluster.y, radius, clusterRingPaint)
            if (cluster.count >= 10 && !fastMoving) {
                val metrics = clusterTextPaint.fontMetrics
                val baseline = cluster.y - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(clusterLabel(cluster.count), cluster.x, baseline, clusterTextPaint)
            }
        }
    }

    private fun drawHeat(
        canvas: Canvas,
        counts: IntArray,
        cols: Int,
        rows: Int,
        cell: Float,
        peak: Int,
    ) {
        val denom = ln(1.0 + peak.coerceAtLeast(1))
        for (i in 0 until cols * rows) {
            val count = counts[i]
            if (count == 0) continue
            val level = (ln(1.0 + count) / denom).toFloat()
            val alpha = (26 + 152 * level).toInt().coerceIn(0, 205)
            heatPaint.color = (alpha shl 24) or 0x006EE7B7
            val gx = i % cols
            val gy = i / cols
            canvas.drawRect(gx * cell, gy * cell, (gx + 1) * cell, (gy + 1) * cell, heatPaint)
        }
    }

    private fun clusterLabel(count: Int): String = when {
        count >= 10000 -> "10k+"
        count >= 1000 -> "${count / 1000}.${(count % 1000) / 100}k"
        else -> count.toString()
    }

    private fun nearestCluster(x: Float, y: Float, maxDistance: Float): Cluster? {
        var best: Cluster? = null
        var bestDistance = maxDistance * maxDistance
        for (i in 0 until clusterCount) {
            val cluster = clusterPool[i]
            val dx = cluster.x - x
            val dy = cluster.y - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = cluster
            }
        }
        return best
    }

    private fun drawLabels(canvas: Canvas, w: Float, h: Float) {
        occupiedCount = 0
        // Keep labels out from under the overlay controls.
        addOccupied(0f, 0f, 248f * density, 78f * density)
        addOccupied(w - 216f * density, 0f, w, 94f * density)
        addOccupied(0f, h - 56f * density, w, h)
        addOccupied(w - 64f * density, h - 128f * density, w, h)
        val minCountryArea = when {
            zoom < 1.4 -> 220
            zoom < 2.2 -> 80
            zoom < 4 -> 28
            zoom < 8 -> 8
            else -> 1
        }
        var countriesDrawn = 0
        if (!streetMode) {
            for (label in countryLabels) {
                if (label.weight < minCountryArea) break
                if (countriesDrawn >= 26) break
                if (drawLabel(canvas, label, countryLabelPaint, countryHaloPaint, w, h, isCity = false)) countriesDrawn++
            }
        }

        if (zoom < 2.4) return
        val minCityWeight = when {
            zoom < 3.4 -> 20
            zoom < 5.5 -> 6
            zoom < 9 -> 3
            else -> 2
        }
        var citiesDrawn = 0
        for (label in cityLabels) {
            if (label.weight < minCityWeight) break
            if (citiesDrawn >= 24) break
            if (drawLabel(canvas, label, cityLabelPaint, cityHaloPaint, w, h, isCity = true)) citiesDrawn++
        }
    }

    private fun addOccupied(left: Float, top: Float, right: Float, bottom: Float) {
        if (occupiedCount < labelRectPool.size) {
            labelRectPool[occupiedCount++].set(left, top, right, bottom)
        }
    }

    private fun drawLabel(
        canvas: Canvas,
        label: Label,
        paint: Paint,
        haloPaint: Paint,
        w: Float,
        h: Float,
        isCity: Boolean,
    ): Boolean {
        vecFromUnit(label.x, label.y, label.z, curVec)
        if (curVec[2] < 0.03) return false
        val px = (cx + radius * curVec[0]).toFloat()
        val py = (cy - radius * curVec[1]).toFloat()
        val textWidth = if (isCity) label.cityWidth else label.countryWidth
        val padX = (if (isCity) 3f else 4f) * density
        val rectWidth = textWidth + padX * 2
        val rectHeight = paint.textSize + 4f * density
        if (px < -rectWidth || px > w + rectWidth || py < -rectHeight || py > h + rectHeight) return false

        val top = if (isCity) py - 7f * density - rectHeight else py - rectHeight / 2f
        labelBounds.set(px - rectWidth / 2f, top, px + rectWidth / 2f, top + rectHeight)
        for (i in 0 until occupiedCount) {
            if (RectF.intersects(labelRectPool[i], labelBounds)) return false
        }

        val metrics = paint.fontMetrics
        val baseline = labelBounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label.text, px - textWidth / 2f, baseline, haloPaint)
        canvas.drawText(label.text, px - textWidth / 2f, baseline, paint)
        if (isCity) canvas.drawCircle(px, py, 1.6f * density, cityDotPaint)
        addOccupied(labelBounds.left, labelBounds.top, labelBounds.right, labelBounds.bottom)
        return true
    }

    /** Satellite lock-on brackets, sonar shockwave and decrypted target callout. */
    private fun drawSelection(canvas: Canvas, h: Float) {
        val sighting = selectedSighting ?: return
        if (!sighting.hasPosition) return
        vec(Math.toRadians(sighting.lon), Math.toRadians(sighting.lat), curVec)
        if (curVec[2] < 0.015) return
        val px = (cx + radius * curVec[0]).toFloat()
        val py = (cy - radius * curVec[1]).toFloat()

        val now = SystemClock.uptimeMillis()
        val elapsed = (now - selectionLockStart).coerceAtLeast(0L)
        val lockProgress = (elapsed / 320f).coerceIn(0f, 1f)
        val eased = 1f - (1f - lockProgress).pow(3f)
        val accent = if (sighting.video != null) 0xFFFFC857.toInt() else 0xFF6EE7B7.toInt()
        val pulse = 1f + sin(now / 200.0).toFloat() * 0.10f
        val boxScale = (2.6f - 1.6f * eased) * pulse
        val rotDeg = (1f - eased) * 45f

        selectionRingPaint.color = accent
        selectionRingPaint.strokeWidth = 1.4f * density
        val box = 11f * density * boxScale
        val leg = 4.5f * density

        if (elapsed < 650L) {
            val waveT = (elapsed / 650f).coerceIn(0f, 1f)
            val waveAlpha = ((1f - waveT) * 190f).toInt().coerceIn(0, 190)
            selectionRingPaint.alpha = waveAlpha
            canvas.drawCircle(px, py, (6f + 30f * waveT) * density, selectionRingPaint)
            selectionRingPaint.alpha = 255
        }

        canvas.save()
        canvas.rotate(rotDeg, px, py)
        canvas.drawLine(px - box, py - box, px - box + leg, py - box, selectionRingPaint)
        canvas.drawLine(px - box, py - box, px - box, py - box + leg, selectionRingPaint)
        canvas.drawLine(px + box, py - box, px + box - leg, py - box, selectionRingPaint)
        canvas.drawLine(px + box, py - box, px + box, py - box + leg, selectionRingPaint)
        canvas.drawLine(px - box, py + box, px - box + leg, py + box, selectionRingPaint)
        canvas.drawLine(px - box, py + box, px - box, py + box - leg, selectionRingPaint)
        canvas.drawLine(px + box, py + box, px + box - leg, py + box, selectionRingPaint)
        canvas.drawLine(px + box, py + box, px + box, py + box - leg, selectionRingPaint)
        canvas.restore()

        canvas.drawCircle(px, py, 2.8f * density, selectionDotPaint)

        if (fastMoving) return

        val fullLabel = "TGT#${sighting.id} // ${sighting.placeLabel().uppercase(Locale.US)}"
        val charCount = ((elapsed / 360f).coerceIn(0.15f, 1f) * fullLabel.length).toInt()
        val shownLabel = fullLabel.substring(0, charCount.coerceIn(1, fullLabel.length))

        val textWidth = labelTextPaint.measureText(fullLabel)
        val padX = 8f * density
        val chipHeight = 21f * density
        val left = (px - textWidth / 2f - padX).coerceIn(
            6f * density,
            max(6f * density, width - textWidth - padX * 2f - 6f * density),
        )
        val top = (py - 34f * density).coerceIn(8f * density, max(8f * density, h - chipHeight - 8f * density))
        labelBounds.set(left, top, left + textWidth + padX * 2f, top + chipHeight)

        canvas.drawLine(px, py - box, labelBounds.centerX(), labelBounds.bottom, selectionRingPaint)
        labelTextPaint.color = accent
        labelStrokePaint.color = accent
        canvas.drawRoundRect(labelBounds, 2f * density, 2f * density, labelBgPaint)
        canvas.drawRoundRect(labelBounds, 2f * density, 2f * density, labelStrokePaint)

        val metrics = labelTextPaint.fontMetrics
        val baseline = labelBounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(shownLabel, left + padX, baseline, labelTextPaint)
    }

    /** Sweeping phosphor radar dome projected onto the globe. */
    private fun drawRadarZone(canvas: Canvas) {
        if (!radarActive || !radarLat.isFinite() || !radarLon.isFinite()) return
        vec(Math.toRadians(radarLon), Math.toRadians(radarLat), curVec)
        if (curVec[2] < 0.02) return

        val px = (cx + radius * curVec[0]).toFloat()
        val py = (cy - radius * curVec[1]).toFloat()
        val rPx = ((radarRadiusKm / 6371.0) * radius).toFloat()
            .coerceIn(18f * density, max(width, height).toFloat())

        val now = SystemClock.uptimeMillis()
        val sweepAngle = ((now * 0.16) % 360.0).toFloat()
        val sonarPhase = (now % 1800L) / 1800f

        hudFillPaint.color = 0x1400FF9C
        canvas.drawCircle(px, py, rPx, hudFillPaint)

        radarArcRect.set(px - rPx, py - rPx, px + rPx, py + rPx)
        hudFillPaint.color = 0x2E00FF9C
        canvas.drawArc(radarArcRect, sweepAngle - 55f, 55f, true, hudFillPaint)

        hudPaint.strokeWidth = 1.2f * density
        hudPaint.color = 0xAA00FF9C.toInt()
        canvas.drawCircle(px, py, rPx, hudPaint)

        hudPaint.strokeWidth = 0.8f * density
        hudPaint.color = 0x4400FF9C
        canvas.drawCircle(px, py, rPx * 0.66f, hudPaint)
        canvas.drawCircle(px, py, rPx * 0.33f, hudPaint)

        val rippleAlpha = ((1f - sonarPhase) * 150f).toInt().coerceIn(0, 150)
        hudPaint.color = (rippleAlpha shl 24) or 0x0000FF9C
        canvas.drawCircle(px, py, rPx * sonarPhase, hudPaint)

        val rad = Math.toRadians(sweepAngle.toDouble())
        val lx = px + (cos(rad) * rPx).toFloat()
        val ly = py + (sin(rad) * rPx).toFloat()
        hudPaint.strokeWidth = 1.5f * density
        hudPaint.color = 0xEE00FF9C.toInt()
        canvas.drawLine(px, py, lx, ly, hudPaint)
    }

    /** Orbital degree bezel, counter-rotating reticle and tactical corner brackets. */
    private fun drawCyberHud(canvas: Canvas, w: Float, h: Float, globeRadius: Float) {
        val r = 15f * density
        val gap = 4.5f * density
        val accentColor = if (fastMoving || radarActive) 0x9900FF9C.toInt() else 0x5500F0FF

        hudPaint.strokeWidth = 1f * density
        hudPaint.color = accentColor
        canvas.drawLine(cx - r, cy, cx - gap, cy, hudPaint)
        canvas.drawLine(cx + gap, cy, cx + r, cy, hudPaint)
        canvas.drawLine(cx, cy - r, cx, cy - gap, hudPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + r, hudPaint)
        canvas.drawCircle(cx, cy, r * 0.62f, hudPaint)

        val rotRad = Math.toRadians(lam * 1.5)
        val innerR = r * 0.72f
        val outerR = r * 1.05f
        for (i in 0 until 4) {
            val a = rotRad + i * (PI / 2.0) + PI / 4.0
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(
                cx + ca * innerR,
                cy + sa * innerR,
                cx + ca * outerR,
                cy + sa * outerR,
                hudPaint,
            )
        }

        if (!streetMode && zoom < 2.6 && globeRadius + 14f * density < min(w, h) * 0.52f) {
            val bezelR = globeRadius + 6f * density
            val tickShort = 3.5f * density
            val tickLong = 7f * density
            hudPaint.strokeWidth = 1f * density
            hudPaint.color = 0x3800F0FF
            val lonPhase = lam % 15.0
            var deg = 0.0
            while (deg < 360.0) {
                val angle = Math.toRadians(deg + lonPhase)
                val ca = cos(angle).toFloat()
                val sa = sin(angle).toFloat()
                val len = if ((deg.roundToInt() % 45) == 0) tickLong else tickShort
                canvas.drawLine(
                    cx + ca * bezelR,
                    cy + sa * bezelR,
                    cx + ca * (bezelR + len),
                    cy + sa * (bezelR + len),
                    hudPaint,
                )
                deg += 15.0
            }
        }

        val m = 10f * density
        val topM = 82f * density
        val botM = 60f * density
        val len = 14f * density
        hudPaint.strokeWidth = 1.2f * density
        hudPaint.color = 0x4400F0FF
        canvas.drawLine(m, topM, m + len, topM, hudPaint)
        canvas.drawLine(m, topM, m, topM + len, hudPaint)
        canvas.drawLine(w - m, topM, w - m - len, topM, hudPaint)
        canvas.drawLine(w - m, topM, w - m, topM + len, hudPaint)
        canvas.drawLine(m, h - botM, m + len, h - botM, hudPaint)
        canvas.drawLine(m, h - botM, m, h - botM - len, hudPaint)
        canvas.drawLine(w - m, h - botM, w - m - len, h - botM, hudPaint)
        canvas.drawLine(w - m, h - botM, w - m, h - botM - len, hudPaint)

        if (!fastMoving) {
            val modeStr = when {
                radarActive -> "SYS:RADAR_LOCK // ${radarRadiusKm.toInt()}KM"
                selectedSighting != null -> "SYS:TGT_ACQUIRED // ID#${selectedSighting?.id}"
                autoRotate -> "SYS:ORBIT_SCAN // AUTO"
                else -> "SYS:MANUAL_VEC // READY"
            }
            canvas.drawText(modeStr, m + 5f * density, h - botM - 5f * density, hudTextPaint)
        }
    }

    /* ---------------- street tiles ---------------- */

    private fun drawTiles(canvas: Canvas) {
        val lat = (-phi).coerceIn(-85.0, 85.0)
        val scale = radius * 2.0 * PI * cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        if (scale <= 0.0) return
        val z = (ln(scale / 300.0) / ln(2.0)).roundToInt().coerceIn(2, 19)
        val n = 1 shl z
        val tilePx = scale / n
        if (tilePx < 48.0) return

        val centerX = mercX(wrap180(-lam))
        val centerY = mercY(lat)
        val halfW = width / 2.0
        val halfH = height / 2.0
        val minTileX = floor((centerX - halfW / scale) * n).toInt()
        val maxTileX = floor((centerX + halfW / scale) * n).toInt()
        val minTileY = floor((centerY - halfH / scale) * n).toInt().coerceAtLeast(0)
        val maxTileY = floor((centerY + halfH / scale) * n).toInt().coerceAtMost(n - 1)

        for (ty in minTileY..maxTileY) {
            for (tx in minTileX..maxTileX) {
                val wrapX = ((tx % n) + n) % n
                val key = "$z/$wrapX/$ty"
                val left = (cx + (tx - centerX * n) * tilePx).toFloat()
                val top = (cy + (ty - centerY * n) * tilePx).toFloat()
                val bitmap = tileCache.get(key)
                if (bitmap != null) {
                    tileRect.set(left, top, left + tilePx.toFloat(), top + tilePx.toFloat())
                    canvas.drawBitmap(bitmap, null, tileRect, tilePaint)
                } else {
                    requestTile(key, z, wrapX, ty)
                }
            }
        }
    }

    private fun requestTile(key: String, z: Int, x: Int, y: Int) {
        if (tileFailed.containsKey(key) || tileLoading.putIfAbsent(key, true) != null) return
        tileExecutor.execute {
            val bitmap = loadTile(z, x, y)
            if (bitmap != null) {
                tileCache.put(key, bitmap)
                postInvalidateOnAnimation()
            } else {
                tileFailed[key] = true
            }
            tileLoading.remove(key)
        }
    }

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val dir = File(context.cacheDir, "tiles_de")
        val file = File(dir, "${z}_${x}_${y}_s.png")
        if (file.exists()) {
            val cached = BitmapFactory.decodeFile(file.absolutePath)
            if (cached != null) return cached
        }
        return try {
            val connection = URL("https://tile.openstreetmap.de/$z/$x/$y.png").openConnection() as HttpURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty(
                "User-Agent",
                "SightingsAtlas/1.0 (Android; personal UFO atlas app)",
            )
            connection.doInput = true
            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val styled = stylizeTile(bitmap)
            bitmap.recycle()
            try {
                dir.mkdirs()
                file.outputStream().use { out ->
                    styled.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Exception) {
            }
            styled
        } catch (_: Exception) {
            null
        }
    }

    private fun stylizeTile(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val v = when {
                lum < 90 -> 240 - (lum * 0.16).toInt()
                lum < 205 -> 36
                lum < 244 -> 26
                lum < 252 -> 26 + (lum - 244) * 24
                else -> 214
            }
            px[i] = (0xFF shl 24) or ((v * 0.52).toInt() shl 16) or (v shl 8) or (v * 0.93).toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

    private fun mercY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val s = sin(Math.toRadians(clamped))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private var lastPublishedLat = Double.NaN
    private var lastPublishedLon = Double.NaN
    private var lastPublishedZoom = Double.NaN

    private fun publishReadout() {
        if (fastMoving) return
        val now = SystemClock.uptimeMillis()
        val interval = if (autoRotate) 500L else 150L
        if (now - lastReadout < interval) return
        lastReadout = now
        val currentLat = -phi
        val currentLon = wrap180(-lam)
        val currentZoom = zoom

        if (lastPublishedLat.isNaN() ||
            abs(currentLat - lastPublishedLat) > 0.05 ||
            abs(currentLon - lastPublishedLon) > 0.05 ||
            abs(currentZoom - lastPublishedZoom) > 0.01
        ) {
            lastPublishedLat = currentLat
            lastPublishedLon = currentLon
            lastPublishedZoom = currentZoom
            onViewChanged?.invoke(currentLat, currentLon, currentZoom)
        }
    }

    /* ---------------- projection ---------------- */

    private fun updateProjection() {
        cx = width / 2f
        cy = height / 2f
        radius = (min(width, height) / 2.0) * 0.9 * zoom
        centerLon = Math.toRadians(-lam)
        centerLat = Math.toRadians(-phi)
        cosLam = cos(centerLon)
        sinLam = sin(centerLon)
        cosPhi = cos(centerLat)
        sinPhi = sin(centerLat)
        if (dragging || flinging) {
            val stepLon = (lam / 8.0).roundToInt()
            val stepLat = (phi / 8.0).roundToInt()
            if (stepLon != lastTickStepLon || stepLat != lastTickStepLat) {
                lastTickStepLon = stepLon
                lastTickStepLat = stepLat
                CyberSfx.rotaryTick(zoom)
            }
        }
    }

    private fun vec(lonRad: Double, latRad: Double, out: DoubleArray) {
        val cosLat = cos(latRad)
        vecFromUnit(cosLat * sin(lonRad), cosLat * cos(lonRad), sin(latRad), out)
    }

    private fun vecFromUnit(a: Double, b: Double, c: Double, out: DoubleArray) {
        val x = a * cosLam - b * sinLam
        val bc = b * cosLam + a * sinLam
        out[0] = x
        out[1] = cosPhi * c - sinPhi * bc
        out[2] = sinPhi * c + cosPhi * bc
    }

    private fun inverse(sx: Float, sy: Float): DoubleArray? {
        if (radius <= 0.0) return null
        val x = (sx - cx) / radius
        val y = -(sy - cy) / radius
        val rho = sqrt(x * x + y * y)
        if (rho > 1.0) return null
        val c = asin(rho.coerceIn(-1.0, 1.0))
        val sinC = sin(c)
        val cosC = cos(c)
        val lat = if (rho < 1e-9) {
            centerLat
        } else {
            asin((cosC * sin(centerLat) + y * sinC * cos(centerLat) / rho).coerceIn(-1.0, 1.0))
        }
        val lon = centerLon + atan2(
            x * sinC,
            rho * cosC * cos(centerLat) - y * sinC * sin(centerLat),
        )
        return doubleArrayOf(lon, lat)
    }

    private fun reanchor() {
        val geo = anchorGeo ?: return
        if (radius <= 0.0) return
        repeat(5) {
            vecFromUnit(
                cos(geo[1]) * sin(geo[0]),
                cos(geo[1]) * cos(geo[0]),
                sin(geo[1]),
                curVec,
            )
            if (curVec[2] < 0.05) return
            val px = cx + radius * curVec[0]
            val py = cy - radius * curVec[1]
            val dx = anchorX - px
            val dy = anchorY - py
            if (abs(dx) < 0.5 && abs(dy) < 0.5) return
            val degPerPx = 180.0 / (PI * radius)
            lam += dx * degPerPx
            phi = (phi - dy * degPerPx).coerceIn(-85.0, 85.0)
            updateProjection()
        }
    }

    private fun buildStars(w: Int, h: Int) {
        var seed = 7L
        fun next(): Double {
            seed = (seed * 16807L) % 2147483647L
            return seed / 2147483647.0
        }
        stars = FloatArray(190 * 4)
        for (i in 0 until 190) {
            stars[i * 4] = (next() * w).toFloat()
            stars[i * 4 + 1] = (next() * h).toFloat()
            stars[i * 4 + 2] = (if (next() < 0.86) 0.6f else 1.2f) * density
            stars[i * 4 + 3] = (0.18 + next() * 0.5).toFloat()
        }
    }

    private fun nearest(x: Float, y: Float, maxDistance: Float): Sighting? {
        var best: Sighting? = null
        var bestDistance = maxDistance * maxDistance
        val cl = cosLam.toFloat()
        val sl = sinLam.toFloat()
        val cp = cosPhi.toFloat()
        val sp = sinPhi.toFloat()
        val radF = radius.toFloat()
        val clusterMode = !heatMode && zoom >= CLUSTER_MIN_ZOOM && zoom < CLUSTER_ZOOM
        for (pin in pins) {
            if (pin.year < yearFrom || pin.year > yearTo) continue
            if (clusterMode && !pin.hasVideo) continue
            val a = pin.x
            val b = pin.y
            val c = pin.z
            val bc = b * cl + a * sl
            val z = sp * c + cp * bc
            if (z < 0.015f) continue
            val px = cx + radF * (a * cl - b * sl)
            val py = cy - radF * (cp * c - sp * bc)
            val dx = px - x
            val dy = py - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = pin.sighting
            }
        }
        return best
    }

    /* ---------------- animation ---------------- */

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) {
                framePosted = false
                return
            }
            val now = frameTimeNanos / 1_000_000.0
            var dt = if (lastFrame == 0.0) 16.0 else now - lastFrame
            lastFrame = now
            dt = dt.coerceIn(0.0, 64.0)

            var moving = false
            val animation = target
            if (animation != null) {
                val progress = ((now - animation.start) / 450.0).coerceIn(0.0, 1.0)
                val eased = 1.0 - (1.0 - progress).pow(3.0)
                val deltaLam = shortestDelta(animation.startLam, animation.targetLam)
                val deltaPhi = animation.targetPhi - animation.startPhi
                lam = wrap180(animation.startLam + deltaLam * eased)
                phi = (animation.startPhi + deltaPhi * eased).coerceIn(-85.0, 85.0)
                if (progress >= 1.0) {
                    lam = wrap180(animation.targetLam)
                    phi = animation.targetPhi
                    target = null
                    lastReadout = 0L
                }
                moving = true
            } else if (autoRotate && !dragging) {
                // Keep the apparent ground speed constant as the view zooms in.
                lam += dt * 0.0032 / zoom.coerceAtLeast(1.0)
                moving = true
            } else if (flinging && !dragging) {
                lam += lamVelocity * dt
                phi = (phi + phiVelocity * dt).coerceIn(-85.0, 85.0)
                val damping = exp(-dt / 220.0)
                lamVelocity *= damping
                phiVelocity *= damping
                if (abs(lamVelocity) < 0.002 && abs(phiVelocity) < 0.002) {
                    flinging = false
                    lamVelocity = 0.0
                    phiVelocity = 0.0
                    lastReadout = 0L
                    dirty = true
                    onInteractionEnd?.invoke()
                }
                moving = true
            }

            if (abs(zoomTarget - zoom) > 1e-4) {
                val easing = 1.0 - exp(-dt / 90.0)
                zoom += (zoomTarget - zoom) * easing
                if (abs(zoomTarget - zoom) < 0.0005) {
                    zoom = zoomTarget
                    lastReadout = 0L
                }
                updateProjection()
                reanchor()
                moving = true
            }

            if (moving || dirty || hudAnimating) invalidate()

            if (moving || dragging || hudAnimating) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                framePosted = false
            }
        }
    }

    private fun ensureFrames() {
        if (!isAttachedToWindow || framePosted) return
        framePosted = true
        lastFrame = 0.0
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0.0
        ensureFrames()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) ensureFrames()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        removeCallbacks(tapRunnable)
        framePosted = false
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    /* ---------------- touch ---------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(tapRunnable)
                pendingTap = null
                target = null
                flinging = false
                lamVelocity = 0.0
                phiVelocity = 0.0
                dragging = false
                moved = false
                pinchUsed = false
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                pinchMidX = event.x
                pinchMidY = event.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                onUserRotate?.invoke()
                ensureFrames()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinchStartDist = pointerDistance(event).coerceAtLeast(1f)
                    pinchStartZoom = zoomTarget
                    pinchUsed = true
                    moved = true
                    dragging = true
                    pinchMidX = pointerMidX(event)
                    pinchMidY = pointerMidY(event)
                    anchorX = pinchMidX
                    anchorY = pinchMidY
                    updateProjection()
                    anchorGeo = inverse(pinchMidX, pinchMidY)
                    ensureFrames()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (event.pointerCount >= 2) {
                    dragging = true
                    val midX = pointerMidX(event)
                    val midY = pointerMidY(event)
                    val distance = pointerDistance(event)
                    val degPerPx = (180.0 / (PI * radius.coerceAtLeast(1.0))).toFloat()
                    lam += (midX - pinchMidX) * degPerPx
                    phi = (phi - (midY - pinchMidY) * degPerPx).coerceIn(-85.0, 85.0)
                    pinchMidX = midX
                    pinchMidY = midY
                    anchorX = midX
                    anchorY = midY
                    updateProjection()
                    anchorGeo = inverse(midX, midY)
                    zoomTarget = (pinchStartZoom * (distance / pinchStartDist)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    if (zoomTarget > 3.0) onUserRotate?.invoke()
                    dirty = true
                    ensureFrames()
                    return true
                }

                var stepX = event.x - lastX
                var stepY = event.y - lastY
                if (!moved) {
                    val totalDx = event.x - downX
                    val totalDy = event.y - downY
                    if (abs(totalDx) + abs(totalDy) > touchSlop) {
                        moved = true
                        dragging = true
                        stepX = totalDx
                        stepY = totalDy
                    }
                }
                if (moved) {
                    val degPerPx = (180.0 / (PI * radius.coerceAtLeast(1.0))).toFloat()
                    lam += stepX * degPerPx
                    phi = (phi - stepY * degPerPx).coerceIn(-85.0, 85.0)
                    dirty = true
                    ensureFrames()
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                moved = true
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                pinchMidX = lastX
                pinchMidY = lastY
                anchorGeo = null
                return true
            }

            MotionEvent.ACTION_UP -> {
                // A fast flick can lose its intermediate move events; treat the
                // distance from the down point as a drag so it never turns into a tap.
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (!moved && !pinchUsed && abs(totalDx) + abs(totalDy) > touchSlop) {
                    val degPerPx = (180.0 / (PI * radius.coerceAtLeast(1.0))).toFloat()
                    lam += totalDx * degPerPx
                    phi = (phi - totalDy * degPerPx).coerceIn(-85.0, 85.0)
                    moved = true
                    dirty = true
                }
                dragging = false
                dirty = true
                lastReadout = 0L
                ensureFrames()
                parent?.requestDisallowInterceptTouchEvent(false)
                anchorGeo = null
                val tracker = velocityTracker
                velocityTracker = null
                if (tracker != null) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000)
                    if (moved && !pinchUsed) {
                        val degPerPx = (180.0 / (PI * radius.coerceAtLeast(1.0))).toFloat()
                        lamVelocity = tracker.xVelocity * degPerPx / 1000.0
                        phiVelocity = -tracker.yVelocity * degPerPx / 1000.0
                        flinging = abs(lamVelocity) > 0.008 || abs(phiVelocity) > 0.008
                        if (flinging) ensureFrames()
                    }
                    tracker.recycle()
                }
                if (!flinging) {
                    onInteractionEnd?.invoke()
                }

                if (!moved && !pinchUsed) {
                    val now = SystemClock.uptimeMillis()
                    val isDoubleTap = now - lastTapTime <= doubleTapTimeout &&
                        abs(event.x - lastTapX) < 48f * density &&
                        abs(event.y - lastTapY) < 48f * density
                    if (isDoubleTap) {
                        removeCallbacks(tapRunnable)
                        pendingTap = null
                        lastTapTime = 0L
                        updateProjection()
                        anchorX = event.x
                        anchorY = event.y
                        anchorGeo = inverse(event.x, event.y)
                        zoomTarget = (zoomTarget * 2.6).coerceAtMost(MAX_ZOOM)
                        CyberSfx.zoomPulse(true)
                        if (zoomTarget > 3.0) onUserRotate?.invoke()
                        dirty = true
                        ensureFrames()
                    } else {
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                        val videoTap = nearest(event.x, event.y, 24f * density)
                        if (videoTap != null) {
                            pendingTap = videoTap
                            postDelayed(tapRunnable, doubleTapTimeout.toLong())
                        } else if (!heatMode && zoom >= CLUSTER_MIN_ZOOM && zoom < CLUSTER_ZOOM) {
                            val cluster = nearestCluster(event.x, event.y, 26f * density)
                            if (cluster != null) {
                                lastTapTime = 0L
                                target = Target(
                                    lam,
                                    phi,
                                    -cluster.lon,
                                    (-cluster.lat).coerceIn(-72.0, 72.0),
                                    SystemClock.uptimeMillis(),
                                )
                                zoomTarget = (zoomTarget * 2.8).coerceAtMost(MAX_ZOOM)
                                CyberSfx.zoomPulse(true)
                                onUserRotate?.invoke()
                                dirty = true
                                ensureFrames()
                                return true
                            }
                            pendingTap = null
                            postDelayed(tapRunnable, doubleTapTimeout.toLong())
                        } else {
                            pendingTap = null
                            postDelayed(tapRunnable, doubleTapTimeout.toLong())
                        }
                    }
                }
                dirty = true
                ensureFrames()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                moved = true
                anchorGeo = null
                flinging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return true
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun pointerMidX(event: MotionEvent): Float =
        if (event.pointerCount < 2) event.x else (event.getX(0) + event.getX(1)) / 2f

    private fun pointerMidY(event: MotionEvent): Float =
        if (event.pointerCount < 2) event.y else (event.getY(0) + event.getY(1)) / 2f

    private fun shortestDelta(from: Double, to: Double): Double = ((to - from + 540) % 360) - 180

    private fun wrap180(value: Double): Double = ((value % 360) + 540) % 360 - 180

    private companion object {
        val pinExecutor = Executors.newSingleThreadExecutor()

        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 50000.0
        const val TILE_MIN_ZOOM = 20.0
        const val CLUSTER_MIN_ZOOM = 1.0
        const val CLUSTER_ZOOM = 10.0
        const val CLUSTER_CELL_DP = 42f
        const val HEAT_CELL_DP = 13f
        const val DEFAULT_LAM = -18.0
        const val DEFAULT_PHI = -14.0
        const val LOCK_ANIMATION_MS = 1_300L
        const val VIDEO_BATCH_THRESHOLD = 256
    }
}
