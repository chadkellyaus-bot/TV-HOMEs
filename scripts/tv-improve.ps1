# tv-improve.ps1 - round two, in one run:
#   1. tests the remote's Input button (screenshot)
#   2. DISABLES (never uninstalls) four more background apps that use memory
#   3. sets Android's animations to 0.5x so menus feel quicker
#   4. updates TV Home from GitHub (photo wallpapers, Inputs button, dock fits the screen)
#   5. checks the new home screen and Inputs panel (screenshots)
# It shows what it will change and waits for "yes" first. tv-restore.ps1 undoes all of it.
# Put this file in the platform-tools folder, open cmd there, then run:
#   powershell -ExecutionPolicy Bypass -File .\tv-improve.ps1 192.168.0.139 tv-backup-YYYYMMDD-HHMMSS
param(
    [Parameter(Mandatory=$true)][string]$Ip,
    [Parameter(Mandatory=$true)][string]$Backup
)

$ProgressPreference = 'SilentlyContinue'   # makes downloads much faster in Windows PowerShell
$TV     = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$adb    = if (Test-Path "$PSScriptRoot\adb.exe") { "$PSScriptRoot\adb.exe" } else { "adb" }
$mine   = 'org.tvhome.launcher'
$stock  = 'com.google.android.tvlauncher'
$apkUrl = 'https://github.com/chadkellyaus-bot/TV-HOMEs/releases/download/latest/tv-home.apk'
$apk    = Join-Path $PSScriptRoot 'tv-home.apk'
$log    = "$Backup\disabled-log.txt"
$anims  = 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'

# Running in the background and safe to switch off on this TV (checked against its memory use).
$stage3 = [ordered]@{
    'com.google.android.tvrecommendations' = 'Recommendation rows for the old home screen (now unused)'
    'com.google.android.katniss'           = 'Voice search and Google Assistant'
    'com.google.android.inputmethod.latin' = 'Gboard keyboard (your TV uses the TCL keyboard)'
    'com.tcl.usercenter'                   = 'TCL account centre'
}

if (-not (Test-Path "$Backup\packages-enabled.txt")) { Write-Host "No backup found in '$Backup'. Run tv-backup.ps1 first."; exit 1 }

& $adb connect $TV | Out-Null
$state = (& $adb -s $TV get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') {
    Write-Host "TV not ready (state: $state). Accept the prompt on the TV, then retry."
    exit 1
}

function Shot($name) {
    # Saved on the TV first: this TV prints debug text into screenshots sent straight to the PC.
    New-Item -ItemType Directory -Path "$Backup\screens" -Force | Out-Null
    & $adb -s $TV shell screencap -p /sdcard/tvhome-shot.png *> $null
    & $adb -s $TV pull /sdcard/tvhome-shot.png "$Backup\screens\$name.png" *> $null
    & $adb -s $TV shell rm -f /sdcard/tvhome-shot.png *> $null
    Write-Host "  screenshot: $Backup\screens\$name.png"
}
function OnScreen {
    $line = & $adb -s $TV shell dumpsys activity activities | Select-String 'ResumedActivity' | Select-Object -First 1
    if ($line -and ($line.ToString() -match 'u0 (\S+)')) { $Matches[1] } else { '(could not tell)' }
}
function Key($k) { & $adb -s $TV shell input keyevent $k *> $null }

# ---- download the new TV Home (nothing on the TV changes if this fails) ----
Write-Host "Downloading the new TV Home from GitHub ..."
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri $apkUrl -OutFile $apk
} catch {
    & curl.exe -fsSL -o $apk $apkUrl
}
if (-not (Test-Path $apk) -or (Get-Item $apk).Length -lt 10000) {
    Write-Host "Couldn't download $apkUrl. Check the PC's internet and try again. Nothing on the TV was changed."
    exit 1
}
Write-Host ("  tv-home.apk ({0:N0} KB)" -f ((Get-Item $apk).Length / 1KB))

# ---- 1. the Input button ----
Write-Host ""
Write-Host "Step 1 of 4: testing the remote's Input button ..."
Key KEYCODE_HOME
Start-Sleep -Seconds 3
Shot 'improve-1-home-before'
Key KEYCODE_TV_INPUT
Start-Sleep -Seconds 3
$afterInput = OnScreen
Shot 'improve-2-input-button'
Write-Host "  After pressing Input the TV showed: $afterInput"
Key KEYCODE_BACK
Start-Sleep -Seconds 1
Key KEYCODE_HOME
Start-Sleep -Seconds 2

# ---- confirm ----
$enabledNow = @(& $adb -s $TV shell pm list packages -e | ForEach-Object { $_.Trim() -replace '^package:', '' })
$todo = @($stage3.Keys | Where-Object { $enabledNow -contains $_ })
Write-Host ""
Write-Host "Step 2 of 4: these will be DISABLED (not uninstalled, fully reversible):"
foreach ($p in $todo) { Write-Host ("  {0,-40} {1}" -f $p, $stage3[$p]) }
if ($todo.Count -eq 0) { Write-Host "  (nothing - already done)" }
$oldVer = & $adb -s $TV shell dumpsys package $mine | Select-String 'versionCode=(\d+)' | Select-Object -First 1
if (-not $oldVer) { $note = 'TV Home will be installed.' }
elseif ($oldVer.Matches[0].Groups[1].Value -eq '1') { $note = "Your TV Home was signed with a throwaway key, so it's reinstalled once and the dock goes back to the default apps." }
else { $note = 'TV Home is updated in place; your dock stays as it is.' }
Write-Host ""
Write-Host "Also: animations set to 0.5x, and TV Home updated to the latest version."
Write-Host $note
Write-Host ""
$ans = Read-Host "Type yes to continue"
if ($ans -ne 'yes') { Write-Host "Cancelled. Nothing on the TV was changed."; exit 0 }

foreach ($p in $todo) {
    $out = (& $adb -s $TV shell pm disable-user --user 0 $p 2>&1 | Out-String).Trim()
    if ($out -match 'new state: disabled') {
        Write-Host "  Disabled  $p"
        Add-Content $log ("{0}  stage3  disabled  {1}" -f (Get-Date -Format s), $p)
    } else {
        Write-Host "  FAILED    $p   ($out)"
    }
}

# ---- animations (old values saved so tv-restore.ps1 can put them back) ----
$saved = "$Backup\animation-scales-before.txt"
if (-not (Test-Path $saved)) {
    foreach ($k in $anims) { Add-Content $saved ("{0}={1}" -f $k, (& $adb -s $TV shell settings get global $k | Out-String).Trim()) }
}
foreach ($k in $anims) { & $adb -s $TV shell settings put global $k 0.5 *> $null }
Write-Host "  Animations set to 0.5x"

# ---- 3. update TV Home ----
Write-Host ""
Write-Host "Step 3 of 4: updating TV Home ..."
& $adb -s $TV shell pm enable --user 0 $stock *> $null   # safety net: the old home screen is back while TV Home is swapped
$out = (& $adb -s $TV install -r $apk 2>&1 | Out-String)
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
    Write-Host "  Reinstalling TV Home once with the new signing key ..."
    & $adb -s $TV uninstall $mine *> $null
    $out = (& $adb -s $TV install $apk 2>&1 | Out-String)
}
if ($out -notmatch 'Success') {
    Write-Host "  Couldn't install the new TV Home:"
    Write-Host $out
    & $adb -s $TV shell cmd package set-home-activity "$stock/.MainActivity" *> $null
    Key KEYCODE_HOME
    Write-Host "  The TV is on its original home screen for now. Copy everything above and send it to me."
    exit 1
}
Add-Content $log ("{0}  launcher  updated  {1}" -f (Get-Date -Format s), $mine)
& $adb -s $TV shell cmd package set-home-activity "$mine/.MainActivity" *> $null
& $adb -s $TV shell pm disable-user --user 0 $stock *> $null
Key KEYCODE_HOME
Start-Sleep -Seconds 6
Write-Host "  Home screen now: $(OnScreen)"
Shot 'improve-3-new-home'

# ---- 4. the Inputs panel ----
Write-Host ""
Write-Host "Step 4 of 4: opening the new Inputs panel ..."
& $adb -s $TV shell am start -n "$mine/.MainActivity" --ez show_inputs true *> $null
Start-Sleep -Seconds 3
Shot 'improve-4-inputs-panel'
Key KEYCODE_BACK
Start-Sleep -Seconds 1
Key KEYCODE_HOME

Write-Host ""
Write-Host "Done. Tell Claude it's finished; it will check the screenshots in $Backup\screens"
Write-Host "To undo everything: powershell -ExecutionPolicy Bypass -File .\tv-restore.ps1 $Ip $Backup"
