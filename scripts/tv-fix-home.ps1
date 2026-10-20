# tv-fix-home.ps1 - makes TV Home the ONLY home screen by DISABLING (never uninstalling) the TV's own launcher.
# Lists what it found and waits for "yes" first. tv-restore.ps1 undoes it.
# Put this file in the platform-tools folder, open cmd there, then run:
#   powershell -ExecutionPolicy Bypass -File .\tv-fix-home.ps1 192.168.0.139 tv-backup-YYYYMMDD-HHMMSS
param(
    [Parameter(Mandatory=$true)][string]$Ip,
    [Parameter(Mandatory=$true)][string]$Backup
)

$TV  = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$adb = if (Test-Path "$PSScriptRoot\adb.exe") { "$PSScriptRoot\adb.exe" } else { "adb" }
$mine = 'org.tvhome.launcher'

if (-not (Test-Path "$Backup\packages-enabled.txt")) { Write-Host "No backup found in '$Backup'. Run tv-backup.ps1 first."; exit 1 }

& $adb connect $TV | Out-Null
$state = (& $adb -s $TV get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "TV not ready (state: $state). Accept the prompt on the TV, then retry."
    exit 1
}

function Shot($name) {
    New-Item -ItemType Directory -Path "$Backup\screens" -Force | Out-Null
    cmd /c "`"$adb`" -s $TV exec-out screencap -p > `"$Backup\screens\$name.png`""
}
function OnScreen {
    $line = & $adb -s $TV shell dumpsys activity activities | Select-String 'ResumedActivity' | Select-Object -First 1
    if ($line) { $line.ToString().Trim() } else { '(could not tell)' }
}

Write-Host ""
Write-Host "On the TV's screen right now:"
Write-Host "  $(OnScreen)"

# every app that offers itself as a home screen
$raw   = & $adb -s $TV shell pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
$comps = @($raw | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[\w.]+/\S+$' })
$pkgs  = @($comps | ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique)

# never touch ours, or anything that looks like first-time setup or Settings
$keep = @($pkgs | Where-Object { $_ -eq $mine -or $_ -match 'setup|provision|factory|settings|wraith' })
$off  = @($pkgs | Where-Object { $keep -notcontains $_ })

Write-Host ""
Write-Host "Home screens found on this TV: $($pkgs -join ', ')"
if ($keep.Count -gt 0) { Write-Host "Leaving alone: $($keep -join ', ')" }

if ($off.Count -eq 0) {
    Write-Host ""
    Write-Host "Nothing to switch off. Copy everything printed above and send it to me."
    exit 0
}

Write-Host ""
Write-Host "These will be DISABLED (not uninstalled, fully reversible):"
foreach ($p in $off) { Write-Host "  $p" }
Write-Host ""
$ans = Read-Host "Type yes to continue"
if ($ans -ne 'yes') { Write-Host "Cancelled. Nothing was changed."; exit 0 }

$log = "$Backup\disabled-log.txt"
foreach ($p in $off) {
    $out = (& $adb -s $TV shell pm disable-user --user 0 $p 2>&1 | Out-String).Trim()
    if ($out -match 'new state: disabled') {
        Write-Host "Disabled  $p"
        Add-Content $log ("{0}  fix-home  disabled  {1}" -f (Get-Date -Format s), $p)
    } else {
        Write-Host "FAILED    $p   ($out)"
    }
}

& $adb -s $TV shell cmd package set-home-activity "$mine/.MainActivity" | Out-Null
& $adb -s $TV shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Seconds 5
Shot "home-fix"

Write-Host ""
Write-Host "On the TV's screen now:"
Write-Host "  $(OnScreen)"
Write-Host "Screenshot: $Backup\screens\home-fix.png"
Write-Host ""
Write-Host "Look at the TV and press Home on the remote. If it shows TV Home, you're done."
Write-Host "To undo: powershell -ExecutionPolicy Bypass -File .\tv-restore.ps1 $Ip $Backup"
