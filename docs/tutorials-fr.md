# Guide et tutoriels

[Installation (anglais)](INSTALLATION.md) · [English](tutorials.md) · [Nederlands](tutorials-nl.md)

## Installer et mettre à jour

1. Ouvrez les [téléchargements](https://github.com/benjaminvdz/openvespatrack-ble-lab/releases) et choisissez l'APK de la version GitHub.
2. Vérifiez le statut de préversion et la somme SHA-256, puis ouvrez l'APK sur le téléphone.
3. Si Android le demande, autorisez l'installation depuis ce navigateur ou gestionnaire de fichiers.
4. Ouvrez **Réglages et à propos**, choisissez le français et appliquez votre thème.

Exportez les profils et enregistrements importants avant une mise à jour.
Un certificat de signature différent peut nécessiter une réinstallation ;
désinstaller l'application efface ses données locales.

## Utiliser un téléphone comme balise de test

**Matériel :** un téléphone Android prenant en charge la publicité BLE et un
récepteur indépendant, par exemple un autre téléphone ou BLE Tagfinder sur
ordinateur.

1. Ouvrez **Réglages et à propos** pour choisir la langue et le thème.
2. Ouvrez **Balise de test** et notez son identité commençant par `OVT-`.
3. Activez Bluetooth, choisissez **Démarrer la balise** et accordez les
   autorisations demandées. Autorisez les notifications pour accéder facilement
   à l'arrêt.
4. Sur le récepteur, affichez **tous les appareils BLE**, ou filtrez par l'UUID
   de service `6f8a5b00-7d3e-4c6a-9b21-0a5c2d7e9101`. Vérifiez l'identité dans
   les données de service. Un filtre destiné à un autre protocole de balise
   peut masquer ce signal.
5. Vérifiez d'abord la réception à proximité. Laissez le téléphone émetteur à
   un point connu et déplacez le récepteur. Notez distance, obstacles et orientation.
6. Éteignez l'écran de l'émetteur pour un essai. Vérifiez la continuité sur le
   récepteur indépendant ; l'état affiché par l'émetteur ne la prouve pas.
7. Utilisez **Arrêter** dans l'application ou la notification et vérifiez qu'aucune
   nouvelle observation n'arrive.

Ce mode n'a pas de minuterie de scénario. La désactivation de Bluetooth, le
retrait d'une autorisation, l'arrêt forcé, le redémarrage ou les restrictions
de batterie peuvent interrompre l'émission. Elle ne reprend jamais
automatiquement après le redémarrage du processus. Le RSSI représente la
réception ; ce n'est pas une mesure de distance ou de direction.

Si l'arrêt n'est pas confirmé, réessayez **Arrêter** ou désactivez Bluetooth.
L'application garde la radio réservée jusqu'à confirmation de sa libération ;
cet état incertain ne prouve pas que le signal a cessé.

## Essayer l'exemple fourni

Téléchargez le [ZIP d'enregistrement synthétique](../examples/capture-fixtures/synthetic-nano-120.zip).
Dans les archives de **Enregistrer et rejouer**, importez ce ZIP, vérifiez
l'aperçu et sauvegardez une copie. Examinez la chronologie et exportez-la à
nouveau sans démarrer le BLE. Les 120 observations et leur motif RSSI sont des
données générées, dont deux valeurs RSSI volontairement invalides ; ce ne sont
pas des mesures d'une véritable balise.

## Enregistrer et conserver des observations

1. Arrêtez la balise ou tout autre essai radio actif.
2. Ouvrez **Enregistrer et rejouer**, choisissez un filtre adapté, recherchez
   les appareils et sélectionnez celui qui vient d'être observé.
3. Donnez un nom et des notes à l'enregistrement, puis démarrez la capture.
4. Arrêtez après l'essai. Ouvrez l'enregistrement sauvegardé pour examiner les
   octets d'origine, le RSSI mesuré et les diagnostics.
5. Exportez un **ZIP** avec le sélecteur de fichiers Android, hors de l'application.

Consulter ou relire une chronologie enregistrée est une opération hors ligne
qui n'émet aucun signal. Sauvegardez les données importantes avant une
désinstallation ou le passage à une version signée avec un autre certificat.

## Reconstruire une publicité enregistrée

Depuis un enregistrement arrêté, préparez une publicité prise en charge ou
une série sélectionnée. Vérifiez l'aperçu et sauvegardez le profil. Testez-le
d'abord sans radio. L'émission réelle nécessite un démarrage BLE explicite.

La relecture reconstruit le contenu pris en charge avec de nouveaux créneaux
fixes. Elle ne reproduit pas l'adresse de l'émetteur, la chronologie radio
d'origine, le RSSI reçu ou les services GATT. Conservez la capture source lors
de la comparaison des résultats.

## Découvrir l'application sans adaptateur

Dans l'onglet de test, choisissez un profil synthétique et démarrez un essai
sans radio. Gardez l'application visible ; utilisez les commandes de pause,
reprise et arrêt. Le graphique du scénario représente des données synthétiques
de payload, pas une mesure physique.
