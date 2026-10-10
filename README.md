# Scanner Caisse — Beli & Jolie

Application Android pour PDA Vanquisher RD50T (ou équivalent avec moteur Zebra SE4710).
Scan de produits, panier multi-canaux, impression de ticket Wi-Fi vers une imprimante
Munbyn ITPP047 (ESC/POS, 80 mm).

## Stack

- **Kotlin + Jetpack Compose** (UI)
- **Room** (base SQLite locale pour catégories & produits)
- **DataStore** (paramètres + sauvegarde des paniers en cours)
- **DantSu ESCPOS-ThermalPrinter-Android** (impression ESC/POS en TCP)
- **BroadcastReceiver** pour capter le scan matériel

## Ouvrir le projet

1. Ouvrir **Android Studio** (déjà installé sur ce poste).
2. **File → Open** → choisir `C:\Users\Admin\Desktop\scanner`.
3. Attendre la synchro Gradle (la première fois, télécharge les dépendances → quelques minutes).
4. Dès que la synchro est verte, le projet est prêt.

## Préparer le RD50T pour le déploiement USB

1. Sur le RD50T : **Paramètres → À propos du téléphone** → tape 7 fois sur « Numéro de build »
   pour activer les options développeur.
2. **Paramètres → Options développeur** → active **« Débogage USB »**.
3. Branche le RD50T au PC via USB. Accepte le fingerprint RSA sur le PDA.
4. Vérifie que le PC voit l'appareil :
   ```
   C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe devices
   ```
   Tu dois voir une ligne avec le numéro de série du RD50T + `device`.

## Lancer l'app sur le PDA

Dans Android Studio :

1. En haut, sélectionne le RD50T dans la liste des devices.
2. Clique sur ▶ **Run 'app'**.
3. L'APK se construit, s'installe et se lance automatiquement.

## Première utilisation

1. **Paramètres** → vérifie :
   - Nom du commerce (affiché en tête de ticket)
   - IP imprimante : `192.168.1.31`
   - Port : `9100`
   - Action broadcast scanner : `com.scanner.broadcast` (voir « Dépannage scanner » plus bas)
2. **Catégories** → ajoute au moins une catégorie (ex. « Boissons », « Snacks »).
3. **Produits** → ajoute un produit : scanne le code-barres, choisis la catégorie, saisis le prix.
4. **Caisse** → scanne un produit → il apparaît dans le panier.
   - Scan répété = quantité +1
   - Bouton **« + Canal »** → ouvre un panier parallèle pour un autre client
   - Appui long sur un onglet → renommer
   - **« Imprimer le ticket »** → envoie le ticket à l'imprimante en TCP

## Dépannage scanner (si le scan ne fonctionne pas)

Le firmware Vanquisher broadcast habituellement :
- Action = `com.scanner.broadcast`
- Extra = `data`

Si rien ne remonte dans l'app quand tu appuies sur le bouton latéral :

### Méthode 1 — Vérifier le broadcast du firmware

Sur le RD50T, cherche l'app pré-installée **« ScanSetting »** ou **« Scanner »** (nom varie).
Ouvre-la, et regarde le mode de sortie :
- **« Broadcast mode »** / **« Intent mode »** → bon mode, note l'action et l'extra.
- **« Keyboard emulation »** → change pour « Broadcast ».

### Méthode 2 — Lire les logs ADB pendant un scan

Branche le PDA en USB au PC, puis dans un terminal :
```
C:\Users\Admin\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s ScanReceiver:D
```
Appuie sur le bouton de scan. Si rien n'apparaît, c'est que le firmware n'envoie pas
sur l'action configurée. Essaie dans **Paramètres → Action broadcast** une des valeurs
suivantes :
- `com.scanner.broadcast`
- `android.intent.ACTION_DECODE_DATA`
- `nlscan.action.SCANNER_RESULT`
- `scan.rcv.message`

Et change l'extra parmi : `data`, `barcode`, `barcode_string`, `SCAN_BARCODE1`.

## Dépannage imprimante

- Vérifie que le PDA est connecté au **même Wi-Fi** que l'imprimante.
- Depuis le PC, teste la connectivité : `ping 192.168.1.31`
- Dans les **Paramètres** de l'app, utilise le bouton **« Test imprimante »**.
- Si l'imprimante ne répond pas : regarde l'IP qu'elle a réellement
  (bouton **FEED** maintenu au démarrage → ticket d'autotest avec IP).

## Structure du code

```
app/src/main/java/com/belijolie/scanner/
├── MainActivity.kt              Scaffold + bottom nav
├── ScannerApp.kt                Application + singletons
├── data/                        Room DB + DataStore settings
│   ├── AppDatabase.kt
│   ├── SettingsStore.kt
│   ├── dao/                     CategoryDao, ProductDao
│   └── entity/                  Category, Product, ProductWithCategory
├── scanner/                     Scan broadcast + event bus
│   ├── ScanReceiver.kt
│   └── ScanEventBus.kt
├── printer/                     ESC/POS TCP wrapper
│   └── EscPosPrinter.kt
├── cart/                        Multi-canaux persistés
│   ├── Channel.kt
│   └── ChannelsRepository.kt
└── ui/
    ├── Nav.kt                   Destinations
    ├── theme/                   Theme + Color
    ├── checkout/                CheckoutScreen + ViewModel
    ├── products/                ProductsScreen + ViewModel
    ├── categories/              CategoriesScreen + ViewModel
    └── settings/                SettingsScreen + ViewModel
```
