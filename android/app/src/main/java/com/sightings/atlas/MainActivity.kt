package com.sightings.atlas

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sightings.atlas.data.AtlasRepository
import com.sightings.atlas.data.BulkExporter
import com.sightings.atlas.data.BulkImporter
import com.sightings.atlas.data.CaseFile
import com.sightings.atlas.data.CaseFileStore
import com.sightings.atlas.data.CaseEvidence
import com.sightings.atlas.data.CaseHypothesis
import com.sightings.atlas.data.Dataset
import com.sightings.atlas.data.LiveTikTokFetcher
import com.sightings.atlas.model.Era
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.coordLabel
import com.sightings.atlas.model.distanceKm
import com.sightings.atlas.model.eraOf
import com.sightings.atlas.model.formatDate
import com.sightings.atlas.model.shapeLabel
import com.sightings.atlas.ui.CyberSfx
import com.sightings.atlas.ui.GlobeView
import com.sightings.atlas.ui.HistogramView
import com.sightings.atlas.ui.ReportSheet
import com.sightings.atlas.ui.SightingAdapter
import com.sightings.atlas.ui.TimelineView
import com.sightings.atlas.ui.YearRangeView
import com.sightings.atlas.ui.colorOf
import com.sightings.atlas.ui.dp
import com.sightings.atlas.ui.monoText
import com.sightings.atlas.ui.pill
import com.sightings.atlas.ui.scrambleText
import java.io.StringWriter
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos

class MainActivity : AppCompatActivity() {

    private lateinit var globe: GlobeView
    private lateinit var list: RecyclerView
    private lateinit var statReports: TextView
    private lateinit var statCountries: TextView
    private lateinit var statYears: TextView
    private lateinit var viewReadout: TextView
    private lateinit var plottedLabel: TextView
    private lateinit var autoSpin: TextView
    private lateinit var rotateHint: TextView
    private lateinit var legend: LinearLayout
    private lateinit var mapCredit: TextView
    private lateinit var tabReports: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabSocial: TextView
    private lateinit var searchInput: EditText
    private lateinit var filtersButton: TextView
    private lateinit var resultCount: TextView
    private lateinit var clearFilters: TextView
    private lateinit var emptyLabel: TextView
    private lateinit var loadingLabel: TextView
    private lateinit var moreButton: TextView
    private lateinit var source: TextView
    private lateinit var timeline: TimelineView
    private lateinit var timelinePlay: TextView
    private lateinit var timelineYear: TextView
    private lateinit var timelineSpeed: TextView
    private lateinit var globeContainer: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelToggle: TextView
    private lateinit var onThisDayButton: TextView
    private lateinit var statsButton: TextView
    private lateinit var nearMe: TextView
    private lateinit var surpriseButton: TextView
    private lateinit var heatButton: TextView

    private var adapter: SightingAdapter? = null
    private var dataset: Dataset? = null
    private var openSheet: ReportSheet? = null

    private var tab = 0
    private var query = ""
    private var era: Era? = null
    private var shape: String? = null
    private var yearStart = 1910
    private var yearEnd = 2026
    private var newestFirst = true
    private var limit = 200
    private var spinning = true
    private var savedOnly = false
    private var nightOnly = false
    private var nearLat = Double.NaN
    private var nearLon = Double.NaN
    private var playing = false
    private var playYear = 0
    private var playTo = 2026
    private var pendingReportId: Int? = null
    private var lastFiltered: List<Sighting> = emptyList()
    private var nearRadiusKm = 100.0
    private var onThisDay = false
    private var todayKey = ""
    private var timelineActive = false
    private var fastPlay = false
    private var timelineRefresh = false
    private var lastScrubAt = 0L
    private var hoverLat = Double.NaN
    private var hoverLon = Double.NaN
    private var hoverZoom = 1.0
    private var hoveringUk = false
    private var lastReadoutText = ""
    private var lastReadoutMode = ""
    private var lastUkFetchAt = 0L
    private var panelMode = 0 // 0 hidden, 1 split, 2 full console
    private val panelOpen: Boolean get() = panelMode != 0
    private var panelExpandedForList = false
    private var adapterDirtyWhileClosed = false

    private val caseStore by lazy { CaseFileStore(this) }
    private var activeCaseId: String? = null
    private var areaSearchActive = false
    private var areaLat = Double.NaN
    private var areaLon = Double.NaN
    private var areaRadiusKm = 200.0
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingExportFormat: String? = null
    private var pendingExportCaseId: String? = null
    private val exportDocLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { writePendingExport(it) }
        } else {
            pendingExportFormat = null
            pendingExportCaseId = null
        }
    }
    private val importDocLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { importDocument(it) }
        }
    }
    private val prefs by lazy { getSharedPreferences("atlas_prefs", Context.MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }
    private val hoverRefreshRunnable = Runnable {
        if (!panelOpen) {
            adapterDirtyWhileClosed = true
        } else if (!globe.isInteracting) {
            refresh(updateGlobePins = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        CyberSfx.init(this)
        bindViews()
        panelMode = if (prefs.getBoolean(KEY_PANEL_OPEN, false)) 1 else 0
        panelToggle.setOnClickListener {
            panelExpandedForList = false
            panelMode = (panelMode + 1) % 3
            CyberSfx.consoleSlide(opening = panelMode != 0)
            prefs.edit().putBoolean(KEY_PANEL_OPEN, panelOpen).apply()
            applyPanelState()
            if (panelOpen && adapterDirtyWhileClosed) {
                adapterDirtyWhileClosed = false
                refresh(updateGlobePins = false)
            }
        }
        applyPanelState()
        panel.post {
            if (panelMode == 1) applyPanelState()
        }
        todayKey = todayKeyString()
        pendingReportId = reportIdFrom(intent)
        buildLegend()

        val adapter = SightingAdapter(
            videoStyle = false,
            onClick = { onSightingClick(it) },
            caseStore = caseStore,
        )
        this.adapter = adapter
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        updateTabs()
        updateSpinChip()
        loadData()
    }

    private fun bindViews() {
        globe = findViewById(R.id.globe)
        list = findViewById(R.id.list)
        statReports = findViewById(R.id.statReports)
        statCountries = findViewById(R.id.statCountries)
        statYears = findViewById(R.id.statYears)
        viewReadout = findViewById(R.id.viewReadout)
        viewReadout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f)
        plottedLabel = findViewById(R.id.plottedLabel)
        autoSpin = findViewById(R.id.autoSpin)
        rotateHint = findViewById(R.id.rotateHint)
        legend = findViewById(R.id.legend)
        mapCredit = findViewById(R.id.mapCredit)
        tabReports = findViewById(R.id.tabReports)
        tabVideos = findViewById(R.id.tabVideos)
        tabSocial = findViewById(R.id.tabSocial)
        searchInput = findViewById(R.id.searchInput)
        filtersButton = findViewById(R.id.filtersButton)
        resultCount = findViewById(R.id.resultCount)
        clearFilters = findViewById(R.id.clearFilters)
        emptyLabel = findViewById(R.id.emptyLabel)
        loadingLabel = findViewById(R.id.loadingLabel)
        moreButton = findViewById(R.id.moreButton)
        source = findViewById(R.id.source)
        timeline = findViewById(R.id.timeline)
        timelinePlay = findViewById(R.id.timelinePlay)
        timelineYear = findViewById(R.id.timelineYear)
        timelineSpeed = findViewById(R.id.timelineSpeed)
        globeContainer = findViewById(R.id.globeContainer)
        panel = findViewById(R.id.panel)
        panelToggle = findViewById(R.id.panelToggle)
        timelineSpeed.contentDescription =
            getString(R.string.timeline_speed_value, getString(R.string.speed_1x))
        onThisDayButton = findViewById(R.id.onThisDay)
        statsButton = findViewById(R.id.statsButton)
        nearMe = findViewById(R.id.nearMe)
        surpriseButton = findViewById(R.id.surprise)
        heatButton = findViewById(R.id.heatToggle)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        tabReports.text = getString(R.string.tab_reports)
        tabVideos.text = getString(R.string.tab_video)
        tabSocial.text = getString(R.string.tab_social)

        tabReports.setOnClickListener { setTab(0) }
        tabVideos.setOnClickListener { setTab(1) }
        tabSocial.setOnClickListener { setTab(2) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString().orEmpty()
                limit = 200
                updateClearVisibility()
                scheduleRefresh()
            }
        })

        filtersButton.setOnClickListener { showFilters() }
        clearFilters.setOnClickListener { resetFilters(clearSearch = true) }
        moreButton.setOnClickListener {
            limit += 200
            refresh()
        }

        findViewById<TextView>(R.id.zoomIn).setOnClickListener {
            stopSpin()
            globe.zoomBy(2.0)
        }
        findViewById<TextView>(R.id.zoomOut).setOnClickListener {
            stopSpin()
            globe.zoomBy(1.0 / 2.0)
        }
        findViewById<TextView>(R.id.resetView).setOnClickListener { globe.resetView() }

        timelinePlay.setOnClickListener { togglePlayback() }
        timelineSpeed.setOnClickListener {
            fastPlay = !fastPlay
            timelineSpeed.text = getString(if (fastPlay) R.string.speed_4x else R.string.speed_1x)
            timelineSpeed.contentDescription =
                getString(R.string.timeline_speed_value, timelineSpeed.text)
            if (playing) {
                handler.removeCallbacks(playRunnable)
                handler.post(playRunnable)
            }
        }
        timelineYear.setOnClickListener { if (timelineActive) clearTimeline() }
        timeline.onScrub = { year -> scrubTo(year) }
        timeline.onScrubEnd = { if (timelineActive) applyTimelineYear(playYear, force = true) }
        onThisDayButton.setOnClickListener { toggleOnThisDay() }
        statsButton.setOnClickListener { showStats() }
        nearMe.setOnClickListener { toggleNearMe() }
        surpriseButton.setOnClickListener { surpriseMe() }
        heatButton.setOnClickListener {
            globe.heatMode = !globe.heatMode
            styleToggle(heatButton, globe.heatMode)
            if (globe.heatMode) stopSpin()
        }
        updateNearChip()
        updateOnThisDay()

        autoSpin.setOnClickListener {
            spinning = !spinning
            globe.autoRotate = spinning
            updateSpinChip()
        }

        globe.autoRotate = true
        globe.onSelect = { sighting ->
            if (sighting != null) openDetail(sighting)
        }
        globe.onUserRotate = { stopSpin() }
        globe.onViewChanged = { lat, lon, zoom ->
            hoverLat = lat
            hoverLon = lon
            hoverZoom = zoom
            val modeTag = when {
                areaSearchActive -> "RADAR:LOCK"
                globe.selected != null -> "TGT:LOCK"
                spinning -> "ORBIT:AUTO"
                else -> "VEC:FREE"
            }
            val readout = "[$modeTag] ${coordLabel(lat, lon)} // ${zoomLabel(zoom)}"
            if (readout != lastReadoutText) {
                lastReadoutText = readout
                if (modeTag != lastReadoutMode) {
                    lastReadoutMode = modeTag
                    viewReadout.scrambleText(readout, 220L)
                } else {
                    viewReadout.text = readout
                }
            }
            val nowHoveringUk = !spinning && LiveTikTokFetcher.isInUkRegion(lat, lon)
            if (nowHoveringUk != hoveringUk) {
                hoveringUk = nowHoveringUk
                if (nowHoveringUk) {
                    triggerUkTikTokFetch()
                }
                if (panelOpen) scheduleHoverRefresh() else adapterDirtyWhileClosed = true
            } else if ((nowHoveringUk || (!spinning && tab == 2)) && !globe.isInteracting) {
                if (panelOpen) scheduleHoverRefresh() else adapterDirtyWhileClosed = true
            }
        }
        globe.onInteractionEnd = {
            if (areaSearchActive) buildLegend()
            if (!spinning && (hoveringUk || tab == 2)) {
                if (panelOpen) scheduleHoverRefresh() else adapterDirtyWhileClosed = true
            }
        }
        globe.onStreetModeChanged = { active ->
            mapCredit.post {
                mapCredit.visibility = if (active) View.VISIBLE else View.GONE
            }
        }

        source.text = getString(R.string.report_source)
        lastReadoutText = getString(R.string.view_readout, "—", "1.0×")
        viewReadout.text = lastReadoutText
        loadingLabel.visibility = View.VISIBLE
    }

    private fun loadData() {
        Thread {
            val result = runCatching { AtlasRepository.load(this) }
            runOnUiThread {
                loadingLabel.visibility = View.GONE
                result.onSuccess { data ->
                    dataset = data
                    yearStart = data.minYear
                    yearEnd = data.maxYear
                    statReports.text = String.format(Locale.US, "%,d", data.reports.size)
                    statCountries.text = data.countries.toString()
                    statYears.text = "${data.minYear}–${data.maxYear}"
                    updateTabLabels(data)
                    globe.setWorld(data.world)
                    buildLegend()
                    buildTimeline(data)
                    refresh()
                    pendingReportId?.let { id ->
                        pendingReportId = null
                        openReportById(id)
                    }
                }.onFailure {
                    emptyLabel.text = getString(R.string.load_failed)
                    emptyLabel.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun updateTabLabels(data: Dataset) {
        val reports = if (data.reports.size >= 10_000) {
            String.format(Locale.US, "%.1fk", data.reports.size / 1000.0)
        } else {
            data.reports.size.toString()
        }
        tabReports.text = "${getString(R.string.tab_reports)} $reports"
        tabVideos.text = "▶ ${getString(R.string.tab_video)} ${data.videos.size}"
        tabSocial.text = "▶ ${getString(R.string.tab_social)} ${data.social.size}"
    }

    private fun setTab(index: Int) {
        if (tab == index && adapter != null) return
        // Video and social are browse lists: give them the whole console.
        if (index != 0 && panelMode == 1) {
            panelMode = 2
            panelExpandedForList = true
            applyPanelState()
        } else if (index == 0 && panelExpandedForList) {
            panelMode = 1
            panelExpandedForList = false
            applyPanelState()
        }
        CyberSfx.cipherBurst()
        tab = index
        limit = 200
        updateTabs()
        buildLegend()
        source.text = getString(
            when (index) {
                1 -> R.string.video_source
                2 -> R.string.social_source
                else -> R.string.report_source
            },
        )
        val adapter = SightingAdapter(
            videoStyle = index != 0,
            onClick = { onSightingClick(it) },
            caseStore = caseStore,
        )
        this.adapter = adapter
        list.adapter = adapter
        refresh()
    }

    private fun applyPanelState() {
        val panelLp = panel.layoutParams as? LinearLayout.LayoutParams
        val globeLp = globeContainer.layoutParams as? LinearLayout.LayoutParams
        when (panelMode) {
            0 -> {
                panel.visibility = View.GONE
                globeContainer.visibility = View.VISIBLE
                panelLp?.let {
                    it.height = 0
                    it.weight = 1.35f
                }
                globeLp?.let {
                    it.height = 0
                    it.weight = 1f
                }
                panelToggle.text = "[ ▤ CONSOLE ]"
                panelToggle.contentDescription = getString(R.string.show_reports_desc)
            }
            1 -> {
                panel.visibility = View.VISIBLE
                globeContainer.visibility = View.VISIBLE
                val available = (panel.parent as? View)?.height ?: 0
                panelLp?.let {
                    it.height = if (available > 0) {
                        (available * 0.48f).toInt()
                    } else {
                        (resources.displayMetrics.heightPixels * 0.48f).toInt()
                    }
                    it.weight = 0f
                }
                globeLp?.let {
                    it.height = 0
                    it.weight = 1f
                }
                panelToggle.text = "[ ⤢ EXPAND ]"
                panelToggle.contentDescription = getString(R.string.show_reports_desc)
            }
            else -> {
                panel.visibility = View.VISIBLE
                globeContainer.visibility = View.GONE
                panelLp?.let {
                    it.height = 0
                    it.weight = 1f
                }
                panelToggle.text = "[ ✕ GLOBE ]"
                panelToggle.contentDescription = getString(R.string.show_globe_desc)
            }
        }
        panelLp?.let { panel.layoutParams = it }
        globeLp?.let { globeContainer.layoutParams = it }
    }

    private fun syncRadarToGlobe() {
        globe.setRadarLock(areaSearchActive, areaLat, areaLon, areaRadiusKm)
        if (!hoverLat.isFinite() || !hoverLon.isFinite()) return
        val modeTag = when {
            areaSearchActive -> "RADAR:LOCK"
            globe.selected != null -> "TGT:LOCK"
            spinning -> "ORBIT:AUTO"
            else -> "VEC:FREE"
        }
        val readout = "[$modeTag] ${coordLabel(hoverLat, hoverLon)} // ${zoomLabel(hoverZoom)}"
        lastReadoutText = readout
        if (modeTag != lastReadoutMode) {
            lastReadoutMode = modeTag
            viewReadout.scrambleText(readout, 220L)
        } else {
            viewReadout.text = readout
        }
    }

    private fun updateTabs() {
        val tabs = listOf(tabReports, tabVideos, tabSocial)
        tabs.forEachIndexed { index, view ->
            view.setBackgroundResource(if (index == tab) R.drawable.bg_tab_on else 0)
            view.setTextColor(if (index == tab) colorOf(R.color.mint) else colorOf(R.color.muted))
        }
    }

    private fun updateSpinChip() {
        styleToggle(autoSpin, spinning)
    }

    private fun styleToggle(view: TextView, active: Boolean) {
        view.setTextColor(if (active) colorOf(R.color.mint) else colorOf(R.color.muted))
        view.background = pill(
            if (active) 0x1F6EE7B7 else 0x0A94C5D0,
            if (active) 0x806EE7B7.toInt() else colorOf(R.color.line),
        )
    }

    private fun buildLegend() {
        legend.removeAllViews()
        val ops = monoText(9.5f, colorOf(R.color.text), 0.08f).apply {
            text = "[ ⌘ OPS ]"
            gravity = Gravity.CENTER
            setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
            background = pill(0x1F6EE7B7, 0x806EE7B7.toInt())
            isClickable = true
            setOnClickListener { showOpsMenu() }
        }
        legend.addView(
            ops,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        legend.addView(
            legendChip("All", colorOf(R.color.mint), era == null && !savedOnly && !areaSearchActive) {
                era = null
                savedOnly = false
                activeCaseId = null
                areaSearchActive = false
                syncRadarToGlobe()
                buildLegend()
                updateClearVisibility()
                scheduleRefresh()
            },
        )

        val totalSaved = caseStore.allSavedIds().size
        val activeCase = activeCaseId?.let { caseStore.getCase(it) }
        val caseLabel = when {
            activeCase != null -> "${activeCase.name.uppercase(Locale.US)} (${activeCase.reportIds.size})"
            savedOnly -> "ALL CASES ($totalSaved)"
            totalSaved > 0 -> "CASES $totalSaved"
            else -> "CASES"
        }
        legend.addView(
            legendChip(caseLabel, colorOf(R.color.mint), savedOnly, dot = false) {
                showCaseFilesSheet()
            },
        )

        val areaAnchorLat = if (hoverLat.isFinite()) hoverLat else 14.0
        val areaAnchorLon = if (hoverLon.isFinite()) hoverLon else 18.0
        val areaMoved = areaSearchActive && hoverLat.isFinite() &&
            distanceKm(areaLat, areaLon, areaAnchorLat, areaAnchorLon) > 15.0

        val areaChipLabel = if (areaSearchActive) {
            "⌖ ${areaRadiusKm.toInt()}KM"
        } else {
            "⌖ AREA"
        }
        legend.addView(
            legendChip(areaChipLabel, colorOf(R.color.mint), areaSearchActive, dot = false) {
                stopSpin()
                if (!areaSearchActive) {
                    areaSearchActive = true
                    areaLat = if (hoverLat.isFinite()) hoverLat else 14.0
                    areaLon = if (hoverLon.isFinite()) hoverLon else 18.0
                } else {
                    areaSearchActive = false
                }
                syncRadarToGlobe()
                buildLegend()
                updateClearVisibility()
                refresh()
            },
        )

        if (areaSearchActive) {
            for (rad in doubleArrayOf(50.0, 200.0, 500.0)) {
                legend.addView(
                    legendChip("${rad.toInt()}KM", colorOf(R.color.mint), areaRadiusKm == rad, dot = false) {
                        areaRadiusKm = rad
                        syncRadarToGlobe()
                        buildLegend()
                        refresh()
                    },
                )
            }
            if (areaMoved) {
                legend.addView(
                    legendChip("↻ HERE", colorOf(R.color.mint), active = true, dot = false) {
                        areaLat = areaAnchorLat
                        areaLon = areaAnchorLon
                        syncRadarToGlobe()
                        buildLegend()
                        refresh()
                    },
                )
            }
        }

        legend.addView(
            legendChip("⇣ EXPORT", colorOf(R.color.mint), active = false, dot = false) {
                showExportViewSheet()
            },
        )
        legend.addView(
            legendChip("⇡ IMPORT", colorOf(R.color.mint), active = false, dot = false) {
                launchImportPicker()
            },
        )

        legend.addView(
            legendChip("▶ Video", colorOf(R.color.video_ink), tab == 1, dot = true) {
                setTab(if (tab == 1) 0 else 1)
                buildLegend()
            },
        )
        for (item in Era.entries) {
            legend.addView(
                legendChip(item.short, item.color, era == item) {
                    era = if (era == item) null else item
                    buildLegend()
                    updateClearVisibility()
                    scheduleRefresh()
                },
            )
        }
    }

    private fun legendChip(
        text: String,
        dotColor: Int,
        active: Boolean,
        dot: Boolean = true,
        onClick: () -> Unit,
    ): TextView {
        val label = SpannableString(if (dot) "● $text" else "★ $text")
        label.setSpan(ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val view = monoText(9.5f, if (active) colorOf(R.color.text) else colorOf(R.color.muted), 0.08f)
        view.text = label
        view.gravity = Gravity.CENTER
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        view.setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
        view.background = pill(
            if (active) 0x246EE7B7 else 0x0A94C5D0,
            if (active) dotColor else colorOf(R.color.line),
        )
        view.isClickable = true
        view.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(6f)
        view.layoutParams = params
        return view
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 70)
    }

    private fun scheduleHoverRefresh() {
        handler.removeCallbacks(hoverRefreshRunnable)
        handler.postDelayed(hoverRefreshRunnable, 70)
    }

    private fun matches(sighting: Sighting, text: String, ignoreYear: Boolean = false): Boolean {
        if (!ignoreYear && (sighting.year < yearStart || sighting.year > yearEnd)) return false
        return matchesNonYear(sighting, text)
    }

    private fun matchesNonYear(sighting: Sighting, text: String): Boolean {
        if (era != null && eraOf(sighting.year) != era) return false
        if (tab == 0 && shape != null && sighting.shape != shape) return false
        if (savedOnly) {
            val targetCase = activeCaseId?.let { caseStore.getCase(it) }
            val inCase = if (targetCase != null) {
                sighting.id in targetCase.reportIds
            } else {
                caseStore.isSaved(sighting.id)
            }
            if (!inCase) return false
        }
        if (onThisDay && !sighting.date.endsWith(todayKey)) return false
        if (nightOnly && sighting.video == null && !isNight(sighting.time)) return false
        if (nearLat.isFinite() && sighting.video == null) {
            if (!sighting.hasPosition) return false
            if (distanceKm(nearLat, nearLon, sighting.lat, sighting.lon) > nearRadiusKm) return false
        }
        if (areaSearchActive && areaLat.isFinite()) {
            if (!sighting.hasPosition) return false
            val maxLatDeg = areaRadiusKm / 110.5
            if (abs(sighting.lat - areaLat) > maxLatDeg) return false
            if (distanceKm(areaLat, areaLon, sighting.lat, sighting.lon) > areaRadiusKm) return false
        }
        if (text.isEmpty()) return true
        return sighting.searchHaystack.contains(text) || caseStore.matchesAnnotation(sighting.id, text)
    }

    private fun refresh(updateGlobePins: Boolean = true) {
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(hoverRefreshRunnable)
        if (playing && !timelineRefresh) {
            playing = false
            handler.removeCallbacks(playRunnable)
            updatePlayGlyph()
        }
        val data = dataset ?: return
        val text = query.trim().lowercase(Locale.US)
        val base = when (tab) {
            1 -> data.videos
            2 -> data.social
            else -> data.reports
        }

        val filtered = ArrayList<Sighting>(base.size)
        val pinReports = if (tab == 0 && updateGlobePins) ArrayList<Sighting>(base.size) else null
        for (sighting in base) {
            if (!matchesNonYear(sighting, text)) continue
            if (sighting.year in yearStart..yearEnd) filtered.add(sighting)
            pinReports?.add(sighting)
        }
        val distOriginLat = when {
            areaSearchActive && areaLat.isFinite() -> areaLat
            nearLat.isFinite() -> nearLat
            panelOpen && !spinning && (tab == 2 || hoveringUk) && hoverLat.isFinite() -> hoverLat
            else -> Double.NaN
        }
        val distOriginLon = when {
            areaSearchActive && areaLat.isFinite() -> areaLon
            nearLat.isFinite() -> nearLon
            else -> hoverLon
        }

        if (distOriginLat.isFinite() && filtered.size > 1) {
            val cosLat = cos(Math.toRadians(distOriginLat))
            val packed = LongArray(filtered.size)
            for (i in filtered.indices) {
                val sighting = filtered[i]
                val d2 = if (sighting.hasPosition) {
                    val dLat = sighting.lat - distOriginLat
                    val dLon = (sighting.lon - distOriginLon) * cosLat
                    ((dLat * dLat + dLon * dLon) * 1e6).toLong().coerceAtMost(0x7FFF_FFFFL)
                } else {
                    0x7FFF_FFFFL
                }
                packed[i] = (d2 shl 32) or (i.toLong() and 0xFFFF_FFFFL)
            }
            packed.sort()
            val copy = ArrayList<Sighting>(filtered.size)
            for (p in packed) copy.add(filtered[p.toInt()])
            for (i in filtered.indices) filtered[i] = copy[i]
        } else if (!newestFirst) {
            filtered.reverse()
        }

        var plotted = filtered.size
        if (updateGlobePins) {
            val pins = ArrayList<Sighting>(base.size + data.social.size + data.videos.size)
            when (tab) {
                1, 2 -> pins.addAll(filtered)
                else -> {
                    pins.addAll(pinReports.orEmpty())
                    for (sighting in data.videos) {
                        if (matches(sighting, text, ignoreYear = true)) pins.add(sighting)
                    }
                    for (sighting in data.social) {
                        if (matches(sighting, text, ignoreYear = true)) pins.add(sighting)
                    }
                }
            }
            globe.setSightings(pins)
        }
        if (tab == 0) {
            for (sighting in data.videos) {
                if (matches(sighting, text)) plotted++
            }
            for (sighting in data.social) {
                if (matches(sighting, text)) plotted++
            }
        }
        plottedLabel.text = getString(R.string.plotted, String.format(Locale.US, "%,d", plotted))

        if (hoveringUk && tab == 0 && !areaSearchActive) {
            val ukClips = (data.videos + data.social)
                .filter { it.hasPosition && LiveTikTokFetcher.isInUkRegion(it.lat, it.lon) && matches(it, text) }
                .sortedBy { distanceKm(hoverLat, hoverLon, it.lat, it.lon) }
            if (ukClips.isNotEmpty()) {
                filtered.addAll(0, ukClips)
            }
        }

        lastFiltered = filtered
        adapter?.origin = when {
            areaSearchActive && areaLat.isFinite() -> doubleArrayOf(areaLat, areaLon)
            nearLat.isFinite() -> doubleArrayOf(nearLat, nearLon)
            hoveringUk && hoverLat.isFinite() -> doubleArrayOf(hoverLat, hoverLon)
            else -> null
        }

        if (panelOpen) {
            adapterDirtyWhileClosed = false
            adapter?.submit(filtered.take(limit))
        } else {
            adapterDirtyWhileClosed = true
        }
        val plural = when (tab) {
            1 -> R.plurals.clips
            2 -> R.plurals.posts
            else -> R.plurals.reports
        }
        resultCount.text = resources.getQuantityString(
            plural,
            filtered.size,
            String.format(Locale.US, "%,d", filtered.size),
        )
        moreButton.visibility = if (filtered.size > limit) View.VISIBLE else View.GONE
        emptyLabel.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        updateClearVisibility()
    }

    private fun updateClearVisibility() {
        val active = era != null || shape != null || savedOnly || nightOnly || onThisDay ||
            nearLat.isFinite() || areaSearchActive ||
            yearStart > (dataset?.minYear ?: yearStart) ||
            yearEnd < (dataset?.maxYear ?: yearEnd) ||
            query.isNotBlank()
        clearFilters.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun resetFilters(clearSearch: Boolean) {
        era = null
        shape = null
        savedOnly = false
        activeCaseId = null
        areaSearchActive = false
        areaLat = Double.NaN
        areaLon = Double.NaN
        syncRadarToGlobe()
        nightOnly = false
        onThisDay = false
        updateOnThisDay()
        resetTimelineUi()
        globe.clearYearWindow()
        nearLat = Double.NaN
        nearLon = Double.NaN
        updateNearChip()
        val data = dataset
        if (data != null) {
            yearStart = data.minYear
            yearEnd = data.maxYear
        }
        if (clearSearch) searchInput.setText("")
        buildLegend()
        updateClearVisibility()
        scheduleRefresh()
    }

    private fun stopSpin() {
        if (rotateHint.visibility == View.VISIBLE) rotateHint.visibility = View.GONE
        if (spinning) {
            spinning = false
            globe.autoRotate = false
            updateSpinChip()
            if (hoverLat.isFinite() && LiveTikTokFetcher.isInUkRegion(hoverLat, hoverLon)) {
                hoveringUk = true
                triggerUkTikTokFetch()
                if (panelOpen) scheduleHoverRefresh() else adapterDirtyWhileClosed = true
            }
        }
    }

    private fun triggerUkTikTokFetch(force: Boolean = false) {
        val data = dataset ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUkFetchAt < 30_000L) return
        lastUkFetchAt = now
        val existingIds = data.social.mapNotNull { it.video?.id }.toSet()
        LiveTikTokFetcher.fetchUkClipsAsync(this, existingIds, force = force) { newClips ->
            runOnUiThread {
                val cur = dataset ?: return@runOnUiThread
                val known = cur.social.mapNotNull { it.video?.id }.toHashSet()
                var added = 0
                for (clip in newClips) {
                    val cid = clip.video?.id ?: continue
                    if (known.add(cid)) {
                        cur.social.add(clip)
                        added++
                    }
                }
                if (added > 0) {
                    cur.social.sortWith(compareByDescending<Sighting> { it.date }.thenByDescending { it.id })
                    updateTabLabels(cur)
                    refresh()
                    Toast.makeText(this, "SIGINT: +$added UK clips acquired", Toast.LENGTH_SHORT).show()
                } else if (force) {
                    Toast.makeText(this, "SIGINT: UK feed up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onSightingClick(sighting: Sighting) {
        stopSpin()
        if (panelMode == 1 && globe.selected?.id != sighting.id && sighting.hasPosition) {
            globe.selected = sighting
            globe.flyTo(sighting.lat, sighting.lon, kotlin.math.max(hoverZoom, 3.6))
            viewReadout.scrambleText(
                "[TGT#${sighting.id}] ${coordLabel(sighting.lat, sighting.lon)} // LOCKED",
                220L,
            )
        } else {
            if (sighting.hasPosition) {
                globe.flyTo(sighting.lat, sighting.lon, kotlin.math.max(hoverZoom, 3.2))
            }
            openDetail(sighting)
        }
    }

    private fun openDetail(sighting: Sighting) {
        // Freeze the spin so the place you opened does not drift while you zoom in.
        stopSpin()
        globe.selected = sighting
        var sheetRef: ReportSheet? = null
        val sheet = ReportSheet(
            this,
            sighting,
            saved = caseStore.isSaved(sighting.id),
            onSaveToggle = { _ ->
                showReportCaseDialog(sighting) { isSaved -> sheetRef?.setSaved(isSaved) }
            },
        )
        sheetRef = sheet
        sheet.onDismiss = {
            globe.selected = null
            if (openSheet === sheet) openSheet = null
        }
        openSheet = sheet
        sheet.show()
    }

    /** Per-report dialog to assign Case Files, edit investigator notes, and add #tags. */
    private fun showReportCaseDialog(sighting: Sighting, onClosed: (Boolean) -> Unit = {}) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(36f))
            setBackgroundResource(R.drawable.bg_sheet)
        }
        scroll.addView(card)
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val selectedCases = caseStore.casesForReport(sighting.id).map { it.id }.toMutableSet()
        if (selectedCases.isEmpty()) {
            caseStore.allCases().firstOrNull()?.let { selectedCases.add(it.id) }
        }
        val existingAnn = caseStore.annotationFor(sighting.id)

        card.addView(
            TextView(this).apply {
                text = "Case File & Notes"
                typeface = Typeface.SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(colorOf(R.color.text))
            },
        )
        card.addView(
            monoText(9.5f, colorOf(R.color.muted), 0.05f).apply {
                text = "${sighting.placeLabel()} · ${formatDate(sighting.date)}"
                setPadding(0, dp(4f), 0, dp(10f))
            },
        )

        card.addView(sectionLabel("COLLECTIONS"))
        val casesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(4f))
        }
        card.addView(casesContainer)

        fun renderCaseCheckboxes() {
            casesContainer.removeAllViews()
            for (c in caseStore.allCases()) {
                val active = c.id in selectedCases
                val row = monoText(11f, if (active) colorOf(R.color.mint) else colorOf(R.color.text), 0.04f).apply {
                    text = if (active) "✓  ${c.name} (${c.reportIds.size})" else "○  ${c.name} (${c.reportIds.size})"
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip)
                    setOnClickListener {
                        if (c.id in selectedCases) selectedCases.remove(c.id) else selectedCases.add(c.id)
                        renderCaseCheckboxes()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6f) }
                casesContainer.addView(row, lp)
            }
            val addBtn = monoText(10f, colorOf(R.color.mint), 0.08f).apply {
                text = "+ NEW CASE FILE"
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setOnClickListener {
                    promptNewCaseName { created ->
                        selectedCases.add(created.id)
                        renderCaseCheckboxes()
                    }
                }
            }
            casesContainer.addView(addBtn)
        }
        renderCaseCheckboxes()

        card.addView(sectionLabel("TAGS (COMMA SEPARATED)"))
        val tagsInput = EditText(this).apply {
            hint = "e.g. radar, military, multiple-witness"
            setHintTextColor(colorOf(R.color.dim))
            setTextColor(colorOf(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setText(existingAnn?.tags?.joinToString(", ").orEmpty())
        }
        card.addView(
            tagsInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6f) },
        )

        card.addView(sectionLabel("INVESTIGATOR NOTES"))
        val noteInput = EditText(this).apply {
            hint = "Add analysis, cross-references, weather/flight notes…"
            setHintTextColor(colorOf(R.color.dim))
            setTextColor(colorOf(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setText(existingAnn?.note.orEmpty())
        }
        card.addView(
            noteInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6f) },
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18f), 0, 0)
        }
        if (caseStore.isSaved(sighting.id) || existingAnn != null) {
            actions.addView(
                monoText(10f, colorOf(R.color.muted), 0.08f).apply {
                    text = "REMOVE ALL"
                    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                    setOnClickListener {
                        caseStore.setReportCases(sighting.id, emptySet())
                        caseStore.setAnnotation(sighting.id, "", emptyList())
                        buildLegend()
                        scheduleRefresh()
                        dialog.dismiss()
                    }
                },
            )
        }
        actions.addView(
            monoText(10f, colorOf(R.color.mint), 0.08f).apply {
                text = "SAVE TO CASE"
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                setBackgroundResource(R.drawable.bg_chip_on)
                setOnClickListener {
                    val parsedTags = tagsInput.text?.toString().orEmpty()
                        .split(',', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    caseStore.setReportCases(sighting.id, selectedCases)
                    caseStore.setAnnotation(sighting.id, noteInput.text?.toString().orEmpty(), parsedTags)
                    CyberSfx.confirm()
                    buildLegend()
                    scheduleRefresh()
                    dialog.dismiss()
                }
            },
        )
        card.addView(actions)
        dialog.setOnDismissListener { onClosed(caseStore.isSaved(sighting.id)) }
        dialog.show()
    }

    /** Bottom sheet to browse, filter, create, rename, delete, and export Case Files. */
    private fun showCaseFilesSheet() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(40f))
            setBackgroundResource(R.drawable.bg_sheet)
        }
        scroll.addView(card)
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "Case Files"
                typeface = Typeface.SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(colorOf(R.color.text))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            monoText(10f, colorOf(R.color.mint), 0.08f).apply {
                text = "+ NEW CASE"
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                setBackgroundResource(R.drawable.bg_chip_on)
                setOnClickListener {
                    promptNewCaseName {
                        dialog.dismiss()
                        showCaseFilesSheet()
                    }
                }
            },
        )
        card.addView(header)

        val totalSaved = caseStore.allSavedIds().size
        val allRow = monoText(
            11f,
            if (savedOnly && activeCaseId == null) colorOf(R.color.mint) else colorOf(R.color.text),
            0.05f,
        ).apply {
            text = if (savedOnly && activeCaseId == null) {
                "★ SHOWING ALL SAVED REPORTS ($totalSaved)  [CLEAR]"
            } else {
                "★ FILTER: ALL SAVED REPORTS ($totalSaved)"
            }
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            setBackgroundResource(if (savedOnly && activeCaseId == null) R.drawable.bg_chip_on else R.drawable.bg_chip)
            setOnClickListener {
                if (savedOnly && activeCaseId == null) {
                    savedOnly = false
                } else {
                    savedOnly = true
                    activeCaseId = null
                }
                buildLegend()
                updateClearVisibility()
                scheduleRefresh()
                dialog.dismiss()
            }
        }
        card.addView(
            allRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12f) },
        )

        card.addView(sectionLabel("COLLECTIONS"))

        for (c in caseStore.allCases()) {
            val isCurrent = savedOnly && activeCaseId == c.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setBackgroundResource(if (isCurrent) R.drawable.bg_chip_on else R.drawable.bg_chip)
            }
            row.addView(
                monoText(11f, if (isCurrent) colorOf(R.color.mint) else colorOf(R.color.text), 0.04f).apply {
                    text = "${c.name} (${c.reportIds.size})"
                    setOnClickListener {
                        if (isCurrent) {
                            savedOnly = false
                            activeCaseId = null
                        } else {
                            savedOnly = true
                            activeCaseId = c.id
                        }
                        buildLegend()
                        updateClearVisibility()
                        scheduleRefresh()
                        dialog.dismiss()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                monoText(9.5f, colorOf(R.color.mint), 0.06f).apply {
                    text = "OPEN"
                    setPadding(dp(10f), dp(6f), dp(6f), dp(6f))
                    setOnClickListener {
                        dialog.dismiss()
                        showCaseDetail(c.id)
                    }
                },
            )
            row.addView(
                monoText(9.5f, colorOf(R.color.mint), 0.06f).apply {
                    text = "SHARE / EXPORT"
                    setPadding(dp(10f), dp(6f), dp(6f), dp(6f))
                    setOnClickListener {
                        dialog.dismiss()
                        showCaseExportOptions(c)
                    }
                },
            )
            card.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6f) },
            )
        }

        dialog.show()
    }

    private fun promptNewCaseName(onCreated: (CaseFile) -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g. UK 2024, Best evidence…"
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        AlertDialog.Builder(this)
            .setTitle("New Case File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString().orEmpty()
                if (name.isNotBlank()) {
                    val created = caseStore.createCase(name)
                    buildLegend()
                    onCreated(created)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Full case workspace: metadata, evidence locker and hypothesis matrix. */
    private fun showCaseDetail(caseId: String) {
        val cf = caseStore.getCase(caseId) ?: return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(40f))
            setBackgroundResource(R.drawable.bg_sheet)
        }
        scroll.addView(card)
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val statuses = listOf("Open", "Active", "Stalled", "Closed — Explained", "Closed — Unexplained")
        val priorities = listOf("Low", "Normal", "High", "Urgent")
        val evidenceKinds = listOf("Report", "Video", "Article", "Witness", "Flight data", "Weather", "Other")
        val reliabilities = listOf("Official", "News", "Eyewitness", "Social", "Unknown")
        val hypothesisStatuses = listOf("Untested", "Likely", "Ruled out", "Untestable")
        var render: () -> Unit = {}

        fun inputField(hintText: String, initial: String, multiline: Boolean = false, uri: Boolean = false): EditText =
            EditText(this).apply {
                hint = hintText
                setHintTextColor(colorOf(R.color.dim))
                setTextColor(colorOf(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setBackgroundResource(R.drawable.bg_field)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                minLines = if (multiline) 3 else 1
                gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
                if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(initial)
            }

        fun label(text: String) = monoText(8.5f, colorOf(R.color.dim), 0.14f).apply {
            this.text = text.uppercase(Locale.US)
            setPadding(0, dp(12f), 0, dp(3f))
        }

        fun actionButton(text: String, onClick: () -> Unit): TextView =
            monoText(9.5f, colorOf(R.color.mint), 0.08f).apply {
                this.text = text
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                setBackgroundResource(R.drawable.bg_chip_on)
                setOnClickListener { onClick() }
            }

        fun editField(title: String, initial: String, placeholder: String, onSave: (String) -> Unit) {
            val field = inputField(placeholder, initial, multiline = true)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("Save") { _, _ ->
                    onSave(field.text?.toString().orEmpty())
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun addEvidenceDialog(existing: CaseEvidence?) {
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20f), dp(4f), dp(20f), 0)
            }
            val titleInput = inputField("Title", existing?.title.orEmpty())
            val urlInput = inputField("Source URL (optional)", existing?.url.orEmpty(), uri = true)
            val notesInput = inputField("What does it show or prove?", existing?.notes.orEmpty(), multiline = true)
            val kindSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, evidenceKinds)
                setSelection(evidenceKinds.indexOf(existing?.kind).coerceAtLeast(0))
            }
            val reliabilitySpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, reliabilities)
                setSelection(reliabilities.indexOf(existing?.reliability).coerceAtLeast(0))
            }
            form.addView(label("Title")); form.addView(titleInput)
            form.addView(label("Source URL")); form.addView(urlInput)
            form.addView(label("Type")); form.addView(kindSpinner)
            form.addView(label("Reliability")); form.addView(reliabilitySpinner)
            form.addView(label("Investigator notes")); form.addView(notesInput)
            val wrapped = ScrollView(this).apply { addView(form) }
            AlertDialog.Builder(this)
                .setTitle(if (existing == null) "Add Evidence" else "Edit Evidence")
                .setView(wrapped)
                .setPositiveButton("Save") { _, _ ->
                    val kind = kindSpinner.selectedItem?.toString().orEmpty().ifBlank { "Other" }
                    val reliability = reliabilitySpinner.selectedItem?.toString().orEmpty().ifBlank { "Unknown" }
                    if (existing == null) {
                        caseStore.addEvidence(cf.id, kind, titleInput.text?.toString().orEmpty(), urlInput.text?.toString().orEmpty(), reliability, notesInput.text?.toString().orEmpty())
                    } else {
                        caseStore.updateEvidence(cf.id, existing.id, kind, titleInput.text?.toString().orEmpty(), urlInput.text?.toString().orEmpty(), reliability, notesInput.text?.toString().orEmpty())
                    }
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun addHypothesisDialog(existing: CaseHypothesis?) {
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20f), dp(4f), dp(20f), 0)
            }
            val labelInput = inputField("e.g. Aircraft landing lights", existing?.label.orEmpty())
            val confidenceInput = inputField("0–100", (existing?.confidence ?: 50).toString()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            val supportingInput = inputField("Supporting evidence", existing?.supporting.orEmpty(), multiline = true)
            val contradictingInput = inputField("Contradicting evidence", existing?.contradicting.orEmpty(), multiline = true)
            val statusSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, hypothesisStatuses)
                setSelection(hypothesisStatuses.indexOf(existing?.status).coerceAtLeast(0))
            }
            form.addView(label("Hypothesis")); form.addView(labelInput)
            form.addView(label("Status")); form.addView(statusSpinner)
            form.addView(label("Confidence %")); form.addView(confidenceInput)
            form.addView(label("Supporting")); form.addView(supportingInput)
            form.addView(label("Contradicting")); form.addView(contradictingInput)
            AlertDialog.Builder(this)
                .setTitle(if (existing == null) "Add Hypothesis" else "Edit Hypothesis")
                .setView(ScrollView(this).apply { addView(form) })
                .setPositiveButton("Save") { _, _ ->
                    val confidence = confidenceInput.text?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                    val status = statusSpinner.selectedItem?.toString().orEmpty().ifBlank { "Untested" }
                    if (existing == null) {
                        caseStore.addHypothesis(cf.id, labelInput.text?.toString().orEmpty(), status, confidence, supportingInput.text?.toString().orEmpty(), contradictingInput.text?.toString().orEmpty())
                    } else {
                        caseStore.updateHypothesis(cf.id, existing.id, labelInput.text?.toString().orEmpty(), status, confidence, supportingInput.text?.toString().orEmpty(), contradictingInput.text?.toString().orEmpty())
                    }
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun confirmDelete(what: String, action: () -> Unit) {
            AlertDialog.Builder(this)
                .setTitle("Delete $what?")
                .setMessage("This removes it from the case file.")
                .setPositiveButton("Delete") { _, _ ->
                    action()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        render = {
            card.removeAllViews()
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(
                TextView(this).apply {
                    text = cf.name
                    typeface = Typeface.SERIF
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTextColor(colorOf(R.color.text))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            header.addView(
                monoText(13f, colorOf(R.color.muted), 0f).apply {
                    text = "×"
                    setPadding(dp(14f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener { dialog.dismiss() }
                },
            )
            card.addView(header)
            card.addView(
                monoText(8.5f, colorOf(R.color.dim), 0.08f).apply {
                    text = "CASE #${cf.id.uppercase(Locale.US)} · ${cf.reportIds.size} REPORTS"
                    setPadding(0, dp(4f), 0, dp(6f))
                },
            )

            val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun metaChip(labelText: String, value: String, onClick: () -> Unit): TextView =
                monoText(9f, colorOf(R.color.mint), 0.06f).apply {
                    text = "$labelText · $value  ▸"
                    setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                    setBackgroundResource(R.drawable.bg_chip)
                    setOnClickListener { onClick() }
                }
            metaRow.addView(
                metaChip("STATUS", cf.status) {
                    val next = statuses[(statuses.indexOf(cf.status).coerceAtLeast(0) + 1) % statuses.size]
                    caseStore.updateCaseMeta(cf.id, status = next)
                    render()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) },
            )
            metaRow.addView(
                metaChip("PRIORITY", cf.priority) {
                    val next = priorities[(priorities.indexOf(cf.priority).coerceAtLeast(0) + 1) % priorities.size]
                    caseStore.updateCaseMeta(cf.id, priority = next)
                    render()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            card.addView(metaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })

            fun caseField(title: String, value: String, placeholder: String, onEdit: () -> Unit) {
                card.addView(sectionLabel(title))
                card.addView(
                    TextView(this).apply {
                        text = value.ifBlank { placeholder }
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTextColor(if (value.isBlank()) colorOf(R.color.dim) else colorOf(R.color.text))
                        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                        setBackgroundResource(R.drawable.bg_field)
                        setOnClickListener { onEdit() }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) },
                )
            }
            caseField("Case brief", cf.summary, "Tap to add the question this case is trying to answer…") {
                editField("Case Brief", cf.summary, "What are you investigating?", { caseStore.updateCaseMeta(cf.id, summary = it) })
            }
            caseField("Next action", cf.nextAction, "Tap to add the next concrete step…") {
                editField("Next Action", cf.nextAction, "e.g. Check Flightradar for 21:14", { caseStore.updateCaseMeta(cf.id, nextAction = it) })
            }
            caseField("Conclusion", cf.conclusion, "Tap to record your current verdict and why…") {
                editField("Conclusion", cf.conclusion, "What do you conclude, and what is the evidence?", { caseStore.updateCaseMeta(cf.id, conclusion = it) })
            }

            val caseActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10f), 0, 0)
            }
            caseActions.addView(actionButton("FILTER REPORTS") {
                savedOnly = true
                activeCaseId = cf.id
                buildLegend()
                updateClearVisibility()
                scheduleRefresh()
                dialog.dismiss()
            })
            caseActions.addView(
                actionButton("SHARE / EXPORT") {
                    dialog.dismiss()
                    showCaseExportOptions(cf)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f) },
            )
            card.addView(caseActions)

            card.addView(sectionLabel("Evidence locker (${cf.evidence.size})"))
            if (cf.evidence.isEmpty()) {
                card.addView(monoText(9.5f, colorOf(R.color.dim), 0.04f).apply { text = "No evidence attached yet." })
            }
            for (e in cf.evidence.toList()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    setBackgroundResource(R.drawable.bg_chip)
                }
                val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                main.addView(TextView(this).apply {
                    text = e.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(colorOf(R.color.text))
                })
                main.addView(monoText(8f, colorOf(R.color.dim), 0.06f).apply {
                    text = "${e.kind} · ${e.reliability}" + if (e.url.isNotBlank()) " · ${e.url}" else ""
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (e.notes.isNotBlank()) {
                    main.addView(monoText(8.5f, colorOf(R.color.muted), 0.02f).apply {
                        text = e.notes
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                main.setOnClickListener {
                    if (e.url.isNotBlank()) openExternalUrl(e.url) else addEvidenceDialog(e)
                }
                row.addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(monoText(8.5f, colorOf(R.color.mint), 0.06f).apply {
                    text = "EDIT"
                    setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
                    setOnClickListener { addEvidenceDialog(e) }
                })
                row.addView(monoText(11f, colorOf(R.color.muted), 0f).apply {
                    text = "×"
                    setPadding(dp(8f), dp(2f), dp(2f), dp(2f))
                    setOnClickListener { confirmDelete("evidence") { caseStore.deleteEvidence(cf.id, e.id) } }
                })
                card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            }
            card.addView(actionButton("+ ADD EVIDENCE") { addEvidenceDialog(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

            card.addView(sectionLabel("Hypotheses (${cf.hypotheses.size})"))
            if (cf.hypotheses.isEmpty()) {
                card.addView(monoText(9.5f, colorOf(R.color.dim), 0.04f).apply { text = "No hypotheses yet. Start with the mundane explanations." })
            }
            for (h in cf.hypotheses.toList()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    setBackgroundResource(R.drawable.bg_chip)
                }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(this).apply {
                    text = h.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(colorOf(R.color.text))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                top.addView(monoText(8.5f, colorOf(R.color.mint), 0.04f).apply { text = "${h.status.uppercase(Locale.US)} · ${h.confidence}%" })
                top.addView(monoText(8.5f, colorOf(R.color.mint), 0.04f).apply {
                    text = "EDIT"
                    setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
                    setOnClickListener { addHypothesisDialog(h) }
                })
                top.addView(monoText(11f, colorOf(R.color.muted), 0f).apply {
                    text = "×"
                    setPadding(dp(8f), dp(2f), dp(2f), dp(2f))
                    setOnClickListener { confirmDelete("hypothesis") { caseStore.deleteHypothesis(cf.id, h.id) } }
                })
                row.addView(top)
                if (h.supporting.isNotBlank()) {
                    row.addView(monoText(8.5f, colorOf(R.color.mint), 0.02f).apply { text = "+ ${h.supporting}"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                }
                if (h.contradicting.isNotBlank()) {
                    row.addView(monoText(8.5f, Color.parseColor("#F59E0B"), 0.02f).apply { text = "− ${h.contradicting}"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                }
                row.setOnClickListener { addHypothesisDialog(h) }
                card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            }
            card.addView(actionButton("+ ADD HYPOTHESIS") { addHypothesisDialog(null) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        render()
        dialog.show()
    }

    private fun showCaseExportOptions(caseFile: CaseFile) {
        val options = arrayOf(
            "Share as Markdown (.md)",
            "Share as JSON (.json)",
            "Save Markdown to device…",
            "Save JSON to device…",
            "Rename Case File",
            "Delete Case File",
        )
        AlertDialog.Builder(this)
            .setTitle(caseFile.name)
            .setItems(options) { _, which ->
                val data = dataset ?: return@setItems
                val byId = HashMap<Int, Sighting>(data.reports.size + data.videos.size + data.social.size)
                for (s in data.reports) byId[s.id] = s
                for (s in data.videos) byId[s.id] = s
                for (s in data.social) byId[s.id] = s

                when (which) {
                    0 -> {
                        val md = caseStore.exportCaseMarkdown(caseFile, byId)
                        CyberSfx.confirm()
                        shareText(md, "${caseFile.name} — Case File", "text/markdown")
                    }
                    1 -> {
                        val json = caseStore.exportCaseJson(caseFile, byId)
                        CyberSfx.confirm()
                        shareText(json, "${caseFile.name}.json", "application/json")
                    }
                    2 -> {
                        pendingExportFormat = "case_md"
                        pendingExportCaseId = caseFile.id
                        val slug = caseFile.name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")
                        launchDocumentPicker("${slug}_case.md", "text/markdown")
                    }
                    3 -> {
                        pendingExportFormat = "case_json"
                        pendingExportCaseId = caseFile.id
                        val slug = caseFile.name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")
                        launchDocumentPicker("${slug}_case.json", "application/json")
                    }
                    4 -> {
                        val input = EditText(this).apply {
                            setText(caseFile.name)
                            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Rename Case File")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                caseStore.renameCase(caseFile.id, input.text?.toString().orEmpty())
                                buildLegend()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    5 -> {
                        caseStore.deleteCase(caseFile.id)
                        if (activeCaseId == caseFile.id) {
                            activeCaseId = null
                            savedOnly = false
                        }
                        buildLegend()
                        scheduleRefresh()
                    }
                }
            }
            .show()
    }

    /** One-thumb command palette for the tactical console. */
    private fun showOpsMenu() {
        CyberSfx.cipherBurst()
        val items = arrayOf(
            "📁  Case Files & Dossiers (${caseStore.allSavedIds().size} saved)",
            "⌖  ${if (areaSearchActive) "Disable Radar Area Lock" else "Lock Radar to Crosshair (${areaRadiusKm.toInt()} km)"}",
            "📡  ${if (nearLat.isFinite()) "Step / Clear GPS Radar (${nearRadiusKm.toInt()} km)" else "GPS Near-Me Scan"}",
            "📡  Scan UK Live Feeds (TikTok / oEmbed)",
            "🔥  ${if (globe.heatMode) "Disable Thermal Density Grid" else "Enable Thermal Density Grid"}",
            "📅  ${if (onThisDay) "Clear On-This-Day Filter ($todayKey)" else "Filter: On This Day ($todayKey)"}",
            "⚡  Acquire Random Target",
            "📊  Global SIGINT Telemetry (Stats)",
            "⇣  Export View (${String.format(Locale.US, "%,d", lastFiltered.size)} records)…",
            if (CyberSfx.enabled) "🔊  Audio Telemetry: ONLINE (Mute)" else "🔇  Audio Telemetry: MUTED (Enable)",
        )
        AlertDialog.Builder(this)
            .setTitle(">_ SIGINT // TACTICAL OPS")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showCaseFilesSheet()
                    1 -> {
                        stopSpin()
                        areaSearchActive = !areaSearchActive
                        if (areaSearchActive) {
                            areaLat = if (hoverLat.isFinite()) hoverLat else 14.0
                            areaLon = if (hoverLon.isFinite()) hoverLon else 18.0
                            if (panelMode == 0) {
                                panelMode = 1
                                applyPanelState()
                            }
                        }
                        syncRadarToGlobe()
                        buildLegend()
                        updateClearVisibility()
                        refresh()
                    }
                    2 -> toggleNearMe()
                    3 -> triggerUkTikTokFetch(force = true)
                    4 -> {
                        globe.heatMode = !globe.heatMode
                        styleToggle(heatButton, globe.heatMode)
                        if (globe.heatMode) stopSpin()
                    }
                    5 -> toggleOnThisDay()
                    6 -> surpriseMe()
                    7 -> showStats()
                    8 -> showExportViewSheet()
                    9 -> {
                        CyberSfx.toggle(this)
                        buildLegend()
                    }
                }
            }
            .show()
    }

    /** Bulk export of the current filtered view (CSV or GeoJSON). */
    private fun showExportViewSheet() {
        val count = lastFiltered.size
        if (count == 0) {
            Toast.makeText(this, "No reports in current view to export", Toast.LENGTH_SHORT).show()
            return
        }
        val formattedCount = String.format(Locale.US, "%,d", count)
        val options = arrayOf(
            "Save CSV ($formattedCount reports) to device…",
            "Save GeoJSON ($formattedCount reports) to device…",
            "Share CSV (top ${kotlin.math.min(count, 500)} reports)",
            "Share GeoJSON (top ${kotlin.math.min(count, 500)} reports)",
        )
        AlertDialog.Builder(this)
            .setTitle("Export Current View ($formattedCount)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingExportFormat = "csv"
                        pendingExportCaseId = null
                        launchDocumentPicker("sightings_export_${yearStart}_${yearEnd}.csv", "text/csv")
                    }
                    1 -> {
                        pendingExportFormat = "geojson"
                        pendingExportCaseId = null
                        launchDocumentPicker("sightings_export_${yearStart}_${yearEnd}.geojson", "application/geo+json")
                    }
                    2 -> {
                        val slice = lastFiltered.take(500)
                        val src = when (tab) { 1 -> "video"; 2 -> "social"; else -> "nuforc" }
                        ioExecutor.execute {
                            val sw = StringWriter(slice.size * 120)
                            BulkExporter.writeCsv(sw, slice, caseStore, src)
                            val payload = sw.toString()
                            runOnUiThread {
                                CyberSfx.confirm()
                                shareText(payload, "sightings_export.csv", "text/csv")
                            }
                        }
                    }
                    3 -> {
                        val slice = lastFiltered.take(500)
                        val src = when (tab) { 1 -> "video"; 2 -> "social"; else -> "nuforc" }
                        ioExecutor.execute {
                            val sw = StringWriter(slice.size * 180)
                            BulkExporter.writeGeoJson(sw, slice, caseStore, src)
                            val payload = sw.toString()
                            runOnUiThread {
                                CyberSfx.confirm()
                                shareText(payload, "sightings_export.geojson", "application/geo+json")
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun launchImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/json",
                    "application/geo+json",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
        }
        importDocLauncher.launch(intent)
    }

    private fun importDocument(uri: Uri) {
        val curData = dataset ?: return
        val newSightings = ArrayList<Sighting>()
        ioExecutor.execute {
            val res = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    BulkImporter.importStream(
                        context = this@MainActivity,
                        input = stream,
                        existing = curData.reports,
                        caseStore = caseStore,
                        parseSighting = { obj -> AtlasRepository.parseReportObject(obj) },
                        outNewSightings = newSightings,
                    )
                } ?: error("No input stream")
            }.getOrNull()

            runOnUiThread {
                if (res != null && res.added > 0) {
                    val merged = ArrayList<Sighting>(curData.reports.size + newSightings.size)
                    merged.addAll(curData.reports)
                    merged.addAll(newSightings)
                    merged.sortWith(
                        compareByDescending<Sighting> { it.date }.thenByDescending { it.id },
                    )
                    val updated = Dataset(merged, curData.videos, curData.social, curData.world)
                    dataset = updated
                    AtlasRepository.cache(updated)
                    if (yearStart == curData.minYear && yearEnd == curData.maxYear) {
                        yearStart = updated.minYear
                        yearEnd = updated.maxYear
                    }
                    statReports.text = String.format(Locale.US, "%,d", updated.reports.size)
                    statCountries.text = updated.countries.toString()
                    statYears.text = "${updated.minYear}–${updated.maxYear}"
                    updateTabLabels(updated)
                    buildTimeline(updated)
                    buildLegend()
                    refresh()
                    CyberSfx.confirm()
                    Toast.makeText(
                        this,
                        "Imported ${res.added} reports (${res.skippedDuplicates} duplicates skipped)",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (res != null) {
                    Toast.makeText(
                        this,
                        "No new reports added (${res.skippedDuplicates} duplicates skipped)",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun launchDocumentPicker(fileName: String, mimeType: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        exportDocLauncher.launch(intent)
    }

    private fun shareText(content: String, subject: String, mimeType: String = "text/plain") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, subject))
    }

    private fun openExternalUrl(rawUrl: String) {
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "No browser available for that link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writePendingExport(uri: Uri) {
        val format = pendingExportFormat ?: return
        val caseId = pendingExportCaseId
        val snapshot = ArrayList(lastFiltered)
        val src = when (tab) { 1 -> "video"; 2 -> "social"; else -> "nuforc" }
        val ds = dataset

        ioExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    when (format) {
                        "csv" -> BulkExporter.writeCsv(writer, snapshot, caseStore, src)
                        "geojson" -> BulkExporter.writeGeoJson(writer, snapshot, caseStore, src)
                        "case_md", "case_json" -> {
                            val cf = caseId?.let { caseStore.getCase(it) } ?: error("Case file not found")
                            val byId = HashMap<Int, Sighting>()
                            if (ds != null) {
                                for (s in ds.reports) byId[s.id] = s
                                for (s in ds.videos) byId[s.id] = s
                                for (s in ds.social) byId[s.id] = s
                            }
                            val text = if (format == "case_md") {
                                caseStore.exportCaseMarkdown(cf, byId)
                            } else {
                                caseStore.exportCaseJson(cf, byId)
                            }
                            writer.write(text)
                        }
                    }
                } ?: error("No output stream")
            }
            runOnUiThread {
                if (result.isSuccess) {
                    CyberSfx.confirm()
                    Toast.makeText(this, "Export saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
                }
                pendingExportFormat = null
                pendingExportCaseId = null
            }
        }
    }

    override fun onPause() {
        openSheet?.pausePlayer()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        todayKey = todayKeyString()
        openSheet?.resumePlayer()
    }

    /* ---------------- timeline ---------------- */

    private var baseYearStart = 1910
    private var baseYearEnd = 2026
    private var reportMinYear = 1910
    private var reportMaxYear = 2026

    private fun buildTimeline(data: Dataset) {
        val min = data.reports.minOfOrNull { it.year } ?: data.minYear
        val max = data.reports.maxOfOrNull { it.year } ?: data.maxYear
        reportMinYear = min
        reportMaxYear = max
        val counts = IntArray(max - min + 1)
        for (sighting in data.reports) {
            val index = sighting.year - min
            if (index in counts.indices) counts[index]++
        }
        timeline.setData(min, max, counts)
        baseYearStart = data.minYear
        baseYearEnd = data.maxYear
        resetTimelineUi()
    }

    private val playRunnable = object : Runnable {
        override fun run() {
            if (!playing) return
            if (playYear > playTo) {
                playing = false
                playYear = playTo
                updatePlayGlyph()
                return
            }
            applyTimelineYear(playYear, force = true)
            playYear += 1
            if (playYear > playTo) {
                playing = false
                playYear = playTo
                updatePlayGlyph()
                return
            }
            handler.postDelayed(this, if (fastPlay) 30L else 120L)
        }
    }

    private fun togglePlayback() {
        if (playing) {
            playing = false
            handler.removeCallbacks(playRunnable)
            updatePlayGlyph()
            return
        }
        if (dataset == null) return
        if (tab != 0) setTab(0)
        stopSpin()
        if (!timelineActive) {
            baseYearStart = yearStart
            baseYearEnd = yearEnd
        }
        val from = baseYearStart.coerceIn(reportMinYear, reportMaxYear)
        val to = baseYearEnd.coerceIn(from, reportMaxYear)
        playTo = to
        playYear = if (timelineActive) playYear.coerceIn(from, to) else from
        if (playYear >= playTo) playYear = from
        playing = true
        updatePlayGlyph()
        applyTimelineYear(playYear, force = true)
        playYear += 1
        if (playYear <= playTo) {
            handler.postDelayed(playRunnable, if (fastPlay) 30L else 120L)
        } else {
            playing = false
            playYear = playTo
            updatePlayGlyph()
        }
    }

    private fun updatePlayGlyph() {
        timelinePlay.text = if (playing) "❚❚" else "▶"
        timelinePlay.contentDescription =
            getString(if (playing) R.string.timeline_pause else R.string.timeline_play)
        timelinePlay.setTextColor(colorOf(if (playing) R.color.mint else R.color.text))
    }

    private fun resetTimelineUi() {
        playing = false
        handler.removeCallbacks(playRunnable)
        timelineActive = false
        timeline.clear()
        timelineYear.text = getString(R.string.timeline_all)
        timelineYear.contentDescription = getString(R.string.timeline_all)
        timelineYear.setTextColor(colorOf(R.color.muted))
        updatePlayGlyph()
    }

    private fun scrubTo(year: Int) {
        if (dataset == null) return
        val frac = (year - reportMinYear).toFloat() /
            (reportMaxYear - reportMinYear).coerceAtLeast(1)
        CyberSfx.scrubTick(frac)
        if (tab != 0) setTab(0)
        if (playing) {
            playing = false
            handler.removeCallbacks(playRunnable)
            updatePlayGlyph()
        }
        stopSpin()
        applyTimelineYear(year)
    }

    private fun applyTimelineYear(year: Int, force: Boolean = false) {
        val data = dataset ?: return
        val target = year.coerceIn(reportMinYear, reportMaxYear)
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastScrubAt < 55L) {
            playYear = target
            val from = (target - 1).coerceAtLeast(data.minYear)
            val to = (target + 1).coerceAtMost(data.maxYear)
            timeline.setPlayhead(target, from, to, true)
            globe.setYearWindow(from, to)
            return
        }
        lastScrubAt = now
        if (!timelineActive) {
            baseYearStart = yearStart
            baseYearEnd = yearEnd
            timelineActive = true
        }
        playYear = target
        yearStart = (target - 1).coerceAtLeast(data.minYear)
        yearEnd = (target + 1).coerceAtMost(data.maxYear)
        timeline.setPlayhead(target, yearStart, yearEnd, true)
        globe.setYearWindow(yearStart, yearEnd)
        timelineYear.text = "$target ×"
        timelineYear.contentDescription = getString(R.string.timeline_clear_hint, target)
        timelineYear.setTextColor(colorOf(R.color.mint))
        if (!panelOpen) {
            adapterDirtyWhileClosed = true
            if (force && !playing) {
                timelineRefresh = true
                refresh(updateGlobePins = false)
                timelineRefresh = false
            }
            return
        }
        timelineRefresh = true
        refresh(updateGlobePins = false)
        timelineRefresh = false
    }

    private fun clearTimeline() {
        val data = dataset ?: return
        resetTimelineUi()
        globe.clearYearWindow()
        yearStart = baseYearStart.coerceIn(data.minYear, data.maxYear)
        yearEnd = baseYearEnd.coerceIn(data.minYear, data.maxYear)
        refresh()
    }

    /* ---------------- near me ---------------- */

    private fun toggleNearMe() {
        if (nearLat.isFinite()) {
            when (nearRadiusKm) {
                50.0 -> nearRadiusKm = 100.0
                100.0 -> nearRadiusKm = 250.0
                250.0 -> nearRadiusKm = 500.0
                else -> {
                    nearLat = Double.NaN
                    nearLon = Double.NaN
                }
            }
            updateNearChip()
            updateClearVisibility()
            scheduleRefresh()
            return
        }
        nearRadiusKm = 100.0
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            locateNow()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
        }
    }

    private fun locateNow() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        var best: Location? = null
        try {
            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )) {
                val candidate = manager?.getLastKnownLocation(provider) ?: continue
                if (best == null || candidate.time > best.time) best = candidate
            }
        } catch (_: SecurityException) {
            best = null
        }
        val location = best
        if (location == null) {
            Toast.makeText(this, R.string.no_location, Toast.LENGTH_SHORT).show()
            return
        }
        nearLat = location.latitude
        nearLon = location.longitude
        stopSpin()
        globe.flyTo(nearLat, nearLon, 3.2)
        globe.zoomTo(3.2)
        updateNearChip()
        updateClearVisibility()
        scheduleRefresh()
    }

    private fun updateNearChip() {
        styleToggle(nearMe, nearLat.isFinite())
        nearMe.text = if (nearLat.isFinite()) {
            getString(R.string.near_active, nearRadiusKm.toInt())
        } else {
            getString(R.string.near_button)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            locateNow()
        } else {
            Toast.makeText(this, R.string.no_location_permission, Toast.LENGTH_SHORT).show()
        }
    }

    /* ---------------- random + saved + links ---------------- */

    private fun surpriseMe() {
        val pool = lastFiltered.filter { it.hasPosition }
        val source = if (pool.isNotEmpty()) {
            pool
        } else {
            dataset?.reports.orEmpty().filter { it.hasPosition }
        }
        if (source.isEmpty()) return
        stopSpin()
        openDetail(source.random())
    }

    private fun isNight(time: String): Boolean {
        val hour = time.substringBefore(':').toIntOrNull() ?: return false
        return hour >= 20 || hour < 5
    }

    private fun todayKeyString(): String {
        val now = Calendar.getInstance()
        return String.format(Locale.US, "%02d-%02d", now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
    }

    private fun toggleOnThisDay() {
        onThisDay = !onThisDay
        updateOnThisDay()
        updateClearVisibility()
        scheduleRefresh()
    }

    private fun updateOnThisDay() {
        onThisDayButton.text = getString(
            if (onThisDay) R.string.on_this_day_clear else R.string.on_this_day,
        ).uppercase(Locale.US)
        onThisDayButton.setTextColor(colorOf(if (onThisDay) R.color.mint else R.color.muted))
    }

    private fun reportIdFrom(intent: Intent?): Int? {
        val uri = intent?.data ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme != "sightings" && scheme != "https") return null
        return uri.lastPathSegment?.toIntOrNull() ?: uri.getQueryParameter("id")?.toIntOrNull()
    }

    private fun openReportById(id: Int) {
        val data = dataset
        if (data == null) {
            pendingReportId = id
            return
        }
        val sighting = (data.reports + data.videos + data.social).firstOrNull { it.id == id } ?: return
        openDetail(sighting)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = reportIdFrom(intent) ?: return
        if (dataset == null) pendingReportId = id else openReportById(id)
    }

    /* ---------------- stats ---------------- */

    private fun showStats() {
        val data = dataset ?: return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scroll = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(52f))
            setBackgroundResource(R.drawable.bg_sheet)
        }
        scroll.addView(card)
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.82f).toInt(),
            )
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(this).apply {
                text = getString(R.string.stats_title)
                typeface = Typeface.SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(colorOf(R.color.text))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            TextView(this).apply {
                text = "×"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(colorOf(R.color.dim))
                gravity = Gravity.CENTER
                minWidth = dp(44f).toInt()
                minHeight = dp(44f).toInt()
                contentDescription = getString(R.string.close)
                setOnClickListener { dialog.dismiss() }
            },
        )
        card.addView(head)
        card.addView(
            monoText(9f, colorOf(R.color.muted), 0.06f).apply {
                text = getString(
                    R.string.stats_totals,
                    String.format(Locale.US, "%,d", data.reports.size),
                    data.countries,
                    data.minYear,
                    data.maxYear,
                )
            },
        )

        val countries = data.reports.groupingBy { it.country }.eachCount()
            .entries.sortedByDescending { it.value }.take(8)
            .map { it.key to it.value }
        addStatBars(card, getString(R.string.stats_countries), countries)

        val shapes = data.shapes.take(8).map { shapeLabel(it.first) to it.second }
        addStatBars(card, getString(R.string.stats_shapes), shapes)

        card.addView(sectionLabel(getString(R.string.stats_decades)))
        val decadeStart = (data.minYear / 10) * 10
        val decadeBins = IntArray((data.maxYear - decadeStart) / 10 + 1)
        for (sighting in data.reports) {
            val index = (sighting.year - decadeStart) / 10
            if (index in decadeBins.indices) decadeBins[index]++
        }
        card.addView(
            HistogramView(this).apply {
                update(decadeBins, decadeStart, 10, data.minYear, data.maxYear)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(58f),
                ).apply { topMargin = dp(6f) }
            },
        )

        val timed = data.reports.count { it.time.isNotBlank() }
        val night = data.reports.count { it.time.isNotBlank() && isNight(it.time) }
        val nightPct = if (timed == 0) 0 else night * 100 / timed
        addStatLine(card, getString(R.string.stats_after_dark), "$night / $timed · $nightPct%")
        data.reports.groupingBy { it.year }.eachCount().maxByOrNull { it.value }?.let { busiest ->
            addStatLine(
                card,
                getString(R.string.stats_busiest),
                "${busiest.key} · ${String.format(Locale.US, "%,d", busiest.value)}",
            )
        }
        val first = data.reports.minByOrNull { it.date }
        val last = data.reports.maxByOrNull { it.date }
        if (first != null && last != null) {
            addStatLine(card, getString(R.string.stats_span), "${formatDate(first.date)} — ${formatDate(last.date)}")
        }

        dialog.show()
    }

    private fun addStatBars(container: LinearLayout, title: String, items: List<Pair<String, Int>>) {
        if (items.isEmpty()) return
        container.addView(sectionLabel(title))
        val peak = items.maxOf { it.second }.coerceAtLeast(1)
        for ((label, count) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7f), 0, 0)
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(colorOf(R.color.text))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                TextView(this).apply {
                    text = String.format(Locale.US, "%,d", count)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(colorOf(R.color.muted))
                },
            )
            container.addView(row)
            val fraction = (count.toFloat() / peak).coerceIn(0.03f, 1f)
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(3f),
                ).apply { topMargin = dp(4f) }
            }
            bar.addView(
                View(this).apply { setBackgroundColor(colorOf(R.color.mint)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraction),
            )
            bar.addView(
                View(this).apply { setBackgroundColor(0x2194C5D0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fraction),
            )
            container.addView(bar)
        }
    }

    private fun addStatLine(container: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, 0)
        }
        row.addView(
            monoText(8.5f, colorOf(R.color.dim), 0.14f).apply { text = label.uppercase(Locale.US) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(monoText(10f, colorOf(R.color.text), 0.04f).apply { text = value })
        container.addView(row)
    }

    private fun sectionLabel(text: String): TextView =
        monoText(9f, colorOf(R.color.dim), 0.18f).apply {
            this.text = text.uppercase(Locale.US)
            setPadding(0, dp(18f), 0, dp(2f))
        }

    private fun zoomLabel(zoom: Double): String =
        if (zoom < 10.0) String.format(Locale.US, "%.1f×", zoom)
        else String.format(Locale.US, "%.0f×", zoom)

    /* ---------------- filter sheet ---------------- */

    private fun showFilters() {
        val data = dataset ?: return
        val view = layoutInflater.inflate(R.layout.dialog_filters, null)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val eraChips = view.findViewById<LinearLayout>(R.id.dialogEraChips)
        val histogram = view.findViewById<HistogramView>(R.id.dialogHistogram)
        val range = view.findViewById<YearRangeView>(R.id.dialogYearRange)
        val rangeValue = view.findViewById<TextView>(R.id.dialogRangeValue)
        val shapeValue = view.findViewById<TextView>(R.id.dialogShapeValue)
        val shapeField = view.findViewById<LinearLayout>(R.id.dialogShapeField)
        val sortNew = view.findViewById<TextView>(R.id.dialogSortNew)
        val sortOld = view.findViewById<TextView>(R.id.dialogSortOld)
        val sourceNote = view.findViewById<TextView>(R.id.dialogSourceNote)
        val timeChips = view.findViewById<LinearLayout>(R.id.dialogTimeChips)

        val binStart = (data.minYear / 5) * 5
        val bins = histogramBins(data, binStart)
        histogram.update(bins, binStart, 5, yearStart, yearEnd)

        range.setBounds(data.minYear, data.maxYear)
        range.setRange(yearStart, yearEnd)
        rangeValue.text = getString(R.string.range_value, yearStart, yearEnd)

        range.onChange = { start, end ->
            yearStart = start
            yearEnd = end
            val frac = (end - data.minYear).toFloat() /
                (data.maxYear - data.minYear).coerceAtLeast(1)
            CyberSfx.scrubTick(frac)
            rangeValue.text = getString(R.string.range_value, start, end)
            histogram.update(bins, binStart, 5, start, end)
            updateClearVisibility()
            scheduleRefresh()
        }

        renderEraChips(eraChips)
        renderTimeChips(timeChips)
        shapeValue.text = if (shape == null) getString(R.string.all_shapes) else shapeLabel(shape.orEmpty())
        sourceNote.text = getString(R.string.report_source)

        fun renderSort() {
            styleSort(sortNew, newestFirst)
            styleSort(sortOld, !newestFirst)
        }
        renderSort()
        sortNew.setOnClickListener {
            newestFirst = true
            renderSort()
            scheduleRefresh()
        }
        sortOld.setOnClickListener {
            newestFirst = false
            renderSort()
            scheduleRefresh()
        }

        shapeField.setOnClickListener {
            val shapes = data.shapes.take(18)
            val labels = ArrayList<String>()
            labels.add(getString(R.string.all_shapes))
            for (entry in shapes) {
                labels.add(getString(R.string.shape_option, shapeLabel(entry.first), entry.second))
            }
            AlertDialog.Builder(this)
                .setItems(labels.toTypedArray()) { _, which ->
                    val chosen = if (which == 0) null else shapes[which - 1].first
                    shape = chosen
                    shapeValue.text = if (chosen == null) {
                        getString(R.string.all_shapes)
                    } else {
                        shapeLabel(chosen)
                    }
                    updateClearVisibility()
                    scheduleRefresh()
                }
                .show()
        }

        view.findViewById<TextView>(R.id.dialogClear).setOnClickListener {
            resetFilters(clearSearch = false)
            range.setRange(yearStart, yearEnd)
            rangeValue.text = getString(R.string.range_value, yearStart, yearEnd)
            histogram.update(bins, binStart, 5, yearStart, yearEnd)
            shapeValue.text = getString(R.string.all_shapes)
            renderEraChips(eraChips)
            renderTimeChips(timeChips)
            renderSort()
        }

        view.findViewById<TextView>(R.id.dialogDone).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.filtersClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun renderEraChips(container: LinearLayout) {
        container.removeAllViews()
        container.addView(
            choiceChip(getString(R.string.all), era == null) {
                era = null
                renderEraChips(container)
                updateClearVisibility()
                scheduleRefresh()
            },
        )
        for (item in Era.entries) {
            container.addView(
                choiceChip(item.short, era == item) {
                    era = if (era == item) null else item
                    renderEraChips(container)
                    updateClearVisibility()
                    scheduleRefresh()
                },
            )
        }
    }

    private fun renderTimeChips(container: LinearLayout) {
        container.removeAllViews()
        container.addView(
            choiceChip(getString(R.string.any_time), !nightOnly) {
                nightOnly = false
                renderTimeChips(container)
                updateClearVisibility()
                scheduleRefresh()
            },
        )
        container.addView(
            choiceChip(getString(R.string.after_dark), nightOnly) {
                nightOnly = true
                renderTimeChips(container)
                updateClearVisibility()
                scheduleRefresh()
            },
        )
    }

    private fun choiceChip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        val view = monoText(9f, if (active) colorOf(R.color.mint) else colorOf(R.color.muted), 0.08f)
        view.text = text.uppercase(Locale.US)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        view.setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
        view.setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip)
        view.isClickable = true
        view.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dp(6f)
        view.layoutParams = params
        return view
    }

    private fun styleSort(view: TextView, active: Boolean) {
        view.setTextColor(if (active) colorOf(R.color.mint) else colorOf(R.color.dim))
        view.setBackgroundResource(if (active) R.drawable.bg_chip_on else 0)
    }

    private fun histogramBins(data: Dataset, binStart: Int): IntArray {
        val size = ((data.maxYear - binStart) / 5) + 1
        val bins = IntArray(size)
        for (sighting in data.reports) {
            val index = (sighting.year - binStart) / 5
            if (index in bins.indices) bins[index]++
        }
        return bins
    }

    private companion object {
        const val KEY_SAVED = "saved_ids"
        const val KEY_PANEL_OPEN = "panel_open"
        const val REQUEST_LOCATION = 41
    }
}
