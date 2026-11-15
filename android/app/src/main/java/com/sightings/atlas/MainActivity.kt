package com.sightings.atlas

import android.Manifest
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
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
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
import android.widget.TextView
import android.widget.Toast
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
import com.sightings.atlas.data.Dataset
import com.sightings.atlas.data.LiveTikTokFetcher
import com.sightings.atlas.model.Era
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.coordLabel
import com.sightings.atlas.model.distanceKm
import com.sightings.atlas.model.eraOf
import com.sightings.atlas.model.formatDate
import com.sightings.atlas.model.shapeLabel
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
import java.util.Calendar
import java.util.Locale

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
    private var lastUkFetchAt = 0L
    private var panelOpen = false

    private val savedIds = HashSet<Int>()
    private val prefs by lazy { getSharedPreferences("atlas_prefs", Context.MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }
    private val hoverRefreshRunnable = Runnable { refresh(updateGlobePins = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        panelOpen = prefs.getBoolean(KEY_PANEL_OPEN, false)
        panelToggle.setOnClickListener {
            panelOpen = !panelOpen
            prefs.edit().putBoolean(KEY_PANEL_OPEN, panelOpen).apply()
            applyPanelState()
        }
        applyPanelState()
        savedIds.addAll(
            prefs.getStringSet(KEY_SAVED, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() },
        )
        todayKey = todayKeyString()
        pendingReportId = reportIdFrom(intent)
        buildLegend()

        val adapter = SightingAdapter(videoStyle = false) { openDetail(it) }
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
            val readout = getString(
                R.string.view_readout,
                coordLabel(lat, lon),
                zoomLabel(zoom),
            )
            if (readout != lastReadoutText) {
                lastReadoutText = readout
                viewReadout.text = readout
            }
            val nowHoveringUk = !spinning && LiveTikTokFetcher.isInUkRegion(lat, lon)
            if (nowHoveringUk != hoveringUk) {
                hoveringUk = nowHoveringUk
                if (nowHoveringUk) {
                    triggerUkTikTokFetch()
                }
                scheduleHoverRefresh()
            } else if ((nowHoveringUk || (!spinning && tab == 2)) && !globe.isInteracting) {
                scheduleHoverRefresh()
            }
        }
        globe.onInteractionEnd = {
            if (!spinning && (hoveringUk || tab == 2)) {
                scheduleHoverRefresh()
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
        val adapter = SightingAdapter(videoStyle = index != 0) { openDetail(it) }
        this.adapter = adapter
        list.adapter = adapter
        refresh()
    }

    private fun applyPanelState() {
        panel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        panelToggle.text = getString(if (panelOpen) R.string.show_globe else R.string.show_reports)
        panelToggle.contentDescription = getString(
            if (panelOpen) R.string.show_globe_desc else R.string.show_reports_desc,
        )
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
        legend.addView(
            legendChip("All", colorOf(R.color.mint), era == null) {
                era = null
                buildLegend()
                updateClearVisibility()
                scheduleRefresh()
            },
        )
        val savedText = if (savedIds.isEmpty()) "SAVED" else "SAVED ${savedIds.size}"
        legend.addView(
            legendChip(savedText, colorOf(R.color.mint), savedOnly, dot = false) {
                savedOnly = !savedOnly
                buildLegend()
                updateClearVisibility()
                scheduleRefresh()
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
        if (savedOnly && sighting.id !in savedIds) return false
        if (onThisDay && !sighting.date.endsWith(todayKey)) return false
        if (nightOnly && sighting.video == null && !isNight(sighting.time)) return false
        if (nearLat.isFinite() && sighting.video == null) {
            if (!sighting.hasPosition) return false
            if (distanceKm(nearLat, nearLon, sighting.lat, sighting.lon) > nearRadiusKm) return false
        }
        if (text.isEmpty()) return true
        return sighting.searchHaystack.contains(text)
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
        val sortByDistance = !spinning && (tab == 2 || hoveringUk) && hoverLat.isFinite()
        if (sortByDistance) {
            val ranked = ArrayList<Pair<Sighting, Double>>(filtered.size)
            for (sighting in filtered) {
                val distance = if (sighting.hasPosition) {
                    distanceKm(hoverLat, hoverLon, sighting.lat, sighting.lon)
                } else {
                    Double.MAX_VALUE
                }
                ranked.add(sighting to distance)
            }
            ranked.sortWith(
                compareBy<Pair<Sighting, Double>> { it.second }.thenByDescending { it.first.date },
            )
            for (i in filtered.indices) filtered[i] = ranked[i].first
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

        if (hoveringUk && tab == 0) {
            val ukTikToks = data.social
                .filter { it.hasPosition && LiveTikTokFetcher.isInUkRegion(it.lat, it.lon) && matches(it, text) }
                .sortedBy { distanceKm(hoverLat, hoverLon, it.lat, it.lon) }
            if (ukTikToks.isNotEmpty()) {
                filtered.addAll(0, ukTikToks)
            }
        }

        lastFiltered = filtered
        adapter?.origin = when {
            nearLat.isFinite() -> doubleArrayOf(nearLat, nearLon)
            hoveringUk && hoverLat.isFinite() -> doubleArrayOf(hoverLat, hoverLon)
            else -> null
        }

        adapter?.submit(filtered.take(limit))
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
        val active = era != null || shape != null || savedOnly || nightOnly || onThisDay || nearLat.isFinite() ||
            yearStart > (dataset?.minYear ?: yearStart) ||
            yearEnd < (dataset?.maxYear ?: yearEnd) ||
            query.isNotBlank()
        clearFilters.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun resetFilters(clearSearch: Boolean) {
        era = null
        shape = null
        savedOnly = false
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
                scheduleRefresh()
            }
        }
    }

    private fun triggerUkTikTokFetch() {
        val data = dataset ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastUkFetchAt < 30_000L) return
        lastUkFetchAt = now
        val existingIds = data.social.mapNotNull { it.video?.id }.toSet()
        LiveTikTokFetcher.fetchUkClipsAsync(this, existingIds) { newClips ->
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
                }
            }
        }
    }

    private fun openDetail(sighting: Sighting) {
        // Freeze the spin so the place you opened does not drift while you zoom in.
        stopSpin()
        globe.selected = sighting
        val sheet = ReportSheet(
            this,
            sighting,
            saved = sighting.id in savedIds,
            onSaveToggle = { saved -> setSaved(sighting, saved) },
        )
        sheet.onDismiss = {
            globe.selected = null
            if (openSheet === sheet) openSheet = null
        }
        openSheet = sheet
        sheet.show()
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

    private fun setSaved(sighting: Sighting, saved: Boolean) {
        if (saved) savedIds.add(sighting.id) else savedIds.remove(sighting.id)
        prefs.edit()
            .putStringSet(KEY_SAVED, savedIds.map { it.toString() }.toSet())
            .apply()
        buildLegend()
        if (savedOnly) scheduleRefresh()
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
