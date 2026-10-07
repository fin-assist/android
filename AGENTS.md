# Instructions for code agents

## Review guidelines

- Write all review comments in Russian (summary and inline comments). Code identifiers, API field names and
  quotes from the code stay as they are.
- Contract: `api.md` / `openapi.yaml` in the claude.ai project «Финансовый консультант»; in this repo the
  rules are mirrored in KDoc of `core/api` and `core/network`. Toggles: `docs/flags.md`. Modules: `docs/modules.md`.
- Report only verified problems with a concrete failure scenario; skip style nits.
- Post each finding as an inline comment on the changed line (a review conversation that can be resolved),
  not only in a summary comment; the summary lists what was checked.

## Build in an agent container

- Fresh Linux container: run `scripts/agent-setup.sh` once (Android SDK matching `gradle/libs.versions.toml`,
  `local.properties`, a Maven Central mirror in `~/.gradle/init.d`). Needs JDK 21 and network access to
  Google Maven, Gradle and Maven Central.
- Then `ANDROID_HOME=/opt/android-sdk ./gradlew assembleMockDebug testMockDebugUnitTest` — the same checks as CI.

## Code

- Code, KDoc and commit messages are in English; user-facing strings are in Russian.
- Each feature is `:api` + `:impl`; an `:impl` depends on other features only through their `:api`
  (enforced by `pf.module.rules`).
