package org.anthrobios.vespasim.ui

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.*
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.ble.AndroidBeaconRadio
import org.anthrobios.vespasim.core.beacon.*
import org.anthrobios.vespasim.service.BeaconService
import org.anthrobios.vespasim.service.BeaconSnapshot
import org.anthrobios.vespasim.service.BeaconText
import java.util.Locale

/** A persistent test point, separate from finite simulation profiles and captured identities. */
class BeaconActivity : Activity() {
    private var service: BeaconService? = null
    private var bound = false
    private var visible = false
    private var pendingPermissionStart = false
    private var live = BeaconSnapshot()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var controllerStatus: TextView
    private lateinit var identity: TextView
    private lateinit var details: TextView
    private lateinit var elapsed: TextView
    private lateinit var notice: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var copy: Button
    private lateinit var findBeacon: Button
    private val listener: (BeaconSnapshot) -> Unit = { live = it; render() }
    private val tick = object : Runnable {
        override fun run() { if (visible) { render(); main.postDelayed(this, 1_000) } }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as BeaconService.LocalBinder).service
            service!!.addListener(listener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            live = live.copy(state = BeaconState(BeaconPhase.FAILED, BeaconIssue.INTERRUPTED), preparing = false)
            render()
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(Appearance.wrapContext(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.beacon_title)
        val padding = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION") val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
                @Suppress("DEPRECATION") val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
                view.setPadding(padding, padding + top, padding, padding + bottom)
                insets
            }
        }
        root.addView(button(R.string.beacon_back) { finish() })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(text(R.string.beacon_project, 12f))
        body.addView(text(R.string.beacon_title, 30f))
        body.addView(text(R.string.beacon_intro, 16f).apply { Appearance.reading(this) })
        val session = card(body)
        session.addView(text(R.string.beacon_status_label, 12f))
        status = text(R.string.beacon_ready, 21f).apply { tag = "beacon_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        session.addView(status)
        controllerStatus = text(R.string.beacon_controller_status, 14f).apply { tag = "beacon_controller_status"; visibility = View.GONE }
        session.addView(controllerStatus)
        identity = text(R.string.beacon_identity_loading, 22f).apply { tag = "beacon_identity"; setTextIsSelectable(true) }
        session.addView(identity)
        elapsed = text(R.string.beacon_no_session, 14f)
        session.addView(elapsed)
        start = button(R.string.beacon_start) { requestStart() }.apply {
            tag = "beacon_start"
            backgroundTintList = ColorStateList.valueOf(Appearance.colors(this@BeaconActivity).accent)
        }
        stop = button(R.string.beacon_stop) { pendingPermissionStart = false; service?.stopBeacon() }.apply { tag = "beacon_stop" }
        session.addView(start)
        session.addView(stop)
        notice = text(R.string.beacon_notification_denied, 14f)
        session.addView(notice)
        copy = button(R.string.beacon_copy_identity) {
            live.identity?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.beacon_identity_label), it.label))
                Toast.makeText(this, R.string.beacon_copied, Toast.LENGTH_SHORT).show()
            }
        }
        session.addView(copy)
        val guide = card(body)
        guide.addView(text(R.string.beacon_receiver_title, 21f))
        findBeacon = button(R.string.beacon_find) {
            startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_TEST_BEACON_FILTER, true))
        }.apply { tag = "beacon_find" }
        guide.addView(findBeacon)
        guide.addView(text(R.string.beacon_find_hint, 15f).apply { Appearance.reading(this) })
        guide.addView(text(R.string.beacon_receiver_steps, 15f).apply { Appearance.reading(this) })
        details = text(R.string.beacon_identity_loading, 13f).apply { setTextIsSelectable(true) }
        guide.addView(details)
        val limits = card(body)
        limits.addView(text(R.string.beacon_limits_title, 21f))
        limits.addView(text(R.string.beacon_limits, 15f).apply { Appearance.reading(this) })
        setContentView(root)
        Appearance.styleTree(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        bound = bindService(Intent(this, BeaconService::class.java), connection, Context.BIND_AUTO_CREATE)
        main.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (Appearance.needsRecreation(this)) recreate()
    }

    override fun onStop() {
        visible = false; pendingPermissionStart = false
        main.removeCallbacks(tick)
        service?.removeListener(listener)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }

    private fun requestStart() {
        if (!visible || service == null) return
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.BLUETOOTH_ADVERTISE
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notifications_asked", false)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply()
        }
        if (missing.isNotEmpty()) {
            pendingPermissionStart = true
            requestPermissions(missing.toTypedArray(), 401)
        } else service?.startBeacon()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 401) return
        val startRequested = pendingPermissionStart
        pendingPermissionStart = false
        if (startRequested && visible && AndroidBeaconRadio.hasPermissions(this)) service?.startBeacon()
        else if (!AndroidBeaconRadio.hasPermissions(this)) Toast.makeText(this, R.string.beacon_error_permission, Toast.LENGTH_LONG).show()
        render()
    }

    private fun render() {
        if (!::status.isInitialized) return
        status.text = if (live.preparing) getString(R.string.beacon_starting) else BeaconText.message(this, live.state)
        val code = live.state.platformCode?.takeIf { it in 0..255 }
        controllerStatus.visibility = if (code == null) View.GONE else View.VISIBLE
        if (code != null) controllerStatus.text = getString(R.string.beacon_controller_status, code)
        identity.text = live.identity?.label ?: getString(R.string.beacon_identity_loading)
        val active = live.state.phase == BeaconPhase.ACTIVE
        elapsed.text = if (active && live.state.acceptedAtMs != null) {
            val seconds = ((SystemClock.elapsedRealtime() - live.state.acceptedAtMs!!) / 1_000).coerceAtLeast(0)
            getString(R.string.beacon_elapsed, String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60))
        } else getString(R.string.beacon_no_session)
        start.isEnabled = service != null && live.identity != null && !live.state.ownsRadio && !live.preparing
        stop.isEnabled = service != null && (live.state.ownsRadio || live.preparing)
        copy.isEnabled = live.identity != null
        findBeacon.isEnabled = !live.state.ownsRadio && !live.preparing
        details.text = getString(R.string.beacon_details, BeaconIdentity.SERVICE_UUID,
            live.identity?.serviceData()?.joinToString("") { "%02x".format(it.toInt() and 255) } ?: "—")
        notice.visibility = if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) View.VISIBLE else View.GONE
    }

    private fun text(resource: Int, size: Float) = TextView(this).apply {
        setText(resource); textSize = size; setPadding(0, dp(5), 0, dp(8))
        setTextColor(Appearance.colors(context).text)
    }
    private fun button(resource: Int, action: () -> Unit) = Button(this).apply {
        setText(resource); isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }
    private fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = Appearance.panelBackground(this@BeaconActivity)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun dp(value: Int) = Appearance.dp(this, value)
}
