package ru.finassist.pf.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.kotlin.dsl.configure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * The graph of [UNIT_TEST_TASK] in real modules: AGP, the Kotlin JVM plugin and Kover applied to ProjectBuilder
 * projects, then [configureCoverage] — as the convention plugins do. The Android cases need the SDK: `ANDROID_HOME`
 * or `ANDROID_SDK_ROOT` (the CI runner), else `sdk.dir` of the repo's `local.properties` (Android Studio,
 * scripts/agent-setup.sh).
 */
class CoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `unitTest of a JVM module runs its tests`() {
        val module = project("jvm")
        module.pluginManager.apply("org.jetbrains.kotlin.jvm")
        module.configureCoverage()
        module.evaluate()

        assertEquals(setOf("test"), unitTestDependencies(module))
    }

    @Test
    fun `unitTest of an Android library runs the debug tests only`() {
        val module = project("library")
        module.pluginManager.apply("com.android.library")
        module.extensions.configure<LibraryExtension> {
            namespace = "ru.finassist.pf.test.library"
            compileSdk = 35
        }
        module.configureCoverage()
        module.evaluate()

        assertTrue("testReleaseUnitTest" in module.tasks.names)
        assertEquals(setOf("testDebugUnitTest"), unitTestDependencies(module))
    }

    @Test
    fun `unitTest of the app runs mockDebug only, not the release, local, prod or e2e variants`() {
        val module = project("app")
        module.pluginManager.apply("com.android.application")
        module.extensions.configure<ApplicationExtension> {
            namespace = "ru.finassist.pf.test.app"
            compileSdk = 35
            defaultConfig { minSdk = 26 }
            flavorDimensions += "env"
            productFlavors {
                listOf("mock", "local", "prod").forEach { create(it) { dimension = "env" } }
            }
            buildTypes { create("e2e") { initWith(getByName("debug")) } }
            testBuildType = "e2e"
        }
        module.configureCoverage()
        module.evaluate()

        val other = listOf("testMockReleaseUnitTest", "testMockE2eUnitTest", "testLocalDebugUnitTest", "testProdReleaseUnitTest")
        assertTrue(module.tasks.names.containsAll(other))
        assertEquals(setOf("testMockDebugUnitTest"), unitTestDependencies(module))
    }

    @Test
    fun `the plugin may come after the convention`() {
        val module = project("late")
        module.configureCoverage()
        module.pluginManager.apply("org.jetbrains.kotlin.jvm")
        module.evaluate()

        assertEquals(setOf("test"), unitTestDependencies(module))
    }

    @Test
    fun `every module with the convention gets unitTest, so a new one needs no list`() {
        val root = ProjectBuilder.builder().withProjectDir(tmp.newFolder("multi")).build()
        val modules = listOf("a", "b").map { name ->
            ProjectBuilder.builder().withName(name).withParent(root).build().also {
                it.pluginManager.apply("org.jetbrains.kotlin.jvm")
                it.configureCoverage()
            }
        }
        modules.forEach { it.evaluate() }

        modules.forEach { assertEquals(setOf("test"), unitTestDependencies(it)) }
        assertEquals(2, root.getTasksByName(UNIT_TEST_TASK, true).size)
    }

    @Test
    fun `Kover variants and unitTest come from one list of plugin, variant and test task`() {
        assertEquals(
            listOf(
                CoverageTarget("org.jetbrains.kotlin.jvm", "jvm", "test"),
                CoverageTarget("com.android.library", "debug", "testDebugUnitTest"),
                CoverageTarget("com.android.application", "mockDebug", "testMockDebugUnitTest"),
            ),
            COVERAGE_TARGETS,
        )
    }

    @Test
    fun `a module with the convention and a known plugin passes the check`() {
        val module = project("ok", buildFile = true)
        module.pluginManager.apply("org.jetbrains.kotlin.jvm")
        module.configureCoverage()

        assertNull(unitTestProblem(module))
    }

    @Test
    fun `a module without the convention fails the check`() {
        val module = project("bare", buildFile = true)
        module.pluginManager.apply("org.jetbrains.kotlin.jvm")

        val problem = unitTestProblem(module)
        assertNotNull(problem)
        assertTrue(problem!!, "has no 'unitTest' task" in problem)
    }

    @Test
    fun `a module whose plugin is not in the list fails the check`() {
        val module = project("unknown", buildFile = true)
        module.pluginManager.apply("java-library")
        module.configureCoverage()

        val problem = unitTestProblem(module)
        assertNotNull(problem)
        assertTrue(problem!!, "runs no tests" in problem)
    }

    @Test
    fun `a path container without a build file is not checked`() {
        assertNull(unitTestProblem(project("container")))
    }

    private fun project(name: String, buildFile: Boolean = false): Project {
        val dir = tmp.newFolder(name)
        sdkDir()?.let { File(dir, "local.properties").writeText("sdk.dir=${it.replace("\\", "\\\\")}\n") }
        if (buildFile) File(dir, "build.gradle.kts").writeText("")
        return ProjectBuilder.builder().withName(name).withProjectDir(dir).build()
    }

    /**
     * The SDK as the build finds it: `ANDROID_HOME` / `ANDROID_SDK_ROOT`, else `sdk.dir` of the nearest
     * `local.properties` above the working directory (Android Studio writes only that one).
     */
    private fun sdkDir(): String? =
        System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "local.properties") }
            .firstOrNull { it.isFile }
            ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir") }

    private fun Project.evaluate() = (this as ProjectInternal).evaluate()

    /** Names of the tasks [UNIT_TEST_TASK] depends on, resolved as Gradle resolves them for the task graph. */
    private fun unitTestDependencies(module: Project): Set<String> {
        val task = module.tasks.getByName(UNIT_TEST_TASK)
        return task.taskDependencies.getDependencies(task).map { it.name }.toSet()
    }
}
