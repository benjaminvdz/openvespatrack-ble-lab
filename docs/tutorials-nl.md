# Handleiding en tutorials

[Installatie (Engels)](INSTALLATION.md) · [English](tutorials.md) · [Français](tutorials-fr.md)

## Installeren en bijwerken

1. Open [Downloads](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases) en kies de APK bij de GitHub-release.
2. Controleer de previewstatus en SHA-256-controlesom. Open de APK op je telefoon.
3. Sta installatie vanuit die browser of bestandsbeheerder toe wanneer Android daarom vraagt.
4. Open **Instellingen & over**, kies Nederlands en pas je thema toe.

Exporteer belangrijke profielen en opnames vóór een update. Een APK met een
ander ondertekeningscertificaat kan een herinstallatie vereisen; verwijderen
van de app wist haar lokale gegevens.

## Een telefoon als testbaken gebruiken

**Nodig:** een Android-telefoon die BLE-advertenties ondersteunt en een tweede
ontvanger, bijvoorbeeld een andere telefoon of BLE Tagfinder op een computer.

1. Open **Instellingen & over** en kies je taal en thema.
2. Open **Testbaken** en noteer de identiteit die met `OVT-` begint.
3. Zet Bluetooth aan, kies **Start baken** en geef de gevraagde toestemmingen.
   Sta meldingen toe zodat de stopknop gemakkelijk bereikbaar blijft.
4. Kies in de nieuwere BLE Lab op het tweede toestel de filter voor **OpenVespaTrack-testbakens** in het opnamescherm. Start de scan en vergelijk de `OVT-`-identiteit. Kies in 0.4.0-rc.2 of een andere BLE-scanner **alle BLE-toestellen** zonder naamfilter. Kijk bij **Service Data** naar UUID `6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101`. De `OVT-`-identiteit staat in die servicedata.
5. Controleer eerst van dichtbij of het signaal aankomt. Leg de zendende
   telefoon op een gekend testpunt en verplaats de ontvanger. Noteer afstand,
   obstakels en oriëntatie.
6. Schakel voor een proef het scherm van de zender uit. Controleer op de tweede
   ontvanger of de ontvangst doorgaat; de status op de zender bewijst dit niet.
7. Kies **Stop** in de app of melding en controleer dat er geen nieuwe
   waarnemingen meer binnenkomen.

Deze modus heeft geen scenariotimer. Bluetooth uitschakelen, ingetrokken
rechten, geforceerd stoppen, herstarten en batterijbeperkingen kunnen het
uitzenden onderbreken. Na een procesherstart begint de app nooit vanzelf
opnieuw. RSSI is ontvangststerkte, geen gemeten afstand of richting.

Wordt stoppen niet bevestigd, probeer **Stop** opnieuw of zet Bluetooth uit.
De app houdt de radio bezet totdat opruimen is bevestigd; die onzekere status
bewijst niet dat het signaal gestopt is.

## Het meegeleverde voorbeeld proberen

Download de [synthetische opname-ZIP](../examples/capture-fixtures/synthetic-nano-120.zip).
Importeer die in het opnamearchief onder **Opnemen & replay**, controleer de
voorvertoning en bewaar een kopie. Bekijk de tijdlijn en exporteer haar opnieuw
zonder BLE te starten. De 120 waarnemingen en het RSSI-patroon zijn gegenereerde
testdata, met twee bewust ongeldige RSSI-waarden; het is geen echte tagmeting.

## Een opname maken en bewaren

1. Stop het baken of een andere actieve radioproef.
2. Open **Opnemen & replay**, kies een passende filter, zoek toestellen en
   selecteer het bedoelde recent waargenomen toestel.
3. Geef de opname een naam en notities en start de opname van dat toestel.
4. Stop na de meting. Open de bewaarde opname voor de oorspronkelijke bytes,
   gemeten RSSI en diagnose.
5. Exporteer een **ZIP** via de Android-bestandskiezer en bewaar die buiten de app.

Een opgeslagen tijdlijn bekijken of afspelen gebeurt offline en zendt niets
uit. Maak een back-up voordat je de app verwijdert of naar een anders
ondertekende versie overstapt.

## Opgenomen inhoud opnieuw aanbieden

Bereid vanuit een gestopte opname één ondersteunde advertentie of een gekozen
reeks voor. Controleer de voorvertoning en bewaar het replayprofiel. Voer eerst
een proef zonder radio uit. Echt uitzenden vereist een aparte, bewuste BLE-start.

Replay bouwt ondersteunde advertentie-inhoud opnieuw op met nieuwe vaste
tijdslots. Het oorspronkelijke zendadres, de radiotiming, ontvangen RSSI en
GATT-diensten worden niet gekopieerd. Bewaar de bronopname bij vergelijkingen.

## Kennismaken zonder Bluetoothadapter

Open de tab **Proef**, kies een synthetisch profiel en gebruik **Start dry-run**.
De app voert het schema zonder Bluetooth uit. Houd de app zichtbaar; gebruik
Pauze, Hervatten en Stop. De scenariografiek toont synthetische payloadgegevens
en is geen fysieke meting.
