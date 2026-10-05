# Modules

Monorepo with Gradle modules. Every feature is split into `:api` (interfaces, models, routes) and `:impl`
(data, domain, ui). The rule that keeps features independent: **a feature depends on another feature only
through its `:api` module**. `:app` is the only module that sees `:impl` modules and binds interfaces to
implementations (Hilt). The rule is enforced at configuration time by `pf.module.rules`
(`build-logic/convention/src/main/kotlin/ModuleRulesPlugin.kt`); a violation fails the build.

```
app                       composition root: Hilt graph, NavHost, tabs, app-lock gate, SDK init, flavors mock/prod
core/common               Money, dates/periods, AppError, Result helpers (JVM)
core/api                  client API contract: DTOs + service interfaces from openapi.yaml (JVM)
core/network              HTTP implementation of core/api: OkHttp, Retrofit, SSE, auth, idempotency (prod)
core/designsystem         tokens, theme, components, chart animation (Compose)
core/navigation           FeatureEntry, Navigator, shared route arguments
core/toggles              FeatureFlags interface + flag registry with code defaults (JVM)
core/tracking             Tracker / CrashReporter interfaces, event naming (JVM)
providers/toggles-local   flags from a JSON asset + debug overrides (mock flavor)
providers/tracking-log    logcat tracker (mock flavor)
providers/toggles-rustore RuStore Remote Config (prod, stage 8)
providers/tracking-mytracker, providers/crash-appmetrica (prod, stage 8)
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
2. Create `feature/<name>/api/build.gradle.kts` with `alias(libs.plugins.pf.feature.api)` and
   `feature/<name>/impl/build.gradle.kts` with `alias(libs.plugins.pf.feature.impl)`.
3. Add `implementation(project(":feature:<name>:api"))` + `:impl` to `app/build.gradle.kts`.
4. Register the `FeatureEntry` in the feature's Hilt module.
