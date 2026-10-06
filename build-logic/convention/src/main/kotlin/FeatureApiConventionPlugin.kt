import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project
import ru.finassist.pf.buildlogic.libs

/**
 * `pf.feature.api`: the public surface of a feature — interfaces, models and navigation routes. Pure JVM,
 * so nothing here can accidentally expose UI or Android details. Other features depend only on these modules.
 */
class FeatureApiConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pf.jvm.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        dependencies {
            add("api", project(":core:common"))
            add("api", libs.findLibrary("kotlinx.serialization.json").get())
            add("implementation", libs.findLibrary("javax.inject").get())
        }
    }
}
