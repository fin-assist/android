# Local adb transport investigation (FIN-390)

`adb device offline` is a local emulator/driver transport failure. It is independent of a server error,
consent-document HTTP requests and the `common` / `mock-only` classification. Moving a flow between those
sets cannot fix it. Historical Qase runs 34–38 and their original failed results remain unchanged.

## Bounded minimal comparison

Requires Python 3.9+, POSIX process groups (macOS/Linux), JDK 21, and installed adb/Maestro 2.11.0.
No SDK, image or CLI upgrade is performed by the runner.

`scripts/adb-transport.py` repeats the same minimal flow a fixed number of times: launch the installed
FinAssist test/stage app, assert the phone screen, launch it again without stopping it, assert the same
screen. The flow does not log in, register an account, submit an import, call control links or clear app data.
Ordinary app startup still runs: the mock preloads its fixture, and stage may fetch the public consent document. Use a
fresh **owned disposable AVD**, with the phone screen as its starting state. The probe is infrastructure
diagnostics, not product scenario coverage or a replacement for the common UI suite.

Both mock and real stage can use the same probe and transport selection. The current stage debug identity
is `ru.finassist.pf.stage`; `ru.finassist.pf.stage.e2e` is accepted for the future unified harness. No production
identity or physical device is accepted. Build/install the intended APK explicitly, pin installation to
`adb -s <serial>`, record its SHA-256 and keep it unchanged across the compared configurations.

Device probes and boot/ownership commands explicitly use `-H 127.0.0.1 -P 5037 -s <serial>`, matching
Maestro's normal local-server route even if the shell has adb-server environment overrides. The read-only
`devices` inventory uses the same loopback server and does not issue a command to another device.
The runner waits for `sys.boot_completed=1`, then verifies the AVD name through the exact serial before
starting Maestro. A visible emulator or an online adb transport alone is insufficient for first-boot readiness.
The two available configurations are:

- `server`: Maestro `--device emulator-N`, through the existing adb server. This remains the normal default
  transport of the UI suite. Before **each** server-route Maestro session, the runner refuses to start if any other device is listed
  on the local server (including offline or unauthorized devices). Maestro 2.11 has a `byId` → `discover`
  fallback inside `createAndroid` if a selected device disappears; an isolated window avoids touching a
  neighbour through that upstream fallback while the inventory remains unchanged. The check is a point-in-time inventory observation, not an atomic
  device lock: another device could appear between the check and that upstream fallback. Do not attach or
  launch another device during the comparison.
- `direct`: Maestro `--platform android --host 127.0.0.1 --port N+1`, connecting to that emulator's local adbd
  endpoint. An even local emulator console port 5554–5682 is required; the adbd port is derived, never supplied
  as an unrelated endpoint. This is an opt-in diagnostic candidate, not a global transport switch.

Maestro 2.11.0 implements those routes in
[`MaestroSessionManager.kt`](https://github.com/mobile-dev-inc/Maestro/blob/c8c361be1cc89b2e742f185466a302a22778be6b/maestro-cli/src/main/java/maestro/cli/session/MaestroSessionManager.kt)
and [`AndroidDeviceConnection.kt`](https://github.com/mobile-dev-inc/Maestro/blob/c8c361be1cc89b2e742f185466a302a22778be6b/maestro-client/src/main/java/maestro/android/AndroidDeviceConnection.kt).
`--host` / `--port` are hidden CLI options in that pinned version. The ordinary selected-device route uses
`Dadb.list(host)`; an arbitrary `ADB_SERVER_PORT` environment variable is not a supported way to redirect it.
Do not restart the shared adb server, disconnect other devices, or run two Maestro sessions on one AVD.

Example, after building and installing the same APK on an owned AVD:

```bash
# Requires explicit owner authorization for local UI tests.
python3 scripts/adb-transport.py --serial emulator-5582 --avd-name fin390_transport \
  --transport server --repetitions 6 --timeout 150 --out build/adb-transport/mock-server
# Wait for the previous command to finish before changing transport.
python3 scripts/adb-transport.py --serial emulator-5582 --avd-name fin390_transport \
  --transport direct --repetitions 6 --timeout 150 --out build/adb-transport/mock-direct
# On another fresh owned AVD with the stage APK installed, use the same commands with:
# --app-id ru.finassist.pf.stage
```

Every repetition has its own JUnit/material/debug directory and return code. Each Maestro **process group**
is killed after the timeout; this bounds the shell launcher, Java and its children. After an ordinary flow failure, the runner continues
the preselected diagnostic repetitions and preserves every result. A process timeout aborts the batch as
incomplete: a killed Maestro process can leave a driver session that would contaminate the next attempt. It returns
nonzero if any repetition failed or an independent adb probe failed. `flows_passed` records the Maestro
result; `transport_clean` records the independent probes; `passed` requires both. A clean timeline is still
only the sampled observation window. These repetitions never overwrite a product/Qase result or turn a failed
suite into a pass. Output directories must be new; existing evidence is refused.

`timeline.jsonl` records UTC and monotonic timestamps for boot, ownership, Maestro start/end and independent
serial-pinned `adb get-state` / `adb shell true` samples. The sampled commands have three-second limits. The sampler is active load (two short adb commands per
cycle, followed by 0.5 seconds of rest); keep it identical across comparisons. It may affect timing and is
not a passive trace or proof of the lower-level disconnect cause.
The public timeline only contains status values, exit codes and an offline flag; it copies no arbitrary
command output. Raw Maestro logs, screenshots, APKs, emulator logs and crash buffers remain local inside
the private output directory (mode 0700). They can contain device/host identity or app data; do not commit
or publish them without inspection. Emulator logs include adb public keys and host identity.

The runner never stops or deletes an AVD, kills a shared adb server, retries a product mutation or cleans up
server users. Its creator stops only the owned AVD after the comparison. A future stage flow that does
register a user must keep the unified harness's own registration receipts and scoped cleanup; this minimal
probe creates no server user and has no receipt to purge.

## Evidence and limits

The October 30 failures showed Maestro's `host:transport:emulator-N` request failing with `device offline`,
followed by gRPC `UNAVAILABLE`. The app crash buffer was empty in that investigation. Reducing emulator
parallelism did not eliminate it. Neither CPU overload nor a consent-server failure is an established cause.
Fresh first-boot failures must be reported separately from failures after the boot gate.

A local finite passing batch is evidence for that exact configuration and probe, not proof that intermittent
failures are impossible. No Linux Actions stability claim is made without its own explicitly authorized run.
The manual UI workflow and the full UI suite are not invoked by this diagnostic command.

## Local comparison, 2026-10-31

Product APKs were built from `b9e427efe27e0e891e6d0222bd52bf1ef85e413d`. The host was macOS/x86_64,
JDK 21, Maestro 2.11.0, platform-tools 37.0.1 and emulator 37.2.12/build16428233, API 35 Google Play
x86_64 image revision 9. One owned AVD used 2 CPUs, 2048 MiB, 1080×2400/420 dpi and SwiftShader,
without snapshots. The owned AVD had completed first boot once; comparison cold boots reused its data
without a wipe or a logged-in account. The compared mock batches ran in the same cold boot, with the same APK and minimal
flow hash, and no other listed device. Each configuration began with an XML-verified phone screen and
no ANR dialog. Each Maestro process had a 90-second deadline; three repetitions were selected beforehand.

| Environment | Route | Process results | Independent adb samples |
|---|---|---|---|
| mockE2e | server | passed, failed, passed | zero failed/offline samples |
| mockE2e | direct loopback adbd | passed, passed, passed | zero failed/offline samples |
| stageDebug | server | passed, passed, passed | zero failed/offline samples |
| stageDebug | direct loopback adbd | passed, passed, passed | zero failed/offline samples |

The server-route failure was an **application ANR** after the second launch, not `device offline`.
The screenshot showed the app's not-responding dialog; `dumpsys activity lastanr` reported input dispatch
waiting 5001 ms for `FocusEvent(hasFocus=true)` on `MainActivity`, launched by the Maestro driver.
This is an ANR reason and activity state, not a Java main-thread stack or an established blocking cause.
The third predefined repetition passed; the failed second repetition remains failed.

Earlier exploratory runs are retained separately and excluded from the controlled comparison: one started
before first boot had completed and observed an adb transport drop during driver installation; another
encountered a system ANR and timed out. After the subsequent cold boot, `System UI isn't responding`
was explicitly dismissed with **Wait**, before checking the phone precondition. Error dialogs were never
disabled. `uiautomator dump` had returned exit 0 with `null root node` during the ANR, so its exit status
alone was not treated as proof of clean UI; the actual XML was parsed.

The stage batches used the same AVD configuration, unchanged stage APK and a second comparison cold boot;
their phone/no-ANR preconditions were verified in the same way before each route. The exact environment,
APK/flow hashes, timestamps, return codes and sample counts are in
[`adb-transport-comparison.json`](adb-transport-comparison.json). All recorded batches used one content-pinned
runner. After recording them, the published source tightened the inventory gate to run before every
server repetition; the added negative unit test verifies that a newly listed neighbour prevents the next
Maestro launch. This changes neither the generated flow nor the connection parameters.

Direct loopback is a locally tested **candidate** for further diagnostics in both environments. The historical post-bootstrap
`host:transport:... device offline` failure has not been reproduced by these clean mock batches, and this PR
does not claim to fix its root cause. Neither the default UI-suite transport nor any product/backend code
is changed. The probe does not validate consent-document completion, HTTP/SSE behaviour or the full common
suite. The original Qase failures are preserved; no diagnostic result is submitted as a replacement pass.
