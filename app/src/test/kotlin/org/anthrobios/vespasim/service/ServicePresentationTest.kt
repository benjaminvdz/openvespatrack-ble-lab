package org.anthrobios.vespasim.service

import org.anthrobios.vespasim.R
import org.junit.Test
import kotlin.test.assertEquals

class ServicePresentationTest {
    @Test fun namesAndTrackIdsAreDataEvenWhenTheyMatchAStatusMessage() {
        val raw = "Tijdslot 42 · Gereed"
        val displayed = ServicePresentation.render(raw) { resource, values ->
            assertEquals(R.string.service_time_slot, resource)
            assertEquals(listOf("42", "Gereed"), values)
            "Time slot ${values[0]} · ${values[1]}"
        }
        assertEquals("Time slot 42 · Gereed", displayed)
        assertEquals("Tijdslot 42 · Gereed", raw)
    }

    @Test fun nameRecoveryKeepsBothOriginalAndTemporaryNamesVerbatim() {
        val temporary = "Gereed. 0xFFAB"
        val original = "Bluetooth is uitgeschakeld"
        val raw = "Bluetoothnaamherstel staat open: tijdelijke naam $temporary. Oorspronkelijke naam: $original. " +
            "Schakel Bluetooth in en kies Bluetoothnaam herstellen. Er wordt geen uitzending gestart."
        ServicePresentation.render(raw) { resource, values ->
            assertEquals(R.string.service_name_recovery_pending, resource)
            assertEquals(listOf(temporary, original), values)
            "recovery"
        }
    }

    @Test fun nestedStopFailuresRetainTheirReasonAndUnconfirmedRadioState() {
        val raw = "Start mislukt: Payload instellen mislukt: Bluetooth is uitgeschakeld; radio-stop niet bevestigd"
        val displayed = ServicePresentation.render(raw) { resource, values ->
            when (resource) {
                R.string.service_bluetooth_off -> "Bluetooth is off"
                R.string.service_payload_failed -> "Setting payload failed: ${values.single()}"
                R.string.service_start_failed -> "Start failed: ${values.single()}"
                R.string.service_radio_stop_unconfirmed -> "${values.single()}; radio stop not confirmed"
                else -> error("Unexpected resource $resource")
            }
        }
        assertEquals("Start failed: Setting payload failed: Bluetooth is off; radio stop not confirmed", displayed)
    }

    @Test fun capabilitiesPreserveDeviceIdentityAndKeepBlankLines() {
        val raw = "Android 16 · API 36\nToestel: Test Gereed\nExtended advertising: false\n\nLE Coded PHY: true"
        val displayed = ServicePresentation.render(raw) { resource, values ->
            when (resource) {
                R.string.service_cap_device -> "Device: ${values.single()}"
                R.string.service_cap_extended -> "Extended advertising: ${values.single()}"
                R.string.service_cap_coded -> "LE Coded PHY: ${values.single()}"
                R.string.service_true -> "yes"
                R.string.service_false -> "no"
                else -> error("Unexpected resource $resource")
            }
        }
        assertEquals("Android 16 · API 36\nDevice: Test Gereed\nExtended advertising: no\n\nLE Coded PHY: yes", displayed)
    }

    @Test fun startupWarningsPreserveProfileIdsAndPlatformDiagnostics() {
        val raw = "Profiel Gereed-0xAB12 kon niet worden geladen: EIO /storage/a.json"
        val displayed = ServicePresentation.render(raw) { resource, values ->
            when (resource) {
                R.string.service_original_diagnostic -> {
                    assertEquals(listOf("EIO /storage/a.json"), values)
                    "Original diagnostic: ${values.single()}"
                }
                R.string.service_profile_load_failed -> {
                    assertEquals(listOf("Gereed-0xAB12", "Original diagnostic: EIO /storage/a.json"), values)
                    "Profile ${values[0]} could not be loaded: ${values[1]}"
                }
                else -> error("Unexpected resource $resource")
            }
        }
        assertEquals("Profile Gereed-0xAB12 could not be loaded: Original diagnostic: EIO /storage/a.json", displayed)
    }

    @Test fun unknownDiagnosticsAreNeverRewrittenOrDropped() {
        val raw = "Vendor callback 0xFFAB: EIO\nraw: 0A 00 FF\n"
        assertEquals(raw, ServicePresentation.render(raw) { _, _ -> error("Unknown diagnostic must stay verbatim") })
    }
}
