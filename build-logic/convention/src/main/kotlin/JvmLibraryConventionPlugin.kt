import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.JavaVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import ru.finassist.pf.buildlogic.configureCoverage
import ru.finassist.pf.buildlogic.configureKotlin
import ru.finassist.pf.buildlogic.libs

/**
 * `pf.jvm.library`: pure Kotlin module (no Android SDK). Used for :core:common, :core:api, :core:toggles,
 * :core:tracking and feature :api modules — interfaces must not pull Android into their consumers.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        // Bytecode 17 like the Android modules, compiled by whatever JDK runs Gradle (no separate JDK 17 needed).
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureKotlin()
        configureCoverage()

        dependencies {
            add("implementation", libs.findLibrary("kotlinx.coroutines.core").get())
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("kotlin.test").get())
            add("testImplementation", libs.findLibrary("kotlinx.coroutines.test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
        }
    }
}
