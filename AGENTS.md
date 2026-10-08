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

- Contract, MVP scope, architecture, mockup decisions: docs of the claude.ai project «Финансовый
  консультант» (`api.md`, `openapi.yaml`, `mvp-scope.md`, `architecture.md`, `android-plan.md`). Mockups:
  the Claude Design canvas (link in `design-check/README.md`).
- Alpha 0.1 (API, mockups, docs) is frozen; every change goes through a YouTrack issue (`FIN-<n>`,
  finassist.youtrack.cloud) and is referenced in the PR. Release notes live in Confluence.
- There is no backend yet: the `mock` flavor is the working product, `prod` is the HTTP client for later.
- Mismatches between mockups, docs and API are expected. Resolve them in favour of `api.md` + the latest
  decision, say so in the PR, and propose (not impose) engineering changes; keep them non-radical.

## Repository map

Details and dependency rules: `docs/modules.md`. Entry points worth knowing:

| What | Where |
|---|---|
| Composition root, NavHost (auth graph → main tabs), app-lock gate | `app/src/main/kotlin/ru/finassist/pf/ui/PfApp.kt`, `di/AppModule.kt`; `app/src/prod/…/ProdModule.kt` |
| API contract (service interfaces + DTOs) | `core/api/…/Services.kt`, `core/api/…/model/*.kt` |
| Fake backend (mock flavor): OFX parsing, ledger, analytics, assistant | `mock/backend/…/mock/` — `MockBackend.kt`, `domain/*`, `MockConfig.kt` (existing phone, delays, `offline`) |
| Feature routes and public interfaces | `feature/<name>/api/…/*Routes.kt` (or `AuthApi.kt`, `AppLock.kt`) |
| Screen registration | `FeatureEntry` / `Navigator` in `core/navigation/…/Navigation.kt`; each `:impl` binds its entry in `di/` |
| Design system: theme, tokens, components, icons | `core/designsystem/…/theme`, `components`, `icons` |
| Feature flags | `core/toggles/…/Flag.kt` (registry + defaults), `docs/flags.md` (meaning); mock overrides `app/src/mock/assets/flags.json` |
| Events / crashes | `core/tracking/…/Tracker.kt`; providers in `providers/*` (only `:app` depends on them) |
| Tokens, preferences, idempotency keys | `core/storage` |
| Screens vs mockups (snapshot comparison) | `design-check/README.md`, `*DesignCheckTest.kt` in feature `:impl` modules |

Package root: `ru.finassist.pf`. Flavors: `mock` (default for development and CI) and `prod`.

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
| `:app` | `pf.android.application`, `pf.hilt` | - |
| `:core:api` | `pf.jvm.library` | unit |
| `:core:common` | `pf.jvm.library` | unit |
| `:core:designsystem` | `pf.android.library.compose` | - |
| `:core:navigation` | `pf.android.library.compose` | - |
| `:core:network` | `pf.android.library`, `pf.hilt` | unit |
| `:core:screenshot-testing` | `pf.android.library.compose` | - |
| `:core:storage` | `pf.android.library`, `pf.hilt` | - |
| `:core:toggles` | `pf.jvm.library` | - |
| `:core:tracking` | `pf.jvm.library` | - |
| `:providers:crash-appmetrica` | `pf.android.library`, `pf.hilt` | - |
| `:providers:toggles-local` | `pf.android.library`, `pf.hilt` | - |
| `:providers:toggles-rustore` | `pf.android.library`, `pf.hilt` | - |
| `:providers:tracking-log` | `pf.android.library`, `pf.hilt` | - |
| `:providers:tracking-mytracker` | `pf.android.library`, `pf.hilt` | - |
| `:mock:backend` | `pf.android.library`, `pf.hilt` | unit |
| `:feature:analytics:api` | `pf.feature.api` | - |
| `:feature:analytics:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:applock:api` | `pf.feature.api` | - |
| `:feature:applock:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:assistant:api` | `pf.feature.api` | - |
| `:feature:assistant:impl` | `pf.feature.impl` | unit |
| `:feature:auth:api` | `pf.feature.api` | - |
| `:feature:auth:impl` | `pf.feature.impl` | unit |
| `:feature:operations:api` | `pf.feature.api` | - |
| `:feature:operations:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:profile:api` | `pf.feature.api` | - |
| `:feature:profile:impl` | `pf.feature.impl`, `pf.screenshots` | unit, design-check |
| `:feature:statements:api` | `pf.feature.api` | - |
| `:feature:statements:impl` | `pf.feature.impl` | unit |
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

- Fresh Linux container: run `scripts/agent-setup.sh` once (Android SDK matching `gradle/libs.versions.toml`,
  `local.properties`, a Maven Central mirror in `~/.gradle/init.d`). Needs JDK 21 and network access to
  Google Maven, Gradle and Maven Central.
- Then `ANDROID_HOME=/opt/android-sdk ./gradlew assembleMockDebug testMockDebugUnitTest` — the same checks as CI.
- `python3 scripts/agent-docs.py --check` and `python3 -m unittest discover -s scripts -p 'test_*.py'` — the
  docs check and its parser tests from CI, no Gradle needed.

## Code

- Code, KDoc and commit messages are in English; user-facing strings are in Russian.
- Each feature is `:api` + `:impl`; an `:impl` depends on other features only through their `:api`
  (enforced by `pf.module.rules`).
- System bar insets go through `PfInsets.statusBars` / `PfInsets.navigationBars`, never `WindowInsets.*`
  directly: design-check snapshots replace them with the mockups' fixed bars.
- A screen is a thin ViewModel wrapper around `…Content(state, handlers)`; design-check tests render the
  content from mockup data (`design-check/README.md`).
- Flags are read at the screen boundary (view model init or `FeatureEntry`), never mid-screen.
- Document in moderation: KDoc on public `:api` interfaces, comments where logic is non-obvious
  (accounting rules, idempotency, animation), not on every method.

## Workflow

- Branches: `<kind>/<slug>` — `fix/…`, `feature/…`, `tooling/…`, `design-check/…` (historically `stage-N/…`).
  Commits: imperative English subject (`Add …`, `Fix …`, `Address review: …`).
- PR title and description in Russian: what changed and why, YouTrack issues, deviations from mockups/docs.
- After the PR is open and all planned changes are pushed, request a review with one PR comment in Russian
  addressed to both `@codex` and `@claude`, asking for comments in Russian. `@claude` runs from
  `.github/workflows/claude.yml` (default branch), `@codex` is the Codex GitHub integration.
- Address review findings in follow-up commits on the same branch and reply in the conversation.

## Review guidelines

- Write all review comments in Russian (summary and inline comments). Code identifiers, API field names and
  quotes from the code stay as they are.
- Contract: `api.md` / `openapi.yaml` in the claude.ai project «Финансовый консультант»; in this repo the
  rules are mirrored in KDoc of `core/api` and `core/network`. Toggles: `docs/flags.md`. Modules: `docs/modules.md`.
- Report only verified problems with a concrete failure scenario; skip style nits.
- Post each finding as an inline comment on the changed line (a review conversation that can be resolved),
  not only in a summary comment; the summary lists what was checked.
- Check that the PR updates this file and `docs/` when it changes what they describe.
