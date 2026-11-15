package org.anthrobios.vespasim.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*
import org.anthrobios.vespasim.BuildConfig
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.VespaApplication
import org.anthrobios.vespasim.ble.AndroidRadioDriver
import org.anthrobios.vespasim.ble.BleCapabilities
import org.anthrobios.vespasim.ble.AndroidTemporaryNameAdapter
import org.anthrobios.vespasim.ble.AndroidTemporaryNameStore
import org.anthrobios.vespasim.core.*
import org.anthrobios.vespasim.core.engine.*
import org.anthrobios.vespasim.ui.Appearance
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Read-only UI projection. Controller acceptance is deliberately not called transmission. */
data class LiveSnapshot(
    val run: RunRecord? = null,
    val summary: RunSummary? = null,
    val paused: Boolean = false,
    val currentFrame: PlannedFrame? = null,
    val message: String = "Gereed. Hardwareloze simulatie gebruikt geen Bluetooth.",
    val running: Boolean = false,
    val preparingStart: Boolean = false,
    val preparingName: Boolean = false,
    val restoringName: Boolean = false,
    val nameRecoveryMessage: String? = null,
)

class SimulationService : Service() {
    companion object {
        const val ACTION_STOP = "org.anthrobios.vespasim.STOP"
        const val ACTION_RESTORE_NAME = "org.anthrobios.vespasim.RESTORE_NAME"
        const val ACTION_PREPARE_DRY_RUN = "org.anthrobios.vespasim.PREPARE_DRY_RUN"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_GENERATION = "startGeneration"
        private const val CHANNEL = "vespa_simulation"
        private const val NOTIFICATION_ID = 21
    }

    inner class LocalBinder : Binder() { val service: SimulationService get() = this@SimulationService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private val repository get() = (application as VespaApplication).repository
    private val listeners = CopyOnWriteArrayList<(LiveSnapshot) -> Unit>()
    @Volatile private var live = LiveSnapshot()
    @Volatile private var foregroundReady = false
    @Volatile private var destroyed = false
    @Volatile private var requestQueued = false
    @Volatile private var boundClient = false
    @Volatile private var latestStartId = 0
    private var runStartId = 0
    private var engine: SimulationEngine? = null
    private val radioOwner = "simulation:${UUID.randomUUID()}"
    private var radioClaimed = false
    private var radioDriver: AndroidRadioDriver? = null
    private var awaitingRadioCleanup = false
    private val startGeneration = AtomicLong()
    private lateinit var names: TemporaryNameSession
    private var preparingName = false
    private var restoringName = false
    private var recoveringName = false
    private var finishing = false
    private var nameRecoveryMessage: String? = null
    private var nameLogFailure: String? = null
    private var runMonotonicStart = 0L
    private var lastEventElapsed = 0L
    @Volatile private var active = false
    private var run: RunRecord? = null
    private var engineState = EngineState(false, false, null, 0, "Gereed")
    private var eventCount = 0L
    private var accepted = 0L
    private var simulated = 0L
    private var late = 0L
    private var errors = 0L
    private var lastNotificationMs = 0L
    private var backgroundStop: Runnable? = null
    private var idleStop: Runnable? = null
    private var healthCheck: Runnable? = null
    private val appearancePreferences by lazy { getSharedPreferences(Appearance.PREFERENCES, MODE_PRIVATE) }
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Appearance.LANGUAGE_KEY) main.post { refreshNotificationPresentation() }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                worker.post { if (active && run?.mode == RunMode.BLE) stopActive(RunStatus.FAILED, "Bluetoothadapter uitgeschakeld of verwijderd") }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("vespa-simulation", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        worker = Handler(thread.looper)
        names = TemporaryNameSession(AndroidTemporaryNameAdapter(this), AndroidTemporaryNameStore(noBackupFilesDir), scheduler(),
            onEvent = { kind, message ->
                if (active) try { appendNameEvent(if (kind == "name_recovery_pending") "name_recovery_failed" else kind, message) }
                catch (error: Exception) { nameLogFailure = error.message ?: error.javaClass.simpleName }
            })
        worker.post { refreshNameRecovery(); publish("Gereed. Hardwareloze simulatie gebruikt geen Bluetooth.") }
        appearancePreferences.registerOnSharedPreferenceChangeListener(appearanceListener)
        updateNotificationChannel()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(adapterReceiver, filter, RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(adapterReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClient = true
        cancelBackgroundStop()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        boundClient = true
        cancelBackgroundStop()
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClient = false
        if (!active && !recoveringName) {
            startGeneration.incrementAndGet()
            requestQueued = false
            worker.post { if (!active && !recoveringName) publish("Startaanvraag geannuleerd: scherm niet meer zichtbaar.") }
        }
        worker.post { scheduleBackgroundStop() }
        return true
    }

    private fun scheduleBackgroundStop() {
        backgroundStop?.let { worker.removeCallbacks(it) }
        if (!boundClient && active && run?.mode == RunMode.DRY_RUN) {
            val task = Runnable {
                if (!boundClient && active && run?.mode == RunMode.DRY_RUN) engine?.stop("Hardwareloze proef gestopt: app niet meer zichtbaar")
            }
            backgroundStop = task
            worker.postDelayed(task, 2_000)
        }
    }

    private fun cancelBackgroundStop() {
        worker.post { backgroundStop?.let { worker.removeCallbacks(it) }; backgroundStop = null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            startGeneration.incrementAndGet()
            worker.post {
                runStartId = startId
                if (active) stopActive(RunStatus.STOPPED, "Gestopt door gebruiker")
                else if (!recoveringName) { requestQueued = false; releaseRadio(); publish("Startaanvraag geannuleerd."); releaseStartedService() }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTORE_NAME) {
            try {
                if (Build.VERSION.SDK_INT >= 31) check(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "Bluetoothtoestemming ontbreekt" }
                promoteForeground("Bluetoothnaam herstellen…")
                worker.post { recoverName(startId) }
            } catch (error: Exception) {
                requestQueued = false
                publishFailure("Naamherstel kon niet starten: ${error.message ?: error.javaClass.simpleName}")
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val mode = if (intent?.action == ACTION_PREPARE_DRY_RUN) RunMode.DRY_RUN else RunMode.BLE
        if (mode == RunMode.BLE) {
            try {
                checkBlePermission()
                promoteForeground("BLE voorbereiden…")
            } catch (e: Exception) {
                foregroundReady = false
                requestQueued = false
                publishFailure("BLE-voorgrondservice kon niet starten: ${e.message ?: e.javaClass.simpleName}")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        val profileText = intent?.getStringExtra(EXTRA_PROFILE)
        if (profileText != null) {
            startPrepared(profileText, mode, intent.getIntExtra(EXTRA_SPEED, 1), startId, intent.getLongExtra(EXTRA_GENERATION, -1))
        } else worker.post {
            idleStop?.let { worker.removeCallbacks(it) }
            val task = Runnable {
                if (!active && !recoveringName) main.post {
                    if (latestStartId == startId) {
                        foregroundReady = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    stopSelf(startId)
                }
            }
            idleStop = task
            worker.postDelayed(task, 10_000)
        }
        return START_NOT_STICKY
    }

    fun snapshot(): LiveSnapshot = live

    fun addListener(listener: (LiveSnapshot) -> Unit) {
        listeners.addIfAbsent(listener)
        main.post { if (listeners.contains(listener)) listener(live) }
    }

    fun removeListener(listener: (LiveSnapshot) -> Unit) { listeners.remove(listener) }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    /** Initiated by a visible Activity; the intent serializes promotion before engine startup. */
    fun startRun(profile: SimulationProfile, mode: RunMode, speed: Int = 1) {
        onMain request@{
            if (requestQueued || live.running) {
                worker.post { publish("Er loopt al een scenario of een startaanvraag. Stop dat eerst.") }
                return@request
            }
            try {
                check((application as VespaApplication).initializationComplete) { "Profielen en logherstel worden nog voorbereid" }
                check(boundClient && !destroyed) { "Start vanuit het zichtbare scherm" }
                requestQueued = true
                val generation = startGeneration.incrementAndGet()
                worker.post prepareWork@{
                    if (generation != startGeneration.get()) return@prepareWork
                    publish("Profiel controleren; nog geen radio gestart.")
                    val encoded = runCatching { ProfileCodec.encode(profile) }
                    main.post prepared@{
                        if (destroyed || generation != startGeneration.get() || !boundClient) {
                            if (generation == startGeneration.get()) requestQueued = false
                            return@prepared
                        }
                        try {
                            val command = Intent(this, SimulationService::class.java)
                                .putExtra(EXTRA_PROFILE, encoded.getOrThrow()).putExtra(EXTRA_SPEED, speed).putExtra(EXTRA_GENERATION, generation)
                            if (mode == RunMode.DRY_RUN) command.action = ACTION_PREPARE_DRY_RUN
                            if (mode == RunMode.BLE) startForegroundService(command) else startService(command)
                        } catch (error: Exception) {
                            requestQueued = false
                            publishFailure("Startaanvraag mislukt: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            } catch (e: Exception) {
                requestQueued = false
                publishFailure("Startaanvraag mislukt: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun startPrepared(profileText: String, mode: RunMode, speed: Int, startId: Int, generation: Long) {
        worker.post {
            if (destroyed || generation != startGeneration.get() || !requestQueued) {
                if (!active && !recoveringName && !requestQueued) {
                    runStartId = startId
                    publish("Startaanvraag geannuleerd; geen radio gestart.")
                    releaseStartedService()
                } else main.post { stopSelf(startId) }
                return@post
            }
            if (active || recoveringName) { runStartId = startId; publish("Er loopt al een scenario of naamherstel. Wacht tot dit is afgerond."); return@post }
            runStartId = startId
            try {
                check((application as VespaApplication).initializationComplete) { "Gegevensinitialisatie nog niet voltooid" }
                require(speed in 1..100 && (mode != RunMode.BLE || speed == 1)) { "BLE gebruikt snelheid 1; hardwareloos 1–100" }
                if (mode == RunMode.BLE) {
                    check(foregroundReady) { "Start eerst de BLE-voorgrondservice vanuit het zichtbare scherm" }
                    checkBlePermission()
                }
                val frozen = ProfileCodec.decode(profileText)
                if (mode == RunMode.BLE) {
                    validateBleHardware(frozen)
                    if (frozen.radio.localName != null) check(names.pending() == null) { "Er staat Bluetoothnaamherstel open. Kies eerst Bluetoothnaam herstellen; dry-run blijft beschikbaar." }
                    claimRadio()
                }
                val plan = Planner.plan(frozen)
                check(generation == startGeneration.get() && boundClient && !destroyed) { "Startaanvraag geannuleerd vóór uitvoering" }
                val record = RunRecord(
                    id = UUID.randomUUID().toString(), profile = frozen, mode = mode,
                    startedAtMs = System.currentTimeMillis().coerceAtLeast(0),
                    appVersion = BuildConfig.VERSION_NAME, buildCommit = BuildConfig.GIT_COMMIT,
                    deviceInfo = BleCapabilities.inspect(this), speed = speed,
                )
                repository.createRun(record)
                engine = null
                run = record
                active = true
                eventCount = 0; accepted = 0; simulated = 0; late = 0; errors = 0
                runMonotonicStart = SystemClock.elapsedRealtime(); lastEventElapsed = 0
                finishing = false; preparingName = false; restoringName = false; nameLogFailure = null
                engineState = EngineState(true, false, null, 0, "Scenario voorbereiden")
                val radio = if (mode == RunMode.BLE) AndroidRadioDriver(this, worker,
                    onCleanupConfirmed = { cleanupConfirmed() }, onFatal = { reason -> engine?.fail(reason) }).also { radioDriver = it }
                    else DryRadio
                val scheduler = scheduler()
                engine = SimulationEngine(record, plan, radio, scheduler,
                    wallClock = System::currentTimeMillis,
                    appendEvent = { event -> appendRunEvent(event, fromEngine = true) },
                    onState = { state -> engineState = state; publish(state.message) },
                    onFinished = { status, reason -> finish(record.id, status, reason) },
                )
                idleStop?.let { worker.removeCallbacks(it) }; idleStop = null
                // A durable create may outlast the visible-screen request. Preserve the record,
                // but never use the radio after cancellation during that write.
                if (generation != startGeneration.get() || !boundClient || destroyed) {
                    finish(record.id, RunStatus.STOPPED, "Startaanvraag geannuleerd vóór radio-uitvoering")
                    return@post
                }
                if (mode == RunMode.BLE && frozen.radio.localName != null) {
                    preparingName = true
                    publish("Bluetoothnaam tijdelijk instellen op ${frozen.radio.localName}; nog geen uitzending.")
                    names.prepare(frozen.radio.localName, record.id) { result ->
                        if (!active || finishing) return@prepare
                        preparingName = false
                        val failure = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: nameLogFailure
                        if (failure != null) finish(record.id, RunStatus.FAILED, "Bluetoothnaam voorbereiden mislukt: $failure")
                        else beginEngine(mode, generation)
                    }
                } else beginEngine(mode, generation)
            } catch (e: Exception) {
                if (active) {
                    val reason = "Start mislukt: ${e.message ?: e.javaClass.simpleName}"
                    if (engine != null && !preparingName && engineState.message != "Scenario voorbereiden") stopActive(RunStatus.FAILED, reason) else {
                        val record = run!!
                        try {
                            appendRunEvent(RunEvent(1, System.currentTimeMillis().coerceAtLeast(0), 0,
                                "startup_failed", message = reason))
                        } catch (_: Exception) { /* finish records failure or keeps recoverable RUNNING metadata. */ }
                        finish(record.id, RunStatus.FAILED, reason)
                    }
                }
                else {
                    releaseRadio()
                    if (generation == startGeneration.get()) requestQueued = false
                    refreshNameRecovery()
                    publishFailure("Start mislukt: ${e.message ?: e.javaClass.simpleName}")
                    releaseStartedService()
                }
            }
        }
    }

    fun stopRun() {
        startGeneration.incrementAndGet()
        main.post {
            try { startService(Intent(this, SimulationService::class.java).setAction(ACTION_STOP)) }
            catch (e: Exception) { worker.post { if (active) stopActive(RunStatus.STOPPED, "Gestopt door gebruiker") else if (!recoveringName) {
                requestQueued = false; releaseRadio(); publish("Startaanvraag geannuleerd."); releaseStartedService()
            } } }
        }
    }

    fun setPaused(paused: Boolean) { worker.post { if (!preparingName && !restoringName) engine?.setPaused(paused) } }

    private fun scheduler() = object : EngineScheduler {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellation {
            val work = Runnable(task)
            worker.postDelayed(work, delayMs)
            return Cancellation { worker.removeCallbacks(work) }
        }
    }

    private fun promoteForeground(message: String) {
        val notice = notification(message)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notice)
        foregroundReady = true
    }

    private fun beginEngine(mode: RunMode, generation: Long) {
        try {
            if (generation != startGeneration.get() || destroyed) {
                run?.let { finish(it.id, RunStatus.STOPPED, "Startaanvraag geannuleerd vóór radio-uitvoering") }
                return
            }
            engine!!.start()
            if (mode == RunMode.BLE && active) scheduleHealthCheck()
            if (mode == RunMode.DRY_RUN && active) scheduleBackgroundStop()
        } catch (error: Exception) {
            run?.let { finish(it.id, RunStatus.FAILED, "Scenario starten mislukt: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun stopActive(status: RunStatus, reason: String) {
        if (!active || finishing) return
        if (preparingName) run?.let { finish(it.id, status, reason) }
        else if (status == RunStatus.STOPPED) engine?.stop(reason) else engine?.fail(reason)
    }

    private fun appendRunEvent(event: RunEvent, fromEngine: Boolean = false) {
        val record = run ?: error("Geen actieve proef voor logregel")
        val namedBle = record.mode == RunMode.BLE && record.profile.radio.localName != null
        val kind = if (namedBle && fromEngine && event.kind == "run_finished") "scenario_finished" else event.kind
        val elapsed = if (namedBle)
            (SystemClock.elapsedRealtime() - runMonotonicStart).coerceAtLeast(lastEventElapsed) else event.elapsedMs
        repository.appendEvent(record.id, event.copy(sequence = eventCount + 1, elapsedMs = elapsed, kind = kind))
        eventCount++; lastEventElapsed = elapsed
        when (event.kind) {
            "controller_accepted" -> accepted++
            "simulated_frame" -> simulated++
            "frame_skipped_late" -> late++
        }
        if (event.kind == "error" || event.kind.endsWith("_error") || event.kind.endsWith("_failed")) errors++
    }

    private fun appendNameEvent(kind: String, message: String) = appendRunEvent(RunEvent(
        sequence = eventCount + 1, atEpochMs = System.currentTimeMillis().coerceAtLeast(0),
        elapsedMs = (SystemClock.elapsedRealtime() - runMonotonicStart).coerceAtLeast(0), kind = kind, message = message,
        details = mapOf("requestedLocalName" to run?.profile?.radio?.localName.orEmpty(), "scope" to "global_bluetooth_adapter"),
    ))

    private fun refreshNameRecovery() {
        nameRecoveryMessage = try {
            names.pending()?.let { "Bluetoothnaamherstel staat open: tijdelijke naam ${it.temporaryName}. Oorspronkelijke naam: ${it.originalName}. Schakel Bluetooth in en kies Bluetoothnaam herstellen. Er wordt geen uitzending gestart." }
        } catch (error: Exception) { "Bluetoothnaam-herstelbestand kan niet worden gelezen: ${error.message ?: error.javaClass.simpleName}. Het bestand blijft bewaard; er wordt geen nieuwe naam ingesteld." }
    }

    /** Explicit user action after interrupted cleanup; never starts an advertising session. */
    fun restoreBluetoothName() {
        main.post {
            if (requestQueued || live.running) return@post
            try {
                requestQueued = true
                startForegroundService(Intent(this, SimulationService::class.java).setAction(ACTION_RESTORE_NAME))
            } catch (error: Exception) {
                requestQueued = false
                publishFailure("Naamherstel aanvragen mislukt: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun recoverName(startId: Int) {
        runStartId = startId
        if (active || recoveringName) { publish("Stop eerst de actieve proef of wacht tot naamherstel klaar is."); return }
        try { claimRadio() } catch (error: Exception) {
            requestQueued = false
            publish("Naamherstel kon niet starten: ${error.message}")
            releaseStartedService()
            return
        }
        recoveringName = true; restoringName = true
        publish("Oorspronkelijke Bluetoothnaam herstellen; er wordt geen radio gestart.")
        names.restore { result ->
            recoveringName = false; restoringName = false; requestQueued = false
            refreshNameRecovery()
            releaseRadio()
            publish(result.fold({ "Bluetoothnaamherstel afgerond; er wordt niets uitgezonden." }, { "Bluetoothnaamherstel niet afgerond: ${it.message ?: it.javaClass.simpleName}" }))
            releaseStartedService()
            if (destroyed) thread.quitSafely()
        }
    }

    private fun finish(runId: String, status: RunStatus, reason: String) {
        if (finishing) return
        finishing = true
        preparingName = false
        healthCheck?.let { worker.removeCallbacks(it) }; healthCheck = null
        backgroundStop?.let { worker.removeCallbacks(it) }; backgroundStop = null
        val record = run ?: return
        if (record.mode != RunMode.BLE || record.profile.radio.localName == null) {
            finishRecorded(runId, status, reason)
            return
        }
        val journal = try { names.pending() } catch (error: Exception) {
            refreshNameRecovery()
            finishRecorded(runId, RunStatus.FAILED, "$reason. Bluetoothnaamherstel niet controleerbaar: ${error.message ?: error.javaClass.simpleName}; herstelbestand blijft bewaard.")
            return
        }
        if (journal == null) {
            refreshNameRecovery()
            finishRecorded(runId, if (nameLogFailure == null) status else RunStatus.FAILED,
                if (nameLogFailure == null) reason else "$reason. Naamlog kon niet worden opgeslagen: $nameLogFailure")
            return
        }
        if (journal.ownerRunId != runId) {
            refreshNameRecovery()
            finishRecorded(runId, RunStatus.FAILED, "$reason. Naamherstel hoort bij een andere proef; oorspronkelijke gegevens blijven bewaard.")
            return
        }
        restoringName = true
        publish("$reason. Bluetoothnaam herstellen…")
        names.restore { result ->
            restoringName = false
            refreshNameRecovery()
            val failure = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: nameLogFailure
            val finalReason = if (failure == null) reason else "$reason. Naamherstel of logopslag niet afgerond: $failure"
            finishRecorded(runId, if (failure == null) status else RunStatus.FAILED, finalReason)
        }
    }

    private fun finishRecorded(runId: String, status: RunStatus, reason: String) {
        var finalMessage = reason
        var finalStatus = status
        val record = run ?: return
        if (record.mode == RunMode.BLE && record.profile.radio.localName != null) {
            try {
                appendRunEvent(RunEvent(eventCount + 1, System.currentTimeMillis().coerceAtLeast(0), lastEventElapsed,
                    "run_finished", message = reason, details = mapOf("status" to status.name, "nameRecoveryPending" to (nameRecoveryMessage != null).toString())))
            } catch (error: Exception) {
                finalStatus = RunStatus.FAILED
                finalMessage = "$reason. Laatste logregel kon niet worden opgeslagen: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        try {
            val ended = System.currentTimeMillis().coerceAtLeast(record.startedAtMs)
            repository.finishRun(runId, finalStatus, ended)
            run = record.copy(status = finalStatus, endedAtMs = ended)
        } catch (e: Exception) {
            // Durable metadata remains RUNNING and is recovered as INTERRUPTED on next process start.
            finalMessage = "$reason. Eindstatus kon niet worden opgeslagen: ${e.message ?: e.javaClass.simpleName}"
            run = record.copy(status = RunStatus.FAILED, endedAtMs = System.currentTimeMillis().coerceAtLeast(record.startedAtMs))
        }
        active = false
        requestQueued = false
        if (!releaseRadio()) finalMessage += ". Radio-stop nog niet bevestigd; zoeken en uitzenden blijven geblokkeerd tot de stopbevestiging. Zet Bluetooth uit en stop de app volledig via App-info als dit blijft duren; open daarna opnieuw."
        engineState = engineState.copy(running = false)
        healthCheck?.let { worker.removeCallbacks(it) }; healthCheck = null
        backgroundStop?.let { worker.removeCallbacks(it) }; backgroundStop = null
        publish(finalMessage)
        releaseStartedService()
        if (destroyed) thread.quitSafely()
    }

    private fun releaseStartedService() {
        val finishedStartId = runStartId
        main.post {
            if (latestStartId == finishedStartId && !live.running && !requestQueued) {
                foregroundReady = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf(finishedStartId)
        }
    }

    @Synchronized private fun claimRadio() {
        check(radioDriver?.isCleanupConfirmed != false) { "De vorige radio-stop is nog niet bevestigd. Wacht op de stopbevestiging of zet Bluetooth uit, stop de app volledig via App-info en open opnieuw." }
        check(RadioSessionGate.tryAcquire(radioOwner)) { "Stop eerst het zoeken of opnemen voordat je uitzendt of de Bluetoothnaam herstelt." }
        radioClaimed = true
        awaitingRadioCleanup = false
    }

    @Synchronized private fun releaseRadio(): Boolean {
        if (radioClaimed && radioDriver?.isCleanupConfirmed == false) {
            awaitingRadioCleanup = true
            return false
        }
        if (radioClaimed) RadioSessionGate.release(radioOwner)
        radioClaimed = false
        awaitingRadioCleanup = false
        return true
    }

    @Synchronized private fun cleanupConfirmed() {
        if (awaitingRadioCleanup) releaseRadio()
    }

    private fun publish(message: String) {
        val record = run
        val value = LiveSnapshot(
            run = record,
            summary = record?.let { RunSummary(it, eventCount, accepted, simulated, late, errors) },
            paused = engineState.paused,
            currentFrame = engineState.currentFrame,
            message = message,
            running = active || recoveringName || requestQueued,
            preparingStart = requestQueued && !active && !recoveringName,
            preparingName = preparingName,
            restoringName = restoringName,
            nameRecoveryMessage = nameRecoveryMessage,
        )
        live = value
        main.post {
            if (live !== value) return@post
            for (listener in listeners) try { listener(value) } catch (_: Exception) { /* UI must not corrupt a running recording. */ }
            if (foregroundReady && SystemClock.elapsedRealtime() - lastNotificationMs >= 1_000) {
                lastNotificationMs = SystemClock.elapsedRealtime()
                try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message)) }
                catch (_: SecurityException) { /* POST_NOTIFICATIONS denial does not cancel the foreground service. */ }
            }
        }
    }

    private fun publishFailure(message: String) {
        worker.post {
            if (active) stopActive(RunStatus.FAILED, message) else {
                engineState = engineState.copy(running = false)
                publish(message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleHealthCheck() {
        val task = object : Runnable {
            override fun run() {
                if (!active || run?.mode != RunMode.BLE) return
                try {
                    checkBlePermission()
                    check(getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) { "Bluetoothadapter niet ingeschakeld" }
                    worker.postDelayed(this, 1_000)
                } catch (e: Exception) { stopActive(RunStatus.FAILED, "Bluetoothcontrole: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
        healthCheck = task
        worker.postDelayed(task, 1_000)
    }

    @SuppressLint("MissingPermission")
    private fun validateBleHardware(profile: SimulationProfile) {
        checkBlePermission()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error("Deze telefoon heeft geen Bluetoothadapter.")
        check(adapter.isEnabled) { "Bluetooth is uitgeschakeld. Schakel het in via de telefooninstellingen en probeer opnieuw." }
        check(adapter.bluetoothLeAdvertiser != null) { "Deze telefoon biedt geen BLE-advertiser aan." }
        if (profile.radio.mode != RadioMode.LEGACY_1M) check(adapter.isLeExtendedAdvertisingSupported) { "Deze telefoon ondersteunt geen extended advertising. Kies expliciet een legacy-profiel." }
        if (profile.radio.mode == RadioMode.EXTENDED_CODED) check(adapter.isLeCodedPhySupported) { "Deze telefoon ondersteunt geen Coded PHY." }
    }

    private fun checkBlePermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) { "BLUETOOTH_ADVERTISE ontbreekt" }
            check(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "BLUETOOTH_CONNECT ontbreekt" }
        }
    }

    private fun notification(message: String): Notification {
        val localized = Appearance.wrapContext(this)
        val stop = PendingIntent.getService(this, 1, Intent(this, SimulationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(localized.getString(R.string.service_simulation_title))
            .setContentText(ServicePresentation.describe(localized, message).take(180)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(content)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.service_stop_transmission), stop).build())
            .build()
    }

    private fun updateNotificationChannel() {
        val localized = Appearance.wrapContext(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localized.getString(R.string.service_simulation_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = localized.getString(R.string.service_simulation_channel_description)
            },
        )
    }

    /** Locale changes update presentation only, including while a scenario is paused. */
    private fun refreshNotificationPresentation() {
        if (destroyed) return
        updateNotificationChannel()
        if (foregroundReady) try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(live.message))
        } catch (_: SecurityException) { /* Notification permission does not control the radio. */ }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshNotificationPresentation()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        worker.post { if (active && run?.mode == RunMode.DRY_RUN) engine?.stop("Hardwareloze proef gestopt: app verwijderd uit recente apps") }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        appearancePreferences.unregisterOnSharedPreferenceChangeListener(appearanceListener)
        startGeneration.incrementAndGet()
        try { unregisterReceiver(adapterReceiver) } catch (_: IllegalArgumentException) { }
        listeners.clear()
        worker.post {
            idleStop?.let { worker.removeCallbacks(it) }
            backgroundStop?.let { worker.removeCallbacks(it) }
            healthCheck?.let { worker.removeCallbacks(it) }
            if (active) stopActive(RunStatus.STOPPED, "Service beëindigd door Android") else if (!recoveringName) { releaseRadio(); thread.quitSafely() }
        }
        super.onDestroy()
    }

    private object DryRadio : RadioDriver {
        override fun apply(frame: PlannedFrame, settings: RadioSettings, complete: (RadioResult) -> Unit) = error("Dry-run mag geen radio aanroepen")
        override fun silence(complete: (RadioResult) -> Unit) = error("Dry-run mag geen radio aanroepen")
        override fun close(complete: (RadioResult) -> Unit) = error("Dry-run mag geen radio aanroepen")
    }
}

