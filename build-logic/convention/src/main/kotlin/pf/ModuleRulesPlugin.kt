package pf

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Enforces the module graph:
 *  - `:feature:X:impl` must not depend on `:feature:Y:impl` (any Y, including X itself is impossible anyway);
 *  - `:feature:X:api` must not depend on any `impl` module or on core provider modules;
 *  - `:core:*` must not depend on `:feature:*`;
 *  - only `:app` may depend on `*:impl` and on provider modules (`toggles-*`, `tracking-*`, `mock-backend`).
 * Runs at configuration time of every project, so a violation fails `./gradlew help` already.
 */
class ModuleRulesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.allprojects { afterEvaluate { checkRules(this) } }
    }

    private fun checkRules(p: Project) {
        val path = p.path
        val isApp = path == ":app"
        val isFeatureApi = path.startsWith(":feature:") && path.endsWith(":api")
        val isCore = path.startsWith(":core:")
        val violations = mutableListOf<String>()
        p.configurations
            .filter { it.name in CHECKED_CONFIGURATIONS }
            .forEach { cfg ->
                cfg.dependencies.filterIsInstance<ProjectDependency>().forEach { dep ->
                    val d = dep.path
                    val depIsImpl = d.endsWith(":impl") && d.startsWith(":feature:")
                    val depIsProvider = PROVIDER_MODULES.any { d == it }
                    when {
                        depIsImpl && !isApp -> violations += "$path -> $d: only :app may depend on an impl module"
                        depIsProvider && !isApp -> violations += "$path -> $d: only :app may depend on a provider module"
                        isFeatureApi && !(d.startsWith(":core:") || d.endsWith(":api")) ->
                            violations += "$path -> $d: api modules may depend only on core and other api modules"
                        isCore && d.startsWith(":feature:") -> violations += "$path -> $d: core must not depend on features"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException("Module rules violated:\n" + violations.joinToString("\n") { "  $it" })
        }
    }

    private companion object {
        val CHECKED_CONFIGURATIONS = setOf("api", "implementation", "compileOnly", "runtimeOnly", "ksp")
        val PROVIDER_MODULES = setOf(
            ":core:toggles-local", ":core:toggles-rustore",
            ":core:tracking-log", ":core:tracking-mytracker",
            ":core:mock-backend",
        )
    }
}
