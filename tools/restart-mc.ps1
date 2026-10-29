# Puts a new autopilot jar into the user's game: closes Minecraft normally (Replay Mod and the world save), copies the
# jar in (the old one kept in dist/), and leaves reopening it to LiveKeeper, which joins the server again; the mod then
# carries on with its build by itself (SchematicMode.carryOnAfterRestart).
# Only closes the game while LiveKeeper is watching (it wrote its status in the last 3 minutes and hasn't given up);
# otherwise it waits for the user to close Minecraft, then installs (-NoWait: give up instead).
# Usage: .\tools\restart-mc.ps1 -Jar <path> [-NoWait]
param([Parameter(Mandatory = $true)][string]$Jar, [switch]$NoWait, [switch]$WaitOnly, [switch]$Restart)
$mods = "$env:APPDATA\.minecraft\mods\human-autopilot-1.0.0.jar"
$dist = "C:\dev\human-autopilot\dist"
$log = "$env:TEMP\restart-mc.log"
function Say($t) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $t" | Tee-Object -FilePath $log -Append }
if (-not (Test-Path $Jar)) { Say "no jar at $Jar"; exit 1 }
function LiveKeeperWatching {
	$f = "$env:APPDATA\LiveKeeper\live-status.json"
	if (-not (Test-Path $f)) { return $false }
	try { $j = Get-Content $f -Raw | ConvertFrom-Json } catch { return $false }
	$age = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long]$j.at
	return ($age -lt 180) -and -not ($j.game -and $j.game.gave_up)
}
$weClosed = $false
$game = @(Get-Process javaw -ErrorAction SilentlyContinue)
# (The old jar saved now, before waiting: once the game's gone, the copy in is all that's left to do. LiveKeeper opens
# Minecraft again ~2 s after it closes, so this waits on those very processes, 0.2 s at a time, not on "no javaw at all":
# checking every 5 s, the new game could be up before it ever saw the old one gone.)
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmm'
if (Test-Path $mods) { Copy-Item $mods "$dist\human-autopilot-1.0.0-installed-$stamp.jar" -Force }
function WaitGone($procs, $seconds) {
	$t0 = Get-Date
	while ($true) {
		$left = @($procs | Where-Object { -not $_.HasExited })
		if ($left.Count -eq 0) { return $true }
		if ($seconds -gt 0 -and ((Get-Date) - $t0).TotalSeconds -ge $seconds) { return $false }
		Start-Sleep -Milliseconds 200
	}
}
if ($game.Count -gt 0) {
	# (Only with -Restart: 2026-10-24 03:37 it closed the user's game with LiveKeeper watching, and LiveKeeper never
	# opened it again - it took the launcher, also Minecraft.exe, for the game. Until that's seen working, it waits.)
	if ($Restart -and -not $WaitOnly -and (LiveKeeperWatching)) {
		Say "closing Minecraft (LiveKeeper will open it again)"
		$weClosed = $true
		$game | ForEach-Object { [void]$_.CloseMainWindow() }
		if (-not (WaitGone $game 90)) { Say "still open after 90 s: stopping it"; $game | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } }
		[void](WaitGone $game 10)
	} elseif ($NoWait) {
		Say "LiveKeeper isn't watching: not closing the game (nobody would open it again)"; exit 2
	} else {
		Say "waiting for you to close Minecraft (no -Restart, or LiveKeeper isn't watching)"
		[void](WaitGone $game 0)
	}
}
# In at once; if the file's still held for a moment, again every 0.1 s for 10 s.
$ok = $false
for ($i = 0; $i -lt 100 -and -not $ok; $i++) {
	try { Copy-Item $Jar $mods -Force -ErrorAction Stop; $ok = $true } catch { Start-Sleep -Milliseconds 100 }
}
if (-not $ok) { Say "couldn't put $Jar in (the mods jar stayed locked)"; exit 3 }
Copy-Item $Jar "$dist\human-autopilot-1.0.0.jar" -Force
Say "installed $Jar (md5 $((Get-FileHash -Algorithm MD5 $mods).Hash))"
# Closed by us: LiveKeeper starts it again and joins (start-minecraft.request, read on its next tick - it didn't do
# that by itself after a normal close).
if ($weClosed) {
	Set-Content -Path "$env:APPDATA\LiveKeeper\start-minecraft.request" -Value (Get-Date -Format o) -Encoding ascii
	Say "asked LiveKeeper to start Minecraft again"
}
