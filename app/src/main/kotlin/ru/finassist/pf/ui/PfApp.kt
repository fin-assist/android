package ru.finassist.pf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.finassist.pf.core.designsystem.components.PfTabBar
import ru.finassist.pf.core.designsystem.components.TabItem
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.designsystem.theme.ThemeMode
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.applock.impl.ui.LockScreen
import ru.finassist.pf.feature.applock.impl.ui.SetupScreen
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.PhoneScreenNotice
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.auth.impl.ui.AuthGraph
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.profile.api.AppTheme
import ru.finassist.pf.feature.profile.api.ProfileRoutes
import ru.finassist.pf.navigation.AppNavigator

/**
 * Root of the UI. Three states (mvp-scope «Вход и аккаунт»): logged out → auth graph; logged in without a
 * passcode → setup; logged in → main graph behind the lock gate.
 */
@Composable
fun PfApp(vm: AppStateViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val mode = when (theme) { AppTheme.System -> ThemeMode.System; AppTheme.Light -> ThemeMode.Light; AppTheme.Dark -> ThemeMode.Dark }
    PfTheme(mode) {
        Box(Modifier.fillMaxSize().background(PfTheme.colors.bg)) {
            when (val s = session) {
                SessionState.Unknown -> Unit
                SessionState.LoggedOut -> AuthHost(vm)
                is SessionState.LoggedIn -> {
                    val hasPasscode by vm.hasPasscode.collectAsStateWithLifecycle()
                    val locked by vm.locked.collectAsStateWithLifecycle()
                    when (hasPasscode) {
                        null -> Unit
                        false -> SetupScreen()
                        true -> {
                            MainHost(vm, key = s.userId)
                            if (locked) LockScreen()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthHost(vm: AppStateViewModel) {
    val controller = rememberNavController()
    val navigator = remember(controller) { AppNavigator(controller) }
    val notice by vm.signOutNotice.collectAsStateWithLifecycle()
    NavHost(navController = controller, startDestination = AuthGraph) {
        vm.entries.forEach { entry -> with(entry) { install(navigator, controller) } }
    }
    LaunchedEffect(notice) {
        if (notice != PhoneScreenNotice.None) navigator.resetTo(AuthRoutes.Phone(notice))
    }
}

@Composable
private fun MainHost(vm: AppStateViewModel, key: String) {
    val controller = rememberNavController()
    val navigator = remember(controller, key) { AppNavigator(controller) }
    val backStack by controller.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val tabIndex = when {
        destination == null -> -1
        destination.hasRoute<OperationsRoutes.Feed>() -> 0
        destination.hasRoute<AnalyticsRoutes.Analytics>() -> 1
        destination.hasRoute<ProfileRoutes.Profile>() -> 2
        else -> -1
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavHost(navController = controller, startDestination = OperationsRoutes.Feed) {
                vm.entries.forEach { entry -> with(entry) { install(navigator, controller) } }
            }
        }
        if (tabIndex >= 0) {
            PfTabBar(
                items = listOf(TabItem("list", "Операции"), TabItem("bar-chart", "Аналитика"), TabItem("user", "Профиль")),
                active = tabIndex,
                onSelect = { i ->
                    val route: Any = when (i) { 0 -> OperationsRoutes.Feed; 1 -> AnalyticsRoutes.Analytics(); else -> ProfileRoutes.Profile }
                    // Back from «Аналитика» or «Профиль» returns to «Операции» (README «Платформа: Android»).
                    controller.navigate(route) { popUpTo(OperationsRoutes.Feed) { saveState = i == 0 }; launchSingleTop = true; restoreState = i == 0 }
                },
            )
        }
    }
}
