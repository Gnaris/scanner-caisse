# Build + sign + publish une release Android en local (~1 min au lieu de ~4 min CI).
#
# Usage :
#   .\release-local.ps1 1.0.10
#
# Le script :
#   1. Vérifie que la working tree est clean
#   2. Push le code sur origin/main
#   3. Build l'APK release signé avec le keystore local
#   4. Crée la Release GitHub + le tag (via gh release create)
#
# NB : le workflow CI va AUSSI se déclencher sur le push du tag et re-builder
# le même APK en parallèle (~4 min). C'est inoffensif — softprops/action-gh-release
# écrase le même binaire. Ignore juste la run CI, ta release est déjà en ligne.

param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Version
)

$ErrorActionPreference = "Stop"

if (-not ($Version -match '^\d+\.\d+\.\d+$')) {
    Write-Host "ERREUR : la version doit être au format X.Y.Z (ex : 1.0.10)" -ForegroundColor Red
    exit 1
}

$Tag = "app-v$Version"
$parts = $Version -split '\.'
$VersionCode = ([int]$parts[0]) * 10000 + ([int]$parts[1]) * 100 + ([int]$parts[2])

Write-Host ""
Write-Host "=== Scanner Caisse - Release $Tag ===" -ForegroundColor Cyan
Write-Host "versionCode=$VersionCode"
Write-Host ""

# 1. Working tree clean ?
$status = git status --porcelain
if ($status) {
    Write-Host "ERREUR : working tree non clean. Commit ou stash d'abord :" -ForegroundColor Red
    Write-Host $status
    exit 1
}

# Vérifie que le tag n'existe pas déjà
$existingTag = git tag -l $Tag
if ($existingTag) {
    Write-Host "ERREUR : le tag $Tag existe déjà en local. Utilise un numéro différent." -ForegroundColor Red
    exit 1
}

# 2. Push code
Write-Host "[1/4] Push du code sur origin/main..." -ForegroundColor Yellow
git push origin main
if ($LASTEXITCODE -ne 0) { exit 1 }

# 3. Build signed release
Write-Host ""
Write-Host "[2/4] Build release APK (version=$Version, versionCode=$VersionCode)..." -ForegroundColor Yellow
& ".\gradlew.bat" assembleRelease "-PversionName=$Version" "-PversionCode=$VersionCode"
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERREUR : build Gradle échoué" -ForegroundColor Red
    exit 1
}

# 4. Rename APK
$ApkDst = "scanner-caisse-$Version.apk"
if (Test-Path $ApkDst) { Remove-Item $ApkDst -Force }
Copy-Item "app\build\outputs\apk\release\app-release.apk" $ApkDst
$sizeMB = [math]::Round((Get-Item $ApkDst).Length / 1MB, 1)
Write-Host "APK prêt : $ApkDst ($sizeMB Mo)" -ForegroundColor Green

# 5. Create GitHub Release (crée aussi le tag sur origin)
Write-Host ""
Write-Host "[3/4] Création de la Release GitHub..." -ForegroundColor Yellow
gh release create $Tag $ApkDst `
    --title "Scanner Caisse v$Version" `
    --generate-notes `
    --target main
if ($LASTEXITCODE -ne 0) {
    Remove-Item $ApkDst -Force
    Write-Host "ERREUR : création de la Release échouée" -ForegroundColor Red
    exit 1
}

# 6. Sync local tag + cleanup
Write-Host ""
Write-Host "[4/4] Récupération du tag local..." -ForegroundColor Yellow
git fetch origin --tags 2>&1 | Out-Null

Remove-Item $ApkDst -Force

Write-Host ""
Write-Host "TERMINE ! Release en ligne :" -ForegroundColor Green
Write-Host "  https://github.com/Gnaris/scanner-caisse/releases/tag/$Tag" -ForegroundColor Green
Write-Host ""
Write-Host "Les PDA detecteront la MAJ au prochain lancement (ou via le bouton" -ForegroundColor Gray
Write-Host "'Verifier les mises a jour' dans Parametres)." -ForegroundColor Gray
Write-Host ""
Write-Host "NB : le workflow CI va aussi se declencher et rebuilder le meme APK" -ForegroundColor DarkGray
Write-Host "(~4 min, inoffensif, ecrase le meme binaire). Ignore sa notification." -ForegroundColor DarkGray
