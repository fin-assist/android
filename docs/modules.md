# Module graph

```
app ──────────────┬─ feature/<name>/impl  (data · domain · ui; Hilt bindings; FeatureEntry)
                  ├─ feature/<name>/api   (interfaces, @Serializable routes, value models)
                  ├─ core/*               (common, designsystem, navigation, network, toggles, tracking)
                  └─ providers (by flavor): mock-backend, toggles-local|toggles-rustore, tracking-log|tracking-mytracker
```

Rules (enforced by `build-logic/.../ModuleRulesPlugin.kt` at configuration time):

1. A feature is two modules: `:feature:<name>:api` and `:feature:<name>:impl`. `impl` depends on its own `api`.
2. A feature that needs another feature depends on that feature's `api` only. `impl → impl` fails the build.
3. `api` modules depend only on `core` and other `api` modules. No Hilt, no Compose, no network.
4. `core` never depends on `feature`.
5. Only `:app` depends on `impl` modules and on provider modules; `:app` binds interfaces to implementations (Hilt).

Inside `impl`: `data/` (API calls, DTO→model mappers, local storage), `domain/` (repositories' logic, use cases when non-trivial), `ui/` (screens, view models), `di/` (Hilt module with `@Binds` and `@IntoSet FeatureEntry`).

Flavors live only in `:app`: `mock` (in-process fake backend, local flags, log tracker) and `prod` (real API, RuStore Remote Config, MyTracker, AppMetrica).

## Feature api contents

| Feature | Exposes |
|---|---|
| auth | `SessionRepository` (tokens, user id, isLoggedIn flow, logout), routes `PhoneRoute`, `LoggedOutRoute` |
| applock | `AppLock` (isLocked, confirm for dangerous actions), routes for passcode setup/login/security |
| operations | routes `FeedRoute`, `SearchRoute(filter)`, `DetailRoute(id)`; `CategoriesRepository` |
| statements | routes `ImportGuideRoute`, `UploadHistoryRoute`, `UnreadLinesRoute`; `StatementsRepository` (summary) |
| analytics | route `AnalyticsRoute(params?)` |
| assistant | route `ChatRoute`; `AssistantLimitRepository` |
| profile | route `ProfileRoute`; `ThemeRepository` |
