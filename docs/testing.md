# Automated tests and coverage

Every new piece of functionality ships with automated tests in the same PR (AGENTS.md, «Tests»). Which layer
checks what:

| Layer | Checks | Where | Runs |
|---|---|---|---|
| Unit (JUnit 4, coroutines-test, Turbine) | logic: domain rules, mappers, repositories, view models, the fake backend, `core/*` helpers | `src/test/` of the module | CI on every push and PR |
| Design check (Robolectric + Roborazzi) | how a screen, state, sheet or dialog looks next to its mockup | `*DesignCheckTest` in feature `:impl` | by hand, `-Ppf.designcheck` (design-check/README.md) |
| Maestro | user scenarios end to end on the mock build | `.maestro/flows/<feature>/` | emulator; CI only after merges to `main` (docs/e2e.md) |
| Native instrumented | what Maestro cannot drive: system dialogs, intents, permissions | `app/src/androidTestMock/` | same as Maestro |

What a change needs:

- New or changed logic → unit tests in the module that owns it, covering the main path, the edge cases the
  code handles (empty, limits, errors, accounting rules) and every branch a reviewer would ask about.
- New screen, state, sheet or dialog → design check (AGENTS.md, «Code»).
- New or changed user scenario → a UI test with its Qase case: what counts as a scenario and which test it
  needs is in docs/e2e.md («When a change needs a UI test»).
- Bug fix → a test that fails without the fix: a UI flow for a user-visible bug, plus a unit test when the cause
  is in logic.

A test asserts behaviour. A test that only runs lines to raise the number is not a test.

## Coverage

Unit test coverage is measured by [Kover](https://github.com/Kotlin/kotlinx-kover) (JetBrains, JaCoCo-compatible
reports). Every module applies it through the convention plugins (`build-logic/…/buildlogic/Coverage.kt`), the
root project merges them into one report.

```bash
# HTML report of the whole project: build/reports/kover/htmlUnit/index.html
./gradlew :koverHtmlReportUnit

# XML report (input for the script below) and the total in the console
./gradlew :koverXmlReportUnit :koverLogUnit

# Coverage of the lines this branch changes, compared with its base branch
python3 scripts/coverage-diff.py --base origin/main
```

- Variant `unit`: the JVM tests of pure Kotlin modules, `debug` unit tests of Android libraries, `mockDebug` of
  `:app`. Release, `prod` and `e2e` variants are not run for coverage.
- Excluded from the report (root `build.gradle.kts`): generated code (Hilt/Dagger, kotlinx.serialization
  serializers, `R`, `BuildConfig`, `ComposableSingletons`, `HiltWrapper_*`, Dagger-generated factories — `@DaggerGenerated`,
  `*Module_*Factory`), `@Composable` and `@Preview` functions, and `:core:screenshot-testing`. Composables are
  checked by design check and Maestro; leaving them in would make the number about UI code that unit tests are
  not meant to run.
- Stays in: hand-written Dagger `@Module`s — a `@Provides` method can hold real logic (`NetworkModule.client()`
  sets up the refresh dispatcher and timeouts); plain wiring there is a fine reason for an uncovered line in the
  PR. Also `Companion.serializer()` of `@Serializable` classes (one line each, about 2 % of the total) — Kover
  cannot tell those companions from hand-written ones.
- Maestro and instrumented tests do not feed the number: it is unit coverage only. Their coverage is a separate
  number («UI test coverage» below).
- `scripts/coverage-diff.py` counts the changed lines of production source sets (`src/main`, `src/mock`, …)
  that the report knows as executable; comments, declarations, tests and excluded code do not count. A line
  with any executed instruction is covered, a partially covered branch included. `--min <pct>` (needs `--base`)
  turns it into a gate (exit 1 below the value); without `--base` it prints the project totals only.

### Target

- Changed lines: at least **80 %** covered. Lines left uncovered are listed in the PR («Проверка») with the
  reason (e.g. platform glue that only an instrumented test can reach).
- Project: the baseline on 2026-10-09 is 35.6 % of lines, 27.3 % of branches. It should only go up.

### In CI

The `build` job (`.github/workflows/ci.yml`) builds the reports after the unit tests:

- PR: a comment «Unit test coverage» in the PR conversation — project totals, coverage of changed lines against
  the PR's base branch, uncovered lines per file (files with gaps first, up to 50, long line lists cut to fit a
  comment). One comment per PR, edited on every push; PRs from forks get none (read-only token). The run summary
  has the same text with the full table.
- Push to `main` / `release/**`: project totals in the run summary.
- The HTML report is the `coverage-report` artifact of the run.

Nothing fails on a low value or on a failed post: the 80 % target is checked in review.

## UI test coverage

What the Maestro flows and the native instrumented tests reach — two numbers, next to the unit one and never
mixed with it (FIN-48):

- **Code coverage** — lines and branches of the mock e2e build the UI tests executed, measured by JaCoCo on the
  device. Needs an emulator: measured by the `UI tests` workflow after a merge to `main`, or locally.
- **Scenario coverage** — which screens and test tags of the app the flows refer to. Static (`.maestro/` against
  the sources), no device: CI prints it in the `agent-docs` job of every push and PR.

### Code coverage

```bash
# Flows + native tests on the connected emulator, then the report (build/reports/e2e-coverage/html/index.html)
scripts/e2e.sh
./gradlew :e2eCoverageReport
python3 scripts/ui-coverage.py summary --jacoco build/reports/e2e-coverage/report.xml
```

How it is measured:

- The mock `e2e` build type has `enableAndroidTestCoverage = true` and is the `testBuildType`
  (`app/build.gradle.kts`): AGP puts JaCoCo probes into every class of the APK — the app and all project modules
  in it (the ASM-transformed classes of Android modules, `main` of JVM ones), plus the libraries. The debug and
  prod builds carry no probes. The native tests therefore run on the same APK as the flows
  (`connectedMockE2eAndroidTest`), and one report covers both.
- Flows: Maestro drives the app from outside, so nothing in the test can dump the data; `E2eCoverage`
  (`app/src/mockE2e/…/e2e/E2eCoverage.kt`, a content provider of the e2e build) saves the agent's execution data
  every 2 s while it changes and when an activity stops, into shared storage (`Download/pf-coverage`, a new file
  per save, the previous one deleted after a successful write). Shared storage survives `clearState`
  (`pm clear`), which every flow starts with. `scripts/e2e.sh` clears the folder before the run, pulls it after
  the flows and keeps the newest complete file of every app process (`scripts/ui-coverage.py collect`; a file
  cut by a kill mid-write is dropped) in `build/e2e/coverage/ec/`.
- Native tests: AGP pulls their data itself through the test storage service
  (`useTestStorageService`; `clearPackageData` would wipe the app's own directory first) into
  `app/build/outputs/code_coverage/`.
- `:e2eCoverageReport` (root `build.gradle.kts`) reads both against the compiled classes of the modules in the
  mock APK (`e2eCoverageModules`) and writes `build/reports/e2e-coverage/{report.xml,html}`. Excluded: the same
  generated code as the Kover report, the e2e hooks (`ru.finassist.pf.e2e`), and — a gap of AGP 8.9 — every
  class whose simple name starts with `R` or `Manifest` (`ResultViewModel`, `ResultScreen.kt`, `RussianDates`,
  `RowModel`…): AGP's filter for the generated `R`/`Manifest` classes (`.+?/R\$?.*?\.class$`) copies them
  without probes, so they would show as never executed. Composables stay in: UI tests are the layer that runs
  them.
- `scripts/ui-coverage.py summary --jacoco` prints the totals, a table per module and the `*Screen*` source
  files no test touched. The same JaCoCo version drives the probes and the report (`libs.versions.jacoco`):
  data of one version is not read by another.

Known limits:

- Up to 2 s of a process's work before it is killed (`clearState`, a crash) is lost; `E2eCoverage` saves on
  every activity stop, so a flow that ends in the app loses nothing.
- Dependencies are instrumented too (the probes cost start-up time on the e2e build only); the report lists
  project modules only.
- The pipeline was built without an emulator at hand: the first run that proves the data arrives is the
  workflow after the merge (`UI test coverage` step and the `e2e-reports` artifact). It never fails the job —
  without data the report is empty and the summary says so.

### Scenario coverage

```bash
python3 scripts/ui-coverage.py summary            # Markdown to stdout; --summary FILE appends to a file
```

- A **screen** is a tag's first two segments (`statements.upload.fallback` → `statements.upload`); the tags come
  from the `*Tags` objects (constants and `fun` builders with a `$` suffix count as a prefix) and `testTag("…")`
  literals of the sources, the ids from `id: "…"` in `.maestro/flows/`. `e2e.*` ids are the control links, not
  tags.
- Covered: a screen with at least one tag a flow refers to; a tag a flow refers to (a prefix tag when any id
  starts with it). The table lists per feature the missing tags; an id no source declares is reported — a renamed
  tag shows up there, and `scripts/test_ui_coverage.py` fails on it.
- The number says what the flows look at, not what they assert, and nothing about the native tests.
