# TV Home

A tiny, fast, Apple TV-style home screen for low-memory Android TVs (built for a 1 GB / 8 GB set), plus
reversible debloat scripts. Open source (MIT).

- **Launcher** (`app/`): wallpaper that cross-fades every 10 minutes, a dock of favourite apps, an "All apps"
  grid and Settings. Long-press an app (hold OK) in All apps to add or remove it from the dock; hold OK on a dock
  icon to remove it. No dependencies, no network, no tracking.
- **Wallpapers**: nature scenes are drawn in code (no image files). To use your own photos, copy JPEGs to
  `/sdcard/Android/data/org.tvhome.launcher/files/wallpapers/` with `adb push`; they rotate with the built-in scenes.
- **Scripts** (`scripts/`): back up the TV, disable (never uninstall) ad/telemetry/unused packages from a list, install
  the launcher, and restore everything to stock.

Build: push this repo to GitHub; the "Build APK" workflow produces `app-debug.apk` under Actions > Artifacts.
