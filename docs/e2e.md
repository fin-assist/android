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

```bash
# All flows and the native tests
scripts/e2e.sh

# Quick pass: flows tagged smoke, no native tests
scripts/e2e.sh --tags smoke

# One flow while writing it (Maestro Studio helps to find elements: `maestro studio`); --flow may be repeated
scripts/e2e.sh --no-build --flow .maestro/flows/assistant/ask_question.yaml

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

The flows run on four emulators at once (FIN-180), one job per shard, each with its own emulator:

- **Split.** `scripts/e2e-shards.py` spreads the flows so that the shards take about the same time: longest flow
  first, to the shard with the least so far, by the durations of the last measured run on CI
  (`scripts/e2e-durations.json`; a flow not measured yet counts as the median). Shard 1 also runs the native tests
  and gets fewer flows for them. The split is deterministic and every flow is in exactly one shard
  (`scripts/test_e2e_shards.py`); `python3 scripts/e2e-shards.py plan --shards 4` prints it. After a run that changed
  the durations a lot, refresh them from the merged report: `python3 scripts/e2e-shards.py durations
  build/e2e/maestro-report.xml` (in the `e2e-reports` artifact).
- **Build.** Every shard builds the APKs itself (`:app:assembleMockE2e :app:assembleMockE2eAndroidTest`, ~40 s from
  the Gradle build cache): the native tests and `:e2eCoverageReport` need the Gradle build anyway, a separate build
  job would add its time and an artifact round trip before every emulator starts, and outputs from the same build
  cache are the same classes the JaCoCo report reads.
- **Reports.** Each shard uploads its `build/e2e/` as `e2e-shard-<i>` (kept 3 days), whatever its result. The
  `Reports` job runs after all shards, whatever they ended with, unless the run was cancelled (`if: !cancelled()`):
  `e2e-shards.py gather` merges the
  Maestro reports into one `build/e2e/maestro-report.xml` and puts the execution data of every shard and of the
  native tests where `:e2eCoverageReport` reads them; then the coverage report, the summary, one
  Qase run and the `e2e-reports` artifact (merged report, coverage, every shard's folder). A shard that left no report
  — its job failed or timed out before the flows ended — is not lost silently: its flows go into the merged report as
  failures («shard i/n left no Maestro report»), and so does a flow missing from a shard's report. Flows are matched
  to the report by its `file` attribute, or by the flow's `name` when a Maestro version writes none (names are unique,
  `scripts/test_e2e_shards.py` checks it). Maestro is pinned in the workflow (`MAESTRO_VERSION`): a new version is
  bumped there and checked with one run.
- **Result.** The run is red when any shard failed, and also when the merged report is not a pass although every job
  was green (a shard's report that never arrived, flows missing or failed — `gather` lists them): the `Reports` job
  fails after it has published everything.


### Clean AVD snapshot cache (FIN-378)

The four shard jobs restore independent writable copies of one clean Android API 34 / google_apis / x86_64 /
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
or using a snapshot. An AVD hit does not promise to avoid SDK downloads. No separate SDK cache is enabled without
measurements: downloading/decompressing SDK archives can cost more than using the packages already present on the
hosted image, and the action would still check them. Evaluate that separately from the AVD cache with the same
emulator/image revisions and test selection. The existing GPU mode is retained; Android's
[snapshot documentation](https://developer.android.com/studio/run/emulator-snapshots) warns about software-rendering
reliability. The boot-ID check and one cold fallback keep the tests runnable; actual snapshot compatibility and
speed on our hosted runners still require the authorized acceptance run.

#### Measurements and pending acceptance

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

**Not yet measured:** the changed workflow's cache miss → save → hit, real boot-ID continuity, cache transfer size
and time, or net speedup. UI/Maestro/native/DesignCheck runs and manually dispatched workflows require separate
explicit owner permission. Helper/unit and workflow-syntax checks do not establish these runtime criteria.
After permission, compare a cold-cache run and an exact-key hit using identical test/configuration revisions;
record the compressed/uncompressed entry size, cache-transfer time, hit/fallback rate per shard, repository cache
quota and whether AVD entries evict Gradle caches. Full CPU model/features remain a conservative compatibility
guard until measurements justify narrowing them; cache fragmentation and net build time are acceptance criteria.
Check all four nonempty Maestro reports and shard 1's native results, verify no cold fallback occurred on a claimed
hit, and compare both preparation stages and total critical path with the baseline. Record SDK-cache A/B results
separately before deciding whether to add one. Do not close FIN-378 until those acceptance measurements are resolved.

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
  | `unknown_codes=true\|false` | the fake server sends codes the client does not know (`MockCodes`): a filter `kind` on the expense tile, an import notice, an analytics feature, an Analytics block of an unknown type and a card locked for an unknown reason (FIN-83, FIN-4) | process |
  | `failing_blocks=<id>,…` | those Analytics blocks fail on the fake server and come as `unavailable` (FIN-5); empty — none | process |
  | `assistant_model_errors=<n>` | the next n assistant answers fail with MODEL_ERROR («Повторить» under them) | process (counts down) |
  | `stream_drops=<n>` | the next n openings of an SSE stream (call, import progress, answer) close after their first event without a terminal one; the app re-opens them with a pause (FIN-170) | process (counts down) |
  | `stream_rate_limits=<n>` | the next n stream openings that follow a dropped one answer 429 `RATE_LIMITED` with `Retry-After: 3`; the app waits and re-opens, no «Слишком много попыток» (FIN-170) | process (counts down) |
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

## Test tags

Maestro finds elements by id; in Compose the id is `Modifier.testTag`, exposed as a resource id by
`Modifier.pfTestRoot()` (set on the app root, dialogs and bottom sheets) — only in debuggable builds (`pfExposeTestTags`,
set in `PfApplication`); release builds keep tags out of the accessibility tree.

- Screens tag their own elements as `<feature>.<screen>[.<element>]`: `auth.phone.input`, `operations.feed`,
  `statements.upload.error.wrong_bank`. The constants live next to the screens (`AuthTags`, `OperationsTags`, …).
- Shared components use `ds.*` (`PfTestTags`): `ds.back`, `ds.dialog.confirm`, `ds.numpad.<digit>`,
  `ds.tab.<operations|analytics|profile>`, `ds.chat.input`.
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

The other sign-in flows (`auth/*`, `availability/maintenance_on_sign_in`, `offline/stream_drops`) drive the screens
themselves. Every other flow uses `fast_login.yaml`; a new one does too unless it checks one of the above.

## Coverage

| Flow | Checks |
|---|---|
| `auth/register_new_user` (smoke) | independent 14+/PD before phone → new number → create account without another consent → passcode → first upload → result → feed (Qase2, FIN-365) |
| `auth/sign_in_existing_user` (smoke) | independent 14+/PD before phone → existing account → passcode → feed; the three tabs (Qase1, FIN-365) |
| `auth/phone_restart` | terminal Start refusal → explicit restart without POST → both choices off → new confirmed intent (Qase59, FIN-365) |
| `auth/phone_confirmation_gates` | both initially unchecked, independent age/PD gates, disabled phone input before 14+, return from call retains confirmations; repeated confirm_phone keeps both checked and input enabled (Qase57, FIN-365, FIN-375) |
| `auth/phone_consent_recovery` | document offline → explicit refresh → confirmations → Start offline → retry with the existing intent (Qase58, FIN-365) |
| `auth/phone_validation` | short number error; no promise of a free call; call screen with «Ждём звонок до ЧЧ:ММ»; «Изменить номер» keeps the number — FIN-166 |
| `auth/call_expired` | `call_ttl_ms=15000`: the wait ends either by the stream's `expired` or by the device timer (their order is not fixed); both lead to «Время на звонок вышло» with no number; «Позвонить ещё раз» brings the number and a new deadline back — FIN-166 |
| `auth/call_stream_rate_limited` | `stream_drops=2&stream_rate_limits=1`: the call stream drops, its re-open gets 429 `Retry-After: 3`; the app waits and re-opens — no «Слишком много попыток», the number stays, sign-in goes on to the passcode — FIN-170 |
| `auth/registration_closed` | `auth.registration=false`: new number → «Регистрация пока закрыта»; existing account still signs in |
| `applock/unlock` (smoke) | cold start → unlock; wrong code error |
| `applock/forgot_code` | «Забыли код?» → cancel / sign out |
| `applock/change_passcode` | current → new → mismatch → new → repeat; the new code unlocks after restart |
| `operations/feed_and_detail` (smoke) | feed → card with required `occurred_at` → change category → back → reopen: the manual category persists (FIN-203) |
| `operations/unknown_filter_kind` | `unknown_codes=true`: the expense tile sends a `kind` the client does not know; the search keeps it under «Как на „Аналитике“» and finds no incomes — FIN-83 |
| `operations/days_in_profile_zone` | `profile_timezone=Asia/Vladivostok`: after the profile is read, the feed groups days in the profile zone, not the device's — an evening operation in Moscow moves to the next day — FIN-92 |
| `operations/statement_stale` | on the preloaded statement the caption under the summary says «Данные по 1 октября» (the statement's end, not the last operation on 30 September); then the preloaded statement deleted, `stale.ofx` uploaded: «Операции» and «Аналитика» show «Данные в приложении по 20 сентября — с тех пор прошло больше двух недель», dated by the statement's end (`state.data_to`), not the last operation (15 September) — FIN-125 |
| `operations/search_filters` | empty result → «Сбросить фильтры» with the query alone clears it; query, «Только расходы», amount, «Сбросить», period, result card; «Без категории» in the picker's «Системные» — FIN-108 |
| `analytics/overview` (smoke) | blocks, previous month, month/quarter/year, «Без переводов», category → operations |
| `analytics/block_unavailable` | `failing_blocks=expense_categories`: the block shows «Не получилось посчитать», the other blocks stay — FIN-5 |
| `analytics/unknown_blocks` | `unknown_codes=true`: a block of an unknown type is shown by its text, a card locked for an unknown reason by the server's lock text — FIN-4, FIN-83 |
| `analytics/cards_open_search` | September: «Мелкие частые траты» opens its first group as a selection (chip with the group's name), a row of the card — its own group, a row of «Подписки и регулярные платежи» — that payment's charges — FIN-108 |
| `analytics/search_scope_own_transfers` | September «Расходы» tile → search «как на Аналитике»; without «Только расходы» and with the amount 6 000 ₽ nothing is found — the single «Себе в другой банк» of 27 September is out, as in the Analytics figure; without the scope chip it is found — FIN-164 |
| `analytics/blocks_switched_off` | flags hide tiles, categories, transfers chip, assistant (card and profile) |
| `assistant/ask_question` (smoke) | disclosure of question/history, account-phone/file exclusion and masking limits → consent (error without the tick) → question → answer → limit 4 of 5 → chip → scoped search (Qase12, FIN-366) |
| `assistant/daily_limit` | unanswerable question is free (the server's CANNOT_ANSWER text, no «Повторить»); five questions exhaust the day |
| `assistant/off_topic` | a question not about finances (`weather`): the server's OFF_TOPIC text, no «Повторить» (`retryable: false`), still 5 of 5; the next question is answered, 4 of 5 — FIN-160 |
| `assistant/off_topic_limit` | five terminal OFF_TOPIC outcomes reach the rolling limit; a refused sixth question keeps its draft, history and 5/5 daily quota; history remains readable — FIN-223, Qase 49 |
| `errors/request_retry_false` | a REST refusal with retryable=false is not resent on resume or after its wait; corrected input is a new action and a later failed result can separately allow RetryAnswer — FIN-223, Qase 50 |
| `errors/server_messages` | profile theme mutations show an unknown ordinary server message, unknown-severity error styling and safe malformed/legacy fallbacks without diagnostics — FIN-223, Qase 48 |
| `assistant/nothing_found_charged` | a merchant with no operations over the covered six months of the answer (`Kaspersky`, only in March): «…операций «Kaspersky» не нашли», an answer, not a refund — 4 of 5 (it used to stay 5 of 5); only a lock or no data over the period is refunded — FIN-136 |
| `assistant/offline_retry` | no network: the notice replaces the input; «Повторить» sends the question once the network is back |
| `assistant/chip_after_statements_deleted` | every statement deleted: the operations chip of an earlier answer opens the search without its stale selection (notice, empty result) — FIN-76 |
| `assistant/retry_without_transfers` | `assistant_model_errors=1`: a question asked «Без переводов» fails, not spent; «Повторить» answers it still «без переводов» — FIN-76 |
| `assistant/answer_retry` | `assistant_model_errors=1`: the answer fails, not spent; a double tap on «Повторить» while the request is on its way sends it once: one answer, no «Не получилось отправить вопрос», 4 of 5 — FIN-75 |
| `assistant/answer_changed_since` | after an answer a category is changed, then the statement deleted: the earlier answer in the history shows «С тех пор категории менялись», then «С тех пор данные менялись» (`changed_since` counted at the time of the request) — FIN-135 |
| `statements/upload_errors` | CSV and another-bank server messages, not a statement; leaving the picker (Qase15, FIN-375) |
| `statements/upload_unreadable` | a file with no readable operation: the empty result still shows the unread lines notice; the history row opens «Непрочитанные строки» (line, date, reason) |
| `statements/unread_same_physical_line` | two distinct unread operations on physical line 31 with the same `no_date` reason: both row titles/reasons are visible and Back still opens history — FIN-352, [Qase56](https://app.qase.io/case/PLAINFIN-56); authored, not executed |
| `statements/upload_unreadable_fixed` | `unreadable.ofx`, then `unreadable-fixed.ofx`: the old upload's row says «Строки дочитаны в загрузке от 8 октября» and has no «Не прочитали» link — FIN-79 |
| `statements/upload_history_period` | statement period wider than operations → 1–30 September; no DTSTART/DTEND → operation day; empty file without a period → empty result, system Back → Operations, Profile → upload history → count without a date label (FIN-198, FIN-209) |
| `statements/upload_and_history` (smoke) | new operations; the same file again — nothing new → «История загрузок»; delete an upload |
| `statements/upload_offline_retry` | «Не скачивается?» sheet; no network while sending: notice on the guide, «Повторить» sends the same file |
| `statements/upload_restored_key` | A seeded unknown-age UUID does not send the file or silently change key; the upload count remains 1 (preloaded statement). Only «Загрузить заново» starts a new action and changes the count to 2 (Qase51) — FIN-223 |
| `assistant/retry_after_consent` | A model failure, consent revocation and denied RetryAnswer lead to consent correction; the same operation resumes and history contains one question with the successful replacement (Qase52) — FIN-223 |
| `statements/upload_rate_limited_resume` | `stream_drops=1&stream_rate_limits=100&stream_retry_s=30`: after the first 429, both control-link and background returns retain the original deadline (`expect_stream_rate_limited_exact=1`); lifting the limiter does not skip that wait, then the result opens within 45s without an upload error (Qase45) — FIN-170, FIN-223 |
| `statements/upload_no_description` | a line without a description is imported (no unread notice) and shown as «Без описания» in the feed and on the operation |
| `statements/upload_unknown_codes` | `unknown_codes=true`: the import result shows the server's text for a notice code and for a locked analytics feature the client does not know — FIN-83 |
| `profile/theme_and_logout` | dark theme; logout cancel / confirm with the notice |
| `profile/delete_account` | passcode (wrong, right) → «Удалить аккаунт?» over the profile → «Удаляем…» ignores Back → «Аккаунт удалён» |
| `availability/update_required` | `min_version=9.0.0`: the next request gets `CLIENT_VERSION_UNSUPPORTED`, the update screen covers the app — FIN-22 |
| `availability/maintenance` | The check button is disabled during `Retry-After`; the first automatic probe is refused, then a guarded manual check after the fresh wait opens the app; a new maintenance episode has its own one-shot automatic check — FIN-22, FIN-223 |
| `availability/maintenance_on_sign_in` | `maintenance=unknown` before sign-in: «Продолжить» on the phone screen gets the maintenance screen over sign-in, without an end time — FIN-22 |
| `availability/maintenance_during_import` | A 15s stream 429 wait survives returning from the control link; its next opening gets 503 MAINTENANCE. After disabling maintenance/limits, the single guarded recovery waits its captured 15s deadline, then lifts maintenance within 40s; history shows both preloaded and new uploads done (Qase46) — FIN-22, FIN-170, FIN-177, FIN-223 |
| `offline/no_network` | «Нет сети» on analytics and profile, «Повторить» after the network is back |
| `offline/stream_drops` | `stream_drops=2` before each stream: the server closes the call, import and answer streams after their first event without a terminal one; the app re-opens them with a pause and shows no error — sign-in goes on, the import result opens, the answer comes, 4 of 5 — FIN-170 |
| native `StatementPickerTest` | upload with a picker result stubbed by Espresso-Intents (on the e2e build the picker is `E2eDocumentPickerActivity`) up to the import result; the `ACTION_OPEN_DOCUMENT` intent itself: Robolectric `PickStatementDocumentTest` |

`StatementPickerTest` waits for passcode setup after sign-in without recreating the activity (FIN-63, after the
FIN-88 session fix). If it stalls, the test fails after 30 seconds and keeps the semantics tree in logcat (`PfTest`)
and the main-thread latency report in the assertion. The logcat remains in the `e2e-reports` artifact.

Not covered yet: voice input (needs a speech recognizer on the emulator), biometrics (needs an enrolled
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


Consent-document rotation between GET and phone Start (FIN-365) and assistant grant is not covered by a mock UI
flow. The mock PD version can be changed by JVM fixtures but has no external control-link mutation, and assistant
versions remain fixed; a separate controllable REST fixture is required. `PhoneViewModelTest`,
`MockPhoneConsentTest`, `CallViewModelTest` and `AiConsentViewModelTest` cover version rejection/refresh and pinning.
Register never refetches current PD consent; mismatched original evidence restarts phone verification.

Real client process-death recovery of an uncertain assistant request is not covered by a mock UI flow
(Qase53). The fake backend, its token state and idempotency cache live in the client process: killing it
also destroys the independent server outcome that recovery must reconcile. A separate persistent BFF/peer
fixture is needed to test that boundary. `ChatViewModelBehaviourTest` restores the actual SavedStateHandle
and tests no automatic POST, consent correction, fresh-history permission and explicit new intent;
`ChatAskRecovery`, `ChatAskRecoveryDark` and `ChatRetryRecovery` fixtures cover the proposed visible states.
The upload flow above deliberately seeds an unknown-age record without claiming real process death.

### FIN-223 safe error controls and coverage

The mockE2e-only control link accepts `error_operation=profile_update|operations_list|assistant_ask|phone_start|off` and `error_scenario=unknown_code|unknown_severity|invalid_message|legacy_response|retry_forbidden|terminal_failure`. These are closed fixtures with fixed safe texts, not arbitrary messages/actions. `expect_error_requests=<n>` requires the exact cumulative number of injected failures; changing the operation does not reset it. Unknown upload-key recovery uses the closed `pending_upload_fixture=october` seed and `expect_uploads` check; it does not claim to kill the client process. The new flows and design-check fixtures were authored but not executed: owner instruction forbids UI, Maestro, instrumented and DesignCheck runs. New server-message/recovery fixture artboard names are proposed; corresponding Figma artboards are not available yet.

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
