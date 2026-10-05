package pf

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `:feature:<name>:api` — interfaces, routes (@Serializable), value models. No UI, no DI, no network.
 * Other features depend on this module only.
 */
class FeatureApiPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pf.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("api", project(":core:common"))
            add("api", project(":core:navigation"))
            add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
        }
    }
}
