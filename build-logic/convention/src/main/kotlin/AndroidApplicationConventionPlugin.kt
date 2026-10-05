import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import ru.finassist.pf.buildlogic.configureCompose
import ru.finassist.pf.buildlogic.configureKotlinAndroid
import ru.finassist.pf.buildlogic.libs
import ru.finassist.pf.buildlogic.version

/** `pf.android.application`: the single app module. Flavors, signing and SDK wiring live in app/build.gradle.kts. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            configureCompose(this)
            defaultConfig.targetSdk = libs.version("targetSdk").toInt()
        }
    }
}
