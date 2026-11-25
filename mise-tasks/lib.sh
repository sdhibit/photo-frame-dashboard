# Shared helpers for the frame tasks in mise-tasks/. Sourced by them; not a task itself (not
# executable). Pick a frame with ADB_SERIAL (USB serial or <ip>:5555), e.g. in mise.local.toml:
#   [env]
#   ADB_SERIAL = "..."

PKG=com.wesbos.homehq
ACTIVITY="$PKG/.MainActivity"

adb_() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }

# adb shell output without the carriage returns Android 6 adds.
sh_() { adb_ shell "$@" | tr -d '\r'; }

# Runs one fixed device command as root (the command must not contain single quotes). adb shell is
# already root on some frames; most su take -c; AOSP su (PF1007L) wants "su 0 sh -c".
root_() {
  adb_ shell "if id | grep -q '^uid=0('; then $1; else su -c '$1' 2>/dev/null || su 0 sh -c '$1'; fi" | tr -d '\r'
}

need_device() {
  local state
  state=$(adb_ get-state 2>/dev/null || true)
  if [ "$state" != device ]; then
    echo "No frame ready on ADB (state: ${state:-none}). Plug it in, accept any prompt on the frame," >&2
    echo "or with several devices set ADB_SERIAL (see mise-tasks/lib.sh)." >&2
    exit 1
  fi
}

# Prints the kiosk's saved dimmer settings (local dimmer patch), with the defaults it uses when
# a value was never saved. Needs root to read the app's private prefs.
dimmer_show() {
  local prefs
  prefs=$(root_ "cat /data/data/$PKG/shared_prefs/kiosk.xml" 2>/dev/null)
  pref() { # <type> <name> <default>
    local v
    if [ "$1" = string ]; then
      v=$(printf '%s\n' "$prefs" | sed -n "s:.*<string name=\"$2\">\(.*\)</string>.*:\1:p")
    else
      v=$(printf '%s\n' "$prefs" | sed -n "s:.*<$1 name=\"$2\" value=\"\([^\"]*\)\".*:\1:p")
    fi
    echo "${v:-$3}"
  }
  if [ -z "$prefs" ]; then
    echo "Dimmer:     unknown (could not read the kiosk's settings; root needed)"
    return
  fi
  if [ "$(pref boolean dimmer false)" != true ]; then
    echo "Dimmer:     off"
    return
  fi
  echo "Dimmer:     on: dim after $(pref int dim_after_min 10) min to $(pref int dim_level 10);" \
    "night $(pref string night 22:00-06:30) after $(pref int night_after_min 1) min to $(pref int night_level 0)"
}
