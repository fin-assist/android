package ru.finassist.pf.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Name of the Kover report variant shared by every module and the root aggregate (`koverXmlReportUnit`,
 * `koverHtmlReportUnit`, `koverLogUnit`). See docs/testing.md.
 */
const val COVERAGE_VARIANT = "unit"

/**
 * Applies Kover and defines the `unit` variant: the JVM target of a pure Kotlin module, `debug` of an Android
 * library, `mockDebug` of the app. Release, `prod` and `e2e` variants stay out — they run the same unit tests
 * over the same code, and running them would only multiply the test time.
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
}
