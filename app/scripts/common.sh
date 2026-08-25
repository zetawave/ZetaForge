#!/usr/bin/env bash
# Shared helpers for the ZetaForge developer scripts.
# Works in Git Bash on Windows and in any POSIX shell.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$ROOT_DIR/gradlew"

HOST_MODULE=":host"
PLUGIN_MODULE=":plugins:retrofit-demo"
HOST_APK="$ROOT_DIR/host/build/outputs/apk/debug/host-universal-debug.apk"
PLUGIN_ZETA="$ROOT_DIR/plugins/retrofit-demo/build/zetaforge/retrofit-demo.zeta"
HOST_PACKAGE="com.zetaforge.app"
PLUGIN_ID="com.zetaforge.plugins.retrofitdemo"
DEVICE_ZETA_DIR="/sdcard/Download"

log()  { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
fail() { printf '\033[31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }
ok()   { printf '\033[32m[ ok ]\033[0m %s\n' "$*"; }
info() { printf '     %s\n' "$*"; }

adb_bin() {
  if command -v adb >/dev/null 2>&1; then
    echo adb
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    echo "$ANDROID_HOME/platform-tools/adb"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
    echo "$ANDROID_SDK_ROOT/platform-tools/adb"
  else
    fail "adb not found. Install platform-tools or set ANDROID_HOME."
  fi
}

require_device() {
  local adb; adb="$(adb_bin)"
  local count
  count="$("$adb" devices | tail -n +2 | grep -c 'device$' || true)"
  [ "$count" -ge 1 ] || fail "No device/emulator connected (adb devices)."
}

gradle_run() {
  log "gradlew $*"
  "$GRADLEW" "$@"
}
