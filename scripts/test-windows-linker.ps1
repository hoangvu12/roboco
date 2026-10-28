# Report which linker and architecture produced an executable, and fail when
# the linker disagrees with the CI fast-linker switch. link.exe writes a "Rich"
# header (its build-tool record) into the DOS stub; rust-lld writes none.
param(
    [string]$Exe = (Join-Path $PSScriptRoot '../target/release/roboco.exe'),
    [Parameter(Mandatory)][ValidateSet('true', 'false')][string]$FastLinker
)
$ErrorActionPreference = 'Stop'
$Exe = (Resolve-Path -LiteralPath $Exe).Path
$bytes = [IO.File]::ReadAllBytes($Exe)
$pe = [BitConverter]::ToInt32($bytes, 0x3c)
$machine = switch ([BitConverter]::ToUInt16($bytes, $pe + 4)) {
    0x8664 { 'x86_64' }
    0xAA64 { 'aarch64' }
    default { '0x{0:X4}' -f $_ }
}
$rich = [Text.Encoding]::ASCII.GetString($bytes, 0, $pe).Contains('Rich')
Write-Output "$(Split-Path -Leaf $Exe): $machine, linked by $(if ($rich) { 'link.exe (Rich header)' } else { 'rust-lld (no Rich header)' })"
if (($FastLinker -eq 'true') -eq $rich) {
    throw "fast-linker is $FastLinker, but the Rich header says otherwise"
}
