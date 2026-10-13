; Inno Setup — Scanner Listener
;
; Installe ScannerListener.exe dans %LOCALAPPDATA%\ScannerListener (pas d'admin
; requis), crée une entrée de démarrage automatique optionnelle, et enregistre
; l'app dans "Programmes et fonctionnalités" pour une désinstallation propre.

; Permet au workflow CI d'overrider la version via /DMyAppVersion=1.2.3
#ifndef MyAppVersion
  #define MyAppVersion "0.0.0-dev"
#endif

#define MyAppName       "Scanner Listener"
#define MyAppShortName  "ScannerListener"
#define MyAppPublisher  "Scanner Caisse"
#define MyAppExe        "ScannerListener.exe"

[Setup]
AppId={{B5A2C1E6-7D4F-4A82-9F3B-7A1C6B9D4E80}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
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
UninstallDisplayIcon={app}\{#MyAppExe}
UninstallDisplayName={#MyAppName}
ArchitecturesInstallIn64BitMode=x64
; Ferme automatiquement l'app pendant l'install/désinstall pour éviter
; "fichier verrouillé".
CloseApplications=yes
CloseApplicationsFilter=*.exe
RestartApplications=no

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "autostart"; Description: "Démarrer automatiquement avec Windows"; GroupDescription: "Options :"

[Files]
Source: "dist\ScannerListener.exe"; DestDir: "{app}"; DestName: "{#MyAppExe}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"
Name: "{group}\Désinstaller {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "{#MyAppShortName}"; \
    ValueData: """{app}\{#MyAppExe}"""; \
    Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "Lancer {#MyAppName}"; \
    Flags: nowait postinstall skipifsilent

; À la désinstallation : supprime la config utilisateur (settings, logs) ET
; le dossier d'install. L'utilisateur peut tout virer en un clic depuis
; "Paramètres > Applications > Scanner Listener > Désinstaller".
[UninstallDelete]
Type: filesandordirs; Name: "{userappdata}\{#MyAppShortName}"
Type: filesandordirs; Name: "{app}"

; Avant la désinstallation, kill tout process restant au cas où
; CloseApplications ait raté (ex. app lancée depuis un autre chemin).
[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
begin
  if CurUninstallStep = usUninstall then
  begin
    Exec(ExpandConstant('{sys}\taskkill.exe'), '/im {#MyAppExe} /f',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(500);
  end;
end;
