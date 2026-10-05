package ru.finassist.pf.feature.statements.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.feature.statements.api.StatementsRepository
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import ru.finassist.pf.feature.statements.impl.ui.StatementsEntry

@Module
@InstallIn(SingletonComponent::class)
abstract class StatementsModule {
    @Binds abstract fun statementsRepository(impl: StatementsRepositoryImpl): StatementsRepository
    @Binds @IntoSet abstract fun entry(impl: StatementsEntry): FeatureEntry
}
