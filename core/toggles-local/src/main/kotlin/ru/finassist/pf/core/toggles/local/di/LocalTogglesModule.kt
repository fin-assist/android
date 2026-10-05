package ru.finassist.pf.core.toggles.local.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.local.LocalFeatureFlags

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalTogglesModule {
    @Binds abstract fun featureFlags(impl: LocalFeatureFlags): FeatureFlags
}
