#!/usr/bin/env bash
# tv-backup.sh - READ-ONLY. Saves the TV's current state to a folder.
# Changes NOTHING on the TV. Run this before anything else.
# Usage: bash tv-backup.sh 192.168.1.42
export LC_ALL=C
set -u

# find adb: next to this script, in the current folder, or on PATH
ADB=adb
for d in "$(dirname "$0")" "$PWD"; do
  if [ -x "$d/adb" ]; then ADB="$d/adb"; break; fi
done
command -v "$ADB" >/dev/null || { echo "adb not found - put this script in the platform-tools folder."; exit 1; }

TV="${1:-}"
[ -n "$TV" ] || { echo "Usage: bash tv-backup.sh <tv-ip>"; exit 1; }
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac

"$ADB" connect "$TV" >/dev/null 2>&1
STATE="$("$ADB" -s "$TV" get-state 2>&1 | tr -d '\r')"
[ "$STATE" = "device" ] || { echo "TV not ready (state: $STATE). Accept the prompt on the TV, then retry."; exit 1; }

OUT="tv-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/screens"
# </dev/null stops adb from swallowing stdin; tr strips Windows line endings
tv() { "$ADB" -s "$TV" shell "$@" </dev/null | tr -d '\r'; }

echo "Backing up to $OUT ..."
tv getprop                                   > "$OUT/props.txt"
tv pm list packages -f -u                    > "$OUT/packages-all.txt"
tv pm list packages -e                       | sort > "$OUT/packages-enabled.txt"
tv pm list packages -d                       | sort > "$OUT/packages-disabled.txt"
tv pm list packages -3                       | sort > "$OUT/packages-user.txt"
tv settings list global                      > "$OUT/settings-global.txt"
tv settings list secure                      > "$OUT/settings-secure.txt"
tv settings list system                      > "$OUT/settings-system.txt"
tv pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/default-launcher.txt"
tv df /data                                  > "$OUT/storage.txt"
tv cat /proc/meminfo                         > "$OUT/meminfo.txt"
tv dumpsys meminfo                           > "$OUT/meminfo-by-process.txt"

"$ADB" -s "$TV" exec-out screencap -p </dev/null > "$OUT/screens/00-before.png"
SIZE=$(wc -c < "$OUT/screens/00-before.png")
[ "$SIZE" -gt 10000 ] || echo "Warning: screenshot looks empty - this TV may block screencap."

echo
echo "Done. Nothing on the TV was changed."
echo "Enabled packages: $(wc -l < "$OUT/packages-enabled.txt")"
echo "Already disabled: $(wc -l < "$OUT/packages-disabled.txt")"
echo "Backup folder:    $OUT"
