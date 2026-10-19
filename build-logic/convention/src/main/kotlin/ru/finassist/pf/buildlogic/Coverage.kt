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

/**
 * What a module kind contributes to the [COVERAGE_VARIANT] variant: with [pluginId] applied, the Kover variant
 * [koverVariant] goes into the report and [testTask] into [UNIT_TEST_TASK].
 */
internal data class CoverageTarget(val pluginId: String, val koverVariant: String, val testTask: String)

/**
 * The one list both Kover and [UNIT_TEST_TASK] are built from: the JVM target of a pure Kotlin module, `debug` of an
 * Android library, `mockDebug` of the app. Release, `local`, `prod` and `e2e` variants stay out — they run the same
 * unit tests over the same code, and running them would only multiply the test time.
 */
internal val COVERAGE_TARGETS = listOf(
    CoverageTarget("org.jetbrains.kotlin.jvm", koverVariant = "jvm", testTask = "test"),
    CoverageTarget("com.android.library", koverVariant = "debug", testTask = "testDebugUnitTest"),
    CoverageTarget("com.android.application", koverVariant = "mockDebug", testTask = "testMockDebugUnitTest"),
)

/** Applies Kover with the [COVERAGE_VARIANT] variant and registers [UNIT_TEST_TASK], both from [COVERAGE_TARGETS]. */
internal fun Project.configureCoverage() {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant(COVERAGE_VARIANT) {
                add(*COVERAGE_TARGETS.map { it.koverVariant }.toTypedArray(), optional = true)
            }
        }
    }
    val unitTest = tasks.register(UNIT_TEST_TASK) {
        group = "verification"
        description = "Runs the unit tests of the '$COVERAGE_VARIANT' coverage variant: jvm, debug or mockDebug."
    }
    COVERAGE_TARGETS.forEach { target ->
        pluginManager.withPlugin(target.pluginId) {
            unitTest.configure { dependsOn(target.testTask) }
        }
    }
}

/**
 * Why [module] would drop out of the PR test run, or null when it is in: a module with a build file must have a
 * [UNIT_TEST_TASK] that runs some tests — no convention plugin applied, or a plugin outside [COVERAGE_TARGETS] (Kotlin
 * Multiplatform, FIN-144), leaves it without one. Checked at configuration time by `pf.module.rules`.
 */
internal fun unitTestProblem(module: Project): String? {
    if (!module.buildFile.exists()) return null
    val task = module.tasks.findByName(UNIT_TEST_TASK)
        ?: return "${module.path} has no '$UNIT_TEST_TASK' task: apply a pf.* convention plugin, which calls " +
            "configureCoverage() (build-logic Coverage.kt)."
    if (task.dependsOn.isEmpty()) {
        return "'$UNIT_TEST_TASK' of ${module.path} runs no tests: none of ${COVERAGE_TARGETS.map { it.pluginId }} is " +
            "applied. Add the module's plugin to COVERAGE_TARGETS (build-logic Coverage.kt)."
    }
    return null
}
