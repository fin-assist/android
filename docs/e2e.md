# UI tests (e2e)

Two layers, both on an emulator or device:

| Layer | What | Where | Build |
|---|---|---|---|
| Maestro | User scenarios end to end: sign-in, passcode, operations, search, analytics, assistant, statements, profile, flags, offline | `.maestro/` | `mockE2e` |
| Native (Espresso / Compose test) | What Maestro cannot drive reliably — the system document picker | `app/src/androidTestMock/` | `mockE2e` (`connectedMockE2eAndroidTest`) |

Native tests are for the gaps, not a second copy of the scenarios. When a check needs the platform itself
(system dialogs, intents, permissions, biometrics), it goes there; everything a user does on our screens goes to
Maestro. The same split will apply to iOS (XCUITest for the gaps).

## Running

Prerequisites: an emulator or device with `adb`, [Maestro](https://maestro.dev) on PATH
(`curl -Ls https://get.maestro.mobile.dev | bash`), JDK 21. Set the emulator time zone to Europe/Moscow
(Settings → System → Date & time, or `-timezone Europe/Moscow` when starting it).

To reproduce a CI failure locally, use `MAESTRO_VERSION` and the `AVD_API`, `AVD_TARGET`, `AVD_ARCH`
and `AVD_PROFILE` values from `.github/workflows/e2e.yml`, with animations disabled as in that workflow.
Record the actual CLI, image and viewport in the result. Use an absolute path with `--flow`: the runner
changes the working directory to its screenshot output folder before invoking Maestro.

```bash
# All flows and the native tests
scripts/e2e.sh

# Quick pass: flows tagged smoke, no native tests
scripts/e2e.sh --tags smoke

# One flow while writing it (Maestro Studio helps to find elements: `maestro studio`); --flow may be repeated
scripts/e2e.sh --no-build --flow "$PWD/.maestro/flows/assistant/ask_question.yaml"

# One shard of the CI split (below): the flows `scripts/e2e-shards.py list --shard 2/4` prints; native tests in 1/n only
scripts/e2e.sh --shard 2/4
```

Reports go to `build/e2e/`: JUnit XML, `maestro-output.tgz` (per flow: screenshot and view hierarchy of the failed
step, commands, log), logcat, the preflight dump, in `coverage/ec/` the JaCoCo execution data the flows left on
the device (`./gradlew :e2eCoverageReport` turns it into the UI test coverage report — docs/testing.md, «UI test
coverage»), and in `native/` and `native-coverage/` the native tests' results and execution data.

In CI (`.github/workflows/e2e.yml`) the tests run after a merge to `main`; PRs never run them automatically. A
failed run is re-run from the Actions tab («Re-run jobs»); any branch can be run by hand there («Run workflow» →
the branch) to check a new flow or the coverage pipeline before the merge — such a run sends nothing to Qase. The
run summary lists the shards, the flows and the UI test coverage; reports are in the `e2e-reports` artifact.

### Shards in CI

The flows run on five emulators at once (FIN-465, originally four in FIN-180), one job per shard:

- **Split.** `scripts/e2e-shards.py` spreads the flows so that the shards take about the same time: longest flow
  first, to the shard with the least so far, by conservative maxima of successful measured durations on CI
  (`scripts/e2e-durations.json`; a flow not measured yet counts as the median). Shard 1 also runs the native tests
  and gets fewer flows for them. The split is deterministic and every flow is in exactly one shard
  (`scripts/test_e2e_shards.py`); `python3 scripts/e2e-shards.py plan --shards 5` prints it. After a run that changed
  the durations a lot, refresh them from the merged report: `python3 scripts/e2e-shards.py durations
  build/e2e/maestro-report.xml` (in the `e2e-reports` artifact).
  This command only raises known weights. After a verified speedup or flow split, lower a stale maximum through
  a reviewed manual JSON edit citing the successful run; remove deleted-flow entries with their source change.
- **FIN-465 measurements (2026-11-08).** Shard 1 reserves 300 s for native Gradle configuration, all 13
  instrumented tests and report copying. The last complete successful sample,
  [run 42210173741](https://github.com/fin-assist/android/actions/runs/42210173741) (2026-11-06, same native sources),
  took 193.8 s; the reserve includes at least 50% headroom. The 6/13 progress before the timeout in
  [run 42461258707](https://github.com/fin-assist/android/actions/runs/42461258707) is incomplete evidence.
  Updating the four-shard forecast to 34.5/35.1/35.0/35.1 min was insufficient: in
  [run 42467271555](https://github.com/fin-assist/android/actions/runs/42467271555), shard 3 exhausted 2400 s on its
  last flow. Qase82 itself passed completely (246 s). Weights now retain maxima of positive successful samples
  from these two runs; `durations` never lowers a known weight or accepts a failed/nonpositive sample. The
  interrupted `upload_unknown_codes` sample (31.051 s) retains its successful 43 s estimate. All 81 flows and 13
  native tests remain selected. The largest comparable successful-flow cohort grew 13.1%; individual growth
  reached 33.1% and is absorbed by the updated maximum weights. A plan guard adds 20% to aggregate flow time and
  120 s setup, without multiplying the already padded native reserve. This is an engineering margin based on
  observed totals, not p95 or a bound on future timings. Five-shard forecasts including native reserve are
  29.2/29.3/28.6/28.7/29.2 min; stressed totals with setup are 36.1/37.1/36.4/36.4/37.1 min, within the unchanged
  2400 s test and 45 min job limits. Four shards fail that stress check. Forecasts are not full UI/native acceptance.
- **Build.** Every shard builds the APKs itself (`:app:assembleMockE2e :app:assembleMockE2eAndroidTest`, ~40 s from
  the Gradle build cache): the native tests and `:e2eCoverageReport` need the Gradle build anyway, a separate build
  job would add its time and an artifact round trip before every emulator starts, and outputs from the same build
  cache are the same classes the JaCoCo report reads.
- **Pinned Maestro setup (FIN-469).** `scripts/install-maestro.sh` downloads the same versioned official release
  archive as the upstream installer directly, pinned by the official release SHA256 in the workflow, with bounded
  curl retries and HTTP failure checks. A checksum mismatch fails before extraction or execution. Archive
  extraction and `maestro --version` have process deadlines; the setup step has a six-minute cap. An empty or
  invalid archive, missing executable or wrong version fails before the AVD starts, and its bin directory is added
  to `GITHUB_PATH` only after validation. No remote bootstrap shell pipe or changes to shell profiles are needed.
- **Reports.** Each shard uploads its `build/e2e/` as `e2e-shard-<i>` (kept 3 days), whatever its result. The
  `Reports` job runs after all shards, whatever they ended with, unless the run was cancelled (`if: !cancelled()`):
  `e2e-shards.py gather` merges the
  Maestro reports into one `build/e2e/maestro-report.xml` and puts the execution data of every shard and of the
  native tests where `:e2eCoverageReport` reads them; then the coverage report, the summary, one
  Qase run and the `e2e-reports` artifact (merged report, coverage, every shard's folder). A shard that left no report
  — its job failed or timed out before the flows ended — is not lost silently: its flows go into the merged report as
  failures («shard i/n left no Maestro report»), and so does a flow missing from a shard's report. Flows are matched
  to the report by its `file` attribute, or by the flow's `name` when a Maestro version writes none (names are unique,
  `scripts/test_e2e_shards.py` checks it). Maestro is pinned in the workflow (`MAESTRO_VERSION` and
  `MAESTRO_SHA256`): update both from the official release and check the new version with one run.
- **Result.** The run is red when any shard failed, and also when the merged report is not a pass although every job
  was green (a shard's report that never arrived, flows missing or failed — `gather` lists them): the `Reports` job
  fails after it has published everything.


### Default SDK caching and fresh cold AVD (FIN-378)

Normal pushes to `main` and manual runs with the default inputs restore **SDK packages**, then create a fresh cold
AVD for each of the five shards. The requested API/profile/cores/RAM/GPU/disk, KVM, test selection, app-data resets,
native tests and reports/coverage/Qase stay unchanged. No AVD or adb key is stored in these SDK caches. Both manual
inputs, `avd_cache` and `sdk_benchmark`, default to `false`; the latter selects the isolated SDK-only experiment.

`scripts/e2e-sdk-cache.py` creates separate emulator/image compatibility prefixes from runner OS/architecture,
ImageOS, requested package path, the actual audited action SHA read from the workflow and a schema
version. ImageVersion is retained in the sidecar telemetry, but does not fragment package entries across hosted-image
patch updates; AVD snapshot keys still require ImageVersion and full CPU compatibility. CPU features and AVD resources are not SDK package compatibility dimensions. An initially absent package
may restore the latest prefix entry. The helper validates its `source.properties` revision/hash against the matched
immutable key. Missing, failed or incompatible restore removes only that initially absent selected package directory
and proceeds to ordinary online installation. Hosted-only checks, SDK-manager existence and confined, non-symlink
package paths guard this cleanup. A package already supplied by the hosted image skips restore and save, so archives
are never merged over preinstalled files and no extra save cost is introduced for that unmeasured case.

Online SDK license/install checks always run through the explicit SDK-manager path, with 120s/600s subprocess
limits. Final immutable keys contain the actual installed revision/metadata hash. Only shard 1 saves new/updated
packages, before any emulator/tests, with two-minute transfer limits; other shards only read caches. A current hit
is not re-saved; a prefix hit updated online gets a new final key. Restore/save failures are cache failures, not a
reason to retry UI tests. An online SDK failure or direct cold test failure remains red. `cache-matched-key`, rather
than exact-only `cache-hit`, identifies prefix restores. Old package-revision/ImageOS entries can accumulate and must
be considered in the repository cache budget; two current archives measured about 1.82 GB below.

The sidecar `build/e2e/sdk-cache.json` records matched/final keys, actual metadata, `hit-current`, `hit-updated`,
`miss` or `preinstalled`, and restore/install/save phase times. It is included in existing shard artifacts.
`sdk-start` precedes the first restore, so total preparation includes all SDK transfer/install/save plus Android
boot. `preparation.json` labels the normal path `fresh-cold-direct` with AVD cache `disabled`; it cannot activate the
snapshot fallback, and it never saves a post-test snapshot. The authorized full cold-SDK/warm-SDK pair below
passed on the unchanged default code and measured a preparation benefit. Its nearly equal whole-workflow times
do not establish a substantial or stable total UI speedup.

### Optional clean AVD snapshot cache (FIN-378)


Only a manual run with `avd_cache=true` enables this experimental path. The five shard jobs restore independent writable copies of one clean Android API 34 / google_apis / x86_64 /
Pixel 6 AVD. A cache miss creates a fresh AVD, boots Android, checks that no FinAssist app or test APK is installed,
and explicitly saves the named `clean` snapshot. This happens before `e2e.sh` installs anything, signs in or imports
financial fixtures. The action shuts down the emulator; the helper waits for its process to exit and verifies the
snapshot files before `actions/cache/save` uploads the pristine device. Only that AVD directory, its `.ini`, and a generated disposable CI adb key pair/provenance receipt are
cached. Ordinary developer/production adb keys are excluded. Saving is a step **before** the UI tests, never a post-job hook.

On a hit, `force-avd-creation: false` and `-snapshot clean -no-snapshot-save` reuse the restored device. Before the
tests, `scripts/e2e-cache.py` checks that the app is absent and that Android's kernel `boot_id` matches the ID saved
in the snapshot metadata: a silent cold boot must not count as a successful snapshot restore. A failure in cache validation, snapshot creation/sealing, or snapshot boot
can fall back once to a fresh AVD with a cold boot. The marker written immediately before starting `e2e.sh` prevents
an assertion failure, native-test failure or timeout from being retried or hidden as a cache problem. Test runs
never save snapshots or update the shared cache. Existing per-flow app-data resets, native-test orchestration,
coverage, missing-report guards and Qase reporting remain in force.


The [action recipe](https://github.com/ReactiveCircus/android-emulator-runner#usage--examples) preserves adb keys
with the AVD: a restored `adbd` must recognize the host key when the snapshot moves to another runner. This workflow
caches only exact `~/.android/adbkey`, `adbkey.pub` and `finassist-ci-adb.json` paths, never an `adb*` wildcard.
Before restore, the helper explicitly generates a fresh disposable pair; it refuses key operations outside a
GitHub-hosted Actions runner. After restore, it verifies the receipt against both files and restarts the adb server
so an already-running server cannot retain a different key. Sealing also verifies provenance. A cold fallback
creates a new disposable pair. These cache keys authorize only disposable test AVDs; they must never be used for
real devices or as production credentials. Cache contents are not secret storage. The acceptance hit must use a
different hosted runner from the cache creator; a second boot in the same job does not prove key portability.

The requested userdata size remains `AVD_DISK=4096M` for cold creation/fallback. On first setup, the emulator raises
API 24+ userdata to a 6 GiB minimum and writes the effective size to `config.ini`
([primary normalization code](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android-qemu2-glue/main.cpp)).
The action otherwise appends the original requested disk size again on a warm launch, changing the saved hardware
from `6g` to `4g` and invalidating it. `seal` now reads the effective size from the saved `hardware.ini`, validates
its numeric/unit representation and publishes a canonical byte count. Cache metadata retains that count; `validate`
checks it against the saved hardware before exposing the same output on a hit. The warm action uses this explicit
validated output as `disk-size`. Snapshot hardware metadata is never edited and AEMU checks stay enabled. Missing,
invalid, duplicated, overflowing or inconsistent size metadata rejects the cache/preparation. The compatibility key
still contains the requested parameters and image revision; this setup change raises the snapshot procedure version.

The exact cache key includes the installed emulator and system-image `source.properties` (including revisions),
runner OS/architecture and runner-image version, host CPU model/features, all `AVD_*` configuration, the pinned
runner-action SHA and explicit `SNAPSHOT_PROCEDURE` version. A change to the snapshot creation/validation procedure
must bump that version; unrelated reports/comments/Maestro changes do not invalidate a device snapshot. No prefix restore can select another configuration. An absent,
incomplete or mismatched snapshot is discarded and recreated. Cache entries are immutable: if an exact entry is
corrupt, the job recreates a clean device without overwriting it; delete that entry in Actions caches or change the
`clean-avd-v2` namespace to replace it. Concurrent cache misses may race to save the same pristine entry; each shard
still uses its own local device. If a stored or just-published snapshot fails warm preparation, an Actions warning
annotation names its exact key and explains how to remove it or bump the namespace; the summary retains the warning.
A cold fallback is not reported as a successful snapshot hit. Changing configuration invalidates the cache rather than carrying test state forward.

The [runner action snapshot recipe](https://github.com/ReactiveCircus/android-emulator-runner#usage--examples) is
used with the [audited action revision](https://github.com/ReactiveCircus/android-emulator-runner/tree/a421e43855164a8197daf9d8d40fe71c6996bb0d).
Its SDK installer still invokes `sdkmanager` for the emulator and image on every call, even after an AVD hit.
The workflow calls `sdkmanager` through `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` and its helper calls
`adb` through `$ANDROID_HOME/platform-tools/adb`: these preparation steps precede the action that adds SDK tools
to `PATH`. It prepares these packages before computing the key, and checks their fingerprint again before saving
or using a snapshot. The separate SDK package cache now runs first, including online revision checks; an AVD hit
alone does not avoid SDK downloads. The experiments below distinguish SDK transfer from AVD snapshot cost. The existing GPU mode is retained; Android's
[snapshot documentation](https://developer.android.com/studio/run/emulator-snapshots) warns about software-rendering
reliability. The boot-ID check and one cold fallback keep the tests runnable; actual snapshot compatibility and
performance on our hosted runners are assessed below.

#### Measurements and acceptance limits

`build/e2e/preparation.json` and each shard's job summary record SDK preparation, cache restore/validation,
cold-device boot, snapshot creation/shutdown, cache save, test boot (and cold fallback when needed), and the complete
`e2e.sh` execution separately. Action step durations additionally include its repeated SDK checks, AVD setup and
teardown. Use the Actions job/step start and finish times for those costs and the workflow's critical-path duration;
do not sum parallel shard durations. Preparation data is retained with the existing shard artifacts.

Before a warm launch, `seal` (miss) or `validate` (hit) retains sanitized expected hardware metadata in
`preparation.json`, because the emulator may remove an incompatible snapshot. A boot-ID mismatch records changed
hardware key names, safe numeric/bool/enum previews and path counts against `hardware-qemu.ini`. Paths, serials,
secret/key/token values and arbitrary string contents are excluded; opaque non-secret strings retain only an
equality digest. This is raw INI telemetry before default stripping, never a replacement for AEMU's compatibility
checks. After a failed/skipped warm step, a small optional `e2e-preparation-<shard>` artifact is uploaded before cold
fallback, so the diagnostic can be read while tests run. Existing `e2e-shard-*` reports, gather and Qase are unchanged.
Telemetry alone does not change the snapshot procedure/cache key or emulator configuration.

The existing successful [baseline run 40944955977](https://github.com/fin-assist/android/actions/runs/40944955977)
used commit `ee17c3a`, the same API/profile/resources and four shards. Its workflow took 22m38s. Approximate stage
boundaries below come from the existing timestamped logs; the SDK column ends at `avdmanager create`, boot is
`Starting emulator` → `Emulator booted`, preparation is the UI action start → the `e2e.sh` command, and scenarios end
at the action's immediately following emulator-kill command (including script cleanup). This is historical evidence,
not a post-change speedup measurement.

| Shard | SDK before AVD creation | Android boot | Total preparation | Scenarios + script cleanup |
|---|---:|---:|---:|---:|
| 1 | 44.6 s | 39.0 s | 92.2 s | 984.9 s |
| 2 | 87.5 s | 28.8 s | 122.7 s | 879.0 s |
| 3 | 51.1 s | 32.6 s | 93.1 s | 930.1 s |
| 4 | 52.9 s | 28.5 s | 88.1 s | 910.2 s |

The first authorized [attempt 40952526793](https://github.com/fin-assist/android/actions/runs/40952526793) on
`52f6647` stopped in all four shards before cache restore, emulator startup or UI execution:
`Prepare SDK and fingerprint the AVD` called bare `sdkmanager`, which was absent from the hosted runner's PATH
(exit 127; shard 1 at `2026-10-29T20:51:58Z`). The explicit SDK paths above fix this preparation failure. That
attempt created no AVD cache and provides no cache/boot/test timing sample or evidence of speedup.

The [second attempt 40953561096](https://github.com/fin-assist/android/actions/runs/40953561096) on `9152d38`
passed SDK setup and disposable-key initialization in all shards. However, all shards rejected the saved snapshot
in `seal`, skipped cache publication/warm boot and entered cold fallback. The owner cancelled that invalid benchmark
instead of spending the full UI run on fallback. The structural guard incorrectly required optional mapped RAM
`ram.img`; a named snapshot stores RAM in `ram.bin` ([AEMU file-name constants](https://android.googlesource.com/platform/hardware/google/aemu/+/refs/heads/main/snapshot/include/snapshot/common.h)).
The guard now requires nonempty `snapshot.pb` and `ram.bin`, and reports only relative file names/sizes if sealing
fails. The procedure version is bumped; GPU, API/profile/resources and test selection are unchanged. This cancelled
attempt created no AVD cache and does not establish cache-hit performance or complete UI correctness.

The [diagnostic run 40958700437](https://github.com/fin-assist/android/actions/runs/40958700437) restored existing
caches in all four shards and identified exactly one changed field, `disk.dataPartition.size`, with equal path counts.
The captured snapshot size was `6g`, while repeated action setup reapplied requested `4096M`. The owner cancelled
after the early diagnostic artifacts arrived. The effective-size output/input correction above follows that evidence
and the emulator's first-setup normalization; it preserves the already-used 6 GiB resource size and does not change
GPU, RAM, cores or snapshot hardware metadata. The full samples below use the corrected procedure.

Two complete authorized samples of the earlier AVD-enabled default used the same `ad230b4556ae67cf6457493c885baab201d91fa6`, configuration and
selection without a code change between them:

- [Empty-cache run 40960467777](https://github.com/fin-assist/android/actions/runs/40960467777),
  21:45:39–22:10:29 UTC, 24m50s: four misses created/saved clean snapshots, then all four test boots verified them.
- [Second run 40963920355](https://github.com/fin-assist/android/actions/runs/40963920355),
  22:11:28–22:33:37 UTC, 22m09s: only shard 4 restored an exact existing key (created by first-run shard 2);
  shards 1–3 missed. Both runs passed all 55 Maestro scenarios and the one native test, with no skipped tests,
  no cold fallback and nonempty merged coverage. All eight boots logged successful loading of named `clean` and
  passed the helper's boot-ID continuity guard. The two artifacts do not independently expose both UUID values.

Seconds below use helper epoch marks. Restore includes disposable-key initialization/validation; cold creation
includes snapshot save and process-exit sealing. Repeated action SDK checks and other step overhead also contribute
to total preparation, so the listed subphases do not sum exactly to it. E2E includes APK install, scenarios, native
execution where selected, and script cleanup; it is separate from preparation.

| Run / shard | SDK | Restore | Cold creation + snapshot | Cache save | Test boot | Total preparation | E2E |
|---|---:|---:|---:|---:|---:|---:|---:|
| miss 1 (miss) | 71.1 | 1.5 | 62.4 | 15.4 | 11.1 | 175.7 | 1090.5 |
| miss 2 (miss) | 45.6 | 0.4 | 51.8 | 11.3 | 10.6 | 128.7 | 864.9 |
| miss 3 (miss) | 56.5 | 1.4 | 56.8 | 30.4 | 10.8 | 169.1 | 1043.9 |
| miss 4 (miss) | 55.9 | 1.1 | 54.8 | 15.6 | 10.6 | 151.9 | 986.8 |
| warm 1 (miss) | 37.6 | 0.9 | 45.4 | 17.5 | 13.9 | 130.6 | 985.5 |
| warm 2 (miss) | 48.4 | 0.9 | 44.6 | 10.1 | 14.0 | 133.5 | 958.9 |
| warm 3 (miss) | 51.7 | 1.4 | 47.9 | 27.9 | 10.8 | 151.9 | 931.4 |
| warm 4 (hit) | 87.1 | 38.8 | 0.0 | 0.0 | 11.8 | 143.1 | 906.5 |

The exact hit restored about 2.07 GB in 38.8s before its 11.8s test boot. Its total preparation was 143.1s,
compared with historical cold preparation of 88.1–122.7s. Second-run total wall time was 29s shorter than the
historical 22m38s, but the historical revision/hosts differ and scenario durations vary. These data **do not
establish net preparation acceleration**. A fast snapshot boot alone does not meet the performance goal.

Initial SDK preparation still downloaded emulator build `16740729` and system image `34_r14`; it took 37.6–87.1s
across the eight jobs. The action's additional SDK checks took 4.3–6.9s per call without download-progress lines
(two calls after each miss, one after the hit). AVD archives therefore do not remove the measured SDK download
cost. A separate package-cache A/B is the next experiment, rather than assuming it will help.

Four initial immutable AVD entries occupied 9,064,256,718 compressed bytes. The second run generated two distinct
new keys (shards 1 and 2 shared one); the after-run inventory contained five AVD entries totaling 11,547,739,495
bytes because one old entry had disappeared. During the measured window, 18 other entries (17 Gradle and one
Robolectric), totaling 3,026,967,063 bytes, disappeared from the repository inventory. The API does not state why
an entry vanished, so this is an observed cache-pressure risk, not proof of a specific eviction cause. The configured
repository quota could not be read with the available API access; uncompressed AVD sizes were not captured.
The owner removed only those five generated branch AVD entries after inventory capture, leaving all nine other
entries untouched. Full key/ID/size inventories and compact per-shard evidence are retained outside the repository;
large logs and archives are not committed.

Exact key differences caused three second-run misses. The current telemetry does not retain sanitized fingerprint
components, so CPU fragmentation is not established as their cause. The conservative CPU model/features guard stays
in place. These AVD-enabled samples verify functionality and cross-runner restoration, but do not justify default
snapshot caching because its acceleration, hit rate and cache budget are unfavorable or unresolved. The default
therefore uses SDK caching and a fresh cold AVD; its completed acceptance is recorded below.

### Opt-in SDK package experiment

`e2e.yml` has a `workflow_dispatch` boolean `sdk_benchmark`, default `false`. Only an explicitly selected manual
run routes to reusable `sdk-preparation-benchmark.yml`; UI shards, reports and Qase are skipped. Normal main pushes
and default manual UI runs use SDK caching plus direct cold AVD boot. A separate concurrency suffix prevents the SDK experiment
from cancelling an ordinary UI run. This routing uses the already registered workflow because GitHub requires a
manual workflow to exist on the default branch ([dispatch documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)).
The owner authorized one SDK-only dispatch; its completed results are below. Any additional benchmark or UI
run still requires separate explicit permission.

The experiment starts no emulator, APK, Gradle, Maestro or native test. One seed job installs the current emulator
and selected API 34 image with online `sdkmanager`, then saves two separate immutable package archives. Eight fresh
jobs follow: four baseline and four warm. Only seed writes caches; warm requires both exact hits, and baseline
never restores. The keys include the experiment schema, run ID, package identity, installed revision/metadata hash,
runner OS/architecture and hosted-image version. No CPU/AVD snapshot keys are reused. The run ID bounds the two
entries for later owner-directed cleanup; these experiment keys are isolated from the stable default SDK keys.

All nine runners perform the same disk-space preparation before measurement. Before deleting a package, the helper
checks that SDK manager exists. Before each arm, it verifies it is on a disposable GitHub-hosted runner, records the hosted preinstalled
package revisions, and removes only the emulator and this one image directory. It rejects symlink escapes. This
measures **empty selected SDK packages versus restored packages**, not the average benefit when a hosted image
already has current packages. Platform-tools, command-line tools, AVDs and adb keys are not deleted. Every arm
accepts licenses and runs the same online `sdkmanager --install platform-tools emulator <image> --channel=0`
([SDK manager documentation](https://developer.android.com/tools/sdkmanager)); a cache never suppresses update checks.
Installed package revisions and `source.properties` hashes, plus runner ImageVersion, must equal seed metadata or
that comparison fails while preserving the actual installed manifest in its artifact. A package/image update or heterogeneous hosted image therefore invalidates the sample
instead of producing a misleading speedup.

Each job has a 15-minute limit; license and install subprocesses have 120s/600s limits. Three-day metadata-only
artifacts retain before/after revisions, runner identity, free bytes before/after package cleanup and install,
restore/setup, SDK manager, save/step and total durations,
and logical package bytes. Individual cache transfer times are available in Actions step logs; compressed bytes
come from the two exact cache entries after execution. Review total preparation distributions across four runners,
seed/save cost, package sizes, update-check cost and repository cache pressure. The measured result supports the
SDK package cache above; the separate full default UI acceptance follows below. This experiment alone cannot
establish full E2E speedup.


The authorized [SDK-only run 40971458743](https://github.com/fin-assist/android/actions/runs/40971458743) used
`3df7a93fe1a8fe4176915ae8a1b4018458ee34e7` and completed successfully on 2026-10-29 at 23:15:44 UTC:
23:11:59–23:15:44, 3m45s including seed, job setup, parallel measurements and metadata upload. All nine SDK jobs
passed; ordinary UI shards and Reports were skipped. Each warm job restored both exact run-scoped entries; none
of the baseline jobs restored a cache. All nine manifests matched: Linux/X64, ubuntu24 hosted image
`20261026.354.1`, emulator `37.2.13`, system-image revision `14`, platform-tools `37.0.1`, command-line tools `12.0`,
and identical package `source.properties` hashes. Both selected packages were absent before cleanup on all nine
runners, so package deletion did not change their observed starting state in this series.

| Arm / shard | Restore + setup | Online SDK manager + metadata | Save + step overhead | Measured total |
|---|---:|---:|---:|---:|
| Seed | 0.1 s | 47.3 s | 19.1 s | 66.4 s |
| Baseline 1 | 0.1 s | 40.9 s | 0.1 s | 41.0 s |
| Baseline 2 | 0.1 s | 38.3 s | 0.1 s | 38.4 s |
| Baseline 3 | 0.0 s | 77.6 s | 0.1 s | 77.7 s |
| Baseline 4 | 0.0 s | 50.5 s | 0.0 s | 50.5 s |
| Warm 1 | 28.8 s | 8.6 s | 0.0 s | 37.4 s |
| Warm 2 | 19.0 s | 8.3 s | 0.1 s | 27.3 s |
| Warm 3 | 31.8 s | 7.7 s | 0.0 s | 39.5 s |
| Warm 4 | 31.5 s | 5.5 s | 0.2 s | 37.1 s |

Total measurement starts after identical disk/package cleanup and excludes checkout, Java setup, artifact upload
and post-job hooks. Baseline and warm jobs ran concurrently after seed on different unpaired runners. Mean measured
total was 51.94s baseline versus 35.34s warm (16.60s / 32.0% lower); medians were 45.78s versus 37.23s
(8.55s / 18.7% lower). Baseline's 77.7s tail affects the mean. Warm online SDK checks remained enabled and took
5.5–8.6s, with no package-download progress in their logs. This single batch supports an SDK transfer benefit for
these versions/hosts; it does not establish a stable distribution, savings when packages are preinstalled, or full
UI workflow improvement. The one-time 19.1s seed-save cost and package updates must also be accounted for.

The emulator package was 903,310,069 logical bytes and 334,907,506 compressed cache bytes; the system image was
4,469,102,280 logical and 1,482,666,423 compressed bytes. The two immutable archives totaled 1,817,573,929 bytes
(~1.82 GB), rather than duplicating one system image across CPU-specific AVD entries. All nine pre-existing repository
cache entries (941,825,348 bytes) remained present in the during-run inventory. Cache IDs, exact keys, manifests,
per-job phase/step times and metadata artifact URLs are retained in the external compact evidence. After all reads the owner removed only those two experiment entries; the exact nine previous entries and bytes
remained unchanged. The implementation therefore adopts SDK caching plus fresh cold AVD as the default and retains
snapshot caching as manual opt-in. This change preserves online revision checks and includes package-update/cache
costs in telemetry. The completed default UI pair below supplements this SDK-only experiment; neither result
reverses the failed/unconfirmed AVD snapshot preparation result.

### Full UI acceptance of the SDK default (2026-10-30)

The owner authorized exactly two full UI runs on unchanged `5d34a342d9c6910e3bea62d021cc3f9cb0de96eb`, with
`sdk_benchmark=false` and `avd_cache=false`. The SDK namespace was empty before the first; the second began only
after the first completed and its actual merged coverage XML was checked.

- [Cold SDK run 41002845291](https://github.com/fin-assist/android/actions/runs/41002845291):
  04:25:50–04:48:36 UTC, **22m46s**. All eight package classifications were `miss`; only shard 1 saved the two
  SDK entries before tests. Shards 2–4 performed no cache writes.
- [Warm SDK run 41005104529](https://github.com/fin-assist/android/actions/runs/41005104529):
  04:50:25–05:13:02 UTC, **22m37s**. All eight package classifications were `hit-current`, each matched key equaled
  the actual final immutable key, and all SDK save steps were skipped. These are validated **prefix restores**:
  `actions/cache` may report exact-primary-key `cache-hit=false` and still have restored a usable matched key.

Both workflows and all eight shard jobs passed. Each run produced 55 Maestro cases (13/14/14/14) plus one native
case, with no failure, error or skipped case. Reports passed; both actual merged coverage XML files were nonempty
and contained 1,633 classes (112,484 covered instructions cold; 115,461 warm). Qase was skipped because these were
manual runs, as designed. The SDK-only benchmark job and all optional snapshot steps were skipped. All eight boot
modes were `fresh-cold-direct`, AVD cache was `disabled`, and logs contained no successful named-snapshot load.
The running emulator was `37.2.13.0`, build `16740729`, in all eight jobs; installed emulator revision `37.2.13`,
image revision `14`, and both package metadata hashes matched across the pair and the SDK-only benchmark.
Runner telemetry was Linux/X64, ImageOS `ubuntu24`, ImageVersion `20261026.354.1` throughout this pair.

Seconds below use `preparation.json`: SDK is `sdk-start` → `sdk-ready`, including **all** restore/install/save and
helper CLI overhead; preparation is `sdk-start` → `tests-started`. Boot is the pre-emulator callback → helper
readiness mark, including launch/readiness overhead. APK build uses the earlier Actions `Build APKs` step. E2E is
`tests-started` → `tests-finished`, including APK installation, Maestro/native execution and script cleanup.
These stages have different boundaries; do not sum parallel jobs into workflow wall time.

| Sample / shard | SDK inclusive | Cold boot | Total preparation | APK build | E2E |
|---|---:|---:|---:|---:|---:|
| Cold SDK 1 | 62.9 | 39.4 | 110.8 | 76 | 1018.0 |
| Cold SDK 2 | 79.4 | 48.6 | 136.5 | 77 | 961.0 |
| Cold SDK 3 | 52.0 | 40.6 | 99.5 | 61 | 923.6 |
| Cold SDK 4 | 53.2 | 40.5 | 100.9 | 72 | 940.2 |
| Warm SDK 1 | 40.1 | 33.3 | 79.2 | 54 | 844.5 |
| Warm SDK 2 | 28.4 | 47.1 | 84.2 | 75 | 965.4 |
| Warm SDK 3 | 38.3 | 27.0 | 70.5 | 50 | 880.3 |
| Warm SDK 4 | 40.5 | 40.1 | 90.1 | 85 | 1024.0 |

The sidecar's inner SDK phases are listed separately below. They start inside `e2e-sdk-cache.py` and exclude about
0.1s of surrounding CLI overhead, so they must not be substituted for the inclusive SDK column above. The final
phase includes step overhead even when no cache is saved.

| Sample / shard | Restore + recovery | Online install + metadata | Save + step overhead |
|---|---:|---:|---:|
| Cold SDK 1 | 0.8 | 40.3 | 21.6 |
| Cold SDK 2 | 0.8 | 78.4 | 0.1 |
| Cold SDK 3 | 0.7 | 51.1 | 0.0 |
| Cold SDK 4 | 0.7 | 52.5 | 0.0 |
| Warm SDK 1 | 32.7 | 7.3 | 0.0 |
| Warm SDK 2 | 17.6 | 10.7 | 0.0 |
| Warm SDK 3 | 33.1 | 4.9 | 0.3 |
| Warm SDK 4 | 26.6 | 13.8 | 0.1 |

In this pair, mean total preparation decreased **111.92 → 80.98s** (30.94s / 27.6%), median **105.83 → 81.69s**
(22.8%); preparation decreased in all four shards. Mean inclusive SDK time decreased **61.86 → 36.87s**
(25.00s / 40.4%), median **58.05 → 39.24s** (32.4%). The initial cold writer paid 21.61s for save/step overhead;
that one-time population cost is included in this comparison and recurs when new SDK entries are needed.
Warm online install/checks remained enabled and took 4.9–13.8s; restore/recovery took 17.6–33.1s. The action also
performed its normal additional SDK check once per shard (3.7–7.1s cold/warm, no download-progress lines there).

This is one same-code pair on unpaired hosted runners, not a stable latency guarantee. Cold-boot timing also
varied (mean 42.28 → 36.88s), and APK build happened before SDK caching. Do not attribute those changes to cache.
E2E duration changed by −173.6/+4.4/−43.3/+83.9s per shard; the critical shard changed from 1 to 4. Consequently,
whole-workflow time improved by only **9s (0.66%)**, 22m46s → 22m37s. The measured result supports SDK/default
**preparation benefit in this pair**, not a substantial or causal whole-UI speedup. Historical 22m38s on `ee17c3a`
is a different revision/time/host sample, not an A/B control. Cross-ImageVersion SDK-key behavior has unit coverage;
these runtime samples all used one ImageVersion.

The two actual production SDK entries occupied **1,817,575,191 compressed bytes**: emulator 334,908,796 and image
1,482,666,395. Use these API values rather than the earlier SDK-only archive sizes. Production logical bytes were
not independently measured; 903,310,069 + 4,469,102,280 bytes from the benchmark are only a matching-revision/hash
reference. Exactly two entries existed during the pair; all nine pre-existing entries (941,825,348 bytes) retained
their IDs and sizes. After all artifacts/metadata were captured, the owner removed only the two generated branch
SDK entries (IDs `9739015526`, `9739020978`); all nine prior entries remained unchanged. Main cannot consume those
branch-only entries. Future SDK/ImageOS/action/schema updates can add immutable entries, so long-term cache budget
and eviction behavior still require monitoring; this pair showed no loss of the prior entries.

Compact per-run evidence, exact key/ID inventories, artifact URLs, phase/step timings, comparison methodology and
owner cleanup receipts are retained outside git; large raw logs and archives are not committed. Runtime acceptance
of this default is complete for the authorized pair. Any further SDK/UI run needs separate owner permission;
merge still requires the owner's explicit instruction after the latest CI/review checks.

## Qase

Every scenario is a test case in Qase, project `PLAINFIN`, suite «UI-автотесты (Android)» (one sub-suite per feature):
steps and expected results in Russian, marked automated.

- `scripts/qase-cases.json` maps tests to cases: a Maestro flow by its path (`.maestro/flows/<feature>/<flow>.yaml`),
  an instrumented test by `<class>#<method>`.
- After a merge to `main` the `UI tests` workflow sends the results as one Qase run
  (`scripts/qase-report.py`: JUnit reports → run → bulk results → complete) — one for all shards, from the merged
  report. The run title is `UI tests · <branch> · <sha>`, its description links the Actions run. Cancelled runs are
  not sent, including a run where a shard was cancelled: its partial results would read as lost coverage. A shard
  that failed without a report sends its flows as failed (see «Shards in CI»).
- Statuses: passed, failed (an assertion `failure` and an unexpected `error` alike: in UI tests the latter is usually
  an app crash), skipped. A test found in several reports keeps its worst result.
- If the results cannot be recorded, the script retries once (429, 5xx, network) and otherwise deletes the empty run.
- The token is the repository secret `QASE_API_TOKEN`. Without it the step prints a notice and passes; a Qase error
  does not fail the job either.
- Check what would be sent without a token: `scripts/qase-report.py --dry-run build/e2e/maestro-report.xml`.

## The `e2e` build type

`assembleMockE2e` builds `ru.finassist.pf.mock.e2e`: the mock flavor plus test hooks from `app/src/mockE2e/`. It
installs next to `mockDebug` and never exists for `local` or `prod`. It is also the `testBuildType`: the native tests run on
it, and only it carries the JaCoCo probes (docs/testing.md, «UI test coverage»).

- **Pinned date.** "Now" starts at 2026-10-08 12:00 MSK (`BuildConfig.E2E_NOW`, `ShiftedClock`) and runs from
  there — for the mock backend and the screens alike (`LocalPfClock`), whatever the emulator's date. The fixture statement covers October 2025 — September 2026, so periods, amounts and limits stay the same
  whenever the tests run.
- **Fixture only.** The fake backend always loads `statements/fixture.ofx`, never a private statement on the
  machine (`E2eModule`). Delays are short: the call «arrives» in 1.5 s, imports and answers stream fast.
- **Registration open.** `assets/flags.json` is empty, so flags have their code defaults; the existing account is
  `+7 916 123-45-67`, any other number registers.
- **Live stream probe (FIN-380).** The non-exported `E2eStreamProbe` provider installs a read-only native marker
  on MainActivity, only in `mockE2e`. It observes the existing process-wide `MockBackend.streamRateLimited`
  StateFlow while the activity is started. Before any real stream 429 the marker is hidden; afterwards its native
  resource id is `e2e_stream_rate_limited` and its text is `e2e.stream.rate_limited=<count>`. It neither opens nor
  pauses an activity, writes backend state or exposes user data. Destroying the activity cancels collection;
  resuming does not add a second marker, and recreation reads the current process count. Flows clear app data
  first, so a previous scenario cannot satisfy the wait. Ordinary mock, local and prod builds have no probe.
  `auth/call_stream_rate_limited` waits up to 10 s for the exact marker text `e2e.stream.rate_limited=1` before checking the call number
  and absence of ErrorMessage/offline text. If the fixture stops issuing 429, this wait fails instead of silently
  passing. The flow uses `stream_drops=1&stream_rate_limits=100&stream_retry_s=30`: the longer server pause
  accommodates the 4 s control-link activity. It checks exactly one 429 across a control-link resume, removes
  future refusals and allows up to 45 s for the retained deadline plus jitter and sign-in. No fixed sleep is used.
- **File picker.** The upload screen opens a list of bundled files instead of the system picker
  (`E2eDocumentPickerActivity`, see `PickStatementDocument`). Files in `app/src/mockE2e/assets/e2e/statements/`:

  | File | Result |
  |---|---|
  | `fixture.ofx` | the preloaded statement again: nothing new |
  | `october.ofx` | 3 new operations, 3–6 October 2026 |
  | `statement.csv` | `CSV_NOT_ACCEPTED` |
  | `notes.txt` | `WRONG_FORMAT` |
| `unreadable.ofx` | no readable operation, 2 unread lines (`EMPTY_STATEMENT`, `unread_count = 2`) |
  | `unread-same-line.ofx` | 2 unread STMTTRN/FITID occurrences on physical line 31, both with no date: equal line numbers and reasons remain separate rows (FIN-352) |
  | `unreadable-fixed.ofx` | the two operations of `unreadable.ofx` read correctly (same account and FITID): closes its unread lines (FIN-79) |
  | `no-description.ofx` | 1 new operation without a description (7 October 2026): «Без описания» in the feed, no unread line |
  | `stale.ofx` | 3 operations, statement period 1 August — 20 September 2026, the last operation on 15 September: once the preloaded statement is deleted, the data is stale on the pinned date — the banner says «по 20 сентября» (FIN-125) |
  | `other-bank.ofx` | `WRONG_BANK` |
  | `zz-history-period.ofx` | one operation on 25 September, statement period 1–30 September 2026 |
  | `zz-history-fallback.ofx` | no statement period: history falls back to the operation day, 25 September 2026 |
  | `zz-history-empty.ofx` | no statement period or operations: history shows the count without a date label |

- **Control link.** `pfe2e://config?…` changes state from a flow (`E2eControlActivity`). Open it through the subflow,
  not a bare `openLink`: `runFlow: {file: ../../subflows/config.yaml, env: {LINK: "pfe2e://config?…"}}`. The activity
  writes the values, shows `e2e.config.applied` and finishes; the subflow waits for that, so a `launchApp` right
  after cannot cut the writes short. Maestro counts the timeout of every wait from the last interaction (here
  `openLink`), not from the start of the wait: the wait for the tag to go must cover the wait for it to come (a
  `sign_in` takes several seconds) plus the 4 s it stays up — `config.yaml` sets both accordingly (FIN-180).

  | Parameter | Effect | Lifetime |
  |---|---|---|
  | `flag.<key>=true\|false\|default` | feature flag override, keys from `docs/flags.md` | app data (wiped by `clearState`) |
  | `flags=reset` | drops all overrides | — |
  | `offline=true\|false` | every backend request fails with «нет сети» | process |
  | `call_delay_ms=<n>` | how long the sign-in call takes | process |
  | `call_ttl_ms=<n>` | lifetime of the next phone verification requests (`expires_at`, `expires_in`; 5 minutes by default): the wait ends without a call (FIN-166) | process |
  | `network_delay_ms=<n>` | latency of every backend request (a «busy» state stays up for that long) | process |
  | `consent_type=personal_data\|assistant` | required scope for every consent command below; unknown/duplicate/malformed controls fail closed | command |
  | `consent_publish=<version>` | publishes the version checked by Start/assistant grant; does not replace captured Start evidence or silently grant consent | process |
  | `consent_document=<version>\|current` | override the next document responses independently from publication; `current` removes the override; versions are 1..64 ASCII letters/digits/`.`/`_`/`-` | process |
  | `consent_read=ready\|offline\|rate_limited\|held`, `consent_retry_s=1..60` | closed GET fixtures; 429 uses canonical `retryable:false`, a fresh Retry-After and captured receipt deadline; held GET captures its reply before waiting | process |
  | `consent_release=true` | releases currently held reads; subsequent held-mode reads return normally | process |
  | `consent_register_error=outdated\|required\|off` | personal-data scope only: reject Register before creating an account, or restore normal validation | process |
  | `expect_consent_reads`, `expect_consent_active`, `expect_consent_max_active` | exact document request/concurrency counts for the selected type, each 0..128 | instant check |
  | `expect_consent_starts`, `expect_consent_registers`, `expect_consent_grants` | exact comma-separated attempted version sequence, including failures; an empty value requires zero attempts | instant check |
  | `expect_consent_start_keys=0..128` | exact number of distinct Start keys; no raw key, phone or token is exposed | instant check |
  | `expect_consent_wait_elapsed=true` | requires a real prior document-429 receipt and zero remaining captured wait; missing receipt fails | instant check |
  | `unknown_codes=true\|false` | the fake server sends codes the client does not know (`MockCodes`): a filter `kind` on the expense tile, an import notice, an analytics feature, an Analytics block of an unknown type and a card locked for an unknown reason (FIN-83, FIN-4) | process |
  | `analytics_recalculating_responses=<0..100>` | next n valid analytics reads carry `calculation.recalculating=true` and the matching legacy state flag when present; auth/validation still apply; no durable-delivery simulation (FIN-416) | process |
  | `failing_blocks=<id>,…` | those Analytics blocks fail on the fake server and come as `unavailable` (FIN-5); empty — none | process |
  | `assistant_model_errors=<n>` | the next n assistant answers fail with MODEL_ERROR («Повторить» under them) | process (counts down) |
  | `stream_drops=<n>` | the next n openings of an SSE stream (call, import progress, answer) close after their first event without a terminal one; the app re-opens them with a pause (FIN-170) | process (counts down) |
  | `stream_rate_limits=<n>` | the next n stream openings that follow a dropped one answer 429 `RATE_LIMITED` with `Retry-After: 3`; the app waits and re-opens without showing the server's RATE_LIMITED message («Слишком много запросов. Попробуйте позже» in mock; FIN-170, FIN-376) | process (counts down) |
  | `stream_retry_s=<n>` | `Retry-After` of those 429 answers, seconds (3 by default; a long one keeps the app waiting) | process |
  | `expect_stream_rate_limited=<n>` | a check, instant: `e2e.config.applied` shows only if the fake server has already turned away at least n stream openings with 429, else `e2e.config.unmet` and the flow fails; the app is paused under the link and its stream openings run on the main thread the link holds, so the openings must have happened before it | — |
  | `expect_stream_rate_limited_exact=<n>` | requires exactly n stream 429 responses; catches an early opening when returning during the original wait | — |
  | `min_version=<MAJOR.MINOR.PATCH>` | the fake server's minimum client version; below it every request and every stream opening gets `CLIENT_VERSION_UNSUPPORTED` and the app shows the update screen (FIN-22); empty — none | process |
  | `maintenance=<minutes>\|unknown\|off` | every request and every stream opening gets 503 `MAINTENANCE` (a stream already open is cut at its next event) ending in that many minutes of the fake «now» or at an unknown time; `off` ends it (FIN-22) | process |
  | `maintenance_retry_after=<s>` | `Retry-After` of the maintenance answer, 15 s by default (the shortest the app waits) | process |
  | `pending_upload_fixture=october` | seeds an unknown-age pending UUID under the current content-and-raw-name hash without an elapsed anchor; simulated restoration, not a process restart | app data |
  | `expect_uploads=<n>` | requires exactly n uploads, including the preloaded statement; unchanged baseline proves no upload happened before explicit new intent | — |
  | `profile_timezone=<zone id>` | `profile.timezone` on the fake server, as if changed elsewhere; the app adopts it on the next profile read (opening «Профиль») | process |
  | `sign_in=existing` | signs the existing account in and sets passcode 1234 without the screens (`E2eSignIn`, FIN-180): applied after the other values of the link, finishes before `e2e.config.applied` shows; `e2e.config.unmet` when already signed in or the sign-in fails. Use it through `fast_login.yaml` (see «Signing in») | app data (session, passcode); unlocked while the process lives |

  Flags are read when a screen opens: set them before the screen (usually right after `clear_state.yaml`, before
  `launchApp`). Process-scoped values are set after `launchApp`.
- **Coverage data.** `E2eCoverage` (a content provider) saves the JaCoCo execution data of the process every 2 s
  while it changes and when an activity stops, into shared storage (`Download/pf-coverage`), where `clearState`
  does not reach; `scripts/e2e.sh` pulls it after the flows. Native tests go through the test storage service
  instead (`useTestStorageService`). The system picker contract that the native test used to check on `mockDebug`
  is now a Robolectric test (`PickStatementDocumentTest`): on the e2e build the picker is `E2eDocumentPickerActivity`.

### Supplemental observations of actual mock requests (FIN-450)

UI assertions remain the oracle for visible behaviour. `MockUiEvidence`, in the mock backend only,
records actual API entry attempts before offline/consent/error checks. `E2eRequestControl` in mockE2e
compares closed `pfe2e://config` expectations with that witness. The control never calls a financial
API to produce the expected result. A repeated failed call counts even when history and quota remain unchanged.

| Control | Observation |
|---|---|
| `expect_assistant_asks`, `_ask_keys`, `_retries`, `_retry_keys`, `_retry_targets` | attempted Ask/Retry calls and distinct private keys/targets; only counts are returned |
| `expect_assistant_questions`, `_answers`, `_off_topics` | actual stored history; replaced answers excluded |
| `expect_statement_upload_requests`, `_upload_keys`, `_delete_requests` | actual API attempts, including offline failures; preloading is not a client upload request |
| `expect_operation_details_requests`, `expect_category_requests` | actual details/category attempts |
| `expect_operation_details_timestamp_exact`, `expect_category_timestamp_exact` | full ISO offset timestamp equals the ledger details or the last actual details response for the same operation; no truncation of seconds/offset/subseconds |
| `expect_phone_ttl_seconds`, `expect_phone_fresh_deadline` | actual verification lifetime and advancement of the returned deadline after a second Start |
| `expect_phone_wait_hhmm=HH:mm` | clock label copied from the actual Call screen, compared with the last response's rounded `expires_in` in the device zone; the direct in-process handoff is bounded to one second at a minute boundary |
| `expect_maintenance_probes`, `expect_maintenance_probe_after_deadline` | actual personal-data probe entries relative to the latest observed maintenance refusal |
| `expect_stream_reopen_after_deadline` | every actual stream opening after a 429 occurs at/after that refusal's captured elapsed deadline, even after future refusals are removed |
| `expect_analytics_dismiss_requests` | all actual dismiss attempts; separate from the injected-failure counter |
| `expect_statement_pending_key_retained`, `expect_statement_upload_key_fresh` | Qase51's closed bundled fixture retains its unknown-age UUID until explicit restart, then uploads using another UUID |

Counts accept one integer in 0–128 (phone TTL: 0–3600), predicates only `true`, phone labels only `00:00`–`23:59`. Unknown names in
these namespaces, duplicate values, overflow or unobserved predicates fail closed with `e2e.config.unmet`.
Private sets/maps are bounded to 128 entries and 256 characters per key. Controls expose no raw keys,
request targets, user text or timestamps. Clear-state/process recreation resets the witness. The seeded-key
retention check reads `IdempotencyKeys.pendingKey`; it cannot create or renew a pending key. These are
same-process mock observations, not evidence of real server survival after application death.

The control activity pauses/resumes the app. Place observations after the relevant visible assertions and
avoid inserting them where lifecycle changes would alter the scenario (in particular active dictation).
Timing predicates are supplemental to the visible wait/recovery and JVM retry-state tests; they do not
claim access to the client's private deadline fields.

FIN-450's full clause audit is in [qa/qase-parity](qa/qase-parity/). Its original source snapshot and
newly authored checks are distinguished from baseline Qase85 execution. The full post-merge run on
`f072ba67` is [Qase98](https://app.qase.io/run/PLAINFIN/dashboard/98): 79/79 Maestro and12/13 native,
89/90 cases Passed. Its sole failure is Qase17's native palette companion. After revealing the stable
theme row before the exact-label wait, both required Qase17 components passed locally on API34 in
[Qase100](https://app.qase.io/run/PLAINFIN/dashboard/100); initial local failures remain in Qase99.
The original15min deadline was respected, emulator stopped, and no full-suite local rerun occurred. Actual results and report hashes: [postmerge-283.json](qa/qase-parity/postmerge-283.json).
Historical authored/not-executed notes below describe the original changes; current FIN-450 execution is in
that report. Qase77–79 retain separate API35 acceptance in [execution.json](qa/qase-parity/execution.json);
normal API34 CI is not API35 acceptance.
Qase17 retains its Maestro flow and adds `ProfileThemePaletteTest#darkChoiceChangesTheRenderedProfilePalette`
for the actual MainActivity's rendered light/dark palette. Both map to Qase17; the reporter keeps their worst
result when both components are present. If any mapped component is missing, an otherwise passing case is
reported Skipped with the missing test named; a reported failure stays Failed. A focused component run must
identify its limited scope in the description and cannot establish a full-case Passed by itself.
Qase84 likewise combines its Maestro calendar/input/error flow with
`StatementPickerTest#failedDownloadKeepsSelectedDatesAndDoesNotOpenBrowser`, which intercepts ACTION_VIEW
and asserts zero browser launches after offline download preparation. Both Qase84 components passed in
Qase98. Both components are required for complete case acceptance.

## Test tags

Maestro finds elements by id; in Compose the id is `Modifier.testTag`, exposed as a resource id by
`Modifier.pfTestRoot()` (set on the app root, dialogs and bottom sheets) — only in debuggable builds (`pfExposeTestTags`,
set in `PfApplication`); release builds keep tags out of the accessibility tree.

- Screens tag their own elements as `<feature>.<screen>[.<element>]`: `auth.phone.input`, `operations.feed`,
  `statements.upload.error.wrong_bank`. The constants live next to the screens (`AuthTags`, `OperationsTags`, …).
- Shared components use `ds.*` (`PfTestTags`): `ds.back`, `ds.dialog.confirm`, `ds.numpad.<digit>`,
  `ds.tab.<operations|analytics|profile>`, `ds.chat.input`.
- Listed insight rows expose `<prefix>.<block_id>.item`, and their remainder row exposes
  `<prefix>.<block_id>.more` (for example `analytics.regular_payments.item` / `.more`). Match a name
  and its amount with `containsDescendants` on the same tagged row; global amount matches do not
  prove which payment owns the value. Row tags carry only the block ID, never a user or payment ID.
- Analytics blocks are tagged with the names of the flags that hide them (`analytics.block.tiles`, …).
- Tag the screen root, every control a scenario uses, and states a scenario checks (errors, empty, offline).
  Prefer ids to texts: texts are fine for checking content, not for finding controls.
- Renaming a tag breaks flows: search `.maestro/` first.

## When a change needs a UI test

A PR that adds or changes something the user does or sees covers it in the same PR (rule in `AGENTS.md`, Code):

| Change | UI test |
|---|---|
| New screen, sheet or dialog | a flow that reaches it and checks what it is for (not only that it opens) |
| New state of a screen: error, empty, offline, limit | a flow that brings the app into it (fixture file, control link, flag) |
| New step or branch between screens | the feature's flow walks the new path; a separate flow when it has its own goal or start state (another account, flag, fixture) |
| Changed behaviour without new UI (validation, limits, what a button does) | the existing flow's checks follow the new behaviour |
| Flag that changes behaviour | a flow with the flag switched (`config.yaml`), next to the default path |
| User-visible bug fix | a check that fails without the fix: the flow reproduces the bug |
| Copy, colours, layout only | none — the design check covers it |
| Logic without UI (parsing, accounting, API) | unit tests, not UI tests |
| Refactoring that keeps behaviour | none; the existing flows must stay green |
| Renamed test tag | the flows that use it (search `.maestro/`) |

Maestro is the default. A native test (`app/src/androidTestMock`) covers what Maestro cannot drive — system
pickers, intents, permissions; keep it to that gap. When a scenario cannot run on the emulator (hardware it lacks,
a bug that does not reproduce there), list it in «Not covered yet» at the end of «Coverage» with the technical
reason and say so in the PR; «takes long to write» is not a reason.

Every new or renamed test: a Qase case (suite of the feature, steps and expected results in Russian, automated),
a line in `scripts/qase-cases.json`, a row in «Coverage». CI runs the flows after the merge to `main`; before that
they run locally (`scripts/e2e.sh`) or by a manual start of the `UI tests` workflow, and the PR says whether they
were.

## Writing a flow

- One file per scenario under `.maestro/flows/<feature>/`, `name` in Russian, tags: the feature and `smoke` for the
  short set (one happy path per feature).
- Start from a known state: `runFlow: ../../subflows/clear_state.yaml`, then `runFlow: ../../subflows/fast_login.yaml`
  (existing account, passcode 1234, ends on the feed — see «Signing in» for when `login.yaml` instead) or `launchApp`
  for sign-in flows. Not a bare `clearState`:
  right after `pm clear` Android may still kill a freshly started app process while it removes the old task, and
  the flow waits on a blank window. `clear_state.yaml` pauses for 3 s after the wipe; `sign_in.yaml` relaunches
  the app once if the phone screen still does not show up.
- Shared steps are in `.maestro/subflows/`: `clear_state`, `config` (env `LINK`), `fast_login`, `login`, `sign_in` (env `PHONE`), `set_passcode`,
  `enter_code` (env `CODE`), `relaunch_and_unlock` (env `CODE`).
- Wait for screens with `extendedWaitUntil` rather than fixed sleeps; scroll to below-the-fold elements with
  `scrollUntilVisible` before tapping them. Scroll to the node you tap, not to its container: Maestro measures
  visibility on bounds already clipped by the list, so a strip of a tall block above the tab bar passes as fully
  visible while the text inside it is still under the bar (FIN-179). Scrolling up stops the same way on a strip of
  a row under the screen title, and a tap there lands on the title: scroll to the nearest element above the row in
  the same list (the card's title) — it must stand closer to the row than a viewport, or the row ends up under the
  tab bar instead — or, for a row near the top of the list (what stands above it is shorter than a swipe), swipe
  once more to the top and wait for an element above the row — a strip of the row itself would pass the check too
  (FIN-188).
- Type ASCII where you can (merchant `Krabsbir`, digits): non-ASCII input depends on the emulator keyboard.
- `id` and `text` in one selector must match the same node. A tag on a container (`PfLink`, `PfPageHeader`, a
  row) has no text of its own — its text is a child node: check the id and the text in two steps.
- A checkbox tag, checked state and click action must belong to the same semantics node. `PfCheckbox`
  exposes all three on its outer node (FIN-375); do not test an uncheckable wrapper's default `checked: false`.
  `confirm_phone` asserts both choices are on after its conditional taps, including repeated calls.
- Labels that depend on «today» («Сегодня», «Вчера», the year next to a date) follow the pinned date, not the
  emulator's: 7 October 2026 is «Вчера».
- A new flow needs a case in Qase and a line in `scripts/qase-cases.json`; a renamed or removed flow updates both.
  `scripts/test_qase_report.py` (CI, `agent-docs` job) fails on a flow without a case or a case without a flow.

## Signing in

Most flows check what comes after signing in. They sign in with `fast_login.yaml`: the control link `sign_in=existing`
takes the path the sign-in screens take, through the same code under them — the phone verification request and its
stream on the fake server, the session saved by `SessionRepositoryImpl.start`, the passcode set by `AppLock` — and
skips only the typing (FIN-180: an estimated 10–20 s less per flow, to be confirmed by the next run). It signs in only the existing account: a new number
needs the personal data consent, which only the screens give. If the app process dies between the link and the launch
(FIN-44), the subflow unlocks the app with the code, as a user would.

`login.yaml` — the full sign-in through the screens — stays where the sign-in, the passcode or a consent is what the
flow checks:

| Flow | Why the screens |
|---|---|
| `auth/sign_in_existing_user` | the sign-in of an existing account itself (phone, call, passcode setup) |
| `applock/unlock`, `applock/forgot_code`, `applock/change_passcode` | the passcode set on the setup screen, then unlocked, forgotten or changed |
| `profile/delete_account` | the passcode is checked again before the deletion |
| `analytics/source_zone_and_session` (second login) | logout has already put the live app into passcode setup; `login.yaml` completes setup and its biometric offer before the new Analytics session |
| `statements/upload_amount_overflow` | cold fixture readiness can exceed the service sign-in budget: wait for `auth.phone.document` (at most 60 s), then complete `login.yaml` through the UI (FIN-454; existing FIN-442/444 setup pattern) |

The other sign-in flows (`auth/*`, `availability/maintenance_on_sign_in`, `offline/stream_drops`) drive the screens
themselves. Every other flow uses `fast_login.yaml`; a new one does too unless it checks one of the above.

FIN-427: `auth/register_new_user` first removes the preloaded fixture through History under the existing
mock account, signs out and registers the new number in the same process. The one-user in-process server
otherwise already has 1969 operations at registration. The flow verifies the empty upload guide and
«Скачать за год» before the first import; clearing app data or restarting that process would preload it again.

## Coverage

| Flow | Checks |
|---|---|
| `refresh/server_data` | pull-to-refresh on the feed, operation details, empty search, selected analytics month, profile, upload history, unread lines and an assistant draft; offline retains data/query/period, online clears the failure; after a failed Year switch, failed/successful Month pulls and current-read Retry preserve Month and its separate target notice, then only the Year Retry changes the period; a history read never asks or retries a question (Qase70, FIN-399); authored, not executed |
| `auth/register_new_user` (smoke) | empty mock history prepared through UI before registration; independent 14+/PD before phone → new number → create account without another consent → passcode → first upload offers «Скачать за год» and reachable «Загружу позже» → result → feed (Qase2, FIN-365, FIN-416/427) |
| `auth/sign_in_existing_user` (smoke) | independent 14+/PD before phone → existing account → passcode → feed; the main tabs (Qase1, FIN-365) |
| `auth/phone_restart` | terminal Start refusal → explicit restart without POST → both choices off → new confirmed intent (Qase59, FIN-365) |
| `auth/phone_confirmation_gates` | both initially unchecked, independent age/PD gates, disabled phone input before 14+, return from call retains confirmations; repeated confirm_phone keeps both checked and input enabled (Qase57, FIN-365, FIN-375) |
| `auth/phone_consent_recovery` | document offline → explicit refresh → confirmations → Start offline → retry with the existing intent (Qase58, FIN-365) |
| `auth/phone_validation` | `call_delay_ms=60000` before Start leaves time to inspect the call screen; locked phone and age-unlock hint (FIN-394); short number error; no promise of a free call; call screen with «Ждём звонок до ЧЧ:ММ»; «Изменить номер» keeps the number (Qase3, FIN-166/436) |
| `auth/call_expired` | `call_delay_ms=600000&call_ttl_ms=15000` before Start prevents early mock verification; this 15s test override does not change the normal 5-minute rule. The stream's `expired` or device timer ends the wait (order not fixed); both lead to «Время на звонок вышло» with no number. Restoring `call_ttl_ms=300000` before «Позвонить ещё раз» brings back the number and normal deadline (Qase41, FIN-166/436) |
| `auth/call_stream_rate_limited` | live 429 marker before negative checks; `stream_drops=1&stream_rate_limits=100&stream_retry_s=30`: one actual refusal, number stays, no ErrorMessage (`ds.error.info/warning/error`) or offline message; exact count across control-link resume, remove future refusals, retained wait → passcode → feed (Qase44, FIN-170, FIN-376, FIN-380) |
| `auth/registration_closed` | clean state, `flag.auth.registration=false` before sign-in: confirmed new number → «Регистрация пока закрыта»; existing account still signs in (Qase4, FIN-436) |
| `auth/consent_document_rotation` (mock-only) | pd-1 rejected at Start after publication of pd-2; offline/same-version/held/429 reads, no repeated Start, explicit restart with fresh choices/key; published pd-3 preserves accepted pd-2 at Register; refused Register returns to fresh Phone (Qase54, FIN-446); LOCAL Passed348.626s/run78 |
| `applock/unlock` (smoke) | cold start → unlock; wrong code error |
| `applock/forgot_code` | «Забыли код?» → cancel / sign out |
| `applock/change_passcode` | current → new → mismatch → new → repeat; the new code unlocks after restart |
| `operations/feed_and_detail` (smoke) | feed → card with required `occurred_at` → change category → back → reopen: the manual category persists (FIN-203) |
| `operations/unknown_filter_kind` | `unknown_codes=true`: the expense tile sends a `kind` the client does not know; the search keeps it under «Как на „Аналитике“» and finds no incomes — FIN-83 |
| `operations/days_in_profile_zone` | `profile_timezone=Asia/Vladivostok`: after the profile is read, the feed groups days in the profile zone, not the device's — an evening operation in Moscow moves to the next day — FIN-92 |
| `operations/statement_stale` | on the preloaded statement the caption under the summary says «Данные по 1 октября» (the statement's end, not the last operation on 30 September); then the preloaded statement deleted, `stale.ofx` uploaded: «Операции» and «Аналитика» show «Данные в приложении по 20 сентября — с тех пор прошло больше двух недель», dated by the statement's end (`state.data_to`), not the last operation (15 September) — FIN-125 |
| `operations/search_filters` | empty result → «Сбросить фильтры» with the query alone clears it; query, «Только расходы», amount, separate «Сбросить» below the chips stays visible at both horizontal scroll edges (FIN-416, Qase9), period, result card; «Без категории» in the picker's «Системные» — FIN-108 |
| `operations/refund_category` (mock-only) | automatic refund card and explicit refund category for an ordinary credit; synthetic September expense/income 900/+300 RUB → 600/0 RUB, target explanation and retained row/details after reopening (Qase92, FIN-442); execution evidence in PR #273 |
| `operations/own_transfer_split` (mock-only) | two known own-transfer pairs: expense category goes to debit, income category to credit; split notice, exact search count 1→2, signed rows, each side's own timestamp/category after reopening (Qase93, FIN-442); execution evidence in PR #273 |
| `analytics/facts_and_forecast` | current-month fact with a blocked forecast; replace the full seeded year through UI import with a rolling-year OFX (11 full months); year keeps expense beside forecast and opens the history explanation in its help sheet; fact → search checks year range, expense kind and merchant/amount → Back; explicitly pick a past month through its option container and inspect the full tile area for no forecast; delete its covering statement while another month remains and retain the selected month with one no-data explanation and no monetary tiles (FIN-401/402); Qase71; follow-up not executed (previous run failed) |
| `analytics/period_change_retry` (mock-only) | failed Year switch keeps September and its selected segment; concurrent dismissal failure keeps a separate notice; durable notice + explicit Retry preserves the exact target, resume does not retry; recovery returns Year and saved Month; deferred Year response after a refused in-flight dismissal scrolls to the selected year text itself (Qase73, FIN-391/444); execution evidence in FIN-444 |
| `analytics/transfer_change_retry` (mock-only) | offline transfer-mode switch preserves September/Month, WITH and the observed ready expense; restoring connectivity through config/resume preserves the notice and old mode; explicit target Retry commits WITHOUT and removes the error; after returning to WITH, disabling the transfer filter retires a new refused WITHOUT target, hides the chip and Retry, and preserves September/Month and the WITH expense (Qase96, FIN-452); authored only, UI not run |
| `analytics/period_controls` (common) | Month/All-time hide rolling choices; Quarter/Year expose only their right-aligned choice; tapping the selected calendar segment exits its rolling period; All-time removes the whole period row across tab navigation (Qase80, FIN-416); authored, not executed |
| `analytics/rolling_periods` (mock-only) | rolling year/quarter anchored to statement coverage, exact ranges, no forecast, failed singleton switch and explicit exact retry, transfer-mode preservation, clipped edge point → full calendar month, independent remembered calendar selection; Qase76 (FIN-411); authored, not executed |
| `analytics/chart_month_selection` (mock-only) | whole-column selection including zero expenses, exact month/year, selected state and retained transfer mode/scroll; failed switch and explicit retry (Qase74, FIN-398); authored, not executed |
| `analytics/all_time` (mock-only) | factual multi-year range, transfer mode, failed switch with explicit retry, import/deletion and final empty state; expense search uses the ASCII query FIN-400 within the inherited 2024–2026 range, checks the single January 2024 debit and retained kind/scope (Qase75, FIN-400/415); follow-up not executed (previous run failed) |
| `analytics/overview` (smoke) | blocks; independent month/quarter/year selections (August, Q2, year), exact title + selected segment and no error after each switch (FIN-391); «Без переводов», category → operations |
| `analytics/import_preparation_retry` | real UI import before first Analytics VM; finite publication delay shows preparation without upload guidance, then data; bounded wait → timeout, resume preserves timeout and explicit Retry recovers after fixture cleared (Qase63, FIN-394) |
| `analytics/recalculation_snapshot` (mock-only) | changed intermediate block retains the whole last-ready snapshot; ready replaces it; bounded recalculation keeps labelled previous data and explicit Retry; uncached Year prepares without showing Month values; active recalculation → targeted dismissal refusal → retained payment → bounded paused read → explicit Retry → ready, with exactly one mutation attempt (Qase82, FIN-416/427); retained category row is observed before the bounded pause and an explicit Retry with a fresh finite recalculation countdown; final month header is revealed after Retry; follow-up to failed run 41855090174, not executed |
| `analytics/regular_block_update` (mock-only) | successful PUT removes RBC and promotes the third visible payment row before a ready GET; seven seconds in the same card retain that row without RBC across marked GET polls; bounded polling pauses with September retained, then explicit Retry permits ready GET; separate unknown offline result retains RBC until explicit PUT retry (Qase81, FIN-416/427/436). Its case-level Passed covers these UI observations only; JVM version/context checks and backend post-commit/durable delivery are separate |
| `analytics/regular_post_commit_retry` (mock-only) | post-commit retryable 500 keeps the original card and unknown warning; persistent fault plus exactly one injected failure excludes a hidden retry; fault recovery/resume retains RBC until another explicit row action; complete PUT block changes 5188/6/«Ещё 3»1599 RUB to 3688/5/«Ещё 2»599 RUB and promotes tablet while ready GET remains withheld (Qase91, FIN-438); focused local API34/Pixel6/Maestro2.11.0 pass in PR #271 |
| `analytics/unknown_mutation_recovery` (mock-only) | forbidden 500 → explicit read with retained unknown warning and no PUT; Offline → uncached Year or upload deletion → reachable recovery outside the missing snapshot, delayed GET shows preparation before data/empty; unknown in-flight PUT rolls an unperformed selection back, then the same segment tap works; Qase89 (FIN-416/427); re-locate the RBC action after offline resume; follow-up to failed run 41844441379, not executed |
| `analytics/chart_history_pages` (mock-only) | synthetic 2000–2026 history crosses more than three page boundaries in both directions; covered zero selects its exact month, scrolling retains selection (Qase83, FIN-416); FIN-441 fully exposes endpoints before selection; FIN-445 brings the exact selected month title into view at all three return points; execution evidence is recorded in FIN-445 |
| `analytics/source_zone_and_session` (mock-only) | unsupported source-zone notice survives an offline retry over cached data, supported response recovery, logout/new login with marked response prepares without old financial blocks (Qase88, FIN-416); updated for FIN-427 after failed run 41819133288; follow-up not executed |
| `analytics/block_unavailable` | `failing_blocks=expense_categories`: the block shows «Не получилось посчитать», the other blocks stay — FIN-5 |
| `analytics/unknown_blocks` | `unknown_codes=true`: a block of an unknown type is shown by its text, a card locked for an unknown reason by the server's lock text — FIN-4, FIN-83 |
| `analytics/cards_open_search` | September: «Мелкие частые траты» opens its first group as a selection (chip with the group's name), a row of the card — its own group, a row of «Подписки и регулярные платежи» — that payment's charges — FIN-108 |
| `analytics/search_scope_own_transfers` | September «Расходы» tile → search «как на Аналитике»; without «Только расходы» and with the amount 6 000 ₽ nothing is found — the single «Себе в другой банк» of 27 September is out, as in the Analytics figure; without the scope chip it is found — FIN-164 |
| `analytics/blocks_switched_off` | flags hide tiles, categories, transfers chip, assistant (card, profile and bottom tab) |
| `assistant/late_enable` | layer starts false → explicit Analytics entry after true → admitted Chat remains through false, orientation recreation and Consent/Back → admitted tab is visible/selected while flag remains false, without sending automatically (Qase68, FIN-397); authored, not executed |
| `assistant/tab_consent_in_profile` | denied draft in retained tab → consent through Profile → server read enables explicit send without submitting on resume (Qase65, FIN-397) |
| `AiConsentSpacingTest#keepsTwentyFourDpAfterConsentContentWhenScrolledAtLargeFont` | native geometry assertion: scroll to submit in 640 dp viewport / 150% text, verify ≥24 dp after full checkbox/error block with and without formError (Qase64, FIN-394/397); authored, compile only |
| `assistant/tab_and_consent_recovery` | bottom tab keeps draft/history across Operations, Analytics and Profile; consent Back → Give consent → grant preserves draft without automatic send, explicit send creates one question (Qase61, FIN-397, FIN-394) |
| `assistant/ask_question` (smoke) | disclosure of question/history, account-phone/file exclusion and masking limits → consent (error without the tick) → question → answer → limit 4 of 5 → chip → scoped search (Qase12, FIN-366) |
| `assistant/daily_limit` | unanswerable question is free (the server's CANNOT_ANSWER text, no «Повторить»); five questions exhaust the day |
| `assistant/off_topic` | a question not about finances (`weather`): the server's OFF_TOPIC text, no «Повторить» (`retryable: false`), still 5 of 5; the next question is answered, 4 of 5 — FIN-160 |
| `assistant/off_topic_limit` | five terminal OFF_TOPIC outcomes reach the rolling limit; a refused sixth question keeps its draft until the nested chat is closed. After full Back/re-entry the previous editable draft is absent, history remains readable and daily quota is 5/5 (owner decision 06.11.2026, FIN-450); Qase49 |
| `errors/request_retry_false` | a REST refusal with retryable=false is not resent on resume or after its wait; corrected input is a new action and a later failed result can separately allow RetryAnswer — FIN-223, Qase 50 |
| `errors/server_messages` | profile theme mutations show an unknown ordinary server message, unknown-severity error styling and safe malformed/legacy fallbacks without diagnostics — FIN-223, Qase 48 |
| `assistant/nothing_found_charged` | a merchant with no operations over the covered six months of the answer (`Kaspersky`, only in March): «…операций «Kaspersky» не нашли», an answer, not a refund — 4 of 5 (it used to stay 5 of 5); only a lock or no data over the period is refunded — FIN-136 |
| `assistant/offline_retry` | draft prepared online → resume history read offline hides input → read-failure Retry restores draft without sending → explicit Send and pending-Ask Retry fail offline without spending quota → online resume restores draft without POST → explicit Send accepts exactly one new question (quota 4 → 3 of 5); Qase20, FIN-415; foreground online Retry limitation is listed below |
| `assistant/chip_after_statements_deleted` | every statement deleted: the operations chip of an earlier answer opens the search without its stale selection (notice, empty result) — FIN-76 |
| `assistant/retry_without_transfers` | `assistant_model_errors=1`: a question asked «Без переводов» fails, not spent; «Повторить» answers it still «без переводов» — FIN-76 |
| `assistant/answer_retry` | `assistant_model_errors=1`: the answer fails, not spent; a double tap on «Повторить» while the request is on its way sends it once: one answer, no «Не получилось отправить вопрос», 4 of 5 — FIN-75 |
| `assistant/answer_changed_since` | after an answer a category is changed, then the statement deleted: the earlier answer in the history shows «С тех пор категории менялись», then «С тех пор данные менялись» (`changed_since` counted at the time of the request) — FIN-135 |
| `statements/upload_errors` | CSV and another-bank server messages, not a statement; leaving the picker (Qase15, FIN-375) |
| `statements/upload_unreadable` | a file with no readable operation: the empty result still shows the unread lines notice; the history row opens «Непрочитанные строки» (line, date, reason) |
| `statements/upload_amount_overflow` | a small OFX with one valid debit and one amount beyond Long: one unread `bad_amount` line, history/unread navigation and the unchanged valid operation/date/amount ([Qase95](https://app.qase.io/case/PLAINFIN-95), FIN-454); LOCAL Passed 172.089 s on API34/Pixel6/Maestro2.11.0 after explicit cold-backend readiness and UI sign-in, evidence in PR #292; earlier driver/setup failures preserved |
| `statements/unread_same_physical_line` | two distinct unread operations on physical line 31 with the same `no_date` reason: both row titles/reasons are visible and Back still opens history — FIN-352, [Qase56](https://app.qase.io/case/PLAINFIN-56); authored, not executed |
| `statements/upload_unreadable_fixed` | `unreadable.ofx`, then `unreadable-fixed.ofx`: the old upload's row says «Строки дочитаны в загрузке от 8 октября» and has no «Не прочитали» link — FIN-79 |
| `statements/download_period` (mock-only) | inclusive calendar selection, invalid/reversed/future bounds, network failure retains dates and offers explicit retry; Qase84 (FIN-416); updated for FIN-427 after failed run 41819133288; follow-up not executed |
| `StatementPickerTest#opensTheServerUrlForSelectedInclusiveDates` | exact server download URL handed to browser for 1–30 September; native Espresso-Intents because Maestro cannot inspect destination; Qase85; authored, not executed |
| `StatementPickerTest#failedDownloadKeepsSelectedDatesAndDoesNotOpenBrowser` | actual MainActivity calendar, retained 1–8 October after offline preparation, enabled Retry and no ACTION_VIEW; native companion to the Maestro Qase84 flow; authored, not executed |
| `ProfileThemePaletteTest#darkChoiceChangesTheRenderedProfilePalette` | actual MainActivity Profile, explicit Light then Dark choices, rendered gutter pixels match independent light/dark palette values; Qase17 native companion; Qase98 Failed while finding Light label; stable-row scroll + bounded exact-label wait passed with the Maestro companion in Qase100; first local failures preserved in Qase99 |
| `operations/source_time_zone` (mock-only) | unsupported IANA metadata above verified feed/search cache; stale notice and explicit supported recovery; Qase86; updated for FIN-427 after failed run 41819133288; follow-up not executed |
| `statements/source_time_zone` (mock-only) | unsupported import config and complete result; explicit supported recovery; Qase87; authored, not executed |
| `statements/upload_history_period` | statement period wider than operations → 1–30 September; no DTSTART/DTEND → operation day; empty file without a period → empty result, system Back → Operations, Profile → upload history → count without a date label (FIN-198, FIN-209) |
| `statements/upload_and_history` (smoke) | new operations without a duplicate period row; October income 0 → +1200 after import; duplicate import and deletion of its first source each retain +1200 and exactly the same three October rows with names/dates/amounts; deleting the second source yields income 0 and an empty October search while preload analytics stays; last deletion clears old totals (FIN-394/436); loaded/yearly download labels and privacy notice remain (FIN-416, Qase14); new assertions authored, not executed |
| `statements/upload_offline_retry` | «Не скачивается?» sheet; no network while sending: notice on the guide, «Повторить» sends the same file |
| `statements/upload_restored_key` | A seeded unknown-age UUID does not send the file or silently change key; the upload count remains 1 (preloaded statement). Only «Загрузить заново» starts a new action and changes the count to 2 (Qase51) — FIN-223 |
| `assistant/retry_after_consent` | A model failure, consent revocation and denied RetryAnswer lead to consent correction; the same operation resumes and history contains one question with the successful replacement (Qase52) — FIN-223 |
| `assistant/consent_document_rotation` (mock-only) | outdated PUT → offline/same-version/held GET, one read in flight, new version resets the tick and needs explicit grant; draft/quota retained without Ask; independent GET429/false remains blocked after its captured wait (Qase55, FIN-446); LOCAL Passed360.281s/run80 |
| `statements/upload_rate_limited_resume` | `stream_drops=1&stream_rate_limits=100&stream_retry_s=30`: after the first 429, both control-link and background returns retain the original deadline (`expect_stream_rate_limited_exact=1`); lifting the limiter does not skip that wait, then the result opens within 45s without an upload error (Qase45) — FIN-170, FIN-223 |
| `statements/upload_no_description` | a line without a description is imported (no unread notice) and shown as «Без описания» in the feed and on the operation |
| `statements/upload_unknown_codes` | `unknown_codes=true`: the import result shows the server's text for a notice code and for a locked analytics feature the client does not know — FIN-83 |
| `profile/theme_and_logout` | dark theme; logout cancel / confirm with the notice |
| `profile/delete_account` | passcode (wrong, right) → «Удалить аккаунт?» over the profile → «Удаляем…» ignores Back → «Аккаунт удалён» |
| `availability/update_required` | `min_version=9.0.0`: the next request gets `CLIENT_VERSION_UNSUPPORTED`, the update screen covers the app — FIN-22 |
| `availability/maintenance` | The check button is disabled during `Retry-After`; the first automatic probe is refused, then a guarded manual check after the fresh wait opens the app; a new maintenance episode has its own one-shot automatic check — FIN-22, FIN-223 |
| `availability/maintenance_on_sign_in` | load the consent document and confirm age/PD first, then `maintenance=unknown&maintenance_retry_after=600`; «Продолжить» on the phone screen gets maintenance over sign-in, without an end time (Qase35, FIN-22/436) |
| `availability/maintenance_during_import` | A 15s stream 429 wait survives returning from the control link; its next opening gets 503 MAINTENANCE. After disabling maintenance/limits, the single guarded recovery waits its captured 15s deadline, then lifts maintenance within 40s; history shows both preloaded and new uploads done (Qase46) — FIN-22, FIN-170, FIN-177, FIN-223 |
| `offline/no_network` | connection-failure title and internet hint without VPN on analytics/profile; enabled retry before recovery; optional recovery tap when resume has not already loaded the screen, followed by mandatory loaded data and no offline state (Qase19, FIN-396/470) |
| `offline/stream_drops` | `stream_drops=2` before each stream: the server closes the call, import and answer streams after their first event without a terminal one; the app re-opens them with a pause and shows no error — sign-in goes on, the import result opens, the answer comes, 4 of 5 — FIN-170 |
| native `VoicePermissionTest#deniedMicrophonePreservesDraftWithoutSendingQuestion` | actual ChatScreen/VoiceInput requests RECORD_AUDIO → real system denial through UiAutomation → clear permission notice → close → original draft, quota=5 and Ask=0 (Qase72, FIN-404); no speech service needed; focused local execution tracked in PR #277 / Qase |
| native `VoiceDictationTest#dictationEventsPreserveDraftAndRequireExplicitSend` | actual ChatScreen/VoiceInput/VoiceSession/VM with test-only platform callbacks: prepare/ready/level/finish/result, draft/no auto-Ask, cancel/Back/ON_STOP/late events, errors/fallback/repeated actions and edited explicit send after a controlled consent-result handoff (Qase69, FIN-449); focused local execution tracked in PR #277 / Qase |
| native `PasscodeViewportTest#wrongCodeFitsUnlockAndDeletionOnSmallViewport` | wrong PIN: full error/dots, keypad and recovery action at 360×640 dp (FIN-389, [Qase77](https://app.qase.io/case/PLAINFIN-77)); native geometry complements Maestro cases 5/18 |
| native `PasscodeViewportTest#errorFreePasscodeFamilyShowsDotsAtLargeFont` | setup NEW/REPEAT, change OLD/NEW/REPEAT, unlock/confirm without error at 360×640 dp / font2 / insets48+48: automatic dots visibility, first digit/delete/back/footer; user scroll survives unchanged-range recomposition ([Qase78](https://app.qase.io/case/PLAINFIN-78), FIN-389) |
| native `PasscodeViewportTest#rangeObserverSurvivesHeldUserDrag` | real held down/move gesture spans error recomposition; later range/error still auto-reveal after UserInput cancellation, with drag/range/no-clamping preconditions ([Qase79](https://app.qase.io/case/PLAINFIN-79), FIN-389) |
| native `ConnectionFailureHintTest` | fake local reader → VPN text; change observation and explicit retry → internet hint without VPN text, for full-screen state and ErrorMessage (Qase62, FIN-396); Compose dependency injection cannot be driven by Maestro without adding an app hook |
| native `ScreenErrorTransitionTest#profileReadFailureReplacesThemeOutcomeAndRetries` | actual ProfileContent: theme failure → read failure without the old outcome; available Retry restores new profile data; native reader/state injection (Qase66, FIN-396) |
| native `ScreenErrorTransitionTest#historyReadFailureReplacesDeleteOutcomeAndRetries` | actual HistoryContent: deletion failure → read failure without the old outcome; available Retry refreshes the list without another DELETE; native reader/state injection (Qase67, FIN-396) |
| native `StatementPickerTest` | login button opens exactly `https://tbank-online.com/login` in an intercepted ACTION_VIEW (FIN-395; Qase16), then upload with a picker result stubbed by Espresso-Intents (on the e2e build the picker is `E2eDocumentPickerActivity`) up to the import result; the `ACTION_OPEN_DOCUMENT` intent itself: Robolectric `PickStatementDocumentTest` |

`StatementPickerTest` waits for passcode setup after sign-in without recreating the activity (FIN-63, after the
FIN-88 session fix). If it stalls, the test fails after 30 seconds and keeps the semantics tree in logcat (`PfTest`)
and the main-thread latency report in the assertion. The logcat remains in the `e2e-reports` artifact.

Not covered yet (FIN-452, FIN-453): an Analytics GET refusal with `retryable:false` after selecting another
transfer mode, its exact `Retry-After` boundary, and an answer connection lost between a final-status `snapshot`
and the following `done` / `error`. The existing closed error controls do not target Analytics GET (only
`analytics_dismiss`), and the mock stream-drop control does not split a completed snapshot from its terminal
metadata. Maestro cannot inject these transport boundaries through the current in-process server.
The ordinary offline transfer-mode refusal and explicit retry are authored in `analytics/transfer_change_retry`
(Qase96), including preservation across config/resume; this flow has not been run.
`AnalyticsViewModelTest` covers false/wait, repeated selection, explicit retry, refresh/resume and changed
contexts. `ChatViewModelBehaviourTest` covers close/offline after failed/complete/unknown snapshots,
terminal text/quota/retry permission, pause/resume and REST refusal without another POST; interleaved
Ask/Retry and LIMIT_EXCEEDED responses must keep their newer quota when an older terminal event is recovered.
Concurrent live refunds reconcile through the gated quota GET, including an already pending read and coalesced
terminal invalidations; recovery clears the corresponding stream error.
No production hooks or UI executions were added for these timing boundaries.

Not covered yet (FIN-40, FIN-415): tapping «Повторить» for a pending Ask after connectivity returns while
Chat stays in the foreground. The mock control link pauses Chat; its online resume reads history and clears the
offline notice before Maestro can tap Retry. The current mock has no Ask-only offline fixture, so Qase20 covers
offline Retry and recovery through resume followed by explicit Send instead. The existing unit regression
`ChatViewModelBehaviourTest#offline keeps the question and the retry re-sends it with the same key` covers the
pending text, exactly two Ask attempts and reuse of the original idempotency key. The foreground online-Retry
path still needs UI coverage with controllable connectivity that does not pause Chat.

Dictation UI behavior is authored in native `VoiceDictationTest` /
[PLAINFIN-69](https://app.qase.io/case/PLAINFIN-69) (FIN-449), using deterministic platform callbacks while retaining
the actual screen, input/session and chat VM. Focused native execution is tracked in [PR #277](https://github.com/fin-assist/android/pull/277) and Qase; each attempt is retained. Maestro cannot inject those
callbacks through the existing control Activity without pausing the chat and triggering cancellation.
Permission refusal remains separately covered by native `VoicePermissionTest` /
[PLAINFIN-72](https://app.qase.io/case/PLAINFIN-72) using the actual Android dialog and default production recognizer path.
The hardware part of the old Qase69 is preserved as Manual
[PLAINFIN-94](https://app.qase.io/case/PLAINFIN-94): microphone/audio quality, installed ru-RU model, real system
recognition/fallback and perceived haptic. Simulated callbacks do not confirm those integrations.
Service-unavailable cannot be inferred solely from an absent ru-RU model: `isRecognitionAvailable` may still find
another provider. `VoiceSessionTest`, `VoiceRecognitionErrorsTest`, `VoiceInputRememberTest`, the new
`VoiceInputBoundaryTest` and `VoiceRecognizerTest` independently cover session ordering, error policy, platform
options, final-only text, recomposition and lifecycle/resource cleanup. `ChatViewModelBehaviourTest` covers append/length
bounds and no automatic Ask; `ChatDesignCheckTest` retains existing panel-state fixtures.
Biometrics (needs an enrolled
fingerprint), custom date ranges in search,
links inside a sentence — the registration terms and privacy policy, «Как мы защищаем данные», «разобрать» and
«Подробнее» on the import result, «Подробнее» on «Аналитика» (a link inside a text node has no own view or
resource id for Maestro to tap, and the first three open an external browser the flow cannot check).
The server's own check of the phone number (400 `VALIDATION_ERROR` at `phone`, FIN-68) cannot be reached either:
the app sends only `+7` and 10 digits, the very rule the server checks, so neither a real build nor the mock gets
that error; `PhoneViewModelTest` covers the branch, `auth/phone_validation` the app's own check of a short number.
An answer stream ending in an error other than the network (404 for an answer the server no longer has) while the
answer is still generating (FIN-75): neither a real server nor the mock answers 404 for an answer it is still
generating, only for an unknown or replaced one, which the chat never watches — the state needs a server that lost
the answer mid-generation; `ChatViewModelBehaviourTest` covers the re-read of the history after such an error.
A false «сессия истекла» after the user's own sign-out (FIN-74) needs the session observer's coroutine to get the
`null` only after the sign-out returned and the phone screen read the reason — a thread interleaving neither the
emulator nor the mock can force; `SessionRepositoryImplTest` reproduces it with a token store that delivers the
`null` late.
A refunded answer (`charged: false` for a lock or `no_data` over the requested period, FIN-136) cannot be reached
in the mock: the canned model builds the answer window from the covered months and knows no locked metrics;
`AssistantEngineTest` covers the refund rule.
A passcode left behind by a signed-out session (the process died between the sign-out and the passcode reset) and
cleared while the lock is starting (FIN-87) cannot be forced either: the flow cannot kill the process inside that
window, and the stale state needs a DataStore collector to start while the reset is being written, a timing the
emulator does not reproduce on demand (about 8 % of starts on the JVM). `AppLockStoreRaceTest` repeats that start
200 times; `profile/theme_and_logout` covers the plain sign-out.
A screen that stays put after a sign-in or a sign-out (FIN-88; the FIN-63 stall on the call screen) cannot be forced
either: it needs a subscriber of the session to start while the token store is being written, a timing the emulator
does not reproduce on demand (about 5 % of such starts on the JVM). `EncryptedTokenStoreRaceTest` repeats both writes
200 times against the real store; `auth/sign_in_existing_user` and `profile/theme_and_logout` cover the plain paths.
The update or maintenance screen over the passcode screen (FIN-22): while the app is locked no screen sends a request,
so a server answer can arrive under the lock only from background work (the time zone sync, Remote Config) at a moment
a flow cannot pick. The overlay is one layer above all others in `PfApp`; `availability/maintenance_on_sign_in`
checks it over sign-in, the other flows over the main screens.
A renamed copy of a statement sent again after a lost answer (FIN-340): the server compares the file's content and
its name under a key (FIN-297), so a client that reused the key of the same bytes under the old name would get 409
`IDEMPOTENCY_CONFLICT`. The case needs a first request that the server stored under the key while the client lost its
answer; the mock has no such hook — `offline` fails before the key is taken, and the upload has no 5xx — and the
bundled files hold no two copies of one statement under different names. `UploadViewModelTest` checks that the copy
gets its own key and that a retry sends the first attempt's name; `MockStatementsApiTest` and `ContractBehaviourTest`
check the server side.

The initial retry-readiness collector racing an unknown-age upload key (FIN-353) is not controllable
through a UI flow: it needs the first collector emission to run after the recovery state was published.
`UploadViewModelTest` queues Main and makes the pending-key cache immediate to reproduce that ordering;
it asserts disabled ordinary retry, no POST, retention of the original UUID and a new key only after explicit
new intent. `statements/upload_restored_key` / Qase51 already cover the visible recovery path with the
closed unknown-age seed. No additional production hooks or UI/DesignCheck execution are used.



The independent refusal of the availability probe after maintenance is not covered by a mock UI flow
(FIN-223). The probe uses `GET /v1/consents/personal_data` to check availability, not to grant consent.
The existing maintenance controls produce only `MAINTENANCE`; `offline` fails before a REST response,
and the closed `error_operation` controls do not target this GET. They cannot produce a subsequent
`INTERNAL_ERROR` 500 with its own `retryable:false` or `Retry-After`; that branch needs a controllable
REST/peer fixture. `AvailabilityViewModelTest` checks the independent prohibition/wait, direct calls
before readiness, unchanged original deadlines and preservation of the scoped maintenance correction.
The faithful interceptor fixture also checks that a late 429 refusal after Open cannot restrict a later
maintenance episode, including when StateFlow conflates Open with the next Maintenance.
The existing authored `MaintenanceWaiting` fixture covers the disabled check button; no UI or DesignCheck
execution is claimed. The existing maintenance flows and Qase34/35/46 cover the maintenance paths only.


Consent-document rotation between GET and phone Start (FIN-365) and assistant grant is authored in
`auth/consent_document_rotation` / [Qase54](https://app.qase.io/case/PLAINFIN-54) and
`assistant/consent_document_rotation` / [Qase55](https://app.qase.io/case/PLAINFIN-55) (FIN-446).
The bounded mock controls separate publication from stale/held document responses; no stage is required.
Register never refetches current PD consent; mismatched original evidence restarts phone verification.
The full flows passed separately in LOCAL runs78/80 (see FIN-446 below). `PhoneViewModelTest`, `PhoneStartGateTest`, `MockPhoneConsentTest`,
`CallViewModelTest` and `AiConsentViewModelTest` remain independent lower-level checks, including exact captured
deadlines, missing local registration evidence and the out-of-order initial assistant GET. Those internal
interleavings are not claimed as executed steps of these two UI cases.

Real client process-death recovery of an uncertain assistant request is not covered by a mock UI flow
(Qase53). The fake backend, its token state and idempotency cache live in the client process: killing it
also destroys the independent server outcome that recovery must reconcile. A separate persistent BFF/peer
fixture is needed to test that boundary. `ChatViewModelBehaviourTest` restores the actual SavedStateHandle
and tests no automatic POST, consent correction, fresh-history permission and explicit new intent;
`ChatAskRecovery`, `ChatAskRecoveryDark` and `ChatRetryRecovery` fixtures cover the proposed visible states.
The upload flow above deliberately seeds an unknown-age record without claiming real process death.

[Qase60](https://app.qase.io/case/PLAINFIN-60) is Draft / To be automated for the current
[FIN-386](https://finassist.youtrack.cloud/issue/FIN-386) common/mock-only architecture. Its old stage
experiment held a real Ask response on the client; that mechanism was rejected and PR228 was closed without
merge. Historical runs remain evidence of that previous implementation only. Current main has neither
`stageE2e` nor a mapped Qase60 test. The original boundary still requires independent server state and proof
that Ask was accepted before client process death, without a client response hold/transform. Until an allowed
reproduction exists, that boundary remains pending; a neighbouring recovery scenario is not its replacement.

### FIN-223 safe error controls and coverage

The mockE2e-only control link accepts `error_operation=profile_update|operations_list|assistant_ask|phone_start|analytics_dismiss|off` and `error_scenario=unknown_code|unknown_severity|invalid_message|legacy_response|retry_forbidden|terminal_failure|post_commit_failure`. These are closed fixtures with fixed safe texts, not arbitrary messages/actions. `post_commit_failure` is valid only with `analytics_dismiss` (or `off`): it saves the mock assignment/target before returning a canonical 500 with explicit request retry permission. Existing scenarios still fail before the mutation. `expect_error_requests=<n>` requires the exact cumulative number of injected failures; changing the operation does not reset it. Unknown upload-key recovery uses the closed `pending_upload_fixture=october` seed and `expect_uploads` check; it does not claim to kill the client process. The new flows and design-check fixtures were authored but not executed: owner instruction forbids UI, Maestro, instrumented and DesignCheck runs. New server-message/recovery fixture artboard names are proposed; corresponding Figma artboards are not available yet.

### FIN-352 repeated physical unread lines

`UnreadLine` has no public occurrence ID. Two records can have identical upload, physical line and reason;
the renderer uses the response occurrence index within each upload for its local LazyColumn key, while keeping
the displayed line number/reason unchanged. The existing mock parser and resolver already retain such records.
The new `unread-same-line.ofx` fixture and `statements/unread_same_physical_line` flow exercise the actual upload →
history → unread list path, with index selectors checking both equal row titles and both equal reasons.
`UnreadLinesViewModelTest` verifies duplicate-preservation in state; it does not claim to execute the renderer.
`UnreadLinesSamePhysicalLine` / `UnreadLinesSamePhysicalLineDark` are proposed DesignCheck artboards with no Figma
counterpart yet. The flow, Qase56 and fixtures were authored without UI/Maestro/DesignCheck/instrumented execution.

### FIN-366 assistant-consent disclosure

`assistant/ask_question` / [Qase12](https://app.qase.io/case/PLAINFIN-12) checks that the consent screen names the
question and dialogue history, excludes the account phone and statement files, and warns that names and other
personal information in free text can remain. Supported phones, email addresses and full card/account numbers
are masked by the backend; the screen does not promise complete anonymisation. Existing assistant-consent flows
scroll to the checkbox and button to keep both reachable with the longer disclosure. The existing path-to-Qase
mapping is unchanged. The flows and existing light/dark `AiConsentDesignCheckTest` fixtures use the updated content;
UI, Maestro, instrumented and DesignCheck runs were not executed under the owner's instruction. Publishing final
legal documents and their production URLs remains FIN-141; the mock consent-document URL is a fixture.

### FIN-365 phone confirmations and registration

`confirm_phone.yaml` supplies independent affirmative fixture choices for all existing sign-in scenarios, including
existing accounts and the native picker path. No production API defaults accept either confirmation. The phone
screen loads `personal_data` first, keeps both ticks initially off, disables phone input before age confirmation,
and exposes the current response's document URL. An outdated Start resets the PD tick; call re-request routes
back for document correction. After verified, Register sends the same accepted version without another checkbox.
`PhoneAgeOnly`, `PhoneConsentOnly`, document-failure light/dark and new-intent-wait DesignCheck fixtures are authored.
The previous post-call ConsentError/document-refresh states were removed because that consent step no longer exists.

`PhoneViewModelTest` / `PhoneStartGateTest` cover lost-response old-key replay, received-429 wait/new-key intent,
independent document-read restrictions and age/PD gates; `ConsentViewModelTest` covers pinned version/body, double
taps, missing evidence and restart. Mock receipt times come from the fake server clock at Start, survive a replay,
and link only after verified or successful Register. These in-process fixtures do not prove backend durability,
unknown-provider recovery or retention cleanup; production enforcement/rollout belongs to FIN-364.

Consent rotation has no mock control-link injection; its UI correction needs a controllable REST fixture.
`phone_start` supports the existing closed `retry_forbidden` 429 fixture and the new `terminal_failure` 500 fixture.
The explicit-restart flow uses the latter; 429 wait and retained timing across restart/back from Call are covered by unit tests. Lost Call Start responses
retain their exact pending key when returning to Phone; terminal refusals expose explicit restart there as well. Receipt retention and
provider unknown deadlines are server-only; no new client environment setting or hard-coded waiting period exists.
The complete legal document is opened from `document.url`; publication of final production URLs/text remains
FIN-141. The mock's example.ru URL is a fixture and is not claimed as a published legal document.

Qase57/58 and updated registration steps are authored, mappings included. UI, Maestro, instrumented and
DesignCheck runs were not executed under the owner's instruction. Existing sign-in-related Qase cases use the
updated confirmation precondition; their other scenarios retain the same IDs.

The `auth.phone.restart` action abandons the local phone flow/key and reloads the document with both choices off,
without a POST. A received 429 keeps its captured server wait across this action and digit edits, including
A → B → A. The pending intent conservatively delays any edited number until the same wait ends, then uses a new key. The rate-limit explanation and original server retry time remain visible after edits,
restart and re-confirming the checkboxes. `phone_start` failures count in
the existing `expect_error_requests` check and happen before receipt creation; arbitrary error payloads are not
accepted. Qase59/`auth/phone_restart` exercises restart after the closed terminal 500 fixture. This is a synthetic
refusal, not a claim to reproduce a lost provider outcome or restart an independent backend process.


FIN-365 — Not covered yet: a refused consent-document GET (including 429 with `retryable:false`) and its
explicit restart need a controllable REST fixture; the mock control-link does not inject document GET failures.
VM/core regressions cover no GET before the captured deadline, an explicit restart afterwards without POST,
and a refused GET without a time restriction. The captured rate-limit explanation, safe server presentation and
retry time survive checkbox changes/blocked restart and clear after successful document loading. There is no automatic GET when the wait ends. UI/DesignCheck
runs are not authorized. The proposed document-refusal states use existing error/link/button primitives;
there is no matching Figma artboard, and source fixtures do not claim a visual approval or a completed UI run.

### FIN-375 checkbox semantics and server-message expectations

The checkbox's existing tag, click action, role, checked state and optional error are on the same
merged semantics node. Both phone choices still start unchecked; no consent or age gate is bypassed.
`confirm_phone` checks the actual checked state after each conditional tap. Qase57 now repeats that
helper twice after returning from Call and verifies that phone input stays enabled. Qase3/35 retain
their repeated-helper paths. Qase15 checks the current mock server's CSV/other-bank messages; Qase49
checks its rate-limit message while keeping draft, daily quota, history and retry assertions.
Existing case IDs and `scripts/qase-cases.json` mappings are retained. The failed run is documented in
[FIN-375](https://finassist.youtrack.cloud/issue/FIN-375); these corrections are authored without a new
UI, Maestro, instrumented or DesignCheck run.


Stage integration (FIN-385): the existing `mockE2e` harness replaces HTTP with `:mock:backend` and therefore
cannot prove the installed `stage` flavor's real HTTPS/SSE path, server-side persistence or provider configuration.
No stage e2e hooks are shipped. CI assembles both stage variants, checks identity/HTTPS/SDK separation and runtime
classpaths; the existing HTTP/SSE unit suites cover the transport. The Android → public stage flow
(synthetic auth → OFX → progress → operations/analytics → assistant) remains a separate device acceptance run;
it was not executed for this build-environment change. This is not a new screen or a claim of stage UI coverage.

FIN-394: `AnalyticsViewModelTest` covers tab resume after import/deletion, invalidating previous totals while
refreshing, failed refresh after deletion, and server retry restrictions. `AnalyticsUpdating*` and
`AnalyticsPreparingWithoutData` capture the preparation states. The reported SmartApe limitation for non-Russian source IP addresses is
infrastructure-specific; it cannot be reproduced by the in-process mock (VPN guidance is FIN-396).

FIN-394 delayed-projection regression uses the mock-only `analytics_pending_responses=0..100`
control-link fixture. It makes the next valid analytics GETs return `has_data:false`, without changing
imported operations or seeding the local import token. `analytics/import_preparation_retry` / Qase63
performs real UI imports and checks preparation → data and timeout → explicit Retry. This models a delayed
projection response, not the real inter-service transport. The corresponding unit tests cover countdown,
authentication/validation, user isolation, generations, retry refusal and failed recalculation polling.

### FIN-397 and FIN-394 assistant navigation and consent

The main bar orders Operations, Analytics, Assistant and Profile; `assistant=false` also hides the assistant tab.
The tab uses the existing saved back stacks, retaining the chat ViewModel, history and draft between switches.
A denied Ask exposes «Дать согласие» after Back; consent correction returns to the draft with Send enabled,
without submitting it. The next explicit send retains a still-valid operation key; uncertain/expired keys
retain their existing explicit recovery flow. Corrective RetryAnswer remains the same existing operation.
`assistant/tab_and_consent_recovery` / Qase61 covers the new navigation and recovery; Qase12 and the
existing assistant flows now explicitly send the first draft after granting consent. The consent layout uses
a fixed 24 dp gap after all checkbox/error content so scroll overflow cannot collapse it.
Design-check fixtures include the active assistant tab and light/dark consent recovery. These authored
flows and fixtures were not executed: no UI/Maestro/instrumented/DesignCheck run was authorized.

Layout-only consent spacing is represented by the authored `AiConsentLargeText` design-check fixture:
640 dp viewport, 150% text, checkbox error and form error; the fixed gap remains in the scrollable content.
### FIN-396 connection failure hints

Qase19/20/21/43/44/58 and their existing flows retain their mappings. `offline/no_network` asserts
the common connection title, internet hint and recovery action. `AndroidConnectionEnvironmentReaderTest` checks VPN detection, transport
changes between reads, no active network, absent capabilities and denied/unavailable system service.
Pure tests distinguish all four observations and verify that HTTP errors do not become VPN failures.
`ProfileOfflineVpn`, `ProfileOfflineVpnDark`, `ProfileOffline`, `DeleteAccountOfflineVpn`,
`ChatOfflineVpn` and `ImportGuideOfflineVpn` are authored DesignCheck fixtures. Existing
`DeleteAccountOffline` now uses the actual Offline.presentation. Chat/import fixtures inject UNKNOWN
for deterministic internet hints; these copy changes follow FIN-396 and differ from older Figma artboards;
no UI/Maestro/DesignCheck run is claimed. Native `ConnectionFailureHintTest#vpnHintUpdatesAfterRetry`
(Qase62) asserts the actual reader-to-UI wiring for the full-screen state and ErrorMessage, then switches
the fake observation before clicking the existing retry action and asserts the internet hint and absence
of VPN text and any «Нет сети» diagnosis, while retaining the actual ProfileTexts.CONSENT_NOT_REVOKED
feature outcome. It needs no real tunnel. This uses native Compose dependency injection because Maestro
cannot replace a CompositionLocal; no production or e2e application hook was added. The native test is
authored and compiled only, not executed under the current instruction.

Not covered yet: a real system VPN route and switching its tunnel during a failed request. The in-process
mock does not traverse a VPN; Maestro cannot create a configured VPN tunnel/provider on its own.
The Android system reader has Robolectric coverage and the presentation dependency has fake-reader
fixtures; neither is proof of a particular VPN's route or FIN-394's network cause.

FIN-397 review regressions: `assistant/tab_and_consent_recovery` also checks that mode-specific chats pushed
by Analytics/Profile cards have Back navigation to their parent and do not impersonate the saved Assistant tab.
A flag update while the main layer is open leaves its tab bar intact; the disabled-at-entry variant remains
`analytics/blocks_switched_off`. Native `AiConsentSpacingTest` checks measured geometry after scrolling,
including the form-error variant, instead of treating an unchecked snapshot as a spacing assertion.
The native test is in `app/src/androidTestMock`, with its Qase mapping, and is compiled without execution.

FIN-397 history refresh retains loaded earlier pages only when a shared message id proves a contiguous
boundary. A non-overlapping latest page starts pagination again from its own server boundary, so new
messages cannot hide a middle gap behind a cached `hasOlder=false`. Reads started before an accepted
RetryAnswer are invalidated by its replacement revision; stale pages cannot restore the failed answer or
move its replacement after later questions. Unit tests cover delayed latest/init reads, replacement
position and an older-page request crossing a refreshed pagination boundary.

FIN-397 — Not covered yet: restoring a Chat/Consent navigation entry after actual process death with
`assistant=false` is not represented by a mock Maestro flow: the server and authentication state of the
in-process backend also disappear on death. Both route entries guard the boundary before screen/ViewModel
creation and redirect disabled entries to Feed; a live composed entry keeps its snapshot. This branch is
compiled and inspected, without a claim of process-death UI execution.
FIN-396 screen transitions use native state/reader injection to assert the real ProfileContent and
HistoryContent fallback and Retry branches without introducing a production/e2e state hook. The lower unit
layer drives the real ViewModel failure/retry chains; these native cases are authored and compiled only.
A confirmed DELETE removes its source from the client list immediately; a later failed read keeps an
available read retry and cannot redisplay the deleted row. The success notice says analytics is updating,
rather than claiming its asynchronous projection has already completed.

`UploadHistoryDeleted` also differs from the existing Figma artboard: the success text is now
«Загрузка удалена, обновляем аналитику» instead of «Загрузка удалена, аналитика пересчитана».
The DELETE confirms removal of the source; publication of its analytics projection can finish later.
Proposed artboard sync: update the `UploadHistoryDeleted` success notice to the new text. This copy
clarification is tracked in FIN-396 alongside PR #232; it does not claim visual acceptance.

FIN-397 shared-policy and history regressions: Qase61 keeps `assistant=false` throughout Consent/Back,
consent correction and tab save/restore, then resets it only at the end. The bar and all route guards use
the one composition-root snapshot. Unit tests separate locally accepted Ask ids from GET pagination,
retain valid older pages across an overlapping refresh, preserve endpoint Retry-After while original refusals remain scoped to their
before_id after a stale-page presentation is discarded, and repeat invalidated latest reads with the original stream skip id.
A failed stream's authoritative status can therefore finish preparation instead of stranding the composer.

FIN-397 configuration stability: Qase61 now rotates LANDSCAPE_LEFT → PORTRAIT after live false and asserts
that the saved draft and zero questions survive Activity recreation. Qase68 starts with false before
sign-in, enables before first Analytics entry, then disables again and rotates before entering Consent.
Policy unit tests retain the same policy through a new owner backed by the layer ViewModelStore and
verify that a genuinely new false policy denies entry. The authored orientation flows were not executed.

FIN-397 retry context: all history mutations reconcile the first-message boundary. A new `before_id`
clears only the old original-request refusal/presentation and retains the exact captured endpoint wait.
Stale page replies contribute only conservative endpoint waits; stale latest-read failures cannot mutate
the newer request gate. Unit regressions cover both completion orders of overlapping latest reads,
new boundary after false, false plus wait, and first-answer replacement changing the boundary.


FIN-401/402: actual expenses remain one of four fact tiles; a fresh current period may append a fifth forecast tile.
The unpaired forecast spans the row, so the global calendar-history explanation is readable. No-data periods return
an empty tile group rather than zero. `ForecastHistoryTest` covers the rolling year, union of multiple OFX periods,
non-adjacent full months and gaps, including a long missing interval compressed to a range. `AnalyticsForecastHistory*` design checks are authored with the rolling-year
JSON response; both new history states passed locally on Robolectric. A full repeat of the 22 AnalyticsDesignCheck
states reached its 300-second process timeout.
The new Maestro flow was not executed locally. Qase case 71 was created after explicit approval.

FIN-401/402 review regressions extend the same Qase71 flow with the saved September context after selective
statement deletion, explicit month selection after restoring the previously selected month (FIN-391), and scoped-search assertions.
The `no_data` golden is composed from real synthetic mock state with another month still present (`has_data=true`),
not the account-wide empty state. `AnalyticsNoData`, `AnalyticsNoDataDark` and `AnalyticsNoDataTilesHidden` design
checks cover the server explanation and the local fallback when the tile block is disabled. Caption unit tests
also cover absent, unsupported-version and missing/blank server text. A supported visible server message replaces
the local caption to avoid duplication; hidden/unsupported blocks retain it. The extended Maestro flow remains
authored without a UI/device run. All three new Robolectric captures passed locally (48-second build), and their
images were inspected for the single explanation and absence of monetary tiles. No approved Figma artboards exist
for these no-data states yet. The targeted unit pass (37 tests: mock texts/history/golden plus caption fallback)
passed; malformed or absent forecast history retains the calendar-month rule without guessing any month list.


FIN-399: screens backed by GET requests use `PfRefreshContainer`; its bounded non-consuming scroll target also
accepts pulls on empty/error states. Refresh keeps the current query, period, filters, shown data and draft; each
read has a pending-request guard and retains its `RequestRetryGate`. The import-result screen is an immutable
receipt and is not refreshed; upload/delete/send/retry-answer actions are never replayed by a gesture. The
Maestro flow is authored, not run locally (no device/UI-test authorization). ViewModel tests check deduplication,
failed refresh retention and Retry-After separately from rendering.
Qase70 also covers a failed Month→Year switch followed by failed and successful pulls of the shown Month.
The current-read `analytics.retry` disappears when that read starts, so the flow then waits for the real
`ds.refresh.indicator` tag to disappear before asserting the selected September and separate
`analytics.period.retry` notice. That tag exists on the real indicator only while reads are pending; the flow
does not require seeing a possibly brief spinner. A second offline pull makes the current-read Retry available,
and its online completion must still keep the failed target. Only the separate period Retry opens Year. Qase70 is updated;
this regression was checked statically without executing Maestro, a device test or the UI workflow.
FIN-391: the strengthened Qase10 flow above is authored, not executed in this change. Unit regressions
cover independent dates even across years, first Year→Month/Quarter and Quarter→Month visits,
inclusive partial coverage and exclusive complete range ends in the profile zone, and failed selections.
The mock boundary rejects coarse date keys before consuming publication-delay fixtures. No stage
HTTP responses are substituted; FIN-386 owns the shared mock/stage suite wiring.

FIN-391 failure path: Qase73 is mock-only transport recovery; resume does not submit a failed switch.
Unit tests cover exact target retry, request_retryable=false, captured Retry-After, expiry without an
automatic request, new intents and duplicate taps. AnalyticsPeriodChangeFailed/…Dark and
AnalyticsPeriodChangeRetryBlocked are proposed artboards, authored/compiled without snapshot execution.

FIN-391 review regressions: Qase73 also makes «Не подписка» fail while the period notice is visible;
both messages keep their meaning and the period action retries only its saved target. The proposed
AnalyticsPeriodAndDismissalErrors artboard and a VM test cover the combined state. The same segment
cannot bypass the period gate during Retry-After or an original-request refusal.

FIN-411 adds the separate «Последние 12 месяцев» / «Последние 3 месяца» choices. Mock ranges use the union of completed upload coverage, including empty statements; `rolling_year`/`rolling_quarter` remain singleton keys, with exact edge facts and missing coverage kept distinct from zero. The calendar month action uses the full YYYY-MM key even when a point is clipped. Qase76 and rolling_periods are authored without UI execution. Canonical contract: fin-assist/contracts d827664 (FIN-413); the complete calculation and OpenAPI corpus is synchronized to that immutable revision after FIN-398/400/403 (PR239) integration; no test exclusions were added.

### Small passcode viewport (FIN-389)

`PasscodeViewportTest#wrongCodeFitsUnlockAndDeletionOnSmallViewport` ([PLAINFIN-77](https://app.qase.io/case/PLAINFIN-77))
checks all three error modes at heights 640/720/760 dp, font scales 1/1.5/2 and system insets 24/24 or 48/48 dp
(54 combinations), including the longest last-attempt text. Error appearance and remeasurement with the same
error must reveal it automatically, without a test-driven scroll. Maestro can find a clipped text node,
so the native test additionally verifies text layout overflow and compares full/visible bounds of the error, dots,
keys and footer; it also clicks digit/delete/forgot actions. Existing Maestro cases 5 and 18 cover the real wrong-PIN
transitions and recovery. The body measures space left by the actual header and system bars; compact keys fit both the 56 dp minimum
and the scaled digit line height. The 390×844 design-check layout keeps its original sizes. At larger text
sizes, only the introductory area scrolls; its measured range automatically reveals the dots/error while the
keypad stays fixed. The test compares keypad geometry before/after error and checks every digit/delete/footer.

`PasscodeViewportTest#errorFreePasscodeFamilyShowsDotsAtLargeFont` ([Qase78](https://app.qase.io/case/PLAINFIN-78))
checks all seven error-free setup/change/unlock/confirmation states at the supported portrait boundary
360×640 dp, font scale 2 and 48/48 dp bars. The range observer reveals dots even without an error.
After automatic visibility/action checks, a separate manual scroll-up assertion verifies that a digit-only
recomposition with an unchanged range respects the user’s position. The regression scope is portrait
640–760 dp, font scales 1–2 and system insets 24–48 dp; the regular 390×844 dp design checks remain the baseline.

The range observer uses `collectLatest`: a Default-priority scroll may yield to UserInput while collection
must survive. `PasscodeViewportTest#rangeObserverSurvivesHeldUserDrag` ([Qase79](https://app.qase.io/case/PLAINFIN-79))
covers this with real `down` and `moveBy` events without `up`, then recomposes the screen during the held gesture.
The positive initial range/end position and reduced value before any state change acknowledge an active drag;
a changed measured range while the pointer is down makes the automatic scroll yield. A stationary event-time
sample and `up` avoid a fling. The next range must reveal the end again, with an explicit guard against clamping
masquerading as recovery; the next error also passes the strict geometry helper. The plain-`collect` df2a1da8
baseline failed on that later range (expected 52 px, actual 0), while `collectLatest` passed. No production hook,
private mutex access, synthetic cancellation or wall-clock sleep is involved. The previous Not-covered claim
was disproved by this real gesture experiment and removed.


### FIN-416 response updates, history and source ownership

The client implements REST FIN-418 (`gen/go/v0.11.0`, contracts `60fa09c0224b7432b8567994c6863a0acd22bd4d`).
The PUT carries the shown month and transfer mode; its successful `AnalyticsBlockUpdate` immediately replaces
only the listed complete blocks in the matching whole snapshot. Other blocks, global timestamps and calculation
metadata remain unchanged. The client never subtracts row amounts or hides a payment speculatively.
A user-wide minimum calculation version protects every period. Marked or older GETs change only the notice;
ready GETs at or above the minimum replace the whole snapshot. An unknown mutation result retains old data and
keeps its command warning independently from the read freeze. After any completed unknown command, explicit
«Обновить аналитику» releases all unknown read freezes without resolving outcomes, lowering the minimum version,
retrying a PUT or clearing its refusal. A ready GET can then update the screen, but the warning remains. Import,
deletion and a cache miss never release the freeze automatically. 403/404/429 on a later retry do not prove an
older unknown attempt failed. A date OUT_OF_RANGE 400 establishes that the current assignment is not saved;
context-unavailable 422 means already saved. Both retire the obsolete month and read the
current default once, without retrying the PUT automatically. Merged FIN-420 clarifies the 400 validation ordering and
unknown recovery semantics without changing the REST shape. The mock keeps its published regular metric for
GET at the applied calculation version; the mutation response uses fresh source facts. Pending GETs may therefore
still contain the old row, which the client must not use to overwrite the complete block received from PUT. Server durable-delivery implementation is separate.

An original command refusal is scoped to the verified owner, payment, params, actual source zone and the known
ready calculation version at command start. An explicit new command with changed params/zone or a strictly newer
accepted ready version retires only that refusal; its endpoint wait survives. The same-version GET does neither.
These states are memory-only in the owning ViewModel; configuration retaining that VM keeps them. Losing the VM
also loses its financial cache and resumes with a fresh read, never a claimed command success.

`AnalyticsSnapshots` stores at most 32 whole ready responses by normalized period/date/transfer mode. Import and
upload deletion invalidate them; category updates retain a still-valid snapshot. Uncached marked responses show
preparation. The existing three-second read cadence stops after ten attempts with an allowed Retry. A monotonic
generation rejects stale successes and failures. Pending regular-block writes serialize, repeated pending taps
are ignored, and reads do not run while a PUT is in flight. Known failures resume only an allowed interrupted read.

`SessionRepository` provides the confirmed user and login epoch. Before that confirmation no financial data or
request is exposed. Logout, a different account, or a new login epoch clear snapshots, required version, uncertain
mutations, page cache and gates, and retire old jobs. Owner checks also reject uncancellable completions. Existing
`RequestOwner` tags bind all analytics HTTP calls and its quota read to that login, including authenticator retries.
No financial cache is persisted. Response-owned `time_zone` controls calendar rendering; an unsupported zone
shows an explicit notice even over cache, retains it across transient failures until a supported answer, and never silently substitutes the profile or transport offset.

The embedded monthly-chart page contains at most 60 calendar months. Edge scrolling loads opaque adjacent
cursors in both directions; at most three neighbouring pages remain, and LazyRow composes visible columns.
Selected month identity and selected-period notes survive prepending/eviction. Pages from another context,
revision or zone are rejected. Failed pages retain points with a gated explicit retry. OUT_OF_RANGE clears pages
and refreshes the current selection once; repeated expiration never starts an automatic loop.

Optional block/tile `help_text` is shown through a 48dp info action and a scrollable sheet, including locked and
unknown-version blocks. Main contents and concise lock reasons remain visible. FIN-415 fixes for the touched
facts/forecast and chart-selection flows are synchronized from main. Proposed light/dark help and history design
artboards are authored; approved Figma artboards for these new states do not yet exist.

JVM scenarios cover full-block replacement, version barriers, missing/malformed data, owner changes and late
responses, page ownership/eviction/errors, source-zone conversion and HTTP routing. Maestro cases 81, 82, 83, 88 and
the changed case 71 are authored with Qase mappings. UI, native and design-check execution was not authorized and
has not been performed. Backend post-commit failures and durable delivery still need service integration coverage;
the mock proves client transitions, not cross-service durability.
FIN-416 adds the closed mockE2e-only control `response_time_zone=unsupported|default`. `unsupported` substitutes
only response metadata with `Area/Future_City`; calculations, stored import results and profile zone stay unchanged.
The production client resolves every ImportConfig, ImportResult and dated OperationsList source zone before accepting
its dates; no local profile/offset alias substitutes it. Unknown-device-zone warnings remain until a supported response;
no retry loop is started. Date-boundary JVM tests cover DST midnight gaps, skipped dates and historical second offsets.
Proposed artboards without Figma frames: ImportDownloadPeriod/ImportDownloadPeriodDark/ImportDownloadPeriodFailure,
ImportUnsupportedZone/ImportUnsupportedZoneCold, ImportResultUnsupportedZone/ImportResultUnsupportedZoneCached,
MainUnsupportedZone/MainUnsupportedZoneCold and SearchUnsupportedZone. Design checks are authored, not executed.


FIN-416 review regression adds deferred period selection during a refused in-flight command to `analytics/period_change_retry` (Qase73), and proposed `AnalyticsUnknownMutation`, `AnalyticsUnknownMutationEmpty`, `AnalyticsUnknownMutationRead` design states. These additions are authored only; UI and DesignCheck were not run.

FIN-416 (upload copy and search reset): existing Qase2/9/14 cases and their unchanged path mappings in
`scripts/qase-cases.json` cover the extended flows above. `DownloadTextsTest` checks first/loaded/empty states,
the server's real suggested range, inclusive end date, year boundary and profile offset; empty or reversed
ranges fall back to the instruction without dates (including a future imported operation). Host JVM
`UploadDownloadControlTest` checks unchanged download URLs, the shortened privacy notice/link and an uncut
button label at 320dp/2.0 font scale; `SearchResetControlTest` checks the separate reset position and callback
before/after chip scrolling at 320dp/1.3. Existing ImportGuide/Search design-check fixtures use the changed
content; the loaded import fixture now has the correct suggested update range. No emulator, Maestro,
instrumented or DesignCheck execution was performed for this change.
The registration flow checks «Скачать за год» before the first import and scrolls to «Загружу позже»;
the longer download instructions and full-width buttons may move it below the initial viewport.

FIN-416 binds rolling choices to their calendar tabs, aligns them to the content right edge and removes the whole all-time period row. Qase75/76 keep their existing scenario identity and are updated; all_time also retains the fixes already verified on main in FIN-415. Qase80 covers returning to a calendar year/quarter by tapping its already selected segment. UI/Maestro/DesignCheck were not run. The implemented dismissal client and Qase81 coverage are described in the response-update section above. The future Temporal saga is tracked separately in FIN-419.

### FIN-415 Maestro selectors synchronized with the 0.2.16 hierarchy

FIN-415 synchronizes the 11 flows diagnosed in [UI tests run 41243782169](https://github.com/fin-assist/android/actions/runs/41243782169)
with the release/0.2.16 accessibility hierarchy. Period labels, transfer labels, tile amounts and forecast explanations
are checked inside their tagged parent (`childOf`); combined text/id selectors are kept only where the hierarchy
actually puts both on one node. Scroll targets are the checked category row, amount or unavailable/forecast text,
so a visible container border cannot prematurely finish a scroll. The existing checked-state assertions stay:
the captured Android hierarchy exposes selected segments and chart columns as `checked=true`.

Qase mappings are unchanged: 10, 14, 20, 21, 30, 58, 70, 71, 73, 74 and 75. Qase20 separates a failed history
read from a failed pending Ask. The draft is typed before offline control; read recovery, failed send/retry and
online resume cannot create its question or spend quota. Online resume restores the input through a successful
GET; a separate explicit Send reuses the pending question. Quota 4 → 3 of 5 checks one new accepted question
without requiring two answers to be simultaneously visible in Chat's lazy list. No application runtime or
accessibility semantics, timeouts, suite membership or skip rules change. These revisions were checked statically;
all 11 flows and the full mock suite still require a separately authorized UI run.


### FIN-415 remaining post-merge flow steps

The [post-merge UI run 41281091191](https://github.com/fin-assist/android/actions/runs/41281091191) at `87f218cf`
passed the original failure points but exposed two later steps. In `analytics/all_time`, the 20-second scroll
stopped among September 2026 operations before the January 2024 target. The flow now searches for `FIN-400`
through the normal query field without clearing the inherited period or analytics filters. It requires exactly
one result with the fixture's name, January 2024 date and debit amount, then checks the retained period,
expense kind and analytics scope. The matching income from the same fixture must remain excluded.
In `analytics/facts_and_forecast`, `PfOptionRow` puts an untagged view between the tagged option and its text.
Both scroll and tap now select `analytics.period.option` with the month in `containsDescendants`; the selected
month is still verified separately under `ds.period.pick`.

Qase71 and Qase75 keep their existing flow paths and IDs. Their scenario descriptions are synchronized;
these follow-up revisions were checked statically and have not been executed on a device. Application runtime,
accessibility semantics, timeout values and suite membership are unchanged.


### FIN-415 count spacing and return from history

The [next post-merge run 41284429429](https://github.com/fin-assist/android/actions/runs/41284429429) at `a36a5178`
found the old January 2024 operation, but the exact count assertion used an ASCII space where `countWithNoun`
outputs NBSP. The selector keeps `operations.search.count`, the exact count 1 and singular noun, accepting only
normal/NBSP/narrow-NBSP separators. The same run passed the period picker in Qase71 and reached the final
statement deletion. History has `ds.back` and no bottom tabs; Qase71 now leaves History before selecting
Analytics for its final no-data check. Qase75's scenario and mapping are unchanged; Qase71's navigation step
is synchronized. Both revisions are checked statically against those captured hierarchies, not by a new device run.

## FIN-427 Maestro assertions

The nine failures of [run 41819133288](https://github.com/fin-assist/android/actions/runs/41819133288)
are corrected in the flows without changing application runtime or the mock API:

- Match download labels in the child Text of `statements.upload.download`, including both later
  reimports and the empty-state yearly download. Registration explicitly prepares an empty mock ledger.
- Known refusal/terminal error fixtures assert their safe server message, not the feature fallback.
  Offline fallback, unknown-outcome warnings and exact mutation-count checks stay separate.
- Re-login after logout completes the real phone/passcode UI, preserving the same backend process.
- Date replacement taps the trailing edge of the field before backspacing, asserts it is empty,
  then verifies the exact replacement before checking reversed/future bounds and online/offline submission.
- Full-block retention observes the promoted payment before waiting for the existing bounded polling
  pause. It scrolls to the actual period picker and uses explicit Retry after allowing ready responses.
- History swipes stop as soon as the target month is visible, with a ceiling of 400 gestures per
  direction for the 322-month fixture. Both endpoints and retained month selection remain assertions.
- The source-zone search uses `Krabsbir`, present in the bundled fixture, before checking cache recovery.

The Qase mappings and scenario count remain unchanged. These are authored corrections with static
checks; they do not report a successful emulator run. Local/UI workflow execution needs the owner's
separate authorization.

FIN-427 post-merge follow-up (run 41831477238): Qase73 reaches the deferred Year response
(the Year segment is selected and its rolling-year control is shown), but the retained body scroll
leaves `ds.period.pick` above the viewport. The flow now waits for that response-specific control,
then scrolls to the period picker and still requires exactly 2026. Qase14 reaches the final empty
state after deleting all three uploads; Profile routes directly to Upload when no statements remain,
so the flow waits for `statements.upload` rather than a History upload button. The yearly label and
privacy checks remain. These follow-up steps have static evidence, not a new emulator execution.

The same run passed the full PUT-block/polling/retry section of Qase81, then failed before
the separate offline mutation: resuming the offline control link inserted a read-error notice and
pushed the already located RBC action below the viewport. The flow now scrolls to that action
after the link, and again after the mutation error before asserting that the payment is retained.
The failure is not evidence of a missing payment: its RBC row remains in the captured hierarchy.

FIN-427 follow-up to [run 41844441379](https://github.com/fin-assist/android/actions/runs/41844441379)
at `1610ed6b`: Qase82 reaches the final ready September response after the refused mutation and
bounded polling pause. Removing the notice retains the body offset: the hierarchy shows September
totals and the selected `2026-09` chart point, while the period picker is above the viewport. The flow
now scrolls to that picker's actual September label before requiring it and the selected Month segment.
Qase89 passes the terminal refusal, explicit read and exact one-PUT check, then fails before the
separate offline mutation: its resume-time read-error notice pushes the RBC action below the viewport.
Both isolated offline sections now locate and centre that action after applying offline, before tapping.
The unknown-outcome, preparation/empty-state and mutation-count assertions stay intact. These changes
have static artifact/source evidence; the changed flows have not been executed on a device.

The completed run has 71/74 Maestro and 10/10 native passes (Qase run 54: 81/84, no
skipped/untested cases). Qase81 is the third failure: after the bounded polling pause,
`ds.period.pick` is clipped to `[179,212][901,231]` and has no visible label child. Its container
therefore satisfies Maestro's visibility check before any scroll, but the exact September check fails.
Qase81 and Qase82 now scroll to the September Text child of that picker, preserving the exact month
assertion instead of accepting a clipped wrapper. No extra refresh, polling budget or fixed delay is added.
Qase73, Qase14 and the full 2000–2026 chart-history traversal pass in this run; Qase81's offline
section remains unexecuted because the earlier header assertion stops the flow.

FIN-427 follow-up to [run 41855090174](https://github.com/fin-assist/android/actions/runs/41855090174)
at `f4064c8e`: Qase89 now passes completely. Qase82 stops earlier, before the final month check:
its first retention observation scrolls to the category block wrapper, then waits for an offscreen row
while the four marked responses expire. The failure hierarchy already contains the ready unavailable
category block. The flow now holds marked responses while locating and centring the actual category row,
asserts retained data and no unavailable message, then waits for the bounded polling pause. It sets
a four-response countdown and uses the existing explicit Retry to start a fresh client budget.
After that marked Retry response, the unavailable block must arrive through automatic polling;
the flow centres the stable category heading for this observation. There is no injected ready
response, further control link/refresh, fixed sleep or change to the app's poll budget.
This explicit phase boundary also handles a slow observation that consumed all ten earlier reads;
changing the mock counter alone cannot reset the client's budget or resume a paused read.
This revision is checked against source and captured commands/hierarchy, not by a new device run.


### FIN-436 Qase review corrections

`statements/upload_and_history` keeps Qase14 and its existing mapping. The added shared assertion
`assert_october_operations` runs after duplicate import and after deleting only the first October source.
It checks the retained Month/October selection, income +1 200 ₽, one income result, then removes only the
income-kind chip while retaining the exact 1–31 October range and Analytics scope. Exactly three results
are required, each identified by its own row descendants: «Поступление», 6 October, +1 200 ₽;
«Яндекс Такси», 5 October, −389 ₽; «Пятёрочка», 3 October, −754 ₽. Deleting the other October source
requires income 0 and an empty search for that same month with kind removed; an offscreen row or a smaller
upload count is not evidence that operations survived or were removed. Existing import/result/download-copy,
history-count and final-empty-state assertions remain. These additional UI assertions have not been run.

Qase3/4/35/41 now state the existing mock controls and setup order documented in the coverage rows;
no app/runtime or mock-control behavior changes. At FIN-436 Qase54 retained its manual status (subsequently
automated by FIN-446) and Qase60 kept its
identity/history with the pending status described above. Qase81 now describes only its mapped Maestro
observations. Its successful result does not assert exact replacement of every response field, delayed
version/context rejection or cross-service post-commit recovery; the former two are lower-level JVM
checks, and the latter is [Qase90](https://app.qase.io/case/PLAINFIN-90). At FIN-436 it was a Draft / Manual
specification; FIN-437 subsequently automated the real cross-service scenario in bank-bff PR #36 and
recorded independent Passed runs 57/58. It remains outside the Android UI mapping. The FIN-436 edits
did not create a new execution result.

### FIN-438: UI recovery after a saved dismissal fails to compose

`analytics/regular_post_commit_retry` maps to [Qase91](https://app.qase.io/case/PLAINFIN-91).
The existing mock-only control uses `error_operation=analytics_dismiss&error_scenario=post_commit_failure`.
Authentication, payment/context validation and the mock assignment still run; the assignment and original
calculation target survive the safe `INTERNAL_ERROR` 500 with `retryable=true`. The existing `terminal_failure`
and offline scenarios keep their pre-commit semantics. A JVM regression checks the committed assignment,
unchanged published GET, stable original target through the successful retry and later ready calculation.

The flow checks the error, unknown-result notice and the whole retained card. While the persistent fault
is enabled, `expect_error_requests=1` proves there was no hidden PUT. After the fault is turned off,
resume still retains the old row/warning until an explicit second tap of «Не подписка — РБК».
`analytics_recalculating_responses=100` withholds ready GET throughout, so only the complete PUT response
can explain the replacement. It positively checks the promoted tablet row, the unchanged remaining rows,
total/count and the rest of the list, rather than treating an offscreen RBC row as deletion.

The oracle comes from the actual bundled preload, not calculation/design-only goldens: RBC1500 +
Rostelecom1089 + phone1000 + tablet1000 + animal charity500 + Premier99 = 5188 RUB and 6 payments.
The original visible rows end with phone; «Ещё 3» totals1599 RUB. Removing only RBC leaves3688 RUB,
5 payments, promotes tablet and leaves «Ещё 2» totalling599 RUB. A JVM test reconciles these exact
source-derived values against the API block. This mock UI case does not claim PostgreSQL/outbox
durability or two total PUT calls from the injected-failure counter; backend Qase90 owns those server
guarantees. Qase81/Qase89 and their historical results are preserved. The original FIN-438 change authored this Maestro flow without UI execution; the focused repair
execution is recorded below. Native and DesignCheck execution was not part of the repair.

FIN-438 follow-up to [UI run 41924455407](https://github.com/fin-assist/android/actions/runs/41924455407):
shard 4 stopped before running its flows because Maestro rejected the parameterized
`scrollUntilVisible.direction` in `assert_regular_retry_card.yaml`. The helper now returns to the
card heading before locating the changing row with the literal `DOWN` direction. This finds both
the original RBC row and the promoted tablet row from the same starting position; row-scoped
merchant/amount, total/count, remainder and explicit-retry assertions are retained.
Focused local runs then exposed clipped rows and header totals after the fixed unknown-result and error
notices appeared. Centring a title or an action against the whole screen could move the data behind those
notices. The helper now scrolls to the same scoped header/row/footer value pairs that it subsequently
asserts, without centring; changing-row actions are observed separately after their merchant/amount pair.
After the error notices appear, the flow enters that helper directly: an extra downward search for the
retained RBC action could skip it above the smaller viewport. The helper still checks RBC and its action.
Before the explicit retry, the flow likewise locates the retained RBC name/amount pair before scrolling
to its action; centring only the action could leave the name hidden behind the fixed notices.

### FIN-440: preserve Qase81 polling pause until explicit retry

In the same main UI run, `analytics/regular_block_update` reached the paused notice and enabled retry,
then its upward month-picker scroll triggered pull-to-refresh (`ds.refresh.indicator` in the captured log).
After the control link released marked responses, that refresh consumed the ready GET before the explicit
retry tap. The month-picker scroll and exact September assertion now run after the explicit retry completes.
The paused notice and enabled retry are checked immediately before releasing the hold. The month/context,
retained card, replacement row, unknown-result, offline and mutation-count assertions remain; runtime,
poll budgets, control hooks and timeouts are unchanged. Qase81 keeps its existing ID and flow mapping.

Focused repair verification (PR #271): Qase91 passed in 139.623 s on source
`b8af1fae97c22a7bb169d8827a6e3c20e6e01f64`; Qase81 passed in 272.007 s on source
`21b85817ad73abe114343db78c02a620337daa61` (its flow is unchanged in the final candidate).
Each was run separately with the existing runner, without native tests, on a disposable API34
`google_apis` x86_64 Pixel6 AVD (1080×2400, dpi420, Europe/Moscow, animations disabled), using
the workflow-pinned Maestro2.11.0 and a 900 s process deadline. The runtime APK is unchanged
from main `f962ddf0`; this repair changes only YAML and documentation. Earlier local failures
and the original CI/Qase run59 remain recorded; these focused local passes are not a full
post-merge UI-suite result.

### FIN-441: reveal chart endpoints before selecting them

In [UI run 41977584876](https://github.com/fin-assist/android/actions/runs/41977584876), Qase83 reached
January 2000 but the repeat stopped while the point was only partly visible at the LazyRow edge.
Its tagged bounds were x35–128, while the actual visible content started at x98; Maestro tapped
the tagged centre x81 outside that viewport, so the month remained unselected. One final drag
toward each history edge now exposes its endpoint before selection. Both 400-gesture ceilings,
the covered-zero/checked assertions, retained January selection during reverse paging and final
October selection remain unchanged. This repair changes no runtime, fixtures or timeouts.

### FIN-442: financial refunds and category changes that split own transfers

Two `mock-only` flows import small synthetic OFX files through the existing document picker after deleting
the preloaded statement. `refund-categories.ofx` separates an automatic same-name refund from a normal
incoming credit; explicitly assigning Refund to that credit moves 300 RUB from income to expense reduction.
`own-transfer-split.ofx` contains two accounts and two pairs, distinguished by amounts 1000/2000 RUB.
The flow selects an expense-only and an income-only category, checks the replacement of one pair by two
ordinary records and reopens both sides. It does not infer a split from the absence of an offscreen row.

`E2eStatementsTest` validates these exact files through the mock operations API and accounting engine,
including category targets, totals, replacement identities, reimport persistence and the both-directions
category rule. Those JVM checks do not establish device/UI acceptance. Qase92/93 describe the assertions
in their respective flows; other refund-target branches, cross-month behavior and reimport are not
reported as executed UI steps. Execution status, source and environment evidence are recorded in PR #273
and the linked Qase runs. Local execution follows the owner-permission rule above. Qase8 and Qase38
retain their existing scope and result history.

The local FIN-442 execution exposed two setup boundaries. Loading the bundled 1969-operation ledger
can exceed the sign-in helper's 30 s budget (46.242 s observed). These two flows first launch the app
and wait at most 60 s for `auth.phone.document`, rendered only after a successful backend document
read. With the app already open, service sign-in can leave `PfApp`'s first-run setup layer visible even
after the hook saves the passcode. Both flows therefore use the existing `login.yaml` UI path, including
passcode setup and its completion callback. Shared helpers, authentication deadlines and financial
assertions are unchanged. Preserved local attempt reports and final execution evidence are linked in
[PR #273](https://github.com/fin-assist/android/pull/273).

The Qase93 run also exposed FIN-443: scrolling the unfiltered category list toward Income crashed on
`Расходы-cat_repair`, because the mock directory included two spelling aliases (NBSP/space) with the
same ID. `Catalog.categories` now returns one entry per ID, keeping the first registered category and
both bank-name lookups. It preserves category IDs, kinds and financial assignments. `CatalogTest`
reproduces the duplicate-ID failure before the fix and checks both aliases, directory uniqueness and
the retained Salary category. Qase93 keeps its ordinary scroll to Salary, so the crash is not bypassed
with a search query. This is a mock-backend correction; shared UI helpers and control hooks are unchanged.

Qase93 navigates the long unfiltered directory in two observable steps: scroll to the Income heading,
then to Salary inside that section, each with Maestro's existing 20 s default. A single scroll across
the whole directory exhausted that navigation budget on the local Pixel6. No financial assertion is
removed, and the ordinary path still traverses the expense categories where FIN-443 reproduced.

### FIN-444: post-merge UI evidence and selected-year visibility

The main `52aa2b3` run [42004117157](https://github.com/fin-assist/android/actions/runs/42004117157)
passed the new refund and own-transfer split flows. Qase73 stopped at its final year assertion: only a
20 px fragment of `ds.period.pick` was visible, so scrolling to the container did not reveal its title.
The flow now scrolls to the exact `2026` text inside the picker before the existing assertion. The
failed-period retry, deferred selection, independent command error and exact single-request check
are preserved, with their existing deadlines. Qase run72 retains the original incomplete/failing CI
result; focused local verification is recorded separately in FIN-444.

The focused local Qase73 attempt also reproduced the cold-start setup boundary documented for FIN-442:
preloading 1969 operations took 35.5 s, beyond the service sign-in's 30 s deadline. Both clean-start
phases now use the existing positive `auth.phone.document` readiness condition (at most 60 s), then
`login.yaml` through the UI. This completes passcode setup normally; shared helpers, authentication
deadlines and analytics assertions are unchanged. The failed setup attempt remains separate evidence.

The same CI run exhausted shard 3's 2400 s deadline. Its remaining flows could not finish after emulator
shutdown, and raw Maestro folders containing `?` then prevented artifact upload. Successful samples from
that run and three successful samples from baseline41986816591 now cover all 77 flow durations. The
four-shard model is 1865/1865/1842/1864 s, including the existing native reserve; Qase73 retains its last
successful CI measurement because its new UI setup has not been measured on CI yet. These estimates
are scheduling inputs, not a full-run acceptance result.

The coordinator now starts the existing shard runner in its own process group. On the unchanged 2400 s
deadline or termination, it sends TERM, allows at most 10 s for report flushing, then kills the entire
remaining group, even if the shell exited first. Only after writers stop does it finish the existing
`maestro-output.tgz` archive and remove the raw per-flow directory. A timeout or nonzero verdict stays
failed. Regression checks use real subprocesses, a TERM-resistant descendant and unsafe-name evidence;
they also check external termination and preservation of an already packed archive. Workflow triggers,
permissions, cache identities, artifact scope and application runtime are unchanged.

FIN-458 adds a bounded 5 s exit check after SIGKILL: the coordinator reaps the leader and
checks group members with `ps` until none remain or all are zombies. `D` and other live states
never count as stopped. If the group remains live or inspection fails, the shard fails and
leaves the raw evidence untouched instead of archiving/removing files with possible writers.
Deterministic regressions cover delayed `D` → `Z`, a persistent live group, unrelated groups,
zombie-only/absent groups and inspection errors; the real TERM-resistant descendant test
keeps its immediate exit assertion. The 2400 s shard deadline and 10 s TERM grace are unchanged.

### FIN-445: bring the selected month title fully into view

The full post-merge run [42017241060](https://github.com/fin-assist/android/actions/runs/42017241060)
finished all four shards within 2400 s and published all four artifacts. Qase73 passed; the complete
Qase run75 contains 86/87 Passed. Its only failure is Qase83's final October header check. The chart's
October point was already checked; `ds.period.pick` exposed only a lower fragment under the fixed
header and had no visible text child. Scrolling to that container therefore stopped too early.

All three returns to the period header now scroll to the exact expected month text inside the picker
(January2000, January2000, October2026). The selected-point and independent header assertions remain,
as do the complete 2000–2026 traversal in both directions, 400-gesture bounds and the endpoint-revealing
swipes of FIN-441. Cold setup reuses the positive document readiness and existing UI login already
used by Qase73/92/93. Shared helpers, application runtime, assertion deadlines and financial expectations
are unchanged. Single-flow Qase83 execution is recorded separately; run75 keeps its original failure.


### FIN-446: consent-document rotation through the UI

Qase54 and Qase55 keep their original IDs and history. They are mapped to
`auth/consent_document_rotation.yaml` and `assistant/consent_document_rotation.yaml`.
The full flows were executed separately on an owned API34 google_apis x86_64 Pixel6, native1080×2400/dpi420,
MSK, animations0, Maestro2.11.0, through stock `scripts/e2e.sh --flow ABS --no-build` with a real1800s
process-group deadline and no native/full-suite run. Q54 passed348.626s on source
`e80d681073385b5eb4a5b74972448da897126333` in [LOCAL run78](https://app.qase.io/run/PLAINFIN/dashboard/78).
Q55 passed360.281s on `7cbb056e62379087f98dc4f21a4fe2dcb70086a3` in
[LOCAL run80](https://app.qase.io/run/PLAINFIN/dashboard/80). The APK runtime was identical in both:
SHA256 `5b99abbf1f2a9d414d27f08925cef957b5165f116a6cbd88ab5ca666d22d19fa`.
The first Q55 attempt failed157.749s because the action container was a clipped strip below the error;
[run79](https://app.qase.io/run/PLAINFIN/dashboard/79) is retained. Scroll targets now require the visible
button text child, while assertions/probes/deadlines are unchanged. The later mock-only tag and documentation/
shard-duration updates do not change the exercised flow bodies or APK runtime. These are focused LOCAL
results, not full post-merge UI acceptance. The dedicated emulator was stopped after the tests.

To avoid assigning a median duration to these longer new flows, their successful local durations rounded
up to349s/361s seed `e2e-durations.json`, explicitly marked LOCAL/provisional. Existing CI samples and the
2400s timeout remain unchanged; replace these two estimates from successful main CI when available.

`MockConsentScenarios` uses the existing in-process APIs, with independent publication and document replies.
It limits request evidence and held reads to 128, exposes only counts/version sequences/key cardinality,
and reports overflow rather than returning a misleading truncated success. No phone, token or raw key is
included in snapshots or the control UI. The control parser is included only in mockE2e; it validates the
whole consent command before mutation, preserves omitted settings, and does not retry failed predicates.
Process recreation/clear-state resets fixtures. These controls do not model real client process death.

Qase54 uses ordinary Phone/Call/Register screens, pd-1/pd-2/pd-3 and a synthetic new number. Offline,
same-version and held reads retain the old Start prohibition; repeated taps allow one GET in flight.
A 30-second synthetic GET429/false wait survives Home/return, then explicit restart clears both choices
and performs a new read without sending the number. The second Start uses pd-2 and a different key.
The configured 60-second verification delay keeps the accepted call pending while pd-3 is published;
changing the delay after opening a stream would not shorten that captured wait. Register still sends pd-2
without a second document read or checkbox. A separate clean Register-refusal branch restarts Phone.
Exact deadline invariants and lost local evidence remain independent JVM checks.

Qase55 uses ordinary UI sign-in and a draft, with zero synthetic network latency before the assistant GET;
held responses provide deterministic concurrency independently from elapsed delays. Phase A checks
outdated PUT, offline and same-version reads, held duplicate taps, new-version tick reset and an explicit
PUT of version2; the draft and five-question quota remain and no Ask is sent. Phase B is a separate clean
original-refusal context: GET429/retryable:false remains forbidden after its captured three-second wait.
The existing four-second config overlay separates observations, but the elapsed predicate checks the
actual receipt; elapsed time alone never grants retry permission. The stale initial-GET interleaving is
covered by `AiConsentViewModelTest`, not claimed as an executed step of this UI case.


### FIN-449: simulated dictation through the real client UI

[Qase69](https://app.qase.io/case/PLAINFIN-69) keeps its ID and history, with one mapped native method
`VoiceDictationTest#dictationEventsPreserveDraftAndRequireExplicitSend`. A fake recognizer is compiled only into
`androidTestMock`; the production `ChatScreen` has an optional factory parameter whose default uses the existing
Android behavior. No Hilt override, new exported component, control-link command, HTTP fixture or runtime test flag
was introduced. `VoiceInput`, its session guard, permission launcher, lifecycle cancellation, fallback policy and
`ChatViewModel` are real. The Android adapter retains the previous SDK guard, ru-RU/free-form/offline-preference
request and final-only recognition callback. Robolectric exercises the default wrapper and the real composable.

Native Compose dependency injection is needed here: opening the existing config Activity would cause ON_STOP and
cancel a session before a recognition event could reach it. The test grants RECORD_AUDIO for its own app, renders
three synthetic earlier question/answer pairs and supplies ready/level/end/results/errors directly on the UI
thread. All waits are bounded; no audio service or microphone capture is needed. Test Orchestrator's existing
clearPackageData isolates this grant from Qase72's real permission-denial scenario.

The scenario checks preparing before ready, visible listening/progress, recognizing before final text, editable
append without Ask/quota use, explicit cancel, Back and actual ActivityScenario ON_STOP/RESUMED with late events,
including old callbacks during a replacement session. Two queued OnClick actions from the same semantics node
exercise duplicate input before the next composition frame; only one recognizer/stop is permitted. Silence and
permission errors retain on-device preference, model/service failures select the system provider on the next
explicit attempt, and unavailable services show the existing error. Only the final edited Send may accept one
question and decrement quota. A denied first attempt observes the real chat's request to open consent; the
subsequent navigation-result handoff is controlled, and its arrival must not send automatically. The actual
ConsentScreen journey remains Qase12 and is not claimed as executed here.

Focused local executions of the new native method and Qase72 are recorded in PR #277 / Qase, with the exact source and APK hashes. The first Q69 run reached the final cleared-field check but its assertion also included placeholder text; the check now compares EditableText independently and confirms the VM draft is empty. Real speech/audio integration, actual installed model/provider
behavior and felt haptic remain the separate Manual Qase94, without a fabricated Passed result. Qase72 and the
existing native tests remain in the normal suite. Production recognition behavior and HTTP contracts are unchanged.

### FIN-464: reach the retained unavailable card in Qase82

In [main UI run 42461258707](https://github.com/fin-assist/android/actions/runs/42461258707),
`analytics/recalculation_snapshot` passed the first ready unavailable response, then stopped after finding only
the clipped header of `analytics.block.expense_categories` during the next recalculation. The error-card assertion
failed while the card was below the viewport. The failed screenshot, hierarchy and command trace are preserved in
the original run; this observation alone does not establish a runtime regression.

The marked-snapshot phase now scrolls to the unavailable text itself, scoped to the expense-categories block, and
centres that text before asserting it. It still requires the retained error, the absent expense-category chart,
the bounded stop and explicit retry, recovery to ready, correct period and uninterrupted polling after a refused
mutation. Qase82, its existing mapping and financial expectations are unchanged. No runtime or timeout changes.
Focused execution and full post-merge CI evidence are recorded in the PR; authoring this correction is not a pass.

### FIN-470/471: recovery actions and period visibility

In [main UI run 42473581777](https://github.com/fin-assist/android/actions/runs/42473581777)
(`64648212`), Qase19 reached the healthy Analytics screen between `when.visible` and the
recovery tap. The hierarchy already contained loaded tiles when the disappearing retry action was
looked up. Only the recovery taps after `offline=false` are optional; the preceding enabled retry
checks, connection hints and subsequent loaded-screen/absent-error assertions remain mandatory.
Maestro documents optional single-command interactions in its
[conditions guide](https://docs.maestro.dev/maestro-flows/flow-control-and-logic/conditions).

Qase89 reached the selected Month and retained September financial cards after the unknown mutation
result, but the period picker was above the viewport after scrolling only to the Year segment and
showing the notices. Reveal the exact September text inside the picker before asserting it, stopping at full visibility
without centering: extra downward swipes at the top could trigger pull-to-refresh. Require the
refresh indicator to be absent immediately after scrolling. The
selected Month, second Year selection, explicit read recovery, unknown-result notice and mutation
request counts remain required. No runtime, fixture, timeout, Qase mapping or CI scope changes.

Both corrections are authored from the failed run's commands, screenshots and hierarchy. No new
local UI run was performed; successful execution is still required through normal main UI CI.
