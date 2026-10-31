package ru.finassist.pf.ui

import androidx.compose.runtime.key
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.feature.availability.impl.ui.AvailabilityScreens
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.finassist.pf.core.designsystem.components.PfTabBar
import ru.finassist.pf.core.designsystem.components.pfTestRoot
import ru.finassist.pf.core.designsystem.components.PfMainTabs
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.NavControllerNavigator
import ru.finassist.pf.core.storage.AppPreferences
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
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
    gate: ServiceGate,
    availabilityScreens: AvailabilityScreens,
    preferences: AppPreferences,
    flags: FeatureFlags,
    onScreenOpened: (routePattern: String?) -> Unit,
    onSessionShown: (SessionState) -> Unit = {},
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
        // Breadcrumb of the value this composition switches on (FIN-63: tells «the session never reached the UI»
        // from «the UI saw it and did not move»).
        LaunchedEffect(sessionState) { onSessionShown(sessionState) }
        // Setup stays on screen until its own flow (code + biometric offer) says it is done, even though the
        // lock state flips to Unlocked as soon as the code is saved. Saveable: survives rotation on the offer.
        var settingUp by rememberSaveable { mutableStateOf(false) }
        // A configured code that is locked ends setup: after process death on the biometric offer the restored
        // flag must not reopen «Придумайте код-пароль» — that would let anyone replace the code without unlocking.
        LaunchedEffect(lockState) {
            when (lockState) {
                LockState.NotConfigured -> settingUp = true
                LockState.Locked -> settingUp = false
                else -> Unit
            }
        }
        val gateState by gate.state.collectAsStateWithLifecycle()
        val reopened by gate.reopened.collectAsStateWithLifecycle()
        val blocked = gateState != ServiceGate.State.Open
        // pfTestRoot: test tags become resource ids for UI tests (.maestro/); dialogs and sheets set it themselves.
        Box(Modifier.fillMaxSize().background(PfTheme.colors.bg).pfTestRoot()) {
            // After maintenance the screens are created anew: whatever failed under the overlay loads again (FIN-22). The
            // generation is in the layer keys too: a layer key reused by the new tree would get the view model store
            // the old tree clears on dispose.
            key(reopened) {
                CompositionLocalProvider(LocalSuppressPopups provides (blocked || LocalSuppressPopups.current)) {
                    Box(if (blocked) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                        when (val s = sessionState) {
                            SessionState.Unknown -> Unit
                            SessionState.SignedOut -> LayerScope("auth-$reopened") { AuthLayer(entries, session, onScreenOpened) }
                            is SessionState.SignedIn -> when {
                                (settingUp && lockState != LockState.Locked) || lockState == LockState.NotConfigured ->
                                    LayerScope("setup-${s.userId}-$reopened") {
                                        LayerBreadcrumb(APP_LOCK_SETUP, onScreenOpened)
                                        appLockScreens.Setup(onDone = { settingUp = false })
                                    }
                                else -> {
                                    val locked = lockState == LockState.Locked
                                    // Main stays composed under the lock so the back stack survives; while locked it is
                                    // hidden from TalkBack and its dialogs and sheets are not shown.
                                    CompositionLocalProvider(LocalSuppressPopups provides (locked || blocked)) {
                                        LayerScope("main-${s.userId}-$reopened") {
                                            MainLayer(
                                                entries, session, flags, userId = s.userId, onScreenOpened = onScreenOpened,
                                                active = !locked,
                                                modifier = if (locked) Modifier.clearAndSetSemantics { } else Modifier,
                                            )
                                        }
                                    }
                                    if (locked) {
                                        // A pointer handler on the overlay root makes it the hit target: taps never reach the app below.
                                        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
                                            LayerScope("unlock-$reopened") {
                                                LayerBreadcrumb(APP_LOCK_UNLOCK, onScreenOpened)
                                                appLockScreens.Unlock()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Update or maintenance (FIN-22): over everything — sign-in, the lock and the main screens alike.
            if (blocked) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
                    LayerScope("availability") {
                        LayerBreadcrumb(if (gateState is ServiceGate.State.UpdateRequired) AVAILABILITY_UPDATE else AVAILABILITY_MAINTENANCE, onScreenOpened)
                        availabilityScreens.Overlay()
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthLayer(entries: Set<FeatureEntry>, session: SessionRepository, onScreenOpened: (String?) -> Unit) {
    val controller = rememberNavController()
    ScreenBreadcrumbs(controller, onScreenOpened)
    val navigator = remember(controller) { NavControllerNavigator(controller) }
    val reason = remember { session.consumeSignOutReason() }
    NavHost(controller, startDestination = AuthRoutes.Phone(reason)) {
        entries.forEach { entry -> with(entry) { install(navigator) } }
    }
}

/**
 * Reports every destination the user lands on by its route pattern (`…AnalyticsRoutes.Period/{params}`): the
 * class of the route, never the arguments it was opened with. Feeds the screen trail of crash reports.
 *
 * While [active] is `false` (the main layer under the lock screen) nothing is reported: the user is on the lock
 * screen, which reports itself. When the layer becomes active again its current destination is reported anew.
 */
@Composable
private fun ScreenBreadcrumbs(controller: NavHostController, onScreenOpened: (String?) -> Unit, active: Boolean = true) {
    val isActive by rememberUpdatedState(active)
    val report by rememberUpdatedState(onScreenOpened)
    DisposableEffect(controller) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (isActive) report(destination.route)
        }
        controller.addOnDestinationChangedListener(listener)
        onDispose { controller.removeOnDestinationChangedListener(listener) }
    }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive) controller.currentDestination?.let { report(it.route) }
        wasActive = active
    }
}

/** Reports a screen shown outside navigation (app lock setup and unlock) when it appears. */
@Composable
private fun LayerBreadcrumb(routePattern: String, onScreenOpened: (String?) -> Unit) {
    LaunchedEffect(Unit) { onScreenOpened(routePattern) }
}

/** Route-like names of the app lock screens, for [screenSlug][ru.finassist.pf.core.tracking.screenSlug]. */
private const val APP_LOCK_SETUP = "AppLock.Setup"
private const val APP_LOCK_UNLOCK = "AppLock.Unlock"
private const val AVAILABILITY_UPDATE = "Availability.Update"
private const val AVAILABILITY_MAINTENANCE = "Availability.Maintenance"

/** Routes of [PfMainTabs], by index. */
private val tabRoutes: List<Any> = listOf(OperationsRoutes.Feed, AnalyticsRoutes.Home, AssistantRoutes.Chat(), ProfileRoutes.Home)

@Composable
private fun MainLayer(
    entries: Set<FeatureEntry>,
    session: SessionRepository,
    flags: FeatureFlags,
    userId: String,
    onScreenOpened: (String?) -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val controller = rememberNavController()
    ScreenBreadcrumbs(controller, onScreenOpened, active)
    val navigator = remember(controller) { NavControllerNavigator(controller) }
    // Right after registration the first run continues with the statement upload (step 4 of 4) — unless the
    // upload screen is switched off by `statements.upload`.
    LaunchedEffect(userId) {
        if (session.consumeJustRegistered() && flags.isEnabled(Flag.STATEMENTS_UPLOAD)) {
            controller.navigate(StatementsRoutes.Upload(firstRun = true))
        }
    }
    val backStack by controller.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val assistantEnabled = remember(flags) { flags.isEnabled(Flag.ASSISTANT) }
    val tabs = PfMainTabs.filter { assistantEnabled || it.id != "assistant" }
    val routes = tabRoutes.filterIndexed { index, _ -> assistantEnabled || index != 2 }
    val activeTab = when {
        destination == null -> -1
        destination.hasRoute<OperationsRoutes.Feed>() -> 0
        destination.hasRoute<AnalyticsRoutes.Home>() -> 1
        // Cards can push a mode-specific chat above Analytics or Profile. It is a detail screen,
        // not the saved Assistant tab: showing a bar there would restore its parent tab with that chat on top.
        destination.hasRoute<AssistantRoutes.Chat>() && controller.previousBackStackEntry?.destination?.hasRoute<OperationsRoutes.Feed>() == true ->
            tabs.indexOfFirst { it.id == "assistant" }
        destination.hasRoute<ProfileRoutes.Home>() -> tabs.indexOfFirst { it.id == "profile" }
        else -> -1
    }
    Column(modifier.fillMaxSize()) {
        NavHost(controller, startDestination = OperationsRoutes.Feed, modifier = Modifier.weight(1f)) {
            entries.forEach { entry -> with(entry) { install(navigator) } }
        }
        if (activeTab >= 0) {
            PfTabBar(
                items = tabs,
                active = activeTab,
                onSelect = { index -> if (index != activeTab) navigator.openTab(routes[index]) },
            )
        }
    }
}
