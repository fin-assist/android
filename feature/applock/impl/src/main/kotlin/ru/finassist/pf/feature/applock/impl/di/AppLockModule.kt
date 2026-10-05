package ru.finassist.pf.feature.applock.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.impl.domain.AppLockImpl
import ru.finassist.pf.feature.applock.impl.ui.AppLockEntry

@Module
@InstallIn(SingletonComponent::class)
abstract class AppLockModule {
    @Binds abstract fun appLock(impl: AppLockImpl): AppLock
    @Binds @IntoSet abstract fun entry(impl: AppLockEntry): FeatureEntry
}
