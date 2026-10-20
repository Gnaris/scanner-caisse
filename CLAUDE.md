# Scanner Caisse — Beli & Jolie

Application Android POS (point de vente) développée pour la boutique **Beli & Jolie**
(Aubervilliers, 93). Tourne sur un **PDA Vanquisher RD50T** avec scanner intégré
**Zebra SE4710** et imprime les tickets sur une **MUNBYN ITPP047** en Wi-Fi.
Deux sous-projets dans ce repo :
- **`app/`** — app Android (Kotlin + Compose)
- **`pc-listener/`** — logiciel Windows (Python + Tkinter) qui réceptionne
  les exports CSV / JSON / Sage 50 envoyés par l'app via Wi-Fi, avec mise à
  jour auto depuis GitHub Releases.

## Stack & versions (app Android)

- **Kotlin 1.9.22** + **Jetpack Compose** (BOM 2024.02.00, Material 3)
- **compileSdk 34**, **minSdk 26**, **targetSdk 34** (Android 15 sur le PDA)
- **AGP 8.2.2**, **Gradle 8.2**, **JDK 17**
- **Room 2.6.1** (SQLite catalogue produits/catégories) — schéma v5,
  migrations destructives via `fallbackToDestructiveMigration()`
- **DataStore Preferences 1.0.0** (paramètres + canaux persistés)
- **kotlinx.serialization** (sérialisation JSON des canaux)
- **DantSu/ESCPOS-ThermalPrinter-Android 3.3.0** (impression ESC/POS)
- **ZXing 3.5.1** (génération des QR codes bitmap)
- **Navigation Compose 2.7.7**

## Architecture app Android (package `com.belijolie.scanner`)

```
MainActivity.kt          Scaffold + bottom nav (62 dp, labels sur 2 lignes
                         FR\n中文) + BroadcastReceiver
ScannerApp.kt            Application : expose db, settingsStore, channelsRepo
audio/
  ScanSounds.kt          ACK / NACK via ToneGenerator
cart/
  Channel.kt             Modèles sérialisables (Channel, CartLine, ChannelsState).
                         CartLine porte 3 snapshots pour l'impression EN et
                         l'export Sage : categoryNameEn, nameEn, sageArticleCode
                         (tous nullable, défauts null → compat sérialisation).
                         Channel porte discountPercent (0-100, défaut 0) :
                         remise de caisse appliquée uniquement sur l'écran et
                         le ticket imprimé — l'export reste sur les prix bruts.
                         Helpers discountCents + totalCentsAfterDiscount.
  ChannelsRepository.kt  Persistance DataStore des canaux (survit au reboot).
                         addProductToActive propage les 3 snapshots EN/Sage.
                         setActive accepte String? ; clearActive() annule le
                         canal courant (utilisé à l'entrée de la liste).
                         setDiscountInActive(percent) pour la remise.
data/
  AppDatabase.kt         Room DB "scanner.db" (v5, fallbackToDestructiveMigration)
  SettingsStore.kt       AppSettings + DataStore. Champs scannerAction / scannerExtra
                         toujours persistés mais plus éditables depuis l'UI.
  dao/                   CategoryDao, ProductDao
  entity/                Category : name (unique NOCASE) + nameEn (traduction
                         EN pour ticket) + sageArticleCode (optionnel, Sage 50).
                         Product : barcode (unique) + nameEn (override EN pour
                         produit manuel). ProductWithCategory.
scanner/
  ScanEventBus.kt        SharedFlow<String> global de scans
  ScanReceiver.kt        BroadcastReceiver, action configurable, extra auto-découvert
printer/
  TicketLang.kt          Enum TicketLang { FR, EN } + data class TicketLabels avec
                         ses 12 libellés (headers table, totaux, titres QR,
                         texte "Avis Google"). resolveLineDisplayName() applique
                         la cascade : si lang=EN alors nameEn → categoryNameEn
                         → fallback categoryName.
  EscPosPrinter.kt       Impression ticket (TCP) + feed/cut explicites par
                         réflexion. printReceipt(settings, lines, lang). Compose
                         les sections QR dynamiquement selon ce qui est configuré
                         (voir Conventions ticket plus bas).
  TicketImage.kt         Bitmaps QR codes (WIDTH_PX = 576, largeur imprimable
                         MUNBYN). API : data class QrEntry(title, content, footer)
                         + composeSingleQrSection(QrEntry) [centré] +
                         composeDoubleQrSection(left, right) [côte à côte] +
                         composeReviewQrSection(url, lang) [style "appel à l'action"
                         gros titre + subtitle, +10px bottomMargin anti-coupure].
                         Alignement via [C]<img>.
export/
  ChannelExporter.kt     Export via POST HTTP. enum ExportFormat { CSV, JSON, SAGE }.
                         - CSV : BOM UTF-8 + ; + virgule décimale (ouverture Excel
                           FR), POST vers settings.exportUrl avec ?filename=.
                         - JSON : idem CSV mais en JSON brut.
                         - SAGE : crée DIRECTEMENT une facture provisoire dans
                           Sage 50 via SageBridge. Le PDA POST un JSON
                           InvoiceRequest à {baseUrl}/sage-facture du pc-listener
                           (déduit en remplaçant le path de settings.exportUrl) ;
                           pc-listener relaie à http://127.0.0.1:8765/facture
                           (SageBridge, loopback). Code client en dur à "003",
                           orderNo = channel.id (anti-doublon natif SageBridge
                           → 409 si recliqué). Description de ligne = nom
                           catégorie en MAJUSCULES, PAS de code-barres (choix
                           produit : le barcode ne doit pas apparaître sur la
                           facture Sage). validateSage() avant appel réseau →
                           SageExportValidationException si codes articles
                           manquants. Return type : sealed ExportSuccess { File,
                           SageInvoice(numero) } pour que le ViewModel choisisse
                           la bonne string de confirmation.
util/
  NetworkErrors.kt       Throwable.toHumanMessage(ctx) : message FR humain selon
                         type d'exception (UnknownHostException, ConnectException…)
                         + détection via ConnectivityManager si Wi-Fi/Ethernet absent
                         → "📶 Wi-Fi non connecté".
  CategoryTranslator.kt  Dictionnaire interne FR→EN de bijouterie/accessoires
                         (~100 entrées : boucle→Earring, bracelet→Bracelet,
                         bague→Ring, collier→Necklace, etc.). suggestEnglishName(fr)
                         utilisé pour pré-remplir Category.nameEn à la création
                         (toujours modifiable à la main).
update/
  UpdateState.kt         sealed class UpdateState { Idle, Checking, UpToDate,
                         Available, Downloading(pct), ReadyToInstall(apkPath),
                         Error(msg) } + data class ReleaseInfo (tag, version,
                         notes, apkUrl).
  UpdateManager.kt       Singleton exposé par ScannerApp.updateManager. StateFlow
                         <UpdateState>. checkForUpdate(silent) : GET GitHub API
                         /releases?per_page=20, filtre tag `app-v*`, prend la
                         plus récente par semver. download(release) : stream
                         l'APK dans cacheDir/updates/, émet Downloading(pct).
                         install(ctx, apk) : si canRequestPackageInstalls()=false
                         → Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES ; sinon
                         FileProvider + Intent.ACTION_VIEW application/
                         vnd.android.package-archive → prompt install système.
                         Repo lu depuis BuildConfig.GITHUB_REPO.
  UpdateDialog.kt        Dialog Compose unique couvrant Available / Downloading /
                         ReadyToInstall / Error. Verrouille la fermeture
                         pendant le téléchargement (dismissOnBackPress=false).
                         Rendu dans MainActivity.AppRoot via collectAsState.
ui/
  Nav.kt                 Destinations (labelRes: @StringRes au lieu de String
                         → labels bilingues depuis strings.xml). L'onglet
                         principal est "Channels" (route "channels") qui
                         remplace l'ancien "Checkout". SubRoutes.CHANNEL_DETAIL
                         ("channel/{id}") pour le détail d'un canal — pas
                         listé dans la bottom bar mais l'onglet Channels reste
                         surligné quand on y est.
  theme/                 Theme + Color (indigo/surface clair)
  keyboard/
    AppKeyboard.kt       Clavier maison (NUMBER, DECIMAL, IP, TEXT, SYMBOLS)
                         rendu via Dialog(fullscreen + bottom-aligned).
                         BasicTextField(readOnly=true) → l'IME Android ne peut
                         JAMAIS s'ouvrir, même quand le KeyboardDialog est
                         empilé par-dessus un AlertDialog. Curseur bleu + poignées
                         de sélection + menu natif Tout sélectionner / Copier
                         restent fonctionnels en readOnly. SOFT_INPUT_STATE_ALWAYS_HIDDEN
                         gardé en ceinture + bretelles. Overlay noir translucide
                         dismissable via pointerInput (pas clickable, sinon
                         vole le focus). Nos touches modifient `value` via onChange.
                         Backspace long-press = suppression en chaîne (400ms puis
                         50ms/tick, via rememberUpdatedState pour éviter stale
                         closures).
  checkout/
    ChannelsListScreen.kt  Nouvel écran "liste des canaux" = point d'entrée
                         de l'onglet Canal. Header : gros bouton vert
                         "Créer un canal" (crée + navigue vers détail en une
                         action). En dessous : liste verticale de cartes
                         (nom + nb articles + total TTC + crayon renommer +
                         croix fermer + chevron). Clic sur carte → détail
                         (route "channel/{id}"). Au montage, clearActiveChannel()
                         pour que les scans ne tapent plus aucun canal tant
                         qu'on n'ouvre pas un détail.
    CheckoutScreen.kt    Écran détail d'un canal (fonction ChannelDetailScreen
                         avec channelId + onBack). PLUS de dropdown de switch
                         de canal. Barre du haut : [← retour] + [+ Ajouter
                         produit] + mini carré "×N" côte à côte sur une seule
                         ligne. Sous la barre : nom du canal + badge remise
                         si active (-X% vert). 4 boutons icône-only côte à côte
                         en bas : Encaissement (vert), Remise (violet / vert
                         si active avec % affiché), Export (bleu), Imprimer
                         (indigo). Panier à fond gris clair (0xFFF3F4F6),
                         chaque ligne dans une Surface blanche arrondie 8dp.
                         Si discountPercent > 0 : prix de ligne affiché barré
                         (gris) + prix remisé (vert) ; totaux récap avec ligne
                         HT barré + ligne "Remise -X%" verte + "Total HT" vert
                         remisé. Headers colonnes empilés 2 lignes (FR\n中文).
                         LaunchedEffect(channelId) → selectChannel. Si le canal
                         est supprimé pendant qu'on l'affiche → onBack auto.
    ExportFormatPickerDialog.kt  Dialog custom avec 3 cartes côte à côte (Excel
                         vert, JSON orange, Sage 50 bleu), icône + label +
                         sous-texte, Annuler discret en bas.
    PrintConfirmDialog.kt  Dialog impression + sélecteur de langue du ticket
                         (Français / English, FR par défaut). Bouton Annuler =
                         OutlinedButton gris pâle (fond visible, pas fondu),
                         bouton Imprimer violet, les deux en weight(1f) symétriques.
    CheckoutViewModel.kt handleScan + ScanSounds + sortLines + printActive(lang) +
                         exportChannel(id, format). _unknownBarcode = String?.
                         Messages snackbar via context.getString(R.string.*).
    AddManualProductSheet.kt   Modal "Ajouter produit" : chips catégories +
                         champ catégorie + champ nom anglais (auto-rempli depuis
                         CategoryTranslator, modifiable) + prix.
    UnknownProductSheet.kt     Modal scan inconnu (créer OU choisir).
    CashPaymentSheet.kt        Modal encaissement : Total HT + input "Montant reçu"
                               + reste à régler / à rendre / soldé en direct.
                               Bouton rouge "Clôturer le canal" en bas (avec
                               AlertDialog de confirmation nommant le canal) qui
                               supprime le canal définitivement sans encaisser.
  products/              Liste produits + modal création (décode prix depuis
                         code-barres via decodePriceFromBarcode).
  categories/            Liste + add + edit + delete. CategoryEditDialog avec
                         3 champs : nom FR, nom EN (suggestion auto depuis
                         CategoryTranslator), code article Sage. Métadonnées
                         affichées sous le nom dans la liste
                         (ex. "EN : Earrings · Sage : ART0003").
  settings/
    SettingsScreen.kt    Infos commerce, adresse, QR Microstore, URL site,
                         URL Avis Google, IP imprimante, URL export PC, code
                         article Sage pour produits manuels. Section Scanner
                         retirée de l'UI (valeurs par défaut conservées en
                         DataStore). Bouton "Aperçu du ticket" (TicketPreviewSheet).
    SettingsViewModel.kt save + testPrint.
    TicketPreviewSheet.kt   Rendu Compose fac-similé du ticket (monospace +
                            mêmes bitmaps QR) avec toggle FR/EN en haut pour
                            prévisualiser les deux langues. Logique dynamique
                            des QR identique à EscPosPrinter. Les constantes
                            COL_* dupliquées depuis EscPosPrinter → à garder sync.
res/
  values/strings.xml     Textes UI au format bilingue permanent "Français / 中文"
                         (ou "Français\n中文" quand il faut empiler sur 2 lignes
                         pour tenir dans la largeur : nav bar, headers panier,
                         boutons Save/Test, labels Code postal/Ville, états vides).
                         Pas de switch de langue (c'est les 2 côte à côte partout).
```

## Matériel ciblé

### PDA Vanquisher RD50T
- Android 15, MediaTek, 4 Go / 128 Go, Wi-Fi
- Scanner intégré **Zebra SE4710 1D/2D**
- Firmware broadcast l'action **`com.android.serial.BARCODEPORT_RECEIVEDDATA_ACTION`**
  (découvert via `adb shell dumpsys package com.scan.scan4710`)
- Le wedge clavier du scanner envoie aussi des key events → `onPreviewKeyEvent`
  dans le KeyboardDialog consomme tout sauf BACK, et toutes les touches ont
  `focusProperties { canFocus = false }` pour éviter l'activation parasite
- La config scanner se change dans l'app pré-installée **ScanSetting / Scan4710**

### Imprimante MUNBYN ITPP047
- Thermique 80 mm, 203 DPI
- Interface : USB + RS232 + LAN + **Wi-Fi** (c'est le mode utilisé)
- Config par défaut en code : `IP=192.168.1.31 Port=9100`
- Printable area ≈ 72 mm = 576 dots (CHARS_PER_LINE = 48)
- Le cutter mange ~2 cm → on appelle `feedPaper(500)` + `cutPaper()` explicitement
  (via **réflexion** car `EscPosPrinter.printer` est `private final` dans DantSu)
- Les bitmaps QR du bas de ticket sont rendus à `WIDTH_PX = 576` (= largeur
  imprimable réelle) et insérés via `[C]<img>` → centrage pile symétrique.
  **Ne PAS passer à `[L]<img>`** : l'image serait décalée à gauche de 64 dots.

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
- **Format colonnes ticket** : `Cat 18 + Qt 4 + P.U. 8 + HT 9` (= 42 chars internes
  + 2 × 3 chars padding = 48 chars). Pas de colonnes TVA / TTC par ligne — elles
  n'apparaissent qu'en ligne de résumé en bas. Quantité préfixée `x` (ex. `x3`),
  prix suffixé `€` (ex. `29,17€`).
- **QR du ticket** (layout dynamique selon ce qui est configuré en settings) :
  - **3 QR configurés** → format historique : Microstore + Boutique en ligne
    côte à côte en style petit (titres 24pt BOLD, footer 26pt BOLD) +
    Avis Google en dessous centré en style "appel à l'action" (titre 32pt BOLD
    "LAISSEZ UN AVIS !" / "LEAVE US A REVIEW!" + QR 220px + subtitle 26pt BOLD
    "Scannez pour nous noter sur Google" / "Scan to rate us on Google").
  - **2 QR configurés** → côte à côte en style petit, quel que soit le couple
    (M+B, M+Avis, B+Avis). Dans ce cas l'Avis Google prend le label générique
    "AVIS GOOGLE" / "GOOGLE REVIEWS" (pas le style "appel à l'action").
  - **1 QR configuré** → centré seul au milieu du papier. Si c'est l'Avis Google
    seul → style "appel à l'action" conservé ; si c'est Microstore ou Boutique
    seul → style petit uniforme.
  - **0 QR** → rien.
  - L'ordre pour les combinaisons 2 QR suit la priorité Microstore → Boutique →
    Avis. Logique implémentée dans `EscPosPrinter.printReceipt` et dupliquée à
    l'identique dans `TicketPreviewSheet` pour le rendu Compose.

## Unicité produits & catégories

- **Catégorie unique par nom** (NOCASE) : index Room `@Index(unique = true)` sur
  `Category.name`. `CategoryDao.findByName(name)` pour pré-check côté UI.
- **Produit unique par barcode** : index déjà en place depuis v1. `createCategory`
  dans CheckoutViewModel renvoie l'id d'une catégorie existante si le nom match
  (pas de prompt d'erreur, création silencieuse). `createAndAddToCart` : si le
  barcode existe déjà, ajoute l'existant au canal au lieu de créer un doublon.

## Build & déploiement (app Android)

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

## Release APK & auto-update (app Android)

L'app Android est signée avec un keystore release unique (`app/belijolie-release.jks`,
git-ignored). Toutes les versions successives doivent être signées avec le même
keystore sinon Android refuse l'install (vu comme une app différente).

### Fichiers sensibles git-ignored

- `app/belijolie-release.jks` — keystore RSA 2048, validité 10000 jours
- `keystore.properties` — mots de passe / alias (lus par `app/build.gradle.kts`)

### Fichier `version.properties` (committed)

Reflète la dernière version publiée (versionName + versionCode). Lu par
`app/build.gradle.kts` comme fallback quand Gradle est lancé **sans `-P`**
(= Android Studio ▶ Run, `gradlew assembleDebug` à la main). Objectif : un
build local embarque la même version que la Release GitHub la plus récente,
donc l'updater ne propose pas une MAJ qu'on vient d'installer.

`release-local.ps1` met ce fichier à jour automatiquement avant chaque
release et commit le bump. En CI, le workflow passe `-PversionName`/
`-PversionCode` depuis le tag → ces lignes du fichier sont écrasées.

**Backup obligatoire** du keystore + mot de passe sur 2 supports distincts
(clé USB + cloud chiffré). Perdu = plus jamais de MAJ possible sur les PDA
existants, obligé de désinstaller/réinstaller à chaque version.

### Secrets GitHub à configurer (une seule fois)

Dans `Settings → Secrets and variables → Actions` :

- `ANDROID_KEYSTORE_B64` — contenu du `.jks` encodé base64
  (`[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\belijolie-release.jks"))`)
- `ANDROID_KEYSTORE_PASSWORD` — mot de passe du keystore
- `ANDROID_KEY_ALIAS` — alias de la clé (`belijolie`)
- `ANDROID_KEY_PASSWORD` — mot de passe de la clé (identique au keystore)

### Build release local (dépannage)

```powershell
# Le fichier keystore.properties doit exister à la racine.
.\gradlew.bat assembleRelease
# APK dans app\build\outputs\apk\release\app-release.apk
```

Si `keystore.properties` est absent → fallback automatique sur signature
debug (APK installable localement mais JAMAIS à publier).

### Publier une nouvelle version Android

**Option A — release locale rapide (~1 min, recommandé)** :

```powershell
# Commit d'abord ta modif comme d'habitude, puis :
.\release-local.ps1 1.0.10
```

Le script `release-local.ps1` à la racine :
1. Vérifie que la working tree est clean
2. Push le code sur origin/main
3. Build l'APK release signé en local (via keystore.properties)
4. Crée la Release GitHub + le tag en une fois via `gh release create`

Le workflow CI (`android-release.yml`) se déclenche quand même sur le push
du tag et rebuilde le même APK en parallèle (~4 min). C'est inoffensif —
`softprops/action-gh-release` écrase le même binaire. L'important c'est
que la release est en ligne tout de suite via le script local.

**Option B — tout en CI (~4 min, backup)** :

```bash
# 1. Mettre à jour le code
git add .
git commit -m "feat(app): ..."
git push

# 2. Taguer la version → déclenche .github/workflows/android-release.yml
git tag app-v1.0.1
git push origin app-v1.0.1
```

~4 minutes plus tard, la Release GitHub est publiée avec
`scanner-caisse-1.0.1.apk` en asset. Les PDA installés chez le client
détectent la MAJ au prochain lancement (check silencieux 3s après démarrage)
ou via le bouton **Vérifier les mises à jour** dans Settings.

### Première install sur un nouveau PDA

1. Récupérer `scanner-caisse-X.Y.Z.apk` depuis la Release GitHub
2. Transférer sur le PDA (clé USB / email / ADB)
3. Taper dessus → prompt système "Installer" (une fois "sources inconnues"
   accordé pour l'app source)

### Après la première install : auto-update

L'app check `https://api.github.com/repos/Gnaris/scanner-caisse/releases` au
démarrage, filtre les tags `app-v*`, et compare à `BuildConfig.VERSION_NAME`.
Si une version plus récente existe → `UpdateDialog` apparaît. L'utilisateur
clique **Installer** → download en tâche de fond → prompt install système
Android apparaît automatiquement une fois le téléchargement terminé.

**NB** : Android oblige à passer par le prompt système d'install (pas de
MAJ silencieuse possible sans Device Owner, qu'on n'a pas configuré pour
rester simple sur ce PDA unique).

## pc-listener (Python / Windows)

Logiciel système côté PC qui écoute les exports de l'app Android. Vit dans
`pc-listener/`. Distribué via **GitHub Releases** avec mise à jour auto.

### Structure
```
pc-listener/
├── listener.py            Script principal (serveur HTTP + tray + settings +
│                          updater + single-instance detection)
├── build_icon.py          Génère build/icon.ico depuis le design "S" bleu
│                          (7 résolutions : 16, 24, 32, 48, 64, 128, 256)
├── requirements.txt       pystray, Pillow
├── build.ps1              Build local : venv + PyInstaller --onedir + Inno Setup
├── installer.iss          Script Inno Setup pour l'installeur Windows
├── README.md
└── build/                 (git-ignored) icon.ico + PyInstaller artifacts
└── dist/                  (git-ignored) exe + installeur + zip de release

.github/workflows/pc-listener-release.yml
   Build auto-déclenché sur tag `pc-listener-v*` → produit l'exe PyInstaller
   onedir + le ZIP + l'installeur Inno Setup, publie dans une Release GitHub.
```

### Fonctionnalités
- Serveur HTTP sur port configurable (défaut 8080), endpoints :
  - `POST /export?filename=…` : reçoit CSV / JSON du PDA → écrit dans le dossier
    d'export.
  - `POST /sage-facture` : relais vers SageBridge (`http://127.0.0.1:8765/facture`,
    loopback). Prend le JSON InvoiceRequest du PDA, le fait suivre tel quel,
    renvoie status + body SageBridge au PDA. Si SageBridge n'est pas lancé →
    HTTP 502 + `{"ok":false,"error":"SageBridge est injoignable sur ce PC."}`.
    Timeout 150 s (SageBridge borne Sage à 120 s en interne).
- Icône bleue "S" dans la zone de notification (pystray + icône générée Pillow)
- Fenêtre Paramètres Tkinter (clic sur icône) : nom entreprise, port, dossier
  d'export, autostart Windows, IP locales affichées + section mise à jour
- Autostart via clé registre `HKCU\...\Run\ScannerListener`
- **Single-instance** : socket loopback sur port fixe `54327`. 2e lancement de
  l'exe (double-clic bureau, menu démarrer…) envoie "SHOW" et quitte → la 1re
  instance affiche sa fenêtre Paramètres
- **Mise à jour auto depuis GitHub Releases** : check 4s après démarrage, si
  nouvelle version → télécharge le ZIP + extrait + supprime MOTW + planifie
  l'auto-apply dans 3s (sans clic). Script bat durci (poll kill process,
  xcopy du dossier, logs dans `%TEMP%\ScannerListener-apply-update.log`).
  Au premier démarrage post-update, la fenêtre Paramètres s'ouvre automatique-
  ment pour que l'utilisateur voie la nouvelle version.

### Décisions importantes pc-listener
- **PyInstaller `--onedir`** (pas `--onefile`) : évite les crashs "Failed to
  load Python DLL" causés par Windows Defender qui interrompt l'extraction
  temp `_MEIxxxxxx` du mode onefile. L'exe est un petit launcher dans un
  dossier avec `_internal/` à côté. Launch instantané, zéro conflit AV.
- **Updater télécharge un ZIP, pas un .exe** : conséquence de `--onedir`, il
  faut remplacer un dossier entier, pas juste un fichier.
- **Mark-of-the-Web supprimé** côté Python juste après `urlretrieve` (via
  suppression du stream NTFS `:Zone.Identifier`). Ceinture et bretelles aussi
  dans le script bat (`Unblock-File`).
- **Installeur Inno Setup** publié en plus du ZIP → enregistre l'app dans
  "Programmes et fonctionnalités" (Settings → Apps). Désinstallation vire tout :
  `%LOCALAPPDATA%\ScannerListener`, `%APPDATA%\ScannerListener`, autostart,
  raccourcis bureau/menu. `[Code] CurUninstallStepChanged` kill le process
  avant la suppression.
- **Pas de signature code** : certificat Authenticode ~200€/an pas acheté. Les
  premiers utilisateurs cliquent "Informations complémentaires → Exécuter
  quand même" dans SmartScreen une seule fois.

### Publier une nouvelle version
```bash
# Modifier le code dans pc-listener/listener.py
git add .
git commit -m "fix: ..."
git push

# Taguer la version → déclenche le workflow CI
git tag pc-listener-v1.0.X
git push origin pc-listener-v1.0.X
```
~3 minutes plus tard, la Release GitHub est publiée avec les 2 assets
(`ScannerListener-X.Y.Z.zip` + `ScannerListener-Setup-X.Y.Z.exe`). Toutes les
installations existantes se mettent à jour automatiquement au prochain démarrage
(ou au check 4s après le lancement si déjà ouverte).

### Développement sans build
```powershell
cd pc-listener
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python listener.py
```
L'updater est désactivé en dev (`APP_VERSION = "0.0.0-dev"` + pas frozen par
PyInstaller). Logs dans `%APPDATA%\ScannerListener\listener.log`.

## Fichiers de référence

- `README.md` — install + préparation du RD50T + dépannage scanner/imprimante
- `app/src/main/java/com/belijolie/scanner/printer/EscPosPrinter.kt` — layout ticket
- `app/src/main/java/com/belijolie/scanner/printer/TicketImage.kt` — bitmaps QR
- `app/src/main/java/com/belijolie/scanner/export/ChannelExporter.kt` — export
  CSV / JSON / Sage 50
- `app/src/main/java/com/belijolie/scanner/ui/keyboard/AppKeyboard.kt` — clavier
  custom et toute la mécanique IME / sélection / long-press
- `app/src/main/java/com/belijolie/scanner/data/SettingsStore.kt` — paramètres
  (adresse, IP imprimante, QR Microstore, URL site, URL Avis Google, URL export
  PC, code article Sage manuel, action+extra scanner — ces 2 derniers plus
  éditables depuis l'UI)
- `app/src/main/java/com/belijolie/scanner/printer/TicketLang.kt` — enum FR/EN
  + libellés localisés + helper de résolution du nom de ligne
- `app/src/main/java/com/belijolie/scanner/util/CategoryTranslator.kt` —
  dictionnaire FR→EN bijouterie pour pré-remplir `Category.nameEn`
- `app/src/main/res/values/strings.xml` — textes UI bilingues permanents
  (Français / 中文)
- `app/src/main/java/com/belijolie/scanner/update/UpdateManager.kt` — check
  GitHub Releases + download + install via FileProvider
- `app/src/main/java/com/belijolie/scanner/update/UpdateDialog.kt` — UI
  Compose unique pour les 4 états visibles de la MAJ
- `pc-listener/listener.py` — toute l'app PC (serveur + tray + updater + single-
  instance)
- `.github/workflows/pc-listener-release.yml` — CI build + release pc-listener
- `.github/workflows/android-release.yml` — CI build release APK signé +
  publish GitHub Release (déclenché par tag `app-v*`)

## Décisions importantes (app Android)

- **Clavier maison en Dialog plein écran** (pas `ModalBottomSheet`) → ancré en bas du
  vrai écran même quand il est appelé depuis un autre `AlertDialog` imbriqué.
- **IME système verrouillée** par `readOnly=true` sur le `BasicTextField` du
  `KeyboardDialog`. Historiquement on utilisait `readOnly=false` + flag fenêtre
  `SOFT_INPUT_STATE_ALWAYS_HIDDEN`, mais ça ne suffisait pas quand le
  KeyboardDialog est empilé par-dessus un AlertDialog (clavier Android qui
  réapparaissait au focus). En `readOnly=true`, Compose n'ouvre jamais l'IME,
  et le curseur bleu + poignées de sélection + menu natif "Tout sélectionner /
  Copier" restent fonctionnels. La saisie passe par nos touches custom qui
  appellent `onChange(newText)` directement. Le flag `SOFT_INPUT_STATE_ALWAYS_HIDDEN`
  reste comme garde-fou.
- **`pointerInput(detectTapGestures)` pour l'overlay noir dismiss**, PAS
  `clickable` (qui volerait le focus au TextField → casse la sélection).
- **Backspace long-press** : capture `onDelete` via `rememberUpdatedState` pour
  éviter les stale closures (la 1re version appelait l'ancien état après chaque
  tick de la boucle → impossible de supprimer plus d'un caractère).
- **Scanner action découverte via `dumpsys`** sur le device réel, pas devinée.
  Les champs `scannerAction` / `scannerExtra` ne sont plus éditables depuis l'UI
  mais restent persistés dans DataStore (défauts suffisants pour tous les clients).
- `dismissOnClickOutside = false` sur le modal "produit inconnu" + garde dans
  `handleScan` pour ignorer les scans quand le modal est déjà ouvert → résolu le bug
  "ouvre et ferme aussitôt" causé par des touches parasites du wedge.
- **`handleScan` n'auto-crée plus de canal** : si `activeChannelId == null`
  (= on est sur la liste des canaux, pas dans un détail) le scan est ignoré.
  Volontaire : l'utilisateur passe forcément par la liste → "Créer un canal"
  → détail. Un scan hors contexte ne doit alimenter aucun panier.
- **Remise de canal uniquement affichage + ticket** : `Channel.discountPercent`
  (0-100) touche l'écran de caisse (prix barrés + ligne "Remise -X%" verte)
  et le ticket imprimé (3 lignes totaux : HT brut, remise, HT remisé en gras ;
  TVA et TTC recalculés sur HT remisé). L'export CSV / JSON / Sage reste au
  prix brut (décision produit : la remise est une faveur à la caisse, pas une
  réalité comptable). Modifiable via le bouton "%" dans la TotalBar (dialog
  NUMBER 0-100). Le bouton passe en vert et affiche le % quand une remise est
  active ; sinon violet neutre.
- **Export Sage 50 → création directe de facture via SageBridge** : le bouton
  "Sage 50" ne génère plus un CSV à importer dans Sage ; il crée directement
  une **facture provisoire** dans Sage 50 par l'interface COM. Chemin :
  PDA → pc-listener (LAN) → SageBridge (loopback 127.0.0.1:8765) → Sage COM.
  Payload JSON : `{ site, orderNo=channel.id, client="003", remarque=channel.name,
  force=false, simulation=false, lines=[{code, qty, puht, tva=20, description}] }`.
  Le code client est en dur à "003" (client par défaut Beli & Jolie dans le
  dossier Princesse). `orderNo = channel.id` déclenche l'anti-doublon natif
  de SageBridge (reclique → HTTP 409, le PDA affiche "déjà créée"). Description
  = nom de catégorie en MAJUSCULES, sans code-barres (choix produit : le barcode
  n'a rien à faire sur la facture Sage). Codes articles toujours pilotés par
  `Category.sageArticleCode` + `settings.sageManualArticleCode` (fallback produit
  manuel). Validation bloquante dans `ChannelExporter.validateSage()` avant
  l'appel réseau. Le ViewModel retourne un `ExportSuccess.SageInvoice(numero)`
  → "Facture Sage créée : n° XXX". Prérequis côté PC Boris : SageBridge
  installé + ouvert + autorisation d'accès COM activée dans Sage (voir
  `C:\Users\Admin\Desktop\SageBridge\CLAUDE.md`).
- **Impression bilingue (FR / EN)** : paramètre `TicketLang` sur
  `EscPosPrinter.printReceipt`, choisi dans `PrintConfirmDialog` juste avant
  d'envoyer. `TicketLabels.FR` / `TicketLabels.EN` concentrent tous les libellés
  statiques du ticket. Le nom affiché par ligne suit la cascade
  `resolveLineDisplayName` : si `lang=EN` → `line.nameEn` → `line.categoryNameEn`
  → fallback sur `line.categoryName`. `CategoryTranslator` pré-remplit
  `Category.nameEn` à la création depuis un dictionnaire FR→EN bijouterie
  (toujours modifiable à la main).
- **UI bilingue permanente (Français / 中文)** : chaque string de
  `res/values/strings.xml` contient directement les 2 langues séparées par
  ` / ` (ou `\n` quand il faut empiler verticalement). Pas de switch, pas de
  `values-zh/`. Décision prise après avoir testé un vrai switcher de locale
  (`LocaleHelper` + `attachBaseContext`) qui a été retiré au profit de ce
  format bilingue permanent. Pour les messages produits côté ViewModel qui
  sortent par snackbar, utiliser `context.getString(R.string.*)` depuis
  `ScannerApp.instance.applicationContext`.
- **Logique dynamique QR en bas de ticket** : 1/2/3 QR configurés → layouts
  différents (voir "Conventions produit & ticket"). La fonction unique
  `TicketImage.QrEntry` + les 3 compose* uniformisent le rendu. Attention à
  garder `EscPosPrinter.printReceipt` et `TicketPreviewSheet` synchrones :
  même logique de sélection des entries et de choix du bitmap.
- **Erreurs réseau humanisées** via `Throwable.toHumanMessage(context)` qui
  détecte en plus si le Wi-Fi est tombé via `ConnectivityManager` → message
  explicite "📶 Wi-Fi non connecté" au lieu de "ConnectException" opaque.
- **Migration Room destructive** (`fallbackToDestructiveMigration`) car app
  jeune, catalogue reconstructible à la main. Dernière bump : v4 → v5 lors
  de l'ajout de `Category.nameEn`, `Category.sageArticleCode`, `Product.nameEn`.
  Si tu bumpes à v6+ avec un schéma plus mature, remplace par de vraies
  migrations.
- **Signature release + auto-update via GitHub Releases** : keystore unique
  `app/belijolie-release.jks` (validité 10000 jours), credentials dans
  `keystore.properties` (git-ignored). `build.gradle.kts` lit la config via
  `resolveProp` : d'abord `-P...` (CI), puis `keystore.properties`, puis env
  vars. Fallback automatique sur signature debug si keystore absent (dev
  local). versionCode/versionName paramétrables via `-PversionName=X.Y.Z
  -PversionCode=NNNNN` ; en CI le workflow calcule `code = major*10000 +
  minor*100 + patch`. L'auto-update filtre les releases GitHub par préfixe
  de tag `app-v` (le repo est partagé avec pc-listener donc `/releases/latest`
  seul n'est pas fiable). Install via `FileProvider` + `ACTION_VIEW` sur
  l'APK stocké dans `cacheDir/updates/` — Android oblige à passer par son
  prompt système d'install, pas de MAJ silencieuse sans Device Owner.
  `REQUEST_INSTALL_PACKAGES` déclaré au manifest + `canRequestPackageInstalls()`
  check qui envoie l'utilisateur dans `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`
  la première fois.
