import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project
import ru.finassist.pf.buildlogic.libs

/**
 * `pf.feature.impl`: implementation of a feature (data, domain, ui) wired with Hilt. Always depends on its own
 * `:api` sibling and on the core modules; other features are reachable only through their `:api` (enforced by
 * `pf.module.rules`).
 */
class FeatureImplConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pf.android.library.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply("pf.hilt")

        val apiPath = path.substringBeforeLast(":impl") + ":api"
        dependencies {
            add("api", project(apiPath))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:api"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:navigation"))
            add("implementation", project(":core:toggles"))
            add("implementation", project(":core:tracking"))

            add("implementation", libs.findLibrary("androidx.core.ktx").get())
            add("implementation", libs.findLibrary("androidx.lifecycle.runtime.compose").get())
            add("implementation", libs.findLibrary("androidx.lifecycle.viewmodel.compose").get())
            add("implementation", libs.findLibrary("androidx.navigation.compose").get())
            add("implementation", libs.findLibrary("androidx.hilt.navigation.compose").get())
            add("implementation", libs.findLibrary("kotlinx.coroutines.android").get())
            add("implementation", libs.findLibrary("kotlinx.serialization.json").get())
        }
    }
}
