package ru.finassist.pf.feature.analytics.impl.di

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
import ru.finassist.pf.core.navigation.PfNavTypes
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.analytics.impl.ui.AnalyticsActions
import ru.finassist.pf.feature.analytics.impl.ui.AnalyticsScreen
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

class AnalyticsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        fun actions(back: (() -> Unit)?) = AnalyticsActions(
            openSearch = { filter -> navigator.navigate(OperationsRoutes.Search(filter)) },
            openChat = { mode -> navigator.navigate(AssistantRoutes.Chat(mode.code)) },
            openUpload = { navigator.navigate(StatementsRoutes.Upload()) },
            openUnreadLines = { from, to -> navigator.navigate(StatementsRoutes.UnreadLines(from = from, to = to)) },
            back = back,
        )
        composable<AnalyticsRoutes.Home> {
            AnalyticsScreen(params = null, actions = actions(back = null))
        }
        composable<AnalyticsRoutes.Period>(typeMap = PfNavTypes.MAP) { entry ->
            val route = entry.toRoute<AnalyticsRoutes.Period>()
            AnalyticsScreen(params = route.params, actions = actions(back = { navigator.back() }))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AnalyticsModule {
    @Binds @IntoSet
    abstract fun entry(impl: AnalyticsEntry): FeatureEntry
}
