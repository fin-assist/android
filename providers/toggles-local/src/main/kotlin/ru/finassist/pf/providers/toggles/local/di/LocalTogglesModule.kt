package ru.finassist.pf.providers.toggles.local.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.FlagOverrides
import ru.finassist.pf.providers.toggles.local.LocalFeatureFlags

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalTogglesModule {
    @Binds abstract fun flags(impl: LocalFeatureFlags): FeatureFlags
    @Binds abstract fun overrides(impl: LocalFeatureFlags): FlagOverrides
}
