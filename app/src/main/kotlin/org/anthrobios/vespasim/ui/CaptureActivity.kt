package org.anthrobios.vespasim.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.text.InputFilter
import android.widget.*
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.VespaApplication
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.core.capture.*
import org.anthrobios.vespasim.service.CaptureDevice
import org.anthrobios.vespasim.service.CaptureService
import org.anthrobios.vespasim.service.CaptureState
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/** Capture and analysis are separate from the transmitter screen. No radio action is restored. */
class CaptureActivity : Activity() {
    private val app get() = application as VespaApplication
    private val repository get() = app.captureRepository
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var alive = true
    private var visible = false
    private var bound = false
    private var service: CaptureService? = null
    private var state: CaptureState? = null
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var tab = 0
    private var filter = CaptureUiLogic.Filter.NANO
    private var query = ""
    private var selectedAddress: String? = null
    private var captureName = ""
    private var captureNotes = ""
    private var variant = "unknown"
    private var deviceList: LinearLayout? = null
    private var selectionLabel: TextView? = null
    private var captureButton: Button? = null
    private var scanButton: Button? = null
    private var scanStatus: TextView? = null
    private var liveLabel: TextView? = null
    private var liveChart: CaptureChart? = null
    private var liveRows = emptyList<CaptureObservation>()
    private var liveId: String? = null
    private var liveLoading = false
    private var pendingDiscovery = false
    private var archiveOffset = 0
    private var trash = false
    private var selectedId: String? = null
    private var afterSequence = 0L
    private var afterEvent = 0L
    private var previousCursors = mutableListOf<Long>()
    private var previousEventCursors = mutableListOf<Long>()
    private var selectedSequence: Long? = null
    private var cursorMs = 0L
    private var playing = false
    private var playEpoch = 0L
    private var playStart = 0L
    private var archiveRequest = 0L
    private var detail: Detail? = null
    private var playbackChart: CaptureChart? = null
    private var playbackLabel: TextView? = null
    private var playbackSeek: SeekBar? = null
    private var playButton: Button? = null
    private var sampleText: TextView? = null
    private var pendingExport: ExportRequest? = null
    private var importUri: String? = null
    private var importPreview: CaptureImportPreview? = null
    private var importDialog: AlertDialog? = null
    private var importLoading = false
    private var importCommitted = false
    private var readyLoaded = false

    private data class ExportRequest(val id: String, val kind: String, val name: String)
    private data class Detail(val record: CaptureRecord, val count: Long, val eventCount: Long,
        val rows: List<CaptureObservation>, val more: Boolean, val events: List<CaptureEvent>, val moreEvents: Boolean)
    private data class Archive(val records: List<CaptureRecord>, val total: Int, val detail: Detail?, val warnings: List<String>)

    private val listener: (CaptureState) -> Unit = { next ->
        val before = state
        state = next
        if (next.capture?.id != liveId) { liveId = next.capture?.id; liveRows = emptyList() }
        if (visible) {
            if (tab == 0 && (before?.recording != next.recording || before?.finishing != next.finishing)) render()
            else if (tab == 0) updateDiscovery()
            updateLive()
            if (before?.recording == true && !next.recording && !next.finishing) {
                selectedId = next.capture?.id; afterSequence = 0; previousCursors.clear()
                if (tab == 1) loadArchive()
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as CaptureService.LocalBinder).service
            service?.addListener(listener)
            service?.snapshot()?.let(listener)
            if (visible && tab == 0) updateDiscovery()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; selectedAddress = null
            if (visible) { status.text = s(R.string.capture_service_disconnected); updateDiscovery() }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!visible || !alive) return
            if (app.initializationComplete && !readyLoaded) {
                readyLoaded = true; render()
                if (tab == 1) loadArchive()
                importUri?.let { stageImport(Uri.parse(it)) }
            }
            if (tab == 0) { updateDiscovery(); refreshLive() }
            if (playing) {
                val duration = duration()
                cursorMs = (playStart + SystemClock.elapsedRealtime() - playEpoch).coerceAtMost(duration)
                if (cursorMs >= duration) playing = false
                updatePlayback()
            }
            handler.postDelayed(this, if (playing) 100 else 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Appearance.wrapContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            tab = it.getInt("tab"); filter = CaptureUiLogic.Filter.entries.getOrElse(it.getInt("filter")) { CaptureUiLogic.Filter.NANO }
            query = it.getString("query", ""); captureName = it.getString("name", ""); captureNotes = it.getString("notes", "")
            variant = it.getString("variant", "unknown"); archiveOffset = it.getInt("offset"); trash = it.getBoolean("trash")
            selectedId = it.getString("capture"); afterSequence = it.getLong("after"); afterEvent = it.getLong("eventAfter")
            previousCursors = it.getLongArray("cursors")?.toMutableList() ?: mutableListOf()
            previousEventCursors = it.getLongArray("eventCursors")?.toMutableList() ?: mutableListOf()
            selectedSequence = it.getLong("sample", -1).takeIf { value -> value >= 0 }; cursorMs = it.getLong("cursor")
            val id = it.getString("exportId"); val kind = it.getString("exportKind")
            if (id != null && kind != null) pendingExport = ExportRequest(id, kind, it.getString("exportName", "recording"))
            importUri = it.getString("importUri")
        }
        if (savedInstanceState == null) intent.getStringExtra("captureId")?.let { selectedId = it; tab = 1 }
        val root = column().apply {
            setPadding(dp(14), dp(8), dp(14), dp(8)); setBackgroundColor(Appearance.colors(this@CaptureActivity).page)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom else insets.systemWindowInsetBottom
                view.setPadding(dp(14), dp(8) + top, dp(14), dp(8) + bottom)
                insets
            }
        }
        root.addView(text(s(R.string.capture_title), 23f))
        val navigation = row()
        navigation.addView(button(s(R.string.capture_back), "capture_back") { finish() }, weight())
        navigation.addView(button(s(R.string.capture_record_tab), "capture_tab_record") { switchTab(0) }, weight())
        navigation.addView(button(s(R.string.capture_archive_tab), "capture_tab_archive") { switchTab(1) }, weight())
        root.addView(navigation)
        status = text(s(R.string.capture_storage_intro), 13f)
        status.tag = "capture_status"; root.addView(status)
        body = column()
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Appearance.styleTree(root)
        render()
    }
    override fun onResume() {
        super.onResume()
        if (Appearance.needsRecreation(this)) { recreate(); return }
    }
    override fun onStart() {
        super.onStart(); visible = true
        bound = bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(tick)
        importPreview?.let(::showImportPreview)
    }
    override fun onStop() {
        visible = false; playing = false; pendingDiscovery = false; selectedAddress = null
        handler.removeCallbacks(tick)
        service?.stopDiscovery()
        service?.removeListener(listener)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
    override fun onDestroy() {
        alive = false
        importDialog?.dismiss(); importDialog = null
        importPreview?.let { preview -> closePreview(preview) }
        importPreview = null
        worker.shutdown()
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("tab", tab); out.putInt("filter", filter.ordinal); out.putString("query", query)
        out.putString("name", captureName); out.putString("notes", captureNotes); out.putString("variant", variant)
        out.putInt("offset", archiveOffset); out.putBoolean("trash", trash); out.putString("capture", selectedId)
        out.putLong("after", afterSequence); out.putLongArray("cursors", previousCursors.toLongArray())
        out.putLong("eventAfter", afterEvent); out.putLongArray("eventCursors", previousEventCursors.toLongArray())
        out.putLong("sample", selectedSequence ?: -1); out.putLong("cursor", cursorMs)
        pendingExport?.let { out.putString("exportId", it.id); out.putString("exportKind", it.kind); out.putString("exportName", it.name) }
        if (!importCommitted) out.putString("importUri", importUri)
        super.onSaveInstanceState(out)
    }
    private fun switchTab(value: Int) {
        playing = false; tab = value; archiveRequest++
        render(); if (tab == 1 && app.initializationComplete) loadArchive()
    }
    private fun render() {
        body.removeAllViews(); deviceList = null; captureButton = null; scanButton = null; selectionLabel = null
        scanStatus = null; liveLabel = null; liveChart = null; playbackChart = null; playbackSeek = null; playbackLabel = null
        if (!app.initializationComplete) { body.addView(text(s(R.string.capture_checking_archive))); return }
        if (tab == 0) renderRecorder() else renderArchiveLoading()
        Appearance.styleTree(body)
    }
    private fun renderRecorder() {
        val active = state?.recording == true || state?.finishing == true
        if (active) {
            body.addView(text(s(R.string.capture_saving_live), 20f))
            liveLabel = text("").also { body.addView(it) }
            liveChart = CaptureChart(this).also { body.addView(it, LinearLayout.LayoutParams(-1, dp(210))) }
            body.addView(button(s(R.string.capture_stop), "capture_stop") { service?.stopCapture() }.apply { isEnabled = state?.recording == true })
            body.addView(text(s(R.string.capture_background_help), 13f))
            updateLive(); refreshLive(); return
        }
        body.addView(text(s(R.string.capture_step_find), 20f))
        body.addView(text(s(R.string.capture_filter_help), 13f))
        body.addView(spinner(CaptureUiLogic.Filter.entries.map { filterLabel(it) }, filter.ordinal, "capture_filter") { filter = CaptureUiLogic.Filter.entries[it]; updateDiscovery() })
        body.addView(edit(s(R.string.capture_query_hint), query, "capture_query") { query = it; updateDiscovery() })
        val actions = row()
        scanButton = button(s(R.string.capture_find_devices), "capture_scan") { discover() }.also { actions.addView(it, weight()) }
        actions.addView(button(s(R.string.capture_stop_search), "capture_scan_stop") { pendingDiscovery = false; service?.stopDiscovery() }, weight())
        body.addView(actions)
        scanStatus = text("").also { body.addView(it) }
        val settings = row()
        settings.addView(button(s(R.string.capture_bluetooth), "capture_bluetooth_settings") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }, weight())
        settings.addView(button(s(R.string.capture_location), "capture_location_settings") { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }, weight())
        body.addView(settings)
        deviceList = column().also { body.addView(it) }
        selectionLabel = text("").also { body.addView(it) }
        body.addView(text(s(R.string.capture_step_name), 20f))
        body.addView(edit(s(R.string.capture_name_hint), captureName, "capture_name") { captureName = it })
        body.addView(spinner(listOf(s(R.string.capture_variant_unknown), s(R.string.capture_variant_android), s(R.string.capture_variant_iphone), s(R.string.capture_variant_handheld)), listOf("unknown", "android", "iphone", "range_pro").indexOf(variant).coerceAtLeast(0), "capture_variant") { variant = listOf("unknown", "android", "iphone", "range_pro")[it] })
        body.addView(edit(s(R.string.capture_notes_optional), captureNotes, "capture_notes", multiline = true) { captureNotes = it })
        captureButton = button(s(R.string.capture_start), "capture_start") { startCapture() }.also { body.addView(it) }
        body.addView(text(s(R.string.capture_selection_help), 13f))
        updateDiscovery()
    }
    private fun model(device: CaptureDevice) = CaptureUiLogic.Device(device.address, device.advertisedName, device.deviceName,
        device.logicalNanoAddress, device.nanoCandidate, device.vespaCandidate, device.fc65, device.lastSeenElapsedMs)
    private fun updateDiscovery() {
        if (tab != 0 || state?.recording == true || state?.finishing == true) return
        val devices = state?.devices.orEmpty()
        val now = SystemClock.elapsedRealtime()
        selectedAddress = CaptureUiLogic.selection(selectedAddress, devices.map(::model), filter, query, now)
        val shown = devices.filter { CaptureUiLogic.visible(model(it), filter, query) }.sortedByDescending { it.lastSeenElapsedMs }
        if (selectedAddress != null && shown.take(80).none { it.address == selectedAddress }) selectedAddress = null
        deviceList?.let { list ->
            list.removeAllViews()
            if (shown.isEmpty()) list.addView(text(if (state?.discovering == true) s(R.string.capture_search_empty) else s(R.string.capture_search_idle), 14f))
            shown.take(80).forEach { device ->
                val fresh = CaptureUiLogic.fresh(model(device), now)
                val label = (if (selectedAddress == device.address) "✓ " else "") +
                    "${device.advertisedName ?: s(R.string.capture_unnamed)}\n${device.address} · ${device.lastObservation.measuredRssi?.let { "$it dBm" } ?: s(R.string.capture_rssi_unknown)}" +
                    (device.logicalNanoAddress?.let { s(R.string.capture_logical_address, it) } ?: "") +
                    (device.deviceName?.takeIf { it != device.advertisedName }?.let { s(R.string.capture_cached_name, it) } ?: "") +
                    if (fresh) "" else s(R.string.capture_stale)
                list.addView(button(label, "capture_device_${device.address}") { selectedAddress = device.address; updateDiscovery() }.apply { isEnabled = fresh })
            }
            if (shown.size > 80) list.addView(text(s(R.string.capture_too_many_devices, shown.size)))
        }
        selectionLabel?.text = selectedAddress?.let { s(R.string.capture_selected_address, it) } ?: s(R.string.capture_none_selected)
        captureButton?.isEnabled = selectedAddress != null && service != null && state?.recording != true
        scanButton?.isEnabled = service != null && state?.discovering != true
        scanStatus?.text = state?.error?.let { CapturePresentation.describe(this, it) } ?: (if (state?.discovering == true) s(R.string.capture_searching) else state?.message?.let { CapturePresentation.describe(this, it) } ?: s(R.string.capture_connecting))
    }
    private fun discover() {
        selectedAddress = null
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pendingDiscovery = true; requestPermissions(permissions.toTypedArray(), PERMISSION_SCAN); return }
        val problem = CaptureService.scanProblem(this)
        if (problem != null) { status.text = CapturePresentation.describe(this, problem); return }
        runCatching { service?.startDiscovery() ?: error(s(R.string.capture_service_not_connected)) }.onFailure(::failure)
    }
    private fun startCapture() {
        updateDiscovery()
        val address = selectedAddress ?: return
        if (captureName.trim().isEmpty()) { status.text = s(R.string.capture_name_required); return }
        val problem = CaptureService.scanProblem(this)
        if (problem != null) { status.text = CapturePresentation.describe(this, problem); return }
        val preferences = getSharedPreferences("capture_ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notificationAsked", false)) {
            preferences.edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_NOTIFICATION)
            status.text = s(R.string.capture_retry_after_notification)
            return
        }
        captureButton?.isEnabled = false
        runCatching { requireNotNull(service).startCapture(address, captureName.trim(), variant, captureNotes) }.onFailure { failure(it); updateDiscovery() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_SCAN) {
            val start = pendingDiscovery; pendingDiscovery = false
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } && start && visible) discover()
            else status.text = s(R.string.capture_permissions_required)
        }
        if (requestCode == PERMISSION_NOTIFICATION) status.text = s(R.string.capture_notification_processed)
    }
    private fun updateLive() {
        val current = state ?: return
        liveLabel?.text = s(R.string.capture_live_summary, current.capture?.name.orEmpty(), current.capture?.address.orEmpty(), current.sampleCount, current.unsavedCount) +
            (current.lastObservation?.let { s(R.string.capture_last_reception, date(it.receivedAtMs), it.measuredRssi?.let { rssi -> "$rssi dBm" } ?: s(R.string.capture_rssi_unavailable)) } ?: s(R.string.capture_no_reception)) +
            CapturePresentation.describe(this, current.error ?: current.message) + s(R.string.capture_chart_latest, liveRows.size) +
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) s(R.string.capture_notifications_disabled) else ""
        liveChart?.show(liveRows)
    }
    private fun refreshLive() {
        val id = state?.capture?.id ?: return
        if (liveLoading || (state?.recording != true && state?.finishing != true)) return
        liveLoading = true
        io(s(R.string.capture_loading_measurements), { val summary = repository.summary(id); repository.observations(id, maxOf(0L, summary.lastObservationSequence - 500), 500) }, quiet = true) {
            liveLoading = false
            if (liveId == id) { liveRows = it; updateLive() }
        }
    }
    private fun renderArchiveLoading() { body.addView(text(s(R.string.capture_loading_archive_ellipsis))) }
    private fun loadArchive() {
        if (!app.initializationComplete) return
        val request = ++archiveRequest
        val wantedId = selectedId; val offset = archiveOffset; val showTrash = trash
        val after = afterSequence; val eventAfter = afterEvent
        io(s(R.string.capture_loading_archive), {
            val page = repository.page(25, offset, showTrash)
            val record = wantedId?.let { repository.get(it, showTrash) }
            val data = record?.let {
                if (showTrash) Detail(it, 0, 0, emptyList(), false, emptyList(), false)
                else {
                    val summary = repository.summary(it.id)
                    val rows = repository.observations(it.id, after, CaptureUiLogic.PAGE_SIZE + 1)
                    val events = repository.events(it.id, eventAfter, 101)
                    Detail(summary.record, summary.observationCount, summary.eventCount, rows.take(100), rows.size > 100, events.take(100), events.size > 100)
                }
            }
            Archive(page.records, page.total, data, page.warnings)
        }) { result ->
            if (tab == 1 && request == archiveRequest && trash == showTrash) {
                detail = result.detail
                if (result.detail == null) selectedId = null
                renderArchive(result)
            }
        }
    }
    private fun renderArchive(archive: Archive) {
        body.removeAllViews(); playbackChart = null; sampleText = null; staticProfileButton = null
        body.addView(text(s(R.string.capture_saved_recordings), 20f))
        val warnings = app.startupWarnings + archive.warnings
        if (warnings.isNotEmpty()) body.addView(text(s(R.string.capture_storage_warnings, warnings.size) + warnings.take(20).joinToString("\n") { UiLogic.shortText(CapturePresentation.describe(this, it), 300) } + if (warnings.size > 20) s(R.string.capture_more_warnings) else "", 13f))
        body.addView(text(s(R.string.capture_archive_help), 13f))
        val controls = row()
        controls.addView(button(s(R.string.capture_refresh), "capture_archive_refresh") { playing = false; loadArchive() }, weight())
        controls.addView(button(s(R.string.capture_import_zip), "capture_import") { chooseImport() }, weight())
        body.addView(controls)
        body.addView(CheckBox(this).apply {
            text = s(R.string.capture_trash); setTextColor(Appearance.colors(this@CaptureActivity).text); isChecked = trash; tag = "capture_trash_toggle"
            setOnCheckedChangeListener { _, checked -> trash = checked; archiveOffset = 0; selectedId = null; resetPages(); loadArchive() }
        })
        body.addView(text(s(R.string.capture_archive_page, archive.total, archiveOffset / 25 + 1), 13f))
        archive.records.forEach { record ->
            body.addView(button("${if (selectedId == record.id) "✓ " else ""}${UiLogic.shortText(record.name, 100)}\n${date(record.startedAtMs)} · ${statusLabel(record.status)}", "capture_record_${record.id}") {
                selectedId = record.id; resetPages(); loadArchive()
            })
        }
        if (archive.records.isEmpty()) body.addView(text(s(R.string.capture_empty_page)))
        val pages = row()
        pages.addView(button(s(R.string.capture_previous_25), "capture_archive_previous") { archiveOffset = maxOf(0, archiveOffset - 25); loadArchive() }.apply { isEnabled = archiveOffset > 0 }, weight())
        pages.addView(button(s(R.string.capture_next_25), "capture_archive_next") { archiveOffset += 25; loadArchive() }.apply { isEnabled = archiveOffset + 25 < archive.total }, weight())
        body.addView(pages)
        archive.detail?.let(::renderDetail)
        Appearance.styleTree(body)
    }
    private fun resetPages() {
        playing = false; cursorMs = 0; selectedSequence = null
        afterSequence = 0; afterEvent = 0; previousCursors.clear(); previousEventCursors.clear(); detail = null
    }
    private fun renderDetail(data: Detail) {
        val record = data.record
        body.addView(text(UiLogic.shortText(record.name, 120), 21f))
        body.addView(text(s(R.string.capture_record_detail, statusLabel(record.status), variantLabel(record.tagVariant), record.address, date(record.startedAtMs), record.endedAtMs?.let(::date) ?: s(R.string.capture_not_yet), record.id), 14f))
        if (record.status == RunStatus.INTERRUPTED) body.addView(text(s(R.string.capture_interrupted)))
        if (record.importedFromId != null) body.addView(text(s(R.string.capture_import_source, record.importedFromId.orEmpty(), record.importedArchiveSha256.orEmpty()), 12f))
        body.addView(text(UiLogic.shortText(record.notes, 1500).ifBlank { s(R.string.capture_no_notes) }, 14f))
        val mutable = record.status != RunStatus.RUNNING
        if (trash) {
            body.addView(button(s(R.string.capture_restore), "capture_restore") { mutate(s(R.string.capture_restore_recording), { repository.restore(record.id) }) { trash = false; loadArchive() } })
            body.addView(button(s(R.string.capture_delete_permanently), "capture_purge") {
                AlertDialog.Builder(this).setTitle(s(R.string.capture_delete_question))
                    .setMessage(s(R.string.capture_delete_message, record.name))
                    .setNegativeButton(s(R.string.capture_cancel), null).setPositiveButton(s(R.string.capture_delete_permanently)) { _, _ ->
                        mutate(s(R.string.capture_delete_permanently), { repository.purge(record.id) }) { selectedId = null; resetPages(); loadArchive() }
                    }.show()
            })
            return
        }
        val editActions = row()
        editActions.addView(button(s(R.string.capture_rename), "capture_rename") { rename(record) }.apply { isEnabled = mutable }, weight())
        editActions.addView(button(s(R.string.capture_notes_type), "capture_edit_notes") { editNotes(record) }.apply { isEnabled = mutable }, weight())
        body.addView(editActions)
        body.addView(button(s(R.string.capture_move_to_trash), "capture_trash") {
            mutate(s(R.string.capture_move_to_trash), { repository.trash(record.id) }) { selectedId = null; resetPages(); loadArchive() }
        }.apply { isEnabled = mutable })
        val exports = row()
        listOf("ZIP", "CSV", "HTML").forEach { kind -> exports.addView(button(kind, "capture_export_${kind.lowercase()}") { chooseExport(record, kind) }, weight()) }
        body.addView(exports)
        body.addView(text(s(R.string.capture_observation_count, data.count, data.eventCount), 16f))
        body.addView(text(s(R.string.capture_offline_title), 19f))
        body.addView(text(s(R.string.capture_timeline_help, data.rows.firstOrNull()?.sequence ?: "–", data.rows.lastOrNull()?.sequence ?: "–"), 13f))
        playbackChart = CaptureChart(this).also { body.addView(it, LinearLayout.LayoutParams(-1, dp(210))) }
        playbackLabel = text("").also { body.addView(it) }
        playbackSeek = SeekBar(this).apply {
            max = 1000; tag = "capture_scrub"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { playing = false; cursorMs = duration() * progress / 1000; updatePlayback() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { playing = false }
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }.also { body.addView(it) }
        playButton = button(s(R.string.capture_play), "capture_offline_play") {
            playing = !playing
            if (playing) { if (cursorMs >= duration()) cursorMs = 0; playEpoch = SystemClock.elapsedRealtime(); playStart = cursorMs }
            updatePlayback()
        }.apply { isEnabled = data.rows.isNotEmpty() }.also { body.addView(it) }
        val samplePages = row()
        samplePages.addView(button(s(R.string.capture_previous_observations), "capture_samples_previous") {
            afterSequence = previousCursors.removeAt(previousCursors.lastIndex); playing = false; cursorMs = 0; selectedSequence = null; loadArchive()
        }.apply { isEnabled = previousCursors.isNotEmpty() }, weight())
        samplePages.addView(button(s(R.string.capture_next_observations), "capture_samples_next") {
            previousCursors.add(afterSequence); afterSequence = data.rows.last().sequence; playing = false; cursorMs = 0; selectedSequence = null; loadArchive()
        }.apply { isEnabled = data.more }, weight())
        body.addView(samplePages)
        selectedSequence = selectedSequence?.takeIf { seq -> data.rows.any { it.sequence == seq } } ?: data.rows.firstOrNull()?.sequence
        val samples = data.rows.map { "#${it.sequence} · ${date(it.receivedAtMs)} · ${it.measuredRssi?.let { r -> "$r dBm" } ?: s(R.string.capture_rssi_unknown)}" }
        if (samples.isNotEmpty()) body.addView(spinner(samples, data.rows.indexOfFirst { it.sequence == selectedSequence }.coerceAtLeast(0), "capture_sample") {
            selectedSequence = data.rows[it].sequence; showSample()
        })
        sampleText = text("", 12f).apply { setTextIsSelectable(true); tag = "capture_original_bytes" }.also { body.addView(it) }
        body.addView(text(s(R.string.capture_replay_title), 19f))
        body.addView(text(s(R.string.capture_replay_help), 13f))
        staticProfileButton = button(s(R.string.capture_repeat_one), "capture_prepare_static") { prepareProfile(false) }.also { body.addView(it) }
        body.addView(button(s(R.string.capture_sequence_page), "capture_prepare_sequence") { sequenceDialog() }.apply { isEnabled = mutable && data.rows.isNotEmpty() })
        showSample(); updatePlayback()
        body.addView(text(s(R.string.capture_log_page, data.events.size), 19f))
        body.addView(text(data.events.joinToString("\n") { "#${it.sequence} ${date(it.atEpochMs)} · ${it.kind}\n${UiLogic.shortText(it.message, 300)}${if (it.details.isNotEmpty()) "\n${UiLogic.shortText(it.details.toString(), 800)}" else ""}" }.ifEmpty { s(R.string.capture_no_logs) }, 12f).apply { setTextIsSelectable(true) })
        val logPages = row()
        logPages.addView(button(s(R.string.capture_previous_log), "capture_logs_previous") {
            afterEvent = previousEventCursors.removeAt(previousEventCursors.lastIndex); playing = false; loadArchive()
        }.apply { isEnabled = previousEventCursors.isNotEmpty() }, weight())
        logPages.addView(button(s(R.string.capture_next_log), "capture_logs_next") {
            previousEventCursors.add(afterEvent); afterEvent = data.events.last().sequence; playing = false; loadArchive()
        }.apply { isEnabled = data.moreEvents }, weight())
        body.addView(logPages)
    }
    private var staticProfileButton: Button? = null
    private fun showSample() {
        val data = detail ?: return
        val item = data.rows.firstOrNull { it.sequence == selectedSequence }
        val inspection = item?.let(CaptureParser::inspect)
        sampleText?.text = item?.let {
            s(R.string.capture_scan_detail, s(R.string.capture_original_record_heading, it.sequence), date(it.receivedAtMs), it.timestampNanos,
                it.address, it.measuredRssi?.toString() ?: s(R.string.capture_invalid_rssi), it.rssiRaw,
                inspection?.advertisedName ?: s(R.string.capture_not_present), it.deviceName ?: s(R.string.capture_not_present),
                it.legacy, it.connectable, it.dataStatus, it.primaryPhy, it.secondaryPhy, it.advertisingSid,
                it.txPowerRaw, it.callbackType, it.rawHex ?: s(R.string.capture_scan_missing),
                inspection?.replayReason?.let { reason -> CapturePresentation.describe(this, reason) } ?: s(R.string.capture_replay_supported))
        } ?: s(R.string.capture_no_scan_selected)
        staticProfileButton?.isEnabled = item != null && data.record.status != RunStatus.RUNNING && inspection?.replayable == true
    }
    private fun duration(): Long = CaptureUiLogic.timeline(detail?.rows.orEmpty()).maxOfOrNull { it.offsetMs } ?: 0
    private fun updatePlayback() {
        val rows = detail?.rows.orEmpty(); val points = CaptureUiLogic.timeline(rows); val duration = points.maxOfOrNull { it.offsetMs } ?: 0
        cursorMs = cursorMs.coerceIn(0, duration)
        val item = CaptureUiLogic.atCursor(points, cursorMs)
        playbackChart?.show(rows, cursorMs)
        playbackLabel?.text = s(R.string.capture_playback_time, cursorMs / 1000.0, duration / 1000.0,
            item?.let { "#${it.sequence}, ${date(it.receivedAtMs)}, ${it.measuredRssi?.let { r -> "$r dBm" } ?: s(R.string.capture_rssi_unknown)}" } ?: s(R.string.capture_no_reception_at_time))
        playbackSeek?.progress = if (duration == 0L) 0 else (cursorMs * 1000 / duration).toInt()
        playButton?.text = if (playing) s(R.string.capture_pause) else s(R.string.capture_play)
    }
    private fun rename(record: CaptureRecord) {
        val field = edit(s(R.string.capture_name), record.name, "capture_rename_value") {}
        val dialog = AlertDialog.Builder(this).setTitle(s(R.string.capture_rename_recording)).setView(field).setNegativeButton(s(R.string.capture_cancel), null).setPositiveButton(s(R.string.capture_save), null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (field.text.toString().trim().isBlank()) { field.error = s(R.string.capture_enter_name); return@setOnClickListener }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            val value = field.text.toString().trim()
            io(s(R.string.capture_saving_name), { repository.rename(record.id, value) }, finished = { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }) { dialog.dismiss(); loadArchive() }
        } }; dialog.show()
    }
    private fun editNotes(record: CaptureRecord) {
        var selectedVariant = record.tagVariant
        val box = column(); val field = edit(s(R.string.capture_notes), record.notes, "capture_notes_value", true) {}
        box.addView(spinner(listOf(s(R.string.capture_variant_unknown), s(R.string.capture_variant_android), s(R.string.capture_variant_iphone), s(R.string.capture_variant_handheld)), listOf("unknown", "android", "iphone", "range_pro").indexOf(selectedVariant).coerceAtLeast(0), "capture_notes_variant") { selectedVariant = listOf("unknown", "android", "iphone", "range_pro")[it] })
        box.addView(field)
        AlertDialog.Builder(this).setTitle(s(R.string.capture_notes_and_type)).setView(box).setNegativeButton(s(R.string.capture_cancel), null).setPositiveButton(s(R.string.capture_save)) { _, _ ->
            val value = field.text.toString()
            val type = selectedVariant
            mutate(s(R.string.capture_saving_notes), { repository.updateNotes(record.id, value, type) }) { loadArchive() }
        }.show()
    }
    private fun sequenceDialog() {
        val data = detail ?: return
        val field = edit(s(R.string.capture_slot_hint), "500", "capture_slot") {}
        field.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        AlertDialog.Builder(this).setTitle(s(R.string.capture_sequence_title, data.rows.size))
            .setMessage(s(R.string.capture_sequence_help))
            .setView(field).setNegativeButton(s(R.string.capture_cancel), null).setPositiveButton(s(R.string.capture_review_profile)) { _, _ ->
                val slot = field.text.toString().toIntOrNull()
                if (slot == null) status.text = s(R.string.capture_slot_invalid) else prepareProfile(true, slot)
            }.show()
    }
    private fun prepareProfile(sequence: Boolean, slotMs: Int = 500) {
        val data = detail ?: return
        val sourceId = data.record.id
        val sequences = if (sequence) data.rows.map { it.sequence } else listOfNotNull(selectedSequence)
        if (sequences.isEmpty()) return
        io(s(R.string.capture_review_replay), {
            val record = requireNotNull(repository.get(sourceId)) { s(R.string.capture_source_unavailable) }
            val rows = repository.observations(sourceId, sequences.first() - 1, sequences.size)
            val unsupported = rows.mapNotNull { sample -> CaptureParser.inspect(sample).replayReason?.let { "#${sample.sequence}: ${CapturePresentation.describe(this, it)}" } }
            require(unsupported.isEmpty()) { s(R.string.capture_selection_unsupported) + unsupported.take(8).joinToString("\n") + if (unsupported.size > 8) s(R.string.capture_more_unsupported, unsupported.size - 8) else "" }
            require(rows.map { it.sequence } == sequences) { s(R.string.capture_source_changed) }
            val name = "${record.name.take(85)} · ${if (sequence) s(R.string.capture_sequence) else s(R.string.capture_scan_record_number, sequences.first())}"
            val profile = if (sequence) CaptureReplay.sequenceProfile(record, rows, UUID.randomUUID().toString(), name, slotMs)
                else CaptureReplay.staticProfile(record, rows.single(), UUID.randomUUID().toString(), name)
            Triple(profile, record, rows)
        }) { (profile, record, rows) -> if (visible && tab == 1 && selectedId == sourceId) reviewProfile(profile, record, rows, sequence) }
    }
    private fun reviewProfile(profile: SimulationProfile, record: CaptureRecord, rows: List<CaptureObservation>, sequence: Boolean) {
        val hashes = rows.joinToString("\n") { "#${it.sequence}: ${CaptureReplay.sha256Hex(requireNotNull(it.rawHex))}" }
        val message = s(R.string.capture_review_source, record.name, record.id, rows.size) +
            s(R.string.capture_expected_name, profile.radio.localName ?: s(R.string.capture_none)) +
            (if (sequence) s(R.string.capture_fixed_slots, profile.slotMs, profile.durationMs) else s(R.string.capture_repeat_duration, profile.durationMs / 1000)) +
            s(R.string.capture_review_hashes, CaptureReplay.differences.joinToString("\n• ", "• ") { CapturePresentation.describe(this, it) }, hashes)
        val view = text(message, 13f).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true) }
        val dialog = AlertDialog.Builder(this).setTitle(s(R.string.capture_review_replay))
            .setView(ScrollView(this).apply { addView(view) }).setNegativeButton(s(R.string.capture_cancel), null)
            .setPositiveButton(s(R.string.capture_save_open), null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            io(s(R.string.capture_saving_replay), {
                val source = requireNotNull(repository.get(record.id)) { s(R.string.capture_source_unavailable) }
                require(source.status != RunStatus.RUNNING) { s(R.string.capture_stop_first) }
                app.repository.saveProfile(profile)
            }, finished = { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }) {
                dialog.dismiss()
                if (visible) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("captureReplayProfileId", profile.id))
                else status.text = s(R.string.capture_replay_saved)
            }
        } }; dialog.show()
    }
    private fun chooseExport(record: CaptureRecord, kind: String) {
        if (pendingExport != null) { status.text = s(R.string.capture_picker_pending); return }
        pendingExport = ExportRequest(record.id, kind, record.name)
        val mime = when (kind) { "ZIP" -> "application/zip"; "CSV" -> "text/csv"; else -> "text/html" }
        runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_TITLE, UiLogic.filename(record.name, kind.lowercase(java.util.Locale.ROOT))), EXPORT_FILE) }
            .onFailure { pendingExport = null; failure(it) }
    }
    private fun chooseImport() {
        if (importLoading || importPreview != null || importUri != null) { status.text = s(R.string.capture_finish_import_first); return }
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream")), IMPORT_FILE) }.onFailure(::failure)
    }
    @Deprecated("Framework result API retained to match native Activity; identity is saved explicitly")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_FILE) {
            val request = pendingExport; pendingExport = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && request != null) io(s(R.string.capture_exporting, request.kind), {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")) { s(R.string.capture_file_unavailable) }.use { output ->
                    when (request.kind) {
                        "ZIP" -> repository.exportZip(request.id, output, resources.configuration.locales[0].language)
                        "CSV" -> repository.exportCsv(request.id, output)
                        "HTML" -> output.write(repository.reportHtml(request.id, resources.configuration.locales[0].language).toByteArray(Charsets.UTF_8))
                        else -> error(s(R.string.capture_export_unknown))
                    }
                }
            }) { status.text = s(R.string.capture_exported, request.kind, UiLogic.shortText(request.name, 100)) }
        }
        if (requestCode == IMPORT_FILE && resultCode == RESULT_OK) data?.data?.let { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            importUri = uri.toString(); importCommitted = false
            stageImport(uri)
        }
    }
    private fun stageImport(uri: Uri) {
        if (importLoading || importPreview != null || !app.initializationComplete) return
        importLoading = true; status.text = s(R.string.capture_checking_zip)
        worker.execute {
            val result = runCatching { requireNotNull(contentResolver.openInputStream(uri)) { s(R.string.capture_zip_unavailable) }.use(repository::previewImport) }
            handler.post {
                importLoading = false
                if (!alive) {
                    result.getOrNull()?.let { preview -> closePreview(preview) }
                } else result.fold(onSuccess = { preview ->
                    importPreview = preview
                    showImportPreview(preview)
                }, onFailure = { importUri = null; failure(it) })
            }
        }
    }
    private fun showImportPreview(preview: CaptureImportPreview) {
        if (!alive || !visible || importDialog?.isShowing == true) return
        val message = s(R.string.capture_import_preview, preview.record.name, variantLabel(preview.record.tagVariant), preview.record.address, preview.observationCount, preview.eventCount, preview.archiveSha256)
        importDialog = AlertDialog.Builder(this).setTitle(s(R.string.capture_review_import)).setMessage(message)
            .setNegativeButton(s(R.string.capture_cancel)) { _, _ -> discardImport() }
            .setPositiveButton(s(R.string.capture_save_copy)) { _, _ ->
                importPreview = null; importCommitted = true; importUri = null
                io(s(R.string.capture_importing), { preview.use { repository.commitImport(it) } }) { record ->
                    tab = 1; trash = false; selectedId = record.id; resetPages(); render(); loadArchive()
                }
            }.create().also { dialog ->
                dialog.setOnCancelListener { discardImport() }
                dialog.setOnDismissListener { if (importDialog === dialog) importDialog = null }
                dialog.show()
            }
    }
    /** Cleanup errors must not reach Android's uncaught-exception handler. A destroyed Activity
     * cannot accept executor tasks, so late staged previews use a short, guarded cleanup thread. */
    private fun closePreview(preview: CaptureImportPreview) {
        val cleanup = Runnable {
            runCatching { preview.close() }.onFailure { error ->
                handler.post { if (alive) failure(error) }
            }
        }
        if (worker.isShutdown) Thread(cleanup, "capture-import-cleanup").start() else worker.execute(cleanup)
    }
    private fun discardImport() {
        importPreview?.let { preview -> closePreview(preview) }
        importPreview = null; importUri = null; importCommitted = false
        status.text = s(R.string.capture_import_cancelled)
    }
    private var mutationPending = false
    private fun mutate(label: String, action: () -> Unit, complete: () -> Unit) {
        if (mutationPending) return
        mutationPending = true
        io(label, action, finished = { mutationPending = false }) { complete() }
    }
    private fun <T> io(label: String, action: () -> T, quiet: Boolean = false, finished: () -> Unit = {}, complete: (T) -> Unit) {
        if (!alive) return
        if (!quiet) status.text = s(R.string.capture_operation_busy, label)
        worker.execute {
            val result = runCatching(action)
            handler.post {
                if (alive) {
                    finished()
                    result.fold(onSuccess = { value ->
                        if (!quiet) status.text = s(R.string.capture_operation_done, label)
                        complete(value)
                    }, onFailure = { error ->
                        if (quiet) liveLoading = false
                        failure(error)
                    })
                }
            }
        }
    }
    private fun failure(error: Throwable) { status.text = UiLogic.shortText(s(R.string.capture_operation_failed, CapturePresentation.describe(this, error.message ?: error.javaClass.simpleName)), 900) }
    private fun openSettings(action: String) { runCatching { startActivity(Intent(action)) }.onFailure(::failure) }
    private fun variantLabel(value: String) = when (value) { "android" -> s(R.string.capture_variant_android); "iphone" -> s(R.string.capture_variant_iphone); "range_pro" -> s(R.string.capture_variant_handheld); else -> s(R.string.capture_variant_unknown) }
    private fun s(id: Int, vararg arguments: Any): String = getString(id, *arguments)
    private fun filterLabel(value: CaptureUiLogic.Filter): String = s(when (value) {
        CaptureUiLogic.Filter.NANO -> R.string.capture_filter_name
        CaptureUiLogic.Filter.VESPA -> R.string.capture_filter_known
        CaptureUiLogic.Filter.FC65 -> R.string.capture_filter_fc65
        CaptureUiLogic.Filter.ALL -> R.string.capture_filter_all
    })
    private fun statusLabel(value: RunStatus): String = s(when (value) {
        RunStatus.RUNNING -> R.string.capture_status_running
        RunStatus.STOPPED -> R.string.capture_status_stopped
        RunStatus.COMPLETED -> R.string.capture_status_completed
        RunStatus.FAILED -> R.string.capture_status_failed
        RunStatus.INTERRUPTED -> R.string.capture_status_interrupted
    })
    private fun date(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, resources.configuration.locales[0]).format(Date(value))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun text(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size
        if (size >= 19f) setTypeface(Appearance.headingTypeface(this@CaptureActivity), android.graphics.Typeface.BOLD)
        else Appearance.reading(this)
        setTextColor(Appearance.colors(this@CaptureActivity).text); setPadding(0, dp(7), 0, dp(7))
    }
    private fun button(label: String, identifier: String, action: () -> Unit) = Button(this).apply {
        text = label; tag = identifier; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }; Appearance.styleTree(this)
    }
    private fun edit(hintText: String, initial: String, identifier: String, multiline: Boolean = false, change: (String) -> Unit) = EditText(this).apply {
        hint = hintText; tag = identifier
        filters = arrayOf(InputFilter.LengthFilter(if (multiline) 4096 else if (identifier.contains("name") || identifier == "capture_rename_value") 120 else 256))
        setText(initial); setTextColor(Appearance.colors(this@CaptureActivity).text); setHintTextColor(Appearance.colors(this@CaptureActivity).muted)
        setSingleLine(!multiline); if (multiline) { minLines = 2; maxLines = 5 }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        Appearance.styleTree(this)
    }
    private fun spinner(labels: List<String>, index: Int, identifier: String, selected: (Int) -> Unit) = Spinner(this).apply {
        tag = identifier
        adapter = object : ArrayAdapter<String>(this@CaptureActivity, android.R.layout.simple_spinner_item, labels) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply {
                (this as? TextView)?.apply { setTextColor(Appearance.colors(this@CaptureActivity).text); typeface = Appearance.uiTypeface(this@CaptureActivity); textSize = 14f; maxLines = 2 }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).apply {
                setBackgroundColor(Appearance.colors(this@CaptureActivity).surface)
                (this as? TextView)?.apply { setTextColor(Appearance.colors(this@CaptureActivity).text); typeface = Appearance.uiTypeface(this@CaptureActivity); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10)); maxLines = 3 }
            }
        }
        setSelection(index)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
    companion object {
        private const val PERMISSION_SCAN = 301
        private const val PERMISSION_NOTIFICATION = 302
        private const val EXPORT_FILE = 303
        private const val IMPORT_FILE = 304
    }
}

