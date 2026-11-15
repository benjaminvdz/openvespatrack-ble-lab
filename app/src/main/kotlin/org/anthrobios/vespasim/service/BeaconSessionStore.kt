package org.anthrobios.vespasim.service

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Worker-thread-only crash marker. Its presence is never authorization to resume Bluetooth. */
class BeaconSessionStore(private val path: File) {
    companion object {
        const val FILE_NAME = "ovt-beacon-session-v1"
        private const val ACTIVE = "OVT-BEACON-ACTIVE-V1"
    }
    private val file = AtomicFile(path)

    fun wasRunning(): Boolean {
        val content = try {
            file.openRead().use { input ->
                require(input.channel.size() <= 32)
                input.readBytes().toString(Charsets.US_ASCII)
            }
        } catch (_: FileNotFoundException) { return false }
        require(content == ACTIVE)
        return true
    }

    /** Must finish successfully before calling the radio driver. */
    fun markRunning() {
        val stream = file.startWrite()
        try { stream.write(ACTIVE.toByteArray(Charsets.US_ASCII)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        check(wasRunning())
    }

    /** The caller has independently confirmed that this session no longer owns a radio set. */
    fun clearAfterConfirmedStop() {
        file.delete()
        check(!path.exists() && !File(path.path + ".bak").exists() && !File(path.path + ".new").exists())
    }
}
