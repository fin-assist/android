# Automated tests and coverage

Every new piece of functionality ships with automated tests in the same PR (AGENTS.md, «Tests»). Which layer
checks what:

| Layer | Checks | Where | Runs |
|---|---|---|---|
| Unit (JUnit 4, coroutines-test, Turbine) | logic: domain rules, mappers, repositories, view models, the fake backend, `core/*` helpers | `src/test/` of the module | CI on every push and PR |
| Design check (Robolectric + Roborazzi) | how a screen, state, sheet or dialog looks next to its mockup | `*DesignCheckTest` in feature `:impl` | by hand, `-Ppf.designcheck` (design-check/README.md) |
| Maestro | user scenarios end to end on the mock build | `.maestro/flows/<feature>/` | emulator; CI after merges to `main` and on PRs labelled `e2e` (docs/e2e.md) |
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
- Maestro and instrumented tests do not feed the number: it is unit coverage only.
- `scripts/coverage-diff.py` counts the changed lines of production source sets (`src/main`, `src/mock`, …)
  that the report knows as executable; comments, declarations, tests and excluded code do not count. A line
  with any executed instruction is covered, a partially covered branch included. `--min <pct>` turns it into a
  gate (exit 1 below the value).

### Target

- Changed lines: at least **80 %** covered. Lines left uncovered are listed in the PR («Проверка») with the
  reason (e.g. platform glue that only an instrumented test can reach).
- Project: the baseline on 2026-10-09 (`release/0.2` cut) is 35.6 % of lines, 27.3 % of branches. It should only
  go up.

### In CI

The `build` job (`.github/workflows/ci.yml`) builds the reports after the unit tests:

- PR: a comment «Unit test coverage» in the PR conversation — project totals, coverage of changed lines against
  the PR's base branch, uncovered lines per file (files with gaps first, up to 50). One comment per PR, edited on
  every push; PRs from forks get none (read-only token). The run summary has the same text with the full table.
- Push to `main` / `release/**`: project totals in the run summary.
- The HTML report is the `coverage-report` artifact of the run.

Nothing fails on a low value or on a failed post: the 80 % target is checked in review.
