import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * `pf.module.rules`: dependency rules between modules, checked at configuration time (see docs/modules.md).
 *
 * - `:feature:*:impl` may depend on any `:feature:*:api`, `:core:*`, but never on another `:impl`, `:providers:*`,
 *   `:mock:*` or `:app`.
 * - `:feature:*:api` may depend only on `:core:common`, `:core:api` and other `:feature:*:api`.
 * - `:core:*` may not depend on `:feature:*`, `:providers:*`, `:mock:*`, `:app`.
 * - `:providers:*` and `:mock:*` may depend only on `:core:*`.
 * - `:app` may depend on anything — it is the composition root.
 */
class ModuleRulesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "pf.module.rules must be applied to the root project" }
        target.subprojects.forEach { sub ->
            sub.afterEvaluate { checkRules(sub) }
        }
    }

    private fun checkRules(project: Project) {
        val path = project.path
        val deps = project.configurations
            .filter { it.name in CHECKED_CONFIGURATIONS }
            .flatMap { it.dependencies }
            .filterIsInstance<ProjectDependency>()
            .map { it.path }
            .filter { it != path }

        val violations = deps.filter { dep -> !isAllowed(path, dep) }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module rule violation in $path: forbidden dependencies ${violations.joinToString()}. " +
                    "See docs/modules.md.",
            )
        }
    }

    private fun isAllowed(from: String, to: String): Boolean = when {
        from == ":app" -> true
        from.isFeatureImpl() -> to.isFeatureApi() || to.isCore()
        from.isFeatureApi() -> to == ":core:common" || to == ":core:api" || to.isFeatureApi()
        from.isCore() -> to.isCore()
        from.startsWith(":providers:") || from.startsWith(":mock:") -> to.isCore()
        else -> true
    }

    private fun String.isFeatureImpl() = startsWith(":feature:") && endsWith(":impl")
    private fun String.isFeatureApi() = startsWith(":feature:") && endsWith(":api")
    private fun String.isCore() = startsWith(":core:")

    private companion object {
        val CHECKED_CONFIGURATIONS = setOf(
            "api", "implementation", "compileOnly", "runtimeOnly",
            "debugImplementation", "releaseImplementation",
            "mockImplementation", "prodImplementation",
        )
    }
}
