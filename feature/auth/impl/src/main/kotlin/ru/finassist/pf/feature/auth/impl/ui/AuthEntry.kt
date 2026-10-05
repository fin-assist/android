package ru.finassist.pf.feature.auth.impl.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.auth.api.AuthRoutes
import javax.inject.Inject

/** Nested graph of the login flow; the view model is shared by its three screens. */
@Serializable data object AuthGraph
@Serializable private data object CallRoute
@Serializable private data object ConsentRoute

class AuthEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        navigation<AuthGraph>(startDestination = AuthRoutes.Phone()) {
            composable<AuthRoutes.Phone> { entry ->
                val vm = sharedViewModel(entry, navController)
                val state by vm.state.collectAsStateWithLifecycle()
                val route = entry.toRoute<AuthRoutes.Phone>()
                HandleNav(vm, navigator)
                PhoneScreen(
                    state = state,
                    notice = route.notice,
                    onPhoneChanged = vm::onPhoneChanged,
                    onContinue = vm::onContinue,
                    onDeletedAcknowledged = { navigator.resetTo(AuthRoutes.Phone()) },
                )
            }
            composable<CallRoute> { entry ->
                val vm = sharedViewModel(entry, navController)
                val state by vm.state.collectAsStateWithLifecycle()
                HandleNav(vm, navigator)
                // Returning from the dialer / background: re-open the status stream (first event = full state).
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.observe() }
                CallScreen(state = state, onBack = vm::onChangePhone, onCallAgain = vm::onCallAgain, onChangePhone = vm::onChangePhone)
            }
            composable<ConsentRoute> { entry ->
                val vm = sharedViewModel(entry, navController)
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                HandleNav(vm, navigator)
                ConsentScreen(
                    state = state,
                    onBack = { navigator.back() },
                    onChecked = vm::onConsentChecked,
                    onCreate = vm::onCreateAccount,
                    onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                )
            }
        }
    }

    @Composable
    private fun sharedViewModel(entry: NavBackStackEntry, navController: NavController): AuthViewModel {
        val parent = remember(entry) { navController.getBackStackEntry<AuthGraph>() }
        return hiltViewModel(parent)
    }

    @Composable
    private fun HandleNav(vm: AuthViewModel, navigator: Navigator) {
        val nav by vm.nav.collectAsStateWithLifecycle()
        LaunchedEffect(nav) {
            when (nav) {
                AuthViewModel.Nav.ToCall -> navigator.navigate(CallRoute)
                AuthViewModel.Nav.ToConsent -> navigator.navigate(ConsentRoute)
                AuthViewModel.Nav.ToPhone -> navigator.navigate(AuthRoutes.Phone(), popUpTo = AuthRoutes.Phone(), inclusive = true)
                null -> return@LaunchedEffect
            }
            vm.navConsumed()
        }
    }
}
