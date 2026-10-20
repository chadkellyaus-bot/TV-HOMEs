# TV Home

A tiny, fast, Apple TV-style home screen for low-memory Android TVs (built for a 1 GB / 8 GB TCL/FFalcon set),
plus scripts that debloat the TV reversibly. Open source (MIT).

**Download:** [tv-home.apk](https://github.com/chadkellyaus-bot/TV-HOMEs/releases/download/latest/tv-home.apk)
(rebuilt automatically on every change).

## The launcher (`app/`)

- Nature photo wallpapers that cross-fade every 10 minutes: 12 public-domain/CC0 photos from Wikimedia Commons,
  listed with credits in [`app/wallpapers.txt`](app/wallpapers.txt). To add your own, copy JPEGs to
  `/sdcard/Android/data/org.tvhome.launcher/files/wallpapers/` with `adb push`.
- A dock of favourite apps that fits the screen, plus **Inputs** (HDMI, antenna), **All apps** and **Settings**.
  In All apps, hold OK on an app to add it to the dock; hold OK on a dock icon to remove it.
- The remote's Input button opens the Inputs panel while you're on the home screen.
- No libraries, no network access, no tracking. It frees its images while another app is open.

## The scripts (`scripts/`, Windows PowerShell; `.sh` versions for Mac/Linux)

Copy them into Google's `platform-tools` folder (it contains `adb`), turn on USB debugging on the TV,
then open a command window in that folder. Nothing is ever uninstalled; every change can be undone.

1. `tv-backup.ps1 <tv-ip>` saves the TV's current state (read-only).
2. `tv-disable.ps1 <tv-ip> <backup-folder> stage1-ads.txt` (then `stage2-unused.txt`) disables ads and unused apps.
3. `install-launcher.ps1` and `tv-fix-home.ps1` install TV Home and make it the only home screen.
4. `tv-improve.ps1 <tv-ip> <backup-folder>` disables a few more memory-hungry background apps, speeds up
   animations, and installs or updates TV Home from the link above.
5. `tv-restore.ps1 <tv-ip> <backup-folder>` puts everything back to stock.

Run each one as `powershell -ExecutionPolicy Bypass -File .\<script> ...`. Each shows what it will change and
waits for `yes`. Screenshots from the TV are saved in the backup folder's `screens` folder.

If a TV software update brings the ads back, run the disable steps and `tv-fix-home.ps1` again; they skip anything
already done.

## Building

Every push builds the APK with GitHub Actions (`.github/workflows/build.yml`), downloads the wallpapers, signs
it with the fixed key in `app/tvhome.keystore.b64` (so updates install over the previous version) and publishes
it to the `latest` release.
