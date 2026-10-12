// Root build: plugins are declared here (apply false) so every module resolves the same versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Unit test coverage of all modules in one report: `./gradlew :koverHtmlReportUnit` (docs/testing.md).
    alias(libs.plugins.kover)
    // Enforces the module dependency rules (see docs/modules.md). Fails the build on a violation.
    id("pf.module.rules")
    // UI test coverage report from the JaCoCo execution data of the mock e2e build: `:e2eCoverageReport`.
    jacoco
}

dependencies {
    // Every module with a build file (path containers like `:feature` have none); the design-check harness is
    // test code, not product code.
    subprojects
        .filter { it.buildFile.exists() && it.path != ":core:screenshot-testing" }
        .forEach { kover(it) }
}

kover {
    currentProject {
        // Same name as the per-module variant (build-logic Coverage.kt): the aggregate merges them by name.
        createVariant("unit") {}
    }
    reports {
        filters {
            excludes {
                // Generated code: Hilt/Dagger, kotlinx.serialization, Android resources and BuildConfig.
                // `*Module_*Factory`: factories of @Provides methods (`NetworkModule_ClientFactory`); the
                // `@DaggerGenerated` annotation below catches the same classes, the pattern is a fallback.
                // Hand-written @Module classes stay in: @Provides methods may hold real logic (client setup).
                // Not excluded: `Companion.serializer()` of @Serializable classes (one line each, ~2 % of the
                // total) — Kover cannot tell those companions from hand-written ones.
                classes(
                    "*.BuildConfig", "*.R", "*.R$*",
                    "Hilt_*", "*.Hilt_*", "HiltWrapper_*", "*.HiltWrapper_*", "*_HiltModules*",
                    "*_Factory", "*_Factory$*", "*Module_*Factory", "*_MembersInjector",
                    "*_GeneratedInjector", "*_ComponentTreeDeps", "*_HiltComponents*",
                    "dagger.hilt.internal.*", "hilt_aggregated_deps.*",
                    "*\$\$serializer",
                    "*.ComposableSingletons*",
                )
                // Composables are checked by design-check snapshots and Maestro flows, not by unit tests: their
                // lines would only dilute the number that tells whether the logic is tested.
                annotatedBy(
                    "androidx.compose.runtime.Composable",
                    "androidx.compose.ui.tooling.preview.Preview",
                    "dagger.internal.DaggerGenerated",
                )
            }
        }
    }
}

// ---- UI test coverage (FIN-48, docs/testing.md) ----
//
// The mock e2e build carries JaCoCo probes (app/build.gradle.kts, `enableAndroidTestCoverage`); the flows and the
// instrumented tests leave execution data in build/e2e/coverage/ec (scripts/e2e.sh) and
// app/build/outputs/code_coverage/ (AGP). This task reads them against the classes the probes were put into
// — the compiled output of every module in the mock APK — and writes build/reports/e2e-coverage/{html,report.xml}.

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

/** Modules of the mock e2e APK: the app, the shared modules, the mock backend and the mock providers. Prod-only modules are not in it. */
val e2eCoverageModules: List<Project> = subprojects.filter {
    it.buildFile.exists() &&
        it.path !in setOf(
            ":core:screenshot-testing", ":core:network",
            ":providers:toggles-rustore", ":providers:tracking-mytracker", ":providers:crash-appmetrica",
        )
}

/**
 * Compiled classes as the JaCoCo probes saw them. Android modules: the ASM-transformed output when a transform
 * runs (Hilt registers one in every module it is applied to), otherwise the Kotlin and Java compiler output;
 * `:app`: the `mockE2e` variant; JVM modules: `main`. Called from a provider, after the modules are configured
 * (the plugin check needs that) and the e2e build has run (the existence check needs that).
 */
fun e2eClassDirectories(module: Project): List<File> {
    val build = module.layout.buildDirectory
    fun dir(path: String): File = build.dir(path).get().asFile
    return when {
        module.path == ":app" -> listOf(dir("intermediates/classes/mockE2e/transformMockE2eClassesWithAsm/dirs"))
        module.plugins.hasPlugin("com.android.library") -> {
            val transformed = dir("intermediates/classes/debug/transformDebugClassesWithAsm/dirs")
            if (transformed.isDirectory) listOf(transformed) else listOf(dir("tmp/kotlin-classes/debug"), dir("intermediates/javac/debug/classes"))
        }
        else -> listOf(dir("classes/kotlin/main"))
    }
}

/** Source sets the mock e2e build compiles: `main`, plus `mock` and `mockE2e` of the app. */
fun e2eSourceDirectories(module: Project): List<File> =
    listOf("main", "mock", "mockE2e").flatMap { set -> listOf(module.file("src/$set/kotlin"), module.file("src/$set/java")) }.filter { it.isDirectory }

tasks.register<JacocoReport>("e2eCoverageReport") {
    group = "verification"
    description = "Coverage of the mock e2e build by the UI tests (Maestro flows and instrumented tests)."
    dependsOn(":app:assembleMockE2e")
    executionData.from(
        fileTree(layout.projectDirectory.dir("build/e2e/coverage/ec")) { include("*.ec") },
        fileTree(layout.projectDirectory.dir("app/build/outputs/code_coverage")) { include("**/*.ec") },
    )
    classDirectories.from(
        provider {
            e2eCoverageModules.flatMap(::e2eClassDirectories).map { directory ->
                fileTree(directory) {
                    // Generated code, as in the Kover report (above), plus the test hooks of the e2e build.
                    exclude(
                        "**/BuildConfig.class", "**/R.class", "**/R$*.class",
                        "**/Hilt_*.class", "**/HiltWrapper_*.class", "**/*_HiltModules*.class", "**/*_HiltComponents*.class",
                        "**/*_Factory.class", "**/*_Factory$*.class", "**/*Module_*Factory*.class", "**/*_MembersInjector.class",
                        "**/*_GeneratedInjector.class", "**/*_ComponentTreeDeps*.class", "**/Dagger*.class",
                        "dagger/**", "hilt_aggregated_deps/**", "**/*$\$serializer.class", "**/*$\$serializer$*.class",
                        "**/ComposableSingletons*.class",
                        "ru/finassist/pf/e2e/**",
                        // AGP 8.9 copies instead of instrumenting every class whose simple name starts with `R` or
                        // `Manifest` (its filter for the generated `R`/`Manifest` classes is `.+?/R\$?.*?\.class$`):
                        // `ResultViewModel`, `RussianDates`, `RowModel`… carry no probes, so they would show as never
                        // executed. Left out of the report; docs/testing.md lists this gap.
                        "**/R*.class", "**/Manifest*.class",
                    )
                }
            }
        },
    )
    sourceDirectories.from(e2eCoverageModules.flatMap(::e2eSourceDirectories))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.projectDirectory.file("build/reports/e2e-coverage/report.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.projectDirectory.dir("build/reports/e2e-coverage/html"))
        csv.required.set(false)
    }
    // No data (the run stopped before the flows) — no report: an empty report.xml would read as 0 % in the summary,
    // which prints «No code coverage» for a missing file instead.
    onlyIf("execution data exists in build/e2e/coverage/ec or app/build/outputs/code_coverage") {
        executionData.files.any { it.isFile }.also { if (!it) logger.warn("e2eCoverageReport: no execution data — no report") }
    }
}
