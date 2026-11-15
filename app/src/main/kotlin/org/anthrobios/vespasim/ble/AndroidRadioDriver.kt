package org.anthrobios.vespasim.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.anthrobios.vespasim.core.*
import org.anthrobios.vespasim.core.capture.CaptureReplay
import org.anthrobios.vespasim.core.engine.RadioDriver
import org.anthrobios.vespasim.core.engine.RadioResult
import java.util.UUID

/** One Android advertising set; logical tags share this physical broadcaster. */
@SuppressLint("MissingPermission") // Checked at entry and every operation; revocation is caught.
class AndroidRadioDriver(
    private val context: Context,
    private val worker: Handler,
    private val onCleanupConfirmed: () -> Unit = {},
    private val onFatal: (String) -> Unit = {},
) : RadioDriver {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingSet: AdvertisingSet? = null
    private var callback: AdvertisingSetCallback? = null
    private var enabled = false
    private var actualPower: Int? = null
    private var pending: ((RadioResult) -> Unit)? = null
    private var expected = ""
    private var closeComplete: ((RadioResult) -> Unit)? = null
    @Volatile private var closing = false
    @Volatile private var disposed = false
    /** False after a start request until Android confirms rejection or a stopped set. */
    @Volatile var isCleanupConfirmed = true
        private set

    override fun apply(frame: PlannedFrame, settings: RadioSettings, complete: (RadioResult) -> Unit) {
        check(!closing && !disposed) { "Radio is gesloten" }
        check(pending == null) { "Een controlleraanvraag is nog bezig" }
        val payload = requireNotNull(frame.payloadHex)
        ensurePermission()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: error("Geen Bluetoothadapter beschikbaar")
        check(adapter.isEnabled) { "Bluetooth is uitgeschakeld" }
        AdvertisingLayout.requireMatchingName(settings.localName, if (settings.localName != null) adapter.name else null)
        check(frame.localName == settings.localName) { "Advertentienaam wijkt af van de profielsnapshot" }
        val captured = frame.capturedAdvertisement?.data
        if (captured != null) {
            require(settings.mode == RadioMode.LEGACY_1M) { "Opnamereplay ondersteunt alleen legacy 1M" }
            require(!frame.includeManufacturerData && payload.isEmpty() && frame.localName == captured.localName) { "Opnameframe wijkt af van de oorspronkelijke scanbytes" }
        } else check(frame.includeManufacturerData || (payload.isEmpty() && frame.localName != null)) { "Naamadvertentie vereist een naam en geen manufacturer-payload" }
        val bytes = Protocol.unhex(payload)
        val dataSize = captured?.let(AdvertisingLayout::byteSize) ?: AdvertisingLayout.byteSize(bytes.size, frame.includeManufacturerData, frame.localName)
        require(dataSize <= AdvertisingLayout.maximumBytes(settings.mode)) { "Naam en payload overschrijden het advertentiebudget" }
        if (settings.mode == RadioMode.LEGACY_1M) {
            require(bytes.size <= 24) { "Legacy-payload overschrijdt conservatieve limiet van 24 bytes" }
        } else {
            check(adapter.isLeExtendedAdvertisingSupported) { "Extended advertising wordt niet ondersteund" }
            require(dataSize <= adapter.leMaximumAdvertisingDataLength) { "Naam en payload overschrijden controllerlimiet" }
            if (settings.mode == RadioMode.EXTENDED_CODED) check(adapter.isLeCodedPhySupported) { "LE Coded PHY wordt niet ondersteund" }
        }
        val data = buildAdvertisingData(frame)
        pending = complete
        try {
            if (advertisingSet == null) start(adapter, settings, data)
            else {
                expected = "data"
                advertisingSet!!.setAdvertisingData(data)
            }
        } catch (e: Exception) {
            finishOperation(RadioResult(false, describe(e)))
        }
    }

    private fun start(adapter: BluetoothAdapter, settings: RadioSettings, data: AdvertiseData) {
        check(callback == null) { "Startaanvraag loopt nog" }
        val engine = adapter.bluetoothLeAdvertiser ?: error("BLE-advertising niet beschikbaar op dit toestel")
        advertiser = engine
        val legacy = settings.mode == RadioMode.LEGACY_1M
        val phy = if (settings.mode == RadioMode.EXTENDED_CODED) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(legacy).setConnectable(false).setScannable(false)
            .setPrimaryPhy(phy).setSecondaryPhy(phy)
            .setInterval((settings.intervalMs * 8 + 4) / 5)
            .setTxPowerLevel(settings.txPowerDbm).setIncludeTxPower(false).build()
        expected = "start"
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS || set == null) confirmCleanup()
                // Delivered on main. Even after worker shutdown, a late successful start is stopped.
                if (closing || disposed) {
                    try { engine.stopAdvertisingSet(this) } catch (_: Exception) { /* Already reported by cleanup path. */ }
                    worker.post {
                        if (status != ADVERTISE_SUCCESS || set == null) {
                            finishClose(RadioResult(true, "Start niet actief"))
                        }
                    }
                    return
                }
                worker.post {
                    if (closing || disposed) {
                        try { engine.stopAdvertisingSet(this) } catch (e: Exception) { finishClose(RadioResult(false, describe(e))) }
                        return@post
                    }
                    if (expected != "start") return@post
                    if (status == ADVERTISE_SUCCESS && set != null) {
                        advertisingSet = set
                        enabled = true
                        actualPower = txPower
                        finishOperation(RadioResult(true, "Advertising-set gestart", txPower))
                    } else {
                        callback = null
                        finishOperation(RadioResult(false, statusText(status)))
                    }
                }
            }

            override fun onAdvertisingDataSet(set: AdvertisingSet, status: Int) {
                worker.post {
                    if (closing || disposed || expected != "data") return@post
                    if (status != ADVERTISE_SUCCESS) {
                        finishOperation(RadioResult(false, statusText(status)))
                    } else if (!enabled) {
                        expected = "enable"
                        try { ensurePermission(); set.enableAdvertising(true, 0, 0) }
                        catch (e: Exception) { finishOperation(RadioResult(false, describe(e))) }
                    } else finishOperation(RadioResult(true, "Payload ingesteld", actualPower))
                }
            }

            override fun onAdvertisingEnabled(set: AdvertisingSet, enable: Boolean, status: Int) {
                worker.post {
                    if (closing || disposed) return@post
                    if ((expected != "enable" || !enable) && (expected != "disable" || enable)) return@post
                    if (status == ADVERTISE_SUCCESS) {
                        enabled = enable
                        finishOperation(RadioResult(true, if (enable) "Ingeschakeld" else "Uitgeschakeld", actualPower))
                    } else finishOperation(RadioResult(false, statusText(status)))
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet) {
                confirmCleanup()
                worker.post {
                    advertisingSet = null
                    enabled = false
                    callback = null
                    if (closing) finishClose(RadioResult(true, "Advertising-set gestopt"))
                    else if (pending != null) finishOperation(RadioResult(false, "Advertising-set onverwacht gestopt"))
                    else onFatal("Advertising-set onverwacht gestopt")
                }
            }
        }
        callback = cb
        isCleanupConfirmed = false
        engine.startAdvertisingSet(parameters, data, null, null, null, cb, Handler(Looper.getMainLooper()))
    }

    override fun silence(complete: (RadioResult) -> Unit) {
        check(!closing && !disposed)
        check(pending == null)
        ensurePermission()
        if (!enabled || advertisingSet == null) { complete(RadioResult(true, "Al uitgeschakeld")); return }
        pending = complete
        expected = "disable"
        try { advertisingSet!!.enableAdvertising(false, 0, 0) }
        catch (e: Exception) { finishOperation(RadioResult(false, describe(e))) }
    }

    override fun close(complete: (RadioResult) -> Unit) {
        if (disposed) { complete(RadioResult(true, "Al gesloten")); return }
        if (closing) { complete(RadioResult(false, "Stopaanvraag loopt al")); return }
        closing = true
        pending = null
        expected = "close"
        closeComplete = complete
        val cb = callback
        if (cb == null) { finishClose(RadioResult(true, "Geen actieve advertising-set")); return }
        try { advertiser?.stopAdvertisingSet(cb) ?: finishClose(RadioResult(false, "Advertiser ontbreekt tijdens stop")) }
        catch (e: Exception) { finishClose(RadioResult(false, describe(e))) }
    }

    private fun finishOperation(result: RadioResult) {
        val done = pending
        pending = null
        expected = ""
        done?.invoke(result)
    }

    private fun confirmCleanup() {
        isCleanupConfirmed = true
        try { onCleanupConfirmed() } catch (_: Exception) { /* A lifecycle listener cannot lose a platform stop acknowledgement. */ }
    }

    private fun finishClose(result: RadioResult) {
        val done = closeComplete ?: return
        closeComplete = null
        disposed = true
        pending = null
        advertisingSet = null
        enabled = false
        done(result)
    }

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) { "Toestemming om Bluetooth uit te zenden ontbreekt" }
            check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "Bluetoothtoestemming ontbreekt" }
        }
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName

    private fun statusText(status: Int): String = when (status) {
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Payload te groot voor controller/mode"
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Geen advertising-set beschikbaar; stop andere BLE-zenders"
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising-set al gestart"
        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Interne Android/controllerfout"
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertentiefunctie niet ondersteund"
        else -> "Controllerstatus $status"
    }
}

/** Public-platform translation, separated so instrumentation can inspect the actual AD elements. */
internal fun buildAdvertisingData(frame: PlannedFrame): AdvertiseData {
    val payload = requireNotNull(frame.payloadHex) { "Een stilteslot heeft geen advertentiedata" }
    frame.capturedAdvertisement?.let { source ->
        CaptureReplay.validateAdvertisement(source)
        val captured = source.data // Reparse immutable original bytes; unsupported AD never silently vanishes.
        require(!frame.includeManufacturerData && payload.isEmpty() && frame.localName == captured.localName) { "Opnameframe wijkt af van de oorspronkelijke scanbytes" }
        require(AdvertisingLayout.byteSize(captured) <= AdvertisingLayout.maximumBytes(RadioMode.LEGACY_1M)) { "Opname-inhoud overschrijdt het legacy-advertentiebudget" }
        val builder = AdvertiseData.Builder().setIncludeDeviceName(captured.localName != null).setIncludeTxPowerLevel(false)
        for (manufacturer in captured.manufacturerData) builder.addManufacturerData(manufacturer.companyId, Protocol.unhex(manufacturer.dataHex))
        for (uuid in captured.serviceUuids) builder.addServiceUuid(ParcelUuid(UUID.fromString(uuid)))
        for (service in captured.serviceData) builder.addServiceData(ParcelUuid(UUID.fromString(service.uuid)), Protocol.unhex(service.dataHex))
        return builder.build()
    }
    require(frame.includeManufacturerData || (payload.isEmpty() && frame.localName != null)) {
        "Naamadvertentie vereist een naam en geen manufacturer-payload"
    }
    val builder = AdvertiseData.Builder().setIncludeDeviceName(frame.localName != null).setIncludeTxPowerLevel(false)
    if (frame.includeManufacturerData) builder.addManufacturerData(frame.companyId, Protocol.unhex(payload))
    return builder.build()
}
