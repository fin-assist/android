#!/usr/bin/env bash
# Runs the UI tests on a connected emulator or device: Maestro flows (.maestro/) against the mock e2e build,
# then the native instrumented tests (app/src/androidTestMock) on the same build. Details: docs/e2e.md.
#
# Usage:
#   scripts/e2e.sh                       # everything
#   scripts/e2e.sh --tags smoke          # only Maestro flows tagged `smoke` (no native tests)
#   scripts/e2e.sh --flow .maestro/flows/auth/register_new_user.yaml   # --flow may be repeated
#   scripts/e2e.sh --shard 2/5           # one Maestro shard; native suite runs separately in CI
#   scripts/e2e.sh --no-build            # reuse the APK built last time
#   scripts/e2e.sh --no-native           # skip the instrumented tests
#   scripts/e2e.sh --native-only         # native suite only, no Maestro installation or flow preflight
#
# Needs: adb with one device attached, Maestro on PATH (https://maestro.dev), JDK 21 for Gradle.
# Reports: build/e2e/ (JUnit XML, Maestro screenshots and logs of failed flows, JaCoCo execution data of the
# flows in coverage/ec; the native tests' results and data in native/ and native-coverage/).
# Coverage report: `./gradlew :e2eCoverageReport`, then
# `python3 scripts/ui-coverage.py summary --jacoco build/reports/e2e-coverage/report.xml` (docs/testing.md).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/e2e"
APK="$ROOT/app/build/outputs/apk/mock/e2e/app-mock-e2e.apk"
APP_ID="ru.finassist.pf.mock.e2e"
# Where the e2e build saves its JaCoCo execution data (E2eCoverage.kt): shared storage, so it survives clearState.
DEVICE_COVERAGE="/sdcard/Download/pf-coverage"

TAGS=""
FLOWS=()
SHARD=""
BUILD=1
NATIVE=1
NATIVE_ONLY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tags) TAGS="$2"; NATIVE=0; shift 2 ;;
        --flow) FLOWS+=("$2"); NATIVE=0; shift 2 ;;
        --shard) SHARD="$2"; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --no-native) NATIVE=0; shift ;;
        --native-only) NATIVE_ONLY=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

log() { printf '[e2e] %s\n' "$*"; }
mkdir -p "$OUT"
receipt() { python3 "$ROOT/scripts/e2e_execution.py" "$@"; }
if [[ "${GITHUB_ACTIONS:-}" != true || ! -f "$OUT/execution.json" ]]; then receipt init; fi
finish() {
    local code=$?
    receipt finish "$code"
    adb shell settings put global hide_error_dialogs 0 >/dev/null 2>&1 || true
}
trap finish EXIT
# A local checkout can retain outputs from an earlier attempt. Clear test payload before any setup can fail,
# while retaining this CI job's execution receipt and its SDK/AVD preparation telemetry. Never remove APKs.
rm -rf "$OUT/preflight" "$OUT/maestro" "$OUT/maestro-log" "$OUT/shots" "$OUT/maestro-output.tgz" "$OUT/maestro-output.tmp" \
    "$OUT/maestro-report.xml" "$OUT/maestro-progress.json" "$OUT/maestro-progress.tmp" "$OUT/native-report.xml" "$OUT/jvm-report.xml" "$OUT/preflight-ui.xml" \
    "$OUT/preflight.png" "$OUT/logcat.txt" "$OUT/coverage" "$OUT/native" "$OUT/native-coverage" \
    "$OUT/native-report" "$OUT/expected-tests.json" "$OUT/execution-status.json" "$OUT/qase-delivery.json" \
    "$ROOT/app/build/outputs/androidTest-results" "$ROOT/app/build/outputs/code_coverage" \
    "$ROOT/app/build/reports/androidTests" "$ROOT/build/reports/e2e-coverage"
receipt phase setup

if [[ "$NATIVE_ONLY" == 1 ]]; then
    [[ -z "$SHARD" && -z "$TAGS" && ${#FLOWS[@]} -eq 0 && "$NATIVE" == 1 ]] || { log "--native-only cannot combine with flow/shard/tags/no-native"; exit 2; }
elif [[ -n "$SHARD" ]]; then
    [[ ${#FLOWS[@]} -eq 0 ]] || { log "--shard and --flow do not combine"; exit 2; }
    # The split is deterministic (durations in scripts/e2e-durations.json): every job computes the same shards.
    shard_flows="$(python3 "$ROOT/scripts/e2e-shards.py" list --shard "$SHARD")" || { log "bad --shard $SHARD"; exit 2; }
    mapfile -t FLOWS <<< "$shard_flows"
    # CI native has its own fresh device and deadline; a shard always contains Maestro only.
    NATIVE=0
    if [[ ${#FLOWS[@]} -eq 0 || -z "${FLOWS[0]}" ]]; then
        log "shard $SHARD has no flows"
        FLOWS=()
    else
        log "shard $SHARD: ${#FLOWS[@]} flow(s)"
        FLOWS=("${FLOWS[@]/#/$ROOT/}")
    fi
elif [[ ${#FLOWS[@]} -eq 0 ]]; then
    FLOWS=("$ROOT/.maestro")
fi

command -v adb >/dev/null || { log "adb is not on PATH"; exit 1; }
if [[ "$NATIVE_ONLY" == 0 ]]; then
    command -v maestro >/dev/null || { log "maestro is not on PATH: use scripts/install-maestro.sh"; exit 1; }
fi
receipt phase device
if [[ "$NATIVE_ONLY" == 1 ]]; then receipt native-mark device_setup_started; fi
[[ "$(adb devices | grep -c -w device)" -ge 1 ]] || { log "no device attached (adb devices)"; exit 1; }

# The flows assume Moscow time: periods and «today» are computed in the device zone.
if [[ "$(adb shell getprop persist.sys.timezone | tr -d '\r')" != "Europe/Moscow" ]]; then
    log "warning: device time zone is not Europe/Moscow; date-dependent checks may fail"
fi

if [[ "$BUILD" == 1 ]]; then
    receipt phase setup
    log "building the mock e2e APK"
    (cd "$ROOT" && ./gradlew :app:assembleMockE2e --console=plain)
fi
receipt identity
receipt phase device

# AGP installs both APKs for connected tests. Only Maestro needs an explicit app installation.
if [[ "$NATIVE_ONLY" == 0 ]]; then
    log "installing $APP_ID"
    adb install -r -t "$APK" >/dev/null
fi

adb logcat -c || true
# Execution data of an earlier run must not count: the device folder is pulled whole after the flows, and
# :e2eCoverageReport reads every .ec AGP left from the native tests — of this run only.
adb shell rm -rf "$DEVICE_COVERAGE" >/dev/null 2>&1 || true

# A freshly booted CI emulator often shows «Pixel Launcher isn't responding». The dialog takes window focus
# from the app under test, so every flow and the Espresso test fail. Hide system error dialogs for the run.
adb shell settings put global hide_error_dialogs 1 || true
# Restored by finish(): on a developer's own device the setting must not stay behind.
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

# The cold app may be progressing while UI Automator cannot return a hierarchy. Keep it in the
# foreground between the three observations instead of discarding its startup work.
if [[ "$NATIVE_ONLY" == 0 ]]; then
preflight_ok=0
needs_launch=1
mkdir -p "$OUT/preflight"
adb shell am force-stop "$APP_ID"
for attempt in 1 2 3; do
    if [[ "$needs_launch" == 1 ]]; then
        launch_log="$OUT/preflight/attempt-$attempt.launch.log"
        launch_status=0
        adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 > "$launch_log" 2>&1 || launch_status=$?
        printf '\nexit_code=%s\n' "$launch_status" >> "$launch_log"
        if [[ "$launch_status" == 0 ]]; then needs_launch=0; fi
    fi
    sleep 10
    if python3 "$ROOT/scripts/e2e-device.py" preflight "$OUT" "$APP_ID" "$attempt"; then
        log "preflight: the phone screen is up, test tags are visible to UI Automator"
        preflight_ok=1
        break
    elif [[ "$?" == 3 ]]; then
        # A valid hierarchy showed another package. Bring the app forward without stopping it.
        needs_launch=1
    fi
    if [[ "$attempt" != 3 ]]; then
        adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
        sleep 15
    fi
done
adb exec-out screencap -p > "$OUT/preflight.png" 2>/dev/null || true
if [[ "$preflight_ok" != 1 ]]; then
    adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
    log "preflight failed: no clean phone screen after 3 attempts (see $OUT/preflight/, preflight.png, logcat.txt)"
    exit 1
fi

# The preflight start ran the instrumented app too: stop it and drop its data, so the report holds the flows only.
adb shell am force-stop "$APP_ID" || true
adb shell rm -rf "$DEVICE_COVERAGE" >/dev/null 2>&1 || true

mkdir -p "$OUT/maestro" "$OUT/maestro-log" "$OUT/shots"
# --test-output-dir: failure screenshots and view hierarchies per flow; --debug-output: the session log.
maestro_args=(test "${FLOWS[@]}" --format junit --output "$OUT/maestro-report.xml" --test-output-dir "$OUT/maestro" --debug-output "$OUT/maestro-log")
[[ -n "$TAGS" ]] && maestro_args+=(--include-tags "$TAGS")
log "maestro ${maestro_args[*]}"
status=0
if [[ ${#FLOWS[@]} -gt 0 ]]; then
    receipt phase maestro
    if [[ -n "$SHARD" && "${GITHUB_ACTIONS:-}" == true ]]; then
        (cd "$OUT/shots" && python3 "$ROOT/scripts/e2e-shards.py" watch --shard "$SHARD" \
            --progress "$OUT/maestro-progress.json" -- maestro "${maestro_args[@]}") || status=$?
    else
        (cd "$OUT/shots" && maestro "${maestro_args[@]}") || status=$?
    fi
    [[ "$status" == 0 ]] || receipt finish "$status"
fi
receipt phase reporting
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
# JaCoCo execution data of every app process the flows started; `collect` keeps the newest complete file of each.
# Never fails the run: coverage is a number next to the results, not a result.
mkdir -p "$OUT/coverage/device"
if adb pull "$DEVICE_COVERAGE" "$OUT/coverage/device" >/dev/null 2>&1; then
    python3 "$ROOT/scripts/ui-coverage.py" collect "$OUT/coverage/device" "$OUT/coverage/ec" || log "warning: could not collect coverage data"
else
    log "warning: no coverage data on the device ($DEVICE_COVERAGE)"
fi
# Per-flow folders are named after the flows («…?», quotes): CI artifacts reject such paths, so ship one archive.
if [[ -d "$OUT/maestro" ]]; then
    tar -czf "$OUT/maestro-output.tgz" -C "$OUT" maestro && rm -rf "$OUT/maestro"
fi
else
    status=0
    receipt native-mark device_setup_finished
fi

if [[ "$NATIVE" == 1 ]]; then
    receipt phase native
    log "native instrumented tests (mock e2e build)"
    # The first failure decides the exit code: the Maestro status is kept if both fail.
    native=0
    native_args=(:app:connectedMockE2eAndroidTest --console=plain)
    receipt native-mark apk_verification_started
    if [[ "${E2E_USE_PREBUILT_APKS:-}" == true ]]; then
        # Preserve AGP's runner, JUnit and JaCoCo. Its APK listings and test discovery inputs came from the build job.
        native_args+=(-Ppf.e2ePrebuiltApks)
        (cd "$ROOT" && python3 scripts/e2e-build-inputs.py check-apks)
    fi
    (cd "$ROOT" && python3 scripts/e2e-device.py native -- ./gradlew "${native_args[@]}") || native=$?
    [[ "$native" == 0 ]] || receipt finish "$native"
    receipt native-mark artifact_collection_started
    if [[ "${E2E_USE_PREBUILT_APKS:-}" == true ]]; then
        (cd "$ROOT" && python3 scripts/e2e-build-inputs.py check-apks)
    fi
    # A fresh local run builds the test APK in the connected task; capture it even after a partial/failed suite.
    receipt identity
    [[ "$status" == 0 ]] && status=$native
    receipt phase reporting
    cp -r "$ROOT/app/build/outputs/androidTest-results" "$OUT/native" 2>/dev/null || true
    # Execution data of the native tests: kept with the reports, so a shard's artifact carries it (e2e-shards.py gather).
    cp -r "$ROOT/app/build/outputs/code_coverage" "$OUT/native-coverage" 2>/dev/null || true
    cp -r "$ROOT/app/build/reports/androidTests" "$OUT/native-report" 2>/dev/null || true
    receipt native-mark artifact_collection_finished
fi

log "reports: $OUT"
exit "$status"
