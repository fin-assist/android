package pf

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `:feature:<name>:impl` — data / domain / ui of the feature. Depends on its own api and on core modules.
 * May depend on OTHER features only through their `:api` modules (enforced by ModuleRulesPlugin).
 */
class FeatureImplPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pf.android.library")
        pluginManager.apply("pf.android.compose")
        pluginManager.apply("pf.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        val ownApi = path.substringBeforeLast(':') + ":api"
        dependencies {
            add("implementation", project(ownApi))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:navigation"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:network"))
            add("implementation", project(":core:toggles"))
            add("implementation", project(":core:tracking"))
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
        }
    }
}
