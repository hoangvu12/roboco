# android.yml's APK checks, shared by the ubuntu and Windows Gradle jobs
# (ci-speed 08 split them into two jobs). Run from apps/android after
# assembleDebug.
#   -Libraries  android-next#02: every split carries the core AND the Tailcat
#               transport for its own ABI; fails otherwise. Sizes go to the
#               job summary (stored is the compressed size in the APK:
#               useLegacyPackaging compresses .so).
#   -Sizes      the debug APKs, the R8 smoke's release APK and the xtask's
#               sizes.json, when present, into the job summary.
param(
    [switch]$Libraries,
    [switch]$Sizes
)

if ($Libraries) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apks = Get-ChildItem "app/build/outputs/apk/debug/*.apk"
    if (-not $apks) { Write-Output "::error::no debug APK was built"; exit 1 }
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "### Native libraries in the debug APKs", "", "| APK | library | stored (KB) | uncompressed (KB) |", "| --- | --- | ---: | ---: |"
    $missing = @()
    foreach ($apk in $apks) {
        $abi = $apk.BaseName -replace '^app-(.+)-debug$', '$1'
        $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            foreach ($lib in "libroboco_mobile.so", "libroboco_tailcat.so") {
                $entry = $zip.GetEntry("lib/$abi/$lib")
                if ($entry) {
                    $stored = [math]::Round($entry.CompressedLength / 1KB)
                    $full = [math]::Round($entry.Length / 1KB)
                    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "| $($apk.Name) | $lib | $stored | $full |"
                } else {
                    $missing += "$($apk.Name) has no lib/$abi/$lib"
                }
            }
        } finally {
            $zip.Dispose()
        }
    }
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value ""
    foreach ($line in $missing) { Write-Output "::error::$line" }
    if ($missing) { exit 1 }
}

if ($Sizes) {
    # ABI splits (android-app#31) name every APK after its ABI.
    $apks = Get-ChildItem "app/build/outputs/apk/debug/*.apk" -ErrorAction SilentlyContinue
    foreach ($apk in $apks) {
        $kb = [math]::Round($apk.Length / 1KB)
        Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "APK $($apk.Name): $kb KB", ""
    }
    # The full tier's R8 smoke, when it ran this build.
    $release = Get-ChildItem "app/build/outputs/apk/release/*.apk" -ErrorAction SilentlyContinue
    foreach ($apk in $release) {
        $kb = [math]::Round($apk.Length / 1KB)
        Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "Release APK $($apk.Name): $kb KB (unsigned R8 smoke)", ""
    }
    # The xtask's sizes.json, when the core was built this run.
    $sizesJson = Get-Content "../../target/android-core/mobile/sizes.json" -Raw -ErrorAction SilentlyContinue
    if ($sizesJson) {
        $fence = ([string][char]96) * 3
        Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "### Core sizes", "", "$($fence)json", $sizesJson, $fence, ""
    }
}
