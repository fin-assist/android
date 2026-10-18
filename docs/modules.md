# Modules

Monorepo with Gradle modules. Every feature is split into `:api` (interfaces, models, routes) and `:impl`
(data, domain, ui). The rule that keeps features independent: **a feature depends on another feature only
through its `:api` module**. `:app` is the only module that sees `:impl` modules and binds interfaces to
implementations (Hilt). The rule is enforced at configuration time by `pf.module.rules`
(`build-logic/convention/src/main/kotlin/ModuleRulesPlugin.kt`); a violation fails the build.

```
app                       composition root: Hilt graph, NavHost, tabs, app-lock gate, SDK init, flavors mock/local/prod (dimension `env`)
core/common               Money, dates/periods, AppError, Result helpers (JVM)
core/api                  client API contract: DTOs + service interfaces from openapi.yaml (JVM)
core/network              HTTP implementation of core/api: OkHttp, Retrofit, SSE, auth, idempotency (local, prod)
core/designsystem         tokens, theme, components, chart animation (Compose)
core/blocks               renderers of the block catalog (api.md «Блоки»): registry, `supports`, Analytics and assistant (Compose, FIN-7)
core/navigation           FeatureEntry, Navigator, shared route arguments
core/toggles              FeatureFlags interface + flag registry with code defaults (JVM)
core/tracking             Tracker / CrashReporter interfaces, event naming, typed crash context (JVM)
core/storage              DataStore preferences + Tink-encrypted TokenStore (all flavors)
core/screenshot-testing   design-check snapshot harness (Robolectric + Roborazzi); test-only dependency via `pf.screenshots`
providers/toggles-local   flags from a JSON asset + debug overrides (mock, local)
providers/tracking-log    logcat tracker (mock, local)
providers/toggles-rustore RuStore Remote Config (prod, stage 8)
providers/tracking-mytracker MyTracker events (prod, stage 8)
providers/crash-tracer    Tracer crashes, ANR, non-fatals (mock, prod; off without `pf.tracerAppToken` and always in local)
mock/backend              in-process fake backend on an OFX statement, implements core/api (mock flavor)
feature/<name>/api        public surface of the feature (JVM)
feature/<name>/impl       data / domain / ui + Hilt bindings (Android, Compose)
```

Features: `auth`, `applock`, `operations`, `statements`, `analytics`, `assistant`, `profile`.

## Dependency rules

| From | May depend on |
|---|---|
| `:feature:*:impl` | its own `:api`, any `:feature:*:api`, `:core:*` |
| `:feature:*:api` | `:core:common`, `:core:api`, other `:feature:*:api` |
| `:core:*` | `:core:*` |
| `:providers:*`, `:mock:*` | `:core:*` |
| `:app` | anything |

## Flavors of `:app` (dimension `env`, FIN-120)

| Flavor | Modules it adds | Source sets |
|---|---|---|
| `mock` | `:mock:backend`, `:providers:toggles-local`, `:providers:tracking-log` | `src/mock` (flags asset), `src/mockE2e` (UI-test hooks), `src/androidTestMock` |
| `local` | `:core:network`, `:providers:toggles-local`, `:providers:tracking-log` | `src/local` (`LocalModule`: base URL, name), `src/localDebug` (network security config) |
| `prod` | `:core:network`, `:providers:toggles-rustore`, `:providers:tracking-mytracker` | `src/prod` (`ProdModule`: base URL, SDK keys); with `pf.apiPins` — a generated network security config with a `pin-set` and its manifest overlay (`ApiPins` in build-logic, FIN-121) |

`:mock:*` never reaches `local` or `prod` (`:app:checkNoMockCode` in CI checks every variant outside `mock`, so a new flavor is covered by itself). A future `stage` flavor follows `prod`.
Variants that are off: `localE2e`, `prodE2e` (`androidComponents.beforeVariants` in `app/build.gradle.kts`).

## Inside `:impl`

```
impl/src/main/kotlin/ru/finassist/pf/feature/<name>/impl/
  data/       repositories (implement :api interfaces), mappers DTO → domain
  domain/     use cases, pure logic (no Android)
  ui/         screens, view models, navigation entry (FeatureEntry)
  di/         Hilt module: @Binds impl → :api interface, @IntoSet FeatureEntry
```

## How a feature exposes itself

- Routes: `@Serializable` route classes in `:api` (e.g. `AnalyticsRoute(params: AnalyticsParams?)`). Another
  feature navigates with `navigator.navigate(AnalyticsRoute(...))` and never imports the screen.
- Screens: `:impl` registers them through `FeatureEntry.install(NavGraphBuilder, Navigator)`, collected in
  `:app` as `Set<FeatureEntry>` (Hilt multibinding).
- Data: interfaces in `:api` (`SessionRepository`, `CategoriesRepository`, …), implementations in `:impl`,
  bound in the feature's Hilt module. Hilt modules are discovered automatically, `:app` only needs the
  `:impl` dependency.

## Adding a feature

1. Add `include(":feature:<name>:api")` / `:impl` to `settings.gradle.kts` (the `listOf(...)` there).
2. Create `feature/<name>/api/build.gradle.kts` with `id("pf.feature.api")` and
   `feature/<name>/impl/build.gradle.kts` with `id("pf.feature.impl")`.
3. Add the name to the `listOf(...)` of features in `app/build.gradle.kts` (wires `:api` + `:impl`).
4. Register the `FeatureEntry` in the feature's Hilt module.
5. Describe the module here and run `python3 scripts/agent-docs.py --write` (CI checks both).
