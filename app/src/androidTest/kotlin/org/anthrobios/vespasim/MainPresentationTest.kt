package org.anthrobios.vespasim

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.anthrobios.vespasim.core.PlannedFrame
import org.anthrobios.vespasim.core.Presets
import org.anthrobios.vespasim.core.ProfileCodec
import org.anthrobios.vespasim.ui.MainPresentation
import org.anthrobios.vespasim.ui.UiLogic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MainPresentationTest {
    @Test fun changingDisplayLanguageNeverChangesProfileOrFrameEvidence() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val original = Presets.all().first()
        val before = ProfileCodec.encode(original)
        val custom = original.copy(name = "Gereed · mes données", description = "Données personnelles — exactes")
        val frame = PlannedFrame(1, 0, 500, "literal-track", 64613, "00ff", localName = "Exacte naam")
        for ((language, expected) in listOf("en" to "Direct tag", "nl" to "Directe tag", "fr" to "Balise directe")) {
            val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            assertTrue(MainPresentation.profileName(localized, original).contains(expected))
            assertEquals(custom.name, MainPresentation.profileName(localized, custom))
            assertEquals(custom.description, MainPresentation.profileDescription(localized, custom))
            val display = UiLogic.frameDescription(frame) { label, args -> MainPresentation.format(localized, label, args) }
            assertTrue(display.contains("00ff"))
            assertTrue(display.contains("Exacte naam"))
            assertEquals(before, ProfileCodec.encode(original))
            assertEquals("00ff", frame.payloadHex)
        }
    }
}
