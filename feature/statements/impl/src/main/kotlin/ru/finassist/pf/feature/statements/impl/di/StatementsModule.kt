package ru.finassist.pf.feature.statements.impl.di

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.statements.api.StatementsRepository
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import ru.finassist.pf.feature.statements.impl.ui.HistoryScreen
import ru.finassist.pf.feature.statements.impl.ui.LocalExternalStatementSelection
import ru.finassist.pf.feature.statements.impl.ui.ResultScreen
import ru.finassist.pf.feature.statements.impl.ui.UnreadLinesScreen
import ru.finassist.pf.feature.statements.impl.ui.UploadScreen
import ru.finassist.pf.feature.statements.impl.ui.UploadViewModel
import javax.inject.Inject

class StatementsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<StatementsRoutes.Upload> { entry ->
            val route = entry.toRoute<StatementsRoutes.Upload>()
            val upload: UploadViewModel = hiltViewModel(entry)
            val consume = LocalExternalStatementSelection.current
            val lifecycle by entry.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
            LaunchedEffect(route.externalRequestId, lifecycle, consume != null) {
                if (lifecycle == Lifecycle.State.RESUMED) {
                    route.externalRequestId?.let { consume?.invoke(it) }?.let(upload::onExternalFilePicked)
                }
            }
            UploadScreen(
                firstRun = route.firstRun,
                vm = upload,
                onBack = { navigator.back() },
                onLater = { navigator.finishTo(OperationsRoutes.Feed) },
                onOpenHistory = {
                    navigator.navigate(StatementsRoutes.History) {
                        popUpTo<StatementsRoutes.Upload> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                // The guide is replaced by the result: «back» from the result goes where the upload was opened from.
                onDone = { id -> navigator.navigate(StatementsRoutes.Result(id)) { popUpTo<StatementsRoutes.Upload> { inclusive = true } } },
            )
        }
        composable<StatementsRoutes.Result> {
            ResultScreen(
                onDone = { navigator.finishTo(OperationsRoutes.Feed) },
                onOpenSearch = { filter -> navigator.navigate(OperationsRoutes.Search(filter)) },
                onOpenUnread = { id, othersLoaded -> navigator.navigate(StatementsRoutes.UnreadLines(uploadId = id, othersLoaded = othersLoaded)) },
                onOpenAnalytics = { navigator.finishTo(AnalyticsRoutes.Home) },
                onUploadAnother = { navigator.navigate(StatementsRoutes.Upload()) { popUpTo<StatementsRoutes.Result> { inclusive = true } } },
                // Opened from the history, the upload went back to it under the result: no second copy on top.
                onOpenHistory = {
                    navigator.navigate(StatementsRoutes.History) {
                        popUpTo<StatementsRoutes.Result> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<StatementsRoutes.History> {
            HistoryScreen(
                onBack = { navigator.back() },
                onUpload = { navigator.navigate(StatementsRoutes.Upload()) },
                onOpenUnread = { id, othersLoaded -> navigator.navigate(StatementsRoutes.UnreadLines(uploadId = id, othersLoaded = othersLoaded)) },
            )
        }
        composable<StatementsRoutes.UnreadLines> {
            UnreadLinesScreen(onBack = { navigator.back() })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StatementsModule {
    @Binds abstract fun repository(impl: StatementsRepositoryImpl): StatementsRepository

    @Binds @IntoSet
    abstract fun entry(impl: StatementsEntry): FeatureEntry
}
