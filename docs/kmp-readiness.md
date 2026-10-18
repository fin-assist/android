# Kotlin Multiplatform readiness of `:core:api` and `:mock:backend`

When iOS starts, `:core:api` (the client contract) and `:mock:backend` (the fake backend of the `mock` flavor) are
the modules to move to Kotlin Multiplatform, so that the Swift app reuses the contract and the mock instead of
writing them again (FIN-144). The move itself is not planned yet; until then the modules must not collect more code
that only runs on the JVM or Android.

## Rule

- New code and new dependencies in `core/api` and `mock/backend` use only what Kotlin Multiplatform has:
  the Kotlin common stdlib (`kotlin.time.Duration`, `kotlin.uuid.Uuid`, `kotlin.io.encoding.Base64`,
  `kotlin.random.Random`, `kotlin.concurrent.Volatile`), kotlinx.serialization, kotlinx-coroutines (`-core`),
  kotlinx-datetime, kotlinx-io and other libraries with an iOS target.
- Not allowed in new code: `java.*`, `javax.*` and the other JDK packages (`org.w3c.*`, `org.xml.*`, `sun.*`, …),
  classes of `java.lang` written without an import (`Math`, `System`, `Thread`, …), `android.*`, `androidx.*`,
  Dagger / Hilt, JVM-only libraries (OkHttp, Retrofit, Gson, Guava, …) and JVM-only parts of the Kotlin stdlib
  (`kotlin.io.path`, `kotlin.streams`, `kotlin.reflect.full` / `.jvm`, `kotlin.jvm.optionals`, `synchronized` /
  `@Synchronized`, `String.format`, `toBigDecimal*`, `java.io` stream extensions such as `bufferedReader`,
  `Charsets`, `.java` / `javaClass`).
- An existing JVM-only API (the inventory below) may be reused while the module still has it: the move replaces it
  everywhere at once.
- An exception (a new JVM-only API or dependency) is a decision of the PR: the PR says why there is no
  multiplatform way and adds the entry to the baseline of the check.
- Test sources (`src/test`) are not covered: in a multiplatform module they become JVM / Android test source sets,
  where JUnit, Robolectric and `java.io` fixtures stay valid.
- `:core:api` exposes `:core:common` (`api(projects.core.common)`), so the move needs `:core:common` too; the rule
  and the check do not cover it yet (it is not shared with iOS by itself), the inventory lists it as a prerequisite.

## Check

`python3 scripts/check-kmp-ready.py` (CI, job «Agent docs up to date»; tests — `scripts/test_check_kmp_ready.py`)
reads the production sources of both modules (every source set under `src/` except `test*` and `androidTest*`)
and their `build.gradle.kts`. Comments (nested block comments too) and the text of string literals are skipped; the
code inside `${…}` of a string template, raw strings included, is checked like any other code.

- Every reference to a JVM-only package — an import or a fully qualified class name in the code — is reduced to its
  class (`java.time.Duration.between` → `java.time.Duration`). The packages: `java.*`, `javax.*`, `android.*`,
  `androidx.*`, `dagger.*`, the JDK's `org.w3c.*`, `org.xml.*`, `org.ietf.*`, `org.omg.*`, `sun.*`, `com.sun.*`,
  `jdk.*`, and the JVM-only stdlib packages `kotlin.io.path`, `kotlin.streams`, `kotlin.reflect.full`,
  `kotlin.reflect.jvm`, `kotlin.jvm.optionals`. A fully qualified name counts when it reaches a class (a segment
  with a capital letter), so a local `val android` and `android.version` are not a package.
- Classes of `java.lang` that Kotlin/JVM resolves without an import and common Kotlin has no class of that name for
  (`System`, `Thread`, `Runtime`, `Class`, `ClassLoader`, `Math`, `StrictMath`, `Integer`, `Character`,
  `ProcessBuilder`, `ThreadLocal`), followed by `.`, `(`, `<` or `{`, are reported as `java.lang.<Name>` unless the
  file imports or declares a class of that name.
- `build.gradle.kts` is compared as a whole, normalized (comments dropped, whitespace collapsed, blank lines
  skipped): a plugin or a dependency can be declared in too many forms (`add("api", …)`, `"implementation"(…)`, a
  variable, `apply(plugin = …)`) to recognize each one, and these files change rarely. Any change to the file fails
  the check with the changed lines until the baseline is rewritten; the PR says why the change is
  multiplatform-ready (a new library needs an iOS target), and the reviewer sees it in the diff of the baseline.

The APIs are compared with `scripts/kmp-baseline.json`, per module: something not in the baseline fails the check
with its file and line; a baseline entry the module no longer uses fails it too, so the baseline only shrinks
(`python3 scripts/check-kmp-ready.py --write` regenerates it). A PR that changes one of the two `build.gradle.kts`
or removes an API runs `--write` too, even when nothing JVM-only is added.

Known limits — a review item (Code Review Rules, Checklist in `AGENTS.md`), not seen by the check:

- dependencies that convention plugins of `build-logic` (`pf.jvm.library`, `pf.android.library`, `pf.hilt`) add to
  the module: the module's file does not show them, so a change there is not checked;
- JVM-only members of the Kotlin stdlib in common packages: `synchronized` / `@Synchronized`, `String.format`,
  `toBigDecimal*`, `java.io` stream extensions, `Charsets`, `.java` / `javaClass`, `@Volatile` of `kotlin.jvm`;
- a fully qualified call of a top-level function without a class (`kotlin.io.path.createTempFile()`), and a `java.lang`
  class outside the list above (`StringBuilder`, `Exception` and others have a common typealias).

## Inventory (18.10.2026)

What the move would have to replace. «Replacement» is the expected multiplatform way, not a decision.

### `:core:api`

| File | JVM-only API | Replacement |
|---|---|---|
| `Services.kt` | `java.io.InputStream` (`StatementFile.open`) | `kotlinx.io.Source`, or `ByteArray` read by the platform (picker) side |
| `Services.kt` | `java.time.OffsetDateTime` (`listUnreadLines(from, to)`) | `kotlinx.datetime.Instant` (or `kotlin.time.Instant`) + `UtcOffset` where the offset matters |
| `model/Auth.kt` | `java.time.Instant` (`serverTime`) | `kotlin.time.Instant` / `kotlinx.datetime.Instant` |
| `model/Common.kt` | `java.util.UUID` (`IdempotencyKey.random`) | `kotlin.uuid.Uuid.random()` |
| `model/Common.kt` | `@JvmInline` value classes | stays: `kotlin.jvm.JvmInline` is available in common code |
| `build.gradle.kts` | `pf.jvm.library` | a multiplatform convention plugin (JVM/Android + iOS targets) |
| `build.gradle.kts` | `api(projects.core.common)` | `:core:common` has to move first (below) |

Already multiplatform: kotlinx.serialization (`OpenEnumSerializer`, `ApiJson`), kotlinx-coroutines `Flow`.

### `:mock:backend`

| File | JVM-only API | Replacement |
|---|---|---|
| `build.gradle.kts` | `pf.android.library`, `pf.hilt` (Hilt Android, KSP) | multiplatform plugin with an Android target; DI stays Android-side |
| `build.gradle.kts` | `kotlinx-coroutines-android` | `kotlinx-coroutines-core` (the module uses `Dispatchers.Default` only) |
| `di/MockModule.kt` | `dagger.*`, `dagger.hilt.*`, `javax.inject.Singleton`, `java.util.Optional`, `android.content.Context` | moves to `androidMain` or `:app` (`src/mock`); Swift builds the graph by hand |
| `MockPfApi.kt` | `android.content.Context` (assets), `android.util.Log` | statement text passed in by the platform (expect/actual resource loader); a logger interface |
| `src/main/assets/statements/fixture.ofx` | Android assets | common resources (bundled per platform) |
| `MockBackend.kt` | `java.time.OffsetDateTime`, `ZoneId`, `DateTimeException`, `java.util.UUID`, `@Volatile` | `Instant`, `TimeZone`, `IllegalTimeZoneException` of kotlinx-datetime (thrown by `TimeZone.of`; catch it, not the wider `IllegalArgumentException` it extends), `kotlin.uuid.Uuid`, `kotlin.concurrent.Volatile` |
| `AssistantStore.kt` | `java.time.LocalDate`, `OffsetDateTime`, `@Synchronized`, `@Volatile` | kotlinx-datetime; `Mutex` or atomicfu locks |
| `api/MockAuthApi.kt` | `java.security.SecureRandom`, `java.util.Base64` (`getUrlEncoder().withoutPadding()`), `java.util.UUID`, `java.time.Duration`, `OffsetDateTime` | `kotlin.random.Random` (mock codes, not a secret), `Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)` of `kotlin.io.encoding`, `Uuid`, `kotlin.time.Duration` |
| `api/MockStatementsApi.kt` | `ConcurrentHashMap` (+ `newKeySet`), `OffsetDateTime`, `UUID`, `InputStream.readBytes`, `Charsets.UTF_8` | `Mutex`-guarded maps (or atomicfu), kotlinx-datetime, `Uuid`, `ByteArray.decodeToString()` |
| `api/MockAssistantApi.kt` | `java.time.LocalDate`, `UUID`, `synchronized(answer)` | kotlinx-datetime, `Uuid`, `Mutex` / atomicfu `synchronized` |
| `api/MockOperationsApi.kt` | `java.time.YearMonth`, `ChronoUnit` | `kotlinx.datetime.YearMonth` (0.7+), `LocalDate.daysUntil` / `monthsUntil` |
| `ofx/OfxParser.kt` | `java.math.BigDecimal`, `RoundingMode`, `LocalDateTime`, `ZoneOffset`, `DateTimeFormatter` | amounts parsed as text into `Long` minor units; `LocalDateTime.Format {}`, `UtcOffset` of kotlinx-datetime (a parse error is `DateTimeFormatException`) |
| `domain/Search.kt` | `String.toBigDecimalOrNull` (JVM stdlib) | parse the digits into minor units by hand |
| `domain/AnalyticsServerTexts.kt` | `java.time.YearMonth`, `"%.1f".format(...)` (JVM stdlib) | `kotlinx.datetime.YearMonth`, own decimal formatting |
| `domain/AnalyticsComposer.kt`, `AnalyticsModel.kt` | `java.time.YearMonth` | `kotlinx.datetime.YearMonth` |
| `domain/AnalyticsEngine.kt` | `LocalDate`, `OffsetDateTime`, `YearMonth`, `ChronoUnit`, `UUID`; `Math.floorDiv` (`java.lang.Math`, no import) | kotlinx-datetime, `Uuid`; `Long.floorDiv` of the stdlib |
| `domain/AssistantEngine.kt` | `LocalDate`, `YearMonth` | kotlinx-datetime |
| `domain/Coverage.kt` | `LocalDate`, `OffsetDateTime`, `YearMonth`, `ZoneId`, `ChronoUnit` | kotlinx-datetime |
| `domain/Ledger.kt` | `Duration`, `OffsetDateTime`, `UUID` | `kotlin.time.Duration`, kotlinx-datetime, `Uuid` |
| `domain/Scope.kt`, `OperationViews.kt`, `UnreadResolver.kt` | `LocalDate`, `OffsetDateTime`, `ZoneId` | kotlinx-datetime |
| `domain/DataChanges.kt`, `MockBackend.kt` | `@Volatile` (`kotlin.jvm`) | `kotlin.concurrent.Volatile` |

On Kotlin 2.1.20 (the stack today) `kotlin.uuid.Uuid` needs `@OptIn(ExperimentalUuidApi::class)` and
`kotlin.io.encoding.Base64` needs `@OptIn(ExperimentalEncodingApi::class)` (stable since Kotlin 2.2), so these
replacements carry an opt-in until the Kotlin update.

`OffsetDateTime` is the largest item: kotlinx-datetime has no type with an offset, so the API times become `Instant`
plus the profile `TimeZone` (`ReportZone`), and the wire format with an offset needs its own serializer.

### Prerequisite: `:core:common`

`Money`, periods and `Clock` (`ru.finassist.pf.core.common.time`) are built on `java.time` (`LocalDate`,
`YearMonth`, `OffsetDateTime`, `ZoneId`, `Duration`, `Instant`, `Month`, `DateTimeFormatter`) and are part of the
`:core:api` surface; they move to kotlinx-datetime together with it.
