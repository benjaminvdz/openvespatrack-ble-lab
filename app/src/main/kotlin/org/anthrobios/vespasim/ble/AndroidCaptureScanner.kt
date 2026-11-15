package org.anthrobios.vespasim.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import org.anthrobios.vespasim.core.Protocol
import org.anthrobios.vespasim.core.capture.CaptureObservation

/** Public Android scan callbacks, copied before delivery. ScanRecord combines ADV and scan response. */
@SuppressLint("MissingPermission") // Explicit preflight; every callback/operation catches revocation.
class AndroidCaptureScanner(context: Context) {
    private val context = context.applicationContext
    interface Listener {
        fun onObservation(observation: CaptureObservation)
        fun onLost(address: String) {}
        fun onFailure(message: String)
    }
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    @Volatile private var generation = 0L
    private var settingsDescription: Map<String, String> = emptyMap()

    @Synchronized fun start(address: String?, listener: Listener): Map<String, String> {
        check(callback == null) { "Er loopt al een zoekactie of opname" }
        scanProblem(context)?.let { error(it) }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("Geen Bluetoothadapter")
        val receiver = adapter.bluetoothLeScanner ?: error("BLE-scanner niet beschikbaar")
        val extended = adapter.isLeExtendedAdvertisingSupported
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .setLegacy(!extended)
            .apply { if (extended) setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
            .build()
        // Android suspends unfiltered scans when the screen turns off. A recording always has
        // an actual platform address filter, independently of the UI's Nano/text view filter.
        val filters = address?.let { listOf(ScanFilter.Builder().setDeviceAddress(it).build()) } ?: emptyList()
        val token = ++generation
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (generation != token) return
                try {
                    val resultAddress = result.device.address.uppercase()
                    if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                        listener.onLost(resultAddress)
                        return
                    }
                    if (callbackType != ScanSettings.CALLBACK_TYPE_ALL_MATCHES && callbackType != ScanSettings.CALLBACK_TYPE_FIRST_MATCH) {
                        listener.onFailure("Onbekend scan-callbacktype $callbackType; opname gestopt")
                        return
                    }
                    if (address != null && !resultAddress.equals(address, ignoreCase = true)) return
                    val bytes = result.scanRecord?.bytes?.copyOf()
                    // Cached BluetoothDevice.name is identity metadata only, never the advertised name.
                    val cachedName = runCatching { result.device.name }.getOrNull()
                    listener.onObservation(CaptureObservation(
                        sequence = 1, // Durable selected-session order is assigned by CaptureService.
                        receivedAtMs = System.currentTimeMillis(),
                        timestampNanos = result.timestampNanos,
                        address = resultAddress,
                        rssiRaw = result.rssi,
                        rawHex = bytes?.let(Protocol::hex),
                        deviceName = cachedName,
                        legacy = result.isLegacy,
                        connectable = result.isConnectable,
                        primaryPhy = result.primaryPhy,
                        secondaryPhy = result.secondaryPhy,
                        advertisingSid = result.advertisingSid,
                        txPowerRaw = result.txPower,
                        dataStatus = result.dataStatus,
                        periodicInterval = result.periodicAdvertisingInterval,
                        callbackType = callbackType,
                    ))
                } catch (failure: Exception) {
                    listener.onFailure("Scangegevens konden niet worden gelezen: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
            override fun onScanFailed(errorCode: Int) {
                if (generation == token) listener.onFailure(scanFailure(errorCode))
            }
        }
        callback = cb
        scanner = receiver
        settingsDescription = mapOf(
            "source" to "android_scan_result", "record_semantics" to "advertising_and_scan_response_combined",
            "scan_mode" to "low_latency", "callback_type" to "all_matches", "report_delay_ms" to "0",
            "platform_address_filter" to (address ?: "none_discovery_only"),
            "legacy_only" to (!extended).toString(), "phy" to (if (extended) "all_supported" else "1M"),
        )
        try { receiver.startScan(filters, settings, cb) }
        catch (failure: Exception) {
            stop()
            throw failure
        }
        return settingsDescription
    }

    /** Invalidate callbacks first. A cleanup failure is surfaced, never reported as a clean stop. */
    @Synchronized fun stop(): String? {
        generation++
        val cb = callback
        val previous = scanner
        if (cb == null) return null
        return try {
            previous?.stopScan(cb)
            callback = null
            scanner = null
            null
        }
        catch (failure: Exception) { "Scanner kon niet bevestigd stoppen: ${failure.message ?: failure.javaClass.simpleName}" }
    }

    companion object {
        fun scanProblem(context: Context): String? = try {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "Dit toestel ondersteunt geen BLE"
            else if (Build.VERSION.SDK_INT >= 31 && (
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) "Geef toestemming voor apparaten in de buurt (scannen en verbinden)"
            else if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) "Geef nauwkeurige locatietoestemming voor ongefilterd BLE-beacononderzoek"
            else {
                val location = context.getSystemService(LocationManager::class.java)
                val enabled = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
                    else location?.let { it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } == true
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                when {
                    !enabled -> "Schakel Locatie in voor BLE-beacononderzoek"
                    adapter == null -> "Geen Bluetoothadapter beschikbaar"
                    !adapter.isEnabled -> "Schakel Bluetooth in"
                    else -> null
                }
            }
        } catch (failure: Exception) { "Bluetoothcontrole mislukt: ${failure.message ?: failure.javaClass.simpleName}" }

        private fun scanFailure(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Android meldt dat de scan al gestart is"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Registratie bij de Android-scanner mislukt"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Deze scaninstellingen worden niet ondersteund"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Interne Android-scanfout"
            5 -> "Onvoldoende scanresources; stop andere Bluetooth-apps"
            6 -> "Te veel scanstarts kort na elkaar; wacht even vóór opnieuw zoeken"
            else -> "Android-scanfout $code"
        }
    }
}
