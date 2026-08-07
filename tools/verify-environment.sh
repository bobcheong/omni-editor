#!/usr/bin/env bash
# T-00 — run this before T-01a. It reports which tiers of testing are available,
# because that decides which acceptance criteria can gate a task and which must be
# deferred to a manual check.
set -uo pipefail

echo "== Omni Editor environment probe =="

have() { command -v "$1" >/dev/null 2>&1 && echo "  yes  $1 ($($1 --version 2>&1 | head -1))" || echo "  NO   $1"; }

echo "-- toolchain"
have java
have adb
have sdkmanager
have gradle

echo "-- Android SDK"
if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  echo "  yes  SDK at ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
else
  echo "  NO   ANDROID_HOME / ANDROID_SDK_ROOT unset"
fi

echo "-- devices"
if command -v adb >/dev/null 2>&1; then
  adb devices -l | sed '1d;/^$/d' | sed 's/^/  /' || true
  [ -z "$(adb devices | sed '1d;/^$/d')" ] && echo "  none attached"
else
  echo "  adb unavailable"
fi

echo
echo "== Tier available =="
echo "Tier 1 (JVM unit tests)      : needs Java only."
echo "Tier 2 (compile + lint)      : needs the Android SDK."
echo "Tier 3 (instrumented tests)  : needs an emulator or attached device."
echo "Tier 4 (macrobenchmarks)     : needs a physical device; emulator numbers are not valid."
echo
echo "Record the result in docs/adr/001-test-environment.md before starting T-01a."
