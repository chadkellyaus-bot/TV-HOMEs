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

# put the animation speeds back (saved by tv-improve.ps1)
if [ -f "$BK/animation-scales-before.txt" ]; then
  tr -d '\r' < "$BK/animation-scales-before.txt" | while IFS='=' read -r k v; do
    if [ -z "$v" ] || [ "$v" = "null" ]; then tv settings delete global "$k" >/dev/null 2>&1
    else tv settings put global "$k" "$v" >/dev/null 2>&1; fi
  done
fi

mkdir -p "$BK/screens"
# saved on the TV first: this TV adds debug text to screenshots sent straight to the PC
"$ADB" -s "$TV" shell screencap -p /sdcard/tvhome-shot.png </dev/null >/dev/null 2>&1
"$ADB" -s "$TV" pull /sdcard/tvhome-shot.png "$BK/screens/99-after-restore.png" </dev/null >/dev/null 2>&1
"$ADB" -s "$TV" shell rm -f /sdcard/tvhome-shot.png </dev/null >/dev/null 2>&1
echo
echo "Done. Restart the TV (unplug it for 10 seconds) to finish."
echo "TV Home is switched off, not uninstalled, so it's easy to come back to."
