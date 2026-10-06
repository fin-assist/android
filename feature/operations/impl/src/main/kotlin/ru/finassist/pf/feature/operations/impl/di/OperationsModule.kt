package ru.finassist.pf.feature.operations.impl.di

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.core.navigation.PfNavTypes
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.OperationsRepository
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.impl.data.CategoriesRepositoryImpl
import ru.finassist.pf.feature.operations.impl.data.OperationsRepositoryImpl
import ru.finassist.pf.feature.operations.impl.ui.DetailScreen
import ru.finassist.pf.feature.operations.impl.ui.FeedScreen
import ru.finassist.pf.feature.operations.impl.ui.SearchScreen
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

class OperationsEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<OperationsRoutes.Feed> {
            FeedScreen(
                onOpenSearch = { navigator.navigate(OperationsRoutes.Search()) },
                onOpenOperation = { id -> navigator.navigate(OperationsRoutes.Detail(id)) },
                onUpload = { navigator.navigate(StatementsRoutes.Upload()) },
            )
        }
        composable<OperationsRoutes.Search>(typeMap = PfNavTypes.MAP) {
            SearchScreen(onBack = { navigator.back() }, onOpenOperation = { id -> navigator.navigate(OperationsRoutes.Detail(id)) })
        }
        composable<OperationsRoutes.Detail> {
            DetailScreen(onBack = { navigator.back() })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OperationsModule {
    @Binds abstract fun categories(impl: CategoriesRepositoryImpl): CategoriesRepository

    @Binds abstract fun operations(impl: OperationsRepositoryImpl): OperationsRepository

    @Binds @IntoSet
    abstract fun entry(impl: OperationsEntry): FeatureEntry
}
