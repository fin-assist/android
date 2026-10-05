# Понятные финансы — Android

Android client for the «Понятные финансы» personal-finance app: T-Bank OFX statement import, analytics, LLM assistant.
Contract: client API v1 (`api.md` / `openapi.yaml` in the product project). Design: «Понятные финансы» design system.

## Build

Requirements: JDK 17, Android Studio (2025.x) with SDK 36.

```sh
# Mock flavor: in-process fake backend on an OFX statement, no network, no SDK keys needed
./gradlew assembleMockDebug

# Unit tests and lint
./gradlew testMockDebugUnitTest lintMockDebug
```

Put your own T-Bank OFX export at `app/src/mock/assets/private/statement.ofx` (gitignored) to preload the mock backend with real data; otherwise the anonymized fixture is used. In the mock flavor the upload screen parses any OFX chosen with the system file picker on device.

## Layout

See `docs/modules.md` (module graph and rules), `docs/flags.md` (feature flags and events).
