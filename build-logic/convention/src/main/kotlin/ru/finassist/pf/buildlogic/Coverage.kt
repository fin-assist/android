package ru.finassist.pf.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Name of the Kover report variant shared by every module and the root aggregate (`:koverXmlReportUnit`,
 * `:koverHtmlReportUnit`, `:koverLogUnit` — root tasks). See docs/testing.md.
 */
const val COVERAGE_VARIANT = "unit"

/**
 * Task every module with this convention gets: the unit tests of the [COVERAGE_VARIANT] variant only. `./gradlew
 * unitTest` runs it in every module, so a new module is in without a list to keep up. CI runs it on PRs; pushes to
 * `main` and release branches run `test`, the unit tests of every variant (FIN-176, docs/testing.md).
 */
const val UNIT_TEST_TASK = "unitTest"

/** Plugins that decide which test task belongs to the [COVERAGE_VARIANT] variant of a module. */
internal val COVERAGE_TEST_PLUGINS = listOf("org.jetbrains.kotlin.jvm", "com.android.library", "com.android.application")

/**
 * Test task of the [COVERAGE_VARIANT] variant in a module with [pluginId] applied, or null for another plugin. Must
 * match the variants added to Kover in [configureCoverage]: `jvm`, `debug`, `mockDebug`.
 */
internal fun coverageTestTask(pluginId: String): String? = when (pluginId) {
    "org.jetbrains.kotlin.jvm" -> "test"
    "com.android.library" -> "testDebugUnitTest"
    "com.android.application" -> "testMockDebugUnitTest"
    else -> null
}

/**
 * Applies Kover and defines the `unit` variant: the JVM target of a pure Kotlin module, `debug` of an Android
 * library, `mockDebug` of the app. Release, `local`, `prod` and `e2e` variants stay out — they run the same unit
 * tests over the same code, and running them would only multiply the test time. Registers [UNIT_TEST_TASK], which
 * runs the tests of the same variant.
 */
internal fun Project.configureCoverage() {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant(COVERAGE_VARIANT) {
                add("jvm", "debug", "mockDebug", optional = true)
            }
        }
    }
    val unitTest = tasks.register(UNIT_TEST_TASK) {
        group = "verification"
        description = "Runs the unit tests of the '$COVERAGE_VARIANT' coverage variant: jvm, debug or mockDebug."
    }
    COVERAGE_TEST_PLUGINS.forEach { id ->
        pluginManager.withPlugin(id) {
            val testTask = checkNotNull(coverageTestTask(id)) { "no coverage test task for plugin $id" }
            unitTest.configure { dependsOn(testTask) }
        }
    }
}
