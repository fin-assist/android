import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import ru.finassist.pf.buildlogic.configureCompose

/** `pf.android.library.compose`: Android library with Jetpack Compose (UI modules). */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pf.android.library")
        extensions.configure<LibraryExtension> {
            configureCompose(this)
        }
    }
}
