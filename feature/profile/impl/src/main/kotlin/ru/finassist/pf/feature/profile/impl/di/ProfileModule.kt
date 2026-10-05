package ru.finassist.pf.feature.profile.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.feature.profile.api.ThemeRepository
import ru.finassist.pf.feature.profile.impl.data.ThemeRepositoryImpl
import ru.finassist.pf.feature.profile.impl.ui.ProfileEntry

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileModule {
    @Binds abstract fun themeRepository(impl: ThemeRepositoryImpl): ThemeRepository
    @Binds @IntoSet abstract fun entry(impl: ProfileEntry): FeatureEntry
}
