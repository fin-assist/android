package ru.finassist.pf.feature.statements.impl.di

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
import ru.finassist.pf.feature.statements.impl.ui.ResultScreen
import ru.finassist.pf.feature.statements.impl.ui.UnreadLinesScreen
import ru.finassist.pf.feature.statements.impl.ui.UploadScreen
import javax.inject.Inject

class StatementsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<StatementsRoutes.Upload> { entry ->
            val route = entry.toRoute<StatementsRoutes.Upload>()
            UploadScreen(
                firstRun = route.firstRun,
                onBack = { navigator.back() },
                onLater = { navigator.finishTo(OperationsRoutes.Feed) },
                // The guide is replaced by the result: «back» from the result goes where the upload was opened from.
                onDone = { id -> navigator.navigate(StatementsRoutes.Result(id)) { popUpTo<StatementsRoutes.Upload> { inclusive = true } } },
            )
        }
        composable<StatementsRoutes.Result> {
            ResultScreen(
                onDone = { navigator.finishTo(OperationsRoutes.Feed) },
                onOpenSearch = { filter -> navigator.navigate(OperationsRoutes.Search(filter)) },
                onOpenUnread = { id -> navigator.navigate(StatementsRoutes.UnreadLines(uploadId = id)) },
                onOpenAnalytics = { navigator.finishTo(AnalyticsRoutes.Home) },
                onUploadAnother = { navigator.navigate(StatementsRoutes.Upload()) { popUpTo<StatementsRoutes.Result> { inclusive = true } } },
            )
        }
        composable<StatementsRoutes.History> {
            HistoryScreen(
                onBack = { navigator.back() },
                onUpload = { navigator.navigate(StatementsRoutes.Upload()) },
                onOpenUnread = { id -> navigator.navigate(StatementsRoutes.UnreadLines(uploadId = id)) },
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
