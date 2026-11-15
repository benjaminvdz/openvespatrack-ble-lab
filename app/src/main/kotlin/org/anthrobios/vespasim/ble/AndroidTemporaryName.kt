package org.anthrobios.vespasim.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.anthrobios.vespasim.core.engine.TemporaryNameAdapter
import org.anthrobios.vespasim.core.engine.TemporaryNameJournal
import org.anthrobios.vespasim.core.engine.TemporaryNameStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Called only on the service worker. A name readback is not proof of an RF packet. */
internal class AndroidTemporaryNameAdapter(private val context: Context) : TemporaryNameAdapter {
    @SuppressLint("MissingPermission")
    private fun adapter(): BluetoothAdapter {
        if (Build.VERSION.SDK_INT >= 31) check(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            "Bluetoothtoestemming ontbreekt; geef toestemming en probeer Bluetoothnaam herstellen opnieuw."
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("Bluetoothadapter ontbreekt.")
        check(adapter.isEnabled) { "Bluetooth staat uit; schakel het in en probeer Bluetoothnaam herstellen opnieuw." }
        return adapter
    }

    @SuppressLint("MissingPermission")
    override fun currentName(): String = adapter().name ?: error("De huidige Bluetoothnaam kan niet worden gelezen.")

    @SuppressLint("MissingPermission")
    override fun requestName(name: String): Boolean = adapter().setName(name)
}

/** Single service worker owns this journal. AtomicFile retains the last completed write after a crash. */
internal class AndroidTemporaryNameStore(directory: File) : TemporaryNameStore {
    private val base = File(directory, "bluetooth-name-recovery.json")
    private val atomic = AtomicFile(base)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun read(): TemporaryNameJournal? {
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val buffer = ByteArray(4097)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                if (count == 0) continue
                used += count
            }
            buffer.copyOf(used)
        }
        require(bytes.size <= 4096) { "Het Bluetoothnaam-herstelbestand is te groot; het blijft bewaard." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromString<TemporaryNameJournal>(text).also(::validate)
    }

    override fun write(journal: TemporaryNameJournal) {
        validate(journal)
        val bytes = json.encodeToString(journal).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            // AtomicFile can log some failures instead of throwing. A failed sync or
            // final replacement must stop preparation before the adapter is renamed.
            output.fd.sync()
            atomic.finishWrite(output)
            check(read() == journal) { "Bluetoothnaam-herstelbestand kon niet duurzaam worden bevestigd." }
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    override fun clear() {
        atomic.delete()
        check(!base.exists() && !File(base.path + ".bak").exists()) { "Bluetoothnaam-herstelbestand kon niet worden verwijderd." }
    }

    private fun validate(journal: TemporaryNameJournal) {
        require(journal.version == 1) { "Onbekende versie van het Bluetoothnaam-herstelbestand." }
        require(journal.ownerRunId.length in 1..128) { "Ongeldige eigenaar in Bluetoothnaam-herstelbestand." }
        require(listOf(journal.originalName, journal.temporaryName).all {
            val encoded = it.toByteArray(Charsets.UTF_8)
            it.isNotEmpty() && encoded.size <= 248 && encoded.toString(Charsets.UTF_8) == it
        }) {
            "Ongeldige naam in Bluetoothnaam-herstelbestand; oorspronkelijke gegevens blijven bewaard."
        }
    }
}
