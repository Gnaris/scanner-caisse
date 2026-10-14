# Build + sign + publish une release Android en local (~1 min au lieu de ~4 min CI).
#
# Usage :
#   .\release-local.ps1 1.0.10
#
# Le script :
#   1. Verifie que la working tree est clean
#   2. Push le code sur origin/main
#   3. Build l'APK release signe avec le keystore local
#   4. Cree la Release GitHub + le tag (via gh release create)
#
# NB : le workflow CI va AUSSI se declencher sur le push du tag et re-builder
# le meme APK en parallele (~4 min). C'est inoffensif, softprops/action-gh-release
# ecrase le meme binaire. Ignore juste la run CI, ta release est deja en ligne.

param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Version
)

$ErrorActionPreference = "Stop"

if (-not ($Version -match '^\d+\.\d+\.\d+$')) {
    Write-Host "ERREUR : la version doit etre au format X.Y.Z (ex : 1.0.10)" -ForegroundColor Red
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

# Verifie que le tag n'existe pas deja
$existingTag = git tag -l $Tag
if ($existingTag) {
    Write-Host "ERREUR : le tag $Tag existe deja en local. Utilise un numero different." -ForegroundColor Red
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
    Write-Host "ERREUR : build Gradle echoue" -ForegroundColor Red
    exit 1
}

# 4. Rename APK
$ApkDst = "scanner-caisse-$Version.apk"
if (Test-Path $ApkDst) { Remove-Item $ApkDst -Force }
Copy-Item "app\build\outputs\apk\release\app-release.apk" $ApkDst
$sizeMB = [math]::Round((Get-Item $ApkDst).Length / 1MB, 1)
Write-Host "APK pret : $ApkDst ($sizeMB Mo)" -ForegroundColor Green

# 5. Create GitHub Release
Write-Host ""
Write-Host "[3/4] Creation de la Release GitHub..." -ForegroundColor Yellow
gh release create $Tag $ApkDst `
    --title "Scanner Caisse v$Version" `
    --generate-notes `
    --target main
if ($LASTEXITCODE -ne 0) {
    Remove-Item $ApkDst -Force
    Write-Host "ERREUR : creation de la Release echouee" -ForegroundColor Red
    exit 1
}

# 6. Sync local tag + cleanup
Write-Host ""
Write-Host "[4/4] Recuperation du tag local..." -ForegroundColor Yellow
try {
    $env:GIT_TERMINAL_PROMPT = "0"
    & git fetch origin --tags 2>$null
} catch {
    Write-Host "  (fetch local echoue - pas critique, le tag est sur GitHub)" -ForegroundColor DarkGray
}

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
