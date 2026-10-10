# Scanner Caisse — Beli & Jolie

Application Android POS (point de vente) développée pour la boutique **Beli & Jolie**
(Aubervilliers, 93). Tourne sur un **PDA Vanquisher RD50T** avec scanner intégré
**Zebra SE4710** et imprime les tickets sur une **MUNBYN ITPP047** en Wi-Fi.

## Stack & versions

- **Kotlin 1.9.22** + **Jetpack Compose** (BOM 2024.02.00, Material 3)
- **compileSdk 34**, **minSdk 26**, **targetSdk 34** (Android 15 sur le PDA)
- **AGP 8.2.2**, **Gradle 8.2**, **JDK 17**
- **Room 2.6.1** (SQLite catalogue produits/catégories)
- **DataStore Preferences 1.0.0** (paramètres + canaux persistés)
- **kotlinx.serialization** (sérialisation JSON des canaux)
- **DantSu/ESCPOS-ThermalPrinter-Android 3.3.0** (impression ESC/POS)
- **ZXing 3.5.1** (génération des QR codes bitmap)
- **Navigation Compose 2.7.7**

## Architecture (package `com.belijolie.scanner`)

```
MainActivity.kt          Scaffold + bottom nav + enregistrement du BroadcastReceiver
ScannerApp.kt            Application : expose db, settingsStore, channelsRepo
audio/
  ScanSounds.kt          ACK / NACK via ToneGenerator
cart/
  Channel.kt             Modèles sérialisables (Channel, CartLine, ChannelsState)
  ChannelsRepository.kt  Persistance DataStore des canaux (survit au reboot)
data/
  AppDatabase.kt         Room DB "scanner.db"
  SettingsStore.kt       AppSettings + DataStore
  dao/                   CategoryDao, ProductDao
  entity/                Category, Product, ProductWithCategory
scanner/
  ScanEventBus.kt        SharedFlow<String> global de scans
  ScanReceiver.kt        BroadcastReceiver, action configurable, extra auto-découvert
printer/
  EscPosPrinter.kt       Impression ticket (TCP) + feed/cut explicites par réflexion
  TicketImage.kt         Composition bitmap pour les 2 QR codes côte à côte
ui/
  Nav.kt                 Destinations
  theme/                 Theme + Color (indigo/surface clair)
  keyboard/
    AppKeyboard.kt       Clavier maison (NUMBER, DECIMAL, IP, TEXT, SYMBOLS)
                         rendu via Dialog(fullscreen + bottom-aligned)
  checkout/
    CheckoutScreen.kt    Dropdown canaux + bouton "+ produit" + table + totaux
    CheckoutViewModel.kt handleScan + ScanSounds + sortLines (groupe + tri HT)
    AddManualProductSheet.kt   Modal "Ajouter produit" (cat libre + prix)
    UnknownProductSheet.kt     Modal scan inconnu (créer OU choisir)
  products/              Liste produits + modal création (décode prix depuis code-barres)
  categories/            Liste + add + rename + delete
  settings/              Infos commerce, adresse, QR Microstore, URL site, IP imprimante, scanner
```

## Matériel ciblé

### PDA Vanquisher RD50T
- Android 15, MediaTek, 4 Go / 128 Go, Wi-Fi
- Scanner intégré **Zebra SE4710 1D/2D**
- Firmware broadcast l'action **`com.android.serial.BARCODEPORT_RECEIVEDDATA_ACTION`**
  (découvert via `adb shell dumpsys package com.scan.scan4710`)
- Le wedge clavier du scanner envoie aussi des key events → le clavier maison bloque
  via `onPreviewKeyEvent { event -> event.nativeKeyEvent.keyCode != KEYCODE_BACK }`
  pour éviter le "1" trailing fantôme
- La config scanner se change dans l'app pré-installée **ScanSetting / Scan4710**

### Imprimante MUNBYN ITPP047
- Thermique 80 mm, 203 DPI
- Interface : USB + RS232 + LAN + **Wi-Fi** (c'est le mode utilisé)
- Config par défaut en code : `IP=192.168.1.31 Port=9100`
- Printable area ≈ 72 mm (CHARS_PER_LINE = 48)
- Le cutter mange ~2 cm → on appelle `feedPaper(500)` + `cutPaper()` explicitement
  (via **réflexion** car `EscPosPrinter.printer` est `private final` dans DantSu)

## Conventions produit & ticket

- **Prix produit stockés = HT** (hors taxe). TVA calculée au moment de l'impression :
  `TVA = HT × 0.20`, `TTC = HT + TVA`.
- **Fusion des lignes du panier** : clé = `(categoryName, unitPriceCents)` et PAS
  `productId` → si on supprime puis rescan un produit identique, les quantités fusionnent.
- **Code-barres à prix intégré** : si le code commence par 5 caractères quelconques
  suivis de 1-5 chiffres interprétables comme centimes ≤ 999.99 €, le prix est
  pré-rempli automatiquement dans le formulaire produit (`decodePriceFromBarcode`).
- **Tri sur ticket & caisse** : groupé par catégorie (ordre par prix mini croissant
  dans chaque catégorie), lignes triées par prix ascendant à l'intérieur.
- **Alternance gras** : une catégorie sur deux en gras pour la lisibilité.
- **Canaux** : plusieurs paniers parallèles persistés dans DataStore. Impression
  ne ferme PAS le canal (fermeture manuelle via ✕).

## Build & déploiement

```powershell
# ADB installé via Android SDK :
$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe

# Vérifier device
adb devices

# Compiler + installer :
# depuis Android Studio → ▶ Run (sélectionner le RD50TE)
# ou en ligne de commande (après `gradlew assembleDebug`) :
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Pour voir les logs du BroadcastReceiver scanner :
```
adb logcat -s ScanReceiver:D
```

## Fichiers de référence

- `README.md` — install + préparation du RD50T + dépannage scanner/imprimante
- `app/src/main/java/com/belijolie/scanner/printer/EscPosPrinter.kt` — layout ticket
- `app/src/main/java/com/belijolie/scanner/data/SettingsStore.kt` — paramètres (adresse,
  IP imprimante, QR Microstore, URL site, action+extra scanner)

## Décisions importantes

- **Clavier maison en Dialog plein écran** (pas `ModalBottomSheet`) → ancré en bas du
  vrai écran même quand il est appelé depuis un autre `AlertDialog` imbriqué.
- **Toutes les touches `focusProperties { canFocus = false }`** + `onPreviewKeyEvent`
  pour bloquer l'activation parasite par les key events du scanner HID.
- **URL du site** affichée en QR code côte à côte avec le QR Microstore via
  bitmap composite (ESC/POS ne sait pas placer deux QR texte sur la même ligne).
- **Scanner action découverte via `dumpsys`** sur le device réel, pas devinée.
- `dismissOnClickOutside = false` sur le modal "produit inconnu" + garde dans
  `handleScan` pour ignorer les scans quand le modal est déjà ouvert → résolu le bug
  "ouvre et ferme aussitôt" causé par des touches parasites du wedge.
