package org.anthrobios.vespasim.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object BleCapabilities {
    @SuppressLint("MissingPermission")
    fun inspect(context: Context): String = buildString {
        appendLine("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        appendLine("Toestel: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("BLE-functie: ${if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "aanwezig" else "ontbreekt"}")
        if (Build.VERSION.SDK_INT >= 31 &&
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            appendLine("Bluetoothtoestemming nog niet verleend. Hardwareloze simulaties blijven beschikbaar.")
        } else try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) appendLine("Geen Bluetoothadapter beschikbaar.") else {
                appendLine("Bluetooth: ${if (adapter.isEnabled) "ingeschakeld" else "uitgeschakeld"}")
                appendLine("Huidige Bluetoothnaam: ${adapter.name ?: "onbekend"}")
                appendLine("Advertiser: ${if (adapter.bluetoothLeAdvertiser != null) "beschikbaar" else "niet beschikbaar (of Bluetooth uit)"}")
                appendLine("Meerdere advertenties ondersteund: ${adapter.isMultipleAdvertisementSupported}")
                appendLine("Extended advertising: ${adapter.isLeExtendedAdvertisingSupported}")
                appendLine("LE Coded PHY: ${adapter.isLeCodedPhySupported}")
                appendLine("Maximum advertising-data: ${adapter.leMaximumAdvertisingDataLength} bytes")
            }
        } catch (e: Exception) { appendLine("Controle niet voltooid: ${e.message ?: e.javaClass.simpleName}") }
        appendLine()
        appendLine("De simulator gebruikt één advertising-set. Virtuele tags delen dezelfde fysieke zender; afzonderlijke MAC-adressen worden niet nagebootst.")
        appendLine("Bij BLE-start met Local Name stelt de app de algemene Bluetoothnaam tijdelijk in en herstelt die na stoppen. Na een onderbreking kan Bluetoothnaam herstellen nodig blijven. Dry-run verandert geen naam.")
        append("Controlleracceptatie bewijst geen uitzending of ontvangst. De lokale RSSI wordt door de Linuxontvanger gemeten.")
    }
}
