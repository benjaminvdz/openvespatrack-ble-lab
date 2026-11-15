package org.anthrobios.vespasim.core.capture

import java.util.Locale

/** Report presentation only. The capture, raw scan records and logs are never rewritten. */
internal class CaptureReportText(language: String) {
    val language = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        .takeIf { it in setOf("en", "nl", "fr") } ?: "en"
    val locale: Locale = Locale.forLanguageTag(this.language)
    operator fun get(label: CaptureReportLabel, vararg values: Any?): String {
        val template = when (language) { "nl" -> label.nl; "fr" -> label.fr; else -> label.en }
        return if (values.isEmpty()) template else String.format(locale, template, *values)
    }
}

internal enum class CaptureReportLabel(val en: String, val nl: String, val fr: String) {
    INTRO("Android BLE recording — schema 1; export snapshot with status %1\$s.", "Android BLE-opname — schema 1; exportmomentopname van status %1\$s.", "Enregistrement BLE Android — schéma 1 ; instantané d’export à l’état %1\$s."),
    RECORDING("Recording", "Opname", "Enregistrement"),
    ADDRESS("Selected radio address", "Geselecteerd radioadres", "Adresse radio sélectionnée"),
    VARIANT("Variant (researcher label)", "Variant (onderzoekerslabel)", "Variante (étiquette du chercheur)"),
    TIME("Start / end (epoch ms)", "Start / einde (epoch ms)", "Début / fin (epoch ms)"),
    ACTIVE("active", "actief", "en cours"),
    BUILD("App / build", "App / build", "Application / compilation"),
    COUNTS("Observations / diagnostics", "Waarnemingen / diagnostiek", "Observations / diagnostics"),
    SOURCE("Import source", "Importbron", "Source d’import"),
    LOCAL("local recording", "lokale opname", "enregistrement local"),
    HASH("Source archive SHA-256", "Bronarchief SHA-256", "SHA-256 de l’archive source"),
    NOT_APPLICABLE("not applicable", "niet van toepassing", "sans objet"),
    SIGNAL("Received signal", "Ontvangen signaal", "Signal reçu"),
    STATISTICS("Valid RSSI: %1\$s; invalid/platform values: %2\$s. Minimum %3\$s, maximum %4\$s, mean %5\$s dBm. All raw values are unchanged in JSONL and CSV.", "Geldige RSSI: %1\$s; ongeldige/platformwaarden: %2\$s. Minimum %3\$s, maximum %4\$s, gemiddelde %5\$s dBm. Alle ruwe waarden staan ongewijzigd in JSONL en CSV.", "RSSI valides : %1\$s ; valeurs invalides/de plateforme : %2\$s. Minimum %3\$s, maximum %4\$s, moyenne %5\$s dBm. Toutes les valeurs brutes restent inchangées dans les fichiers JSONL et CSV."),
    CHART("RSSI points by sequence number", "RSSI-punten per volgnummer", "Points RSSI par numéro de séquence"),
    CHART_AXIS("Sequence number; at most 500 regularly sampled points", "Volgnummer; maximaal 500 regelmatig gekozen punten", "Numéro de séquence ; au plus 500 points échantillonnés régulièrement"),
    INTERPRETATION("Local RSSI is measured by this receiver; it is not the RSSI in relay payloads. Android supplies combined advertising/scan-response bytes; this does not prove original RF order, packet timing or delivery. Replay requests semantic fields again; Android may change the MAC, AD layout and timing.", "Lokale RSSI is gemeten door deze ontvanger, niet de RSSI in repeaterpayloads. Android levert samengevoegde advertising/scan-response-bytes; oorspronkelijke RF-volgorde, pakkettiming en aflevering worden hiermee niet bewezen. Een replay vraagt opnieuw semantische velden aan; Android kan MAC, AD-indeling en timing wijzigen.", "Le RSSI local est mesuré par ce récepteur ; ce n’est pas le RSSI contenu dans les données de relais. Android fournit les octets combinés d’advertising et de réponse de recherche ; cela ne prouve ni l’ordre RF, ni la chronologie des paquets, ni leur livraison d’origine. Une réémission demande à nouveau les champs sémantiques ; Android peut modifier l’adresse MAC, l’organisation AD et la chronologie."),
    NOTES("Notes — original text", "Notities — oorspronkelijke tekst", "Notes — texte d’origine"),
    DEVICE("Receiver — original details", "Ontvangtoestel — oorspronkelijke gegevens", "Récepteur — détails d’origine"),
    SETTINGS("Settings — original values", "Instellingen — oorspronkelijke waarden", "Paramètres — valeurs d’origine"),
    ARCHIVE("The ZIP contains original JSONL, CSV, this summary, source metadata and a SHA-256 manifest. Hashes establish archive integrity, not origin or RF authenticity. Original notes, metadata and evidence retain their recorded language.", "De ZIP bevat oorspronkelijke JSONL, CSV, deze samenvatting, bronmetadata en een SHA-256-manifest. Hashes bewijzen integriteit van het archief, geen herkomst of RF-authenticiteit. Oorspronkelijke notities, metadata en bewijs behouden hun vastgelegde taal.", "Le ZIP contient les données JSONL d’origine, le CSV, ce résumé, les métadonnées source et un manifeste SHA-256. Les empreintes établissent l’intégrité de l’archive, pas sa provenance ni son authenticité RF. Les notes, métadonnées et preuves d’origine conservent leur langue enregistrée."),
    FOOTER("OpenVespaTrack is an open-source project by AnthroBios Nexus. Standalone report without scripts or network resources; print or save as PDF from your browser.", "OpenVespaTrack is een opensourceproject van AnthroBios Nexus. Zelfstandig rapport zonder scripts of netwerkbronnen; druk af of bewaar als PDF vanuit de browser.", "OpenVespaTrack est un projet open source d’AnthroBios Nexus. Rapport autonome sans scripts ni ressources réseau ; imprimez-le ou enregistrez-le au format PDF depuis votre navigateur."),
    RUNNING("Running — snapshot", "Lopend — momentopname", "En cours — instantané"),
    COMPLETED("Completed", "Voltooid", "Terminé"),
    STOPPED("Stopped", "Gestopt", "Arrêté"),
    FAILED("Failed / incomplete", "Mislukt / onvolledig", "Échec / incomplet"),
    INTERRUPTED("Interrupted", "Onderbroken", "Interrompu");
}
