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
    // Unit test coverage of all modules in one report: `./gradlew koverHtmlReportUnit` (docs/testing.md).
    alias(libs.plugins.kover)
    // Enforces the module dependency rules (see docs/modules.md). Fails the build on a violation.
    id("pf.module.rules")
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
                classes(
                    "*.BuildConfig", "*.R", "*.R$*",
                    "Hilt_*", "*.Hilt_*", "*_HiltModules*", "*_Factory", "*_Factory$*", "*_MembersInjector",
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
                    "dagger.Module",
                )
            }
        }
    }
}
