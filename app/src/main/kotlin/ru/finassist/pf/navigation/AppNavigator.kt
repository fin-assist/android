package ru.finassist.pf.navigation

import androidx.navigation.NavHostController
import ru.finassist.pf.core.navigation.Navigator

/** [Navigator] over the root NavHostController. */
class AppNavigator(private val controller: NavHostController) : Navigator {
    override fun navigate(route: Any) = controller.navigate(route)

    override fun navigate(route: Any, popUpTo: Any, inclusive: Boolean) =
        controller.navigate(route) { popUpTo(popUpTo) { this.inclusive = inclusive }; launchSingleTop = true }

    override fun back() { controller.popBackStack() }

    override fun resetTo(route: Any) = controller.navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
}
