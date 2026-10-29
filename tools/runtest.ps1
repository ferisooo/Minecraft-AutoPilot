# Runs build + client gametests from a short path (%TEMP%\hatest) to dodge Windows MAX_PATH.
# Usage:  $env:HA_MINUTES="25"; .\tools\runtest.ps1 -Only real      (basic|survival|life|danger|real)
param([string]$Only = "")
$src = Split-Path -Parent $PSScriptRoot
$dst = "$env:TEMP\hatest"
robocopy $src $dst /MIR /XD build .gradle run dist /NFL /NDL /NJH /NJS | Out-Null
Set-Location $dst
$env:HA_ONLY = $Only
& "$dst\gradlew.bat" build runClientGameTest --console=plain 2>&1 | ForEach-Object { "$_" } | Out-File -Encoding utf8 "$dst\gametest.log"
"exit $LASTEXITCODE"
