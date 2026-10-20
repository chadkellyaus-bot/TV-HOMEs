# tv-backup.ps1 - READ-ONLY. Saves the TV's current state to a folder. Changes nothing on the TV.
# Put this file in the platform-tools folder, open cmd there, then run:
#   powershell -ExecutionPolicy Bypass -File .\tv-backup.ps1 192.168.0.139
param([Parameter(Mandatory=$true)][string]$Ip)

$TV  = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$adb = if (Test-Path "$PSScriptRoot\adb.exe") { "$PSScriptRoot\adb.exe" } else { "adb" }

& $adb connect $TV | Out-Null
$state = (& $adb -s $TV get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "TV not ready (state: $state). Accept the prompt on the TV, then retry."
    exit 1
}

$OUT = "tv-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Path "$OUT\screens" -Force | Out-Null

function Tv { & $adb -s $TV shell @args }

Write-Host "Backing up to $OUT ..."
Tv getprop                                  | Set-Content "$OUT\props.txt"
Tv pm list packages -f -u                   | Set-Content "$OUT\packages-all.txt"
Tv pm list packages -e                      | Sort-Object | Set-Content "$OUT\packages-enabled.txt"
Tv pm list packages -d                      | Sort-Object | Set-Content "$OUT\packages-disabled.txt"
Tv pm list packages -3                      | Sort-Object | Set-Content "$OUT\packages-user.txt"
Tv settings list global                     | Set-Content "$OUT\settings-global.txt"
Tv settings list secure                     | Set-Content "$OUT\settings-secure.txt"
Tv settings list system                     | Set-Content "$OUT\settings-system.txt"
Tv pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Set-Content "$OUT\default-launcher.txt"
Tv df /data                                 | Set-Content "$OUT\storage.txt"
Tv cat /proc/meminfo                        | Set-Content "$OUT\meminfo.txt"
Tv dumpsys meminfo                          | Set-Content "$OUT\meminfo-by-process.txt"

# cmd handles the binary screenshot safely (PowerShell would corrupt it)
cmd /c "`"$adb`" -s $TV exec-out screencap -p > `"$OUT\screens\00-before.png`""
if ((Get-Item "$OUT\screens\00-before.png").Length -lt 10000) {
    Write-Host "Warning: screenshot looks empty - this TV may block screencap."
}

Write-Host ""
Write-Host "Done. Nothing on the TV was changed."
Write-Host ("Enabled packages: " + (Get-Content "$OUT\packages-enabled.txt").Count)
Write-Host ("Already disabled: " + (Get-Content "$OUT\packages-disabled.txt").Count)
Write-Host "Backup folder:    $OUT"
