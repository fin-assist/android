import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * `pf.screenshots`: design-check snapshots of a feature's screens (`*DesignCheckTest`, Robolectric + Roborazzi).
 *
 * The snapshots are skipped by the regular unit test run (they download a full Android runtime and render
 * every screen); `-Ppf.designcheck` runs them and writes PNGs to `build/design-check/app/` at the repo root.
 */
class ScreenshotsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        extensions.configure<LibraryExtension> {
            testOptions.unitTests.isIncludeAndroidResources = true
        }
        dependencies {
            add("testImplementation", project(":core:screenshot-testing"))
        }
        val enabled = providers.gradleProperty("pf.designcheck").isPresent
        val outDir = rootDir.resolve("build/design-check/app").path
        // Robolectric downloads its Android runtime from Maven Central itself; agent containers point it at a mirror.
        val robolectricRepo = providers.gradleProperty("pf.robolectric.repo").orNull
        tasks.withType<Test>().configureEach {
            if (!enabled) {
                exclude("**/*DesignCheckTest*")
            } else {
                filter.includeTestsMatching("*DesignCheckTest")
                systemProperty("roborazzi.test.record", "true")
                systemProperty("robolectric.graphicsMode", "NATIVE")
                systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                systemProperty("pf.designcheck.dir", outDir)
                if (robolectricRepo != null) systemProperty("robolectric.dependency.repo.url", robolectricRepo)
                maxHeapSize = "3g"
            }
        }
    }
}
