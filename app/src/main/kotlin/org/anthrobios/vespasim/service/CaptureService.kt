package org.anthrobios.vespasim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.anthrobios.vespasim.BuildConfig
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.VespaApplication
import org.anthrobios.vespasim.ble.AndroidCaptureScanner
import org.anthrobios.vespasim.core.RunStatus
import org.anthrobios.vespasim.core.capture.CaptureCodec
import org.anthrobios.vespasim.core.capture.CaptureEvent
import org.anthrobios.vespasim.core.capture.CaptureObservation
import org.anthrobios.vespasim.core.capture.CaptureParser
import org.anthrobios.vespasim.core.capture.CaptureRecord
import org.anthrobios.vespasim.ui.Appearance
import org.anthrobios.vespasim.ui.CapturePresentation
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

data class CaptureDevice(
    val address: String,
    val deviceName: String?,
    val advertisedName: String?,
    val logicalNanoAddress: String?,
    val nanoCandidate: Boolean,
    val vespaCandidate: Boolean,
    val fc65: Boolean,
    val lastSeenElapsedMs: Long,
    val lastObservation: CaptureObservation,
)

data class CaptureState(
    val discovering: Boolean = false,
    val recording: Boolean = false,
    val finishing: Boolean = false,
    val capture: CaptureRecord? = null,
    val devices: List<CaptureDevice> = emptyList(),
    val sampleCount: Long = 0,
    val lastObservation: CaptureObservation? = null,
    val message: String = "Zoek eerst toestellen, selecteer een radioadres en start daarna een opname.",
    val error: String? = null,
    val unsavedCount: Long = 0,
)

/** Explicit selected-address capture. The scan producer is bounded; disk writes and parsing are
 * serialized off the UI. Closing admission never drops already accepted callback records. */
class CaptureService : Service() {
    inner class LocalBinder : Binder() { val service: CaptureService get() = this@CaptureService }
    private data class StopRequest(val status: RunStatus, val message: String, val failure: Boolean = false)
    private class ScanSession(val recording: Boolean) {
        val inbox = CaptureInbox<CaptureObservation>(QUEUE_CAPACITY, if (recording) MAX_OBSERVATIONS else Long.MAX_VALUE)
        val drainScheduled = AtomicBoolean(false)
        val terminal = AtomicReference<StopRequest?>(null)
        var scannerStopped = false
        var storageFailed = false
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(CaptureState) -> Unit>()
    private val thread = HandlerThread("vespa-capture-writer")
    private lateinit var worker: Handler
    private lateinit var scanner: AndroidCaptureScanner
    private val repository get() = (application as VespaApplication).captureRepository
    private val gateOwner = "capture:${UUID.randomUUID()}"
    private val cancellationGeneration = AtomicLong()
    @Volatile private var live = CaptureState()
    @Volatile private var session: ScanSession? = null
    @Volatile private var visibleBinding = false
    @Volatile private var destroyed = false
    @Volatile private var requestQueued = false
    @Volatile private var foregroundReady = false
    private val devices = linkedMapOf<String, CaptureDevice>()
    private var capture: CaptureRecord? = null
    private var sampleCount = 0L
    private var eventCount = 0L
    private var unsavedCount = 0L
    private var lastObservation: CaptureObservation? = null
    private var message = live.message
    private var error: String? = null
    private var cleanupProblem: String? = null
    private var latestStartId = 0
    private var runStartId = 0
    private var timeout: Runnable? = null
    private var health: Runnable? = null
    private var lastPublishElapsed = 0L
    private var lastNotificationElapsed = 0L
    private val appearancePreferences by lazy { getSharedPreferences(Appearance.PREFERENCES, MODE_PRIVATE) }
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Appearance.LANGUAGE_KEY) main.post { refreshNotificationPresentation() }
    }
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                session?.let { requestStop(it, StopRequest(RunStatus.FAILED, "Bluetooth werd uitgeschakeld; opname gestopt", true)) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread.start()
        worker = Handler(thread.looper)
        scanner = AndroidCaptureScanner(this)
        appearancePreferences.registerOnSharedPreferenceChangeListener(appearanceListener)
        updateNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_EXPORTED)
        else registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        worker.post { CaptureCleanupRecovery.problem()?.let { failIdle("$it. Herstel Bluetooth/toestemming en kies Zoek toestellen om de vorige scannerstop opnieuw te proberen.") } }
    }

    override fun onBind(intent: Intent?): IBinder { visibleBinding = true; return binder }
    override fun onRebind(intent: Intent?) { visibleBinding = true; super.onRebind(intent) }
    override fun onUnbind(intent: Intent?): Boolean {
        visibleBinding = false
        stopDiscovery()
        return true
    }

    fun snapshot(): CaptureState = live
    fun addListener(listener: (CaptureState) -> Unit) {
        listeners.add(listener)
        main.post { if (listener in listeners) try { listener(live) } catch (_: Exception) { /* UI isolation. */ } }
    }
    fun removeListener(listener: (CaptureState) -> Unit) { listeners.remove(listener) }

    /** No automatic scan on bind/open, and discovery ends when the visible Activity unbinds. */
    fun startDiscovery() {
        val generation = cancellationGeneration.get()
        worker.post {
            if (destroyed || !visibleBinding || generation != cancellationGeneration.get()) return@post
            if (session != null || requestQueued) { failIdle("Stop eerst de huidige zoekactie of opname"); return@post }
            try {
                check((application as VespaApplication).initializationComplete) { "Archiefherstel loopt nog; probeer zo opnieuw" }
                retryCleanup()
                scanProblem(this)?.let { error(it) }
                if (destroyed || !visibleBinding || generation != cancellationGeneration.get()) return@post
                check(RadioSessionGate.tryAcquire(gateOwner)) { "De Bluetoothradio is bezig met uitzenden of naamherstel. Stop dat eerst." }
                devices.clear()
                error = null
                message = "Zoeken naar BLE-zendingen (maximaal 30 seconden)…"
                val current = ScanSession(false)
                session = current
                check(!destroyed && visibleBinding && generation == cancellationGeneration.get()) { "Zoeken geannuleerd door gebruiker of Android" }
                scanner.start(null, listener(current))
                scheduleBounds(current, DISCOVERY_MS)
                publish(true)
            } catch (failure: Exception) {
                val current = session
                if (current != null) requestStop(current,
                    if (destroyed || !visibleBinding || generation != cancellationGeneration.get()) StopRequest(RunStatus.STOPPED, "Zoeken geannuleerd door gebruiker of Android")
                    else StopRequest(RunStatus.FAILED, describe(failure), true))
                else { releaseGateIfClean(); failIdle(describe(failure)) }
            }
        }
    }

    fun stopDiscovery() {
        cancellationGeneration.incrementAndGet()
        session?.takeIf { !it.recording }?.let { requestStop(it, StopRequest(RunStatus.STOPPED, "Zoeken gestopt. Selecteer een recent gezien radioadres.")) }
    }

    /** Called only by an explicit button in the visible bound Activity. FGS creation stays on main. */
    fun startCapture(address: String, name: String, tagVariant: String = "unknown", notes: String = "", durationMs: Long = MAX_DURATION_MS) {
        val generation = cancellationGeneration.get()
        main.post {
            if (generation != cancellationGeneration.get()) return@post
            if (destroyed || requestQueued || live.recording || live.finishing) return@post
            if (!visibleBinding) { worker.post { failIdle("Start een opname vanuit het zichtbare opnamescherm") }; return@post }
            try {
                requestQueued = true
                startForegroundService(Intent(this, CaptureService::class.java).setAction(ACTION_START)
                    .putExtra("address", address).putExtra("name", name).putExtra("variant", tagVariant)
                    .putExtra("notes", notes).putExtra("durationMs", durationMs).putExtra("generation", generation))
            } catch (failure: Exception) {
                requestQueued = false
                worker.post { failIdle("Opname aanvragen mislukt: ${describe(failure)}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                try {
                    check(visibleBinding && requestQueued && !destroyed) { "Start vereist een expliciete actie in het zichtbare opnamescherm" }
                    check(intent.getLongExtra("generation", -1) == cancellationGeneration.get()) { "Opnamestart geannuleerd door gebruiker" }
                    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification("Opname voorbereiden…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    else startForeground(NOTIFICATION_ID, notification("Opname voorbereiden…"))
                    foregroundReady = true
                    worker.post { beginCapture(intent, startId) }
                } catch (failure: Exception) {
                    requestQueued = false
                    // Do not leave a startForegroundService request waiting behind slow disk I/O.
                    stopSelf(startId)
                    worker.post {
                        runStartId = startId
                        failIdle("Opname starten mislukt: ${describe(failure)}")
                        releaseStartedService()
                    }
                }
            }
            ACTION_STOP -> {
                cancellationGeneration.incrementAndGet()
                worker.post {
                    runStartId = startId
                    session?.let { requestStop(it, StopRequest(RunStatus.STOPPED, "Opname gestopt door gebruiker")) }
                        ?: releaseStartedService()
                }
            }
            else -> { if (session == null) stopSelf(startId) } // Never resume after process death.
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(intent: Intent, startId: Int) {
        runStartId = startId
        val generation = intent.getLongExtra("generation", -1)
        try {
            check(generation == cancellationGeneration.get()) { "Opnamestart geannuleerd door gebruiker" }
            check(!destroyed && visibleBinding && foregroundReady) { "Open het opnamescherm en start de opname opnieuw" }
            check((application as VespaApplication).initializationComplete) { "Archiefherstel loopt nog" }
            check(session?.recording != true) { "Er loopt al een opname" }
            val address = intent.getStringExtra("address").orEmpty().uppercase()
            val selected = devices[address] ?: error("Zoek eerst toestellen en selecteer het gewenste radioadres")
            require(CaptureSelection.isFresh(selected.lastSeenElapsedMs, SystemClock.elapsedRealtime())) { "Toestel is niet recent gezien. Zoek opnieuw en selecteer een actuele zending." }
            val duration = intent.getLongExtra("durationMs", MAX_DURATION_MS)
            require(duration in 1_000..MAX_DURATION_MS) { "Opnameduur moet tussen 1 seconde en 2 uur liggen" }
            // End discovery synchronously on this writer before creating selected-address evidence.
            session?.let { old ->
                old.inbox.close()
                old.terminal.compareAndSet(null, StopRequest(RunStatus.STOPPED, "Zoeken afgerond"))
                stopScanner(old)
                while (old.inbox.poll() != null) { /* Discovery has no durable recording. */ }
                session = null
                cancelBounds()
            }
            retryCleanup()
            scanProblem(this)?.let { error(it) }
            check(RadioSessionGate.tryAcquire(gateOwner)) { "De Bluetoothradio is bezig met uitzenden of naamherstel" }
            val record = CaptureRecord(
                id = UUID.randomUUID().toString(), name = intent.getStringExtra("name").orEmpty().trim(), address = address,
                startedAtMs = System.currentTimeMillis(), startedElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                tagVariant = intent.getStringExtra("variant") ?: "unknown", notes = intent.getStringExtra("notes").orEmpty(),
                appVersion = BuildConfig.VERSION_NAME, buildCommit = BuildConfig.GIT_COMMIT,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}",
                settings = mapOf("duration_limit_ms" to duration.toString(), "observation_limit" to MAX_OBSERVATIONS.toString(),
                    "queue_capacity" to QUEUE_CAPACITY.toString(), "platform_address_filter" to address,
                    "source" to "android_scan_result", "record_semantics" to "advertising_and_scan_response_combined",
                    "identity_scope" to "selected_radio_address_not_physical_tag_identity"),
            )
            CaptureCodec.validateRecord(record)
            repository.create(record)
            capture = record
            sampleCount = 0; eventCount = 0; unsavedCount = 0; lastObservation = null; error = null
            message = "Opname van $address loopt; elke ontvangen callback wordt automatisch opgeslagen."
            val current = ScanSession(true)
            session = current
            appendEvent("capture_started", message)
            check(!destroyed && generation == cancellationGeneration.get()) { "Opnamestart geannuleerd door gebruiker of Android" }
            val settings = scanner.start(address, listener(current))
            appendEvent("scan_configuration", "Android-scanner gestart met een adresfilter", settings)
            scheduleBounds(current, duration)
            publish(true)
        } catch (failure: Exception) {
            val current = session
            if (current?.recording == true) requestStop(current,
                if (destroyed || generation != cancellationGeneration.get()) StopRequest(RunStatus.STOPPED, "Opnamestart geannuleerd door gebruiker of Android")
                else StopRequest(RunStatus.FAILED, "Opname starten mislukt: ${describe(failure)}", true))
            else {
                requestQueued = false
                releaseGateIfClean()
                failIdle("Opname starten mislukt: ${describe(failure)}")
                releaseStartedService()
            }
        }
    }

    fun stopCapture() {
        cancellationGeneration.incrementAndGet()
        session?.takeIf { it.recording }?.let { requestStop(it, StopRequest(RunStatus.STOPPED, "Opname gestopt door gebruiker")) }
            ?: worker.post {
                try { retryCleanup(); publish(true) }
                catch (failure: Exception) { failIdle(describe(failure)) }
            }
    }

    private fun listener(current: ScanSession) = object : AndroidCaptureScanner.Listener {
        override fun onObservation(observation: CaptureObservation) {
            if (session !== current || destroyed) return
            when (current.inbox.offer(observation)) {
                CaptureInbox.Offer.ACCEPTED -> scheduleDrain(current)
                CaptureInbox.Offer.ACCEPTED_LIMIT -> requestStop(current, StopRequest(RunStatus.COMPLETED, "Limiet van $MAX_OBSERVATIONS metingen bereikt"))
                CaptureInbox.Offer.FULL -> requestStop(current, StopRequest(RunStatus.FAILED, "Opslag kan de zendingen niet volgen: wachtrij vol; minimaal 1 callback niet opgenomen. Geaccepteerde metingen worden afgewerkt.", true))
                CaptureInbox.Offer.CLOSED -> Unit // Explicitly late: no new evidence after admission closes.
            }
        }
        override fun onFailure(message: String) { requestStop(current, StopRequest(RunStatus.FAILED, message, true)) }
        // MATCH_LOST is a state notification, never a fresh measurement or a repeated cached RSSI.
    }

    private fun requestStop(current: ScanSession, request: StopRequest) {
        current.inbox.close()
        current.terminal.compareAndSet(null, request)
        scheduleDrain(current)
    }

    private fun scheduleDrain(current: ScanSession) {
        if (current.drainScheduled.compareAndSet(false, true)) worker.post { drain(current) }
    }

    private fun drain(current: ScanSession) {
        if (session !== current) { current.drainScheduled.set(false); return }
        if (current.terminal.get() != null) stopScanner(current)
        repeat(64) {
            val observation = current.inbox.poll() ?: return@repeat
            if (current.recording) {
                if (current.storageFailed) unsavedCount++
                else try {
                    val saved = observation.copy(sequence = sampleCount + 1)
                    repository.appendObservation(requireNotNull(capture).id, saved)
                    sampleCount++
                    lastObservation = saved
                } catch (failure: Exception) {
                    current.storageFailed = true
                    unsavedCount++
                    current.inbox.close()
                    current.terminal.set(StopRequest(RunStatus.FAILED, "Opslagfout: ${describe(failure)}. Niet bevestigd opgeslagen callbacks worden apart geteld.", true))
                    stopScanner(current)
                }
            } else updateDevice(observation)
        }
        publish(false)
        current.drainScheduled.set(false)
        if (current.inbox.size() > 0) scheduleDrain(current)
        else if (current.terminal.get() != null) complete(current)
    }

    private fun updateDevice(observation: CaptureObservation) {
        val parsed = CaptureParser.inspect(observation)
        if (!devices.containsKey(observation.address) && devices.size >= MAX_DEVICES) devices.remove(devices.keys.first())
        val previous = devices[observation.address]
        // Identity metadata remains separate: advertisedName always comes from this callback's bytes.
        devices[observation.address] = CaptureDevice(observation.address, observation.deviceName ?: previous?.deviceName,
            parsed.advertisedName, parsed.logicalNanoAddress, parsed.nanoCandidate, parsed.vespaCandidate, parsed.fc65,
            observation.timestampNanos / 1_000_000, observation)
    }

    private fun stopScanner(current: ScanSession) {
        if (current.scannerStopped) return
        current.scannerStopped = true
        cancelBounds()
        scanner.stop()?.let {
            cleanupProblem = it
            CaptureCleanupRecovery.remember(gateOwner, scanner::stop, it)
            current.terminal.set(StopRequest(RunStatus.FAILED, "${current.terminal.get()?.message.orEmpty()}. $it", true))
        }
    }

    private fun complete(current: ScanSession) {
        if (session !== current) return
        stopScanner(current)
        val terminal = current.terminal.get() ?: return
        message = terminal.message
        error = if (terminal.failure) message else null
        if (current.recording) {
            unsavedCount += current.inbox.overflowCount
            var status = terminal.status
            try {
                appendEvent("capture_finished", message, mapOf("status" to status.name,
                    "saved_observations" to sampleCount.toString(), "unsaved_callbacks_minimum" to unsavedCount.toString(),
                    "queue_overflow" to current.inbox.overflowCount.toString(), "scanner_stop_confirmed" to (cleanupProblem == null).toString()))
            } catch (failure: Exception) {
                status = RunStatus.FAILED
                error = "$message. Eindlog kon niet worden opgeslagen: ${describe(failure)}"
                message = error!!
            }
            val record = requireNotNull(capture)
            try {
                val endedAt = System.currentTimeMillis().coerceAtLeast(record.startedAtMs)
                repository.finish(record.id, status, endedAt)
                capture = record.copy(status = status, endedAtMs = endedAt)
            } catch (failure: Exception) {
                // Keep the durable RUNNING status truthful. Application recovery marks INTERRUPTED.
                error = "$message. Eindstatus niet opgeslagen: ${describe(failure)}. Herstel volgt bij de volgende appstart."
                message = error!!
            }
        }
        session = null
        requestQueued = false
        releaseGateIfClean()
        publish(true)
        if (current.recording) releaseStartedService()
        if (destroyed) thread.quitSafely()
    }

    private fun appendEvent(kind: String, message: String, details: Map<String, String> = emptyMap()) {
        repository.appendEvent(requireNotNull(capture).id, CaptureEvent(eventCount + 1, System.currentTimeMillis(), kind, message, details))
        eventCount++
    }

    private fun scheduleBounds(current: ScanSession, duration: Long) {
        cancelBounds()
        timeout = Runnable { requestStop(current, StopRequest(RunStatus.COMPLETED,
            if (current.recording) "Ingestelde opnameduur bereikt" else "Zoeken afgerond na 30 seconden. Kies een recent gezien toestel.")) }.also { worker.postDelayed(it, duration) }
        health = object : Runnable {
            override fun run() {
                if (session !== current || current.terminal.get() != null) return
                val problem = scanProblem(this@CaptureService)
                if (problem != null) requestStop(current, StopRequest(RunStatus.FAILED, problem, true))
                else worker.postDelayed(this, 2_000)
            }
        }.also { worker.postDelayed(it, 2_000) }
    }

    private fun cancelBounds() {
        timeout?.let(worker::removeCallbacks); timeout = null
        health?.let(worker::removeCallbacks); health = null
    }

    private fun retryCleanup() {
        if (CaptureCleanupRecovery.problem() == null) return
        cleanupProblem = CaptureCleanupRecovery.retry()
        check(cleanupProblem == null) { "$cleanupProblem. Herstel eerst de Bluetoothtoestemming en probeer opnieuw." }
        error = null
        message = "Vorige scannerstop bevestigd; Bluetoothradio is vrij."
    }

    private fun releaseGateIfClean() { if (cleanupProblem == null && session == null) RadioSessionGate.release(gateOwner) }

    private fun failIdle(reason: String) { error = reason; message = reason; publish(true) }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishElapsed < 200) return
        lastPublishElapsed = now
        val current = session
        val value = CaptureState(current?.recording == false && current.terminal.get() == null,
            current?.recording == true, current?.terminal?.get() != null, capture,
            devices.values.toList(), sampleCount, lastObservation, message, error, unsavedCount)
        live = value
        main.post {
            if (live !== value || destroyed) return@post
            for (listener in listeners) try { listener(value) } catch (_: Exception) { /* A UI listener cannot interrupt durable capture. */ }
            if (foregroundReady && now - lastNotificationElapsed >= 1_000) {
                lastNotificationElapsed = now
                try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value.message, value.sampleCount)) }
                catch (_: SecurityException) { /* Notification denial does not revoke the foreground service. */ }
            }
        }
    }

    private fun releaseStartedService() {
        val finishedStart = runStartId
        main.post {
            if (latestStartId == finishedStart && !live.recording && !requestQueued) {
                foregroundReady = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf(finishedStart)
        }
    }

    private fun notification(text: String, count: Long? = null): Notification {
        val localized = Appearance.wrapContext(this)
        val message = CapturePresentation.describe(localized, text).let {
            if (count == null) it else localized.getString(R.string.service_capture_count, count, it)
        }
        val stop = PendingIntent.getService(this, 31, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = Intent().setClassName(this, "org.anthrobios.vespasim.ui.CaptureActivity").putExtra("captureId", live.capture?.id)
        val content = PendingIntent.getActivity(this, 32, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(localized.getString(R.string.service_capture_title)).setContentText(message.take(180)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(content).addAction(Notification.Action.Builder(null, localized.getString(R.string.service_stop_capture), stop).build()).build()
    }

    private fun updateNotificationChannel() {
        val localized = Appearance.wrapContext(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localized.getString(R.string.service_capture_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = localized.getString(R.string.service_capture_channel_description)
            },
        )
    }

    /** Refresh notification text without changing the scan, writer or captured evidence. */
    private fun refreshNotificationPresentation() {
        if (destroyed) return
        updateNotificationChannel()
        if (foregroundReady) try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(live.message, live.sampleCount))
        } catch (_: SecurityException) { /* Notification permission does not control capture. */ }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshNotificationPresentation()
    }

    override fun onDestroy() {
        destroyed = true; visibleBinding = false
        appearancePreferences.unregisterOnSharedPreferenceChangeListener(appearanceListener)
        cancellationGeneration.incrementAndGet()
        try { unregisterReceiver(adapterReceiver) } catch (_: IllegalArgumentException) { }
        listeners.clear()
        // Close an existing producer immediately, then re-read on the writer: an in-flight
        // create/fsync may only publish its session after this main-thread lifecycle callback.
        session?.let { requestStop(it, StopRequest(RunStatus.STOPPED, "Service beëindigd door Android")) }
        worker.post {
            val current = session
            if (current != null) requestStop(current, StopRequest(RunStatus.STOPPED, "Service beëindigd door Android"))
            else { cancelBounds(); releaseGateIfClean(); thread.quitSafely() }
        }
        super.onDestroy()
    }

    companion object {
        const val MAX_DURATION_MS = 7_200_000L
        const val MAX_OBSERVATIONS = 50_000L
        private const val DISCOVERY_MS = 30_000L
        private const val MAX_DEVICES = 512
        private const val QUEUE_CAPACITY = 512
        private const val CHANNEL = "ble-captures"
        private const val NOTIFICATION_ID = 14
        private const val ACTION_START = "org.anthrobios.vespasim.CAPTURE_START"
        private const val ACTION_STOP = "org.anthrobios.vespasim.CAPTURE_STOP"
        fun scanProblem(context: Context): String? = AndroidCaptureScanner.scanProblem(context)
        private fun describe(failure: Exception): String = failure.message ?: failure.javaClass.simpleName
    }
}

