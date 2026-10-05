package ru.finassist.pf.feature.assistant.impl.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import javax.inject.Inject

class AssistantEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<AssistantRoutes.Chat> {
            val vm: ChatViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFlags(); vm.loadLimit(); vm.resumeStream() }
            // No consent yet: the consent screen opens on top; the chat waits underneath with the question kept.
            LaunchedEffect(state.needsConsent) { if (state.needsConsent) { vm.onConsentHandled(); navigator.navigate(AssistantRoutes.AiConsent) } }
            ChatScreen(
                state = state,
                vm = vm,
                onBack = { navigator.back() },
                onOperations = { f -> navigator.navigate(OperationsRoutes.Search(f)) },
                onAnalytics = { p -> navigator.navigate(AnalyticsRoutes.Analytics(p), popUpTo = OperationsRoutes.Feed) },
            )
        }
        composable<AssistantRoutes.AiConsent> {
            val vm: AiConsentViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) navigator.back() }
            AiConsentScreen(state = state, vm = vm, onBack = { navigator.back() })
        }
    }
}
