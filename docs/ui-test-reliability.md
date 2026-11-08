# UI test execution and reliability (FIN-473)

The full post-merge acceptance consists of three independent suites on the same source revision:

- Five Maestro shards on clean API34 Pixel6 emulators. Each shard has its own 2400 s test deadline.
- Native instrumented tests on a separate clean emulator, with a 600 s test deadline. They no longer wait
  behind Maestro or consume its remaining budget. Local `scripts/e2e.sh` still runs the full selection;
  `--native-only` selects only the native suite, `--shard i/n` selects Maestro without native tests.
- Targeted JVM Compose/Robolectric companions (`AnalyticsReliabilityComponentTest`), with a 900 s process
  deadline. These use the actual Analytics ViewModel and production Compose renderers, controlled fake API replies
  and virtual coroutine time; Qase82/89 render the full screen and Qase83 isolates its chart renderer. Their ordinary JVM PR execution remains enabled; emulator UI still runs only after
  merge to main or an explicitly authorized manual dispatch.

The expected inventory is derived from flow discovery, test source discovery and the existing
`scripts/qase-cases.json`; there is no second hand-maintained registry. Its `jvm:Class#method` keys are
explicit JVM companions. Unprefixed `Class#method` keys remain native, and `.maestro/…` keys remain flows.
The 92 existing case IDs are preserved. Qase82/83/89 each require both their Maestro and JVM components.
The assertion-by-assertion transfer is in [the pilot coverage map](qa/fin473-pilot-coverage.md).

## Evidence and verdict

`Reports` gathers `e2e-shard-*`, `e2e-native` and `e2e-components`. It validates the exact expected results:
missing, skipped, duplicate, unknown, malformed or wrong-shard entries prevent a complete PASS. Native and
JVM results receive the same completeness checks as Maestro. Synthetic missing results are NOT RUN/skipped,
not assertions that ran and failed; the full acceptance still stays red. Canonical XML, an inventory,
structured execution status, raw suite artifacts and the summary retain the distinction. Observations without
valid source/run receipts or required APK identity become UNVERIFIED/skipped in canonical XML and Qase, even
when their raw report says PASS. Raw status counts and bounded original diagnostics remain available.

Execution receipts start before setup. They retain phase, environment/source identity, safe tool versions
and APK hashes, and identify setup/device/budget/test failures. The first failing phase and per-test evidence
must be read before changing an assertion. Each local attempt clears the previous test payload before setup,
while preserving the current CI execution receipt, preparation telemetry and APKs. Its own traces survive failure. Report-integrity errors and Qase delivery are separate from the
application verdict. A Qase outage cannot establish publication success; retained canonical XML can be sent
again without repeating the UI tests. Never discard an earlier failed attempt when a later attempt succeeds.

Maestro is pinned by version and official ZIP SHA256. The cache holds that archive, not a previously executed
installation. Every restore is rehashed and extracted into a fresh directory; the exact executable version is
checked before publishing PATH. Missing or corrupt cached input uses a bounded download fallback. Cache success
does not authorize a retry of a test assertion. Native execution does not depend on Maestro installation.

The main path still uses a fresh cold AVD. Snapshot caching remains explicitly experimental. Changing host or
SDK packages is visible in execution evidence; neither cache identity nor a pinned API level promises an
immutable hosted runner. APK identities from independently built suites are recorded for comparison.

## Deterministic preparation and interactions

The mockE2e sign-in waits at most 60 s for the existing backend fixture-ready signal before the unchanged
30 s authentication deadline starts. A setup timeout cannot silently create a session. The control receipt
`e2e.config.applied` persists until explicit acknowledgement through `e2e.config.continue`. The existing
four-second minimum observation dwell stays in place: elapsed-deadline and SSE probes relied on it. An unmet
predicate has no applied receipt or continue action. These controls remain absent from stage/local/prod.

`reveal_text_up.yaml` targets fully visible scoped period text instead of a clipped parent container and uses
a literal upward direction without centering. The Qase82-only `reveal_tag_up.yaml` uses a bounded loop along
Analytics' noninteractive side padding; the default central gesture had opened a category/operation. It checks
the screen after every gesture and requires the requested notice without a refresh indicator. Horizontal charts retain gestures confined to the chart. A helper does not
replace the financial, request-count, selected-period or forbidden-action assertions following it.

The pilot keeps real import, gesture, navigation, selection and mutation/recovery boundaries in Maestro. Long
state combinations and exhaustive history paging move only where the companion proves the same obligation.
JVM event injection proves the subscriber, not the producer UI or the network transport. Fake API results are
never described as real server acceptance.

## Validation policy

Every test process, including local JVM and scripts checks, has a real deadline that stops its children.
CI builds and components use the same process-group coordinator as the emulator suites; budget expiry is
recorded from an observed deadline, not inferred from a child exit code such as 137 or 124.
Before a PR, successfully execute new UI tests and the changed pilot journeys locally; shared helper changes
also list and execute representative affected journeys. The PR records exact commands, source/APK identity,
outcomes and limitations. No automatic emulator UI job is added to PR checks, and no e2e label is used.

An assertion or budget failure is not made green through blind retries. A bounded setup retry before tests
start retains its original evidence. Quarantine, deletion of mandatory tests and reduction of coverage are
not automatic recovery actions. Completing one full run proves functional acceptance of that run; stability
requires a separately authorized comparison series on unchanged source, APK and recorded environment.
