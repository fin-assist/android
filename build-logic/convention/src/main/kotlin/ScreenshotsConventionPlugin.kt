import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * `pf.screenshots`: design-check snapshots of a feature's screens (`*DesignCheckTest`, Robolectric + Roborazzi).
 *
 * The snapshots are skipped by the regular unit test run (they download a full Android runtime and render
 * every screen); `-Ppf.designcheck` runs them and writes PNGs to `build/design-check/app/` at the repo root.
 *
 * `-Ppf.designcheck.changed=<file>` limits the run to the screens a change can affect: the file lists changed
 * paths relative to the repo root, one per line (`git diff --name-only`). A module's snapshots run when a
 * changed path lies in the module itself or in any module it depends on, directly or transitively (declared
 * project dependencies of every configuration, tests included); a change to the build itself or to
 * `design-check/` runs all of them. Paths outside modules (docs, scripts) affect nothing.
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
        val changedFile = providers.gradleProperty("pf.designcheck.changed").orNull
        if (enabled && changedFile != null) {
            // Read through providers so the configuration cache is invalidated when the list changes.
            val changed = providers.fileContents(rootProject.layout.projectDirectory.file(changedFile)).asText.get()
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
            gradle.projectsEvaluated {
                val affected = isAffected(this@with, changed)
                logger.lifecycle("design check: $path ${if (affected) "affected" else "not affected, skipped"}")
                tasks.withType<Test>().configureEach { onlyIf("affected by the change") { affected } }
            }
        }
    }

    private fun isAffected(project: Project, changed: List<String>): Boolean {
        val root = project.rootProject
        if (changed.any { path -> GLOBAL.any { path == it || path.startsWith("$it/") } }) return true
        val modules = root.subprojects.associateBy { it.projectDir.relativeTo(root.projectDir).invariantSeparatorsPath }
        val changedModules = changed.mapNotNull { path ->
            modules.keys.filter { path.startsWith("$it/") }.maxByOrNull { it.length }?.let { modules.getValue(it).path }
        }.toSet()
        return dependencyClosure(project).any { it in changedModules }
    }

    /** The project and every project it declares a dependency on, transitively. */
    private fun dependencyClosure(project: Project): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(project.path))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!seen.add(path)) continue
            project.rootProject.project(path).configurations
                .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                .mapTo(queue) { it.path }
        }
        return seen
    }

    private companion object {
        /** Paths that change how every screen is built or compared. */
        val GLOBAL = listOf(
            "build-logic", "gradle", "design-check",
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        )
    }
}
