package org.anthrobios.vespasim

import android.app.Application
import org.anthrobios.vespasim.core.Presets
import org.anthrobios.vespasim.core.storage.LabRepository
import org.anthrobios.vespasim.core.capture.CaptureRepository
import java.io.File

/** One repository and one crash-recovery pass per process, never on Activity recreation. */
class VespaApplication : Application() {
    lateinit var repository: LabRepository
        private set
    lateinit var captureRepository: CaptureRepository
        private set
    var startupWarnings: List<String> = emptyList()
        private set
    @Volatile var initializationComplete: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LabRepository(File(filesDir, "lab"))
        captureRepository = CaptureRepository(File(filesDir, "captures"))
        Thread({ initializeArchive() }, "vespa-archive-recovery").start()
    }

    private fun initializeArchive() {
        val warnings = mutableListOf<String>()
        try {
            repository.recoverInterruptedRuns()
        } catch (error: Exception) {
            warnings += "Archiefherstel niet volledig: ${error.message}. Oorspronkelijke bestanden blijven bewaard."
        }
        try {
            captureRepository.recoverInterruptedCaptures()
        } catch (error: Exception) {
            warnings += "Opnameherstel niet volledig: ${error.message}. Oorspronkelijke metingen blijven bewaard."
        }
        for (profile in Presets.all()) {
            try {
                if (repository.profile(profile.id) == null) repository.saveProfile(profile)
            } catch (error: Exception) {
                warnings += "Profiel ${profile.id} kon niet worden geladen: ${error.message}"
            }
        }
        startupWarnings = warnings
        // Publishing readiness also publishes the completed recovery and warnings to UI/worker.
        initializationComplete = true
    }
}
