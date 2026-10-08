#!/usr/bin/env bash
# Runs the UI tests on a connected emulator or device: Maestro flows (.maestro/) against the mock e2e build,
# then the native instrumented tests (app/src/androidTestMock) on mockDebug. Details: docs/e2e.md.
#
# Usage:
#   scripts/e2e.sh                       # everything
#   scripts/e2e.sh --tags smoke          # only Maestro flows tagged `smoke` (no native tests)
#   scripts/e2e.sh --flow .maestro/flows/auth/register_new_user.yaml
#   scripts/e2e.sh --no-build            # reuse the APK built last time
#   scripts/e2e.sh --no-native           # skip the instrumented tests
#
# Needs: adb with one device attached, Maestro on PATH (https://maestro.dev), JDK 21 for Gradle.
# Reports: build/e2e/ (JUnit XML, Maestro screenshots and logs of failed flows).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/e2e"
APK="$ROOT/app/build/outputs/apk/mock/e2e/app-mock-e2e.apk"
APP_ID="ru.finassist.pf.mock.e2e"

TAGS=""
FLOW="$ROOT/.maestro"
BUILD=1
NATIVE=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tags) TAGS="$2"; NATIVE=0; shift 2 ;;
        --flow) FLOW="$2"; NATIVE=0; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --no-native) NATIVE=0; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

log() { printf '[e2e] %s\n' "$*"; }

command -v adb >/dev/null || { log "adb is not on PATH"; exit 1; }
command -v maestro >/dev/null || { log "maestro is not on PATH: curl -Ls https://get.maestro.mobile.dev | bash"; exit 1; }
[[ "$(adb devices | grep -c -w device)" -ge 1 ]] || { log "no device attached (adb devices)"; exit 1; }

# The flows assume Moscow time: periods and «today» are computed in the device zone.
if [[ "$(adb shell getprop persist.sys.timezone | tr -d '\r')" != "Europe/Moscow" ]]; then
    log "warning: device time zone is not Europe/Moscow; date-dependent checks may fail"
fi

if [[ "$BUILD" == 1 ]]; then
    log "building the mock e2e APK"
    (cd "$ROOT" && ./gradlew :app:assembleMockE2e --console=plain)
fi

log "installing $APP_ID"
adb install -r -t "$APK" >/dev/null

mkdir -p "$OUT"
adb logcat -c || true

# Preflight: cold start and dump the UI hierarchy. If test tags do not show up as resource-id here, every
# flow would fail on its first step — the dump says why in one file instead of eighteen failures.
adb shell am force-stop "$APP_ID"
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 10
adb shell uiautomator dump /sdcard/pf-ui.xml >/dev/null 2>&1 && adb pull /sdcard/pf-ui.xml "$OUT/preflight-ui.xml" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$OUT/preflight.png" 2>/dev/null || true
if grep -q 'resource-id="auth.phone' "$OUT/preflight-ui.xml" 2>/dev/null; then
    log "preflight: the phone screen is up, test tags are visible to UI Automator"
else
    log "preflight: auth.phone not found in the UI hierarchy (see $OUT/preflight-ui.xml, preflight.png, logcat.txt)"
fi

maestro_args=(test "$FLOW" --format junit --output "$OUT/maestro-report.xml" --debug-output "$OUT/maestro")
[[ -n "$TAGS" ]] && maestro_args+=(--include-tags "$TAGS")
log "maestro ${maestro_args[*]}"
status=0
maestro "${maestro_args[@]}" || status=$?
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
# Screenshots and logs of failed flows land in ~/.maestro/tests when --debug-output is not honoured.
[[ -d "$HOME/.maestro/tests" ]] && cp -r "$HOME/.maestro/tests" "$OUT/maestro-tests" 2>/dev/null || true

if [[ "$NATIVE" == 1 ]]; then
    log "native instrumented tests (mockDebug)"
    (cd "$ROOT" && ./gradlew :app:connectedMockDebugAndroidTest --console=plain) || status=$?
    cp -r "$ROOT/app/build/outputs/androidTest-results" "$OUT/native" 2>/dev/null || true
fi

log "reports: $OUT"
exit "$status"
