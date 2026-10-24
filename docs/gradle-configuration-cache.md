# Configuration cache (FIN-187)

`gradle.properties` disables configuration caching for local and CI builds. Gradle configures the project on
every invocation, avoiding the configuration-cache serialization path that intermittently fails on
`CheckAarMetadataTask.aarMetadataArtifacts`. Gradle task-output caching (`org.gradle.caching=true`), incremental
builds and the setup-gradle dependency/cache restore policy remain enabled. The cost is repeated configuration
work, including between CI test and coverage commands.

## Evidence and scope

The reported CI failures on Gradle 8.11.1 / AGP 8.9.1 have this signature:

```text
Configuration cache state could not be cached: field `aarMetadataArtifacts`
... CheckAarMetadataTask ... DefaultArtifactCollection
Cannot invoke "java.util.List.get(int)" because "path" is null
```

[Gradle issue 36284](https://github.com/gradle/gradle/issues/36284) documents the same NPE when reading
failure messages from a lenient artifact view. [Its merged fix](https://github.com/gradle/gradle/pull/37824)
repairs failure-path calculation through cyclic dependency graphs; its milestone is **9.6.0 RC1**.
[Issue 30552](https://github.com/gradle/gradle/issues/30552) reports the same exception with another resolution
failure trigger. These are evidence of a Gradle diagnostic bug, not proof that the intermittent Android CI
failure was caused by a race or by the same dependency graph. Without the complete failing resolution
and a stable reproducer, that underlying trigger is unconfirmed.

Disabling configuration caching prevents the reported serialization failure; it does not fix dependency
resolution itself. Genuine missing/incompatible dependencies must still fail the build. Do not use
`org.gradle.configuration-cache.problems=warn`: warning mode still attempts serialization and does not
guarantee a successful build ([Gradle documentation](https://docs.gradle.org/8.11.1/userguide/configuration_cache.html)).
Do not retry all failed builds as part of this workaround.

A move from Gradle 8.11.1 to 9.6 requires a separately validated compatible Android toolchain; it is not a
patch-level fix for the pinned AGP 8.9.1 stack. No dependency/toolchain versions change in FIN-187.

## Verification and return to caching

Ordinary PR CI checks test/coverage, lint and mock/local/prod assembly with the repository default. The e2e
assembly uses the same default; executing UI tests still needs the owner's authorization.

Before changing the default back to `true`, validate the chosen toolchain against the upstream fix and run
`lintMockDebug`, mock/local/prod assembly, and `:app:assembleMockE2e :app:assembleMockE2eAndroidTest` twice with
`--configuration-cache` in a disposable checkout. The first invocation must store an entry and the second
must reuse it. Include unit tests and coverage-report tasks, and a deliberately unavailable dependency to
confirm that resolution failures remain readable and fail the build. Repeated green builds alone do not
prove an intermittent bug fixed: retain the upstream evidence and the failed resolution reproducer when
available. UI-test execution is a separate authorized check.
