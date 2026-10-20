# tv-disable.ps1 - DISABLES (never uninstalls) the packages in a list file. Fully reversible.
# Shows you the list and waits for "yes" first. Takes home-screen screenshots before and after.
# Put this file and the list file in the platform-tools folder, open cmd there, then run:
#   powershell -ExecutionPolicy Bypass -File .\tv-disable.ps1 192.168.0.139 tv-backup-YYYYMMDD-HHMMSS stage1-ads.txt
param(
    [Parameter(Mandatory=$true)][string]$Ip,
    [Parameter(Mandatory=$true)][string]$Backup,
    [Parameter(Mandatory=$true)][string]$List
)

$TV  = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$adb = if (Test-Path "$PSScriptRoot\adb.exe") { "$PSScriptRoot\adb.exe" } else { "adb" }

if (-not (Test-Path "$Backup\packages-enabled.txt")) { Write-Host "No backup found in '$Backup'. Run tv-backup.ps1 first."; exit 1 }
if (-not (Test-Path $List)) { Write-Host "Can't find the list file '$List'."; exit 1 }

& $adb connect $TV | Out-Null
$state = (& $adb -s $TV get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "TV not ready (state: $state). Accept the prompt on the TV, then retry."
    exit 1
}

$stage = [IO.Path]::GetFileNameWithoutExtension($List)

# read the list: "package   # note"
$items = @(foreach ($l in Get-Content $List) {
    $parts = $l -split '#', 2
    $pkg = $parts[0].Trim()
    if ($pkg) {
        [pscustomobject]@{ Pkg = $pkg; Note = $(if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }) }
    }
})

# only touch packages that are installed and currently enabled
$enabledNow = @(& $adb -s $TV shell pm list packages -e | ForEach-Object { $_.Trim() -replace '^package:', '' })
$todo = @($items | Where-Object { $enabledNow -contains $_.Pkg })
$skip = @($items | Where-Object { $enabledNow -notcontains $_.Pkg })

if ($todo.Count -eq 0) { Write-Host "Nothing to do - everything in the list is already disabled or not on this TV."; exit 0 }

Write-Host ""
Write-Host "These will be DISABLED (not uninstalled, fully reversible):"
Write-Host ""
foreach ($i in $todo) { Write-Host ("  {0,-42} {1}" -f $i.Pkg, $i.Note) }
if ($skip.Count -gt 0) {
    Write-Host ""
    Write-Host ("Skipped (not on this TV or already disabled): " + (($skip | ForEach-Object { $_.Pkg }) -join ', '))
}
Write-Host ""
$ans = Read-Host "Type yes to continue"
if ($ans -ne 'yes') { Write-Host "Cancelled. Nothing was changed."; exit 0 }

New-Item -ItemType Directory -Path "$Backup\screens" -Force | Out-Null

function Shot($name) {
    & $adb -s $TV shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 4
    cmd /c "`"$adb`" -s $TV exec-out screencap -p > `"$Backup\screens\$name.png`""
}

Shot "$stage-1-before"

$log = "$Backup\disabled-log.txt"
$failed = @()
$done = 0
foreach ($i in $todo) {
    $out = (& $adb -s $TV shell pm disable-user --user 0 $i.Pkg 2>&1 | Out-String).Trim()
    if ($out -match 'new state: disabled') {
        Write-Host "Disabled  $($i.Pkg)"
        Add-Content $log ("{0}  {1}  disabled  {2}" -f (Get-Date -Format s), $stage, $i.Pkg)
        $done++
    } else {
        Write-Host "FAILED    $($i.Pkg)   ($out)"
        $failed += $i.Pkg
    }
}

Start-Sleep -Seconds 2
Shot "$stage-2-after"

Write-Host ""
Write-Host "Disabled $done of $($todo.Count). Log: $log"
if ($failed.Count -gt 0) { Write-Host ("Could not disable: " + ($failed -join ', ') + " - tell me and I'll look into it.") }
Write-Host "Screenshots: $Backup\screens\$stage-1-before.png and $stage-2-after.png"
Write-Host ""
Write-Host "Now restart the TV (unplug it for 10 seconds) and check everything still works."
Write-Host "To undo: powershell -ExecutionPolicy Bypass -File .\tv-restore.ps1 $Ip $Backup"
