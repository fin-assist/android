# FIN-473: focused local validation

This records focused local evidence, not a complete main acceptance or a measured stability series.
The base is `release/0.2.20` at `ed17eaa53f9d22af3ba245d0ac1240296aeb890d`.

## Deterministic checks

| Check | Observed result |
|---|---|
| AnalyticsReliabilityComponentTest | 3 tests, 0 failures/errors/skips |
| AnalyticsRevisionContractTest | 5 tests, 0 failures/errors/skips |
| E2eSignInTest | 13 tests, 0 failures/errors/skips |
| E2eControlReceiptTest | 2 tests, 0 failures/errors/skips |
| Existing app mockDebug tests | 2 tests, 0 failures/errors/skips |
| Python scripts suite before the review follow-up | 398 tests PASS in 48.028 s |
| Python scripts suite after the review follow-up | 408 tests PASS in 58.073 s |
| Nested shared scripts suite | 137 tests, 5 expected skips for another profile |
| Both mockE2e APKs | assembled successfully |
| Agent docs / KMP / actionlint1.7.12 / diff checks | PASS; ShellCheck was not installed |

The JVM companion source SHA256 is `f1baec457d728ee9dcf0010c48ed73c2abd45c99893b5993a237995c6bcbaac4`;
it is identical in the executed isolated worktree and the final branch. Child commands included:

```bash
./gradlew :feature:analytics:impl:testDebugUnitTest --tests '*AnalyticsReliabilityComponentTest'
./gradlew :mock:backend:testDebugUnitTest --tests '*AnalyticsRevisionContractTest'
./gradlew :app:testMockE2eUnitTest --tests '*E2eSignInTest' --tests '*E2eControlReceiptTest'
./gradlew :app:testMockDebugUnitTest
./gradlew :app:assembleMockE2e :app:assembleMockE2eAndroidTest
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

These are child commands, not unbounded instructions: the actual local invocations used an external process-group
deadline, with TERM/KILL of children. The final scripts suite used 180 s; the focused UI limits are recorded below.
The repository coordinator provides the equivalent CI boundary through `e2e-cache.py run-command`.

## Emulator journeys

Owned disposable API34/google_apis/x86_64/Pixel6 AVD,1080×2400,420dpi, Europe/Moscow, Maestro2.11.0.
The harness boot budget was 240 s and always stopped its owned emulator. It invoked `scripts/e2e.sh --no-build`
with an explicit repeated `--flow` for each selected path.

| Journey | Result | Exact seconds | Source revision |
|---|---|---:|---|
| analytics/chart_history_pages (Q83) | PASS | 601.482 | 6e930fef |
| analytics/unknown_mutation_recovery (Q89) | PASS | 428.834 | 6e930fef |
| offline/no_network (Q19) | PASS | 112.149 | 6e930fef |
| auth/consent_document_rotation (Q54) | PASS | 326.169 | 6e930fef |
| assistant/consent_document_rotation (Q55) | PASS | 426.305 | 6e930fef |
| analytics/recalculation_snapshot (Q82), gutter repair | PASS | 291.527 | a38e3504 |
| offline/stream_drops, initial scoped preparation | PASS | 193.615 | a38e3504 |
| offline/stream_drops, final common preparation helper | PASS | 192.135 | b4aee0af |

The first seven-flow selection had a 3000 s process deadline and exited1 after 2176.99 s: five passed, Q82 and SSE
failed. The two-flow repair selection had a 900 s deadline and exited 0 after 551.95 s. The final cold SSE selection
used a 600 s deadline (900 s total with emulator preparation). It exited 0 after255.63s..

The successful Q83/89 flows and the common ACK/text-reveal sources were unchanged after their recorded run.
The final `confirm_phone` change only raises the bounded initial document preparation maximum 15→60 s;
Q83/54/55 already wait for the document before invoking that helper, so their successful path adds no action,
GET or restart. The final cold SSE run exercises the changed helper without a separate pre-wait.
These earlier successes are not described as executions of a later SHA.

All recorded Maestro runs used app APK SHA256 `25217e4e8c69d51c228630648db61eac0476e16de83749d3e5a79165dc4eda37`. The runtime mockE2e sources remained unchanged after the successful runs. The final native test APK SHA256 is `7e6839461e52899a19bfe23ae867f3a9084923418253fa0cd48e65604773d5a8`; only its ACK consumer was adapted after the Maestro runs.

The native consumer of the control link was also adapted to wait for an enabled Continue, click it and then
wait for the applied receipt to disappear. Its existing error/date/no-browser assertions remain. The focused
`StatementPickerTest#failedDownloadKeepsSelectedDatesAndDoesNotOpenBrowser` executed **1/1 PASS**,
0 skips/failures/errors, on source `9abdc406c8e4c8d4fe09bdde418cf1867b1b9278` (JUnit time 56.418s;
bounded Gradle process136.56s,600s deadline). Its exact task/test selection was:

```bash
./gradlew :app:connectedMockE2eAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=ru.finassist.pf.StatementPickerTest#failedDownloadKeepsSelectedDatesAndDoesNotOpenBrowser' \
  --no-daemon --console=plain
```

The final cold SSE trace waited15.454s for the initial consent document, exercising the preparation range
beyond the previous15s maximum. This changes only pre-submission preparation, not business deadlines.

## Retained failures and limitations

- Attempt1: Maestro rejected a templated direction enum before scenarios ran. Helpers now use supported literal
  directions or explicit coordinates.
- Attempt2: Continue was tapped during the preserved four-second disabled dwell. Added a positive enabled wait;
  the interrupted attempt and raw trace were retained.
- Attempt3 Q82: a central vertical gesture opened Operations search, then an operation. A bounded side-gutter
  helper now checks Analytics after every gesture; all financial assertions/request guards stay in place.
- Attempt3 SSE: phone root appeared while initial document GET waited for the cold 1969-operation fixture;
  preload finished only ~380 ms before the 15 s predicate expired. Preparation now has its own bounded 60 s wait in
  the shared phone helper; no phone/call/SSE business deadline was extended.

Raw reports, console logs, source/flow/APK/environment hashes and previous attempts are retained in local
`fin473-evidence`, separately from repository test output. The new entry cleanup prevents old attempts from
being mixed into a fresh execution directory. No local results were published as a Qase acceptance run.

All 94 prior map bindings and 92 Qase IDs remain; the map adds three required JVM companions.
Static scenario metrics are unchanged against the base: screens 68/72(94.4%), tags 198/239(82.8%).
The obligation-by-obligation transfer is [the pilot coverage map](fin473-pilot-coverage.md).

The full native 13-test suite and the full 81-flow suite were not run locally; the changed native method was executed as recorded above. No PR emulator UI job, e2e label or
manual UI workflow dispatch was used. The new full post-merge workflow and stability series remain unexecuted;
FIN-464 acceptance still requires the owner's merge decision and main evidence. Independent earlier cold
guards in Q57/58 are outside the common phone-helper change and are not claimed fixed by this evidence.

## Review follow-up: deadline and Bash EXIT trap

Claude identified a concrete classification race: the shell EXIT trap could record the coordinator's TERM
as test failure (or success on a shell returning0 from the trap), before post-barrier deadline annotation.
A real Bash trap regression failed before the fix.

The coordinator now marks observed expiry in a separate file before TERM, avoiding concurrent JSON writers.
`finish` uses that evidence while preserving an already recorded assertion failure; `init` clears stale markers.
Final receipt annotation and archival still happen after the process-group barrier. The report carries the
strict boolean `budget_expired`, shows deadline expiry in the summary and gives missing results a budget reason.
Ordinary child exit124/137 is not evidence of timeout. Real Bash trap, prior failure, stopped descendants and
gather→Qase regressions passed, followed by the full408-test suite with a180s external process deadline.

This follow-up changes only scripts/tests and documentation. Android code, APKs, flows and shared UI helpers
remain identical to the recorded successful device runs, so those runs were not repeated.

The first follow-up CI exposed a fixture isolation error: the local-runner test inherited
`GITHUB_ACTIONS=true`, so its second subtest correctly retained the first CI receipt. The fixture now
explicitly selects local mode; the separate CI receipt-retention test still enables CI mode itself.
Assertions and runtime behavior are unchanged. Re-running the13 execution tests under an inherited
`GITHUB_ACTIONS=true` passed (23.417s,90s process deadline); the original failed CI log is retained.
