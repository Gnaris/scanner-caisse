# Build local de l'executable Windows (+ installeur Inno Setup optionnel).
# Compatible Windows PowerShell 5.1 et PowerShell 7+.
#
# Prerequis :
#   - Python 3.10+ sur le PATH
#   - Inno Setup 6 (facultatif, sinon utiliser -NoInstaller)
#
# Utilisation :
#   .\build.ps1                    # build exe + installeur dans dist\Installer
#   .\build.ps1 -NoInstaller       # juste l'exe
#   .\build.ps1 -Version "1.2.3"   # injecte la version dans l'exe

param(
    [switch]$NoInstaller,
    [string]$IsccPath = "",
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if ($Version) {
    Write-Host "== 0/3  Injection de la version $Version =="
    $content = Get-Content listener.py -Raw
    $content = $content -replace 'APP_VERSION = ".*"', "APP_VERSION = `"$Version`""
    Set-Content -Path listener.py -Value $content -Encoding UTF8 -NoNewline
}

Write-Host "== 1/3  Preparation du venv =="
if (-not (Test-Path ".venv")) {
    python -m venv .venv
}
. .\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip | Out-Null
python -m pip install -r requirements.txt pyinstaller | Out-Null

Write-Host "== 2/3  PyInstaller =="
pyinstaller `
    --noconfirm `
    --onefile `
    --windowed `
    --name "ScannerListener" `
    --hidden-import "PIL._tkinter_finder" `
    listener.py

$exe = Join-Path $here "dist\ScannerListener.exe"
if (-not (Test-Path $exe)) {
    throw "L'executable n'a pas ete produit."
}
Write-Host "OK -> $exe"

if ($NoInstaller) { exit 0 }

Write-Host "== 3/3  Inno Setup =="
$iscc = $IsccPath
if (-not $iscc) {
    $cmd = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($cmd) { $iscc = $cmd.Source }
}
if (-not $iscc -or -not (Test-Path $iscc)) {
    foreach ($c in @(
        "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        "C:\Program Files\Inno Setup 6\ISCC.exe"
    )) {
        if (Test-Path $c) { $iscc = $c; break }
    }
}
if (-not $iscc) {
    Write-Warning "ISCC.exe introuvable - installeur non genere."
    exit 0
}
& $iscc "installer.iss"
Write-Host ""
Write-Host "Termine. L'installeur est dans dist\Installer\"
