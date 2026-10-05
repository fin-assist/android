package ru.finassist.pf.feature.operations.impl.ui

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
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

class OperationsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<OperationsRoutes.Feed> {
            val vm: FeedViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFlags() }
            FeedScreen(
                state = state,
                onSearch = { navigator.navigate(OperationsRoutes.Search()) },
                onUpload = { navigator.navigate(StatementsRoutes.ImportGuide(first = state.isEmpty)) },
                onDetail = { id -> navigator.navigate(OperationsRoutes.Detail(id)) },
                onLoadMore = vm::loadMore,
                onRetry = vm::load,
            )
        }
        composable<OperationsRoutes.Search> {
            val vm: SearchViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            SearchScreen(state = state, vm = vm, onBack = { navigator.back() }, onDetail = { id -> navigator.navigate(OperationsRoutes.Detail(id)) })
        }
        composable<OperationsRoutes.Detail> {
            val vm: DetailViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            DetailScreen(state = state, vm = vm, onBack = { navigator.back() })
        }
    }
}
