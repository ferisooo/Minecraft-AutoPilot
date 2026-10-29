# One schematic test run from %TEMP%\hatest (short path: Windows MAX_PATH), its log kept as run-<Name>.log in %TEMP% (or $env:HA_LOGDIR).
# Usage: .\tools\run-schem-test.ps1 -Name base1 -Schem "copper-guard-walls.litematic" -Minutes 12 [-Seed 1234] [-Env "HA_SINK=7;HA_TNT20=1"] [-Src C:\dev\human-autopilot] [-Flat] [-Anywhere]
# A normal-world run goes to the user's test spot by default (2026-10-24, "keep that coords, seed and test on there from
# now on"): seed 5436, the build's middle at 272, 47, -271 - a forested valley, hills 26 and 33 up, trees over 63%.
# -Anywhere: a random world, built where he spawns. Any -Seed, or HA_AT / HA_VALLEY / HA_LANDSPOT in -Env, overrides it.
param([string]$Name, [string]$Schem, [int]$Minutes = 12, [string]$Seed = "", [string]$Env = "", [string]$Src = "C:\dev\human-autopilot", [switch]$Flat, [switch]$Anywhere)
if (-not $Flat -and -not $Anywhere -and -not $Seed -and $Env -notmatch 'HA_(AT|VALLEY|LANDSPOT)=') {
	$Seed = "5436"
	$Env = ($Env + ";HA_AT=272,47,-271").TrimStart(';')
}
$sp = if ($env:HA_LOGDIR) { $env:HA_LOGDIR } else { "$env:TEMP" }
$dst = "$env:TEMP\hatest"
robocopy $Src $dst /MIR /XD build .gradle run dist /NFL /NDL /NJH /NJS | Out-Null
# (A stale compile once ran an old jar: force the classes to be built again.)
Remove-Item -Recurse -Force "$dst\build\classes" -ErrorAction SilentlyContinue
Set-Location $dst
# (Every HA_ setting from an earlier run in the same shell goes: HA_TOWER leaked into the next run once.)
Get-ChildItem Env: | Where-Object { $_.Name -like 'HA_*' } | ForEach-Object { Remove-Item "Env:$($_.Name)" -ErrorAction SilentlyContinue }
$env:HA_ONLY = "schem"
if (-not $Flat) { $env:HA_TERRAIN = "1" }
if ($Seed) { $env:HA_SEED = $Seed }
if ($Schem) { $env:HA_SCHEM = "C:\Users\ItsMe\AppData\Roaming\.minecraft\config\humanautopilot\schematics\$Schem" }
$env:HA_MINUTES = "$Minutes"
foreach ($kv in ($Env -split ';')) {
	if ($kv -match '^\s*([A-Z_0-9]+)=(.*)$') { Set-Item "Env:$($Matches[1])" $Matches[2] }
}
$log = "$sp\run-$Name.log"
"start $(Get-Date -Format HH:mm:ss) $Name schem=$Schem seed=$Seed min=$Minutes env=$Env src=$Src" | Out-File -Encoding utf8 $log
& "$dst\gradlew.bat" build runClientGameTest --console=plain 2>&1 | ForEach-Object { "$_" } | Out-File -Encoding utf8 -Append $log
"exit $LASTEXITCODE $(Get-Date -Format HH:mm:ss)" | Out-File -Encoding utf8 -Append $log
