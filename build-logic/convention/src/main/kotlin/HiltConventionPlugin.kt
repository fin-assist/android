import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import ru.finassist.pf.buildlogic.libs

/**
 * `pf.hilt`: Hilt with KSP. On Android modules applies the Hilt Gradle plugin (needed for @AndroidEntryPoint);
 * on JVM modules only the compiler, so a module can declare @Module/@Binds without Android.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        val isAndroid = pluginManager.hasPlugin("com.android.application") || pluginManager.hasPlugin("com.android.library")
        dependencies {
            if (isAndroid) {
                add("implementation", libs.findLibrary("hilt.android").get())
            } else {
                add("implementation", libs.findLibrary("hilt.core").get())
            }
            add("ksp", libs.findLibrary("hilt.compiler").get())
        }
        if (isAndroid) {
            pluginManager.apply("com.google.dagger.hilt.android")
        }
    }
}
