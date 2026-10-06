import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import ru.finassist.pf.buildlogic.configureKotlinAndroid
import ru.finassist.pf.buildlogic.defaultNamespace

/** `pf.android.library`: Android library without Compose. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            namespace = defaultNamespace
            configureKotlinAndroid(this)
            // Resource prefix keeps resource names unique across modules (R classes are non-transitive).
            resourcePrefix = path.split(":").filter { it.isNotEmpty() }.joinToString("_").replace('-', '_') + "_"
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
}
