; Inno Setup — Scanner Listener
;
; Installe ScannerListener.exe dans %LOCALAPPDATA%\ScannerListener (pas d'admin
; requis) et crée une entrée de démarrage automatique optionnelle.

#define MyAppName       "Scanner Listener"
#define MyAppShortName  "ScannerListener"
#define MyAppVersion    "1.0.0"
#define MyAppPublisher  "Scanner Caisse"

[Setup]
AppId={{B5A2C1E6-7D4F-4A82-9F3B-7A1C6B9D4E80}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\{#MyAppShortName}
DefaultGroupName={#MyAppName}
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=dist\Installer
OutputBaseFilename=ScannerListener-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\ScannerListener.exe
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "autostart"; Description: "Démarrer automatiquement avec Windows"; GroupDescription: "Options :"

[Files]
Source: "dist\ScannerListener.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\ScannerListener.exe"
Name: "{group}\Désinstaller {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\ScannerListener.exe"; Flags: createonlyiffileexists

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "{#MyAppShortName}"; \
    ValueData: """{app}\ScannerListener.exe"""; \
    Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\ScannerListener.exe"; Description: "Lancer {#MyAppName}"; \
    Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Garde la config utilisateur par défaut. Décommente pour tout effacer :
; Type: filesandordirs; Name: "{userappdata}\{#MyAppShortName}"
