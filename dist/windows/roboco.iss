; Roboco for Windows — per-user installer (Inno Setup 6).
;
; Built by scripts/package-windows.ps1, which passes the version, the package
; architecture, and the staged portable directory:
;   ISCC.exe /DAppVersion=<version> /DArch=x86_64 /DPackageDir=<stage> /DOutputDir=<out> roboco.iss
;
; Installs into %LOCALAPPDATA%\Programs\Roboco without elevation, like VS
; Code's user setup: the directory stays writable by its user, so the in-app
; updater (crates/update/src/windows.rs) can replace roboco.exe in place. The
; staged directory already carries roboco-update.json, which marks the install
; as update-managed. Re-running a newer installer upgrades in place; user data
; lives in %LOCALAPPDATA%\Roboco and is never touched here.

#ifndef AppVersion
  #error AppVersion must be defined (/DAppVersion=x.y.z)
#endif
#ifndef Arch
  #error Arch must be defined (/DArch=x86_64 or /DArch=aarch64)
#endif
#ifndef PackageDir
  #error PackageDir must be defined (/DPackageDir=<staged package directory>)
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

#if Arch == "aarch64"
  #define ArchAllowed "arm64"
#else
  #define ArchAllowed "x64compatible"
#endif

[Setup]
; Never change AppId: it identifies the installation across upgrades, and
; crates/update/src/windows.rs refreshes DisplayVersion under this key after
; in-app updates. This GUID is Roboco's own, deliberately not upstream
; zeron's — installations must not be shared between the two products.
AppId={{22BA167B-E6D5-45C5-BA8B-7569B44F6065}
AppName=Roboco
AppVersion={#AppVersion}
AppVerName=Roboco {#AppVersion}
AppPublisher=Roboco
AppPublisherURL=https://github.com/hoangvu12/roboco
AppSupportURL=https://github.com/hoangvu12/roboco/issues
AppUpdatesURL=https://github.com/hoangvu12/roboco/releases
VersionInfoVersion={#AppVersion}
PrivilegesRequired=lowest
DefaultDirName={autopf}\Roboco
DisableProgramGroupPage=yes
DisableDirPage=auto
DisableReadyPage=yes
ArchitecturesAllowed={#ArchAllowed}
ArchitecturesInstallIn64BitMode={#ArchAllowed}
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=roboco-{#AppVersion}-windows-{#Arch}-setup
SetupIconFile=roboco.ico
UninstallDisplayIcon={app}\roboco.exe
UninstallDisplayName=Roboco
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
; A running Roboco is closed through the Restart Manager before its files are
; replaced; the updated app starts again from the finish page.
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#PackageDir}\roboco.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PackageDir}\roboco-tailcat.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PackageDir}\roboco-update.json"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PackageDir}\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PackageDir}\THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PackageDir}\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Roboco"; Filename: "{app}\roboco.exe"
Name: "{autodesktop}\Roboco"; Filename: "{app}\roboco.exe"; Tasks: desktopicon

[Registry]
; roboco:// conversation links — the scheme macOS registers in Info.plist and
; Linux in roboco.desktop.
Root: HKCU; Subkey: "Software\Classes\roboco"; ValueType: string; ValueName: ""; ValueData: "URL:Roboco"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\roboco"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKCU; Subkey: "Software\Classes\roboco\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\roboco.exe"",0"
Root: HKCU; Subkey: "Software\Classes\roboco\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\roboco.exe"" ""%1"""

[Run]
Filename: "{app}\roboco.exe"; Description: "{cm:LaunchProgram,Roboco}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Leftovers of in-app updates (crates/update/src/windows.rs).
Type: files; Name: "{app}\roboco.exe.old"
Type: files; Name: "{app}\.roboco-update-incoming.exe"
Type: filesandordirs; Name: "{app}\.roboco-update-*"
