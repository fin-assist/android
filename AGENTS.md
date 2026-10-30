# Instructions for code agents

Android client of «Понятные финансы» (B2C personal finance: OFX statement import from T-Bank, operations,
analytics, LLM assistant). Read by Claude Code (through `CLAUDE.md`) and by Codex. This file is the
orientation map: read it first, then open only the files the task touches.

## Keep this file current

- A PR that changes what this file says (module layout, entry points, build commands, conventions, process)
  updates this file in the same PR. The same goes for `docs/modules.md` (new module) and `docs/flags.md`
  (new flag).
- Blocks between `<!-- generated:… -->` markers are produced by `python3 scripts/agent-docs.py --write`;
  do not edit them by hand. CI runs `--check`: stale blocks, a module missing from `docs/modules.md`, a
  feature not wired in `:app`, or `docs/flags.md` out of sync with `Flag.kt` (keys and defaults) fail the
  build. So does a source the parser cannot read; then fix the parser and its tests (`scripts/test_agent_docs.py`).

## Where things are decided

- Contract: repo [fin-assist/contracts](https://github.com/fin-assist/contracts) — `docs/api.md` (meaning of
  fields), `docs/calc.md` (how the fields are calculated), `openapi/openapi.yaml`, `docs/grpc-api.md`, `proto/`.
- MVP scope, architecture, Android plan, product and mockup-review decisions: Confluence space PF
  (https://finassist.atlassian.net/wiki/spaces/PF) — «Состав MVP», «Архитектура», «План Android-приложения»,
  «Продуктовые решения» and their child pages.
- Mockups and design system: Figma «Понятные финансы — макеты»
  (https://www.figma.com/design/u8paWRq6KAT73UJ1x2jXLv, link also in `design-check/README.md`).
- Version 0.2 (API, mockups, docs) is crystallized; every change goes through a YouTrack issue (`FIN-<n>`,
  finassist.youtrack.cloud) and is referenced in the PR. Release notes live in Confluence.
- There is no deployed backend yet: the `mock` flavor is the working product, `local` talks to a bank-bff on the
  developer's machine, `prod` is the HTTP client for later.
- Mismatches between mockups, docs and API are expected. Resolve them in favour of `docs/api.md` (contracts) +
  the latest decision, say so in the PR, and propose (not impose) engineering changes; keep them non-radical.
- API extensibility (decision of 13.10.2026): the contract grows by addition and keeps `deprecated` for
  exceptional cases — «Архитектура» in Confluence PF («Расширяемость API») and «Нотация» in `docs/api.md`
  (contracts). Client-side rules: Code.

## Repository map

Details and dependency rules: `docs/modules.md`. Entry points worth knowing:

| What | Where |
|---|---|
| Composition root, NavHost (auth graph → main tabs), app-lock gate | `app/src/main/kotlin/ru/finassist/pf/ui/PfApp.kt`, `di/AppModule.kt`; `app/src/prod/…/ProdModule.kt`, `app/src/local/…/LocalModule.kt` |
| API contract (service interfaces + DTOs) | `core/api/…/Services.kt`, `core/api/…/model/*.kt` |
| Universal error presentation, request retry restrictions and server deadlines (FIN-223) | `docs/errors.md`; `core/common/…/error/`, `time/ServerDeadline.kt`; `core/designsystem/…/ErrorMessage.kt` |
| SSE streams: transport and the one re-open loop of all three streams (pauses with jitter, 429, FIN-170) | `core/network/…/Sse.kt`; `core/api/…/SseReopen.kt` (`reopeningStream`, used by the view models; the fake backend's drops — `MockConfig.streamDrops`) |
| Client version, update and maintenance (FIN-22): `X-Client-Version`, `CLIENT_VERSION_UNSUPPORTED` / `MAINTENANCE` → `ServiceGate` → screens over the app | `core/common/…/availability/` (`ClientVersion`, `ServiceGate`), `core/network/…/Interceptors.kt`, `core/toggles/…/MinVersionCheck.kt` (`app.min_version`), `feature/availability`; version and build number — `app/build.gradle.kts` |
| Fake backend (mock flavor): OFX parsing, ledger, analytics, assistant | `mock/backend/…/mock/` — `MockBackend.kt`, `domain/*`, `MockConfig.kt` (existing phone, delays, `offline`) |
| Fake backend against the calculation fixtures of contracts | `mock/backend/src/test/…/mock/calc/`, `mock/backend/src/test/resources/calc/` (copy), `scripts/sync-contracts.sh`; rules — `docs/testing.md`, «Calculation fixtures» |
| Fake backend against `openapi.yaml` and the error registry of contracts (contract tests, known mismatches) | `mock/backend/src/test/…/mock/contract/` (`Exclusions.kt`), `mock/backend/src/test/resources/contracts/` (copy of the same commit); rules — `docs/testing.md`, «Contract tests» |
| Feature routes and public interfaces | `feature/<name>/api/…/*Routes.kt` (or `AuthApi.kt`, `AppLock.kt`) |
| Screen registration | `FeatureEntry` / `Navigator` in `core/navigation/…/Navigation.kt`; each `:impl` binds its entry in `di/` |
| Design system: theme, tokens, components, icons | `core/designsystem/…/theme`, `components`, `icons` |
| Server-composed blocks (Analytics, assistant): catalog models, renderers, registry, `supports` | `core/api/…/model/Blocks.kt`; `core/blocks` (`CatalogV1`, `BlockRegistry`, `BlocksColumn`); composer of the fake server `mock/backend/…/domain/AnalyticsComposer.kt` |
| Feature flags | `core/toggles/…/Flag.kt` (registry + defaults), `docs/flags.md` (meaning); mock overrides `app/src/mock/assets/flags.json` |
| Events / crashes | `core/tracking/…/Tracker.kt`, `CrashReporter.kt` (typed context, no free-form strings); providers in `providers/*` (only `:app` depends on them; Tracer keys — README) |
| Tokens, preferences, idempotency keys | `core/storage`; `EncryptedTokenStore` atomically maintains `authSessionEpoch` (new login rotates, refresh retains) |
| Silent background profile-zone sync (FIN-345) | `feature/profile/impl/…/data/TimeZoneChecker.kt`, `ZoneSyncStateStore.kt`, `BootCounter.kt`; independent durable GET/PATCH advice and boot-bound waits — `docs/errors.md` |
| Screens vs mockups (snapshot comparison) | `design-check/README.md` (mockups: Figma, exported by `design-check/export.py`), `*DesignCheckTest.kt` in feature `:impl` modules |
| UI tests: Maestro flows, e2e build hooks (sign-in without the screens — `E2eSignIn`), native gaps, shards in CI, Qase; default SDK package cache + fresh cold AVD, opt-in snapshot (`avd_cache`), SDK-only experiment (`sdk_benchmark`) | `docs/e2e.md`; `.maestro/`, `app/src/mockE2e/`, `app/src/androidTestMock/`, `scripts/e2e-shards.py`, `scripts/qase-*`; `scripts/e2e-sdk-cache.py`, `scripts/e2e-cache.py`, `scripts/sdk-preparation-benchmark.py` |
| Multiplatform readiness of `:core:api` and `:mock:backend`: rule, JVM-only inventory, check | `docs/kmp-readiness.md`; `scripts/check-kmp-ready.py`, `scripts/kmp-baseline.json` |
| Test layers, unit test coverage (Kover), coverage of changed lines | `docs/testing.md`; root `build.gradle.kts`, `build-logic/…/buildlogic/Coverage.kt`, `scripts/coverage-diff.py` |
| UI test coverage: JaCoCo on the e2e build, scenario coverage of the flows | `docs/testing.md` («UI test coverage»); `:e2eCoverageReport` in root `build.gradle.kts`, `app/src/mockE2e/…/E2eCoverage.kt`, `scripts/ui-coverage.py` |

Package root: `ru.finassist.pf`. Flavor dimension `env` (FIN-120), one flavor per environment, dimensions are not
multiplied:

| Flavor | Backend | Flags / events / crashes | Id, name |
|---|---|---|---|
| `mock` | in-process fake (`:mock:backend`) | `toggles-local` (`app/src/mock/assets/flags.json`) / logcat / Tracer with a token | `.mock` |
| `local` | bank-bff on the developer's machine: `pf.apiBaseUrl` (Gradle property or `PF_API_BASE_URL`), default `http://10.0.2.2:8080/` (emulator → host; `LocalBackend` in build-logic) | `toggles-local` (optional, gitignored `app/src/local/assets/flags.json`) / logcat / off — nothing reaches production consoles | `.local`, «Понятные финансы local» |
| `prod` | `API_BASE_URL` (placeholder until there is a server); certificate pinning of its host when `pf.apiPins` + `pf.apiPinsExpiration` are set (`ApiPins` in build-logic, FIN-121) | RuStore Remote Config / MyTracker / Tracer with a token | no suffix |

A stand will be one more flavor `stage`, built like `prod`. Plain HTTP only in `localDebug`: its network security config
is generated from `pf.apiBaseUrl` (one host, no subdomains); every other variant has `usesCleartextTraffic="false"`
and no config — except `prod` with API pins (Gradle properties or `PF_API_PINS` / `PF_API_PINS_EXPIRATION`; at least
two distinct `sha256/` pins and an expiration within a year, or the configuration fails): a generated config with a
`pin-set` for the `API_BASE_URL` host, no cleartext, no trust anchors of its own. Real pins are never committed; a
refused certificate is the usual offline error plus a `network.tls` non-fatal (`core/network/…/TlsFailures.kt`).
Decision and rotation: «Архитектура» → «Вход трафика» in Confluence PF. CI checks it on the merged manifests
(`scripts/check-build-separation.py`, `pinned-release` with test pins) and keeps `:mock:*` off the
`local` and `prod` classpaths (`:app:checkNoMockCode`). Build type `e2e` (mock only): debug + UI-test hooks, pinned
date, JaCoCo probes; the native tests run on it (docs/e2e.md).

### Stack

<!-- generated:stack:start (scripts/agent-docs.py) -->
| Component | Version |
|---|---|
| Gradle | 8.11.1 |
| agp | 8.9.1 |
| kotlin | 2.1.20 |
| ksp | 2.1.20-1.0.32 |
| hilt | 2.56.1 |
| composeBom | 2025.03.01 |
| navigationCompose | 2.8.9 |
| compileSdk | 35 |
| minSdk | 26 |
| targetSdk | 35 |
<!-- generated:stack:end -->

JDK 21 runs Gradle (`gradle/gradle-daemon-jvm.properties`); bytecode targets Java 17.

### Modules

<!-- generated:modules:start (scripts/agent-docs.py) -->
| Module | Convention plugins | Tests |
|---|---|---|
| `:app` | `pf.android.application`, `pf.hilt` | unit, instrumented |
| `:core:api` | `pf.jvm.library` | unit |
| `:core:blocks` | `pf.android.library.compose` | unit |
| `:core:common` | `pf.jvm.library` | unit |
| `:core:designsystem` | `pf.android.library.compose` | - |
| `:core:navigation` | `pf.android.library.compose` | - |
| `:core:network` | `pf.android.library`, `pf.hilt` | unit |
| `:core:screenshot-testing` | `pf.android.library.compose` | - |
| `:core:storage` | `pf.android.library`, `pf.hilt` | unit |
| `:core:toggles` | `pf.jvm.library` | unit |
| `:core:tracking` | `pf.jvm.library` | unit |
| `:providers:crash-tracer` | `pf.android.library`, `pf.hilt` | unit |
| `:providers:toggles-local` | `pf.android.library`, `pf.hilt` | unit |
| `:providers:toggles-rustore` | `pf.android.library`, `pf.hilt` | unit |
| `:providers:tracking-log` | `pf.android.library`, `pf.hilt` | - |
| `:providers:tracking-mytracker` | `pf.android.library`, `pf.hilt` | - |
| `:mock:backend` | `pf.android.library`, `pf.hilt` | unit |
| `:feature:analytics:api` | `pf.feature.api` | - |
| `:feature:analytics:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:applock:api` | `pf.feature.api` | - |
| `:feature:applock:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:assistant:api` | `pf.feature.api` | - |
| `:feature:assistant:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:auth:api` | `pf.feature.api` | - |
| `:feature:auth:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:availability:api` | `pf.feature.api` | - |
| `:feature:availability:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:operations:api` | `pf.feature.api` | - |
| `:feature:operations:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:profile:api` | `pf.feature.api` | - |
| `:feature:profile:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:statements:api` | `pf.feature.api` | - |
| `:feature:statements:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
<!-- generated:modules:end -->

### Flags

<!-- generated:flags:start (scripts/agent-docs.py) -->
| Key | Constant | Code default |
|---|---|---|
| `auth.registration` | `Flag.AUTH_REGISTRATION` | `true` |
| `analytics.block.tiles` | `Flag.ANALYTICS_BLOCK_TILES` | `true` |
| `analytics.block.expense_categories` | `Flag.ANALYTICS_BLOCK_EXPENSE_CATEGORIES` | `true` |
| `analytics.block.income_categories` | `Flag.ANALYTICS_BLOCK_INCOME_CATEGORIES` | `true` |
| `analytics.block.monthly_chart` | `Flag.ANALYTICS_BLOCK_MONTHLY_CHART` | `true` |
| `analytics.block.regular_payments` | `Flag.ANALYTICS_BLOCK_REGULAR_PAYMENTS` | `true` |
| `analytics.block.notable_spending` | `Flag.ANALYTICS_BLOCK_NOTABLE_SPENDING` | `true` |
| `analytics.block.bank_fees` | `Flag.ANALYTICS_BLOCK_BANK_FEES` | `true` |
| `analytics.block.small_frequent` | `Flag.ANALYTICS_BLOCK_SMALL_FREQUENT` | `true` |
| `analytics.filter.transfers` | `Flag.ANALYTICS_FILTER_TRANSFERS` | `true` |
| `assistant` | `Flag.ASSISTANT` | `true` |
| `search.filter.period` | `Flag.SEARCH_FILTER_PERIOD` | `true` |
| `search.filter.category` | `Flag.SEARCH_FILTER_CATEGORY` | `true` |
| `search.filter.amount` | `Flag.SEARCH_FILTER_AMOUNT` | `true` |
| `search.filter.kind` | `Flag.SEARCH_FILTER_KIND` | `true` |
| `statements.upload` | `Flag.STATEMENTS_UPLOAD` | `true` |
| `profile.delete_account` | `Flag.PROFILE_DELETE_ACCOUNT` | `true` |
<!-- generated:flags:end -->

## Build in an agent container

Configuration caching is temporarily disabled for all builds (FIN-187): Gradle 8.11.1 can fail while serializing
AGP AAR metadata inputs. Task-output caching remains enabled. Cause, trade-off and re-enable checks:
`docs/gradle-configuration-cache.md`. Keep the default fail-on-problems policy; do not mask dependency errors.

- Fresh Linux container: run `scripts/agent-setup.sh` once (Android SDK matching `gradle/libs.versions.toml`,
  `local.properties`, a Maven Central mirror in `~/.gradle/init.d`). Needs JDK 21 and network access to
  Google Maven, Gradle and Maven Central.
- Then `ANDROID_HOME=/opt/android-sdk ./gradlew assembleMockDebug unitTest` — the main checks of CI. `unitTest`
  (every module, `build-logic/…/buildlogic/Coverage.kt`) runs the unit tests of the coverage variant only: `test`
  of JVM modules, `testDebugUnitTest` of Android libraries, `testMockDebugUnitTest` of `:app`.
- CI (`.github/workflows/ci.yml`, FIN-176) runs the Gradle checks as parallel jobs: unit tests with coverage
  (`unitTest :build-logic:convention:test` on a PR; `test`, the unit tests of every variant, on pushes to `main`,
  `release/**` and `stage-*/**`), `lintMockDebug`, and the build of `assembleMockDebug assembleLocalDebug
  assembleProdRelease` (release unsigned) with `:app:checkNoMockCode`, the Tracer check and the manifest checks
  (`scripts/check-build-separation.py`, pinning included). A test that fails only in a release, `local`, `prod` or
  `e2e` variant is caught after the merge, not on the PR; run `./gradlew test` locally when a change is variant-specific.
- FIN-185: the assemble job resolves `mockE2eRuntimeClasspath` and `prodReleaseRuntimeClasspath` using
  `scripts/compose-classpath.init.gradle` (`:app:composeClasspathReport --no-configuration-cache`), then validates
  the JSON with `scripts/check-compose-classpath.py`: Compose UI/foundation and lifecycle-runtime must match the
  isolated BOM/navigation/catalog lifecycle baseline. Commands and failure diagnostics: docs/testing.md.
- FIN-377: `scripts/check-app-version.py` checks `appVersionName` against release branches on pushes and either
  side of a PR. The assemble job also checks generated manifest/BuildConfig versions for mockDebug, localDebug
  and prodRelease. Release preparation and the independent monotonic build number: `docs/releasing.md`.
- UI tests need an emulator (not available in a cloud container): `scripts/e2e.sh` locally (all flows and the native
  tests; `--shard i/n` runs one CI shard), the `UI tests` workflow in CI (after a merge to `main`, or started by hand
  for a branch; PRs never run them by themselves). CI splits the flows between four emulators
  (`scripts/e2e-shards.py`, durations in `scripts/e2e-durations.json`) and merges their reports (docs/e2e.md).
  Their coverage: `./gradlew :e2eCoverageReport` after a run, then
  `python3 scripts/ui-coverage.py summary --jacoco build/reports/e2e-coverage/report.xml`; the scenario part
  (`python3 scripts/ui-coverage.py summary`) needs no device (docs/testing.md).
- `python3 scripts/agent-docs.py --check`, `python3 scripts/check-kmp-ready.py` and
  `python3 -m unittest discover -s scripts -p 'test_*.py'` — the docs check, the multiplatform check of `:core:api` /
  `:mock:backend` and the scripts' tests from CI, no Gradle needed.
- Coverage: `./gradlew :koverXmlReportUnit` (runs the unit tests), then
  `python3 scripts/coverage-diff.py --base origin/<base branch>` for the changed lines; HTML report via
  `:koverHtmlReportUnit` (docs/testing.md).

## Code

- Code, KDoc and commit messages are in English; user-facing strings are in Russian.
- Each feature is `:api` + `:impl`; an `:impl` depends on other features only through their `:api`
  (enforced by `pf.module.rules`).
- SOLID applies to the Android code too (decision of 18.10.2026, FIN-175). The Go services follow the same principles
  (FIN-117), but their order of priorities — tests → clarity → security → optimality — does not apply here. The rules
  below are for new code and for a dependency or responsibility a PR adds or changes; editing a file does not oblige
  rewriting what it already does (known gaps are listed at the end):
  - Single responsibility: a class or module has one reason to change.
    - How to show a value — money, dates, plurals, the text for a value of an open code (`ApiCode`: `ChangedSince`,
      `AnalyticsFeature`, `OperationKind`), a text that depends on data (`ImportErrorTexts` takes the file's name and
      size) — lives in pure formatters: `Money` (`core/common`), `BlockFormat` (`core/blocks`), the feature's
      `domain/*Format` and `*Texts` objects. A ViewModel or a `…Content` calls them instead of building such strings
      itself.
    - A ViewModel decides *what* to show — which message for an `AppError` subtype or for an `AppError.Api` with a
      code of `ErrorCodes`, which server text — and keeps it in the state; a `…Content` draws the state and calls
      formatters, but makes no API calls and decides no rules.
    - A repository moves and caches data; it does not decide business rules.
    - The models of `core/api` are the client's model: features use them as they are, with no separate domain model
      and mapper. Where a conversion exists (`BlockRenderer.prepare` decoding a block, `core/network` mapping errors
      to `AppError`), decoding stays apart from the code that uses the result.
  - Open/closed: behaviour that has an interface grows by a new implementation — a new `BlockRenderer` in `CatalogV1`,
    a new data source, a new `FeatureEntry` — not by a branch or flag inside existing code. A new value of an open code
    (`ApiCode`, `ActionType`, `ValueKind`, `BlockStatus`, a subtype of `AppError`) is a new branch in the existing
    `when` next to `UNKNOWN`, as the extensibility rules below require; that is not a violation. Flags are read at the
    screen boundary (below).
  - Liskov substitution: implementations and fakes (`:mock:backend`, `Fake…` in tests) keep the contract of the
    interface. In `core/api` every call returns a result or throws an `AppError` (`Services.kt`): a fake throws the
    same `AppError` subtype in the same case and succeeds where the real implementation succeeds — no
    `IllegalStateException` instead of `AppError.Offline`, no success where the server answers with `AppError.Api`.
    A fake may fail with `error("unused")` on a method the test must not reach.
  - Interface segregation: interfaces in a feature's `:api` are small and hold only what their consumers need
    (`SessionRepository`, `AppLock`); no "fat" repository for every case. A consumer that needs more gets its own
    narrow interface, not the concrete `…Impl`.
  - Dependency inversion: an `:impl` depends on other features' `:api` (above); a ViewModel depends on interfaces
    injected by Hilt, not on concrete `…Impl` classes or on `Context`, `Application` and platform services
    (`ContentResolver`, `PackageManager`). `SavedStateHandle` is allowed: it keeps the state through process death and
    is built in unit tests without Android.
  - Known gaps, left as they are until a PR changes that dependency: `UploadViewModel` and `HistoryViewModel` (in
    `HistoryScreen.kt`) — `@ApplicationContext` in the first, `StatementsRepositoryImpl` in both;
    `CallViewModel` and `ConsentViewModel` (`SessionRepositoryImpl`) — they reach methods the `:api` interfaces
    do not have.
- System bar insets go through `PfInsets.statusBars` / `PfInsets.navigationBars`, never `WindowInsets.*`
  directly: design-check snapshots replace them with the mockups' fixed bars.
- "Now" and "today" come from the injected `Clock` (`LocalPfClock` in composables), never `LocalDate.now()` /
  `OffsetDateTime.now()`: the e2e build pins that clock, and the whole app has to live on the same date.
  `Clock.zone` is the profile zone (`ReportZone`, kept by the profile feature), not the device's: days and months
  on the client match analytics (FIN-92). It can change while the process lives — read it when needed, do not keep it.
- A PR that adds a screen, a new state of a screen, a bottom sheet or a dialog wires it into the design check in
  the same PR: a `…Content` the test can render, and a `*DesignCheckTest` capturing it under the artboard name
  (`popups = true` for a sheet or dialog). An artboard is a top-level screen frame in Figma, named like `Main`
  or `MainDark`. Capture every artboard Figma has for the screen, `…Dark` ones included; without artboards,
  capture the base state in light and dark and other states in light, as the mockups do. No artboard in Figma
  yet — capture it under the name the artboard should get and say in the PR that the mockup is missing.
  Steps: `design-check/README.md`, «Добавить экран».
- A screen is a thin ViewModel wrapper around `…Content(state, handlers)`; design-check tests render the
  content from mockup data (`design-check/README.md`).
- Flags are read at the screen boundary (view model init or `FeatureEntry`), never mid-screen.
- Parsing of API responses tolerates what the client does not know yet (rules for new code; existing gaps — FIN-84):
  - unknown fields are ignored (`ApiJson`: `ignoreUnknownKeys`);
  - an open code is an `ApiCode` enum with `UNKNOWN` and an `OpenEnumSerializer`; what `UNKNOWN` means (hide, fall
    back to another value, show the server's text) is the rule of that field, written in the field's KDoc (or the
    enum's, when every field using the enum follows the same rule);
  - a closed `enum` in a DTO only for a set that cannot grow (none in responses today: `Theme` and the filter `kind`
    became open codes in FIN-83 — `Theme` falls back to `SYSTEM`, `OperationKindFilter` keeps an unknown code as is);
  - a broken element of a list is skipped, not allowed to fail the whole response;
  - a new DTO or open code comes with a unit test that parses an unknown field and an unknown code.
- When the server sends a ready text for a code (`*_name`, `text`, lock texts), the UI shows that text for a code it does
  not know instead of building one itself or skipping it (FIN-83: import features and notices, `changed_since_text`).
- Upload history uses inclusive profile calendar days `period_from` / `period_to`, without device-zone conversion
  (FIN-198); old `uploads[].first_operation_at` / `last_operation_at` are removed. Import result 3.3 is unchanged.
- Operation details 4.2 and category change 4.3 require `occurred_at` from the selected record, including offset
  and fractional seconds (FIN-203); use the latest returned timestamp for retries and reopening the record.
- A new block type or code value changes both `:core:api` and the fake backend `:mock:backend` in the same PR.
- `:core:api` and `:mock:backend` move to Kotlin Multiplatform when iOS starts (FIN-144): new code and dependencies
  there use only what KMP has — kotlinx.serialization, kotlinx-coroutines, kotlinx-datetime, the common stdlib
  (`kotlin.uuid.Uuid`, `kotlin.time.Duration`, `kotlin.io.encoding.Base64`) — never new `java.*`, `javax.*`,
  `android.*`, `androidx.*`, Dagger / Hilt, JVM-only libraries or JVM-only stdlib (`synchronized`, `String.format`).
  An API already in `scripts/kmp-baseline.json` may be reused; an exception is justified in the PR and added there.
  `scripts/check-kmp-ready.py` (CI) fails on anything beyond the baseline; rule, inventory — `docs/kmp-readiness.md`.
- The Analytics screen is composed by the server (FIN-4): blocks of the catalog (api.md «Блоки») drawn by the renderers of
  `core/blocks`; texts of blocks come from the server (in `mock` — `AnalyticsServerTexts`). A new primitive is a renderer in
  `CatalogV1` (it goes into `supports` by itself) plus the composer of the fake server. Design-check fixtures of the screen
  are golden responses the composer writes (`AnalyticsDesignFixturesTest`, `UPDATE_GOLDEN=1`).
- A PR that adds or changes a user scenario covers it with a UI test in the same PR (docs/e2e.md, «When a change
  needs a UI test»). A scenario is what the user does and sees: a new screen or state, a step between screens, an
  error, empty or offline path they can reach, a flag that changes behaviour, a user-visible bug fix (the flow
  reproduces the bug first). «Can reach» means in a real build (no network, a server error, an empty statement);
  tests bring it about with a fixture, the control link or a flag. Not a scenario: copy, colours or layout of an
  existing screen (the design check covers them), logic without UI (unit tests), a refactoring that keeps behaviour.
  - Maestro by default: extend the feature's flow or add one under `.maestro/flows/<feature>/`. A native test in
    `app/src/androidTestMock` only for what Maestro cannot drive (system pickers, intents, permissions).
  - Tag the root, the controls and the checked states (`<feature>.<screen>[.<element>]`, shared components `ds.*`).
    Renaming a tag means updating `.maestro/`.
  - A new or renamed test also updates its Qase case, `scripts/qase-cases.json` and the coverage table in
    docs/e2e.md («Qase», «Coverage»).
  - Not automatable on the emulator (biometrics, voice, a bug that does not reproduce there): add it to «Not
    covered yet» in docs/e2e.md and say so in the PR. The reason must be technical — what the emulator or the mock
    cannot do — not the effort.
  - CI runs UI tests after the merge to `main`, never on a PR by itself: a new or changed flow is first run there,
    unless it was run locally with `scripts/e2e.sh` or the owner started the `UI tests` workflow for the branch by
    hand. Say in the PR whether the flows were run and how.
- Document in moderation: KDoc on public `:api` interfaces, comments where logic is non-obvious
  (accounting rules, idempotency, animation), not on every method.

## Tests

Every new piece of functionality is covered by automated tests in the same PR — not in a follow-up. Layers and
commands: `docs/testing.md`.

- Logic (domain, data, mappers, view models, `mock/backend`, `core/*`) → unit tests in the module's `src/test`
  covering the main path and every edge case and branch the code handles. View models are tested through their
  state and handlers, with fake `:api` dependencies.
- Screens, states, sheets, dialogs → design check; user scenarios → UI tests (both rules in Code, details in
  docs/e2e.md).
- A bug fix comes with a test that fails without the fix: a UI flow for a user-visible bug (see Code), plus a
  unit test when the cause is in logic.
- The fake backend's calculation follows `docs/calc.md` of contracts: `CalcFixturesTest` requires its responses to
  equal the calculation fixtures, any difference fails CI (`docs/testing.md`, «Calculation fixtures»).
- The fake backend's responses follow `openapi.yaml` and section 8 of `docs/api.md` of contracts: the contract tests
  fail on a field, type, status or error code the pinned contract does not have (`docs/testing.md`, «Contract tests»).
  A contract change updates the pinned copy (`scripts/sync-contracts.sh`) in its own PR, and the failing cases show
  where the mock must follow; a mismatch left for later goes to `Exclusions.kt` with its issue, never into the validator.
- The measurable check is coverage of changed lines by unit tests: at least 80 % of changed executable lines
  (Build in an agent container → Coverage). Composables are excluded from the report — they are covered by the
  design check and UI tests, not by this number. Uncovered changed lines are listed in the PR («Проверка») with
  the reason; an uncovered line without a reason is a review finding.
- Tests assert behaviour; do not write tests that only execute lines.

## Workflow

- Branches: `<kind>/<slug>` — `fix/…`, `feature/…`, `tooling/…`, `design-check/…` (historically `stage-N/…`).
  `release/<version>` (`release/0.2.5`) is the release branch of a version, cut from `main`; CI runs on pushes to it.
  Commits: imperative English subject (`Add …`, `Fix …`, `Address review: …`).
- A PR targets the current release branch — the newest `release/<version>` not yet merged into `main` — not `main`;
  the owner merges the release branch into `main` with its own PR. When every release branch is already merged, target
  `main`. A release branch counts as merged only when both hold: a merged PR from it into `main` exists
  (`gh api 'repos/fin-assist/android/pulls?state=closed&base=main&head=fin-assist:release/<version>'`, an entry with
  `merged_at`) and `git merge-base --is-ancestor origin/release/<version> origin/main` exits 0. The ancestor check
  alone is not enough: a branch just cut from `main` points at the same commit and passes it.
  Fetch both refs first, with explicit refspecs (a single-branch or shallow clone has no `origin/release/*`):
  `git fetch --depth=500 origin '+refs/heads/main:refs/remotes/origin/main'
  '+refs/heads/release/*:refs/remotes/origin/release/*'` (no `--depth` in a full clone). Exit 1 means «not an
  ancestor»; any other code means a missing ref — fetch and run again, never take it as an answer.
- PR title and description in Russian: what changed and why, deviations from mockups/docs, how it was checked.
  A PR for a single YouTrack issue starts its title with the issue key (`FIN-42 Краш при открытии поиска`);
  a PR for several issues lists all of them in the description, each linked
  (`https://finassist.youtrack.cloud/issue/FIN-<n>`). No issue — no key in the title.
- After the PR is open and all planned changes are pushed, request a review with one PR comment in Russian
  addressed to both reviewers: it starts with `@codex @claude`, and the text after them is addressed to both (never
  «@claude, …» alone — Codex reads the same comment):

  ```
  @codex @claude проведите ревью этого PR по Code Review Rules в AGENTS.md, включая разделы Security и Test coverage
  of use cases. Замечания — на русском, inline на изменённых строках. Особое внимание: <что именно и почему>.
  ```

  - `@claude` — `.github/workflows/claude.yml` (runs from the default branch), Claude Opus 5.5 (`claude-opus-5-5`,
    with `--effort high`, set in `claude_args`; FIN-204);
  - `@codex` — the Codex GitHub integration, under the owner's ChatGPT subscription. Its model is chosen by
    OpenAI and cannot be pinned from here; a pinned model would need the OpenAI API with per-token billing, which was
    declined (FIN-103).
- Waiting for the review: when the session receives the PR's GitHub events (reviews, comments, CI), react to them
  instead of polling. Otherwise check 3 minutes after the request, then every 2 minutes. A reviewer has answered when
  it posted its review or summary for the latest commit; for Codex (`chatgpt-codex-connector[bot]`) «Didn't find any
  major issues» or a 👍 reaction without a comment also counts. If a reviewer has not answered within 15 minutes of the last request, say so in the
  PR and to the owner and stop waiting for it. The cycle ends when both reviewers have answered the latest request and
  no review thread is open.
- Address review findings in follow-up commits on the same branch, reply in the conversation and resolve it;
  then request a review again and wait the same way.
  Agents never merge a PR (into a release branch or `main`); the owner does.

## Code Review Rules

Read by `@claude` (through `CLAUDE.md`) and by the Codex integration, which looks for this heading. Two areas get a
dedicated pass on every PR: Security and Test coverage of use cases (below).

### General

- Write all review comments in Russian (summary and inline comments). Code identifiers, API field names and
  quotes from the code stay as they are.
- Contract: `docs/api.md` / `openapi/openapi.yaml` in fin-assist/contracts; in this repo the
  rules are mirrored in KDoc of `core/api` and `core/network`. In the `@claude` review run the contract is checked
  out read-only at `.contracts/` (`main` of fin-assist/contracts, FIN-174): check client changes that follow the
  contract against the contract itself (`.contracts/docs/api.md`, `openapi/openapi.yaml`, `docs/calc.md` for the
  fake backend), not only against the PR description; when `.contracts/docs/api.md` is missing (no checkout,
  or a failed one that left only `.git`), fall back to the KDoc and say so in the summary. Toggles: `docs/flags.md`. Modules: `docs/modules.md`.
- Report only verified problems with a concrete failure scenario; skip style nits.
- A SOLID violation in new or changed code (see Code) is a finding when it has a concrete consequence: name what
  cannot be replaced, tested with a fake or extended without editing existing code. A violation without such a
  consequence, one in code the PR does not touch, or one of the known gaps listed there (unless the PR changes that
  dependency), is not a finding.
- Post each finding as an inline comment on the changed line (a review conversation that can be resolved),
  not only in a summary comment. The summary lists what was checked, with a «Безопасность» part and a «Покрытие
  сценариев» part (see below); «not affected» is a valid answer for either, with one line why.
- Check that the PR updates this file and `docs/` when it changes what they describe.

### Security

The app holds a user's bank operations, phone number and app-lock secrets (personal data under 152-ФЗ). A finding
names what leaks or breaks and how an attacker or another app gets to it. Check the changed code for:

- Personal and financial data (phone, amounts, counterparties, operation descriptions, statement content, assistant
  dialogue) leaving the device or the app's private storage other than through the API client: logs, crash reports
  (Tracer), analytics events, Remote Config parameters (only our opaque `user_id` / device id go there), exception
  messages, clipboard, notifications, files in shared storage.
- Secrets: auth tokens, the passcode and keys are stored encrypted with a Keystore-backed key (as the passcode in
  `feature/applock`), never in plain preferences, files, `BuildConfig`, resources or source; no real keys or tokens
  in the repository.
- Network: HTTPS only, no cleartext exceptions, no custom `TrustManager` / `HostnameVerifier` that weakens checks;
  auth headers not logged. Certificate pinning (FIN-121) only through the generated `pin-set` of `prod`: at least two
  pins, an `expiration`, no `trust-anchors` / `debug-overrides`; pin failures reported without URL, host or
  certificate data.
- Untrusted input — the OFX file the user picks (read and uploaded by the app, parsed today by the fake backend
  `mock/ofx`) and every API response: size and depth limits, no XML external entities or DTD expansion in OFX 2.x,
  malformed or hostile data ends in a handled error, not a crash or a hang.
- Android surface: a new exported component, intent filter or deep link validates its input and does not act on
  behalf of the caller without checks; `PendingIntent`s are immutable unless they must not be; no WebView with
  JavaScript bridges to app data.
- Backup and data extraction: new stores (databases, DataStore, files) stay covered by
  `allowBackup="false"` / `data_extraction_rules.xml`.
- Build separation: mock, e2e and debug hooks (`app/src/mock*`, `E2eControlActivity`, the control link, test
  flags) stay out of the `local` and `prod` flavors and release builds; cleartext HTTP only in `localDebug`, to the
  one host of `pf.apiBaseUrl` (FIN-120).
- Trust boundaries: limits, quotas and permissions are enforced by the backend; the client only shows them.
  Client-side checks are not the security control — flag code that relies on them.
- Consent: user data goes to the LLM assistant only after the assistant consent. Before phone input, a separate
  unchecked 14+ confirmation is required; before Start, a separate unchecked personal-data consent and the viewed
  document version are required for new and existing users alike (FIN-365). Server checks and server receipt times
  are authoritative. Register reuses the original Start version without a second checkbox or a current-document GET.
  A received Start 429 means wait, then an explicit new intent with a new key; a lost transport response reuses
  the exact original request and key. Explicit restart resets the flow/key and both choices without a POST,
  preserving any captured Start429 wait shared across Phone/Call navigation. Server-only unknown deadlines are never hard-coded in the client.
- New dependencies: flag a new library or SDK that gets data access (network, storage, analytics) so the owner
  decides on it.

### Test coverage of use cases

The criterion is not a percentage of lines but whether the use cases of the changed functionality are covered.
For each feature or behaviour the PR adds or changes:

1. List its use cases from the code, the mockups and the issue: the main path; alternative paths; every error the
   user can reach (no network, server error, unknown code, invalid or hostile input); empty, first-run and
   loading states; boundary values (zero, negative, very large amounts, currency, dates at month and year
   boundaries, time zones, long texts); each flag state that changes behaviour; repeated actions (double tap,
   retry), back navigation, configuration change and process death where state must survive.
2. Map each case to the test that covers it: unit test, design check or UI flow (see Code and Tests).
3. Post a finding for an uncovered case only when the case is reachable in the changed code and its behaviour is
   added or changed by the PR; name the concrete case and the layer that should cover it. The list in step 1 is a
   prompt, not a checklist to report item by item. A case not worth a test (covered by a lower layer, not
   reproducible in the mock) is fine when the PR or the summary says why.

The 80 % changed-line threshold in Tests is the author's minimum; meeting it does not settle this check.

Also check:

- A test counts only if it asserts the behaviour of that case and would fail if the case broke; a test that only
  runs lines, asserts on a mock it set up itself or snapshots without a check is not coverage.
- A bug fix has a test that fails without the fix and reproduces the user's case, not a neighbouring one.
- Security-relevant behaviour (consent gates, app lock, hostile OFX, unknown codes) has negative tests: what must not
  happen is asserted, not only what must.

### Checklist
- Check that a new screen, screen state, bottom sheet or dialog comes with its `*DesignCheckTest` (see Code).
- Check that a new or changed user scenario comes with a UI test (or a «Not covered yet» entry with a technical
  reason), its Qase case, its line in `scripts/qase-cases.json` and its row in the «Coverage» table of docs/e2e.md
  (see Code).
- Check that changes in `core/api` and `mock/backend` add no JVM-only APIs or dependencies (see Code,
  `docs/kmp-readiness.md`): a new entry in `scripts/kmp-baseline.json` and a change of its `build` lines need a reason
  in the PR, a new library needs an iOS target, and what the check does not see is a finding — JVM-only stdlib members
  (`synchronized`, `String.format`, `toBigDecimal*`, `java.io` stream extensions, `.java` / `javaClass`,
  `RegexOption.DOT_MATCHES_ALL`, JDK default methods on collections such as `computeIfAbsent`),
  dependencies added through the convention plugins of `build-logic` and code shaped to slip past the check (it is a
  heuristic against accidental JVM-only APIs, not a guard against a deliberate bypass; «Known limits»).
- Check that new DTOs and mappers follow the extensibility rules (see Code): no closed `enum` for a set that grows,
  no failure on an unknown field or code, and a unit test that parses an unknown value.
- Check that new functionality comes with tests (see Tests) and that the tests would fail if the behaviour
  broke; flag changed lines the «Unit test coverage» comment of the PR (posted by CI) shows as uncovered when
  the PR gives no reason for them.

## Renovate automation

FIN-211: dependency-only Renovate PRs may be merged/closed by the reviewed `renovate-policy.yml` controller, after exact-head required CI and bot-only commit checks. Ordinary PRs and release-to-main PRs still require the owner. Do not enable native automerge or bypass protections. Preserve base policy in `renovate-base.json` (schedules, groups, allowed versions and manual Go upgrades); generated target/version approvals live on the separate `renovate-policy` branch. Failed exact values are isolated for explicit Dashboard approval; new values keep their original groups. AndroidX/Compose and mixed groups never auto-merge. See `docs/renovate-policy.md` for credentials, safeguards, bootstrap and verification.
