package ru.finassist.pf.core.navigation

import androidx.navigation.NavGraphBuilder

/**
 * A feature's contribution to the root NavHost. Implemented in `:feature:<name>:impl`,
 * collected by `:app` through a Hilt multibinding (`@IntoSet`). Routes live in `:feature:<name>:api`
 * so other features can navigate to them without knowing the implementation.
 */
fun interface FeatureEntry {
    fun NavGraphBuilder.install(navigator: Navigator)
}
