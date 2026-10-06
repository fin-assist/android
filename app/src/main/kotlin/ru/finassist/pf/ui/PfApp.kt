package ru.finassist.pf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.finassist.pf.core.designsystem.components.PfTabBar
import ru.finassist.pf.core.designsystem.components.TabItem
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.NavControllerNavigator
import ru.finassist.pf.core.storage.AppPreferences
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.applock.impl.ui.AppLockScreens
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.profile.api.ProfileRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes

/**
 * Root of the UI. Three layers chosen by state, not by navigation:
 * 1. no session → the sign-in graph (phone → call → consent);
 * 2. session without a passcode → passcode setup (first run only; sign-out always clears the code);
 * 3. session + passcode → the main graph with the tab bar, covered by the unlock overlay while locked.
 *
 * Each layer has its own NavController, so switching layers resets the back stack for free.
 */
@Composable
fun PfApp(
    entries: Set<FeatureEntry>,
    session: SessionRepository,
    appLock: AppLock,
    appLockScreens: AppLockScreens,
    preferences: AppPreferences,
) {
    val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = null)
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    PfTheme(darkTheme = dark) {
        val sessionState by session.state.collectAsStateWithLifecycle(initialValue = SessionState.Unknown)
        val lockState by appLock.state.collectAsStateWithLifecycle()
        // Setup stays on screen until its own flow (code + biometric offer) says it is done, even though the
        // lock state flips to Unlocked as soon as the code is saved.
        var settingUp by remember { mutableStateOf(false) }
        LaunchedEffect(lockState) { if (lockState == LockState.NotConfigured) settingUp = true }
        Box(Modifier.fillMaxSize().background(PfTheme.colors.bg)) {
            when (val s = sessionState) {
                SessionState.Unknown -> Unit
                SessionState.SignedOut -> AuthLayer(entries, session)
                is SessionState.SignedIn -> when {
                    settingUp || lockState == LockState.NotConfigured -> appLockScreens.Setup(onDone = { settingUp = false })
                    else -> {
                        MainLayer(entries, session, userId = s.userId)
                        if (lockState == LockState.Locked) appLockScreens.Unlock()
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthLayer(entries: Set<FeatureEntry>, session: SessionRepository) {
    val controller = rememberNavController()
    val navigator = remember(controller) { NavControllerNavigator(controller) }
    val reason = remember { session.consumeSignOutReason() }
    NavHost(controller, startDestination = AuthRoutes.Phone(reason)) {
        entries.forEach { entry -> with(entry) { install(navigator) } }
    }
}

private val tabs = listOf(
    TabItem(PfIcons.LIST, "Операции"),
    TabItem(PfIcons.BAR_CHART, "Аналитика"),
    TabItem(PfIcons.USER, "Профиль"),
)

private val tabRoutes: List<Any> = listOf(OperationsRoutes.Feed, AnalyticsRoutes.Home, ProfileRoutes.Home)

@Composable
private fun MainLayer(entries: Set<FeatureEntry>, session: SessionRepository, userId: String) {
    val controller = rememberNavController()
    val navigator = remember(controller) { NavControllerNavigator(controller) }
    // Right after registration the first run continues with the statement upload (step 4 of 4).
    LaunchedEffect(userId) {
        if (session.consumeJustRegistered()) controller.navigate(StatementsRoutes.Upload(firstRun = true))
    }
    val backStack by controller.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val activeTab = when {
        destination == null -> -1
        destination.hasRoute<OperationsRoutes.Feed>() -> 0
        destination.hasRoute<AnalyticsRoutes.Home>() -> 1
        destination.hasRoute<ProfileRoutes.Home>() -> 2
        else -> -1
    }
    Column(Modifier.fillMaxSize()) {
        NavHost(controller, startDestination = OperationsRoutes.Feed, modifier = Modifier.weight(1f)) {
            entries.forEach { entry -> with(entry) { install(navigator) } }
        }
        if (activeTab >= 0) {
            PfTabBar(
                items = tabs,
                active = activeTab,
                onSelect = { index -> if (index != activeTab) navigator.openTab(tabRoutes[index]) },
            )
        }
    }
}
