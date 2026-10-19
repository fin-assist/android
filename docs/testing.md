# Automated tests and coverage

Every new piece of functionality ships with automated tests in the same PR (AGENTS.md, «Tests»). Which layer
checks what:

| Layer | Checks | Where | Runs |
|---|---|---|---|
| Unit (JUnit 4, coroutines-test, Turbine) | logic: domain rules, mappers, repositories, view models, the fake backend, `core/*` helpers | `src/test/` of the module | CI: the coverage variant (`unitTest`) on a PR, every variant (`test`) on pushes to `main` and release branches |
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

## Calculation fixtures

`CalcFixturesTest` (`mock/backend/src/test/…/mock/calc/`, FIN-108) runs the calculation fixtures of
fin-assist/contracts on the fake backend: the same statements, «now», profile zone and actions as the Go backend
(`scenario.json`), the same expected responses and comparison rules (`fixtures/calc/README.md` of contracts, ported in
`CalcCompare.kt`). How the fields are calculated is `docs/calc.md` of contracts.

- **Data.** A copy of `fixtures/calc` lives in `mock/backend/src/test/resources/calc`, with the contracts commit in
  `VERSION`; the test runs offline. `scripts/sync-contracts.sh [ref]` (default `main`) replaces the copy — together
  with the copy of the contract tests below, at the same commit — and fails if `.gitignore` would keep a file of it
  out of the commit. Take a new copy when contracts change the expected responses, and bring the fake backend in line
  in the same PR.
- **Exact match.** The responses of the fake backend equal the expected ones by the comparison rules; any difference
  fails the test with its JSON path and both values. `CALC_DUMP=<file>` also writes the fake backend's responses
  next to it (`./gradlew :mock:backend:testDebugUnitTest --tests '*CalcFixturesTest'`).
- **Categories.** The fake backend loads the registry of the fixtures (`categories.json`) instead of its built-in one
  (`Catalog(known)`), as the Go backend does in the same test.

## Contract tests

The fake backend is in-process: Kotlin calls, no HTTP and no JSON, so nothing in it is checked against `openapi.yaml`
by itself. The contract tests (`mock/backend/src/test/…/mock/contract/`, FIN-123) render each mock call as the HTTP
exchange a server would send and validate it against the pinned contract.

- **Data.** `openapi/openapi.yaml` and `docs/api.md` of fin-assist/contracts are copied into
  `mock/backend/src/test/resources/contracts` with the commit in `VERSION`, by the same `scripts/sync-contracts.sh`
  and at the same commit as the calculation fixtures (`ContractCoverageTest` checks that the two `VERSION` files agree).
- **Wire (test code only).** `Wire.kt` builds the HTTP side: a result is the operation's 2xx status and a body
  serialized with `ApiJson` (the configuration the HTTP client reads with) plus `encodeDefaults` and `explicitNulls`,
  because a server writes every field and writes an empty one as `null` (the examples of `api.md`, the expected
  responses of `fixtures/calc`); an `AppError` is the status and `{"error": …}` body the HTTP client maps back to that
  error (`core/network`, `ErrorMapping.kt`); stream events are `event` + `data` pairs.
- **Validator.** [swagger-request-validator-core](https://bitbucket.org/atlassian/swagger-request-validator)
  (Atlassian, Apache-2.0), test dependency of `:mock:backend` only, with its defaults: every message is an error and
  objects without `additionalProperties` get `false`, so an undeclared field fails like a missing, mistyped or
  `null` one. Two preparations (`Contract.kt`): every `allOf` of the copy is merged into one schema, keeping the
  wrapper's `nullable`, so that holds for composed schemas, events and nested objects too; and a `null` for a
  declared, optional field the schema does not declare `nullable` is taken as the field left out, as a server writes
  it (the Kotlin DTO has one `T? = null` for both). A `null` in a required field still fails, and so does a field the
  schema does not declare, `null` or not.
  Stream events are checked against their `x-sse-events` schema, block `data` against its primitive (`heading` →
  `HeadingData`, …). `ContractValidatorTest` pins what the validator must reject, so an update of the library or of
  its options cannot check less.
- **What runs.** `ContractShapeTest` — the success of every operation in its main states (empty, loaded, each kind,
  each terminal event of a stream, which must come last; an answer stream also while it is generated). `ContractErrorCodesTest` — every code of section 8 of `api.md` the mock can return, with the
  registry's status and a body valid for the operation. `ContractBehaviourTest` — error order (503 `MAINTENANCE` and 400
  `CLIENT_VERSION_UNSUPPORTED` first, neither stored under an `Idempotency-Key`, FIN-22), idempotency (replay,
  another body, request in progress), 404 for an object the user never had or another user's.
  `ContractCoverageTest` fails when an operation or a code of the pinned contract has neither a case nor an exclusion.
- **Exclusions.** `Exclusions.kt` lists the known mismatches, each with its reason and issue: a validation message of
  one case, bound to the JSON pointer it is reported at and to the value the body may carry there (anything else at
  that place still fails); a code the mock cannot return; a behaviour it does not follow (`knownGap` takes the code
  the contract wants and the one the mock gives today, and fails when the gap is closed or the outcome is anything
  else). An entry that no longer matches fails the test, so the list only shrinks; never weaken the validator instead.
- **A contract change.** Run `scripts/sync-contracts.sh <ref>` in a PR of its own (the contract issue's client
  issue), then `./gradlew :mock:backend:testDebugUnitTest --tests 'ru.finassist.pf.mock.contract.*'`: the failures
  are where the fake backend has to follow, with the JSON pointer and the schema message. Fix the mock in that PR, or
  add an exclusion with its issue and say so in the PR.

## Coverage

Unit test coverage is measured by [Kover](https://github.com/Kotlin/kotlinx-kover) (JetBrains, JaCoCo-compatible
reports). Every module applies it through the convention plugins (`build-logic/…/buildlogic/Coverage.kt`), the
root project merges them into one report.

```bash
# HTML report of the whole project: build/reports/kover/htmlUnit/index.html
./gradlew :koverHtmlReportUnit

# XML report (input for the script below) and the total in the console
./gradlew :koverXmlReportUnit :koverLogUnit

# Coverage of the lines this branch changes, compared with its base branch (the PR's base: the current
# release/<version>, or main)
python3 scripts/coverage-diff.py --base origin/<base>
```

- Variant `unit`: the JVM tests of pure Kotlin modules, `debug` unit tests of Android libraries, `mockDebug` of
  `:app`. Release, `local`, `prod` and `e2e` variants are not run for coverage. `./gradlew unitTest` runs exactly
  these tests: every module gets the task from the convention plugins (`Coverage.kt`, one list `COVERAGE_TARGETS`
  of plugin, Kover variant and test task feeds both), so a new module needs no list. `pf.module.rules` fails the
  configuration when a module with a build file has no `unitTest` or its plugin is not in `COVERAGE_TARGETS` (Kotlin
  Multiplatform, FIN-144): such a module would otherwise drop out of the PR run silently.
- Excluded from the report (root `build.gradle.kts`): generated code (Hilt/Dagger, kotlinx.serialization
  serializers, `R`, `BuildConfig`, `ComposableSingletons`, `HiltWrapper_*`, Dagger-generated factories — `@DaggerGenerated`,
  `*Module_*Factory`), `@Composable` and `@Preview` functions, and `:core:screenshot-testing`. Composables are
  checked by design check and Maestro; leaving them in would make the number about UI code that unit tests are
  not meant to run. Also the design tokens of `:core:designsystem` — tables of constants a test could only restate:
  `IconPaths.kt` (generated icon paths), `PfColors.kt` (`LightPfColors`, `DarkPfColors`, `PfColorScheme`),
  `PfTypography`, `PfDimens`. The rest of the design system (`PfIcons` lookup, the Material scheme mapping, chart
  helpers) stays in.
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
- Project: 85.8 % of lines, 62.4 % of branches on 2026-10-13 (FIN-47; 35.6 % / 27.3 % on 2026-10-09). It should
  only go up.

### In CI

The `unit-tests` job (`.github/workflows/ci.yml`; lint and the build of the variants run in parallel jobs, FIN-176)
runs the unit tests and builds the reports after them. On a PR it runs `unitTest` — the tests of the variant the
coverage is measured on; on pushes to `main`, `release/**` and `stage-*/**` it runs `test`, the unit tests of every
variant (release, `local`, `prod`, `e2e`), so a test that fails only in another variant is caught there:

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
- In CI the flows run in shards, one emulator each (docs/e2e.md, «Shards in CI»): every shard keeps its data in its
  `build/e2e/` (the native tests' in `native-coverage/`, shard 1), and the `Reports` job puts all of it back where
  the report reads it (`scripts/e2e-shards.py gather`: `build/e2e/coverage/ec/shard<i>-<process>.ec` and
  `app/build/outputs/code_coverage/`) before `:e2eCoverageReport` — one report for every shard. The shards and the
  report job build the same commit, mostly from the Gradle build cache; outputs it does not have are compiled
  again, and the compilers and the Hilt transform give the same classes, so the class ids match the probes. A shard that failed still adds the data it saved; one that left nothing is named in the summary.
- `:e2eCoverageReport` (root `build.gradle.kts`) reads both against the compiled classes of the modules in the
  mock APK (`e2eCoverageModules`: the ASM-transformed classes where Hilt transforms them, otherwise the compiler
  output, so that the class ids match the probes) and writes `build/reports/e2e-coverage/{report.xml,html}`. Excluded: the same
  generated code as the Kover report, the e2e hooks (`ru.finassist.pf.e2e`), and — a gap of AGP 8.9 — every
  class whose simple name starts with `R` or `Manifest` (`ResultViewModel`, `ResultScreen.kt`, `RussianDates`,
  `RowModel`…): AGP's filter for the generated `R`/`Manifest` classes (`.+?/R\$?.*?\.class$`) copies them
  without probes, so they would show as never executed. The summary counts these declarations from the sources
  («Not measured — N …») so the gap stays visible next to the number. Composables stay in: UI tests are the layer
  that runs them.
- `scripts/ui-coverage.py summary --jacoco` prints the totals, a table per module, the `*Screen*` source files no
  test touched and the not-measured declarations. Without execution data the task writes no report and the
  summary says so instead of showing 0 %. The same JaCoCo version drives the probes and the report (`libs.versions.jacoco`):
  data of one version is not read by another.

Known limits:

- Up to 2 s of a process's work before it is killed (`clearState`, a crash) is lost; `E2eCoverage` saves on
  every activity stop, so a flow that ends in the app loses nothing.
- Dependencies are instrumented too (the probes cost start-up time on the e2e build only); the report lists
  project modules only.
- The report step never fails the job: without data there is no report and the summary says so. The first
  measurement (manual run of the workflow on 2026-10-12, 18 flows and the native test): 83.3 % of lines, 54.0 % of
  branches of the project modules (run 38216952064); `UnreadLinesScreen.kt` the one screen file no test touched (covered by `statements/upload_unreadable` since FIN-66).

### Scenario coverage

```bash
python3 scripts/ui-coverage.py summary            # Markdown to stdout; --summary FILE appends to a file
```

- A **screen** is a tag's first two segments (`statements.upload.fallback` → `statements.upload`); the tags come
  from the `*Tags` objects (constants and `fun` builders with a `$` suffix count as a prefix) and `testTag("…")`
  literals in the sources, the ids from `id: "…"` in `.maestro/flows/`. `e2e.*` ids are the control links, not
  tags. A tag that reaches `testTag` through a parameter (the analytics tiles' `chartId`) is invisible to the
  script: declare it in the feature's `*Tags` object.
- Covered: a screen with at least one tag a flow refers to; a tag a flow refers to (a prefix tag when any id
  starts with it). The table lists per feature the missing tags; an id no source declares is reported — a renamed
  tag shows up there, and `scripts/test_ui_coverage.py` fails on it.
- The number says what the flows look at, not what they assert, and nothing about the native tests.
