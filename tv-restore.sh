#!/usr/bin/env bash
# tv-restore.sh - re-enables every package that was enabled when the backup was taken.
# Nothing is ever uninstalled, so this puts the TV back to stock.
# Usage: bash tv-restore.sh 192.168.1.42 tv-backup-YYYYMMDD-HHMMSS
export LC_ALL=C
set -u

# find adb: next to this script, in the current folder, or on PATH
ADB=adb
for d in "$(dirname "$0")" "$PWD"; do
  if [ -x "$d/adb" ]; then ADB="$d/adb"; break; fi
done
command -v "$ADB" >/dev/null || { echo "adb not found - put this script in the platform-tools folder."; exit 1; }

TV="${1:-}"
BK="${2:-}"
[ -n "$TV" ] && [ -f "$BK/packages-enabled.txt" ] || { echo "Usage: bash tv-restore.sh <tv-ip> <backup-folder>"; exit 1; }
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac

"$ADB" connect "$TV" >/dev/null 2>&1
STATE="$("$ADB" -s "$TV" get-state 2>&1 | tr -d '\r')"
[ "$STATE" = "device" ] || { echo "TV not ready (state: $STATE). Accept the prompt on the TV, then retry."; exit 1; }

tv() { "$ADB" -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

NOW="$(mktemp)"
tv pm list packages -d | sort > "$NOW"

# packages disabled now that were enabled at backup time
comm -12 "$NOW" "$BK/packages-enabled.txt" | sed 's/^package://' | while read -r p; do
  echo "Re-enabling $p"
  tv pm enable --user 0 "$p" >/dev/null 2>&1 || tv pm enable "$p" >/dev/null 2>&1
done
rm -f "$NOW"

# put the original home screen back and switch our launcher off (it is not uninstalled)
STOCK="$(grep '/' "$BK/default-launcher.txt" 2>/dev/null | tail -n 1 | tr -d '\r ')"
[ -n "$STOCK" ] && tv cmd package set-home-activity "$STOCK" >/dev/null 2>&1
tv pm list packages org.tvhome.launcher | grep -q . && tv pm disable-user --user 0 org.tvhome.launcher >/dev/null 2>&1

mkdir -p "$BK/screens"
"$ADB" -s "$TV" exec-out screencap -p </dev/null > "$BK/screens/99-after-restore.png"
echo
echo "Done. Restart the TV to finish. If you installed the custom launcher,"
echo "pick the original one again under Settings > Apps, or uninstall the launcher yourself."
