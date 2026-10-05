package ru.finassist.pf.feature.applock.impl.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.applock.api.AppLockRoutes
import javax.inject.Inject

class AppLockEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<AppLockRoutes.Security> { SecurityScreen(onBack = { navigator.back() }, onChangeCode = { navigator.navigate(AppLockRoutes.Change) }) }
        composable<AppLockRoutes.Change> { ChangePasscodeScreen(onDone = { navigator.back() }, onBack = { navigator.back() }) }
        composable<AppLockRoutes.Confirm> { entry ->
            val route = entry.toRoute<AppLockRoutes.Confirm>()
            ConfirmScreen(reason = route.reason, title = route.title, onBack = { navigator.back() }, onConfirmed = { navigator.back() })
        }
        // Setup is not a destination: the app shell shows SetupScreen until a passcode exists.
    }
}
