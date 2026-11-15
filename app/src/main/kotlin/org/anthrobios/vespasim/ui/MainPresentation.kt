package org.anthrobios.vespasim.ui

import android.content.Context
import org.anthrobios.vespasim.R
import org.anthrobios.vespasim.core.Presets
import org.anthrobios.vespasim.core.SimulationProfile
import org.anthrobios.vespasim.service.ServicePresentation

/** Resource-backed presentation. Original profiles, frame bytes and log evidence are never rewritten. */
object MainPresentation {
    fun format(context: Context, label: UiLogic.Label, args: List<Any?>): String {
        val id = when (label) {
            UiLogic.Label.SILENCE -> R.string.main_logic_silence
            UiLogic.Label.PAUSE -> R.string.main_logic_pause
            UiLogic.Label.INACTIVE -> R.string.main_logic_inactive
            UiLogic.Label.OMISSION -> R.string.main_logic_omission
            UiLogic.Label.CAPTURED -> R.string.main_logic_captured
            UiLogic.Label.SOURCE -> R.string.main_logic_source
            UiLogic.Label.ADDRESS -> R.string.main_logic_address
            UiLogic.Label.HASH -> R.string.main_logic_hash
            UiLogic.Label.LOCAL_NAME -> R.string.main_logic_local_name
            UiLogic.Label.NOT_RECORDED -> R.string.main_logic_not_recorded
            UiLogic.Label.EMPTY -> R.string.main_logic_empty
            UiLogic.Label.MANUFACTURER -> R.string.main_logic_manufacturer
            UiLogic.Label.SERVICE_UUID -> R.string.main_logic_service_uuid
            UiLogic.Label.SERVICE_DATA -> R.string.main_logic_service_data
            UiLogic.Label.NOT_REPRODUCED -> R.string.main_logic_not_reproduced
            UiLogic.Label.NAME_ONLY -> R.string.main_logic_name_only
            UiLogic.Label.PAYLOAD -> R.string.main_logic_payload
            UiLogic.Label.CAPTURED_FIELDS -> R.string.main_logic_captured_fields
            UiLogic.Label.NO_MANUFACTURER -> R.string.main_logic_no_manufacturer
            UiLogic.Label.MANUFACTURER_EXCEPT -> R.string.main_logic_manufacturer_except
            UiLogic.Label.IDENTITY -> R.string.main_logic_identity
            UiLogic.Label.PARAMETER_ERROR -> R.string.main_logic_parameter_error
            UiLogic.Label.INVALID_PROFILE -> R.string.main_logic_invalid_profile
            UiLogic.Label.PROVIDER_ERROR -> R.string.main_logic_provider_error
            UiLogic.Label.SIZE_ERROR -> R.string.main_logic_size_error
            UiLogic.Label.DURATION_ERROR -> R.string.main_logic_duration_error
            UiLogic.Label.SLOT_ERROR -> R.string.main_logic_slot_error
            UiLogic.Label.LOSS_ERROR -> R.string.main_logic_loss_error
            UiLogic.Label.SEED_ERROR -> R.string.main_logic_seed_error
            UiLogic.Label.EXTENSION_ERROR -> R.string.main_logic_extension_error
            UiLogic.Label.ADDRESS_ERROR -> R.string.main_logic_address_error
            UiLogic.Label.TAG_NAME -> R.string.main_logic_tag_name
            UiLogic.Label.TAG_DESCRIPTION -> R.string.main_logic_tag_description
        }
        return context.getString(id, *args.toTypedArray())
    }

    fun diagnostic(context: Context, error: Throwable): String {
        val raw = error.message ?: error.javaClass.simpleName
        UiLogic.Label.entries.firstOrNull { it.english == raw }?.let { return format(context, it, emptyList()) }
        if (raw == "Logisch tagadres vereist zes hexoctetten, bijvoorbeeld AA:BB:CC:DD:EE:FF.")
            return format(context, UiLogic.Label.ADDRESS_ERROR, emptyList())
        val translated = CapturePresentation.describe(context, ServicePresentation.describe(context, raw))
        return if (translated != raw) translated else context.getString(R.string.main_error_diagnostic, raw)
    }

    // Only untouched bundled profiles are translated. Imported names, edited notes and evidence remain literal.
    private val bundled by lazy { Presets.all().associateBy { it.id } }
    private fun bundledId(profile: SimulationProfile): String? = profile.id.takeIf { bundled[it] == profile }
    fun profileName(context: Context, profile: SimulationProfile): String = when (bundledId(profile)) {
        "direct-baseline" -> context.getString(R.string.main_preset_direct_baseline_name)
        "repeater-triangle" -> context.getString(R.string.main_preset_repeater_triangle_name)
        "planned-dropouts" -> context.getString(R.string.main_preset_planned_dropouts_name)
        "counter-wrap-reset" -> context.getString(R.string.main_preset_counter_wrap_reset_name)
        "boundary-five-six" -> context.getString(R.string.main_preset_boundary_five_six_name)
        "short-id-collision" -> context.getString(R.string.main_preset_short_id_collision_name)
        "malformed-and-unknown" -> context.getString(R.string.main_preset_malformed_and_unknown_name)
        "rssi-unavailable" -> context.getString(R.string.main_preset_rssi_unavailable_name)
        "combined-tracking" -> context.getString(R.string.main_preset_combined_tracking_name)
        "nano-name-recognition" -> context.getString(R.string.main_preset_nano_name_recognition_name)
        "nano-direct-vn" -> context.getString(R.string.main_preset_nano_direct_vn_name)
        else -> profile.name
    }
    fun profileDescription(context: Context, profile: SimulationProfile): String = when (bundledId(profile)) {
        "direct-baseline" -> context.getString(R.string.main_preset_direct_baseline_description)
        "repeater-triangle" -> context.getString(R.string.main_preset_repeater_triangle_description)
        "planned-dropouts" -> context.getString(R.string.main_preset_planned_dropouts_description)
        "counter-wrap-reset" -> context.getString(R.string.main_preset_counter_wrap_reset_description)
        "boundary-five-six" -> context.getString(R.string.main_preset_boundary_five_six_description)
        "short-id-collision" -> context.getString(R.string.main_preset_short_id_collision_description)
        "malformed-and-unknown" -> context.getString(R.string.main_preset_malformed_and_unknown_description)
        "rssi-unavailable" -> context.getString(R.string.main_preset_rssi_unavailable_description)
        "combined-tracking" -> context.getString(R.string.main_preset_combined_tracking_description)
        "nano-name-recognition" -> context.getString(R.string.main_preset_nano_name_recognition_description)
        "nano-direct-vn" -> context.getString(R.string.main_preset_nano_direct_vn_description)
        else -> CapturePresentation.describe(context, profile.description)
    }
}
