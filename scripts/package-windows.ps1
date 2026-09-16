param(
    [Parameter(Mandatory)][string]$ReleasesUrl,
    [switch]$SkipWebBuild
)
$ErrorActionPreference = 'Stop'
if (-not $ReleasesUrl.StartsWith('https://')) { throw 'Release feed must use HTTPS' }
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    # The engine embeds the built web client (rust-embed, staged by
    # crates/engine/build.rs). Build it before cargo so the embed has
    # bytes to bake. Skippable for CI caches that already have a fresh
    # dist, or when ROBOCO_WEB_DIST points at a vendored bundle.
    if (-not $SkipWebBuild) {
        $pnpm = Get-Command pnpm -ErrorAction SilentlyContinue
        if (-not $pnpm) { throw 'pnpm is required to build the embedded web client (or pass -SkipWebBuild and set ROBOCO_WEB_DIST)' }
        corepack enable | Out-Null
        Push-Location (Join-Path $root 'web')
        try {
            & pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw 'pnpm install failed' }
            & pnpm --filter '@roboco/app' run build
            if ($LASTEXITCODE -ne 0) { throw 'web build failed' }
        } finally { Pop-Location }
    }
    cargo build --release --locked -p roboco
    if ($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
    # Normalize to one scalar string: a multi-record capture (console/GUI
    # subsystem quirks) makes -notmatch filter instead of test, leaving
    # $Matches null ("Cannot index into a null array").
    $versionText = (& ./target/release/roboco.exe --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '^roboco (\d+\.\d+\.\d+)$') { throw "Cannot read executable version (captured '$versionText', exit $LASTEXITCODE)" }
    $version = $Matches[1]
    $out = Join-Path $root 'target/package'
    $stage = Join-Path $out "roboco-$version-windows-x86_64"
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Copy-Item -LiteralPath './target/release/roboco.exe' -Destination (Join-Path $stage 'roboco.exe')
    @{ releases_url = $ReleasesUrl } | ConvertTo-Json | Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $stage 'roboco-update.json')
    Copy-Item -LiteralPath 'LICENSE','THIRD_PARTY_NOTICES.md' -Destination $stage
    $licenses = Join-Path $stage 'licenses/fonts'
    New-Item -ItemType Directory -Force -Path $licenses | Out-Null
    Copy-Item -Path 'crates/ui/assets/fonts/licenses/*' -Destination $licenses
    Compress-Archive -Path "$stage/*" -DestinationPath "$stage.zip" -Force
    Copy-Item -LiteralPath './target/release/roboco.exe' -Destination "$stage.exe"
    $file = Split-Path "$stage.exe" -Leaf
    $hash = (Get-FileHash -LiteralPath "$stage.exe" -Algorithm SHA256).Hash.ToLowerInvariant()
    @{ version = $version; files = @{ $file = @{ sha256 = $hash } } } |
        ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $out 'manifest.json')
    Write-Host "Packaged $stage.zip"
} finally { Pop-Location }
