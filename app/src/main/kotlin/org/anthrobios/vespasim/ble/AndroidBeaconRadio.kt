package org.anthrobios.vespasim.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.anthrobios.vespasim.core.beacon.*

/** Static, non-connectable advertising; no adapter rename, scan, location request or GATT. */
@SuppressLint("MissingPermission")
class AndroidBeaconRadio(
    private val context: Context,
    private val worker: Handler,
    private val onUnexpectedStop: (BeaconIssue) -> Unit,
) : BeaconRadio {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var startCompletion: ((BeaconResult) -> Unit)? = null
    private var stopCompletion: ((BeaconResult) -> Unit)? = null
    private var registered = false
    @Volatile private var closing = false
    @Volatile var cleanupConfirmed = true; private set

    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            // This path also runs after service teardown. Do not let a late start escape Stop.
            if (closing && status == ADVERTISE_SUCCESS) {
                try { advertiser?.stopAdvertisingSet(this) } catch (_: Exception) { /* worker reports unresolved cleanup */ }
            }
            worker.post {
                if (status != ADVERTISE_SUCCESS || set == null) {
                    registered = false
                    cleanupConfirmed = true
                    val done = startCompletion; startCompletion = null
                    done?.invoke(BeaconResult(false, BeaconIssue.CONTROLLER, status))
                    if (closing) finishStop()
                } else if (closing) requestStop()
                else {
                    val done = startCompletion; startCompletion = null
                    done?.invoke(BeaconResult(true, txPowerDbm = txPower))
                }
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            cleanupConfirmed = true
            worker.post {
                registered = false
                startCompletion = null
                if (closing) finishStop() else onUnexpectedStop(BeaconIssue.INTERRUPTED)
            }
        }

        override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
            worker.post {
                if (!closing && (!enable || status != ADVERTISE_SUCCESS)) onUnexpectedStop(BeaconIssue.INTERRUPTED)
            }
        }
    }

    override fun start(identity: BeaconIdentity, complete: (BeaconResult) -> Unit) {
        if (registered || closing) { complete(BeaconResult(false, BeaconIssue.BUSY)); return }
        val problem = availability()
        if (problem != BeaconIssue.NONE) { complete(BeaconResult(false, problem)); return }
        try {
            advertiser = adapter?.bluetoothLeAdvertiser
            if (advertiser == null) { complete(BeaconResult(false, BeaconIssue.UNSUPPORTED)); return }
            startCompletion = complete
            registered = true
            cleanupConfirmed = false
            // This overload has no duration or event limit: one set continues until Stop.
            advertiser!!.startAdvertisingSet(parameters(), advertisement(identity), scanResponse(), null, null,
                callback, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) { rejectStart(BeaconIssue.PERMISSION) }
        catch (_: IllegalArgumentException) { rejectStart(BeaconIssue.UNSUPPORTED) }
        catch (_: Exception) { rejectStart(BeaconIssue.CONTROLLER) }
    }

    override fun stop(complete: (BeaconResult) -> Unit) {
        closing = true
        stopCompletion = complete
        if (!registered || cleanupConfirmed) finishStop() else requestStop()
    }

    /** STATE_OFF is independent confirmation that this adapter is no longer advertising. */
    fun adapterTurnedOff() {
        cleanupConfirmed = true
        registered = false
        startCompletion = null
        if (closing) finishStop() else onUnexpectedStop(BeaconIssue.BLUETOOTH_OFF)
    }

    private fun requestStop() {
        try { advertiser?.stopAdvertisingSet(callback) }
        catch (_: SecurityException) { failStop(BeaconIssue.PERMISSION) }
        catch (_: Exception) { failStop(BeaconIssue.CONTROLLER) }
    }

    private fun rejectStart(issue: BeaconIssue) {
        registered = false
        cleanupConfirmed = true
        val done = startCompletion; startCompletion = null
        done?.invoke(BeaconResult(false, issue))
        if (closing) finishStop()
    }

    private fun finishStop() {
        cleanupConfirmed = true
        registered = false
        val complete = stopCompletion; stopCompletion = null
        complete?.invoke(BeaconResult(true))
    }

    private fun failStop(issue: BeaconIssue) {
        // Keep the callback: a later real stop confirmation must still release ownership.
        stopCompletion?.invoke(BeaconResult(false, issue))
    }

    fun availability(): BeaconIssue = try {
        when {
            !hasPermissions(context) -> BeaconIssue.PERMISSION
            adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> BeaconIssue.UNSUPPORTED
            !adapter.isEnabled -> BeaconIssue.BLUETOOTH_OFF
            !adapter.isMultipleAdvertisementSupported -> BeaconIssue.UNSUPPORTED
            else -> BeaconIssue.NONE
        }
    } catch (_: SecurityException) { BeaconIssue.PERMISSION }
      catch (_: Exception) { BeaconIssue.CONTROLLER }

    fun isAdapterOff(): Boolean = try { adapter?.state == BluetoothAdapter.STATE_OFF } catch (_: Exception) { false }

    companion object {
        fun hasPermissions(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

        fun advertisement(identity: BeaconIdentity): AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid.fromString(BeaconIdentity.SERVICE_UUID), identity.serviceData()).build()

        // Keep the original primary service-data packet intact. Active scanners can separately
        // match the UUID list in this 18-byte response without exceeding either legacy budget.
        fun scanResponse(): AdvertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid.fromString(BeaconIdentity.SERVICE_UUID)).build()

        fun parameters(): AdvertisingSetParameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true).setConnectable(false).setScannable(true)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M).setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .setInterval(BeaconIdentity.INTERVAL_MS * 8 / 5)
            .setTxPowerLevel(BeaconIdentity.REQUESTED_TX_POWER_DBM).build()
    }
}
