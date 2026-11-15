package org.anthrobios.vespasim.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.AtomicFile
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.ble.AndroidBeaconRadio
import org.anthrobios.vespasim.core.beacon.*
import org.anthrobios.vespasim.core.engine.Cancellation
import org.anthrobios.vespasim.core.engine.EngineScheduler
import org.anthrobios.vespasim.ui.Appearance
import org.anthrobios.vespasim.ui.BeaconActivity
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class BeaconSnapshot(val identity: BeaconIdentity? = null, val state: BeaconState = BeaconState(), val preparing: Boolean = false)

/** Serializes request ownership, not radio or disk work. A Stop changes the command but not session. */
internal class BeaconRequests {
    data class Stop(val command: Long, val session: Long)
    private var command = 0L
    private var session = 0L
    private var pending = false

    @Synchronized fun request(allowed: () -> Boolean, prepare: () -> Unit): Long? {
        if (pending || !allowed()) return null
        session = ++command; pending = true; prepare()
        return session
    }
    @Synchronized fun cancel(): Stop { pending = false; return Stop(++command, session) }
    @Synchronized fun cancelPending(): Stop? = if (pending) cancel() else null
    @Synchronized fun isCurrentStart(token: Long): Boolean = token == command && token == session
    @Synchronized fun forCommand(token: Long, action: () -> Unit) { if (token == command) action() }
    @Synchronized fun forSession(token: Long, action: () -> Unit) { if (token == session) action() }
    @Synchronized fun finishStart(token: Long, action: () -> Unit) {
        if (isCurrentStart(token)) { pending = false; action() }
    }
    @Synchronized fun commitStart(token: Long, allowed: () -> Boolean, commit: () -> Unit): Boolean {
        if (!pending || !isCurrentStart(token) || !allowed()) return false
        // Publish STARTING before dropping pending, without a gap for a second request.
        commit(); pending = false
        return true
    }
    @Synchronized fun canCleanForeground(token: Long, idle: () -> Boolean): Boolean =
        token == session && !pending && idle()
}

/** Explicitly started foreground session; never resumes after process death or reboot. */
class BeaconService : Service() {
    companion object {
        const val ACTION_STOP = "org.anthrobios.vespasim.BEACON_STOP"
        private const val ACTION_START = "org.anthrobios.vespasim.BEACON_START"
        private const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "ovt_test_beacon"
        private const val NOTIFICATION_ID = 23
    }
    inner class LocalBinder : Binder() { val service: BeaconService get() = this@BeaconService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var sessionStore: BeaconSessionStore
    private val listeners = CopyOnWriteArrayList<(BeaconSnapshot) -> Unit>()
    @Volatile private var live = BeaconSnapshot()
    @Volatile private var boundClient = false
    @Volatile private var destroyed = false
    private val requests = BeaconRequests()
    private var foreground = false
    private var engine: BeaconEngine? = null
    private var radio: AndroidBeaconRadio? = null
    private val owner = "beacon:${UUID.randomUUID()}"
    private var claimed = false
    private var latestStartId = 0
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post {
            if (!destroyed) {
                refreshChannel()
                if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(Appearance.wrapContext(base)) }

    private val health = object : Runnable {
        override fun run() {
            if (destroyed || !live.state.ownsRadio) return
            val problem = radio?.availability() ?: BeaconIssue.INTERRUPTED
            if (problem != BeaconIssue.NONE) engine?.fail(problem)
            if (problem == BeaconIssue.BLUETOOTH_OFF && radio?.isAdapterOff() == true) radio?.adapterTurnedOff()
            if (live.state.ownsRadio) worker.postDelayed(this, 2_000)
        }
    }
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            worker.post {
                if (state != BluetoothAdapter.STATE_ON) engine?.fail(BeaconIssue.BLUETOOTH_OFF)
                if (state == BluetoothAdapter.STATE_OFF) radio?.adapterTurnedOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ovt-test-beacon", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        worker = Handler(thread.looper)
        sessionStore = BeaconSessionStore(File(noBackupFilesDir, BeaconSessionStore.FILE_NAME))
        refreshChannel()
        getSharedPreferences(Appearance.PREFERENCES, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesListener)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(adapterReceiver, filter, RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(adapterReceiver, filter)
        worker.post {
            try {
                val identity = loadIdentity()
                val restored = if (sessionStore.wasRunning()) BeaconState(BeaconPhase.FAILED, BeaconIssue.INTERRUPTED) else BeaconState()
                publish(live.copy(identity = identity, state = restored))
            }
            catch (_: Exception) { publish(live.copy(state = BeaconState(BeaconPhase.FAILED, BeaconIssue.STORAGE))) }
        }
    }

    override fun onBind(intent: Intent?): IBinder { boundClient = true; return binder }
    override fun onRebind(intent: Intent?) { boundClient = true; super.onRebind(intent) }
    override fun onUnbind(intent: Intent?): Boolean {
        boundClient = false
        requests.cancelPending()?.let { cancelled -> worker.post {
            requests.forCommand(cancelled.command) { if (!live.state.ownsRadio) publish(live.copy(preparing = false)) }
            stopForegroundSession(cancelled.session)
        } }
        return true
    }

    fun snapshot(): BeaconSnapshot = live
    fun addListener(listener: (BeaconSnapshot) -> Unit) { listeners.add(listener); main.post { if (listener in listeners) listener(live) } }
    fun removeListener(listener: (BeaconSnapshot) -> Unit) { listeners.remove(listener) }

    /** Called synchronously by a visible bound Activity after an explicit tap and permission grant. */
    fun startBeacon(): Boolean {
        val token = requests.request(
            { boundClient && !destroyed && !live.state.ownsRadio && live.identity != null },
            { publish(live.copy(preparing = true)) },
        ) ?: return false
        try {
            startForegroundService(Intent(this, BeaconService::class.java).setAction(ACTION_START).putExtra(EXTRA_GENERATION, token))
        } catch (_: Exception) {
            requests.finishStart(token) { publish(live.copy(preparing = false, state = BeaconState(BeaconPhase.FAILED, BeaconIssue.FOREGROUND))) }
            stopForegroundSession(token)
            return false
        }
        return true
    }

    fun stopBeacon() {
        val stop = requests.cancel()
        worker.post {
            var engineToStop: BeaconEngine? = null
            requests.forCommand(stop.command) {
                if (engine != null && live.state.ownsRadio) engineToStop = engine
                else publish(live.copy(preparing = false, state = BeaconState(BeaconPhase.STOPPED)))
            }
            engineToStop?.stop()
            stopForegroundSession(stop.session)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) { stopBeacon(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(startId); return START_NOT_STICKY }
        val token = intent.getLongExtra(EXTRA_GENERATION, -1)
        if (destroyed || !boundClient || !requests.isCurrentStart(token)) {
            // Stop a stale start command before promotion; never reset a newer request's flags.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Promote immediately; preparation and controller callbacks run on a serial worker.
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } catch (_: Exception) {
            requests.finishStart(token) { publish(live.copy(preparing = false, state = BeaconState(BeaconPhase.FAILED, BeaconIssue.FOREGROUND))) }
            stopSelf(startId)
            stopForegroundSession(token)
            return START_NOT_STICKY
        }
        worker.post {
            if (destroyed || !requests.isCurrentStart(token) || !boundClient) {
                requests.finishStart(token) { publish(live.copy(preparing = false)) }
                stopForegroundSession(token)
            } else if (!live.state.ownsRadio) begin(token)
        }
        return START_NOT_STICKY
    }

    private fun begin(token: Long) {
        val identity = live.identity ?: return
        if (!RadioSessionGate.tryAcquire(owner)) {
            requests.finishStart(token) { publish(live.copy(preparing = false, state = BeaconState(BeaconPhase.FAILED, BeaconIssue.BUSY))) }
            stopForegroundSession(token)
            return
        }
        claimed = true
        try { sessionStore.markRunning() }
        catch (_: Exception) {
            RadioSessionGate.release(owner); claimed = false
            requests.finishStart(token) { publish(live.copy(preparing = false, state = BeaconState(BeaconPhase.FAILED, BeaconIssue.STORAGE))) }
            stopForegroundSession(token)
            return
        }
        val driver = AndroidBeaconRadio(this, worker) { issue ->
            var current: BeaconEngine? = null
            requests.forSession(token) { current = engine }
            current?.fail(issue)
        }
        val candidate = BeaconEngine(identity, driver, object : EngineScheduler {
            override fun nowMs(): Long = SystemClock.elapsedRealtime()
            override fun schedule(delayMs: Long, task: () -> Unit): Cancellation {
                val runnable = Runnable(task); worker.postDelayed(runnable, delayMs)
                return Cancellation { worker.removeCallbacks(runnable) }
            }
        }, onState = { state -> requests.forSession(token) { publish(live.copy(state = state, preparing = false)) } }, onCleaned = {
            worker.removeCallbacks(health)
            try { sessionStore.clearAfterConfirmedStop() }
            catch (_: Exception) { requests.forSession(token) { publish(live.copy(state = live.state.copy(phase = BeaconPhase.FAILED, issue = BeaconIssue.STORAGE_CLEANUP))) } }
            if (claimed) { RadioSessionGate.release(owner); claimed = false }
            stopForegroundSession(token)
            if (destroyed) thread.quitSafely()
        })
        if (!requests.commitStart(token, { !destroyed && boundClient }) {
            radio = driver; engine = candidate
            publish(live.copy(preparing = false, state = BeaconState(BeaconPhase.STARTING)))
        }) {
            var state = BeaconState(BeaconPhase.STOPPED)
            // No radio API has been called, so this prepared session has no active controller set.
            try { sessionStore.clearAfterConfirmedStop() }
            catch (_: Exception) { state = BeaconState(BeaconPhase.FAILED, BeaconIssue.STORAGE_CLEANUP) }
            RadioSessionGate.release(owner); claimed = false
            requests.forSession(token) { publish(live.copy(preparing = false, state = state)) }
            stopForegroundSession(token)
            return
        }
        candidate.start()
        if (live.state.ownsRadio) worker.postDelayed(health, 2_000)
    }

    private fun publish(value: BeaconSnapshot) {
        live = value
        main.post {
            if (!destroyed) listeners.forEach { it(live) }
            if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val localized = Appearance.wrapContext(this)
        val stop = PendingIntent.getService(this, 30, Intent(this, BeaconService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 31, Intent(this, BeaconActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(localized.getString(R.string.beacon_notification_title))
            .setContentText(BeaconText.message(localized, live.state))
            .setStyle(Notification.BigTextStyle().bigText(BeaconText.message(localized, live.state)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.beacon_stop), stop).build()).build()
    }

    private fun refreshChannel() {
        val localized = Appearance.wrapContext(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localized.getString(R.string.beacon_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = localized.getString(R.string.beacon_channel_description) })
    }

    private fun stopForegroundSession(session: Long) {
        main.post {
            if (!requests.canCleanForeground(session) { !live.preparing && !live.state.ownsRadio }) return@post
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
            stopSelf(latestStartId)
        }
    }

    private fun loadIdentity(): BeaconIdentity {
        val file = AtomicFile(File(noBackupFilesDir, "ovt-beacon-identity-v1"))
        try {
            // openRead also restores an AtomicFile backup after an interrupted write.
            return file.openRead().use { input ->
                require(input.channel.size() <= 32)
                BeaconIdentity(input.readBytes().toString(Charsets.US_ASCII))
            }
        } catch (_: FileNotFoundException) { /* First installation: generate a new synthetic ID. */ }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val identity = BeaconIdentity(bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
        val stream = file.startWrite()
        try { stream.write(identity.hex.toByteArray(Charsets.US_ASCII)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        return identity
    }

    override fun onDestroy() {
        destroyed = true; requests.cancel(); listeners.clear()
        getSharedPreferences(Appearance.PREFERENCES, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesListener)
        unregisterReceiver(adapterReceiver)
        worker.post {
            worker.removeCallbacks(health)
            if (live.state.ownsRadio) {
                engine?.stop()
                // Main-looper radio callbacks retain the late-start safety stop after this thread exits.
                worker.postDelayed({ thread.quitSafely() }, 6_000)
            } else {
                if (claimed && radio?.cleanupConfirmed != false) RadioSessionGate.release(owner)
                thread.quitSafely()
            }
        }
        super.onDestroy()
    }
}

object BeaconText {
    fun message(context: Context, state: BeaconState): String = context.getString(when (state.phase) {
        BeaconPhase.IDLE -> R.string.beacon_ready
        BeaconPhase.STARTING -> R.string.beacon_starting
        BeaconPhase.ACTIVE -> R.string.beacon_active
        BeaconPhase.STOPPING -> R.string.beacon_stopping
        BeaconPhase.STOPPED -> R.string.beacon_stopped
        BeaconPhase.STOP_UNCONFIRMED -> R.string.beacon_stop_unconfirmed
        BeaconPhase.FAILED -> when (state.issue) {
            BeaconIssue.PERMISSION -> R.string.beacon_error_permission
            BeaconIssue.BLUETOOTH_OFF -> R.string.beacon_error_bluetooth
            BeaconIssue.UNSUPPORTED -> R.string.beacon_error_unsupported
            BeaconIssue.BUSY -> R.string.beacon_error_busy
            BeaconIssue.START_TIMEOUT -> R.string.beacon_error_timeout
            BeaconIssue.STORAGE -> R.string.beacon_error_storage
            BeaconIssue.STORAGE_CLEANUP -> R.string.beacon_error_storage_cleanup
            BeaconIssue.FOREGROUND -> R.string.beacon_error_foreground
            BeaconIssue.INTERRUPTED -> R.string.beacon_error_interrupted
            else -> R.string.beacon_error_controller
        }
    })
}
