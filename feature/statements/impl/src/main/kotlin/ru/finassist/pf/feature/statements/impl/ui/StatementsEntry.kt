package ru.finassist.pf.feature.statements.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

class StatementsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<StatementsRoutes.ImportGuide> { entry ->
            val route = entry.toRoute<StatementsRoutes.ImportGuide>()
            val vm: ImportViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFlags() }
            when (val phase = state.phase) {
                is ImportViewModel.Phase.Done -> ImportResultScreen(
                    result = phase.result,
                    fileName = phase.fileName,
                    uploadId = phase.uploadId,
                    // Result → tabs: drop the import screen so «назад» from the tab does not return here.
                    onAnalytics = { navigator.navigate(AnalyticsRoutes.Analytics(), popUpTo = OperationsRoutes.Feed) },
                    onOperations = { navigator.navigate(OperationsRoutes.Feed, popUpTo = OperationsRoutes.Feed) },
                    onUncategorized = { f -> navigator.navigate(OperationsRoutes.Search(f), popUpTo = OperationsRoutes.Feed) },
                    onHistory = { navigator.navigate(StatementsRoutes.UploadHistory, popUpTo = StatementsRoutes.ImportGuide(route.first), inclusive = true) },
                    onUnreadLines = { navigator.navigate(StatementsRoutes.UnreadLines(uploadId = phase.uploadId)) },
                    onAnotherFile = vm::backToGuide,
                    onGuide = vm::backToGuide,
                    onBack = { navigator.back() },
                )
                is ImportViewModel.Phase.Failed -> ImportErrorScreen(
                    failure = phase.failure,
                    fileName = phase.fileName,
                    onAnotherFile = vm::backToGuide,
                    onGuide = vm::backToGuide,
                    onBack = vm::backToGuide,
                )
                else -> ImportGuideScreen(state = state, vm = vm, first = route.first, onBack = { navigator.back() })
            }
        }
        composable<StatementsRoutes.UploadHistory> {
            val vm: UploadHistoryViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFlags(); vm.load() }
            UploadHistoryScreen(
                state = state,
                vm = vm,
                onBack = { navigator.back() },
                onUpload = { navigator.navigate(StatementsRoutes.ImportGuide()) },
                onUnreadLines = { id -> navigator.navigate(StatementsRoutes.UnreadLines(uploadId = id)) },
            )
        }
        // A dialog destination keeps the previous screen visible under the sheet.
        dialog<StatementsRoutes.UnreadLines>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) {
            val vm: UnreadLinesViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            UnreadLinesSheet(state = state, vm = vm, onDismiss = { navigator.back() })
        }
    }
}
