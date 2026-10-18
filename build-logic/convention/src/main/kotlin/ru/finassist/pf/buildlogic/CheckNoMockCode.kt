package ru.finassist.pf.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a project module under `:mock` (the fake backend) is on a runtime classpath of an HTTP flavor (`local`,
 * `prod`): mock code must not reach a build that talks to a real backend (FIN-120, AGENTS.md «Security»).
 */
abstract class CheckNoMockCode : DefaultTask() {
    /** Classpath name → root of its resolved graph. */
    @get:Input
    abstract val graphs: MapProperty<String, ResolvedComponentResult>

    @TaskAction
    fun check() {
        val found = graphs.get().flatMap { (name, root) -> mockModules(root).map { "$name: $it" } }
        if (found.isNotEmpty()) throw GradleException("Mock code on an HTTP flavor's classpath:\n" + found.joinToString("\n"))
        logger.lifecycle("No :mock modules in ${graphs.get().keys.joinToString()}")
    }

    private fun mockModules(root: ResolvedComponentResult): Set<String> {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(root))
        val found = sortedSetOf<String>()
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            val id = component.id
            if (id is ProjectComponentIdentifier && (id.projectPath == ":mock" || id.projectPath.startsWith(":mock:"))) {
                found += id.projectPath
            }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        return found
    }
}
