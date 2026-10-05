package ru.finassist.pf.feature.analytics.impl.ui

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
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

class AnalyticsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<AnalyticsRoutes.Analytics>(typeMap = AnalyticsRoutes.Analytics.typeMap) {
            val vm: AnalyticsViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            // Flags, the assistant limit and recalculated numbers are re-read on every return to the tab.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFlags(); vm.loadLimit(); if (state.data != null) vm.load() }
            AnalyticsScreen(
                state = state,
                vm = vm,
                onUpload = { navigator.navigate(StatementsRoutes.ImportGuide(first = state.data?.hasData == false)) },
                onAsk = { navigator.navigate(AssistantRoutes.Chat(transferMode = state.transferMode.wire)) },
                onSearch = { f -> navigator.navigate(OperationsRoutes.Search(f)) },
                onUnreadLines = { from, to -> navigator.navigate(StatementsRoutes.UnreadLines(from = from, to = to)) },
            )
        }
    }
}
