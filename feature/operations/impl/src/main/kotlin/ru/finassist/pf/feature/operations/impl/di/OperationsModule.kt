package ru.finassist.pf.feature.operations.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.impl.data.OperationsRepository
import ru.finassist.pf.feature.operations.impl.ui.OperationsEntry

@Module
@InstallIn(SingletonComponent::class)
abstract class OperationsModule {
    @Binds abstract fun categories(impl: OperationsRepository): CategoriesRepository
    @Binds @IntoSet abstract fun entry(impl: OperationsEntry): FeatureEntry
}
