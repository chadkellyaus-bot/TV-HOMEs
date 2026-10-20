# tv-restore.ps1 - re-enables every package that was enabled when the backup was taken.
# Nothing is ever uninstalled, so this puts the TV back to stock.
# Put this file in the platform-tools folder, open cmd there, then run:
#   powershell -ExecutionPolicy Bypass -File .\tv-restore.ps1 192.168.0.139 tv-backup-YYYYMMDD-HHMMSS
param(
    [Parameter(Mandatory=$true)][string]$Ip,
    [Parameter(Mandatory=$true)][string]$Backup
)

$TV  = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$adb = if (Test-Path "$PSScriptRoot\adb.exe") { "$PSScriptRoot\adb.exe" } else { "adb" }

if (-not (Test-Path "$Backup\packages-enabled.txt")) {
    Write-Host "Can't find $Backup\packages-enabled.txt - check the backup folder name."
    exit 1
}

& $adb connect $TV | Out-Null
$state = (& $adb -s $TV get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "TV not ready (state: $state). Accept the prompt on the TV, then retry."
    exit 1
}

$wasEnabled  = Get-Content "$Backup\packages-enabled.txt" | ForEach-Object { $_.Trim() }
$nowDisabled = & $adb -s $TV shell pm list packages -d | ForEach-Object { $_.Trim() }

foreach ($line in ($nowDisabled | Where-Object { $wasEnabled -contains $_ })) {
    $p = $line -replace '^package:', ''
    Write-Host "Re-enabling $p"
    & $adb -s $TV shell pm enable --user 0 $p *> $null
    if ($LASTEXITCODE -ne 0) { & $adb -s $TV shell pm enable $p *> $null }
}

# put the original home screen back and switch our launcher off (it is not uninstalled)
$stockHome = Get-Content "$Backup\default-launcher.txt" -ErrorAction SilentlyContinue | Where-Object { $_ -match '/' } | Select-Object -Last 1
if ($stockHome) { & $adb -s $TV shell cmd package set-home-activity $stockHome.Trim() *> $null }
if (& $adb -s $TV shell pm list packages org.tvhome.launcher) { & $adb -s $TV shell pm disable-user --user 0 org.tvhome.launcher *> $null }

# put the animation speeds back (saved by tv-improve.ps1)
if (Test-Path "$Backup\animation-scales-before.txt") {
    foreach ($line in Get-Content "$Backup\animation-scales-before.txt") {
        $k, $v = $line -split '=', 2
        if (-not $v -or $v -eq 'null') { & $adb -s $TV shell settings delete global $k *> $null }
        else { & $adb -s $TV shell settings put global $k $v *> $null }
    }
    Write-Host "Animation speeds put back"
}

New-Item -ItemType Directory -Path "$Backup\screens" -Force | Out-Null
# saved on the TV first: this TV adds debug text to screenshots sent straight to the PC
& $adb -s $TV shell screencap -p /sdcard/tvhome-shot.png *> $null
& $adb -s $TV pull /sdcard/tvhome-shot.png "$Backup\screens\99-after-restore.png" *> $null
& $adb -s $TV shell rm -f /sdcard/tvhome-shot.png *> $null

Write-Host ""
Write-Host "Done. Restart the TV (unplug it for 10 seconds) to finish."
Write-Host "TV Home is switched off, not uninstalled, so it's easy to come back to."
