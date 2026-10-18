package ru.finassist.pf.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MockModulesTest {

    @Test
    fun `a mock module two levels down is found by its path`() {
        val backend = component(project(":mock:backend"))
        val network = component(project(":core:network"), backend)
        val root = component(project(":"), network, component(external("com.squareup.okhttp3:okhttp")))
        assertEquals(setOf(":mock:backend"), mockModules(root))
    }

    @Test
    fun `a graph without mock modules is clean`() {
        val root = component(
            project(":"),
            component(project(":core:network"), component(external("com.squareup.retrofit2:retrofit"))),
            component(project(":providers:toggles-rustore")),
        )
        assertEquals(emptySet<String>(), mockModules(root))
    }

    @Test
    fun `a module named like mock elsewhere is not mock code`() {
        val root = component(project(":"), component(project(":core:mockery", name = "mock")), component(external("x:mock")))
        assertEquals(emptySet<String>(), mockModules(root))
    }

    @Test
    fun `the mock root project itself counts`() {
        assertEquals(setOf(":mock"), mockModules(component(project(":"), component(project(":mock")))))
    }

    @Test
    fun `a cycle ends`() {
        val a = MutableComponent(project(":core:a"))
        val b = MutableComponent(project(":mock:backend"))
        a.children.add(b.proxy)
        b.children.add(a.proxy)
        val root = component(project(":"), a.proxy)
        assertEquals(setOf(":mock:backend"), mockModules(root))
    }

    @Test
    fun `an unresolved dependency fails the check`() {
        val root = component(project(":"), component(project(":core:network")), unresolved = listOf(":mock:backend"))
        val error = assertThrows(GradleException::class.java) { mockModules(root) }
        assertTrue(error.message!!, error.message!!.contains(":mock:backend"))
    }

    // Gradle's result types are interfaces; the walk reads only ids and dependencies.

    private class MutableComponent(val id: ComponentIdentifier) {
        val children = mutableListOf<ResolvedComponentResult>()
        val unresolved = mutableListOf<String>()
        val proxy: ResolvedComponentResult = proxy { method ->
            when (method) {
                "getId" -> id
                "getDependencies" -> children.map(::resolved).toSet() + unresolved.map(::unresolvedDependency)
                "hashCode" -> System.identityHashCode(this)
                "toString" -> id.displayName
                else -> null
            }
        }
    }

    private fun component(
        id: ComponentIdentifier,
        vararg children: ResolvedComponentResult,
        unresolved: List<String> = emptyList(),
    ): ResolvedComponentResult = MutableComponent(id).also {
        it.children += children
        it.unresolved += unresolved
    }.proxy

    private fun project(path: String, name: String = path.substringAfterLast(':')): ProjectComponentIdentifier =
        proxy { method ->
            when (method) {
                "getProjectPath" -> path
                "getProjectName" -> name
                "getDisplayName", "toString" -> "project $path"
                "hashCode" -> path.hashCode()
                else -> null
            }
        }

    private fun external(coordinates: String): ModuleComponentIdentifier = proxy { method ->
        when (method) {
            "getGroup" -> coordinates.substringBefore(':')
            "getModule" -> coordinates.substringAfter(':')
            "getDisplayName", "toString" -> coordinates
            "hashCode" -> coordinates.hashCode()
            else -> null
        }
    }

    private companion object {
        fun resolved(selected: ResolvedComponentResult): DependencyResult = proxy<ResolvedDependencyResult> { method ->
            when (method) {
                "getSelected" -> selected
                "hashCode" -> System.identityHashCode(selected)
                else -> null
            }
        }

        fun unresolvedDependency(path: String): DependencyResult = proxy<UnresolvedDependencyResult> { method ->
            when (method) {
                "getAttempted" -> proxy<ComponentSelector> { if (it == "getDisplayName") "project $path" else null }
                "getFailure" -> RuntimeException("Could not resolve project $path")
                "hashCode" -> path.hashCode()
                else -> null
            }
        }

        inline fun <reified T : Any> proxy(crossinline answer: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
                if (method.name == "equals") self === args?.get(0) else answer(method.name)
            } as T
    }
}
