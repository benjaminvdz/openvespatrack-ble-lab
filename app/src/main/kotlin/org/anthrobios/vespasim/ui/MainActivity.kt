package org.anthrobios.vespasim.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.print.PrintAttributes
import android.print.PrintJob
import android.print.PrintManager
import android.provider.Settings
import android.text.InputType
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.VespaApplication
import org.anthrobios.vespasim.ble.BleCapabilities
import org.anthrobios.vespasim.core.PlannedFrame
import org.anthrobios.vespasim.core.Planner
import org.anthrobios.vespasim.core.Presets
import org.anthrobios.vespasim.core.ProfileCodec
import org.anthrobios.vespasim.core.RadioMode
import org.anthrobios.vespasim.core.RunMode
import org.anthrobios.vespasim.core.RunRecord
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.service.ServicePresentation
import org.anthrobios.vespasim.service.LiveSnapshot
import org.anthrobios.vespasim.service.SimulationService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Local experiment UI. A saved profile is immutable within each run. */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val repository get() = (application as VespaApplication).repository
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var bound = false
    private var service: SimulationService? = null
    private var live = LiveSnapshot()
    private var tab = 0
    private var profiles: List<SimulationProfile> = emptyList()
    private data class EncodedProfile(val json: String, val sha256: String)
    // Populated together with profiles on the worker. Capture profiles may contain 256 sources.
    private var encodedProfiles: Map<String, EncodedProfile> = emptyMap()
    private var selectedId: String? = null
    private var runs: List<RunRecord> = emptyList()
    private var selectedRunId: String? = null
    private var runPageIndex = 0
    private var runPageCount = 0
    private var totalRunCount = 0
    private var selectedMode = RunMode.DRY_RUN
    private var speed = 1
    private var draftJson: String? = null
    private var draftProfileId: String? = null
    private var draftBasic: List<String>? = null
    private var selectedPlan: List<PlannedFrame> = emptyList()
    private var selectedPlanProfileId: String? = null
    private var planGeneration = 0
    private var livePlanRunId: String? = null
    private var historyCursor = 0L
    private var nextHistoryCursor = 0L
    private val historyBack = mutableListOf<Long>()
    private var historyGeneration = 0
    private var pendingStart: StartRequest? = null
    private var requestedReplayId: String? = null
    private var pendingExport: ExportRequest? = null
    private var reportWebView: WebView? = null
    private var printJob: PrintJob? = null

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView
    private lateinit var startupNotice: TextView
    private var initializationPollPosted = false
    private lateinit var tabs: LinearLayout
    private var chart: ScenarioChart? = null
    private var chartLegend: TextView? = null
    private var liveStatus: TextView? = null
    private var liveSummary: TextView? = null
    private var livePayload: TextView? = null
    private var runProgress: ProgressBar? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var pauseButton: Button? = null
    private var jsonEditor: EditText? = null
    private var basicName: EditText? = null
    private var basicDuration: EditText? = null
    private var basicSlot: EditText? = null
    private var basicLoss: EditText? = null
    private var basicSeed: EditText? = null
    private var historySummary: TextView? = null
    private var historyEvents: TextView? = null
    private var historyPage: TextView? = null
    private var historyRunSelector: Spinner? = null
    private var runProfileSelector: Spinner? = null
    private val modeButtons = mutableListOf<RadioButton>()
    private var speedSelector: Spinner? = null
    private var nameRecoveryText: TextView? = null
    private var nameRecoveryButton: Button? = null

    private data class StartRequest(val profile: SimulationProfile, val mode: RunMode, val speed: Int)
    private data class ExportRequest(val kind: String, val name: String, val profileJson: String? = null, val runId: String? = null, val profileId: String? = null, val profileHash: String? = null)

    private val listener: (LiveSnapshot) -> Unit = { snapshot ->
        val wasRunning = live.running
        live = snapshot
        updateLive()
        val run = snapshot.run
        if (run != null && snapshot.running && livePlanRunId != run.id && tab == 0) {
            livePlanRunId = run.id
            computePlan(run.profile)
        }
        if (wasRunning && !snapshot.running) {
            showNotice(ServicePresentation.describe(this, snapshot.message).ifBlank { tr(R.string.main_finished) })
            if (tab == 0) selectedProfile()?.let { computePlan(it) }
            refreshRuns()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SimulationService.LocalBinder).service
            service?.addListener(listener)
            live = service?.snapshot() ?: LiveSnapshot()
            updateLive()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            updateLive()
            showNotice(tr(R.string.main_disconnected), true)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Appearance.wrapContext(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("ui", MODE_PRIVATE)
        tab = savedInstanceState?.getInt("tab") ?: 0
        selectedId = savedInstanceState?.getString("profile") ?: preferences.getString("profile", null)
        selectedRunId = savedInstanceState?.getString("run")
        runPageIndex = savedInstanceState?.getInt("runPage", 0) ?: 0
        draftJson = savedInstanceState?.getString("draft")
        draftProfileId = savedInstanceState?.getString("draftProfile")
        draftBasic = savedInstanceState?.getStringArrayList("draftBasic")
        selectedMode = savedInstanceState?.getString("mode")?.let { runCatching { RunMode.valueOf(it) }.getOrNull() } ?: RunMode.DRY_RUN
        speed = savedInstanceState?.getInt("speed", 1) ?: 1
        requestedReplayId = savedInstanceState?.getString("requestedReplay")
        savedInstanceState?.getString("exportKind")?.let {
            pendingExport = ExportRequest(it, savedInstanceState.getString("exportName") ?: "vespa-export", runId = savedInstanceState.getString("exportRun"), profileId = savedInstanceState.getString("exportProfileId"), profileHash = savedInstanceState.getString("exportProfileHash"))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom else insets.systemWindowInsetBottom
                view.setPadding(dp(16), dp(12) + top, dp(16), dp(8) + bottom)
                insets
            }
        }
        setContentView(root)
        root.addView(text(tr(R.string.main_title), 21f, TEAL, true))
        root.addView(text(tr(R.string.main_subtitle), 12f, MUTED))
        root.addView(button(tr(R.string.main_beacon), "open_beacon") {
            captureDraft()
            startActivity(Intent(this, BeaconActivity::class.java))
        }.apply { setTag(R.id.anb_primary_action, true) }, LinearLayout.LayoutParams(-1, -2))
        root.addView(text(tr(R.string.main_beacon_hint), 12f, MUTED))
        root.addView(button(tr(R.string.main_settings), "open_settings") {
            captureDraft()
            startActivity(Intent(this, AppearanceActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(button(tr(R.string.main_capture), "open_capture") {
            if (live.running || pendingStart != null) showNotice(tr(R.string.main_stop_first), true)
            else {
                captureDraft()
                startActivity(Intent(this, CaptureActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-1, -2))
        notice = text(tr(R.string.main_profiles_loading), 12f, MUTED).apply { setPadding(0, dp(8), 0, dp(8)); tag = "notice" }
        root.addView(notice)
        startupNotice = text(tr(R.string.main_startup_warning), 12f, AMBER).apply { visibility = View.GONE }
        root.addView(startupNotice)
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(20)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text(tr(R.string.main_footer), 10f, MUTED))
        renderTabs()
        takeReplayIntent(intent)
        refreshProfiles()
        Appearance.styleTree(root)
    }

    override fun onResume() {
        super.onResume()
        if (Appearance.needsRecreation(this)) {
            captureDraft()
            recreate()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDraft()
        takeReplayIntent(intent)
        refreshProfiles()
    }

    /** Navigation only: preparing a captured profile never starts a radio or a dry-run. */
    private fun takeReplayIntent(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CAPTURE_REPLAY_PROFILE_ID) ?: return
        intent.removeExtra(EXTRA_CAPTURE_REPLAY_PROFILE_ID)
        if (id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) requestedReplayId = id
        else showNotice(tr(R.string.main_invalid_replay), true)
    }

    private fun selectRequestedReplay() {
        val id = requestedReplayId ?: return
        requestedReplayId = null
        if (profiles.none { it.id == id }) {
            showNotice(tr(R.string.main_replay_missing), true)
            return
        }
        if (live.running || pendingStart != null) {
            showNotice(tr(R.string.main_replay_saved_busy), true)
            return
        }
        val choose = {
            selectedId = id; tab = 0; selectedMode = RunMode.DRY_RUN; speed = 1
            draftJson = null; draftProfileId = null; draftBasic = null
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("profile", id).apply()
            renderTabs(); render()
            showNotice(tr(R.string.main_replay_loaded))
        }
        if (draftJson != null || draftBasic != null) {
            AlertDialog.Builder(this).setTitle(tr(R.string.main_open_replay))
                .setMessage(tr(R.string.main_close_edit))
                .setPositiveButton(tr(R.string.main_open_replay)) { _, _ -> choose() }
                .setNegativeButton(tr(R.string.main_keep_edit), null).show()
        } else choose()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, SimulationService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) showNotice(tr(R.string.main_service_unavailable), true)
    }

    override fun onStop() {
        service?.removeListener(listener)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdown()
        reportWebView?.destroy()
        reportWebView = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureDraft()
        outState.putInt("tab", tab)
        outState.putString("profile", selectedId)
        outState.putString("run", selectedRunId)
        outState.putInt("runPage", runPageIndex)
        outState.putString("draft", draftJson)
        outState.putString("draftProfile", draftProfileId)
        draftBasic?.let { outState.putStringArrayList("draftBasic", ArrayList(it)) }
        outState.putString("mode", selectedMode.name)
        outState.putInt("speed", speed)
        outState.putString("requestedReplay", requestedReplayId)
        pendingExport?.let { outState.putString("exportKind", it.kind); outState.putString("exportName", it.name); outState.putString("exportRun", it.runId); outState.putString("exportProfileId", it.profileId); outState.putString("exportProfileHash", it.profileHash) }
        super.onSaveInstanceState(outState)
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        listOf(tr(R.string.main_tab_test), tr(R.string.main_tab_profiles), tr(R.string.main_tab_logs), tr(R.string.main_tab_phone)).forEachIndexed { index, title ->
            tabs.addView(button(title, "tab_$index") {
                captureDraft(); tab = index; renderTabs(); render()
            }.apply {
                setTag(R.id.anb_primary_action, tab == index)
                setSingleLine(false)
                minLines = 2
                maxLines = Int.MAX_VALUE
                ellipsize = null
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setTextColor(if (tab == index) colors.onAccent else TEXT)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (tab == index) TEAL else PANEL)
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        Appearance.styleTree(tabs)
    }

    private fun render() {
        if (destroyed) return
        content.removeAllViews()
        chart = null; chartLegend = null; liveStatus = null; liveSummary = null; livePayload = null; runProgress = null
        startButton = null; stopButton = null; pauseButton = null
        jsonEditor = null; basicName = null; basicDuration = null; basicSlot = null; basicLoss = null; basicSeed = null
        historySummary = null; historyEvents = null; historyPage = null; historyRunSelector = null
        runProfileSelector = null; modeButtons.clear(); speedSelector = null
        nameRecoveryText = null; nameRecoveryButton = null
        when (tab) { 0 -> renderRun(); 1 -> renderProfiles(); 2 -> renderHistory(); else -> renderHardware() }
        Appearance.styleTree(root)
    }

    private fun selectedProfile(): SimulationProfile? = profiles.firstOrNull { it.id == selectedId }

    private fun profileSpinner(parent: LinearLayout) {
        parent.addView(text(tr(R.string.main_saved_profile), 12f, MUTED))
        val spinner = Spinner(this).apply { tag = "profile_selector" }
        if (tab == 0) runProfileSelector = spinner
        spinner.adapter = spinnerAdapter(profiles.map { MainPresentation.profileName(this, it) })
        val index = profiles.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        spinner.setSelection(index, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val profile = profiles.getOrNull(position) ?: return
                if (selectedId != profile.id) {
                    captureDraft()
                    val old = selectedProfile()
                    val changedJson = draftProfileId == selectedId && draftJson != null && draftJson != encodedProfiles[old?.id]?.json
                    val oldBasics = old?.let { listOf(it.name, it.durationMs.toString(), it.slotMs.toString(), it.lossPercent.toString(), it.seed.toString()) }
                    val changedBasics = draftProfileId == selectedId && draftBasic != null && draftBasic != oldBasics
                    val select = {
                        selectedId = profile.id; draftJson = null; draftProfileId = null; draftBasic = null
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("profile", selectedId).apply()
                        render()
                    }
                    if (changedJson || changedBasics) {
                        AlertDialog.Builder(this@MainActivity).setTitle(tr(R.string.main_unsaved))
                            .setMessage(tr(R.string.main_discard_edit))
                            .setPositiveButton(tr(R.string.main_switch)) { _, _ -> select() }
                            .setNegativeButton(tr(R.string.main_back)) { _, _ -> spinner.setSelection(profiles.indexOfFirst { it.id == selectedId }.coerceAtLeast(0), false) }
                            .setOnCancelListener { spinner.setSelection(profiles.indexOfFirst { it.id == selectedId }.coerceAtLeast(0), false) }.show()
                    } else select()
                }
            }
        }
        parent.addView(spinner, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun renderRun() {
        profileSpinner(content)
        val profile = selectedProfile()
        content.addView(text(profile?.let { MainPresentation.profileDescription(this, it) } ?: tr(R.string.main_create_first), 13f, MUTED))
        profile?.radio?.localName?.let { expected ->
            content.addView(text(tr(R.string.main_temporary_name, expected), 12f, AMBER))
            row(content, button(tr(R.string.main_copy_name), "copy_advertised_name") {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr(R.string.main_clipboard_name), expected))
                showNotice(tr(R.string.main_copied_name, expected))
            }, button(tr(R.string.main_bluetooth_settings), "configure_advertised_name") {
                try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                catch (failure: Exception) { showNotice(diagnostic(failure), true) }
            })
        }
        renderNameRecovery()
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; setPadding(0, dp(10), 0, dp(8)) }
        val dry = RadioButton(this).apply { id = View.generateViewId(); text = tr(R.string.main_mode_dry); setTextColor(TEAL); tag = "mode_dry" }
        val real = RadioButton(this).apply { id = View.generateViewId(); text = tr(R.string.main_mode_ble); setTextColor(AMBER); tag = "mode_ble" }
        modeButtons += listOf(dry, real)
        modeGroup.addView(dry); modeGroup.addView(real)
        modeGroup.check(if (selectedMode == RunMode.DRY_RUN) dry.id else real.id)
        val modeHint = text("", 12f, MUTED)
        fun modeChanged() {
            modeHint.text = if (selectedMode == RunMode.DRY_RUN) tr(R.string.main_dry_hint) else tr(R.string.main_ble_hint)
            startButton?.text = if (selectedMode == RunMode.BLE) tr(R.string.main_start_ble) else tr(R.string.main_start_dry)
        }
        modeGroup.setOnCheckedChangeListener { _, checked -> selectedMode = if (checked == real.id) RunMode.BLE else RunMode.DRY_RUN; modeChanged() }
        content.addView(modeGroup); content.addView(modeHint)
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedRow.addView(text(tr(R.string.main_speed), 12f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        val speeds = listOf(1, 5, 10, 25, 100)
        val speedSpinner = Spinner(this).apply { adapter = spinnerAdapter(speeds.map { "${it}×" }); setSelection(speeds.indexOf(speed).coerceAtLeast(0)) }
        speedSelector = speedSpinner
        speedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { speed = speeds[position] }
        }
        speedRow.addView(speedSpinner, LinearLayout.LayoutParams(dp(105), dp(48))); content.addView(speedRow)
        row(content,
            button(tr(R.string.main_start_dry), "start_run") { requestStart() }.also { startButton = it },
            button(tr(R.string.main_stop), "stop_run") { service?.stopRun() }.also { stopButton = it },
        )
        pauseButton = button(tr(R.string.main_pause), "pause_run") { service?.setPaused(!live.paused) }.also { content.addView(it) }
        liveStatus = text("", 15f, TEAL, true).also { content.addView(it) }
        runProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(TEAL) }.also { content.addView(it, LinearLayout.LayoutParams(-1, dp(16))) }
        liveSummary = text("", 12f, MUTED).also { content.addView(it) }
        heading(tr(R.string.main_planned_rssi))
        content.addView(text(tr(R.string.main_payload_warning), 12f, AMBER))
        chart = ScenarioChart(this).also { content.addView(it, LinearLayout.LayoutParams(-1, dp(225))) }
        chartLegend = text("", 11f, MUTED).also { content.addView(it) }
        content.addView(text(tr(R.string.main_timeline_legend), 11f, MUTED))
        livePayload = text(tr(R.string.main_choose_plan), 12f, TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }.also { content.addView(it) }
        row(content, button(tr(R.string.main_edit_profile), "edit_profile") { tab = 1; renderTabs(); render() }, button(tr(R.string.main_view_logs), "open_logs") { live.run?.let { selectedRunId = it.id }; tab = 2; renderTabs(); render() })
        modeChanged()
        val shown = if (live.running) live.run?.profile ?: profile else profile
        if (shown != null) computePlan(shown)
        updateLive()
    }

    private fun computePlan(profile: SimulationProfile) {
        val generation = ++planGeneration
        io({ Planner.plan(profile) }) { plan ->
            if (generation != planGeneration) return@io
            selectedPlan = plan; selectedPlanProfileId = profile.id
            chart?.setPlan(plan, profile.durationMs)
            if (!live.running && live.run?.profile != profile) chart?.setCursor(null)
            val tracks = plan.filter { it.payloadHex != null }.mapNotNull { it.trackId }.distinct()
            val labels = tracks.joinToString("  ·  ")
            val legend = SpannableString(labels)
            var offset = 0
            tracks.forEachIndexed { index, name ->
                legend.setSpan(ForegroundColorSpan(chart?.trackColor(index) ?: MUTED), offset, offset + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                offset += name.length + 5
            }
            chartLegend?.text = legend
            if (!live.running) livePayload?.text = tr(R.string.main_plan_summary, plan.size, profile.tracks.size, manufacturerDescription(profile), profile.radio.mode, plan.firstOrNull { it.payloadHex != null }?.let(::frameDescription) ?: tr(R.string.main_only_silence))
        }
    }

    private fun updateLive() {
        updateNameRecovery()
        if (tab != 0) return
        startButton?.isEnabled = (application as VespaApplication).initializationComplete && service != null && !live.running && selectedProfile() != null
        stopButton?.isEnabled = live.running
        pauseButton?.isEnabled = live.running && !live.preparingStart && !live.preparingName && !live.restoringName
        pauseButton?.text = if (live.paused) tr(R.string.main_resume) else tr(R.string.main_pause)
        modeButtons.forEach { it.isEnabled = !live.running }
        speedSelector?.isEnabled = !live.running
        runProfileSelector?.isEnabled = !live.running
        val run = live.run
        liveStatus?.text = when {
            live.preparingStart -> tr(R.string.main_state_validating)
            live.preparingName -> tr(R.string.main_state_renaming)
            live.restoringName -> tr(R.string.main_state_restoring)
            live.running && live.paused -> tr(R.string.main_state_paused)
            live.running && run?.mode == RunMode.BLE -> tr(R.string.main_state_advertising, MainPresentation.profileName(this, run.profile))
            live.running -> tr(R.string.main_state_dry)
            run != null -> "${statusName(run.status)} · ${MainPresentation.profileName(this, run.profile)}"
            service == null -> tr(R.string.main_service_connecting)
            else -> tr(R.string.main_ready)
        }
        liveStatus?.setTextColor(if (run?.mode == RunMode.BLE && live.running) AMBER else TEAL)
        val message = ServicePresentation.describe(this, live.message)
        liveSummary?.text = if (live.preparingStart) message else live.summary?.let { tr(R.string.main_live_summary, it.eventCount, it.controllerAccepted, it.simulatedFrames, it.skippedLate, it.errorCount, message) } ?: message
        val showRunFrame = !live.preparingStart && run != null && (live.running || run.profile == selectedProfile())
        live.currentFrame?.takeIf { showRunFrame }?.let { frame ->
            runProgress?.progress = ((frame.atMs.toDouble() / (run?.profile?.durationMs ?: 1L).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
            chart?.setCursor(frame.atMs)
            livePayload?.text = tr(R.string.main_live_frame, frame.sequence, frame.atMs, frame.trackId ?: "—", frameDescription(frame))
        }
        if (showRunFrame && run.status == RunStatus.COMPLETED) runProgress?.progress = 1000
        if (!showRunFrame) { runProgress?.progress = 0; chart?.setCursor(null) }
    }

    private fun requestStart() {
        if (live.running) return
        val profile = selectedProfile() ?: return showNotice(tr(R.string.main_select_saved), true)
        if (service == null) return showNotice(tr(R.string.main_wait_service), true)
        val request = StartRequest(profile, selectedMode, if (selectedMode == RunMode.BLE) 1 else speed)
        if (request.mode == RunMode.BLE) {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) { pendingStart = request; requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS); return }
        }
        startPrepared(request)
    }

    @SuppressLint("MissingPermission")
    private fun startPrepared(request: StartRequest) {
        try {
            val currentService = service ?: error(tr(R.string.main_service_retry))
            require((application as VespaApplication).initializationComplete) { tr(R.string.main_wait_storage) }
            require(!currentService.snapshot().running) { tr(R.string.main_already_running) }
            // The service validates and freezes the full profile on its worker before any radio action.
            if (request.mode == RunMode.BLE) {
                if (Build.VERSION.SDK_INT >= 31) require(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { tr(R.string.main_need_ble_permission) }
                // Hardware availability and name preparation run on the service worker.
            }
            // The service serializes its started/foreground lifecycle before starting the run.
            currentService.startRun(request.profile, request.mode, request.speed)
            showNotice(if (request.mode == RunMode.BLE) tr(R.string.main_start_requested) else tr(R.string.main_dry_started))
        } catch (failure: Exception) { showNotice(diagnostic(failure), true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NAME_RECOVERY) {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                showNotice(tr(R.string.main_recovery_permission), true)
            else service?.restoreBluetoothName()
            return
        }
        if (requestCode != REQUEST_PERMISSIONS) return
        val request = pendingStart ?: return
        pendingStart = null
        if (Build.VERSION.SDK_INT >= 31 && listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            showNotice(tr(R.string.main_permission_denied), true)
        } else startPrepared(request)
    }

    private fun renderProfiles() {
        profileSpinner(content)
        row(content, button(tr(R.string.main_import_json), "import_profile") { importProfile() }, button(tr(R.string.main_export_json), "export_profile") {
            selectedProfile()?.let {
                val encoded = encodedProfiles[it.id] ?: return@let
                beginExport(ExportRequest("profile", UiLogic.filename(it.name, "json"), encoded.json, profileId = it.id, profileHash = encoded.sha256))
            }
        })
        row(content, button(tr(R.string.main_new_copy), "clone_profile") { cloneSelected() }, button(tr(R.string.main_choose_example), "preset_profile") { choosePreset() })
        content.addView(button(tr(R.string.main_create_tag), "create_nano_profile") { createNanoProfile() })
        val profile = selectedProfile() ?: return
        content.addView(text(tr(R.string.main_edit_copy_hint), 12f, MUTED))
        heading(tr(R.string.main_basic_settings))
        val basicDraft = draftBasic.takeIf { draftProfileId == profile.id && it?.size == 5 }
        basicName = field(tr(R.string.main_name), basicDraft?.get(0) ?: profile.name, false, "profile_name")
        basicDuration = field(tr(R.string.main_duration), basicDraft?.get(1) ?: profile.durationMs.toString(), true, "profile_duration")
        basicSlot = field(tr(R.string.main_slot), basicDraft?.get(2) ?: profile.slotMs.toString(), true, "profile_slot")
        basicLoss = field(tr(R.string.main_loss), basicDraft?.get(3) ?: profile.lossPercent.toString(), true, "profile_loss")
        basicSeed = field(tr(R.string.main_seed), basicDraft?.get(4) ?: profile.seed.toString(), true, "profile_seed")
        content.addView(button(tr(R.string.main_apply_basic), "apply_basic") {
            val editor = jsonEditor ?: return@button
            val before = editor.text.toString()
            val id = selectedId
            val basics = listOf(basicName?.text.toString(), basicDuration?.text.toString(), basicSlot?.text.toString(), basicLoss?.text.toString(), basicSeed?.text.toString())
            io({
                val value = ProfileCodec.decode(before)
                ProfileCodec.encode(UiLogic.applyBasic(value, basics[0], basics[1], basics[2], basics[3], basics[4]))
            }) { updated ->
                val now = listOf(basicName?.text.toString(), basicDuration?.text.toString(), basicSlot?.text.toString(), basicLoss?.text.toString(), basicSeed?.text.toString())
                if (jsonEditor !== editor || selectedId != id || editor.text.toString() != before || basics != now) {
                    showNotice(tr(R.string.main_edit_changed))
                    return@io
                }
                editor.setText(updated); captureDraft()
                showNotice(tr(R.string.main_basic_applied))
            }
        })
        heading(tr(R.string.main_advanced))
        content.addView(text(tr(R.string.main_json_hint), 12f, MUTED))
        val initial = if (draftProfileId == profile.id && draftJson != null) draftJson!! else encodedProfiles[profile.id]?.json ?: return
        jsonEditor = EditText(this).apply {
            tag = "profile_json"; setText(initial); setTextColor(TEXT); setHintTextColor(MUTED); textSize = 12f; typeface = Typeface.MONOSPACE
            filters = arrayOf(InputFilter.LengthFilter(UiLogic.MAX_PROFILE_BYTES))
            isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START; setPadding(dp(10), dp(10), dp(10), dp(10)); background = panelBackground(); minLines = 9
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, dp(300))) }
        row(content, button(tr(R.string.main_validate), "validate_profile") { validateDraft() }, button(tr(R.string.main_save_profile), "save_profile") { saveDraft() })
    }

    private fun captureDraft() {
        jsonEditor?.let { draftJson = it.text.toString(); draftProfileId = selectedId }
        if (basicName != null) draftBasic = listOf(basicName?.text.toString(), basicDuration?.text.toString(), basicSlot?.text.toString(), basicLoss?.text.toString(), basicSeed?.text.toString())
    }

    private fun validateDraft() {
        val text = jsonEditor?.text?.toString() ?: return
        io({ val profile = ProfileCodec.decode(text); profile to Planner.plan(profile) }) { (profile, plan) -> showProfilePreview(profile, plan, tr(R.string.main_valid_profile), null) }
    }

    private fun saveDraft() {
        val text = jsonEditor?.text?.toString() ?: return
        val oldId = selectedId
        io({
            val profile = ProfileCodec.decode(text)
            require(profile.id == oldId || repository.profile(profile.id) == null) { tr(R.string.main_id_conflict) }
            repository.saveProfile(profile)
            profile
        }) { profile ->
            selectedId = profile.id; draftJson = null; draftProfileId = null; draftBasic = null
            getSharedPreferences("ui", MODE_PRIVATE).edit().putString("profile", profile.id).apply()
            showNotice(tr(R.string.main_saved_profile_notice, profile.name))
            refreshProfiles()
        }
    }

    private fun cloneSelected() {
        val base = selectedProfile() ?: return
        val clone = base.copy(id = UUID.randomUUID().toString(), name = (base.name.take(90) + tr(R.string.main_copy_suffix)))
        io({ repository.saveProfile(clone); clone }) { selectedId = it.id; draftJson = null; draftProfileId = null; refreshProfiles(); showNotice(tr(R.string.main_copy_created)) }
    }

    private fun choosePreset() {
        val presets = Presets.all()
        AlertDialog.Builder(this).setTitle(tr(R.string.main_example_copy))
            .setItems(presets.map { MainPresentation.profileName(this, it) }.toTypedArray()) { _, index ->
                val source = presets[index]
                val profile = source.copy(id = UUID.randomUUID().toString(), name = MainPresentation.profileName(this, source), description = MainPresentation.profileDescription(this, source))
                io({ repository.saveProfile(profile); profile }) { selectedId = it.id; draftJson = null; draftProfileId = null; refreshProfiles(); showNotice(tr(R.string.main_example_saved)) }
            }.setNegativeButton(tr(R.string.main_close), null).show()
    }

    private fun createNanoProfile() {
        captureDraft()
        val current = selectedProfile()
        val previousBasics = current?.let { listOf(it.name, it.durationMs.toString(), it.slotMs.toString(), it.lossPercent.toString(), it.seed.toString()) }
        val dirty = draftProfileId == selectedId &&
            ((draftJson != null && draftJson != encodedProfiles[current?.id]?.json) || (draftBasic != null && draftBasic != previousBasics))
        if (dirty) {
            AlertDialog.Builder(this).setTitle(tr(R.string.main_unsaved))
                .setMessage(tr(R.string.main_tag_discard))
                .setPositiveButton(tr(R.string.main_continue)) { _, _ -> showNanoGenerator() }
                .setNegativeButton(tr(R.string.main_back), null).show()
        } else showNanoGenerator()
    }

    private fun showNanoGenerator() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        form.addView(text(tr(R.string.main_tag_hint), 13f, MUTED))
        form.addView(text(tr(R.string.main_logical_address), 13f, TEXT))
        val address = EditText(this).apply {
            tag = "nano_address"; setSingleLine(true); setText("AA:BB:CC:DD:EE:FF"); setTextColor(TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(17))
            contentDescription = tr(R.string.main_logical_address_a11y)
        }
        form.addView(address)
        form.addView(text(tr(R.string.main_parameter), 13f, TEXT))
        val parameter = EditText(this).apply {
            tag = "nano_parameter"; setSingleLine(true); setText("0C"); setTextColor(TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(2))
            contentDescription = tr(R.string.main_parameter_a11y)
        }
        form.addView(parameter)
        val error = text("", 12f, AMBER).apply { tag = "nano_error" }
        form.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(tr(R.string.main_tag_dialog))
            .setView(form).setPositiveButton(tr(R.string.main_create_profile), null).setNegativeButton(tr(R.string.main_cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val profile = UiLogic.nanoProfile(address.text.toString(), parameter.text.toString(), UUID.randomUUID().toString()) { key, args -> MainPresentation.format(this, key, args) }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    io({ repository.saveProfile(profile); profile }, onError = { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }) {
                        selectedId = it.id; draftJson = null; draftProfileId = null; draftBasic = null
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("profile", it.id).apply()
                        dialog.dismiss(); tab = 0; renderTabs(); refreshProfiles()
                        showNotice(tr(R.string.main_tag_saved, it.radio.localName))
                    }
                } catch (failure: Exception) { error.text = diagnostic(failure) }
            }
        }
        Appearance.styleTree(form)
        dialog.show()
    }

    private fun importProfile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")) }, REQUEST_IMPORT)
        } catch (failure: Exception) { showNotice(tr(R.string.main_picker_failed, failure.message), true) }
    }

    private fun previewImport(uri: Uri) {
        io({
            val text = contentResolver.openInputStream(uri)?.use(UiLogic::readProfileText) ?: error(tr(R.string.main_file_unreadable))
            val profile = ProfileCodec.decode(text)
            profile to Planner.plan(profile)
        }) { (profile, plan) ->
            showProfilePreview(profile, plan, tr(R.string.main_review_import), {
                io({
                    val imported = UiLogic.uniqueImport(profile, repository.profiles().map { it.id }.toSet())
                    repository.saveProfile(imported)
                    imported
                }) { imported ->
                    selectedId = imported.id; draftJson = null; draftProfileId = null; tab = 1; renderTabs(); refreshProfiles()
                    showNotice(tr(R.string.main_imported))
                }
            })
        }
    }

    private fun showProfilePreview(profile: SimulationProfile, plan: List<PlannedFrame>, title: String, save: (() -> Unit)?) {
        val silent = plan.count { it.payloadHex == null }
        val body = tr(R.string.main_preview, MainPresentation.profileName(this, profile), MainPresentation.profileDescription(this, profile), profile.durationMs, plan.size, silent, profile.tracks.size, profile.radio.mode, profile.radio.txPowerDbm, manufacturerDescription(profile), profile.radio.localName ?: tr(R.string.main_not_included), profile.seed, profile.tracks.joinToString("\n") { "${it.id}: ${it.kind}" })
        val view = text(body, 14f, TEXT).apply { setPadding(dp(20), dp(8), dp(20), dp(16)); setTextIsSelectable(true) }
        val builder = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) }).setNegativeButton(tr(R.string.main_close), null)
        if (save != null) builder.setPositiveButton(tr(R.string.main_save_import)) { _, _ -> save() }
        builder.show()
    }

    private fun renderHistory() {
        heading(tr(R.string.main_runs_evidence))
        content.addView(text(tr(R.string.main_logs_explanation), 12f, MUTED))
        content.addView(button(tr(R.string.main_refresh_list), "refresh_runs") { refreshRuns(true) })
        if (runs.isEmpty()) { content.addView(text(tr(R.string.main_no_runs), 14f, MUTED)); refreshRuns(); return }
        if (selectedRunId != null && runs.none { it.id == selectedRunId }) {
            content.addView(text(tr(R.string.main_run_loading), 14f, MUTED)); refreshRuns(true); return
        }
        content.addView(text(tr(R.string.main_runs_page, runPageIndex * 100 + 1, runPageIndex * 100 + runs.size, totalRunCount, runPageIndex + 1, runPageCount), 12f, MUTED))
        row(content,
            button(tr(R.string.main_newer_runs), "runs_previous") { refreshRuns(true, runPageIndex - 1) }.apply { isEnabled = runPageIndex > 0 && !refreshingRuns },
            button(tr(R.string.main_older_runs), "runs_next") { refreshRuns(true, runPageIndex + 1) }.apply { isEnabled = runPageIndex + 1 < runPageCount && !refreshingRuns },
        )
        val spinner = Spinner(this).apply { tag = "run_selector" }
        historyRunSelector = spinner
        spinner.adapter = spinnerAdapter(runs.map { "${formatTime(it.startedAtMs)} · ${MainPresentation.profileName(this, it.profile)} · ${statusName(it.status)}" })
        val initial = runs.indexOfFirst { it.id == selectedRunId }.coerceAtLeast(0)
        if (selectedRunId == null || runs.none { it.id == selectedRunId }) selectedRunId = runs[initial].id
        spinner.setSelection(initial, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val run = runs.getOrNull(position) ?: return
                if (selectedRunId != run.id) { selectedRunId = run.id; historyCursor = 0; historyBack.clear(); loadHistory() }
            }
        }
        content.addView(spinner, LinearLayout.LayoutParams(-1, dp(56)))
        historySummary = text(tr(R.string.main_summary_loading), 13f, TEXT).also { content.addView(it) }
        row(content, button(tr(R.string.main_research_zip), "export_zip") { exportRun("zip", "zip") }, button(tr(R.string.main_logs_csv), "export_csv") { exportRun("csv", "csv") })
        row(content, button(tr(R.string.main_report_html), "export_html") { exportRun("html", "html") }, button(tr(R.string.main_report_pdf), "print_report") { showReport() })
        content.addView(text(tr(R.string.main_export_hint), 12f, MUTED))
        heading(tr(R.string.main_raw_logs))
        historyPage = text("", 12f, MUTED).also { content.addView(it) }
        row(content, button(tr(R.string.main_previous_page), "logs_previous") { if (historyBack.isNotEmpty()) { historyCursor = historyBack.removeAt(historyBack.lastIndex); loadHistory() } }, button(tr(R.string.main_next_page), "logs_next") { if (nextHistoryCursor > historyCursor) { historyBack += historyCursor; historyCursor = nextHistoryCursor; loadHistory() } })
        historyEvents = text(tr(R.string.main_loading), 12f, TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }.also { content.addView(it) }
        loadHistory()
    }

    private fun loadHistory() {
        val id = selectedRunId ?: return
        val after = historyCursor
        val generation = ++historyGeneration
        io({ repository.summary(id) to repository.events(id, after, 100) }) { (summary, events) ->
            if (generation != historyGeneration || selectedRunId != id || tab != 2) return@io
            val run = summary.run
            historySummary?.text = tr(R.string.main_history_summary, if (run.mode == RunMode.BLE) tr(R.string.main_real_ble_mode) else tr(R.string.main_dry_mode), statusName(run.status), summary.eventCount, summary.controllerAccepted, summary.simulatedFrames, summary.skippedLate, summary.errorCount, run.appVersion, run.buildCommit, run.id)
            historyPage?.text = if (events.isEmpty()) tr(R.string.main_no_new_logs) else tr(R.string.main_records_page, events.first().sequence, events.last().sequence)
            nextHistoryCursor = if (events.size == 100) events.last().sequence else after
            historyEvents?.text = events.joinToString("\n\n") {
                "#${it.sequence}  +${it.elapsedMs} ms  ${it.kind}\n${shortText(it.message)}${it.frameSequence?.let { f -> "\nslot=$f" } ?: ""}${it.trackId?.let { t -> " · tag=$t" } ?: ""}${it.payloadHex?.let { p -> "\nhex=$p" } ?: ""}${if (it.details.isEmpty()) "" else "\n${shortText(it.details.toString())}"}"
            }.ifEmpty { tr(R.string.main_no_page_logs) }
        }
    }

    private fun exportRun(kind: String, extension: String) {
        val id = selectedRunId ?: return showNotice(tr(R.string.main_select_run), true)
        beginExport(ExportRequest(kind, UiLogic.filename("vespa-$id", extension), runId = id))
    }

    private fun beginExport(request: ExportRequest) {
        if (pendingExport != null) return showNotice(tr(R.string.main_finish_picker), true)
        pendingExport = request
        val mime = when (request.kind) { "profile" -> "application/json"; "zip" -> "application/zip"; "csv" -> "text/csv"; else -> "text/html" }
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, request.name) }, REQUEST_EXPORT)
        } catch (failure: Exception) { pendingExport = null; showNotice(tr(R.string.main_destination_failed, failure.message), true) }
    }

    @Deprecated("Legacy Activity result API is intentionally used without an AndroidX UI dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK) data?.data?.let(::previewImport)
            return
        }
        if (requestCode == REQUEST_EXPORT) {
            val request = pendingExport
            pendingExport = null
            if (resultCode != RESULT_OK || request == null) return
            val uri = data?.data ?: return showNotice(tr(R.string.main_no_destination), true)
            showNotice(tr(R.string.main_exporting))
            io({
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    when (request.kind) {
                        "profile" -> {
                            val json = request.profileJson ?: ProfileCodec.encode(repository.profile(requireNotNull(request.profileId)) ?: error(tr(R.string.main_profile_missing)))
                            require(UiLogic.sha256(json) == request.profileHash) { tr(R.string.main_profile_changed) }
                            output.write(json.toByteArray(Charsets.UTF_8))
                        }
                        "zip" -> repository.exportBundle(requireNotNull(request.runId), output, resources.configuration.locales[0].language)
                        "csv" -> repository.exportCsv(requireNotNull(request.runId), output)
                        "html" -> output.write(repository.reportHtml(requireNotNull(request.runId), resources.configuration.locales[0].language).toByteArray(Charsets.UTF_8))
                        else -> error(tr(R.string.main_unknown_export))
                    }
                    output.flush()
                } ?: error(tr(R.string.main_destination_unavailable))
            }) { showNotice(tr(R.string.main_export_saved, request.name)) }
        }
    }

    private fun showReport() {
        val id = selectedRunId ?: return
        if (printJob?.let { !it.isCompleted && !it.isCancelled && !it.isFailed } == true) return showNotice(tr(R.string.main_finish_print))
        io({ repository.reportHtml(id, resources.configuration.locales[0].language) }) { html ->
            reportWebView?.destroy()
            val web = WebView(this)
            reportWebView = web
            web.settings.javaScriptEnabled = false
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.blockNetworkLoads = true
            web.settings.domStorageEnabled = false
            var loaded = false
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            }
            web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            val dialog = AlertDialog.Builder(this).setTitle(tr(R.string.main_report_title))
                .setView(web).setNegativeButton(tr(R.string.main_close), null).setPositiveButton(tr(R.string.main_print_pdf), null).create()
            dialog.setOnShowListener {
                dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .88).toInt())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!loaded) { showNotice(tr(R.string.main_report_loading)); return@setOnClickListener }
                    try {
                        val manager = getSystemService(PrintManager::class.java) ?: error(tr(R.string.main_print_unavailable))
                        printJob = manager.print("OpenVespaTrack-report-$id", web.createPrintDocumentAdapter("OpenVespaTrack-report-$id"), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                    } catch (failure: Exception) { showNotice(tr(R.string.main_print_failed, failure.message), true) }
                }
            }
            dialog.show()
        }
    }

    private fun renderNameRecovery() {
        nameRecoveryText = text("", 13f, AMBER).apply { tag = "bluetooth_name_recovery"; setTextIsSelectable(true) }.also { content.addView(it) }
        nameRecoveryButton = button(tr(R.string.main_restore_name), "restore_bluetooth_name") {
            if (service == null) { showNotice(tr(R.string.main_wait_service), true); return@button }
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_NAME_RECOVERY)
            else service?.restoreBluetoothName()
        }.also { content.addView(it) }
        updateNameRecovery()
    }

    private fun updateNameRecovery() {
        val message = live.nameRecoveryMessage
        nameRecoveryText?.text = ServicePresentation.describe(this, message.orEmpty())
        nameRecoveryText?.visibility = if (message != null) View.VISIBLE else View.GONE
        nameRecoveryButton?.visibility = if (message != null) View.VISIBLE else View.GONE
        nameRecoveryButton?.isEnabled = service != null && !live.running
    }

    private fun renderHardware() {
        heading(tr(R.string.main_capabilities_title))
        renderNameRecovery()
        val warnings = (application as VespaApplication).startupWarnings
        if (warnings.isNotEmpty()) {
            content.addView(text(tr(R.string.main_startup_diagnostics, warnings.joinToString("\n") { ServicePresentation.describe(this, it) }), 13f, AMBER).apply { setTextIsSelectable(true) })
        }
        content.addView(text(tr(R.string.main_permission_hint), 13f, MUTED))
        val capabilities = text(tr(R.string.main_capabilities_loading), 13f, TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        content.addView(capabilities)
        io({ BleCapabilities.inspect(this) }) { capabilities.text = ServicePresentation.describe(this, it) }
        row(content, button(tr(R.string.main_refresh), "refresh_capabilities") { render() }, button(tr(R.string.main_bluetooth_settings), "bluetooth_settings") {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (failure: Exception) { showNotice(diagnostic(failure), true) }
        })
        heading(tr(R.string.main_evidence_title))
        content.addView(text(tr(R.string.main_evidence_claims), 14f, TEXT))
        heading(tr(R.string.main_receiver_test))
        content.addView(text(tr(R.string.main_receiver_test_hint), 14f, MUTED))
    }

    private fun refreshProfiles() {
        if (destroyed) return
        if (!(application as VespaApplication).initializationComplete) {
            if (!initializationPollPosted) {
                initializationPollPosted = true
                root.postDelayed({ initializationPollPosted = false; refreshProfiles() }, 100)
            }
            return
        }
        startupNotice.visibility = if ((application as VespaApplication).startupWarnings.isEmpty()) View.GONE else View.VISIBLE
        io({
            val loaded = repository.profiles().sortedBy { it.name.lowercase(Locale.ROOT) }
            loaded to loaded.associate { profile ->
                val json = ProfileCodec.encode(profile)
                profile.id to EncodedProfile(json, UiLogic.sha256(json))
            }
        }) { (loaded, encoded) ->
            profiles = loaded
            encodedProfiles = encoded
            if (profiles.none { it.id == selectedId }) selectedId = profiles.firstOrNull()?.id
            render()
            selectRequestedReplay()
            if (notice.text == tr(R.string.main_profiles_loading)) showNotice(tr(R.string.main_choose_dry))
        }
    }

    private var refreshingRuns = false
    private fun refreshRuns(forceRender: Boolean = false, requestedPage: Int? = null) {
        if (refreshingRuns) return
        refreshingRuns = true
        historyRunSelector?.isEnabled = false
        val anchor = selectedRunId
        val currentPage = runPageIndex
        io({ UiLogic.historyPage(repository.runs(), currentPage, anchor, requestedPage) }, { refreshingRuns = false; historyRunSelector?.isEnabled = true }) { page ->
            refreshingRuns = false
            historyRunSelector?.isEnabled = true
            if (requestedPage == null && selectedRunId != anchor) { refreshRuns(forceRender); return@io }
            val changed = page.items != runs || page.index != runPageIndex || page.totalCount != totalRunCount
            if (selectedRunId != page.selectedId) { historyCursor = 0; historyBack.clear() }
            selectedRunId = page.selectedId
            runs = page.items; runPageIndex = page.index; runPageCount = page.pageCount; totalRunCount = page.totalCount
            if (tab == 2 && (changed || forceRender)) render()
        }
    }

    private fun <T> io(task: () -> T, onError: (() -> Unit)? = null, done: (T) -> Unit) {
        if (destroyed) return
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.fold(done) { error -> onError?.invoke(); showNotice(diagnostic(error), true) }
            }
        }
    }

    private fun heading(label: String) { content.addView(text(label, 18f, TEXT, true).apply { setPadding(0, dp(18), 0, dp(8)) }) }
    private fun field(label: String, value: String, numeric: Boolean, key: String): EditText {
        content.addView(text(label, 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        return EditText(this).apply {
            tag = key; setText(value); setTextColor(TEXT); textSize = 15f; setSingleLine(true)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = panelBackground()
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, dp(48))) }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f)
        typeface = if (bold) Appearance.headingTypeface(this@MainActivity) else Appearance.uiTypeface(this@MainActivity)
    }
    private fun button(label: String, key: String, action: () -> Unit) = Button(this).apply {
        tag = key; text = label; isAllCaps = false; textSize = 13f; minHeight = dp(48)
        setTextColor(TEXT); backgroundTintList = android.content.res.ColorStateList.valueOf(PANEL)
        setOnClickListener { action() }
    }
    private fun row(parent: LinearLayout, vararg views: View) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { index, view -> addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) }) }
        })
    }
    private fun spinnerAdapter(values: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply { (this as? TextView)?.apply { setTextColor(TEXT); textSize = 14f; maxLines = 2 } }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).apply { setBackgroundColor(PANEL); (this as? TextView)?.apply { setTextColor(TEXT); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10)); maxLines = 3 } }
    }
    private fun panelBackground() = GradientDrawable().apply { setColor(PANEL); cornerRadius = 0f; setStroke(dp(1), this@MainActivity.colors.rule) }
    private fun showNotice(message: String, error: Boolean = false) { notice.text = shortText(message, 900); notice.setTextColor(if (error) AMBER else MUTED) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(time: Long) = SimpleDateFormat("dd-MM HH:mm:ss", resources.configuration.locales[0]).format(Date(time))
    private fun statusName(status: RunStatus) = when (status) { RunStatus.RUNNING -> tr(R.string.main_status_running); RunStatus.COMPLETED -> tr(R.string.main_status_completed); RunStatus.STOPPED -> tr(R.string.main_status_stopped); RunStatus.FAILED -> tr(R.string.main_status_failed); RunStatus.INTERRUPTED -> tr(R.string.main_status_interrupted) }

    private val colors get() = Appearance.colors(this)
    private val BG get() = colors.page
    private val PANEL get() = colors.surface
    private val TEXT get() = colors.text
    private val MUTED get() = colors.muted
    private val TEAL get() = colors.accent
    private val AMBER get() = colors.warning
    private fun tr(id: Int, vararg args: Any?): String = if (args.isEmpty()) getString(id) else getString(id, *args)
    private fun diagnostic(error: Throwable): String = MainPresentation.diagnostic(this, error)
    private fun shortText(value: String, limit: Int = 500): String = UiLogic.shortText(value, limit, tr(R.string.main_short_suffix))
    private fun frameDescription(frame: PlannedFrame): String = UiLogic.frameDescription(frame) { key, args -> MainPresentation.format(this, key, args) }
    private fun manufacturerDescription(profile: SimulationProfile): String = UiLogic.manufacturerDescription(profile) { key, args -> MainPresentation.format(this, key, args) }

    companion object {
        const val EXTRA_CAPTURE_REPLAY_PROFILE_ID = "captureReplayProfileId"
        private const val REQUEST_IMPORT = 100
        private const val REQUEST_EXPORT = 101
        private const val REQUEST_PERMISSIONS = 102
        private const val REQUEST_NAME_RECOVERY = 103
    }
}

