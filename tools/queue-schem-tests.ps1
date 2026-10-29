# Runs schematic tests one after another from a queue file (one per line: name|schem|minutes|env|flat|src), each with
# run-schem-test.ps1; a line STOP ends it. Lines are read as they come, so the file can be added to while it runs.
# Usage: .\tools\queue-schem-tests.ps1 -Queue $env:TEMP\ha-queue.txt [-WhenIdle]
# -WhenIdle: only while the user's own Minecraft (javaw.exe) is closed - a test window beside their game slows it (they
# closed it). Their game opening mid-test stops that test, and it's run again once their game is closed.
param([string]$Queue = "$env:TEMP\ha-queue.txt", [switch]$WhenIdle)
function Test-UserGame { [bool](Get-Process javaw -ErrorAction SilentlyContinue) }
$done = 0
while ($true) {
	$lines = @(Get-Content $Queue -ErrorAction SilentlyContinue)
	if ($done -ge $lines.Count) { Start-Sleep -Seconds 10; continue }
	$l = $lines[$done]; $done++
	if ($l -match '^\s*STOP') { break }
	if ($l -match '^\s*(#|$)') { continue }
	$f = $l -split '\|'
	$src = if ($f.Count -ge 6 -and $f[5]) { $f[5] } else { "C:\dev\human-autopilot" }
	$run = @{ Name = $f[0]; Schem = $f[1]; Minutes = [int]$f[2]; Env = $(if ($f.Count -ge 4) { $f[3] } else { "" }); Src = $src }
	if ($f.Count -ge 5 -and $f[4] -eq 'flat') { $run.Flat = $true }
	# (A run of the same name overwrote the last one's log: the first valley run's end was lost. Kept with its time.)
	$old = "$env:TEMPun-$($f[0]).log"
	if (Test-Path $old) { Move-Item $old "$env:TEMPun-$($f[0])-$(Get-Date -Format 'MMdd-HHmmss').log" -Force }
	if (-not $WhenIdle) {
		& "C:\dev\human-autopilot\tools\run-schem-test.ps1" @run
		continue
	}
	while (Test-UserGame) { Start-Sleep -Seconds 20 }
	$job = Start-Job -ScriptBlock { param($r) & "C:\dev\human-autopilot\tools\run-schem-test.ps1" @r } -ArgumentList $run
	$cut = $false
	while ($job.State -eq 'Running') {
		Start-Sleep -Seconds 5
		if (-not $cut -and (Test-UserGame)) {
			$cut = $true
			Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*fabric.client.gametest*' } |
				ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
			"stopped $(Get-Date -Format HH:mm:ss): the user's game opened" | Out-File -Encoding utf8 -Append "$env:TEMP\run-$($run.Name).log"
		}
	}
	Remove-Job $job -Force
	if ($cut) { $done-- } # (the same line again once their game is closed)
}
