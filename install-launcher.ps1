# install-launcher.ps1 - installs the TV Home launcher and makes it the home screen.
# Nothing is uninstalled: the stock launcher stays installed, and tv-restore.ps1 switches back to it.
# Put this file and app-debug.apk in the platform-tools folder, open cmd there, then run:
#   powershell -ExecutionPolicy Bypass -File .\install-launcher.ps1 192.168.0.139 tv-backup-YYYYMMDD-HHMMSS app-debug.apk
param(
    [Parameter(Mandatory=$true)][string]$Ip,
    [Parameter(Mandatory=$true)][string]$Backup,
    [Parameter(Mandatory=$true)][string]$Apk
)

$TV  = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$adb = if (Test-Path "$PSScriptRoot\adb.exe") { "$PSScriptRoot\adb.exe" } else { "adb" }
$pkg = 'org.tvhome.launcher'

if (-not (Test-Path "$Backup\default-launcher.txt")) { Write-Host "No backup found in '$Backup'. Run tv-backup.ps1 first."; exit 1 }
if (-not (Test-Path $Apk)) { Write-Host "Can't find '$Apk'."; exit 1 }

& $adb connect $TV | Out-Null
$state = (& $adb -s $TV get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "TV not ready (state: $state). Accept the prompt on the TV, then retry."
    exit 1
}

function Shot($name) {
    Start-Sleep -Seconds 5
    New-Item -ItemType Directory -Path "$Backup\screens" -Force | Out-Null
    cmd /c "`"$adb`" -s $TV exec-out screencap -p > `"$Backup\screens\$name.png`""
}

Write-Host "Installing $Apk ..."
& $adb -s $TV install -r $Apk
& $adb -s $TV shell pm enable --user 0 $pkg | Out-Null
Write-Host "Making it the home screen ..."
& $adb -s $TV shell cmd package set-home-activity "$pkg/.MainActivity"
& $adb -s $TV shell input keyevent KEYCODE_HOME
Shot "launcher-installed"
Add-Content "$Backup\disabled-log.txt" ("{0}  launcher  installed  {1}" -f (Get-Date -Format s), $pkg)

Write-Host ""
Write-Host "Done. Screenshot: $Backup\screens\launcher-installed.png"
Write-Host "To go back to stock: powershell -ExecutionPolicy Bypass -File .\tv-restore.ps1 $Ip $Backup"
