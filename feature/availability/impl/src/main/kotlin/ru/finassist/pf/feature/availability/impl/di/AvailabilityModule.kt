package ru.finassist.pf.feature.availability.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.feature.availability.impl.ui.AvailabilityScreens
import ru.finassist.pf.feature.availability.impl.ui.AvailabilityScreensImpl

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AvailabilityModule {
    @Binds abstract fun screens(impl: AvailabilityScreensImpl): AvailabilityScreens
}
