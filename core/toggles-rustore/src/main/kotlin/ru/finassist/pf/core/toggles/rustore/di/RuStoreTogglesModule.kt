package ru.finassist.pf.core.toggles.rustore.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.rustore.RuStoreFeatureFlags

@Module
@InstallIn(SingletonComponent::class)
abstract class RuStoreTogglesModule {
    @Binds abstract fun featureFlags(impl: RuStoreFeatureFlags): FeatureFlags
}
