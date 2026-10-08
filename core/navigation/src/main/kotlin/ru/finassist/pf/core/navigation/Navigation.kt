package ru.finassist.pf.core.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder

/**
 * How features reach each other without knowing each other's implementation: a feature navigates with a
 * route object declared in some feature's `:api` module (`@Serializable` class), and the owning `:impl`
 * registers the screen for that route in its [FeatureEntry]. The app collects all entries (Hilt `@IntoSet`)
 * and installs them into the root NavHost.
 */
interface FeatureEntry {
    fun NavGraphBuilder.install(navigator: Navigator)
}

/** Navigation facade handed to screens. Routes are the `@Serializable` route classes from `:api` modules. */
interface Navigator {
    fun navigate(route: Any, builder: NavOptionsBuilder.() -> Unit = {})
    fun back(): Boolean
    /** Pops back to [route] (inclusive or not) — used after sign-in / sign-out to reset the stack. */
    fun popUpTo(route: Any, inclusive: Boolean)
    /** Returns a value to the previous screen (e.g. a chosen category) and pops. */
    fun returnResult(key: String, value: String)

    /**
     * Switches to a bottom tab root ([route] is a tab root) from the tab bar: the tab being left is kept and comes
     * back as it was on its next visit.
     */
    fun openTab(route: Any)

    /**
     * Ends the current flow (upload → result, …) and shows the root of the tab [route]. Unlike [openTab] the
     * screens of the flow are dropped, not kept for the next visit of their tab.
     */
    fun finishTo(route: Any)
}

class NavControllerNavigator(private val controller: NavHostController) : Navigator {
    override fun navigate(route: Any, builder: NavOptionsBuilder.() -> Unit) = controller.navigate(route, builder)

    override fun back(): Boolean = controller.popBackStack()

    override fun popUpTo(route: Any, inclusive: Boolean) {
        controller.popBackStack(route, inclusive)
    }

    override fun returnResult(key: String, value: String) {
        controller.previousBackStackEntry?.savedStateHandle?.set(key, value)
        controller.popBackStack()
    }

    override fun openTab(route: Any) {
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // No saveState / restoreState: with them a flow opened in the start tab (Operations) is saved on the way out
    // and restored at once, so «К операциям» on the upload result stayed on the result (FIN-41).
    override fun finishTo(route: Any) {
        controller.navigate(route) {
            popUpTo(controller.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }
}
