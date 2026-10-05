package ru.finassist.pf.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The `libs` version catalog, for use inside convention plugins. */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/**
 * Android namespace derived from the project path: `:feature:analytics:impl` → `ru.finassist.pf.feature.analytics.impl`.
 * Modules may override it in their own `android { namespace = ... }` block.
 */
val Project.defaultNamespace: String
    get() = "ru.finassist.pf" + path.replace(':', '.').replace('-', '_')
