# Roboco Windows installer -- the curl|sh equivalent.
#
# One-liner:
#   irm https://github.com/hoangvu12/roboco/releases/latest/download/install.ps1 | iex
#
# Downloads the latest release, verifies it against the release manifest, and
# atomically swaps it into %LOCALAPPDATA%\Programs\Roboco (param-overridable
# when run as a file), then creates a Start Menu shortcut. Roboco's user data
# lives in %LOCALAPPDATA%\Roboco and is never touched. The installed app
# self-updates from then on (the strip's one-click swap).
#
# Runs under Windows PowerShell 5.1 and PowerShell 7+. ASCII only on purpose:
# PowerShell 5.1 reads a BOM-less .ps1 in the system codepage.

param(
    [string]$InstallDir = "$env:LOCALAPPDATA\Programs\Roboco"
)

$ErrorActionPreference = 'Stop'
# `irm | iex` runs under Windows PowerShell 5.1 by default: pin TLS 1.2
# (GitHub rejects older) and silence the progress bar that otherwise
# throttles Invoke-WebRequest downloads.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

$releases = 'https://github.com/hoangvu12/roboco/releases/latest/download'
$dataDir = "$env:LOCALAPPDATA\Roboco"
$target = $InstallDir.TrimEnd('\', '/')
# Guard the data dir: refuse any target that would cover (or be) it -- this
# also rejects drive roots and %LOCALAPPDATA% itself.
if ($dataDir.StartsWith($target, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing: $target would cover Roboco's data dir ($dataDir)."
}
$parent = Split-Path $target -Parent
$leaf = Split-Path $target -Leaf
if (-not $parent -or -not $leaf) { throw "Invalid install dir: $InstallDir" }

$temp = $null
$stage = $null
$succeeded = $false
try {
    Write-Host 'Fetching the latest release...'
    $manifest = Invoke-RestMethod "$releases/manifest.json"
    $version = [string]$manifest.version
    if (-not $version) { throw 'manifest.json has no version.' }
    $zip = "roboco-$version-windows-x86_64.zip"
    $expected = $manifest.files.$zip.sha256
    if (-not $expected) { throw "manifest.json has no SHA-256 for $zip." }

    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temp = Join-Path ([IO.Path]::GetTempPath()) "roboco-install-$PID"
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -Recurse -Force -LiteralPath $temp
    }
    New-Item -ItemType Directory -Path $temp | Out-Null
    $zipPath = Join-Path $temp $zip
    Write-Host "Downloading $zip..."
    Invoke-WebRequest "$releases/$zip" -OutFile $zipPath -UseBasicParsing
    $actual = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
    if (-not $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Checksum mismatch for $zip (expected $expected, got $actual)."
    }

    # Stage beside the target (same volume) so the swap is two renames -- no
    # window in which the install dir is missing, and a failed move restores.
    $stage = Join-Path $parent ".roboco-install-$PID"
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -Recurse -Force -LiteralPath $stage
    }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $stage
    $entries = @(Get-ChildItem -LiteralPath $stage)
    if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) {
        # The zip's root holds the files directly (package-windows.ps1); a
        # single top-level directory is handled defensively by flattening it,
        # so the install layout is normalized either way.
        $inner = $entries[0].FullName
        Get-ChildItem -LiteralPath $inner | Move-Item -Destination $stage
        Remove-Item -LiteralPath $inner
    }
    $stagedExe = Join-Path $stage 'roboco.exe'
    if (-not (Test-Path -LiteralPath $stagedExe)) {
        throw 'The archive did not contain roboco.exe.'
    }

    # Version probe (same contract as scripts/package-windows.ps1): proves the
    # payload runs on this machine and matches the manifest before anything
    # existing is touched.
    $probe = [Diagnostics.ProcessStartInfo]::new()
    $probe.FileName = $stagedExe
    $probe.Arguments = '--version'
    $probe.UseShellExecute = $false
    $probe.CreateNoWindow = $true
    $probe.RedirectStandardOutput = $true
    $probe.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($probe)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill()
            throw 'Downloaded executable version probe timed out.'
        }
        $probeOutput = $stdout.Result.Trim()
        if ($process.ExitCode -ne 0 -or $probeOutput -ne "roboco $version") {
            throw "Downloaded executable cannot run or has the wrong version (exit $($process.ExitCode)): $probeOutput $($stderr.Result)"
        }
    } finally { $process.Dispose() }

    # Never swap under a running install from the target dir -- the old image
    # could not be deleted afterwards, leaving a half-finished state.
    $running = @(Get-Process -Name roboco -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Path -and $_.Path.StartsWith($target, [StringComparison]::OrdinalIgnoreCase)
        })
    if ($running.Count -gt 0) {
        throw "Roboco is running from $target -- quit it before installing."
    }

    if (Test-Path -LiteralPath $target) {
        $old = Join-Path $parent ".roboco-old-$PID"
        if (Test-Path -LiteralPath $old) {
            Remove-Item -Recurse -Force -LiteralPath $old
        }
        Rename-Item -LiteralPath $target -NewName (Split-Path $old -Leaf)
        try {
            Rename-Item -LiteralPath $stage -NewName $leaf
        } catch {
            # The installation must not be left missing: put the old one back.
            Rename-Item -LiteralPath $old -NewName $leaf
            throw
        }
        try {
            Remove-Item -Recurse -Force -LiteralPath $old
        } catch {
            Write-Warning "Installed, but could not remove the previous install: $_"
        }
    } else {
        Rename-Item -LiteralPath $stage -NewName $leaf
    }
    $stage = $null  # renamed into place; nothing to clean up

    # Sweep leftovers from an interrupted earlier run (only after a successful
    # swap, when a concurrent installer is no longer plausible).
    Get-ChildItem -LiteralPath $parent -Filter '.roboco-*' -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "\.$PID$" } |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

    # Start Menu shortcut; the exe embeds its icon (dist/windows/roboco.rc).
    $shortcutPath = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Roboco.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = (Join-Path $target 'roboco.exe')
    $shortcut.WorkingDirectory = $target
    $shortcut.Description = 'Roboco'
    $shortcut.Save()

    $succeeded = $true
    Write-Host "Installed Roboco $version to $target."
    Write-Host 'Start it from the Start Menu; it updates itself from there on.'
    Write-Host "Your data lives in $dataDir and was not touched."
} finally {
    if ($temp -and (Test-Path -LiteralPath $temp)) {
        Remove-Item -Recurse -Force -LiteralPath $temp -ErrorAction SilentlyContinue
    }
    if (-not $succeeded -and $stage -and (Test-Path -LiteralPath $stage)) {
        # Leave nothing half-staged on failure.
        Remove-Item -Recurse -Force -LiteralPath $stage -ErrorAction SilentlyContinue
    }
}
