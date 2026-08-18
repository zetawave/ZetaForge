#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ZetaForge - one-shot development loop.
#
#   ./run.sh                 build plugin + Host, install, import the .zeta,
#                            launch the app and execute the plugin
#   ./run.sh --logs          same, then follow the ZetaForge log stream
#   ./run.sh --host-only     rebuild/install only the Host (fast UI loop)
#   ./run.sh --plugin-only   rebuild the plugin, re-import it, run it
#   ./run.sh --no-run        stop after import (do not execute the plugin)
#   ./run.sh --fresh         wipe app data first (clean install state)
#   ./run.sh --scenario throw|unreachable
#                            run the failure paths instead of the happy path
#   ./run.sh --test          also run unit + instrumented acceptance tests
#   ./run.sh --clean         gradle clean before building
#   ./run.sh -s <serial>     target a specific device (default: autodetected)
#
# Everything is driven through the Gradle wrapper and adb: no Android Studio.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# Git Bash on Windows rewrites /data/... and /sdcard/... into C:/Program Files/...
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

HOST_MODULE=":host"
PLUGIN_MODULE=":plugins:retrofit-demo"
HOST_APK="host/build/outputs/apk/debug/host-debug.apk"
PLUGIN_ZETA="plugins/retrofit-demo/build/zetaforge/retrofit-demo.zeta"
HOST_PACKAGE="com.zetaforge.app"
MAIN_ACTIVITY="$HOST_PACKAGE/.MainActivity"
PLUGIN_ID="com.zetaforge.plugins.retrofitdemo"
DEVICE_TMP="/data/local/tmp/retrofit-demo.zeta"
APP_IMPORT_PATH="/data/data/$HOST_PACKAGE/cache/import.zeta"

ACTION_IMPORT="com.zetaforge.app.action.IMPORT_FILE"
ACTION_RUN="com.zetaforge.app.action.RUN_PLUGIN"

# --- options ---------------------------------------------------------------
BUILD_HOST=1
BUILD_PLUGIN=1
DO_IMPORT=1
DO_RUN=1
DO_LOGS=0
DO_TEST=0
DO_CLEAN=0
FRESH=0
SCENARIO=""
SERIAL="${ANDROID_SERIAL:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --host-only)   BUILD_PLUGIN=0; DO_IMPORT=0; DO_RUN=0 ;;
    --plugin-only) BUILD_HOST=0 ;;
    --no-import)   DO_IMPORT=0; DO_RUN=0 ;;
    --no-run)      DO_RUN=0 ;;
    --logs|-l)     DO_LOGS=1 ;;
    --test|-t)     DO_TEST=1 ;;
    --clean)       DO_CLEAN=1 ;;
    --fresh)       FRESH=1 ;;
    --scenario)    shift; SCENARIO="${1:-}" ;;
    -s|--serial)   shift; SERIAL="${1:-}" ;;
    -h|--help)     sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# --- helpers ---------------------------------------------------------------
step()  { printf '\033[1;36m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
ok()    { printf '\033[32m[ ok ]\033[0m %s\n' "$*"; }
fail()  { printf '\033[31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

find_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then echo "$ANDROID_HOME/platform-tools/adb.exe"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then echo "$ANDROID_HOME/platform-tools/adb"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then echo "$ANDROID_SDK_ROOT/platform-tools/adb"
  else fail "adb not found: install platform-tools or set ANDROID_HOME"; fi
}

ADB_BIN="$(find_adb)"
adbx() { "$ADB_BIN" ${SERIAL:+-s "$SERIAL"} "$@"; }

# Picks the connected device: honours -s / ANDROID_SERIAL, prefers a running
# emulator, and refuses to guess when several devices are attached.
detect_device() {
  "$ADB_BIN" start-server >/dev/null 2>&1 || true
  local devices
  devices="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')"
  [ -n "$devices" ] || fail "No device/emulator connected. Start one and retry (adb devices)."

  if [ -n "$SERIAL" ]; then
    echo "$devices" | grep -qx "$SERIAL" || fail "Device '$SERIAL' is not connected."
    return
  fi

  local count emulators
  count="$(echo "$devices" | wc -l | tr -d ' ')"
  if [ "$count" = "1" ]; then
    SERIAL="$devices"
    return
  fi
  emulators="$(echo "$devices" | grep '^emulator-' || true)"
  if [ "$(echo "$emulators" | grep -c . || true)" = "1" ]; then
    SERIAL="$emulators"
    return
  fi
  fail "Several devices connected, pick one with -s <serial>:
$(echo "$devices" | sed 's/^/    /')"
}

wait_for_boot() {
  local booted
  booted="$(adbx shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$booted" != "1" ]; then
    step "Waiting for the device to finish booting"
    adbx wait-for-device
    until [ "$(adbx shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  fi
}

# --- go --------------------------------------------------------------------
START_TS=$(date +%s)

detect_device
wait_for_boot
DEVICE_MODEL="$(adbx shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
DEVICE_API="$(adbx shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
step "Device: $SERIAL ($DEVICE_MODEL, API $DEVICE_API)"

if [ "$DO_CLEAN" = "1" ]; then
  step "gradle clean"
  ./gradlew clean --console=plain -q
fi

GRADLE_TARGETS=()
[ "$BUILD_PLUGIN" = "1" ] && GRADLE_TARGETS+=("$PLUGIN_MODULE:buildZetaPlugin")
[ "$BUILD_HOST" = "1" ] && GRADLE_TARGETS+=("$HOST_MODULE:assembleDebug")

if [ "${#GRADLE_TARGETS[@]}" -gt 0 ]; then
  step "Building: ${GRADLE_TARGETS[*]}"
  ./gradlew "${GRADLE_TARGETS[@]}" --console=plain
fi

if [ "$DO_TEST" = "1" ]; then
  step "Unit tests (:runtime:test)"
  ./gradlew :runtime:test --console=plain
  step "Instrumented acceptance test (:host:connectedDebugAndroidTest)"
  ./gradlew "$HOST_MODULE:connectedDebugAndroidTest" --console=plain
fi

if [ "$BUILD_HOST" = "1" ]; then
  [ -f "$HOST_APK" ] || fail "Host APK missing: $HOST_APK"
  step "Installing the Host"
  adbx install -r "$HOST_APK" >/dev/null
  ok "$HOST_PACKAGE installed ($(du -h "$HOST_APK" | cut -f1))"
fi

if [ "$FRESH" = "1" ]; then
  step "Clearing app data"
  adbx shell pm clear "$HOST_PACKAGE" >/dev/null
fi

step "Launching the app"
adbx shell am start -n "$MAIN_ACTIVITY" >/dev/null
until adbx shell pidof "$HOST_PACKAGE" >/dev/null 2>&1; do sleep 1; done
# `logcat -c` is unreliable on emulators, so the dump is filtered by the pid of
# the process we just started instead.
APP_PID="$(adbx shell pidof "$HOST_PACKAGE" | tr -d '' | awk '{print $1}')"
LOG_SINCE="$(adbx shell date "+%m-%d %H:%M:%S.000" | tr -d '\r')"

if [ "$DO_IMPORT" = "1" ]; then
  [ -f "$PLUGIN_ZETA" ] || fail "Plugin artifact missing: $PLUGIN_ZETA (run without --host-only)"
  step "Importing $(basename "$PLUGIN_ZETA") ($(du -h "$PLUGIN_ZETA" | cut -f1))"
  # Push to a shell-writable location, then stream it into the app's own cache
  # through run-as: the Host imports from its private storage exactly as it
  # would after a SAF pick, with no extra runtime permission involved.
  adbx push "$PLUGIN_ZETA" "$DEVICE_TMP" >/dev/null
  adbx shell "run-as $HOST_PACKAGE sh -c 'cat > $APP_IMPORT_PATH' < $DEVICE_TMP" \
    || fail "run-as failed: the installed build must be the debuggable one"
  adbx shell rm -f "$DEVICE_TMP" >/dev/null 2>&1 || true
  adbx shell am start -n "$MAIN_ACTIVITY" -a "$ACTION_IMPORT" --es path "$APP_IMPORT_PATH" >/dev/null 2>&1
  sleep 2
fi

if [ "$DO_RUN" = "1" ]; then
  case "$SCENARIO" in
    throw)       step "Running $PLUGIN_ID (failure scenario: plugin throws)" ;;
    unreachable) step "Running $PLUGIN_ID (failure scenario: unreachable host)" ;;
    "")          step "Running $PLUGIN_ID" ;;
    *)           fail "Unknown scenario '$SCENARIO' (use throw or unreachable)" ;;
  esac
  adbx shell am start -n "$MAIN_ACTIVITY" -a "$ACTION_RUN" \
    --es pluginId "$PLUGIN_ID" ${SCENARIO:+--es scenario "$SCENARIO"} >/dev/null 2>&1
  sleep 4
fi

LOG_DUMP="$(adbx logcat -d 2>/dev/null || true)"

# `grep -q` would close the pipe early and, under `set -o pipefail`, report a
# failure for the whole pipeline; collect the lines instead.
ZETA_LOG="$(echo "$LOG_DUMP" | grep -E "ZetaForge/" | grep -E "[[:space:]]$APP_PID[[:space:]]" || true)"
CRASHES="$(echo "$LOG_DUMP" | grep -A3 -E "FATAL EXCEPTION" | grep -E "$HOST_PACKAGE|[[:space:]]$APP_PID[[:space:]]" || true)"

step "ZetaForge log"
if [ -n "$ZETA_LOG" ]; then
  echo "$ZETA_LOG" | sed 's/^[0-9][0-9-]* //; s/^/    /'
else
  info "(no records)"
fi

if [ -n "$CRASHES" ]; then
  fail "The Host crashed - see logcat"
fi

if [ "$DO_RUN" = "1" ]; then
  OUTCOME="$(echo "$ZETA_LOG" | grep -E "Runtime.*(SUCCESS -|FAILED -)" | tail -1 | sed 's/.*ZetaForge\/Runtime: //')"
  [ -n "$OUTCOME" ] && step "Result: $OUTCOME"
fi

ok "done in $(( $(date +%s) - START_TS ))s"

if [ "$DO_LOGS" = "1" ]; then
  step "Following logs (Ctrl-C to stop)"
  adbx logcat -v time | grep --line-buffered -E "ZetaForge/"
fi
